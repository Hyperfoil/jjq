package io.hyperfoil.tools.jjq.vector;

import io.hyperfoil.tools.jjq.value.JsonByteSkipKernel;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Vector API container skipper (issue #211 Phase 3B).
 *
 * <p>Processes one vector of bytes per iteration (64 bytes on AVX-512,
 * 32 on AVX2, 16 on SSE): classifies quotes, backslashes, and structural
 * braces into bitmasks, computes the in-string mask with a prefix-XOR
 * scan, and updates the container depth in aggregate per block — walking
 * individual structural bytes only when the depth could reach zero or the
 * nesting limit inside the block (about once per skipped container).</p>
 *
 * <p>Blocks containing backslashes (rare in clean JSON) and block tails
 * fall back to an exact scalar scan so escape parity and offsets stay
 * bit-identical to the {@code jjq-core} scalar skipper.</p>
 */
public final class VectorByteSkipKernel implements JsonByteSkipKernel {

    private static final int FALLBACK = -2;

    private static final VectorSpecies<Byte> SPECIES = VectorSupport.preferredSpecies();
    private static final int STEP = SPECIES.length();
    private static final long BLOCK_MASK = STEP >= 64 ? -1L : ((1L << STEP) - 1);

    @Override
    public boolean isEnabled() {
        // Bitmasks fit a long only up to 64 lanes; wider species (none
        // shipped today) stay on the scalar path.
        return VectorKernels.isSupported() && STEP <= 64;
    }

    @Override
    public int skipToContainerEnd(byte[] d, int start, int end, int maxDepth) {
        int depth = 1;
        boolean inString = false;
        boolean pendingEscape = false;
        int p = start;
        while (p + STEP <= end) {
            ByteVector v = ByteVector.fromArray(SPECIES, d, p);
            if (!pendingEscape && v.compare(VectorOperators.EQ, (byte) '\\').toLong() == 0) {
                long quotes = v.compare(VectorOperators.EQ, (byte) '"').toLong();
                long opens = v.compare(VectorOperators.EQ, (byte) '{').toLong()
                        | v.compare(VectorOperators.EQ, (byte) '[').toLong();
                long closes = v.compare(VectorOperators.EQ, (byte) '}').toLong()
                        | v.compare(VectorOperators.EQ, (byte) ']').toLong();
                long inStringMask = prefixXor(quotes);
                if (inString) inStringMask ^= BLOCK_MASK;
                long openMask = opens & ~inStringMask;
                long closeMask = closes & ~inStringMask;
                long structural = openMask | closeMask;
                if (structural != 0) {
                    int closed = Long.bitCount(closeMask);
                    int opened = Long.bitCount(openMask);
                    if (depth > closed && depth + opened <= maxDepth) {
                        // Aggregate update: the depth cannot reach zero
                        // (depth > closed) or the limit (depth + opened)
                        // anywhere inside this block.
                        depth += opened - closed;
                    } else {
                        // The depth may hit zero or the limit inside this
                        // block: walk structural bytes in ascending order.
                        long s = structural;
                        while (s != 0) {
                            int bit = Long.numberOfTrailingZeros(s);
                            s &= s - 1;
                            if ((openMask >>> bit & 1) != 0) {
                                if (++depth > maxDepth) return FALLBACK;
                            } else {
                                if (--depth == 0) return p + bit + 1;
                            }
                        }
                    }
                }
                if ((Long.bitCount(quotes) & 1) != 0) inString = !inString;
                p += STEP;
                continue;
            }
            // Rare: escape bytes present (or a dangling escape from the
            // previous block). Scan this block scalar so escape parity,
            // string state, and depth stay exact.
            int limit = p + STEP;
            while (p < limit) {
                byte b = d[p];
                if (pendingEscape) {
                    pendingEscape = false;
                } else if (inString) {
                    if (b == '\\') pendingEscape = true;
                    else if (b == '"') inString = false;
                } else if (b == '"') {
                    inString = true;
                } else if (b == '{' || b == '[') {
                    if (++depth > maxDepth) return FALLBACK;
                } else if (b == '}' || b == ']') {
                    if (--depth == 0) return p + 1;
                }
                p++;
            }
            if (pendingEscape) {
                // Consume the escaped byte so the vector path never sees
                // a dangling escape at a block start.
                if (p >= end) return FALLBACK;
                pendingEscape = false;
                p++;
            }
        }
        // Scalar tail: fewer than STEP bytes remain.
        while (p < end) {
            byte b = d[p];
            if (pendingEscape) {
                pendingEscape = false;
            } else if (inString) {
                if (b == '\\') pendingEscape = true;
                else if (b == '"') inString = false;
            } else if (b == '"') {
                inString = true;
            } else if (b == '{' || b == '[') {
                if (++depth > maxDepth) return FALLBACK;
            } else if (b == '}' || b == ']') {
                if (--depth == 0) return p + 1;
            }
            p++;
        }
        return FALLBACK;
    }

    /**
     * Hillis-Steele prefix XOR: bit i of the result is the XOR of bits
     * 0..i of the input, giving the in-string parity at each position
     * (each quote byte toggles the string state).
     */
    private static long prefixXor(long x) {
        x ^= x << 1;
        x ^= x << 2;
        x ^= x << 4;
        x ^= x << 8;
        x ^= x << 16;
        x ^= x << 32;
        return x;
    }
}

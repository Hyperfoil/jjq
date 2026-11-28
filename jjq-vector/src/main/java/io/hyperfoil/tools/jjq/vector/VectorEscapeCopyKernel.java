package io.hyperfoil.tools.jjq.vector;

import io.hyperfoil.tools.jjq.value.JsonEscapeCopyKernel;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Vector API escape-copy kernel (#1B): scans and copies the clean run
 * of a UTF-8-encoded window in vector widths, and validates the encode
 * in the same pass.
 *
 * <p>Processes one vector of bytes per iteration (64 on AVX-512, 32 on
 * AVX2, 16 on SSE): classifies the slow-path set (control bytes, quote,
 * backslash) with three compares — bytes {@code >= 0x80} are multi-byte
 * UTF-8 and clean, so the input copies verbatim with no re-encoding.
 * A vector containing a slow byte stores only its clean leading lanes
 * through a prefix mask and stops storing; the sub-vector tail uses a
 * masked load whose out-of-range lanes read as zero — and zero
 * classifies as slow, so the masked lanes act as sentinels and the
 * first-true index can never exceed the tail length.</p>
 *
 * <p>The pass also watches for {@code '?'} (0x3F): the byte
 * {@code String.getBytes(UTF_8)} substitutes for lone surrogates. Any
 * 0x3F in the input — replaced surrogate or genuine question mark —
 * makes the encode unverifiable, and the kernel returns {@code -1} so
 * the caller reroutes to the char-based path, which preserves the
 * CESU-8 lone-surrogate form. The scan for it continues past the
 * first slow byte (loads only) and exits early on a hit.</p>
 */
public final class VectorEscapeCopyKernel implements JsonEscapeCopyKernel {

    private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;

    private static final int STEP = SPECIES.length();

    /** Prefix masks for k in [0..STEP]: exactly the first k lanes set. */
    private static final VectorMask<Byte>[] PREFIX = buildPrefixMasks();

    /** Even lanes (UTF-16LE low bytes) and odd lanes (high bytes). */
    private static final VectorMask<Byte> EVENS = VectorMask.fromLong(SPECIES, 0x5555555555555555L);
    private static final VectorMask<Byte> ODDS = EVENS.not();
    /** Compressed clean vectors store their first STEP/2 lanes. */
    private static final VectorMask<Byte> FIRST_HALF = VectorMask.fromLong(SPECIES,
            (1L << (STEP >> 1)) - 1);

    @SuppressWarnings("unchecked")
    private static VectorMask<Byte>[] buildPrefixMasks() {
        VectorMask<Byte>[] masks = (VectorMask<Byte>[]) new VectorMask[STEP + 1];
        for (int k = 0; k <= STEP; k++) {
            long bits = (k == 64) ? -1L : ((1L << k) - 1);
            masks[k] = VectorMask.fromLong(SPECIES, bits);
        }
        return masks;
    }

    @Override
    public boolean isEnabled() {
        // Prefix bitmasks fit a long only up to 64 lanes; wider species
        // (none shipped today) stay on the scalar path.
        return VectorKernels.isSupported() && STEP <= 64;
    }

    @Override
    public int escapeUtf16(byte[] a, int start, int end, byte[] dst, int dstOff) {
        final int chars = STEP >> 1;
        int ci = start >> 1;
        int charEnd = end >> 1;
        int p = dstOff;
        boolean escaped = false;
        while (ci + chars <= charEnd) {
            ByteVector v = ByteVector.fromArray(SPECIES, a, ci << 1);
            // Slow low bytes (control, quote, backslash) mean escapes.
            VectorMask<Byte> slowLo = v.compare(VectorOperators.LT, (byte) 0x20)
                    .or(v.compare(VectorOperators.EQ, (byte) '"'))
                    .or(v.compare(VectorOperators.EQ, (byte) '\\'))
                    .and(EVENS);
            if (!slowLo.anyTrue()
                    && !v.compare(VectorOperators.NE, (byte) 0).and(ODDS).anyTrue()) {
                // All chars clean ASCII: compress low bytes and store.
                v.compress(EVENS).intoArray(dst, p, FIRST_HALF);
                p += chars;
                ci += chars;
            } else {
                // Dirty vector: scalar transcode, exactly this vector's
                // chars. A high surrogate whose low surrogate starts the
                // next vector is deferred — the next (deliberately
                // unaligned) load reprocesses the pair whole.
                long r = transcodeScalar(a, ci, ci + chars, charEnd, dst, p);
                p = (int) (r >> 32);
                ci = (int) (r >> 1) & 0x7FFFFFFF;
                escaped |= (r & 1L) != 0;
            }
        }
        // Scalar tail: fewer than one vector of chars remains. Pair
        // lookahead may run to the end of input here.
        while (ci < charEnd) {
            char c0 = charAt16(a, ci);
            if (Character.isHighSurrogate(c0) && ci + 1 < charEnd) {
                char c1 = charAt16(a, ci + 1);
                if (Character.isLowSurrogate(c1)) {
                    p = emitPair(c0, c1, dst, p);
                    ci += 2;
                    continue;
                }
            }
            if (c0 < 0x20 || c0 == '"' || c0 == '\\') {
                escaped = true;
            }
            p = emitScalar(c0, dst, p);
            ci++;
        }
        int written = p - dstOff;
        return escaped ? -written - 1 : written;
    }

    /**
     * Scalar transcode of the chars [from, limit): escapes and 1/2/3-byte
     * UTF-8 per char, pairs when both halves lie inside the range. A
     * boundary-spanning pair (high surrogate at the last position, low
     * beyond limit but inside charEnd) defers: the loop stops before it
     * so the caller's next iteration reprocesses the pair whole. Returns
     * the packed (position, next char index, escaped-any flag).
     */
    private static long transcodeScalar(byte[] a, int from, int limit, int charEnd,
            byte[] dst, int p) {
        int ci = from;
        boolean escaped = false;
        while (ci < limit) {
            char c0 = charAt16(a, ci);
            if (Character.isHighSurrogate(c0)) {
                if (ci + 1 < limit) {
                    char c1 = charAt16(a, ci + 1);
                    if (Character.isLowSurrogate(c1)) {
                        p = emitPair(c0, c1, dst, p);
                        ci += 2;
                        continue;
                    }
                } else if (ci + 1 < charEnd && Character.isLowSurrogate(charAt16(a, ci + 1))) {
                    break; // defer the boundary pair to the next iteration
                }
            }
            if (c0 < 0x20 || c0 == '"' || c0 == '\\') {
                escaped = true;
            }
            p = emitScalar(c0, dst, p);
            ci++;
        }
        return ((long) p << 32) | ((long) ci << 1) | (escaped ? 1L : 0L);
    }

    /** The UTF-16 char at char index ci of the little-endian bytes. */
    private static char charAt16(byte[] a, int ci) {
        int b = ci << 1;
        return (char) ((a[b] & 0xFF) | ((a[b + 1] & 0xFF) << 8));
    }

    /** One char, pair handling done by the caller — mirrors BytOutput. */
    private static int emitScalar(char c, byte[] dst, int p) {
        switch (c) {
            case '"' -> { dst[p++] = '\\'; dst[p++] = '"'; }
            case '\\' -> { dst[p++] = '\\'; dst[p++] = '\\'; }
            case '\b' -> { dst[p++] = '\\'; dst[p++] = 'b'; }
            case '\f' -> { dst[p++] = '\\'; dst[p++] = 'f'; }
            case '\n' -> { dst[p++] = '\\'; dst[p++] = 'n'; }
            case '\r' -> { dst[p++] = '\\'; dst[p++] = 'r'; }
            case '\t' -> { dst[p++] = '\\'; dst[p++] = 't'; }
            default -> {
                if (c < 0x20) {
                    dst[p++] = '\\';
                    dst[p++] = 'u';
                    dst[p++] = '0';
                    dst[p++] = '0';
                    dst[p++] = (byte) Character.forDigit((c >> 4) & 0xF, 16);
                    dst[p++] = (byte) Character.forDigit(c & 0xF, 16);
                } else if (c < 0x80) {
                    dst[p++] = (byte) c;
                } else if (c < 0x800) {
                    dst[p++] = (byte) (0xC0 | (c >> 6));
                    dst[p++] = (byte) (0x80 | (c & 0x3F));
                } else {
                    // Includes lone surrogates: the CESU-8 3-byte form.
                    dst[p++] = (byte) (0xE0 | (c >> 12));
                    dst[p++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                    dst[p++] = (byte) (0x80 | (c & 0x3F));
                }
            }
        }
        return p;
    }

    /** A valid surrogate pair: 4-byte UTF-8. */
    private static int emitPair(char hi, char lo, byte[] dst, int p) {
        int cp = Character.toCodePoint(hi, lo);
        dst[p++] = (byte) (0xF0 | (cp >> 18));
        dst[p++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
        dst[p++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
        dst[p++] = (byte) (0x80 | (cp & 0x3F));
        return p;
    }

    @Override
    public int copyCleanUtf8(byte[] a, int start, int end, byte[] dst, int dstOff, boolean trusted) {
        int i = start;
        int p = dstOff;
        int firstSlow = -1;
        for (; i + STEP <= end; i += STEP) {
            ByteVector v = ByteVector.fromArray(SPECIES, a, i);
            if (!trusted && v.compare(VectorOperators.EQ, (byte) '?').anyTrue()) {
                return -1; // possible surrogate replacement: unverifiable
            }
            if (firstSlow >= 0) {
                continue; // past the break: '?' watch only, no stores
            }
            VectorMask<Byte> slow = slowMask(v);
            if (slow.anyTrue()) {
                // Once per value at the break: store only the clean lanes.
                firstSlow = i + slow.firstTrue();
                v.intoArray(dst, p, PREFIX[firstSlow - i]);
                p += firstSlow - i;
            } else {
                v.intoArray(dst, p); // verbatim: the bytes are the output
                p += STEP;
            }
        }
        int remain = end - i;
        if (remain > 0) {
            // Masked load: out-of-range lanes read as zero, which
            // classifies as slow — sentinels that bound firstTrue by
            // the tail length, so the clean count is exact.
            ByteVector v = ByteVector.fromArray(SPECIES, a, i, PREFIX[remain]);
            if (!trusted && v.compare(VectorOperators.EQ, (byte) '?').anyTrue()) {
                return -1;
            }
            if (firstSlow < 0) {
                int clean = slowMask(v).firstTrue();
                v.intoArray(dst, p, PREFIX[clean]);
                p += clean;
                if (i + clean < end) {
                    firstSlow = i + clean;
                }
            }
        }
        return firstSlow >= 0 ? firstSlow - start : end - start;
    }

    /**
     * Clean bytes are {@code >= 0x20} unsigned, minus quote and
     * backslash. Bytes have no unsigned compare: XOR 0x80 biases the
     * unsigned order onto the signed one, so unsigned {@code < 0x20}
     * becomes signed {@code < 0xA0} on the biased vector.
     */
    private static VectorMask<Byte> slowMask(ByteVector v) {
        return v.lanewise(VectorOperators.XOR, (byte) 0x80)
                .compare(VectorOperators.LT, (byte) 0xA0)
                .or(v.compare(VectorOperators.EQ, (byte) '"'))
                .or(v.compare(VectorOperators.EQ, (byte) '\\'));
    }
}

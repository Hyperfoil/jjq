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

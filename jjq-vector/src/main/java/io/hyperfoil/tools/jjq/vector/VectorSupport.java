package io.hyperfoil.tools.jjq.vector;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * Vector hardware probe for the optional {@code jjq-vector} kernels.
 *
 * <p>Stage 0: reports the preferred species only. Later stages branch
 * kernel shapes on it (512-bit tiles vs narrower fallbacks).
 */
public final class VectorSupport {

    private VectorSupport() {}

    /** Preferred byte species on this CPU and JIT. */
    public static VectorSpecies<Byte> preferredSpecies() {
        return ByteVector.SPECIES_PREFERRED;
    }

    /** Preferred species width in bits (512 on AVX-512 boxes). */
    public static int preferredBits() {
        return preferredSpecies().vectorBitSize();
    }
}

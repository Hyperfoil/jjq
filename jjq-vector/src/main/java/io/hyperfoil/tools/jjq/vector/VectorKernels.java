package io.hyperfoil.tools.jjq.vector;

import java.util.Objects;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Optional explicit-vector kernels over raw bytes.
 *
 * <p>Strictly opt-in: nothing in {@code jjq-core} (or any default path)
 * references this class, so loading it is always a deliberate caller
 * choice. Class file version requires JDK 27+; do not load on older
 * runtimes (the module is absent from those builds by construction).
 *
 * <p>Stage 1: {@code scanVector} is an explicit Vector API kernel
 * (compare/mask/early-exit); the scalar arm stays as fallback and
 * differential oracle. Tests pin both arms to identical output.
 */
public final class VectorKernels {

    private VectorKernels() {}

    /** System property forcing the scalar fallback ({@code -Djjq.vector.enabled=false}). */
    public static final String ENABLE_PROPERTY = "jjq.vector.enabled";

    /** Preferred species, looked up once (stage-1 note: re-query if
     * kernels ever need to adapt to migration across ISAs at runtime). */
    private static final VectorSpecies<Byte> SPECIES = VectorSupport.preferredSpecies();

    /** False only when explicitly disabled; read dynamically so tests can toggle per case. */
    public static boolean isEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(ENABLE_PROPERTY, "true"));
    }

    /**
     * True when the vector arm may run: explicitly enabled and a usable
     * species present. Stage 1 callers hoist this out of hot loops.
     */
    public static boolean isSupported() {
        return isEnabled() && VectorSupport.preferredBits() >= 128;
    }

    /**
     * Index (relative to {@code off}) of the first byte needing JSON
     * escape work ({@code < 0x20} as signed bytes, {@code '"'},
     * {@code '\\'}); {@code len} when the range is clean.
     */
    public static int scanFirstEscape(byte[] v, int off, int len) {
        Objects.checkFromIndexSize(off, len, v.length);
        if (isSupported()) return scanVector(v, off, len);
        return scanScalar(v, off, len);
    }

    // Stage 1: explicit Vector API kernel (compare/mask/early-exit over
    // caller-provided arrays — no private String access, no --add-opens).
    // Operates on signed bytes so one LT catches controls and >= 0x80,
    // exactly like the scalar arm.
    static int scanVector(byte[] v, int off, int len) {
        final int step = SPECIES.length();
        int i = 0;
        for (; i + step <= len; i += step) {
            ByteVector x = ByteVector.fromArray(SPECIES, v, off + i);
            VectorMask<Byte> m = x.compare(VectorOperators.LT, (byte) 0x20)
                    .or(x.compare(VectorOperators.EQ, (byte) '"'))
                    .or(x.compare(VectorOperators.EQ, (byte) '\\'));
            if (m.anyTrue()) return i + m.firstTrue();
        }
        for (; i < len; i++) {
            byte b = v[off + i];
            if (b < 0x20 || b == '"' || b == '\\') return i;
        }
        return len;
    }

    static int scanScalar(byte[] v, int off, int len) {
        int end = off + len;
        for (int i = off; i < end; i++) {
            byte b = v[i];
            if (b < 0x20 || b == '"' || b == '\\') return i - off;
        }
        return len;
    }
}

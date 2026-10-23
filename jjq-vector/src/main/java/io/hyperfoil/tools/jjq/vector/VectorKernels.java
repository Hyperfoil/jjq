package io.hyperfoil.tools.jjq.vector;

import java.util.Objects;

/**
 * Optional explicit-vector kernels over raw bytes.
 *
 * <p>Strictly opt-in: nothing in {@code jjq-core} (or any default path)
 * references this class, so loading it is always a deliberate caller
 * choice. Class file version requires JDK 27+; do not load on older
 * runtimes (the module is absent from those builds by construction).
 *
 * <p>Stage 0: the dispatch shape only. {@link #scanFirstEscape} selects
 * a vector arm when {@link #isSupported()} and the scalar arm otherwise;
 * both arms currently share one scalar loop, so output is identical by
 * construction and the tests pin the dispatch, not speed. Stage 1
 * replaces {@code scanVector} with an explicit Vector API kernel.
 */
public final class VectorKernels {

    private VectorKernels() {}

    /** System property forcing the scalar fallback ({@code -Djjq.vector.enabled=false}). */
    public static final String ENABLE_PROPERTY = "jjq.vector.enabled";

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

    // Stage 1 replaces this body with the explicit Vector API kernel
    // (compare/mask/store/early-exit); kept separate so the dispatch
    // shape is already in place and tested.
    static int scanVector(byte[] v, int off, int len) {
        return scanScalar(v, off, len);
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

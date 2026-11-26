package io.hyperfoil.tools.jjq.value;

/**
 * Optional service-provider interface for accelerating structural JSON
 * container skipping (issue #211 Phase 3B).
 *
 * <p>Implemented by the opt-in {@code jjq-vector} module (JDK 27+,
 * Vector API) and discovered via {@link java.util.ServiceLoader}. This
 * interface lives in {@code jjq-core} so the core module keeps zero
 * compile-time dependencies: when no implementation is on the classpath
 * (the default, including GraalVM native images without the vector
 * module), {@code jjq-core} runs its portable scalar SWAR skipper
 * unchanged.</p>
 *
 * <p>Not intended for direct use by application code.</p>
 */
public interface JsonByteSkipKernel {

    /**
     * True when the accelerated path may run. Read dynamically so
     * callers and tests can toggle implementations at runtime (for
     * example via {@code -Djjq.vector.enabled=false}).
     */
    boolean isEnabled();

    /**
     * Advance from inside an already-open JSON container (depth = 1) to
     * the byte immediately past its matching closing brace or bracket.
     *
     * <p>Contract — identical to the scalar implementation in
     * {@code JsonByteSkipper}:</p>
     * <ul>
     *   <li>String values (including backslash-escaped quotes) never
     *       affect the container depth</li>
     *   <li>{@code maxDepth} bounds nesting exactly like the scalar path:
     *       exceeding it reports failure</li>
     *   <li>Returns the offset immediately past the matching close, or
     *       {@code -2} when the range ends before the container closes or
     *       the nesting depth is exceeded</li>
     * </ul>
     *
     * @param bytes    the UTF-8 JSON buffer
     * @param startPos first byte inside the container (after its opener)
     * @param end      one past the last byte of the range
     * @param maxDepth maximum tolerated nesting depth
     * @return the offset past the matching close, or -2 on failure
     */
    int skipToContainerEnd(byte[] bytes, int startPos, int end, int maxDepth);
}

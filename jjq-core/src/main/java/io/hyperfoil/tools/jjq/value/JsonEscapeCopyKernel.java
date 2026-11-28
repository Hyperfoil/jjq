package io.hyperfoil.tools.jjq.value;

/**
 * Optional service-provider interface for accelerating the clean-prefix
 * copy of UTF-8 JSON string escaping.
 *
 * <p>Implemented by the opt-in {@code jjq-vector} module (JDK 27+,
 * Vector API) and discovered via {@link java.util.ServiceLoader}. This
 * interface lives in {@code jjq-core} so the core module keeps zero
 * compile-time dependencies: when no implementation is on the classpath
 * (the default, including GraalVM native images without the vector
 * module), {@code jjq-core} runs its fused scalar loop unchanged.</p>
 *
 * <p>Not intended for direct use by application code.</p>
 */
public interface JsonEscapeCopyKernel {

    /**
     * True when the accelerated path may run. Read dynamically so
     * callers and tests can toggle implementations at runtime (for
     * example via {@code -Djjq.vector.enabled=false}).
     */
    boolean isEnabled();

    /**
     * Copy the leading clean run of {@code utf8[start..end)} into
     * {@code dst} at {@code dstOff}, stopping before the first byte
     * that needs the escape slow path: a control byte ({@code < 0x20}),
     * {@code '"'}, or {@code '\\'}.
     *
     * <p>Bytes {@code >= 0x80} are multi-byte UTF-8 sequences and are
     * clean: the input is already UTF-8, so the clean run copies
     * verbatim with no re-encoding.</p>
     *
     * <p>Unless {@code trusted} is set, the kernel also watches the
     * whole range for {@code '?'} (0x3F) — the byte
     * {@code String.getBytes(UTF_8)} substitutes for lone surrogates.
     * When any 0x3F is present the encode cannot be verified lossless,
     * and the kernel returns {@code -1} so the caller can verify (for
     * example by a UTF-8 round-trip against the source string) and
     * retry with {@code trusted}, or reroute to a surrogate-safe path.
     * The kernel may have written a clean prefix before hitting the
     * 0x3F; the caller must not advance its position on {@code -1} —
     * the partial copy is then simply overwritten.</p>
     *
     * <p>Contract — identical to the byte-level scanner in
     * {@code BytOutput.escapeWindowBytes}:</p>
     * <ul>
     *   <li>each clean byte copies verbatim (one dst byte per input
     *       byte)</li>
     *   <li>returns the number of bytes copied: {@code end - start} when
     *       the whole range is clean, otherwise the offset of the first
     *       slow byte relative to {@code start}; or {@code -1} on an
     *       untrusted 0x3F (a clean prefix may have been written: do
     *       not advance the destination on -1)</li>
     *   <li>never reads or writes past the returned count</li>
     *   <li>the caller guarantees {@code dst} room for the whole range</li>
     * </ul>
     *
     * @param utf8   UTF-8 window from {@code String.getBytes(UTF_8)}
     * @param start  first byte index to copy
     * @param end    one past the last byte to consider
     * @param dst    destination byte buffer
     * @param dstOff first destination index
     * @param trusted true when the caller verified the encode lossless
     *                (0x3F bytes are genuine question marks)
     * @return bytes copied before the first slow byte (or the full
     *         range), or -1 on an unverified encode
     */
    int copyCleanUtf8(byte[] utf8, int start, int end, byte[] dst, int dstOff, boolean trusted);
}

package io.hyperfoil.tools.jjq.jakarta;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Buffered-first request-body reads (issue #215).
 *
 * <p>The JDK {@link InputStream#readAllBytes()} always allocates its fixed
 * 8192-byte scratch and copies into an exact-size result. Against streams
 * whose {@code read} method cannot be inlined into the caller — real
 * request streams such as VertxInputStream — the scratch escapes escape
 * analysis, costing kilobytes of garbage per small request (a fully
 * inlined {@code ByteArrayInputStream} hides this by scalar-replacing the
 * scratch, which is why the benchmark carries an opaque-stream row).</p>
 */
final class IoSupport {
    /** Trust available() up to this size; larger bodies grow from here. */
    private static final int MAX_TRUSTED_HINT = 1 << 20;

    private IoSupport() {}

    /**
     * Read the whole stream into one array. Request bodies are typically
     * already buffered, so {@code available()} sizes the first buffer
     * exactly: the common case allocates one right-sized array and never
     * copies — when the buffer fills, a single-byte probe distinguishes
     * end-of-stream (return the buffer directly) from a short hint (grow
     * and continue).
     *
     * @param in the stream to drain (not closed)
     * @return the stream's contents, exact-length
     * @throws IOException if reading fails
     */
    static byte[] readAll(InputStream in) throws IOException {
        int hint = in.available();
        byte[] buf = new byte[Math.min(Math.max(hint, 64), MAX_TRUSTED_HINT)];
        int n = 0;
        while (true) {
            if (n == buf.length) {
                int next = in.read();
                if (next < 0) return buf;
                buf = Arrays.copyOf(buf, buf.length + (buf.length >> 1) + 64);
                buf[n++] = (byte) next;
                continue;
            }
            int r = in.read(buf, n, buf.length - n);
            if (r < 0) {
                return n == buf.length ? buf : Arrays.copyOf(buf, n);
            }
            n += r;
        }
    }
}

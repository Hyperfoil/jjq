package io.hyperfoil.tools.jjq.value;

import java.util.Arrays;

/**
 * Experimental stage-1 structural indexer (simdjson-style split prototype):
 * one scalar pass over the document bytes producing the offsets of
 * structural characters ({@code { } [ ] , :}) outside strings, opening
 * quotes, and scalar-token starts (first byte of numbers and literals),
 * while validating UTF-8 inline. Grammar validation stays a stage-2 job,
 * mirroring simdjson's division of labor.
 *
 * <p>Storage mirrors the native contract: the caller retains an
 * {@code int[]} plus the returned count alongside the original
 * {@code byte[]}; no trimming copy is made. Size the buffer generously
 * (every byte is a usable upper bound).</p>
 *
 * <p>Not wired into any parse path yet: this exists to measure whether
 * index production is cheap enough for staged parsing to pay off. See the
 * twitter.json comparison before promoting this beyond an experiment.</p>
 */
public final class StructuralIndexer {

    private StructuralIndexer() {}

    /**
     * Index structural positions in {@code buf[off, end)} into a retained buffer.
     *
     * @return the number of meaningful entries written to {@code out}
     * @throws IllegalArgumentException on malformed UTF-8, unclosed
     *         strings, unescaped control characters, or an undersized
     *         output buffer
     */
    public static int indexInto(byte[] buf, int off, int end, int[] out) {
        int n = 0;
        int i = off;
        // True when the previous position ends a token: document start,
        // structural characters, whitespace, or a complete string. A
        // scalar byte there starts a token and is recorded.
        boolean boundary = true;
        while (i < end) {
            int b = buf[i] & 0xFF;
            switch (b) {
                case '{', '}', '[', ']', ',', ':' -> {
                    if (n == out.length) throw overflow(n);
                    out[n++] = i;
                    i++;
                    boundary = true;
                }
                case '"' -> {
                    if (n == out.length) throw overflow(n);
                    out[n++] = i;
                    i = skipString(buf, i + 1, end);
                    boundary = true;
                }
                case ' ', '\t', '\n', '\r' -> {
                    i++;
                    boundary = true;
                }
                default -> {
                    if (b < 0x20) throw new IllegalArgumentException(
                            "Unescaped control character at offset " + i);
                    if (boundary) {
                        if (n == out.length) throw overflow(n);
                        out[n++] = i;
                        boundary = false;
                    }
                    if (b < 0x80) {
                        i++;
                    } else {
                        i = utf8Step(buf, i, end);
                    }
                }
            }
        }
        return n;
    }

    /**
     * Index structural positions in {@code buf[off, end)}, trimming copy.
     * Test and debug convenience; hot paths retain a buffer and call
     * {@link #indexInto}.
     */
    static int[] index(byte[] buf, int off, int end) {
        int[] out = new int[Math.max(16, end - off)];
        return Arrays.copyOf(out, indexInto(buf, off, end, out));
    }

    private static IllegalArgumentException overflow(int n) {
        return new IllegalArgumentException("Structural index buffer too small at " + n);
    }

    /** Skip a string body starting after the opening quote; return past the closing quote. */
    private static int skipString(byte[] buf, int i, int end) {
        while (i < end) {
            int b = buf[i] & 0xFF;
            if (b == '"') return i + 1;
            if (b == '\\') {
                i += 2;
                if (i > end) break;
                continue;
            }
            if (b < 0x20) throw new IllegalArgumentException(
                    "Unescaped control character in string at offset " + i);
            if (b < 0x80) {
                i++;
            } else {
                i = utf8Step(buf, i, end);
            }
        }
        throw new IllegalArgumentException("Unclosed string in document");
    }

    /** Validate one UTF-8 sequence at {@code i}; return the next offset. Rules mirror Utf8. */
    private static int utf8Step(byte[] buf, int i, int end) {
        int b = buf[i] & 0xFF;
        if (b < 0xC2) throw new IllegalArgumentException("Invalid UTF-8 at offset " + i);
        if (b < 0xE0) {
            if (i + 1 >= end || !isContinuation(buf[i + 1])) {
                throw new IllegalArgumentException("Invalid UTF-8 at offset " + i);
            }
            return i + 2;
        }
        if (b < 0xF0) {
            if (i + 2 >= end
                    || !isContinuation(buf[i + 1]) || !isContinuation(buf[i + 2])
                    || (b == 0xE0 && (buf[i + 1] & 0xFF) < 0xA0)
                    || (b == 0xED && (buf[i + 1] & 0xFF) > 0x9F)) {
                throw new IllegalArgumentException("Invalid UTF-8 at offset " + i);
            }
            return i + 3;
        }
        if (b <= 0xF4) {
            if (i + 3 >= end
                    || !isContinuation(buf[i + 1]) || !isContinuation(buf[i + 2])
                    || !isContinuation(buf[i + 3])
                    || (b == 0xF0 && (buf[i + 1] & 0xFF) < 0x90)
                    || (b == 0xF4 && (buf[i + 1] & 0xFF) > 0x8F)) {
                throw new IllegalArgumentException("Invalid UTF-8 at offset " + i);
            }
            return i + 4;
        }
        throw new IllegalArgumentException("Invalid UTF-8 at offset " + i);
    }

    private static boolean isContinuation(byte b) {
        return (b & 0xC0) == 0x80;
    }
}

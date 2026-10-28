package io.hyperfoil.tools.jjq.value;

/**
 * Strict UTF-8 validation for byte-based entry points.
 *
 * <p>Decoders ({@code new String(bytes, UTF_8)}) silently replace malformed
 * input with U+FFFD, corrupting user files without notice. Callers that must
 * fail loudly (strict JSON parsing, YAML stream/file loading) pre-validate
 * with {@link #validate} and report the byte offset; lenient paths skip
 * validation and keep replacement behavior.</p>
 */
public final class Utf8 {

    private Utf8() {}

    /**
     * Validate a byte range as UTF-8: ASCII, 2-byte, 3-byte (rejecting
     * overlongs and surrogates) and 4-byte (rejecting overlongs and
     * codepoints above U+10FFFF) sequences.
     *
     * @param bytes  the buffer
     * @param offset the first byte to check
     * @param length the number of bytes to check
     * @return -1 when valid, otherwise the offset of the first malformed
     *         sequence's lead byte (truncated sequences report their start)
     */
    public static int validate(byte[] bytes, int offset, int length) {
        int end = offset + length;
        // Tiny inputs stay on the original scalar path verbatim so its
        // compiled shape (and inline budget) does not change; larger inputs
        // skip 8 ASCII bytes per iteration in a separate helper.
        if (length < 64) return validateScalar(bytes, offset, end);
        int i = offset;
        while (i + 8 <= end) {
            if (!SwarUtil.isAscii8(SwarUtil.loadLong(bytes, i))) break;
            i += 8;
        }
        return validateScalar(bytes, i, end);
    }

    /**
     * Validate a byte range as UTF-8: ASCII, 2-byte, 3-byte (rejecting
     * overlongs and surrogates) and 4-byte (rejecting overlongs and
     * codepoints above U+10FFFF) sequences.
     *
     * @param bytes the buffer
     * @param i     the first byte to check
     * @param end   one past the last byte to check
     * @return -1 when valid, otherwise the offset of the first malformed
     *         sequence's lead byte (truncated sequences report their start)
     */
    private static int validateScalar(byte[] bytes, int i, int end) {
        while (i < end) {
            int b = bytes[i] & 0xFF;
            if (b < 0x80) {
                i++;
                continue;
            }
            int next = sequenceEnd(bytes, i, end);
            if (next < 0) return i;
            i = next;
        }
        return -1;
    }

    /**
     * Validate the single UTF-8 sequence starting at {@code i} and return
     * the offset just past it, or -1 when malformed. Shared with
     * {@code StructuralIndexer} so the sequence rules exist in exactly one
     * place (issue #190); each caller translates -1 into its own error
     * form. Truncated sequences report -1 and the caller reports {@code i}
     * as the lead offset.
     *
     * @param bytes the buffer
     * @param i     the sequence lead byte
     * @param end   one past the last byte of the range
     * @return the offset past the sequence, or -1 when malformed
     */
    static int sequenceEnd(byte[] bytes, int i, int end) {
        int b = bytes[i] & 0xFF;
        if (b < 0xC2) return -1; // continuation byte or overlong C0/C1
        if (b < 0xE0) {
            // 2-byte: one continuation expected
            if (i + 1 >= end || !isContinuation(bytes[i + 1])) return -1;
            return i + 2;
        } else if (b < 0xF0) {
            // 3-byte: two continuations; E0 needs second >= A0
            // (overlong), ED needs second <= 9F (surrogates)
            if (i + 2 >= end
                    || !isContinuation(bytes[i + 1]) || !isContinuation(bytes[i + 2])
                    || (b == 0xE0 && (bytes[i + 1] & 0xFF) < 0xA0)
                    || (b == 0xED && (bytes[i + 1] & 0xFF) > 0x9F)) {
                return -1;
            }
            return i + 3;
        } else if (b <= 0xF4) {
            // 4-byte: three continuations; F0 needs second >= 90
            // (overlong), F4 needs second <= 8F (above U+10FFFF)
            if (i + 3 >= end
                    || !isContinuation(bytes[i + 1]) || !isContinuation(bytes[i + 2])
                    || !isContinuation(bytes[i + 3])
                    || (b == 0xF0 && (bytes[i + 1] & 0xFF) < 0x90)
                    || (b == 0xF4 && (bytes[i + 1] & 0xFF) > 0x8F)) {
                return -1;
            }
            return i + 4;
        } else {
            return -1; // F5-FF can never start a sequence
        }
    }

    static boolean isContinuation(byte b) {
        return (b & 0xC0) == 0x80;
    }
}

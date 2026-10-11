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
        int i = offset;
        while (i < end) {
            int b = bytes[i] & 0xFF;
            if (b < 0x80) {
                i++;
                continue;
            }
            if (b < 0xC2) return i; // continuation byte or overlong C0/C1
            if (b < 0xE0) {
                // 2-byte: one continuation expected
                if (i + 1 >= end || !isContinuation(bytes[i + 1])) return i;
                i += 2;
            } else if (b < 0xF0) {
                // 3-byte: two continuations; E0 needs second >= A0
                // (overlong), ED needs second <= 9F (surrogates)
                if (i + 2 >= end
                        || !isContinuation(bytes[i + 1]) || !isContinuation(bytes[i + 2])
                        || (b == 0xE0 && (bytes[i + 1] & 0xFF) < 0xA0)
                        || (b == 0xED && (bytes[i + 1] & 0xFF) > 0x9F)) {
                    return i;
                }
                i += 3;
            } else if (b <= 0xF4) {
                // 4-byte: three continuations; F0 needs second >= 90
                // (overlong), F4 needs second <= 8F (above U+10FFFF)
                if (i + 3 >= end
                        || !isContinuation(bytes[i + 1]) || !isContinuation(bytes[i + 2])
                        || !isContinuation(bytes[i + 3])
                        || (b == 0xF0 && (bytes[i + 1] & 0xFF) < 0x90)
                        || (b == 0xF4 && (bytes[i + 1] & 0xFF) > 0x8F)) {
                    return i;
                }
                i += 4;
            } else {
                return i; // F5-FF can never start a sequence
            }
        }
        return -1;
    }

    private static boolean isContinuation(byte b) {
        return (b & 0xC0) == 0x80;
    }
}

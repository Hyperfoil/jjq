package io.hyperfoil.tools.jjq.value;

import java.util.Arrays;

/**
 * Growable byte buffer for direct UTF-8 JSON serialization.
 * Used by {@link JqValue#appendToBytes(BytOutput)} to serialize JSON
 * directly to bytes without intermediate String/StringBuilder allocation.
 *
 * <p>Package-private — callers use {@link JqValues#serializeToBytes(JqValue)}.</p>
 */
final class BytOutput {

    private static final byte[] NULL_BYTES = {'n', 'u', 'l', 'l'};
    private static final byte[] TRUE_BYTES = {'t', 'r', 'u', 'e'};
    private static final byte[] FALSE_BYTES = {'f', 'a', 'l', 's', 'e'};

    byte[] buf;
    int pos;

    BytOutput(int initialCapacity) {
        this.buf = new byte[initialCapacity];
    }

    void reset() {
        pos = 0;
    }

    // --- Primitive writes ---

    void writeByte(int b) {
        ensureCapacity(1);
        buf[pos++] = (byte) b;
    }

    void writeBytes(byte[] src, int off, int len) {
        ensureCapacity(len);
        System.arraycopy(src, off, buf, pos, len);
        pos += len;
    }

    void writeBytes(byte[] src) {
        writeBytes(src, 0, src.length);
    }

    void writeNull() { writeBytes(NULL_BYTES); }
    void writeTrue() { writeBytes(TRUE_BYTES); }
    void writeFalse() { writeBytes(FALSE_BYTES); }

    // --- ASCII string write (field names, number formatting) ---

    void writeAsciiString(String s) {
        int len = s.length();
        ensureCapacity(len);
        for (int i = 0; i < len; i++) {
            buf[pos++] = (byte) s.charAt(i);
        }
    }

    // --- Long to ASCII digits ---

    void writeLong(long v) {
        if (v == 0) {
            writeByte('0');
            return;
        }
        if (v == Long.MIN_VALUE) {
            writeAsciiString("-9223372036854775808");
            return;
        }
        boolean neg = v < 0;
        if (neg) {
            writeByte('-');
            v = -v;
        }
        // Count digits
        int digits = 0;
        long tmp = v;
        while (tmp > 0) { digits++; tmp /= 10; }
        ensureCapacity(digits);
        int end = pos + digits;
        pos = end;
        while (v > 0) {
            buf[--end] = (byte) ('0' + (v % 10));
            v /= 10;
        }
    }

    // --- UTF-8 encoding ---

    /**
     * Encode a Java String as UTF-8 bytes (raw, no JSON escaping).
     * Used for number formatting and other non-string contexts.
     */
    void writeUTF8(String s) {
        int len = s.length();
        ensureCapacity(len * 3); // worst case UTF-8
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                buf[pos++] = (byte) c;
            } else if (c < 0x800) {
                buf[pos++] = (byte) (0xC0 | (c >> 6));
                buf[pos++] = (byte) (0x80 | (c & 0x3F));
            } else if (Character.isHighSurrogate(c) && i + 1 < len) {
                char low = s.charAt(++i);
                int cp = Character.toCodePoint(c, low);
                buf[pos++] = (byte) (0xF0 | (cp >> 18));
                buf[pos++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                buf[pos++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                buf[pos++] = (byte) (0x80 | (cp & 0x3F));
            } else {
                buf[pos++] = (byte) (0xE0 | (c >> 12));
                buf[pos++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                buf[pos++] = (byte) (0x80 | (c & 0x3F));
            }
        }
    }

    /**
     * Write a JSON-escaped string value as UTF-8 bytes (with surrounding quotes).
     * Handles escaping of control characters, backslash, and double quote.
     */
    void writeJsonString(String s) {
        writeByte('"');
        escapeJsonToBytes(s);
        writeByte('"');
    }

    /**
     * JSON-escape a string and write as UTF-8 bytes (without quotes).
     */
    void escapeJsonToBytes(String s) {
        int len = s.length();
        ensureCapacity(len * 3 + 12); // worst case: all escaped + UTF-8 expansion
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c != '"' && c != '\\') {
                // Common case: no escaping needed — encode as UTF-8
                if (c < 0x80) {
                    buf[pos++] = (byte) c;
                } else if (c < 0x800) {
                    buf[pos++] = (byte) (0xC0 | (c >> 6));
                    buf[pos++] = (byte) (0x80 | (c & 0x3F));
                } else if (Character.isHighSurrogate(c) && i + 1 < len) {
                    char low = s.charAt(++i);
                    int cp = Character.toCodePoint(c, low);
                    buf[pos++] = (byte) (0xF0 | (cp >> 18));
                    buf[pos++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                    buf[pos++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                    buf[pos++] = (byte) (0x80 | (cp & 0x3F));
                } else {
                    buf[pos++] = (byte) (0xE0 | (c >> 12));
                    buf[pos++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                    buf[pos++] = (byte) (0x80 | (c & 0x3F));
                }
            } else {
                // Escape sequences (all ASCII)
                switch (c) {
                    case '"' -> { buf[pos++] = '\\'; buf[pos++] = '"'; }
                    case '\\' -> { buf[pos++] = '\\'; buf[pos++] = '\\'; }
                    case '\b' -> { buf[pos++] = '\\'; buf[pos++] = 'b'; }
                    case '\f' -> { buf[pos++] = '\\'; buf[pos++] = 'f'; }
                    case '\n' -> { buf[pos++] = '\\'; buf[pos++] = 'n'; }
                    case '\r' -> { buf[pos++] = '\\'; buf[pos++] = 'r'; }
                    case '\t' -> { buf[pos++] = '\\'; buf[pos++] = 't'; }
                    default -> {
                         // Control character: \\u00XX
                        buf[pos++] = '\\';
                        buf[pos++] = 'u';
                        buf[pos++] = '0';
                        buf[pos++] = '0';
                        buf[pos++] = (byte) Character.forDigit((c >> 4) & 0xF, 16);
                        buf[pos++] = (byte) Character.forDigit(c & 0xF, 16);
                    }
                }
            }
        }
    }

    byte[] toByteArray() {
        return Arrays.copyOf(buf, pos);
    }

    private void ensureCapacity(int needed) {
        if (pos + needed > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, pos + needed));
        }
    }
}

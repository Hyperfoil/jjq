package io.hyperfoil.tools.jjq.value;

import java.util.Arrays;

/**
 * Growable byte buffer for direct UTF-8 JSON serialization.
 * Used by {@link JqValue#appendToBytes(BytOutput)} to serialize JSON
 * directly to bytes without intermediate String/StringBuilder allocation.
 *
 * <p>Public so generated mapping classes can emit JSON bytes directly.
 * Obtain an instance via {@link JqValues#acquireByteBuffer()} and release it
 * with {@link JqValues#releaseByteBuffer(BytOutput)} or
 * {@link JqValues#writeByteBuffer(BytOutput, java.io.OutputStream)}.</p>
 */
public final class BytOutput {

    private static final byte[] NULL_BYTES = {'n', 'u', 'l', 'l'};
    private static final byte[] TRUE_BYTES = {'t', 'r', 'u', 'e'};
    private static final byte[] FALSE_BYTES = {'f', 'a', 'l', 's', 'e'};

    /**
     * Retained scratch for getChars-based string encoding (issue #157):
     * 8K chars (16 KB) per buffer, so retention stays bounded no matter
     * how large any single string is. Longer strings encode in windows;
     * windows never split a surrogate pair (back off one char).
     */
    private static final int CHAR_SCRATCH_SIZE = 8192;

    byte[] buf;
    int pos;
    private char[] charScratch;

    public BytOutput(int initialCapacity) {
        this.buf = new byte[initialCapacity];
    }

    /** Create a BytOutput backed by the given buffer. Used for pre-sized serialization. */
    public BytOutput(byte[] buffer) {
        this.buf = buffer;
    }

    public void reset() {
        pos = 0;
    }

    // --- Primitive writes ---

    public void writeByte(int b) {
        ensureCapacity(1);
        buf[pos++] = (byte) b;
    }

    public void writeBytes(byte[] src, int off, int len) {
        ensureCapacity(len);
        System.arraycopy(src, off, buf, pos, len);
        pos += len;
    }

    public void writeBytes(byte[] src) {
        writeBytes(src, 0, src.length);
    }

    public void writeNull() { writeBytes(NULL_BYTES); }
    public void writeTrue() { writeBytes(TRUE_BYTES); }
    public void writeFalse() { writeBytes(FALSE_BYTES); }

    // --- ASCII string write (field names, number formatting) ---

    public void writeAsciiString(String s) {
        int len = s.length();
        ensureCapacity(len);
        for (int i = 0; i < len; i++) {
            buf[pos++] = (byte) s.charAt(i);
        }
    }

    /**
     * Write a double in jq's plain notation for common values.
     * Integral values serialize without a decimal point (3.0 becomes "3").
     */
    public void writeDouble(double d) {
        if (Double.doubleToRawLongBits(d) == Long.MIN_VALUE) {
            writeAsciiString("-0");
            return;
        }
        long asLong = (long) d;
        if ((double) asLong == d) {
            writeLong(asLong);
        } else if ((d > 1e-3 && d < 1e15) || (d < -1e-3 && d > -1e15)) {
            writeAsciiString(Double.toString(d));
        } else {
            writeAsciiString(java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString());
        }
    }

    // --- Long to ASCII digits ---

    public void writeLong(long v) {
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
     * Copies through the bounded scratch via {@code getChars} like the
     * escape path (issue #162 experiment): same pollution immunity,
     * same window discipline for surrogate pairs.
     */
    public void writeUTF8(String s) {
        int len = s.length();
        ensureCapacity(len * 3); // worst case UTF-8
        char[] scratch = charScratch();
        int off = 0;
        while (off < len) {
            int wEnd = Math.min(off + CHAR_SCRATCH_SIZE, len);
            if (wEnd < len && Character.isHighSurrogate(s.charAt(wEnd - 1))) {
                wEnd--; // never split a surrogate pair across windows
            }
            s.getChars(off, wEnd, scratch, 0);
            // Hand-inlined segment encode with locals (issue #162): the
            // shared helper must stay callable from the escape path, but
            // inlining it here regressed mixed strings while forbidding
            // the inline won everywhere. Local buffer/position copies;
            // buf already sized above and scratch never aliases it.
            byte[] dst = buf;
            int p = pos;
            for (int i = 0; i < wEnd - off; i++) {
                char c = scratch[i];
                if (c < 0x80) {
                    dst[p++] = (byte) c;
                } else if (c < 0x800) {
                    dst[p++] = (byte) (0xC0 | (c >> 6));
                    dst[p++] = (byte) (0x80 | (c & 0x3F));
                } else if (Character.isHighSurrogate(c) && i + 1 < wEnd - off
                        && Character.isLowSurrogate(scratch[i + 1])) {
                    char low = scratch[++i];
                    int cp = Character.toCodePoint(c, low);
                    dst[p++] = (byte) (0xF0 | (cp >> 18));
                    dst[p++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                    dst[p++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                    dst[p++] = (byte) (0x80 | (cp & 0x3F));
                } else {
                    dst[p++] = (byte) (0xE0 | (c >> 12));
                    dst[p++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                    dst[p++] = (byte) (0x80 | (c & 0x3F));
                }
            }
            pos = p;
            off = wEnd;
        }
    }

    /**
     * Write a JSON-escaped string value as UTF-8 bytes (with surrounding quotes).
     * Handles escaping of control characters, backslash, and double quote.
     */
    public void writeJsonString(String s) {
        writeByte('"');
        escapeJsonToBytes(s);
        writeByte('"');
    }

    /**
     * JSON-escape a string and write as UTF-8 bytes (without quotes).
     * Copies the string through a bounded scratch buffer with
     * {@code getChars} (an intrinsic, no per-call-site coder profile) and
     * runs the segment scan/encode over the array (issue #157 experiment).
     * Semantics match the previous charAt loop exactly, including lone
     * surrogates (3-byte form) and window-boundary behavior.
     */
    public void escapeJsonToBytes(String s) {
        final int len = s.length();
        ensureCapacity(len * 3 + 12); // worst case: all escaped + UTF-8 expansion
        if (len <= CHAR_SCRATCH_SIZE) {
            char[] scratch = charScratch();
            s.getChars(0, len, scratch, 0);
            escapeWindow(scratch, 0, len);
            return;
        }
        char[] scratch = charScratch();
        int off = 0;
        while (off < len) {
            int wEnd = Math.min(off + CHAR_SCRATCH_SIZE, len);
            if (wEnd < len && Character.isHighSurrogate(s.charAt(wEnd - 1))) {
                wEnd--; // never split a surrogate pair across windows
            }
            s.getChars(off, wEnd, scratch, 0);
            escapeWindow(scratch, 0, wEnd - off);
            off = wEnd;
        }
    }

    /** Lazily-created bounded scratch (retained with the buffer). */
    private char[] charScratch() {
        char[] c = charScratch;
        if (c == null) {
            charScratch = c = new char[CHAR_SCRATCH_SIZE];
        }
        return c;
    }

    /**
     * Segment-based escape scan over a scratch window: scans for the next
     * escape character without writing, then bulk-flushes the clean segment.
     */
    private void escapeWindow(char[] a, int start, int end) {
        int segStart = start;

        for (int i = start; i < end; i++) {
            char c = a[i];
            if (c >= 0x20 && c != '"' && c != '\\') continue; // scan — no writes

            // Found escape character — flush clean segment before it
            if (i > segStart) {
                writeSegmentChars(a, segStart, i);
            }
            // Emit escape sequence
            ensureCapacity(6);
            switch (c) {
                case '"'  -> { buf[pos++] = '\\'; buf[pos++] = '"'; }
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
            segStart = i + 1;
        }
        // Flush remaining clean segment
        if (segStart < end) {
            writeSegmentChars(a, segStart, end);
        }
    }

    /**
     * Bulk-write a clean scratch segment (no escape characters) as UTF-8.
     * Most JSON string values are ASCII, so the inner loop is a simple byte cast.
     */
    private void writeSegmentChars(char[] a, int start, int end) {
        int segLen = end - start;
        ensureCapacity(segLen * 3); // worst case UTF-8
        // Local buffer/position copies: every buf[pos++] is two field
        // accesses in bytecode, and this loop is almost all stores.
        // Capture buf after ensureCapacity, which may replace the array.
        byte[] dst = buf;
        int p = pos;
        for (int i = start; i < end; i++) {
            char c = a[i];
            if (c < 0x80) {
                dst[p++] = (byte) c;
            } else if (c < 0x800) {
                dst[p++] = (byte) (0xC0 | (c >> 6));
                dst[p++] = (byte) (0x80 | (c & 0x3F));
            } else if (Character.isHighSurrogate(c) && i + 1 < end
                    && Character.isLowSurrogate(a[i + 1])) {
                char low = a[++i];
                int cp = Character.toCodePoint(c, low);
                dst[p++] = (byte) (0xF0 | (cp >> 18));
                dst[p++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                dst[p++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                dst[p++] = (byte) (0x80 | (cp & 0x3F));
            } else {
                dst[p++] = (byte) (0xE0 | (c >> 12));
                dst[p++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                dst[p++] = (byte) (0x80 | (c & 0x3F));
            }
        }
        pos = p;
    }

    public byte[] toByteArray() {
        return (pos == buf.length) ? buf : Arrays.copyOf(buf, pos);
    }

    private void ensureCapacity(int needed) {
        if (pos + needed > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, pos + needed));
        }
    }
}

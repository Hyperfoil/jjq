package io.hyperfoil.tools.jjq.value;

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.Objects;

/**
 * Provisional char-buffer experiment for #207. Existing serializers do not
 * use this class. Completed segments never need copying during growth.
 */
public final class CharOutput implements AutoCloseable {
    private static final int SEGMENT_CHARS = 8192;
    private static final int MAX_RETAINED_CHARS = 512 * 1024;
    private static final ThreadLocal<CharOutput> LOCAL = new ThreadLocal<>() {
        @Override
        protected CharOutput initialValue() {
            return new CharOutput();
        }
    };

    private final int segmentChars;
    private char[][] segments = new char[8][];
    private int[] lengths = new int[8];
    private char[] buffer;
    private int segment;
    private int position;
    private int completedChars;
    private int allocatedSegments = 1;
    private boolean inUse;

    public CharOutput() {
        this(SEGMENT_CHARS);
    }

    public CharOutput(int segmentChars) {
        if (segmentChars < 32) throw new IllegalArgumentException("Segment size must be at least 32");
        this.segmentChars = segmentChars;
        segments[0] = buffer = new char[segmentChars];
    }

    /** A nested acquisition gets independent storage, not the active buffer. */
    public static CharOutput acquire() {
        CharOutput out = LOCAL.get();
        if (out.inUse) out = new CharOutput();
        out.inUse = true;
        return out;
    }

    public CharOutput append(char c) {
        if (position == buffer.length) nextSegment();
        buffer[position++] = c;
        return this;
    }

    public CharOutput append(boolean value) {
        return append(value ? "true" : "false");
    }

    public CharOutput append(Object value) {
        return append(String.valueOf(value));
    }

    public CharOutput append(int value) {
        return append((long) value);
    }

    public CharOutput append(long value) {
        // Work negatively so Long.MIN_VALUE needs no overflow special case.
        long negative = value > 0 ? -value : value;
        long remaining = negative;
        int digits = 1;
        while (remaining <= -10) {
            remaining /= 10;
            digits++;
        }
        int needed = digits + (value < 0 ? 1 : 0);
        if (buffer.length - position < needed) nextSegment();
        int end = position + needed;
        position = end;
        do {
            buffer[--end] = (char) ('0' - negative % 10);
            negative /= 10;
        } while (negative != 0);
        if (value < 0) buffer[--end] = '-';
        return this;
    }

    public void appendDouble(double value) {
        if (Double.doubleToRawLongBits(value) == Long.MIN_VALUE) {
            append("-0");
            return;
        }
        long integral = (long) value;
        if ((double) integral == value) {
            append(integral);
        } else if ((value > 1e-3 && value < 1e15) || (value < -1e-3 && value > -1e15)) {
            append(Double.toString(value));
        } else {
            append(java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString());
        }
    }

    public CharOutput append(String s) {
        if (s == null) s = "null";
        return append(s, 0, s.length());
    }

    public CharOutput append(String s, int start, int end) {
        Objects.checkFromToIndex(start, end, s.length());
        int length = end - start;
        if (length <= buffer.length - position) {
            s.getChars(start, end, buffer, position);
            position += length;
            return this;
        }
        while (start < end) {
            if (position == buffer.length) nextSegment();
            int n = Math.min(end - start, buffer.length - position);
            s.getChars(start, start + n, buffer, position);
            position += n;
            start += n;
        }
        return this;
    }

    public void appendJsonString(String s) {
        if (s == null) {
            append("null");
            return;
        }
        append('"');
        escapeJson(s);
        append('"');
    }

    /** Copies clean text directly into the destination, then scans that copy. */
    public boolean escapeJson(String s) {
        int offset = 0;
        while (offset < s.length()) {
            if (position == buffer.length) nextSegment();
            int n = Math.min(s.length() - offset, buffer.length - position);
            int start = position;
            s.getChars(offset, offset + n, buffer, start);
            int end = start + n;
            for (int i = start; i < end; i++) {
                char c = buffer[i];
                if (c < 0x20 || c == '"' || c == '\\') {
                    position = i;
                    escapeRemainder(s, offset + i - start);
                    return true;
                }
            }
            position = end;
            offset += n;
        }
        return false;
    }

    private void escapeRemainder(String s, int first) {
        int start = first;
        for (int i = first; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c != '"' && c != '\\') continue;
            if (start < i) append(s, start, i);
            appendEscape(c);
            start = i + 1;
        }
        if (start < s.length()) append(s, start, s.length());
    }

    private void appendEscape(char c) {
        if (buffer.length - position < 6) nextSegment();
        buffer[position++] = '\\';
        char escape = switch (c) {
            case '"', '\\' -> c;
            case '\b' -> 'b';
            case '\f' -> 'f';
            case '\n' -> 'n';
            case '\r' -> 'r';
            case '\t' -> 't';
            default -> 'u';
        };
        buffer[position++] = escape;
        if (escape == 'u') {
            buffer[position++] = '0';
            buffer[position++] = '0';
            buffer[position++] = Character.forDigit((c >> 4) & 0xF, 16);
            buffer[position++] = Character.forDigit(c & 0xF, 16);
        }
    }

    /** Tree fallback for the prototype. Generated scalar writers bypass it. */
    public void appendValue(JqValue value) {
        switch (value) {
            case null -> append("null");
            case JqNull ignored -> append("null");
            case JqString s -> s.appendTo(this);
            case JqBoolean b -> append(b.booleanValue());
            case JqNumber n -> {
                if (n.isLongBacked()) append(n.longValue());
                else append(n.toJsonString());
            }
            case JqArray array -> {
                append('[');
                for (int i = 0; i < array.size(); i++) {
                    if (i > 0) append(',');
                    appendValue(array.get(i));
                }
                append(']');
            }
            case JqObject object -> appendObject(object);
        }
    }

    private void appendObject(JqObject object) {
        append('{');
        if (object.isArrayBacked()) {
            for (int i = 0; i < object.size(); i++) {
                if (i > 0) append(',');
                appendKey(object.keyAt(i));
                appendValue(object.valueAt(i));
            }
        } else {
            boolean separator = false;
            for (var entry : object.objectValue().entrySet()) {
                if (separator) append(',');
                separator = true;
                appendKey(entry.getKey());
                appendValue(entry.getValue());
            }
        }
        append('}');
    }

    private void appendKey(String key) {
        String quoted = JqValues.internedJsonKey(key);
        if (quoted != null) append(quoted);
        else {
            appendJsonString(key);
            append(':');
        }
    }

    private void nextSegment() {
        lengths[segment] = position;
        completedChars = Math.addExact(completedChars, position);
        segment++;
        if (segment == segments.length) {
            segments = Arrays.copyOf(segments, segments.length * 2);
            lengths = Arrays.copyOf(lengths, lengths.length * 2);
        }
        char[] next = segments[segment];
        if (next == null) {
            segments[segment] = next = new char[segmentChars];
            allocatedSegments++;
        }
        buffer = next;
        position = 0;
    }

    public int length() {
        return Math.addExact(completedChars, position);
    }

    /** Writes without closing or flushing the caller's Writer. */
    public void writeTo(Writer writer) throws IOException {
        for (int i = 0; i < segment; i++) writer.write(segments[i], 0, lengths[i]);
        writer.write(buffer, 0, position);
    }

    @Override
    public String toString() {
        if (segment == 0) return new String(buffer, 0, position);
        char[] result = new char[length()];
        int offset = 0;
        for (int i = 0; i < segment; i++) {
            System.arraycopy(segments[i], 0, result, offset, lengths[i]);
            offset += lengths[i];
        }
        System.arraycopy(buffer, 0, result, offset, position);
        return new String(result);
    }

    @Override
    public void close() {
        if ((long) allocatedSegments * segmentChars > MAX_RETAINED_CHARS) {
            char[] first = segments[0];
            segments = new char[8][];
            lengths = new int[8];
            segments[0] = first;
            allocatedSegments = 1;
        }
        segment = 0;
        position = 0;
        completedChars = 0;
        buffer = segments[0];
        inUse = false;
    }
}

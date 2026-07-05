package io.hyperfoil.tools.jjq.value;

import java.nio.charset.StandardCharsets;

/**
 * Immutable JSON string value. Supports three internal representations:
 * <ul>
 *   <li><b>Eager</b> (via {@code of(String)}): the Java String is available immediately.</li>
 *   <li><b>Deferred from String</b> (via {@code deferred(String, ...)}): holds a reference
 *       to the original JSON input String with start/end offsets.</li>
 *   <li><b>Deferred from bytes</b> (via {@code deferredBytes(byte[], ...)}): holds a reference
 *       to the original UTF-8 byte array with start/end offsets.</li>
 * </ul>
 * In both deferred modes, the Java String is materialized lazily on first
 * {@link #stringValue()} call. Serialization via {@link #appendTo} can write
 * the source directly (zero-copy) for strings without escapes.
 */
public final class JqString implements JqValue {
    private static final long serialVersionUID = 1L;
    // Eager: value is set, source is null
    // Deferred: value is null (until materialized), source is String or byte[]
    private volatile String value;
    private final Object source;      // String or byte[] or null
    private final int start;          // content start offset (after opening ")
    private final int end;            // content end offset (before closing ")
    private final boolean hasEscapes; // whether source range contains backslash escapes

    private JqString(String value, Object source, int start, int end, boolean hasEscapes) {
        this.value = value;
        this.source = source;
        this.start = start;
        this.end = end;
        this.hasEscapes = hasEscapes;
    }

    /** Create an eager JqString with the value already materialized. */
    public static JqString of(String value) {
        return new JqString(value, null, 0, 0, false);
    }

    /**
     * Create a deferred JqString that references a region of the JSON input String.
     * The Java String is materialized lazily on first access.
     * Package-private -- used by the String parser only.
     */
    static JqString deferred(String source, int start, int end, boolean hasEscapes) {
        return new JqString(null, source, start, end, hasEscapes);
    }

    /**
     * Create a deferred JqString that references a region of a UTF-8 byte array.
     * The Java String is materialized lazily on first access.
     * Package-private -- used by the byte parser only.
     */
    static JqString deferredBytes(byte[] source, int start, int end, boolean hasEscapes) {
        return new JqString(null, source, start, end, hasEscapes);
    }

    @Override
    public Type type() { return Type.STRING; }

    @Override
    public String stringValue() {
        String v = value;
        if (v == null) {
            v = materialize();
            value = v;
        }
        return v;
    }

    private String materialize() {
        if (source instanceof String s) {
            return hasEscapes ? unescapeJson(s, start, end) : s.substring(start, end);
        } else if (source instanceof byte[] bytes) {
            String raw = new String(bytes, start, end - start, StandardCharsets.UTF_8);
            return hasEscapes ? unescapeJson(raw, 0, raw.length()) : raw;
        }
        throw new IllegalStateException("Deferred JqString with no source");
    }

    /**
     * Process JSON escape sequences in a source range, producing the unescaped Java String.
     * Handles standard JSON escape sequences including unicode escapes.
     */
    static String unescapeJson(String source, int start, int end) {
        var sb = new StringBuilder(end - start);
        int pos = start;
        while (pos < end) {
            char c = source.charAt(pos);
            if (c == '\\' && pos + 1 < end) {
                pos++;
                char esc = source.charAt(pos);
                switch (esc) {
                    case '"', '\\', '/' -> sb.append(esc);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        int cp = Integer.parseInt(source, pos + 1, pos + 5, 16);
                        sb.append((char) cp);
                        pos += 4;
                    }
                    default -> sb.append(esc);
                }
            } else {
                sb.append(c);
            }
            pos++;
        }
        return sb.toString();
    }

    /**
     * Append the JSON-escaped form of a string to the given StringBuilder.
     * Does not include surrounding quotes.
     * <p>
     * Uses a fast path: scans for characters that need escaping and appends
     * clean segments in bulk via {@code sb.append(s, start, end)}, only
     * falling back to per-character handling at escape points.
     */
    static void escapeJson(String s, StringBuilder sb) {
        final int len = s.length();
        int start = 0; // start of current clean segment
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c != '"' && c != '\\') continue; // common case: no escaping needed
            // Flush the clean segment before this character
            if (i > start) sb.append(s, start, i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    // Control character below 0x20
                    sb.append("\\u00");
                    sb.append(Character.forDigit((c >> 4) & 0xF, 16));
                    sb.append(Character.forDigit(c & 0xF, 16));
                }
            }
            start = i + 1;
        }
        // Flush remaining clean segment
        if (start < len) sb.append(s, start, len);
    }

    /**
     * Format a string for jq error messages: uses JSON-style escaping for
     * control characters, matching jq's error message convention.
     */
    public static String formatForError(String s) {
        var sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u00");
                        sb.append(Character.forDigit((c >> 4) & 0xF, 16));
                        sb.append(Character.forDigit(c & 0xF, 16));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    @Override
    public String toJsonString() {
        String v = value;
        if (v != null && !needsEscaping(v)) {
            return "\"" + v + "\"";
        }
        return JqValues.serialize(this);
    }

    /** Check if a string contains any characters that require JSON escaping. */
    static boolean needsEscaping(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == '"' || c == '\\') return true;
        }
        return false;
    }

    @Override
    public void appendTo(StringBuilder sb) {
        sb.append('"');
        String v = value;
        if (v != null) {
            // Materialized (eager or already accessed deferred) -- standard path
            escapeJson(v, sb);
        } else if (source instanceof String s && !hasEscapes) {
            // Deferred from String, no escapes -- ZERO-COPY: write source directly
            // Source content between quotes is already valid JSON (no escape sequences)
            sb.append(s, start, end);
        } else if (source instanceof byte[] bytes && !hasEscapes) {
            // Deferred from bytes, no escapes -- materialize once, then append
            // (For ASCII byte ranges this constructs the String; future SWAR
            // optimization could append bytes directly for pure-ASCII content)
            v = stringValue();
            sb.append(v);
        } else if (source != null) {
            // Deferred with escapes -- must materialize first, then re-escape
            // (cannot write source directly since re-escaping may differ, e.g. \/ -> /)
            v = stringValue(); // triggers materialize + caching
            escapeJson(v, sb);
        }
        sb.append('"');
    }

    @Override
    public void appendToBytes(BytOutput out) {
        out.writeByte('"');
        String v = value;
        if (v != null) {
            // Materialized (eager or already accessed deferred) — escape + encode to bytes
            out.escapeJsonToBytes(v);
        } else if (source instanceof byte[] bytes && !hasEscapes) {
            // Deferred from bytes, no escapes — ZERO-COPY: raw byte copy
            // Source bytes are already valid UTF-8 JSON content (no escape sequences).
            // This is the key optimization: for the parse→query→serialize round-trip,
            // most strings are never touched by jq filters, so their original bytes
            // can be copied directly to the output without ever constructing a Java String.
            out.writeBytes(bytes, start, end - start);
        } else if (source instanceof String s && !hasEscapes) {
            // Deferred from String, no escapes — encode source region as UTF-8
            out.escapeJsonToBytes(s.substring(start, end));
        } else if (source != null) {
            // Deferred with escapes — materialize first, then re-escape to bytes
            v = stringValue();
            out.escapeJsonToBytes(v);
        }
        out.writeByte('"');
    }

    @Override
    public String toString() { return toJsonString(); }

    @Override
    public boolean equals(Object o) {
        return o instanceof JqString s && stringValue().equals(s.stringValue());
    }

    @Override
    public int hashCode() { return stringValue().hashCode(); }

    /**
     * Serialization proxy: materializes the deferred string before serialization.
     * This avoids serializing the entire source buffer (e.g., a 14MB JSON input)
     * for each deferred JqString — only the materialized String is written.
     */
    private Object writeReplace() {
        return new SerializedForm(stringValue());
    }

    private static class SerializedForm implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        private final String value;
        SerializedForm(String v) { this.value = v; }
        private Object readResolve() { return JqString.of(value); }
    }
}

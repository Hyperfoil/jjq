package io.hyperfoil.tools.jjq.value;

public final class JqString implements JqValue {
    private final String value;

    private JqString(String value) {
        this.value = value;
    }

    public static JqString of(String value) {
        return new JqString(value);
    }

    @Override
    public Type type() { return Type.STRING; }

    @Override
    public String stringValue() { return value; }

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
        // Fast path: if the string contains no characters that need escaping,
        // avoid StringBuilder and thread-local overhead entirely
        if (!needsEscaping(value)) {
            return "\"" + value + "\"";
        }
        return JqValues.serialize(this);
    }

    /** Check if a string contains any characters that require JSON escaping. */
    private static boolean needsEscaping(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == '"' || c == '\\') return true;
        }
        return false;
    }

    @Override
    public void appendTo(StringBuilder sb) {
        sb.append('"');
        // escapeJson uses bulk segment appending -- for clean strings it appends
        // the entire string in one sb.append(s, 0, len) call, so the needsEscaping
        // pre-check would just duplicate that scan. Skip it and let escapeJson handle both cases.
        escapeJson(value, sb);
        sb.append('"');
    }

    @Override
    public String toString() { return toJsonString(); }

    @Override
    public boolean equals(Object o) {
        return o instanceof JqString s && s.value.equals(value);
    }

    @Override
    public int hashCode() { return value.hashCode(); }
}

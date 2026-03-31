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
     */
    static void escapeJson(String s, StringBuilder sb) {
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
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
    }

    @Override
    public String toJsonString() {
        var sb = new StringBuilder();
        sb.append('"');
        escapeJson(value, sb);
        sb.append('"');
        return sb.toString();
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

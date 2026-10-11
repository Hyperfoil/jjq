package io.hyperfoil.tools.jjq.value;

public final class JqBoolean implements JqValue {
    private static final long serialVersionUID = 1L;

    public static final JqBoolean TRUE = new JqBoolean(true);
    public static final JqBoolean FALSE = new JqBoolean(false);

    private final boolean value;
    // Original source text when it differs from the typed rendering (e.g. YAML
    // `yes` resolving to true). Null on the singletons. Used only for String
    // binding; ignored by serialization, equality, and queries. Note: Java
    // serialization collapses instances to the singletons via readResolve,
    // dropping source text.
    private final String sourceText;

    private JqBoolean(boolean value) {
        this(value, null);
    }

    private JqBoolean(boolean value, String sourceText) {
        this.value = value;
        this.sourceText = sourceText;
    }

    /** Preserve singleton identity across serialization. */
    private Object readResolve() { return value ? TRUE : FALSE; }

    public static JqBoolean of(boolean value) {
        return value ? TRUE : FALSE;
    }

    /**
     * Copy this boolean preserving its value, attaching source text
     * (see {@link JqValue#sourceText()}). Always returns a fresh instance,
     * leaving the singletons canonical and source-free.
     *
     * @param sourceText the original source text (may be null to clear)
     * @return a boolean equal to this one, carrying {@code sourceText}
     */
    public JqBoolean withSourceText(String sourceText) {
        if (sourceText == this.sourceText) return this;
        return new JqBoolean(value, sourceText);
    }

    @Override
    public String sourceText() { return sourceText; }

    @Override
    public Type type() { return Type.BOOLEAN; }

    @Override
    public boolean booleanValue() { return value; }

    @Override
    public boolean isTruthy() { return value; }

    @Override
    public String toJsonString() { return String.valueOf(value); }

    @Override
    public void appendTo(StringBuilder sb) { sb.append(value); }

    @Override
    public void appendToBytes(BytOutput out) {
        if (value) out.writeTrue(); else out.writeFalse();
    }

    @Override
    public String toString() { return String.valueOf(value); }

    @Override
    public boolean equals(Object o) {
        return o instanceof JqBoolean b && b.value == value;
    }

    @Override
    public int hashCode() { return Boolean.hashCode(value); }
}

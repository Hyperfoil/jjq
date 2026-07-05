package io.hyperfoil.tools.jjq.value;

public final class JqBoolean implements JqValue {
    private static final long serialVersionUID = 1L;

    public static final JqBoolean TRUE = new JqBoolean(true);
    public static final JqBoolean FALSE = new JqBoolean(false);

    private final boolean value;

    private JqBoolean(boolean value) {
        this.value = value;
    }

    /** Preserve singleton identity across serialization. */
    private Object readResolve() { return value ? TRUE : FALSE; }

    public static JqBoolean of(boolean value) {
        return value ? TRUE : FALSE;
    }

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

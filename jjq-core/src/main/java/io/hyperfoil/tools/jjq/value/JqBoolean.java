package io.hyperfoil.tools.jjq.value;

public final class JqBoolean implements JqValue {
    public static final JqBoolean TRUE = new JqBoolean(true);
    public static final JqBoolean FALSE = new JqBoolean(false);

    private final boolean value;

    private JqBoolean(boolean value) {
        this.value = value;
    }

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
    public String toString() { return String.valueOf(value); }

    @Override
    public boolean equals(Object o) {
        return o instanceof JqBoolean b && b.value == value;
    }

    @Override
    public int hashCode() { return Boolean.hashCode(value); }
}

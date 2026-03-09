package io.hyperfoil.tools.jjq.value;

public final class JqNull implements JqValue {
    public static final JqNull NULL = new JqNull();

    private JqNull() {}

    @Override
    public Type type() { return Type.NULL; }

    @Override
    public boolean isTruthy() { return false; }

    @Override
    public String toJsonString() { return "null"; }

    @Override
    public String toString() { return "null"; }

    @Override
    public boolean equals(Object o) { return o instanceof JqNull; }

    @Override
    public int hashCode() { return 0; }
}

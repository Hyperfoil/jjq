package io.hyperfoil.tools.jjq.value;

public final class JqNull implements JqValue {
    private static final long serialVersionUID = 1L;

    public static final JqNull NULL = new JqNull();

    private JqNull() {}

    /** Preserve singleton identity across serialization. */
    private Object readResolve() { return NULL; }

    @Override
    public Type type() { return Type.NULL; }

    @Override
    public boolean isTruthy() { return false; }

    @Override
    public String toJsonString() { return "null"; }

    @Override
    public void appendTo(StringBuilder sb) { sb.append("null"); }

    @Override
    public String toString() { return "null"; }

    @Override
    public boolean equals(Object o) { return o instanceof JqNull; }

    @Override
    public int hashCode() { return 0; }
}

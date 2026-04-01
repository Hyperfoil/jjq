package io.hyperfoil.tools.jjq.value;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class JqArray implements JqValue {
    public static final JqArray EMPTY = new JqArray(List.of());

    private final List<JqValue> elements;

    private JqArray(List<JqValue> elements) {
        this.elements = elements;
    }

    public static JqArray of(List<JqValue> elements) {
        if (elements.isEmpty()) return EMPTY;
        return new JqArray(Collections.unmodifiableList(elements));
    }

    /** Create from a list that the caller guarantees won't be mutated. Avoids unmodifiable wrapper. */
    public static JqArray ofTrusted(List<JqValue> elements) {
        if (elements.isEmpty()) return EMPTY;
        return new JqArray(elements);
    }

    /** Create from a raw array that the caller guarantees won't be mutated. Uses Arrays.asList (no copy). */
    public static JqArray ofTrusted(JqValue[] elements) {
        if (elements.length == 0) return EMPTY;
        return new JqArray(Arrays.asList(elements));
    }

    /** Create from a raw array, trimming to the given count. No copy if count == elements.length. */
    public static JqArray ofTrusted(JqValue[] elements, int count) {
        if (count == 0) return EMPTY;
        if (count == elements.length) return new JqArray(Arrays.asList(elements));
        return new JqArray(Arrays.asList(Arrays.copyOf(elements, count)));
    }

    public static JqArray of(JqValue... elements) {
        if (elements.length == 0) return EMPTY;
        return new JqArray(List.of(elements));
    }

    @Override
    public Type type() { return Type.ARRAY; }

    @Override
    public List<JqValue> arrayValue() { return elements; }

    public JqValue get(int index) {
        if (index < 0) index = elements.size() + index;
        if (index < 0 || index >= elements.size()) return JqNull.NULL;
        return elements.get(index);
    }

    public JqArray slice(Integer from, Integer to) {
        int size = elements.size();
        int start = from == null ? 0 : (from < 0 ? Math.max(0, size + from) : from);
        int end = to == null ? size : (to < 0 ? Math.max(0, size + to) : Math.min(to, size));
        if (start >= end) return EMPTY;
        return JqArray.of(elements.subList(start, end));
    }

    @Override
    public String toJsonString() {
        var sb = new StringBuilder("[");
        for (int i = 0; i < elements.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(elements.get(i).toJsonString());
        }
        sb.append(']');
        return sb.toString();
    }

    @Override
    public String toString() { return toJsonString(); }

    @Override
    public boolean equals(Object o) {
        return o instanceof JqArray a && a.elements.equals(elements);
    }

    @Override
    public int hashCode() { return elements.hashCode(); }
}

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
        if (elements.isEmpty()) return "[]";
        return JqValues.serialize(this);
    }

    @Override
    public void appendTo(StringBuilder sb) {
        int size = elements.size();
        if (size == 0) { sb.append("[]"); return; }
        sb.append('[');
        elements.get(0).appendTo(sb);
        for (int i = 1; i < size; i++) {
            sb.append(',');
            elements.get(i).appendTo(sb);
        }
        sb.append(']');
    }

    @Override
    public String toString() { return toJsonString(); }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof JqArray a)) return false;
        return equalsDepth(this, a, 0);
    }

    static boolean equalsDepth(JqArray a, JqArray b, int depth) {
        // Iterative descent for linear chains (single-element nested arrays)
        while (true) {
            if (depth > JqValue.MAX_MERGE_DEPTH) {
                throw new JqTypeError("Equality check too deep");
            }
            var aElems = a.elements;
            var bElems = b.elements;
            if (aElems.size() != bElems.size()) return false;
            if (aElems.isEmpty()) return true;
            // Compare all but the last element recursively
            for (int i = 0; i < aElems.size() - 1; i++) {
                JqValue av = aElems.get(i), bv = bElems.get(i);
                if (av instanceof JqArray aa && bv instanceof JqArray ba) {
                    if (!equalsDepth(aa, ba, depth + 1)) return false;
                } else if (av instanceof JqObject ao && bv instanceof JqObject bo) {
                    if (!JqObject.equalsDepth(ao, bo, depth + 1)) return false;
                } else {
                    if (!av.equals(bv)) return false;
                }
            }
            // Tail-iterate on the last element
            JqValue lastA = aElems.getLast(), lastB = bElems.getLast();
            if (lastA instanceof JqArray aa && lastB instanceof JqArray ba) {
                a = aa;
                b = ba;
                depth++;
                continue; // iterate instead of recurse
            } else if (lastA instanceof JqObject ao && lastB instanceof JqObject bo) {
                return JqObject.equalsDepth(ao, bo, depth + 1);
            } else {
                return lastA.equals(lastB);
            }
        }
    }

    @Override
    public int hashCode() { return elements.hashCode(); }
}

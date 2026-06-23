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

    // ========================================================================
    //  Copy-on-write mutation methods
    // ========================================================================

    /**
     * Returns a new JqArray with the element at the given index replaced.
     * Supports negative indexing ({@code -1} = last element).
     *
     * @throws IndexOutOfBoundsException if the resolved index is out of range
     */
    public JqArray with(int index, JqValue element) {
        int actualIndex = index < 0 ? elements.size() + index : index;
        if (actualIndex < 0 || actualIndex >= elements.size()) {
            throw new IndexOutOfBoundsException("Index " + index + " out of range for array of size " + elements.size());
        }
        JqValue[] arr = elements.toArray(new JqValue[0]);
        arr[actualIndex] = element;
        return new JqArray(Arrays.asList(arr));
    }

    /**
     * Returns a new JqArray with the element appended at the end.
     */
    public JqArray append(JqValue element) {
        JqValue[] arr = new JqValue[elements.size() + 1];
        for (int i = 0; i < elements.size(); i++) {
            arr[i] = elements.get(i);
        }
        arr[elements.size()] = element;
        return new JqArray(Arrays.asList(arr));
    }

    // ========================================================================
    //  Builder for incremental construction
    // ========================================================================

    /** Create a builder for constructing a JqArray incrementally. */
    public static ArrayBuilder arrayBuilder() {
        return new ArrayBuilder(8);
    }

    /** Create a builder with a hint for the expected number of elements. */
    public static ArrayBuilder arrayBuilder(int expectedSize) {
        return new ArrayBuilder(expectedSize);
    }

    /**
     * Builder for constructing a JqArray incrementally using direct array
     * construction (no intermediate List resizing).
     */
    public static final class ArrayBuilder {
        private JqValue[] elements;
        private int size;

        ArrayBuilder(int expectedSize) {
            elements = new JqValue[Math.max(expectedSize, 4)];
        }

        /** Add a JqValue element. */
        public ArrayBuilder add(JqValue value) {
            if (size >= elements.length) {
                elements = Arrays.copyOf(elements, elements.length * 2);
            }
            elements[size++] = value;
            return this;
        }

        /** Add a String element. */
        public ArrayBuilder add(String value) { return add(JqString.of(value)); }

        /** Add a long element. */
        public ArrayBuilder add(long value) { return add(JqNumber.of(value)); }

        /** Add a double element. */
        public ArrayBuilder add(double value) { return add(JqNumber.of(value)); }

        /** Add a boolean element. */
        public ArrayBuilder add(boolean value) { return add(JqBoolean.of(value)); }

        /** Add a null element. */
        public ArrayBuilder addNull() { return add(JqNull.NULL); }

        /** Build the JqArray. The builder should not be used after this call. */
        public JqArray build() {
            return size == 0 ? EMPTY : JqArray.ofTrusted(elements, size);
        }
    }

    // ========================================================================
    //  Core accessors
    // ========================================================================

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

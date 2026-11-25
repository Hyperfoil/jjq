package io.hyperfoil.tools.jjq.value;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.stream.Stream;

public final class JqArray implements JqValue, Iterable<JqValue> {
    private static final long serialVersionUID = 1L;

    private static final Object[] EMPTY_ARRAY = new Object[0];
    public static final JqArray EMPTY = new JqArray(EMPTY_ARRAY, null);

    // Dual representation mirroring JqObject: native arrays store a single concrete
    // Object[] (avoiding both polymorphic List.get itable stubs and interface-array
    // aastore checks when built via ArrayBuilder); lazy adapter arrays (e.g.
    // LazyArrayList) keep their List verbatim in externalList.
    private final Object[] raw;
    private final List<JqValue> externalList;
    private transient List<JqValue> listView;

    // Source byte length — set by JqValues.parse() on root values for cache weighing.
    private transient int sourceLengthBytes;

    private JqArray(Object[] raw, List<JqValue> externalList) {
        this.raw = raw;
        this.externalList = externalList;
    }

    public static JqArray of(List<JqValue> elements) {
        if (elements.isEmpty()) return EMPTY;
        return new JqArray(elements.toArray(), null);
    }

    /** Create from a list that the caller guarantees won't be mutated. Avoids copying lazy lists. */
    public static JqArray ofTrusted(List<JqValue> elements) {
        if (elements.isEmpty()) return EMPTY;
        return new JqArray(null, elements);
    }

    /** Create from a raw array that the caller guarantees won't be mutated (no copy). */
    public static JqArray ofTrusted(JqValue[] elements) {
        if (elements.length == 0) return EMPTY;
        return new JqArray(elements, null);
    }

    /** Create from a raw array, trimming to the given count. No copy if count == elements.length. */
    public static JqArray ofTrusted(JqValue[] elements, int count) {
        return ofRaw(elements, count);
    }

    private static JqArray ofRaw(Object[] elements, int count) {
        if (count == 0) return EMPTY;
        if (count == elements.length) return new JqArray(elements, null);
        return new JqArray(Arrays.copyOf(elements, count, Object[].class), null);
    }

    public static JqArray of(JqValue... elements) {
        if (elements.length == 0) return EMPTY;
        Object[] copy = new Object[elements.length];
        for (int i = 0; i < elements.length; i++) {
            copy[i] = Objects.requireNonNull(elements[i]);
        }
        return new JqArray(copy, null);
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
        Objects.requireNonNull(element, "element");
        int len = size();
        int actualIndex = index < 0 ? len + index : index;
        if (actualIndex < 0 || actualIndex >= len) {
            throw new IndexOutOfBoundsException("Index " + index + " out of range for array of size " + len);
        }
        Object[] arr = raw != null ? Arrays.copyOf(raw, len, Object[].class) : externalList.toArray();
        arr[actualIndex] = element;
        return new JqArray(arr, null);
    }

    /**
     * Returns a new JqArray with the element appended at the end.
     */
    public JqArray append(JqValue element) {
        Objects.requireNonNull(element, "element");
        int oldSize = size();
        Object[] arr;
        if (raw != null) {
            arr = Arrays.copyOf(raw, oldSize + 1, Object[].class);
        } else {
            arr = externalList.toArray(new Object[oldSize + 1]);
        }
        arr[oldSize] = element;
        return new JqArray(arr, null);
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
        private Object[] elements;
        private int size;

        ArrayBuilder(int expectedSize) {
            elements = new Object[Math.max(expectedSize, 1)];
        }

        /** Add a JqValue element. */
        public ArrayBuilder add(JqValue value) {
            Objects.requireNonNull(value, "value");
            if (size >= elements.length) {
                elements = Arrays.copyOf(elements, Math.max(elements.length * 2, 4));
            }
            elements[size++] = value;
            return this;
        }

        /**
         * Store a non-null JqValue at a pre-reserved index without grow checks.
         * Caller must create the builder with {@code arrayBuilder(capacity)} where
         * {@code index < capacity}, and finish with {@link #build(int)}.
         */
        public void setUnchecked(int index, JqValue value) {
            elements[index] = value;
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
            return size == 0 ? EMPTY : JqArray.ofRaw(elements, size);
        }

        /** Build the JqArray with an explicit count filled via {@link #setUnchecked}. */
        public JqArray build(int count) {
            return count == 0 ? EMPTY : JqArray.ofRaw(elements, count);
        }
    }

    private static final class RawArrayList extends AbstractList<JqValue> implements RandomAccess {
        private final Object[] raw;

        RawArrayList(Object[] raw) {
            this.raw = raw;
        }

        @Override
        public JqValue get(int index) {
            return (JqValue) raw[index];
        }

        @Override
        public int size() {
            return raw.length;
        }

        @Override
        public Object[] toArray() {
            return Arrays.copyOf(raw, raw.length, Object[].class);
        }
    }

    // ========================================================================
    //  Core accessors
    // ========================================================================

    @Override
    public Type type() { return Type.ARRAY; }

    @Override
    public List<JqValue> arrayValue() {
        if (externalList != null) return externalList;
        List<JqValue> view = listView;
        if (view == null) {
            view = new RawArrayList(raw);
            listView = view;
        }
        return view;
    }

    /** Return the number of elements. */
    @Override
    public int size() { return raw != null ? raw.length : externalList.size(); }

    /**
     * Direct positional element access for internal/hot loops (0-based, no negative indexing).
     * Mirrors {@link JqObject#valueAt(int)} and avoids polymorphic {@code List.get} dispatch
     * for native arrays.
     */
    public JqValue elementAt(int index) {
        return raw != null ? (JqValue) raw[index] : externalList.get(index);
    }

    @Override
    public int estimatedSizeInBytes() { return sourceLengthBytes > 0 ? sourceLengthBytes : 1; }

    /** Package-private — called by JqValues.parse() to set source byte length on root values. */
    void setSourceLengthBytes(int len) { this.sourceLengthBytes = len; }

    /** Return the first element, or {@link JqNull#NULL} if empty. */
    public JqValue first() {
        int len = size();
        return len == 0 ? JqNull.NULL : elementAt(0);
    }

    /** Return the last element, or {@link JqNull#NULL} if empty. */
    public JqValue last() {
        int len = size();
        return len == 0 ? JqNull.NULL : elementAt(len - 1);
    }

    @Override
    public Iterator<JqValue> iterator() { return arrayValue().iterator(); }

    /** Return a stream over the elements. */
    public Stream<JqValue> stream() { return arrayValue().stream(); }

    public JqValue get(int index) {
        int len = size();
        if (index < 0) index = len + index;
        if (index < 0 || index >= len) return JqNull.NULL;
        return elementAt(index);
    }

    /** Check if the given index is within bounds. Supports negative indexing. */
    public boolean has(int index) {
        int len = size();
        int actual = index < 0 ? len + index : index;
        return actual >= 0 && actual < len;
    }

    public JqArray slice(Integer from, Integer to) {
        int len = size();
        int start = from == null ? 0 : (from < 0 ? Math.max(0, len + from) : from);
        int end = to == null ? len : (to < 0 ? Math.max(0, len + to) : Math.min(to, len));
        if (start >= end) return EMPTY;
        if (raw != null) {
            return new JqArray(Arrays.copyOfRange(raw, start, end, Object[].class), null);
        }
        return JqArray.of(externalList.subList(start, end));
    }

    @Override
    public String toJsonString() {
        if (size() == 0) return "[]";
        return JqValues.serialize(this);
    }

    @Override
    public void appendTo(StringBuilder sb) {
        int len = size();
        if (len == 0) { sb.append("[]"); return; }
        sb.append('[');
        appendValue(elementAt(0), sb);
        for (int i = 1; i < len; i++) {
            sb.append(',');
            appendValue(elementAt(i), sb);
        }
        sb.append(']');
    }

    @Override
    public void appendToBytes(BytOutput out) {
        int len = size();
        if (len == 0) { out.writeByte('['); out.writeByte(']'); return; }
        out.writeByte('[');
        appendValueBytes(elementAt(0), out);
        for (int i = 1; i < len; i++) {
            out.writeByte(',');
            appendValueBytes(elementAt(i), out);
        }
        out.writeByte(']');
    }

    private static void appendValueBytes(JqValue v, BytOutput out) {
        if (v instanceof JqString s) s.appendToBytes(out);
        else if (v instanceof JqNumber n) n.appendToBytes(out);
        else if (v instanceof JqBoolean b) { if (b.booleanValue()) out.writeTrue(); else out.writeFalse(); }
        else if (v instanceof JqNull) out.writeNull();
        else v.appendToBytes(out);
    }

    /** Type-specialize dispatch to help JIT devirtualize common cases (avoids itable stub). */
    private static void appendValue(JqValue v, StringBuilder sb) {
        if (v instanceof JqString s) s.appendTo(sb);
        else if (v instanceof JqNumber n) n.appendTo(sb);
        else if (v instanceof JqBoolean b) sb.append(b.booleanValue() ? "true" : "false");
        else if (v instanceof JqNull) sb.append("null");
        else v.appendTo(sb);
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
            int len = a.size();
            if (len != b.size()) return false;
            if (len == 0) return true;
            // Compare all but the last element recursively
            for (int i = 0; i < len - 1; i++) {
                JqValue av = a.elementAt(i), bv = b.elementAt(i);
                if (av instanceof JqArray aa && bv instanceof JqArray ba) {
                    if (!equalsDepth(aa, ba, depth + 1)) return false;
                } else if (av instanceof JqObject ao && bv instanceof JqObject bo) {
                    if (!JqObject.equalsDepth(ao, bo, depth + 1)) return false;
                } else {
                    if (!av.equals(bv)) return false;
                }
            }
            // Tail-iterate on the last element
            JqValue lastA = a.elementAt(len - 1), lastB = b.elementAt(len - 1);
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
    public int hashCode() {
        if (raw != null) {
            int hashCode = 1;
            for (Object e : raw) {
                hashCode = 31 * hashCode + (e == null ? 0 : e.hashCode());
            }
            return hashCode;
        }
        return externalList.hashCode();
    }

    private Object writeReplace() {
        int len = size();
        JqValue[] arr = new JqValue[len];
        for (int i = 0; i < len; i++) {
            arr[i] = elementAt(i);
        }
        return new SerializedForm(arr);
    }

    private static final class SerializedForm implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        private final JqValue[] elements;

        SerializedForm(JqValue[] elements) {
            this.elements = elements;
        }

        private Object readResolve() {
            return elements.length == 0 ? EMPTY : JqArray.ofTrusted(elements);
        }
    }
}

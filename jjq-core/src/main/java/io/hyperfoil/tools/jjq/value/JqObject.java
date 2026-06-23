package io.hyperfoil.tools.jjq.value;

import java.util.*;

/**
 * Immutable JSON object value. Internally uses one of two representations:
 * <ul>
 *   <li><b>Array-backed</b> (parser, builtins, VM): parallel {@code String[] keys}
 *       + {@code JqValue[] values} arrays for cache-friendly access and low allocation.</li>
 *   <li><b>Map-backed</b> (lazy adapter wrappers): external {@code Map<String, JqValue>}
 *       preserves lazy conversion benefit from Jackson/fastjson2 adapters.</li>
 * </ul>
 */
public final class JqObject implements JqValue {
    public static final JqObject EMPTY = new JqObject(new String[0], new JqValue[0], 0, null);

    // Array-backed representation (null when map-backed)
    private final String[] keys;
    private final JqValue[] values;
    private final int size;

    // Map-backed representation (null when array-backed)
    private final Map<String, JqValue> externalMap;

    // Cached views (lazy)
    private String[] sortedKeysCache;
    private Map<String, JqValue> mapView;

    private JqObject(String[] keys, JqValue[] values, int size, Map<String, JqValue> externalMap) {
        this.keys = keys;
        this.values = values;
        this.size = size;
        this.externalMap = externalMap;
    }

    // ========================================================================
    //  Factory methods
    // ========================================================================

    /** Create from a Map with defensive copy. */
    public static JqObject of(Map<String, JqValue> fields) {
        if (fields.isEmpty()) return EMPTY;
        // Copy into arrays preserving insertion order
        int n = fields.size();
        String[] keys = new String[n];
        JqValue[] vals = new JqValue[n];
        int i = 0;
        for (var entry : fields.entrySet()) {
            keys[i] = entry.getKey();
            vals[i] = entry.getValue();
            i++;
        }
        return new JqObject(keys, vals, n, null);
    }

    /**
     * Create from a LinkedHashMap without copying the map contents.
     * Converts to parallel arrays for cache-friendly access.
     * The caller must not modify the map after this call.
     */
    public static JqObject ofTrusted(LinkedHashMap<String, JqValue> fields) {
        if (fields.isEmpty()) return EMPTY;
        int n = fields.size();
        String[] keys = new String[n];
        JqValue[] vals = new JqValue[n];
        int i = 0;
        for (var entry : fields.entrySet()) {
            keys[i] = entry.getKey();
            vals[i] = entry.getValue();
            i++;
        }
        return new JqObject(keys, vals, n, null);
    }

    /**
     * Create wrapping an external Map without copying.
     * Used by lazy adapter wrappers (Jackson LazyObjectMap, fastjson2 LazyObjectMap).
     * The caller must guarantee the map is not modified after this call.
     */
    public static JqObject ofTrusted(Map<String, JqValue> fields) {
        if (fields.isEmpty()) return EMPTY;
        if (fields instanceof LinkedHashMap<String, JqValue> lhm) {
            return ofTrusted(lhm);
        }
        // External map mode (lazy adapters)
        return new JqObject(null, null, fields.size(), fields);
    }

    /**
     * Create directly from parallel arrays. The caller must guarantee
     * the arrays are not modified after this call.
     */
    public static JqObject ofArrays(String[] keys, JqValue[] values, int size) {
        if (size == 0) return EMPTY;
        return new JqObject(keys, values, size, null);
    }

    public static JqObject of(String key, JqValue value) {
        return new JqObject(new String[]{key}, new JqValue[]{value}, 1, null);
    }

    /**
     * Create a JqObject from key-value pairs, preserving insertion order.
     *
     * @throws IllegalArgumentException if the number of rest arguments is odd
     */
    public static JqObject of(String key1, JqValue value1, String key2, JqValue value2, Object... rest) {
        if (rest.length % 2 != 0) {
            throw new IllegalArgumentException("Arguments must be key-value pairs");
        }
        int n = 2 + rest.length / 2;
        String[] keys = new String[n];
        JqValue[] vals = new JqValue[n];
        keys[0] = key1; vals[0] = value1;
        keys[1] = key2; vals[1] = value2;
        for (int i = 0; i < rest.length; i += 2) {
            keys[2 + i / 2] = (String) rest[i];
            vals[2 + i / 2] = (JqValue) rest[i + 1];
        }
        return new JqObject(keys, vals, n, null);
    }

    // ========================================================================
    //  Convenience factories with primitive values
    // ========================================================================

    /** Create a single-field object with a String value. */
    public static JqObject of(String key, String value) {
        return of(key, JqString.of(value));
    }

    /** Create a single-field object with a long value. */
    public static JqObject of(String key, long value) {
        return of(key, JqNumber.of(value));
    }

    /** Create a single-field object with a double value. */
    public static JqObject of(String key, double value) {
        return of(key, JqNumber.of(value));
    }

    // ========================================================================
    //  Copy-on-write mutation methods
    // ========================================================================

    /**
     * Returns a new JqObject with the field added or replaced.
     * If the key already exists, the value is replaced at its current position
     * (preserving insertion order). If the key is new, it is appended.
     */
    public JqObject with(String key, JqValue value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (externalMap != null) {
            var map = new LinkedHashMap<>(externalMap);
            map.put(key, value);
            return JqObject.ofTrusted(map);
        }
        // Check if key exists (replace in place)
        for (int i = 0; i < size; i++) {
            if (key.equals(keys[i])) {
                JqValue[] newValues = Arrays.copyOf(values, size);
                newValues[i] = value;
                return new JqObject(Arrays.copyOf(keys, size), newValues, size, null);
            }
        }
        // New key: extend arrays by 1
        String[] newKeys = Arrays.copyOf(keys, size + 1);
        JqValue[] newValues = Arrays.copyOf(values, size + 1);
        newKeys[size] = key;
        newValues[size] = value;
        return new JqObject(newKeys, newValues, size + 1, null);
    }

    /**
     * Returns a new JqObject with the field removed.
     * No-op (returns this) if the key doesn't exist.
     *
     * <p>For bulk removal of multiple keys, prefer building a new object
     * with selective inclusion via {@link Builder} instead of chaining
     * {@code without()} calls (each call copies arrays).</p>
     */
    public JqObject without(String key) {
        Objects.requireNonNull(key, "key");
        if (externalMap != null) {
            if (!externalMap.containsKey(key)) return this;
            var map = new LinkedHashMap<>(externalMap);
            map.remove(key);
            return map.isEmpty() ? EMPTY : JqObject.ofTrusted(map);
        }
        // Find key index
        int idx = -1;
        for (int i = 0; i < size; i++) {
            if (key.equals(keys[i])) { idx = i; break; }
        }
        if (idx < 0) return this; // key not found
        if (size == 1) return EMPTY;
        // Create new arrays without the entry at idx
        String[] newKeys = new String[size - 1];
        JqValue[] newValues = new JqValue[size - 1];
        System.arraycopy(keys, 0, newKeys, 0, idx);
        System.arraycopy(keys, idx + 1, newKeys, idx, size - idx - 1);
        System.arraycopy(values, 0, newValues, 0, idx);
        System.arraycopy(values, idx + 1, newValues, idx, size - idx - 1);
        return new JqObject(newKeys, newValues, size - 1, null);
    }

    /**
     * Returns a new JqObject with all fields from {@code other} merged in.
     * Shallow merge: overlapping keys take the value from {@code other},
     * preserving their position from {@code this}. New keys from {@code other}
     * are appended at the end.
     *
     * <p>Matches jq {@code +} operator semantics:
     * {@code {"a":1,"b":2} + {"b":3,"c":4} = {"a":1,"b":3,"c":4}}</p>
     *
     * <p>For deep recursive merge, use the {@code *} operator
     * ({@link JqValue#multiply}).</p>
     */
    public JqObject merge(JqObject other) {
        if (other.size == 0 && other.externalMap == null) return this;
        if (other.externalMap != null && other.externalMap.isEmpty()) return this;
        if (this.size == 0 && this.externalMap == null) return other;
        if (this.externalMap != null && this.externalMap.isEmpty()) return other;

        // Use builder for clean construction
        int thisSize = externalMap != null ? externalMap.size() : size;
        int otherSize = other.externalMap != null ? other.externalMap.size() : other.size;
        var builder = new Builder(thisSize + otherSize);

        // Add all entries from this
        if (externalMap != null) {
            for (var e : externalMap.entrySet()) builder.put(e.getKey(), e.getValue());
        } else {
            for (int i = 0; i < size; i++) builder.put(keys[i], values[i]);
        }

        // Merge entries from other (replaces in place for existing keys, appends new)
        if (other.externalMap != null) {
            for (var e : other.externalMap.entrySet()) builder.put(e.getKey(), e.getValue());
        } else {
            for (int i = 0; i < other.size; i++) builder.put(other.keys[i], other.values[i]);
        }

        return builder.build();
    }

    // ========================================================================
    //  Builder for incremental construction
    // ========================================================================

    /** Create a builder for constructing a JqObject incrementally. */
    public static Builder builder() {
        return new Builder(8);
    }

    /** Create a builder with a hint for the expected number of fields. */
    public static Builder builder(int expectedSize) {
        return new Builder(expectedSize);
    }

    /**
     * Builder for constructing a JqObject incrementally using direct array
     * construction (no LinkedHashMap intermediate).
     *
     * <p>{@code put()} performs scan-and-replace for duplicate keys:
     * {@code builder.put("x", 1).put("x", 2).build()} produces {@code {"x": 2}}.</p>
     */
    public static final class Builder {
        private String[] keys;
        private JqValue[] values;
        private int size;

        Builder(int expectedSize) {
            keys = new String[Math.max(expectedSize, 4)];
            values = new JqValue[keys.length];
        }

        /** Add or replace a field with a JqValue. */
        public Builder put(String key, JqValue value) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            // Scan for existing key (replace in place)
            for (int i = 0; i < size; i++) {
                if (key.equals(keys[i])) {
                    values[i] = value;
                    return this;
                }
            }
            // New key: append
            if (size >= keys.length) {
                keys = Arrays.copyOf(keys, keys.length * 2);
                values = Arrays.copyOf(values, values.length * 2);
            }
            keys[size] = key;
            values[size] = value;
            size++;
            return this;
        }

        /** Add or replace a field with a String value. */
        public Builder put(String key, String value) { return put(key, JqString.of(value)); }

        /** Add or replace a field with a long value. */
        public Builder put(String key, long value) { return put(key, JqNumber.of(value)); }

        /** Add or replace a field with a double value. */
        public Builder put(String key, double value) { return put(key, JqNumber.of(value)); }

        /** Add or replace a field with a boolean value. */
        public Builder put(String key, boolean value) { return put(key, JqBoolean.of(value)); }

        /** Add a null-valued field. */
        public Builder putNull(String key) { return put(key, JqNull.NULL); }

        /** Build the JqObject. The builder should not be used after this call. */
        public JqObject build() {
            return size == 0 ? EMPTY : JqObject.ofArrays(keys, values, size);
        }
    }

    // ========================================================================
    //  Core accessors
    // ========================================================================

    @Override
    public Type type() { return Type.OBJECT; }

    @Override
    public Map<String, JqValue> objectValue() {
        if (externalMap != null) return externalMap;
        Map<String, JqValue> mv = mapView;
        if (mv == null) {
            mv = new ArrayBackedMap();
            mapView = mv;
        }
        return mv;
    }

    /**
     * Get a field value by key. Returns {@link JqNull#NULL} for missing keys.
     * For array-backed objects, uses linear scan with equals (forward, first match wins
     * since parser deduplicates via last-wins at construction).
     */
    public JqValue get(String key) {
        if (externalMap != null) {
            JqValue v = externalMap.get(key);
            return v != null ? v : JqNull.NULL;
        }
        for (int i = 0; i < size; i++) {
            if (key.equals(keys[i])) {
                return values[i];
            }
        }
        return JqNull.NULL;
    }

    public boolean has(String key) {
        if (externalMap != null) return externalMap.containsKey(key);
        for (int i = 0; i < size; i++) {
            if (key.equals(keys[i])) return true;
        }
        return false;
    }

    /** Returns sorted keys, cached for reuse in compareTo. */
    String[] sortedKeys() {
        String[] cached = sortedKeysCache;
        if (cached == null) {
            if (externalMap != null) {
                cached = externalMap.keySet().toArray(new String[0]);
            } else {
                cached = java.util.Arrays.copyOf(keys, size);
            }
            java.util.Arrays.sort(cached);
            sortedKeysCache = cached;
        }
        return cached;
    }

    // ========================================================================
    //  Serialization
    // ========================================================================

    @Override
    public String toJsonString() {
        if (size == 0 && externalMap == null) return "{}";
        if (externalMap != null && externalMap.isEmpty()) return "{}";
        return JqValues.serialize(this);
    }

    @Override
    public void appendTo(StringBuilder sb) {
        if (externalMap != null) {
            appendFromMap(sb);
            return;
        }
        if (size == 0) { sb.append("{}"); return; }
        sb.append('{');
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append(',');
            sb.append('"');
            JqString.escapeJson(keys[i], sb);
            sb.append("\":");
            values[i].appendTo(sb);
        }
        sb.append('}');
    }

    private void appendFromMap(StringBuilder sb) {
        if (externalMap.isEmpty()) { sb.append("{}"); return; }
        sb.append('{');
        boolean first = true;
        for (var e : externalMap.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"');
            JqString.escapeJson(e.getKey(), sb);
            sb.append("\":");
            e.getValue().appendTo(sb);
        }
        sb.append('}');
    }

    @Override
    public String toString() { return toJsonString(); }

    // ========================================================================
    //  Equality and hashing
    // ========================================================================

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof JqObject obj)) return false;
        return equalsDepth(this, obj, 0);
    }

    static boolean equalsDepth(JqObject a, JqObject b, int depth) {
        while (true) {
            if (depth > JqValue.MAX_MERGE_DEPTH) {
                throw new JqTypeError("Equality check too deep");
            }
            int aSize = a.externalMap != null ? a.externalMap.size() : a.size;
            int bSize = b.externalMap != null ? b.externalMap.size() : b.size;
            if (aSize != bSize) return false;
            if (aSize == 0) return true;

            // Delegate to map-based comparison if either is map-backed
            if (a.externalMap != null || b.externalMap != null) {
                var aMap = a.objectValue();
                var bMap = b.objectValue();
                var iter = aMap.entrySet().iterator();
                Map.Entry<String, JqValue> lastEntry = null;
                while (iter.hasNext()) {
                    var entry = iter.next();
                    if (!iter.hasNext()) { lastEntry = entry; break; }
                    JqValue bv = bMap.get(entry.getKey());
                    if (bv == null) return false;
                    JqValue av = entry.getValue();
                    if (av instanceof JqArray aa && bv instanceof JqArray ba) {
                        if (!JqArray.equalsDepth(aa, ba, depth + 1)) return false;
                    } else if (av instanceof JqObject ao && bv instanceof JqObject bo) {
                        if (!equalsDepth(ao, bo, depth + 1)) return false;
                    } else {
                        if (!av.equals(bv)) return false;
                    }
                }
                JqValue bv = bMap.get(lastEntry.getKey());
                if (bv == null) return false;
                JqValue av = lastEntry.getValue();
                if (av instanceof JqObject ao && bv instanceof JqObject bo) {
                    a = ao; b = bo; depth++; continue;
                } else if (av instanceof JqArray aa && bv instanceof JqArray ba) {
                    return JqArray.equalsDepth(aa, ba, depth + 1);
                } else {
                    return av.equals(bv);
                }
            }

            // Both array-backed: iterate directly
            for (int i = 0; i < a.size - 1; i++) {
                JqValue bv = b.get(a.keys[i]);
                if (bv instanceof JqNull && !b.has(a.keys[i])) return false;
                JqValue av = a.values[i];
                if (av instanceof JqArray aa && bv instanceof JqArray ba) {
                    if (!JqArray.equalsDepth(aa, ba, depth + 1)) return false;
                } else if (av instanceof JqObject ao && bv instanceof JqObject bo) {
                    if (!equalsDepth(ao, bo, depth + 1)) return false;
                } else {
                    if (!av.equals(bv)) return false;
                }
            }
            // Tail-iterate on last entry
            String lastKey = a.keys[a.size - 1];
            JqValue bv = b.get(lastKey);
            if (bv instanceof JqNull && !b.has(lastKey)) return false;
            JqValue av = a.values[a.size - 1];
            if (av instanceof JqObject ao && bv instanceof JqObject bo) {
                a = ao; b = bo; depth++; continue;
            } else if (av instanceof JqArray aa && bv instanceof JqArray ba) {
                return JqArray.equalsDepth(aa, ba, depth + 1);
            } else {
                return av.equals(bv);
            }
        }
    }

    @Override
    public int hashCode() {
        if (externalMap != null) return externalMap.hashCode();
        // Match AbstractMap.hashCode(): sum of (key.hashCode() ^ value.hashCode())
        int h = 0;
        for (int i = 0; i < size; i++) {
            h += keys[i].hashCode() ^ values[i].hashCode();
        }
        return h;
    }

    // ========================================================================
    //  ArrayBackedMap: Map view over parallel arrays
    // ========================================================================

    private final class ArrayBackedMap extends AbstractMap<String, JqValue> {
        @Override
        public JqValue get(Object key) {
            if (key instanceof String k) {
                for (int i = 0; i < size; i++) {
                    if (k.equals(keys[i])) return values[i];
                }
            }
            return null; // Map contract: null for missing, not JqNull
        }

        @Override
        public boolean containsKey(Object key) {
            if (key instanceof String k) {
                for (int i = 0; i < size; i++) {
                    if (k.equals(keys[i])) return true;
                }
            }
            return false;
        }

        @Override
        public int size() { return size; }

        @Override
        public boolean isEmpty() { return size == 0; }

        @Override
        public Set<Entry<String, JqValue>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<String, JqValue>> iterator() {
                    return new Iterator<>() {
                        int pos = 0;
                        @Override public boolean hasNext() { return pos < size; }
                        @Override public Entry<String, JqValue> next() {
                            var e = Map.entry(keys[pos], values[pos]);
                            pos++;
                            return e;
                        }
                    };
                }
                @Override public int size() { return size; }
            };
        }

        @Override
        public Set<String> keySet() {
            return new AbstractSet<>() {
                @Override
                public Iterator<String> iterator() {
                    return new Iterator<>() {
                        int pos = 0;
                        @Override public boolean hasNext() { return pos < size; }
                        @Override public String next() { return keys[pos++]; }
                    };
                }
                @Override public int size() { return size; }
                @Override public boolean contains(Object o) { return containsKey(o); }
            };
        }

        @Override
        public Collection<JqValue> values() {
            return new AbstractCollection<>() {
                @Override
                public Iterator<JqValue> iterator() {
                    return new Iterator<>() {
                        int pos = 0;
                        @Override public boolean hasNext() { return pos < size; }
                        @Override public JqValue next() { return values[pos++]; }
                    };
                }
                @Override public int size() { return size; }
            };
        }
    }
}

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
    private static final long serialVersionUID = 1L;

    public static final JqObject EMPTY = new JqObject(new String[0], new JqValue[0], 0, null);

    // Array-backed representation (null when map-backed)
    private final String[] keys;
    private final JqValue[] values;
    private final int size;

    // Map-backed representation (null when array-backed)
    private final Map<String, JqValue> externalMap;

    /**
     * Threshold above which array-backed objects build a hash index for
     * O(1) {@code get()} / {@code has()} lookups instead of linear scan.
     * Below this size, linear scan with {@code String.equals()} is faster
     * due to cache locality and no hash computation overhead.
     * <p>
     * Set to 32 based on profiling: the 17-key production root object
     * was slower with HashMap (hashCode + getNode + equals) than with
     * linear scan. The 127-key PCP time series objects clearly benefit
     * from hash lookup. The crossover point is somewhere around 20-30 keys.
     */
    static final int HASH_THRESHOLD = 32;

    // Cached views (lazy, transient — rebuilt on demand, not serialized)
    private transient String[] sortedKeysCache;
    private transient JqArray cachedKeysArray; // lazy, sorted JqArray of JqString keys for `keys` builtin
    private transient Map<String, JqValue> mapView;
    // Open-addressing hash index for O(1) key lookup on large objects.
    // hashSlots[hash & mask] = key index into keys[]/values[], or -1 if empty.
    // Collisions use linear probing. Cheaper than HashMap (no Node/Integer allocation).
    private transient int[] hashSlots;
    private transient int hashMask;

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
        // Hash index built lazily on first get()/has() call for objects > HASH_THRESHOLD
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

        /** Add or replace a field with a JqValue. Java {@code null} is treated as {@link JqNull#NULL}. */
        public Builder put(String key, JqValue value) {
            Objects.requireNonNull(key, "key");
            if (value == null) value = JqNull.NULL;
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

        /**
         * Append a field without checking for duplicates.
         * The caller must guarantee the key is not already present in the builder.
         * Used by the VM for {@code BUILD_OBJECT} with compile-time-known unique keys.
         */
        public Builder putUnchecked(String key, JqValue value) {
            if (size >= keys.length) {
                keys = Arrays.copyOf(keys, keys.length * 2);
                values = Arrays.copyOf(values, values.length * 2);
            }
            keys[size] = key;
            values[size] = value;
            size++;
            return this;
        }

        /** Add all entries from a Map. Duplicate keys are replaced (last wins). */
        public Builder putAll(java.util.Map<String, JqValue> map) {
            for (var entry : map.entrySet()) {
                put(entry.getKey(), entry.getValue());
            }
            return this;
        }

        /**
         * Add all fields from a JqObject. Uses zero-allocation iteration
         * over parallel arrays for array-backed objects. Duplicate keys are replaced.
         */
        public Builder putAll(JqObject obj) {
            obj.forEach(this::put);
            return this;
        }

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

    /** Return the number of fields. */
    public int size() { return externalMap != null ? externalMap.size() : size; }

    /**
     * Iterate all fields with zero allocation for array-backed objects.
     * Avoids {@link java.util.Map.Entry} creation that {@link #entries()} requires.
     */
    public void forEach(java.util.function.BiConsumer<String, JqValue> action) {
        if (externalMap != null) {
            externalMap.forEach(action);
        } else {
            for (int i = 0; i < size; i++) action.accept(keys[i], values[i]);
        }
    }

    /** Return the field names as a Set (insertion order preserved). */
    public java.util.Set<String> keys() { return objectValue().keySet(); }

    /** Return the field values as a Collection (insertion order preserved). */
    public java.util.Collection<JqValue> values() { return objectValue().values(); }

    /** Return the field entries as a Set (insertion order preserved). */
    public java.util.Set<Map.Entry<String, JqValue>> entries() { return objectValue().entrySet(); }

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
     *
     * <p>Lookup strategy (following SwissTable/IndexMap patterns):</p>
     * <ul>
     *   <li>Size 0: immediate return</li>
     *   <li>Size 1: direct equals check (no loop, no hash)</li>
     *   <li>Size 2-{@value #HASH_THRESHOLD}: linear scan with String.equals</li>
     *   <li>Size &gt; {@value #HASH_THRESHOLD}: open-addressing hash index (int[] with linear probing)</li>
     * </ul>
     */
    public JqValue get(String key) {
        if (externalMap != null) {
            JqValue v = externalMap.get(key);
            return v != null ? v : JqNull.NULL;
        }
        if (size == 0) return JqNull.NULL;
        if (size == 1) return key.equals(keys[0]) ? values[0] : JqNull.NULL;
        if (size <= HASH_THRESHOLD) {
            for (int i = 0; i < size; i++) {
                if (key.equals(keys[i])) return values[i];
            }
            return JqNull.NULL;
        }
        int idx = hashLookup(key);
        return idx >= 0 ? values[idx] : JqNull.NULL;
    }

    public boolean has(String key) {
        if (externalMap != null) return externalMap.containsKey(key);
        if (size == 0) return false;
        if (size == 1) return key.equals(keys[0]);
        if (size <= HASH_THRESHOLD) {
            for (int i = 0; i < size; i++) {
                if (key.equals(keys[i])) return true;
            }
            return false;
        }
        return hashLookup(key) >= 0;
    }

    /**
     * Look up a key in the hash index. Returns the index into keys[]/values[],
     * or -1 if not found. Uses open-addressing with linear probing.
     */
    private int hashLookup(String key) {
        int[] slots = hashSlots;
        if (slots == null) {
            slots = buildHashSlots();
        }
        int mask = hashMask;
        int slot = key.hashCode() & mask;
        while (true) {
            int idx = slots[slot];
            if (idx < 0) return -1; // empty slot
            if (key.equals(keys[idx])) return idx;
            slot = (slot + 1) & mask; // linear probe
        }
    }

    /**
     * Build the open-addressing hash index. Uses a power-of-2 sized int[] array
     * with load factor ~50% for fast probing. No Node/Integer allocation —
     * just a flat int array mapping hash slots to key indices.
     */
    private int[] buildHashSlots() {
        // Size to next power of 2, at least 2x the number of keys for ~50% load
        int capacity = Integer.highestOneBit(size * 2 - 1) << 1;
        int mask = capacity - 1;
        int[] slots = new int[capacity];
        java.util.Arrays.fill(slots, -1);
        for (int i = 0; i < size; i++) {
            int slot = keys[i].hashCode() & mask;
            while (slots[slot] >= 0) {
                slot = (slot + 1) & mask; // linear probe
            }
            slots[slot] = i;
        }
        this.hashSlots = slots;
        this.hashMask = mask;
        return slots;
    }

    /**
     * Deep merge: for overlapping keys where both values are JqObject,
     * recursively merge them. For all other overlapping keys, {@code other}'s
     * value wins. Matches jq {@code *} operator semantics for objects.
     *
     * <p>Unlike {@link #merge(JqObject)} which is shallow (jq {@code +}),
     * this method recurses into nested objects.</p>
     *
     * @throws JqTypeError if recursion exceeds {@link JqValue#MAX_MERGE_DEPTH}
     */
    public JqObject deepMerge(JqObject other) {
        return deepMerge(other, 0);
    }

    private JqObject deepMerge(JqObject other, int depth) {
        // Use iterative approach with explicit stack to avoid StackOverflowError
        // on deeply nested structures (e.g., reduce range(10000) as $_ ({}; {a: .}) * .)
        record MergeFrame(Builder builder, String key) {}
        var frames = new java.util.ArrayDeque<MergeFrame>();
        JqObject left = this, right = other;

        while (true) {
            if (depth + frames.size() > JqValue.MAX_MERGE_DEPTH) {
                throw new JqTypeError("Object merge too deep");
            }
            if (right.size == 0 && right.externalMap == null) break;
            if (right.externalMap != null && right.externalMap.isEmpty()) break;
            if (left.size == 0 && left.externalMap == null) { left = right; break; }
            if (left.externalMap != null && left.externalMap.isEmpty()) { left = right; break; }

            int leftSize = left.externalMap != null ? left.externalMap.size() : left.size;
            int rightSize = right.externalMap != null ? right.externalMap.size() : right.size;
            var builder = new Builder(leftSize + rightSize);

            // Add all from left
            if (left.externalMap != null) {
                for (var e : left.externalMap.entrySet()) builder.put(e.getKey(), e.getValue());
            } else {
                for (int i = 0; i < left.size; i++) builder.put(left.keys[i], left.values[i]);
            }

            // Merge from right — find at most one key to tail-recurse on
            String tailKey = null;
            JqObject tailLeft = null, tailRight = null;
            if (right.externalMap != null) {
                for (var e : right.externalMap.entrySet()) {
                    JqValue existing = left.get(e.getKey());
                    if (existing instanceof JqObject eo && e.getValue() instanceof JqObject ov) {
                        if (tailKey == null) {
                            tailKey = e.getKey();
                            tailLeft = eo;
                            tailRight = ov;
                        } else {
                            builder.put(e.getKey(), eo.deepMerge(ov, depth + frames.size() + 1));
                        }
                    } else {
                        builder.put(e.getKey(), e.getValue());
                    }
                }
            } else {
                for (int i = 0; i < right.size; i++) {
                    JqValue existing = left.get(right.keys[i]);
                    if (existing instanceof JqObject eo && right.values[i] instanceof JqObject ov) {
                        if (tailKey == null) {
                            tailKey = right.keys[i];
                            tailLeft = eo;
                            tailRight = ov;
                        } else {
                            builder.put(right.keys[i], eo.deepMerge(ov, depth + frames.size() + 1));
                        }
                    } else {
                        builder.put(right.keys[i], right.values[i]);
                    }
                }
            }

            if (tailKey == null) {
                // No recursive merge needed — build and unwind
                left = builder.build();
                break;
            }

            // Push this level and iterate deeper on the tail key
            frames.push(new MergeFrame(builder, tailKey));
            left = tailLeft;
            right = tailRight;
        }

        // Unwind the stack
        JqObject result = left;
        while (!frames.isEmpty()) {
            var frame = frames.pop();
            frame.builder.put(frame.key, result);
            result = frame.builder.build();
        }
        return result;
    }

    /**
     * Returns a cached JqArray of sorted JqString-wrapped keys.
     * Used by the {@code keys} builtin to avoid re-sorting and
     * re-wrapping key strings on repeated calls on the same object.
     */
    public JqArray sortedKeysAsArray() {
        JqArray cached = cachedKeysArray;
        if (cached == null) {
            String[] sorted = sortedKeys();
            JqValue[] wrapped = new JqValue[sorted.length];
            for (int i = 0; i < sorted.length; i++) {
                wrapped[i] = JqString.of(sorted[i]);
            }
            cached = JqArray.ofTrusted(wrapped);
            cachedKeysArray = cached;
        }
        return cached;
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
            // Try pre-computed JSON key form from intern cache (e.g., "\"user\":")
            // Eliminates escapeJson scan + 3 separate appends for interned keys
            String jsonKey = JqValues.internedJsonKey(keys[i]);
            if (jsonKey != null) {
                sb.append(jsonKey);
            } else {
                sb.append('"');
                JqString.escapeJson(keys[i], sb);
                sb.append("\":");
            }
            // Type-specialize dispatch to help JIT devirtualize the common cases
            // (avoids itable stub overhead for interface method dispatch)
            JqValue v = values[i];
            if (v instanceof JqString s) s.appendTo(sb);
            else if (v instanceof JqNumber n) n.appendTo(sb);
            else if (v instanceof JqBoolean b) sb.append(b.booleanValue() ? "true" : "false");
            else if (v instanceof JqNull) sb.append("null");
            else v.appendTo(sb); // JqObject, JqArray — recurse
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
            String jsonKey = JqValues.internedJsonKey(e.getKey());
            if (jsonKey != null) {
                sb.append(jsonKey);
            } else {
                sb.append('"');
                JqString.escapeJson(e.getKey(), sb);
                sb.append("\":");
            }
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

    /**
     * Serialization proxy: converts map-backed objects to array-backed and
     * serializes the parallel arrays. Transient caches (hash index, sorted keys,
     * map view) are rebuilt lazily after deserialization via ofArrays().
     */
    private Object writeReplace() {
        if (externalMap != null) {
            // Convert map-backed to array-backed for serialization
            int n = externalMap.size();
            String[] k = new String[n];
            JqValue[] v = new JqValue[n];
            int i = 0;
            for (var entry : externalMap.entrySet()) {
                k[i] = entry.getKey();
                v[i] = entry.getValue();
                i++;
            }
            return new SerializedForm(k, v, n);
        }
        return new SerializedForm(
                java.util.Arrays.copyOf(keys, size),
                java.util.Arrays.copyOf(values, size),
                size);
    }

    private static class SerializedForm implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        private final String[] keys;
        private final JqValue[] values;
        private final int size;
        SerializedForm(String[] keys, JqValue[] values, int size) {
            this.keys = keys;
            this.values = values;
            this.size = size;
        }
        private Object readResolve() {
            if (size == 0) return EMPTY;
            return JqObject.ofArrays(keys, values, size);
        }
    }
}

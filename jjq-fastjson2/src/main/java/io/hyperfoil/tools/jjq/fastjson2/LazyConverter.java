package io.hyperfoil.tools.jjq.fastjson2;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.hyperfoil.tools.jjq.value.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/**
 * Lazy converter that wraps fastjson2 objects, deferring element conversion
 * until accessed. For large documents where only a subset of fields are accessed,
 * this avoids converting the entire document tree upfront.
 *
 * <p>For {@link JqArray}, laziness is fully preserved: individual elements are
 * converted only when accessed by index. For {@link JqObject}, immediate field
 * values are converted during construction (due to the copy in {@code JqObject.of}),
 * but nested objects and arrays remain lazy — they are wrapped rather than
 * recursively converted, so deep structures are still deferred.</p>
 */
public final class LazyConverter {

    private LazyConverter() {}

    /**
     * Create a JqObject that lazily converts values from a JSONObject.
     * Keys are available immediately but values are converted on first access.
     * Nested objects and arrays are themselves wrapped lazily, so deep
     * structures are only converted when actually traversed.
     */
    public static JqObject lazyObject(JSONObject jsonObj) {
        var map = new LazyObjectMap(jsonObj);
        return JqObject.of(map);
    }

    /**
     * Create a JqArray that lazily converts elements from a JSONArray.
     * Elements are converted on first access by index.
     */
    public static JqArray lazyArray(JSONArray jsonArr) {
        var list = new LazyArrayList(jsonArr);
        return JqArray.of(list);
    }

    /**
     * Convert a single fastjson2 value to JqValue on demand.
     * Nested objects and arrays are wrapped lazily rather than
     * being recursively converted.
     */
    static JqValue convertValue(Object obj) {
        if (obj == null) return JqNull.NULL;
        if (obj instanceof JSONObject jo) return lazyObject(jo);
        if (obj instanceof JSONArray ja) return lazyArray(ja);
        if (obj instanceof String s) return JqString.of(s);
        if (obj instanceof Boolean b) return JqBoolean.of(b);
        if (obj instanceof Integer i) return JqNumber.of(i);
        if (obj instanceof Long l) return JqNumber.of(l);
        if (obj instanceof Double d) return JqNumber.of(d);
        if (obj instanceof Float f) return JqNumber.of(f.doubleValue());
        if (obj instanceof BigDecimal bd) return JqNumber.of(bd);
        if (obj instanceof BigInteger bi) return JqNumber.of(new BigDecimal(bi));
        // Fall back to string representation for unknown types
        return JqString.of(obj.toString());
    }

    /**
     * A Map implementation that lazily converts values from a JSONObject.
     * Iteration order matches the JSONObject's insertion order.
     */
    private static final class LazyObjectMap extends AbstractMap<String, JqValue> {
        private final JSONObject source;
        private final Map<String, JqValue> converted = new LinkedHashMap<>();
        private boolean fullyConverted = false;

        LazyObjectMap(JSONObject source) {
            this.source = source;
        }

        @Override
        public JqValue get(Object key) {
            if (key instanceof String k) {
                JqValue cached = converted.get(k);
                if (cached != null) return cached;
                if (!source.containsKey(k)) return null;
                Object raw = source.get(k);
                JqValue val = convertValue(raw);
                converted.put(k, val);
                return val;
            }
            return null;
        }

        @Override
        public boolean containsKey(Object key) {
            return source.containsKey(key);
        }

        @Override
        public int size() {
            return source.size();
        }

        @Override
        public Set<Entry<String, JqValue>> entrySet() {
            ensureFullyConverted();
            return converted.entrySet();
        }

        @Override
        public Set<String> keySet() {
            return source.keySet();
        }

        @Override
        public Collection<JqValue> values() {
            ensureFullyConverted();
            return converted.values();
        }

        private void ensureFullyConverted() {
            if (!fullyConverted) {
                for (String key : source.keySet()) {
                    converted.computeIfAbsent(key, k -> convertValue(source.get(k)));
                }
                fullyConverted = true;
            }
        }
    }

    /**
     * A List implementation that lazily converts elements from a JSONArray.
     * Each element is converted from its fastjson2 representation only on
     * first access via {@link #get(int)}.
     */
    private static final class LazyArrayList extends AbstractList<JqValue> {
        private final JSONArray source;
        private final JqValue[] converted;

        LazyArrayList(JSONArray source) {
            this.source = source;
            this.converted = new JqValue[source.size()];
        }

        @Override
        public JqValue get(int index) {
            JqValue cached = converted[index];
            if (cached != null) return cached;
            JqValue val = convertValue(source.get(index));
            converted[index] = val;
            return val;
        }

        @Override
        public int size() {
            return source.size();
        }
    }
}

package io.hyperfoil.tools.jjq.value;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JqObject implements JqValue {
    public static final JqObject EMPTY = new JqObject(Map.of());

    private final Map<String, JqValue> fields;

    private JqObject(Map<String, JqValue> fields) {
        this.fields = fields;
    }

    public static JqObject of(Map<String, JqValue> fields) {
        if (fields.isEmpty()) return EMPTY;
        return new JqObject(Collections.unmodifiableMap(new LinkedHashMap<>(fields)));
    }

    /**
     * Creates a JqObject wrapping the given map without copying.
     * The caller must not modify the map after this call.
     */
    public static JqObject ofTrusted(LinkedHashMap<String, JqValue> fields) {
        if (fields.isEmpty()) return EMPTY;
        return new JqObject(Collections.unmodifiableMap(fields));
    }

    /**
     * Creates a JqObject wrapping the given map without copying or wrapping.
     * The caller must guarantee the map is not modified after this call.
     */
    public static JqObject ofTrusted(Map<String, JqValue> fields) {
        if (fields.isEmpty()) return EMPTY;
        return new JqObject(fields);
    }

    public static JqObject of(String key, JqValue value) {
        var map = new LinkedHashMap<String, JqValue>();
        map.put(key, value);
        return new JqObject(Collections.unmodifiableMap(map));
    }

    /**
     * Create a JqObject from key-value pairs, preserving insertion order.
     * Arguments must alternate between String keys and JqValue values.
     *
     * <pre>{@code
     * JqObject.of("name", JqString.of("Alice"), "age", JqNumber.of(30))
     * }</pre>
     *
     * @throws IllegalArgumentException if the number of arguments is odd
     */
    public static JqObject of(String key1, JqValue value1, String key2, JqValue value2, Object... rest) {
        if (rest.length % 2 != 0) {
            throw new IllegalArgumentException("Arguments must be key-value pairs");
        }
        var map = new LinkedHashMap<String, JqValue>(2 + rest.length / 2);
        map.put(key1, value1);
        map.put(key2, value2);
        for (int i = 0; i < rest.length; i += 2) {
            map.put((String) rest[i], (JqValue) rest[i + 1]);
        }
        return new JqObject(Collections.unmodifiableMap(map));
    }

    @Override
    public Type type() { return Type.OBJECT; }

    @Override
    public Map<String, JqValue> objectValue() { return fields; }

    public JqValue get(String key) {
        return fields.getOrDefault(key, JqNull.NULL);
    }

    public boolean has(String key) {
        return fields.containsKey(key);
    }

    @Override
    public String toJsonString() {
        var sb = new StringBuilder("{");
        boolean first = true;
        for (var e : fields.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"');
            JqString.escapeJson(e.getKey(), sb);
            sb.append("\":");
            sb.append(e.getValue().toJsonString());
        }
        sb.append('}');
        return sb.toString();
    }

    @Override
    public String toString() { return toJsonString(); }

    @Override
    public boolean equals(Object o) {
        return o instanceof JqObject obj && obj.fields.equals(fields);
    }

    @Override
    public int hashCode() { return fields.hashCode(); }
}

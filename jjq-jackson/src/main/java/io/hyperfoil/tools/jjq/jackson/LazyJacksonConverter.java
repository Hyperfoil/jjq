package io.hyperfoil.tools.jjq.jackson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hyperfoil.tools.jjq.value.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Lazy converter that wraps Jackson {@link JsonNode} trees, deferring element
 * conversion until accessed. For large documents where the jq filter only
 * touches a subset of fields, this avoids converting the entire tree upfront.
 *
 * <p>Scalars (strings, numbers, booleans, null) are converted immediately since
 * they are trivial. Objects and arrays are wrapped lazily — nested elements are
 * only converted when the filter actually accesses them.
 *
 * <pre>{@code
 * // Only converts the fields the filter touches
 * JqValue lazy = LazyJacksonConverter.fromJsonNode(largeJsonNode);
 * List<JqValue> results = program.applyAll(lazy);
 * }</pre>
 */
public final class LazyJacksonConverter {

    private LazyJacksonConverter() {}

    /**
     * Convert a Jackson {@link JsonNode} to a {@link JqValue} with lazy conversion
     * of nested objects and arrays.
     */
    public static JqValue fromJsonNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return JqNull.NULL;
        }
        if (node.isObject()) {
            return lazyObject((ObjectNode) node);
        }
        if (node.isArray()) {
            return lazyArray((ArrayNode) node);
        }
        return convertScalar(node);
    }

    static JqValue convertScalar(JsonNode node) {
        if (node.isBoolean()) return JqBoolean.of(node.booleanValue());
        if (node.isTextual()) return JqString.of(node.textValue());
        if (node.isIntegralNumber()) {
            if (node.isBigInteger()) return JqNumber.of(new BigDecimal(node.bigIntegerValue()));
            return JqNumber.of(node.longValue());
        }
        if (node.isNumber()) {
            if (node.isBigDecimal()) return JqNumber.of(node.decimalValue());
            return JqNumber.of(node.doubleValue());
        }
        if (node.isNull()) return JqNull.NULL;
        return JqNull.NULL;
    }

    private static JqObject lazyObject(ObjectNode node) {
        if (node.isEmpty()) return JqObject.EMPTY;
        return JqObject.ofTrusted(new LazyObjectMap(node));
    }

    private static JqArray lazyArray(ArrayNode node) {
        if (node.isEmpty()) return JqArray.EMPTY;
        return JqArray.ofTrusted(new LazyArrayList(node));
    }

    static JsonNode originalNodeIfLazy(JqValue value) {
        if (value instanceof JqObject o && o.objectValue() instanceof LazyObjectMap map) {
            return map.source;
        }
        if (value instanceof JqArray a && a.arrayValue() instanceof LazyArrayList list) {
            return list.source;
        }
        return null;
    }

    static boolean isFullyConverted(JqObject value) {
        return value.objectValue() instanceof LazyObjectMap map && map.fullyConverted;
    }

    static int convertedEntryCount(JqObject value) {
        return value.objectValue() instanceof LazyObjectMap map ? map.converted.size() : -1;
    }

    /**
     * Map that lazily converts ObjectNode fields to JqValue on access.
     * Keys are available immediately; values are converted on demand.
     */
    private static final class LazyObjectMap extends AbstractMap<String, JqValue> {
        private final ObjectNode source;
        private final Map<String, JqValue> converted = new LinkedHashMap<>();
        private boolean fullyConverted;

        LazyObjectMap(ObjectNode source) {
            this.source = source;
        }

        @Override
        public JqValue get(Object key) {
            if (key instanceof String k) {
                JqValue cached = converted.get(k);
                if (cached != null) return cached;
                JsonNode child = source.get(k);
                if (child == null) return null;
                JqValue val = fromJsonNode(child);
                converted.put(k, val);
                return val;
            }
            return null;
        }

        @Override
        public boolean containsKey(Object key) {
            return key instanceof String k && source.has(k);
        }

        @Override
        public int size() {
            return source.size();
        }

        @Override
        public Set<String> keySet() {
            // Build key set from source to preserve insertion order
            var keys = new LinkedHashSet<String>();
            source.fieldNames().forEachRemaining(keys::add);
            return keys;
        }

        @Override
        public Set<Entry<String, JqValue>> entrySet() {
            if (fullyConverted) {
                return converted.entrySet();
            }
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<String, JqValue>> iterator() {
                    var fields = source.fields();
                    return new Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            return fields.hasNext();
                        }

                        @Override
                        public Entry<String, JqValue> next() {
                            var entry = fields.next();
                            String key = entry.getKey();
                            JqValue value = converted.get(key);
                            if (value == null) {
                                value = fromJsonNode(entry.getValue());
                            }
                            return new AbstractMap.SimpleImmutableEntry<>(key, value);
                        }
                    };
                }

                @Override
                public int size() {
                    return source.size();
                }
            };
        }

        public Collection<JqValue> values() {
            ensureFullyConverted();
            return converted.values();
        }

        private void ensureFullyConverted() {
            if (!fullyConverted) {
                var fields = source.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    converted.computeIfAbsent(entry.getKey(), k -> fromJsonNode(entry.getValue()));
                }
                fullyConverted = true;
            }
        }
    }

    /**
     * List that lazily converts ArrayNode elements to JqValue on access.
     * Each element is converted only when accessed by index.
     */
    private static final class LazyArrayList extends AbstractList<JqValue> {
        private final ArrayNode source;
        private final JqValue[] converted;

        LazyArrayList(ArrayNode source) {
            this.source = source;
            this.converted = new JqValue[source.size()];
        }

        @Override
        public JqValue get(int index) {
            JqValue cached = converted[index];
            if (cached != null) return cached;
            JqValue val = fromJsonNode(source.get(index));
            converted[index] = val;
            return val;
        }

        @Override
        public int size() {
            return source.size();
        }
    }
}

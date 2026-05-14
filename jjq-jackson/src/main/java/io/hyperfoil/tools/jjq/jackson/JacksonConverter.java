package io.hyperfoil.tools.jjq.jackson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import io.hyperfoil.tools.jjq.value.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Converts between Jackson {@link JsonNode} and jjq {@link JqValue}.
 *
 * <p>Use {@link #fromJsonNode(JsonNode)} to convert Jackson nodes to jjq values
 * for filter evaluation, and {@link #toJsonNode(JqValue)} to convert results back
 * to Jackson nodes for serialization in REST responses.
 *
 * <pre>{@code
 * JsonNode input = objectMapper.readTree(requestBody);
 * JqValue jqInput = JacksonConverter.fromJsonNode(input);
 * List<JqValue> results = program.applyAll(jqInput);
 * JsonNode output = JacksonConverter.toJsonNode(results.getFirst());
 * }</pre>
 */
public final class JacksonConverter {

    private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper();

    private JacksonConverter() {}

    /**
     * Convert a Jackson {@link JsonNode} to a {@link JqValue} with lazy conversion.
     * Nested objects and arrays are only converted when the filter accesses them.
     * This is the recommended method for large documents where only a subset
     * of fields are accessed by the filter.
     */
    public static JqValue fromJsonNodeLazy(JsonNode node) {
        return LazyJacksonConverter.fromJsonNode(node);
    }

    /**
     * Convert a Jackson {@link JsonNode} to a {@link JqValue}.
     * The entire tree is converted eagerly.
     */
    public static JqValue fromJsonNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return JqNull.NULL;
        }
        if (node.isBoolean()) {
            return JqBoolean.of(node.booleanValue());
        }
        if (node.isTextual()) {
            return JqString.of(node.textValue());
        }
        if (node.isNumber()) {
            return fromNumericNode(node);
        }
        if (node.isArray()) {
            var list = new ArrayList<JqValue>(node.size());
            for (JsonNode elem : node) {
                list.add(fromJsonNode(elem));
            }
            return JqArray.of(list);
        }
        if (node.isObject()) {
            var map = new LinkedHashMap<String, JqValue>();
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                map.put(entry.getKey(), fromJsonNode(entry.getValue()));
            }
            return JqObject.ofTrusted(map);
        }
        return JqNull.NULL;
    }

    private static JqValue fromNumericNode(JsonNode node) {
        if (node.isIntegralNumber()) {
            if (node.isBigInteger()) {
                return JqNumber.of(new BigDecimal(node.bigIntegerValue()));
            }
            return JqNumber.of(node.longValue());
        }
        if (node.isBigDecimal()) {
            return JqNumber.of(node.decimalValue());
        }
        return JqNumber.of(node.doubleValue());
    }

    /**
     * Convert a {@link JqValue} to a Jackson {@link JsonNode}.
     */
    public static JsonNode toJsonNode(JqValue value) {
        return toJsonNode(value, DEFAULT_MAPPER);
    }

    /**
     * Convert a {@link JqValue} to a Jackson {@link JsonNode} using the given mapper
     * for node creation.
     */
    public static JsonNode toJsonNode(JqValue value, ObjectMapper mapper) {
        JsonNode original = LazyJacksonConverter.originalNodeIfLazy(value);
        if (original != null) {
            return original;
        }
        return switch (value) {
            case JqNull ignored -> NullNode.getInstance();
            case JqBoolean b -> BooleanNode.valueOf(b.booleanValue());
            case JqString s -> new TextNode(s.stringValue());
            case JqNumber n -> numberToNode(n);
            case JqArray a -> {
                ArrayNode arr = mapper.createArrayNode();
                for (JqValue elem : a.arrayValue()) {
                    arr.add(toJsonNode(elem, mapper));
                }
                yield arr;
            }
            case JqObject o -> {
                ObjectNode obj = mapper.createObjectNode();
                for (var entry : o.objectValue().entrySet()) {
                    obj.set(entry.getKey(), toJsonNode(entry.getValue(), mapper));
                }
                yield obj;
            }
        };
    }

    private static JsonNode numberToNode(JqNumber n) {
        if (n.isIntegral()) {
            long l = n.longValue();
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                return IntNode.valueOf((int) l);
            }
            return LongNode.valueOf(l);
        }
        // Use DoubleNode for values that can be exactly represented as double,
        // matching Jackson's default parsing behavior
        BigDecimal bd = n.decimalValue();
        double d = bd.doubleValue();
        if (BigDecimal.valueOf(d).compareTo(bd) == 0) {
            return DoubleNode.valueOf(d);
        }
        return DecimalNode.valueOf(bd);
    }
}

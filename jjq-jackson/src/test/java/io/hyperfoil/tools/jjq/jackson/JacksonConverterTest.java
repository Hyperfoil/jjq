package io.hyperfoil.tools.jjq.jackson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import io.hyperfoil.tools.jjq.value.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class JacksonConverterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---- fromJsonNode ----

    @Test
    void nullNode() {
        assertEquals(JqNull.NULL, JacksonConverter.fromJsonNode(NullNode.getInstance()));
        assertEquals(JqNull.NULL, JacksonConverter.fromJsonNode(null));
    }

    @Test
    void booleanNode() {
        assertEquals(JqBoolean.TRUE, JacksonConverter.fromJsonNode(BooleanNode.TRUE));
        assertEquals(JqBoolean.FALSE, JacksonConverter.fromJsonNode(BooleanNode.FALSE));
    }

    @Test
    void textNode() {
        assertEquals(JqString.of("hello"), JacksonConverter.fromJsonNode(new TextNode("hello")));
        assertEquals(JqString.of(""), JacksonConverter.fromJsonNode(new TextNode("")));
    }

    @Test
    void integerNode() {
        JqValue result = JacksonConverter.fromJsonNode(IntNode.valueOf(42));
        assertInstanceOf(JqNumber.class, result);
        assertEquals(42L, ((JqNumber) result).longValue());
    }

    @Test
    void longNode() {
        JqValue result = JacksonConverter.fromJsonNode(LongNode.valueOf(Long.MAX_VALUE));
        assertInstanceOf(JqNumber.class, result);
        assertEquals(Long.MAX_VALUE, ((JqNumber) result).longValue());
    }

    @Test
    void doubleNode() {
        JqValue result = JacksonConverter.fromJsonNode(DoubleNode.valueOf(3.14));
        assertInstanceOf(JqNumber.class, result);
    }

    @Test
    void bigDecimalNode() {
        JqValue result = JacksonConverter.fromJsonNode(DecimalNode.valueOf(new BigDecimal("1.23456789012345678901234567890")));
        assertInstanceOf(JqNumber.class, result);
    }

    @Test
    void arrayNode() throws Exception {
        JsonNode node = MAPPER.readTree("[1, \"two\", true, null]");
        JqValue result = JacksonConverter.fromJsonNode(node);
        assertInstanceOf(JqArray.class, result);
        var arr = (JqArray) result;
        assertEquals(4, arr.arrayValue().size());
        assertEquals(JqNumber.of(1), arr.arrayValue().get(0));
        assertEquals(JqString.of("two"), arr.arrayValue().get(1));
        assertEquals(JqBoolean.TRUE, arr.arrayValue().get(2));
        assertEquals(JqNull.NULL, arr.arrayValue().get(3));
    }

    @Test
    void objectNode() throws Exception {
        JsonNode node = MAPPER.readTree("{\"name\":\"Alice\",\"age\":30}");
        JqValue result = JacksonConverter.fromJsonNode(node);
        assertInstanceOf(JqObject.class, result);
        var obj = (JqObject) result;
        assertEquals(JqString.of("Alice"), obj.objectValue().get("name"));
        assertEquals(JqNumber.of(30), obj.objectValue().get("age"));
    }

    @Test
    void nestedStructure() throws Exception {
        JsonNode node = MAPPER.readTree("{\"users\":[{\"name\":\"Alice\"},{\"name\":\"Bob\"}]}");
        JqValue result = JacksonConverter.fromJsonNode(node);
        assertInstanceOf(JqObject.class, result);
        assertEquals("{\"users\":[{\"name\":\"Alice\"},{\"name\":\"Bob\"}]}", result.toJsonString());
    }

    // ---- toJsonNode ----

    @Test
    void nullToNode() {
        assertTrue(JacksonConverter.toJsonNode(JqNull.NULL).isNull());
    }

    @Test
    void booleanToNode() {
        assertTrue(JacksonConverter.toJsonNode(JqBoolean.TRUE).isBoolean());
        assertTrue(JacksonConverter.toJsonNode(JqBoolean.TRUE).booleanValue());
        assertFalse(JacksonConverter.toJsonNode(JqBoolean.FALSE).booleanValue());
    }

    @Test
    void stringToNode() {
        JsonNode node = JacksonConverter.toJsonNode(JqString.of("hello"));
        assertTrue(node.isTextual());
        assertEquals("hello", node.textValue());
    }

    @Test
    void intToNode() {
        JsonNode node = JacksonConverter.toJsonNode(JqNumber.of(42));
        assertTrue(node.isInt());
        assertEquals(42, node.intValue());
    }

    @Test
    void longToNode() {
        JsonNode node = JacksonConverter.toJsonNode(JqNumber.of(Long.MAX_VALUE));
        assertTrue(node.isLong());
        assertEquals(Long.MAX_VALUE, node.longValue());
    }

    @Test
    void decimalToNode() {
        JsonNode node = JacksonConverter.toJsonNode(JqNumber.of(3.14));
        assertTrue(node.isNumber());
    }

    @Test
    void arrayToNode() {
        JqArray arr = JqArray.of(java.util.List.of(JqNumber.of(1), JqString.of("two")));
        JsonNode node = JacksonConverter.toJsonNode(arr);
        assertTrue(node.isArray());
        assertEquals(2, node.size());
        assertEquals(1, node.get(0).intValue());
        assertEquals("two", node.get(1).textValue());
    }

    @Test
    void objectToNode() {
        var map = new java.util.LinkedHashMap<String, JqValue>();
        map.put("name", JqString.of("Alice"));
        map.put("age", JqNumber.of(30));
        JqObject obj = JqObject.of(map);
        JsonNode node = JacksonConverter.toJsonNode(obj);
        assertTrue(node.isObject());
        assertEquals("Alice", node.get("name").textValue());
        assertEquals(30, node.get("age").intValue());
    }

    // ---- Round-trip ----

    @Test
    void roundTrip() throws Exception {
        String json = "{\"users\":[{\"name\":\"Alice\",\"active\":true,\"score\":98.5}],\"count\":1}";
        JsonNode original = MAPPER.readTree(json);
        JqValue jqValue = JacksonConverter.fromJsonNode(original);
        JsonNode restored = JacksonConverter.toJsonNode(jqValue);
        assertEquals(original, restored);
    }

    @Test
    void lazyIdentityPassthroughReturnsOriginalNode() throws Exception {
        JsonNode original = MAPPER.readTree("{\"f0\":0,\"f1\":1,\"f2\":2,\"f3\":3,\"f4\":4,\"f5\":5,\"f6\":6,\"f7\":7,\"f8\":8,\"f9\":9,\"f10\":10,\"f11\":11,\"f12\":12,\"f13\":13,\"f14\":14,\"f15\":15,\"f16\":16}");
        JqValue lazy = JacksonConverter.fromJsonNodeLazy(original);

        JsonNode restored = JacksonConverter.toJsonNode(lazy);
        assertSame(original, restored);
    }

}

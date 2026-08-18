package io.hyperfoil.tools.jjq.jackson;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyperfoil.tools.jjq.value.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JqValueModuleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JqValueModule());

    // ---- Serialization ----

    @Test
    void serializeNull() throws JsonProcessingException {
        assertEquals("null", MAPPER.writeValueAsString(JqNull.NULL));
    }

    @Test
    void serializeBoolean() throws JsonProcessingException {
        assertEquals("true", MAPPER.writeValueAsString(JqBoolean.TRUE));
        assertEquals("false", MAPPER.writeValueAsString(JqBoolean.FALSE));
    }

    @Test
    void serializeString() throws JsonProcessingException {
        assertEquals("\"hello\"", MAPPER.writeValueAsString(JqString.of("hello")));
        assertEquals("\"\"", MAPPER.writeValueAsString(JqString.of("")));
    }

    @Test
    void serializeStringWithEscapes() throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(JqString.of("line1\nline2\ttab"));
        assertTrue(json.contains("\\n"));
        assertTrue(json.contains("\\t"));
    }

    @Test
    void serializeIntegerNumber() throws JsonProcessingException {
        assertEquals("42", MAPPER.writeValueAsString(JqNumber.of(42)));
        assertEquals("0", MAPPER.writeValueAsString(JqNumber.of(0)));
        assertEquals("-1", MAPPER.writeValueAsString(JqNumber.of(-1)));
    }

    @Test
    void serializeLongNumber() throws JsonProcessingException {
        assertEquals(Long.toString(Long.MAX_VALUE), MAPPER.writeValueAsString(JqNumber.of(Long.MAX_VALUE)));
    }

    @Test
    void serializeDecimalNumber() throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(JqNumber.of(3.14));
        assertEquals("3.14", json);
    }

    @Test
    void serializeNaN() throws JsonProcessingException {
        assertEquals("null", MAPPER.writeValueAsString(JqNumber.of(Double.NaN)));
    }

    @Test
    void serializeInfinity() throws JsonProcessingException {
        assertEquals("null", MAPPER.writeValueAsString(JqNumber.of(Double.POSITIVE_INFINITY)));
    }

    @Test
    void serializeArray() throws JsonProcessingException {
        JqArray arr = JqArray.of(List.of(JqNumber.of(1), JqString.of("two"), JqBoolean.TRUE, JqNull.NULL));
        assertEquals("[1,\"two\",true,null]", MAPPER.writeValueAsString(arr));
    }

    @Test
    void serializeEmptyArray() throws JsonProcessingException {
        assertEquals("[]", MAPPER.writeValueAsString(JqArray.EMPTY));
    }

    @Test
    void serializeObject() throws JsonProcessingException {
        var map = new LinkedHashMap<String, JqValue>();
        map.put("name", JqString.of("Alice"));
        map.put("age", JqNumber.of(30));
        JqObject obj = JqObject.of(map);
        String json = MAPPER.writeValueAsString(obj);
        assertTrue(json.contains("\"name\":\"Alice\""));
        assertTrue(json.contains("\"age\":30"));
    }

    @Test
    void serializeEmptyObject() throws JsonProcessingException {
        assertEquals("{}", MAPPER.writeValueAsString(JqObject.EMPTY));
    }

    @Test
    void serializeNestedStructure() throws JsonProcessingException {
        var inner = new LinkedHashMap<String, JqValue>();
        inner.put("id", JqNumber.of(1));
        inner.put("tags", JqArray.of(List.of(JqString.of("a"), JqString.of("b"))));
        var outer = new LinkedHashMap<String, JqValue>();
        outer.put("item", JqObject.of(inner));
        String json = MAPPER.writeValueAsString(JqObject.of(outer));
        assertTrue(json.contains("\"item\":{"));
        assertTrue(json.contains("\"tags\":[\"a\",\"b\"]"));
    }

    // ---- Deserialization ----

    @Test
    void deserializeNull() throws JsonProcessingException {
        assertEquals(JqNull.NULL, MAPPER.readValue("null", JqValue.class));
    }

    @Test
    void deserializeBoolean() throws JsonProcessingException {
        assertEquals(JqBoolean.TRUE, MAPPER.readValue("true", JqValue.class));
        assertEquals(JqBoolean.FALSE, MAPPER.readValue("false", JqValue.class));
    }

    @Test
    void deserializeString() throws JsonProcessingException {
        JqValue result = MAPPER.readValue("\"hello\"", JqValue.class);
        assertInstanceOf(JqString.class, result);
        assertEquals("hello", result.stringValue());
    }

    @Test
    void deserializeInteger() throws JsonProcessingException {
        JqValue result = MAPPER.readValue("42", JqValue.class);
        assertInstanceOf(JqNumber.class, result);
        assertEquals(42L, result.longValue());
    }

    @Test
    void deserializeDecimal() throws JsonProcessingException {
        JqValue result = MAPPER.readValue("3.14", JqValue.class);
        assertInstanceOf(JqNumber.class, result);
    }

    @Test
    void deserializeArray() throws JsonProcessingException {
        JqValue result = MAPPER.readValue("[1,\"two\",true,null]", JqValue.class);
        assertInstanceOf(JqArray.class, result);
        var arr = (JqArray) result;
        assertEquals(4, arr.size());
        assertEquals(JqNumber.of(1), arr.get(0));
        assertEquals(JqString.of("two"), arr.get(1));
        assertEquals(JqBoolean.TRUE, arr.get(2));
        assertEquals(JqNull.NULL, arr.get(3));
    }

    @Test
    void deserializeObject() throws JsonProcessingException {
        JqValue result = MAPPER.readValue("{\"name\":\"Alice\",\"age\":30}", JqValue.class);
        assertInstanceOf(JqObject.class, result);
        var obj = (JqObject) result;
        assertEquals(JqString.of("Alice"), obj.get("name"));
        assertEquals(JqNumber.of(30), obj.get("age"));
    }

    @Test
    void deserializeNestedStructure() throws JsonProcessingException {
        JqValue result = MAPPER.readValue("{\"users\":[{\"name\":\"Alice\"},{\"name\":\"Bob\"}]}", JqValue.class);
        assertInstanceOf(JqObject.class, result);
        JqArray users = (JqArray) result.getField("users");
        assertEquals(2, users.size());
        assertEquals("Alice", users.get(0).getField("name").stringValue());
    }

    @Test
    void deserializeEmptyContainers() throws JsonProcessingException {
        JqValue arr = MAPPER.readValue("[]", JqValue.class);
        assertInstanceOf(JqArray.class, arr);
        assertEquals(0, arr.length());

        JqValue obj = MAPPER.readValue("{}", JqValue.class);
        assertInstanceOf(JqObject.class, obj);
        assertEquals(0, obj.length());
    }

    // ---- Deserialization to specific subtypes ----

    @Test
    void deserializeToJqObject() throws JsonProcessingException {
        JqObject obj = MAPPER.readValue("{\"x\":1}", JqObject.class);
        assertEquals(JqNumber.of(1), obj.get("x"));
    }

    @Test
    void deserializeToJqArray() throws JsonProcessingException {
        JqArray arr = MAPPER.readValue("[1,2,3]", JqArray.class);
        assertEquals(3, arr.size());
    }

    @Test
    void deserializeToJqString() throws JsonProcessingException {
        JqString s = MAPPER.readValue("\"hello\"", JqString.class);
        assertEquals("hello", s.stringValue());
    }

    @Test
    void deserializeToJqNumber() throws JsonProcessingException {
        JqNumber n = MAPPER.readValue("42", JqNumber.class);
        assertEquals(42L, n.longValue());
    }

    // ---- Round-trip ----

    @Test
    void roundTrip() throws JsonProcessingException {
        String json = "{\"users\":[{\"name\":\"Alice\",\"active\":true,\"score\":98.5}],\"count\":1}";
        JqValue parsed = MAPPER.readValue(json, JqValue.class);
        String serialized = MAPPER.writeValueAsString(parsed);
        JqValue reparsed = MAPPER.readValue(serialized, JqValue.class);
        assertEquals(parsed, reparsed);
    }

    // ---- POJO with JqValue field ----

    record TestEntity(String name, JqValue metadata) {}

    @Test
    void pojoWithJqValueField() throws JsonProcessingException {
        var metadata = JqObject.of(Map.of("key", JqString.of("value")));
        TestEntity entity = new TestEntity("test", metadata);
        String json = MAPPER.writeValueAsString(entity);
        assertTrue(json.contains("\"name\":\"test\""));
        assertTrue(json.contains("\"metadata\":{\"key\":\"value\"}"));

        TestEntity restored = MAPPER.readValue(json, TestEntity.class);
        assertEquals("test", restored.name());
        assertInstanceOf(JqObject.class, restored.metadata());
        assertEquals("value", restored.metadata().getField("key").stringValue());
    }

    @Test
    void pojoWithNullJqValueField() throws JsonProcessingException {
        TestEntity entity = new TestEntity("test", null);
        String json = MAPPER.writeValueAsString(entity);
        TestEntity restored = MAPPER.readValue(json, TestEntity.class);
        // JSON null deserializes to JqNull.NULL, not Java null
        assertEquals(JqNull.NULL, restored.metadata());
    }

    @Test
    void pojoWithJqNullField() throws JsonProcessingException {
        TestEntity entity = new TestEntity("test", JqNull.NULL);
        String json = MAPPER.writeValueAsString(entity);
        assertTrue(json.contains("\"metadata\":null"));
        TestEntity restored = MAPPER.readValue(json, TestEntity.class);
        assertEquals(JqNull.NULL, restored.metadata());
    }
}

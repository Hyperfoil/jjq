package io.hyperfoil.tools.jjq.jakarta;

import io.hyperfoil.tools.jjq.value.*;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JqValueJsonbTest {

    private static Jsonb jsonb;

    @BeforeAll
    static void setUp() {
        JsonbConfig config = new JsonbConfig()
                .withSerializers(new JqValueJsonbSerializer())
                .withDeserializers(new JqValueJsonbDeserializer());
        jsonb = JsonbBuilder.create(config);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (jsonb != null) {
            jsonb.close();
        }
    }

    // ---- Serialization ----

    @Test
    void serializeNull() {
        assertEquals("null", jsonb.toJson(JqNull.NULL, JqValue.class));
    }

    @Test
    void serializeBoolean() {
        assertEquals("true", jsonb.toJson(JqBoolean.TRUE, JqValue.class));
        assertEquals("false", jsonb.toJson(JqBoolean.FALSE, JqValue.class));
    }

    @Test
    void serializeString() {
        assertEquals("\"hello\"", jsonb.toJson(JqString.of("hello"), JqValue.class));
    }

    @Test
    void serializeInteger() {
        assertEquals("42", jsonb.toJson(JqNumber.of(42), JqValue.class));
    }

    @Test
    void serializeDecimal() {
        String json = jsonb.toJson(JqNumber.of(3.14), JqValue.class);
        assertEquals("3.14", json);
    }

    @Test
    void serializeNaN() {
        assertEquals("null", jsonb.toJson(JqNumber.of(Double.NaN), JqValue.class));
    }

    @Test
    void serializeArray() {
        JqArray arr = JqArray.of(List.of(JqNumber.of(1), JqString.of("two"), JqBoolean.TRUE));
        String json = jsonb.toJson(arr, JqValue.class);
        assertEquals("[1,\"two\",true]", json);
    }

    @Test
    void serializeEmptyArray() {
        assertEquals("[]", jsonb.toJson(JqArray.EMPTY, JqValue.class));
    }

    @Test
    void serializeObject() {
        var map = new LinkedHashMap<String, JqValue>();
        map.put("name", JqString.of("Alice"));
        map.put("age", JqNumber.of(30));
        JqObject obj = JqObject.of(map);
        String json = jsonb.toJson(obj, JqValue.class);
        assertTrue(json.contains("\"name\":\"Alice\""));
        assertTrue(json.contains("\"age\":30"));
    }

    @Test
    void serializeEmptyObject() {
        assertEquals("{}", jsonb.toJson(JqObject.EMPTY, JqValue.class));
    }

    @Test
    void serializeNestedStructure() {
        var inner = new LinkedHashMap<String, JqValue>();
        inner.put("id", JqNumber.of(1));
        inner.put("tags", JqArray.of(List.of(JqString.of("a"), JqString.of("b"))));
        var outer = new LinkedHashMap<String, JqValue>();
        outer.put("item", JqObject.of(inner));
        String json = jsonb.toJson(JqObject.of(outer), JqValue.class);
        assertTrue(json.contains("\"item\":{"));
        assertTrue(json.contains("\"tags\":[\"a\",\"b\"]"));
    }

    // ---- Deserialization ----

    @Test
    void deserializeNull() {
        JqValue result = jsonb.fromJson("null", JqValue.class);
        assertEquals(JqNull.NULL, result);
    }

    @Test
    void deserializeBoolean() {
        assertEquals(JqBoolean.TRUE, jsonb.fromJson("true", JqValue.class));
        assertEquals(JqBoolean.FALSE, jsonb.fromJson("false", JqValue.class));
    }

    @Test
    void deserializeString() {
        JqValue result = jsonb.fromJson("\"hello\"", JqValue.class);
        assertInstanceOf(JqString.class, result);
        assertEquals("hello", result.stringValue());
    }

    @Test
    void deserializeInteger() {
        JqValue result = jsonb.fromJson("42", JqValue.class);
        assertInstanceOf(JqNumber.class, result);
        assertEquals(42L, result.longValue());
    }

    @Test
    void deserializeDecimal() {
        JqValue result = jsonb.fromJson("3.14", JqValue.class);
        assertInstanceOf(JqNumber.class, result);
    }

    @Test
    void deserializeArray() {
        JqValue result = jsonb.fromJson("[1,\"two\",true,null]", JqValue.class);
        assertInstanceOf(JqArray.class, result);
        var arr = (JqArray) result;
        assertEquals(4, arr.size());
        assertEquals(JqNumber.of(1), arr.get(0));
        assertEquals(JqString.of("two"), arr.get(1));
        assertEquals(JqBoolean.TRUE, arr.get(2));
        assertEquals(JqNull.NULL, arr.get(3));
    }

    @Test
    void deserializeObject() {
        JqValue result = jsonb.fromJson("{\"name\":\"Alice\",\"age\":30}", JqValue.class);
        assertInstanceOf(JqObject.class, result);
        var obj = (JqObject) result;
        assertEquals(JqString.of("Alice"), obj.get("name"));
        assertEquals(JqNumber.of(30), obj.get("age"));
    }

    @Test
    void deserializeNestedStructure() {
        JqValue result = jsonb.fromJson("{\"users\":[{\"name\":\"Alice\"},{\"name\":\"Bob\"}]}", JqValue.class);
        assertInstanceOf(JqObject.class, result);
        JqArray users = (JqArray) result.getField("users");
        assertEquals(2, users.size());
        assertEquals("Alice", users.get(0).getField("name").stringValue());
    }

    @Test
    void deserializeEmptyContainers() {
        JqValue arr = jsonb.fromJson("[]", JqValue.class);
        assertInstanceOf(JqArray.class, arr);
        assertEquals(0, arr.length());

        JqValue obj = jsonb.fromJson("{}", JqValue.class);
        assertInstanceOf(JqObject.class, obj);
        assertEquals(0, obj.length());
    }

    // ---- Round-trip ----

    @Test
    void roundTrip() {
        String json = "{\"users\":[{\"name\":\"Alice\",\"active\":true,\"score\":98.5}],\"count\":1}";
        JqValue parsed = jsonb.fromJson(json, JqValue.class);
        String serialized = jsonb.toJson(parsed, JqValue.class);
        JqValue reparsed = jsonb.fromJson(serialized, JqValue.class);
        assertEquals(parsed, reparsed);
    }

    // ---- POJO with JqValue field ----

    public static class TestEntity {
        public String name;
        public JqValue metadata;

        public TestEntity() {}
        public TestEntity(String name, JqValue metadata) {
            this.name = name;
            this.metadata = metadata;
        }
    }

    @Test
    void pojoWithJqValueField() {
        var metadata = JqObject.of(java.util.Map.of("key", JqString.of("value")));
        TestEntity entity = new TestEntity("test", metadata);
        String json = jsonb.toJson(entity);
        assertTrue(json.contains("\"name\":\"test\""));
        assertTrue(json.contains("\"metadata\":{\"key\":\"value\"}"));

        TestEntity restored = jsonb.fromJson(json, TestEntity.class);
        assertEquals("test", restored.name);
        assertInstanceOf(JqObject.class, restored.metadata);
        assertEquals("value", restored.metadata.getField("key").stringValue());
    }
}

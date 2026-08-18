package io.hyperfoil.tools.jjq.jakarta;

import io.hyperfoil.tools.jjq.value.*;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JqValueConverterTest {

    private final JqValueConverter converter = new JqValueConverter();

    // ---- convertToDatabaseColumn ----

    @Test
    void toDatabaseColumn_null() {
        assertNull(converter.convertToDatabaseColumn(null));
    }

    @Test
    void toDatabaseColumn_object() {
        var map = new LinkedHashMap<String, JqValue>();
        map.put("name", JqString.of("Alice"));
        map.put("age", JqNumber.of(30));
        JqObject obj = JqObject.of(map);
        assertEquals("{\"name\":\"Alice\",\"age\":30}", converter.convertToDatabaseColumn(obj));
    }

    @Test
    void toDatabaseColumn_array() {
        JqArray arr = JqArray.of(List.of(JqNumber.of(1), JqNumber.of(2), JqNumber.of(3)));
        assertEquals("[1,2,3]", converter.convertToDatabaseColumn(arr));
    }

    @Test
    void toDatabaseColumn_scalars() {
        assertEquals("\"hello\"", converter.convertToDatabaseColumn(JqString.of("hello")));
        assertEquals("42", converter.convertToDatabaseColumn(JqNumber.of(42)));
        assertEquals("true", converter.convertToDatabaseColumn(JqBoolean.TRUE));
        assertEquals("false", converter.convertToDatabaseColumn(JqBoolean.FALSE));
        assertEquals("null", converter.convertToDatabaseColumn(JqNull.NULL));
    }

    // ---- convertToEntityAttribute ----

    @Test
    void toEntityAttribute_null() {
        assertNull(converter.convertToEntityAttribute(null));
    }

    @Test
    void toEntityAttribute_empty() {
        assertNull(converter.convertToEntityAttribute(""));
    }

    @Test
    void toEntityAttribute_object() {
        JqValue result = converter.convertToEntityAttribute("{\"name\":\"Alice\",\"age\":30}");
        assertInstanceOf(JqObject.class, result);
        assertEquals("Alice", result.getField("name").stringValue());
        assertEquals(30L, result.getField("age").longValue());
    }

    @Test
    void toEntityAttribute_array() {
        JqValue result = converter.convertToEntityAttribute("[1,2,3]");
        assertInstanceOf(JqArray.class, result);
        assertEquals(3, result.length());
    }

    @Test
    void toEntityAttribute_scalars() {
        assertEquals(JqString.of("hello"), converter.convertToEntityAttribute("\"hello\""));
        assertEquals(JqNumber.of(42), converter.convertToEntityAttribute("42"));
        assertEquals(JqBoolean.TRUE, converter.convertToEntityAttribute("true"));
        assertEquals(JqBoolean.FALSE, converter.convertToEntityAttribute("false"));
        assertEquals(JqNull.NULL, converter.convertToEntityAttribute("null"));
    }

    // ---- Round-trip ----

    @Test
    void roundTrip_object() {
        var map = new LinkedHashMap<String, JqValue>();
        map.put("key", JqString.of("value"));
        map.put("count", JqNumber.of(42));
        map.put("active", JqBoolean.TRUE);
        JqObject original = JqObject.of(map);

        String dbValue = converter.convertToDatabaseColumn(original);
        JqValue restored = converter.convertToEntityAttribute(dbValue);
        assertEquals(original, restored);
    }

    @Test
    void roundTrip_nestedStructure() {
        String json = "{\"data\":{\"items\":[{\"id\":1,\"name\":\"first\"},{\"id\":2,\"name\":\"second\"}]}}";
        JqValue original = JqValues.parse(json);
        String dbValue = converter.convertToDatabaseColumn(original);
        JqValue restored = converter.convertToEntityAttribute(dbValue);
        assertEquals(original, restored);
    }

    @Test
    void roundTrip_stringWithEscapes() {
        JqValue original = JqValues.parse("{\"msg\":\"hello\\nworld\\t!\"}");
        String dbValue = converter.convertToDatabaseColumn(original);
        JqValue restored = converter.convertToEntityAttribute(dbValue);
        assertEquals(original, restored);
    }
}

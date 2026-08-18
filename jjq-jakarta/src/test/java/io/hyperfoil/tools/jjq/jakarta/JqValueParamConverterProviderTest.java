package io.hyperfoil.tools.jjq.jakarta;

import io.hyperfoil.tools.jjq.value.*;
import jakarta.ws.rs.ext.ParamConverter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JqValueParamConverterProviderTest {

    private final JqValueParamConverterProvider provider = new JqValueParamConverterProvider();

    @SuppressWarnings("unchecked")
    private ParamConverter<JqValue> converter() {
        return (ParamConverter<JqValue>) (ParamConverter<?>)
                provider.getConverter(JqValue.class, JqValue.class, null);
    }

    // ---- Provider ----

    @Test
    void returnsConverterForJqValue() {
        assertNotNull(provider.getConverter(JqValue.class, JqValue.class, null));
    }

    @Test
    void returnsConverterForJqObjectSubtype() {
        assertNotNull(provider.getConverter(JqObject.class, JqObject.class, null));
    }

    @Test
    void returnsNullForUnrelatedTypes() {
        assertNull(provider.getConverter(String.class, String.class, null));
        assertNull(provider.getConverter(Object.class, Object.class, null));
        assertNull(provider.getConverter(Integer.class, Integer.class, null));
    }

    // ---- fromString ----

    @Test
    void fromString_null() {
        assertNull(converter().fromString(null));
    }

    @Test
    void fromString_empty() {
        assertNull(converter().fromString(""));
    }

    @Test
    void fromString_object() {
        JqValue result = converter().fromString("{\"name\":\"Alice\",\"age\":30}");
        assertInstanceOf(JqObject.class, result);
        assertEquals("Alice", result.getField("name").stringValue());
        assertEquals(30L, result.getField("age").longValue());
    }

    @Test
    void fromString_array() {
        JqValue result = converter().fromString("[1,2,3]");
        assertInstanceOf(JqArray.class, result);
        assertEquals(3, result.length());
    }

    @Test
    void fromString_scalars() {
        assertEquals(JqNumber.of(42), converter().fromString("42"));
        assertEquals(JqString.of("hello"), converter().fromString("\"hello\""));
        assertEquals(JqBoolean.TRUE, converter().fromString("true"));
        assertEquals(JqNull.NULL, converter().fromString("null"));
    }

    // ---- toString ----

    @Test
    void toString_null() {
        assertNull(converter().toString(null));
    }

    @Test
    void toString_object() {
        JqValue obj = JqValues.parse("{\"key\":\"value\"}");
        assertEquals("{\"key\":\"value\"}", converter().toString(obj));
    }

    @Test
    void toString_scalar() {
        assertEquals("42", converter().toString(JqNumber.of(42)));
        assertEquals("\"hello\"", converter().toString(JqString.of("hello")));
    }

    // ---- Round-trip ----

    @Test
    void roundTrip() {
        String json = "{\"filter\":{\"status\":\"active\"},\"limit\":10}";
        JqValue parsed = converter().fromString(json);
        String serialized = converter().toString(parsed);
        JqValue reparsed = converter().fromString(serialized);
        assertEquals(parsed, reparsed);
    }
}

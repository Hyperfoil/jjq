package io.hyperfoil.tools.jjq.jakarta;

import io.hyperfoil.tools.jjq.value.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JqValueFormatMapperTest {

    private final JqValueFormatMapper mapper = new JqValueFormatMapper();

    @Test
    void nullInput_returnsNull() {
        assertNull(mapper.fromString(null, null, null));
        assertNull(mapper.toString(null, null, null));
    }

    @Test
    void roundTrip_object() {
        String json = "{\"name\":\"Alice\",\"age\":30,\"active\":true}";
        JqValue parsed = (JqValue) mapper.fromString(json, new SimpleJavaType<>(JqValue.class), null);

        assertInstanceOf(JqObject.class, parsed);
        JqObject obj = (JqObject) parsed;
        assertEquals("Alice", ((JqString) obj.get("name")).stringValue());
        assertEquals(30, ((JqNumber) obj.get("age")).longValue());
        assertEquals(true, ((JqBoolean) obj.get("active")).booleanValue());

        String serialized = mapper.toString(parsed, new SimpleJavaType<>(JqValue.class), null);
        assertNotNull(serialized);

        // Re-parse and verify
        JqValue reparsed = (JqValue) mapper.fromString(serialized, new SimpleJavaType<>(JqValue.class), null);
        assertEquals(parsed, reparsed);
    }

    @Test
    void roundTrip_array() {
        String json = "[1,2,3,\"hello\",null,true]";
        JqValue parsed = (JqValue) mapper.fromString(json, new SimpleJavaType<>(JqValue.class), null);

        assertInstanceOf(JqArray.class, parsed);
        JqArray arr = (JqArray) parsed;
        assertEquals(6, arr.arrayValue().size());

        String serialized = mapper.toString(parsed, new SimpleJavaType<>(JqValue.class), null);
        JqValue reparsed = (JqValue) mapper.fromString(serialized, new SimpleJavaType<>(JqValue.class), null);
        assertEquals(parsed, reparsed);
    }

    @Test
    void roundTrip_nestedStructure() {
        String json = "{\"data\":{\"items\":[{\"id\":1,\"name\":\"first\"},{\"id\":2,\"name\":\"second\"}]}}";
        JqValue parsed = (JqValue) mapper.fromString(json, new SimpleJavaType<>(JqValue.class), null);

        String serialized = mapper.toString(parsed, new SimpleJavaType<>(JqValue.class), null);
        JqValue reparsed = (JqValue) mapper.fromString(serialized, new SimpleJavaType<>(JqValue.class), null);
        assertEquals(parsed, reparsed);
    }

    @Test
    void roundTrip_numericTypes() {
        // Integer
        String intJson = "42";
        JqValue intVal = (JqValue) mapper.fromString(intJson, new SimpleJavaType<>(JqValue.class), null);
        assertEquals("42", mapper.toString(intVal, new SimpleJavaType<>(JqValue.class), null));

        // Decimal
        String decJson = "3.14";
        JqValue decVal = (JqValue) mapper.fromString(decJson, new SimpleJavaType<>(JqValue.class), null);
        assertEquals("3.14", mapper.toString(decVal, new SimpleJavaType<>(JqValue.class), null));
    }

    @Test
    void roundTrip_stringWithEscapes() {
        String json = "{\"msg\":\"hello\\nworld\\t!\"}";
        JqValue parsed = (JqValue) mapper.fromString(json, new SimpleJavaType<>(JqValue.class), null);

        String serialized = mapper.toString(parsed, new SimpleJavaType<>(JqValue.class), null);
        JqValue reparsed = (JqValue) mapper.fromString(serialized, new SimpleJavaType<>(JqValue.class), null);
        assertEquals(parsed, reparsed);
    }

    @Test
    void roundTrip_nullValue() {
        String json = "null";
        JqValue parsed = (JqValue) mapper.fromString(json, new SimpleJavaType<>(JqValue.class), null);
        assertInstanceOf(JqNull.class, parsed);

        String serialized = mapper.toString(parsed, new SimpleJavaType<>(JqValue.class), null);
        assertEquals("null", serialized);
    }

    @Test
    void roundTrip_booleanValues() {
        JqValue trueVal = (JqValue) mapper.fromString("true", new SimpleJavaType<>(JqValue.class), null);
        JqValue falseVal = (JqValue) mapper.fromString("false", new SimpleJavaType<>(JqValue.class), null);

        assertEquals("true", mapper.toString(trueVal, new SimpleJavaType<>(JqValue.class), null));
        assertEquals("false", mapper.toString(falseVal, new SimpleJavaType<>(JqValue.class), null));
    }

    @Test
    void roundTrip_emptyContainers() {
        JqValue emptyObj = (JqValue) mapper.fromString("{}", new SimpleJavaType<>(JqValue.class), null);
        JqValue emptyArr = (JqValue) mapper.fromString("[]", new SimpleJavaType<>(JqValue.class), null);

        assertEquals("{}", mapper.toString(emptyObj, new SimpleJavaType<>(JqValue.class), null));
        assertEquals("[]", mapper.toString(emptyArr, new SimpleJavaType<>(JqValue.class), null));
    }

    /**
     * Minimal JavaType implementation for testing.
     * Only {@code getReturnedClass()} is used by JqValueFormatMapper.
     */
    private static class SimpleJavaType<T> implements org.hibernate.type.descriptor.java.JavaType<T> {
        private final Class<T> clazz;

        SimpleJavaType(Class<T> clazz) {
            this.clazz = clazz;
        }

        @Override
        public Class<T> getJavaTypeClass() {
            return clazz;
        }

        // All other methods throw -- not needed for testing
        @Override public org.hibernate.type.descriptor.jdbc.JdbcType getRecommendedJdbcType(org.hibernate.type.descriptor.jdbc.JdbcTypeIndicators indicators) { throw new UnsupportedOperationException(); }
        @Override public T fromString(CharSequence string) { throw new UnsupportedOperationException(); }
        @Override public <X> X unwrap(T value, Class<X> type, org.hibernate.type.descriptor.WrapperOptions options) { throw new UnsupportedOperationException(); }
        @Override public <X> T wrap(X value, org.hibernate.type.descriptor.WrapperOptions options) { throw new UnsupportedOperationException(); }
    }
}

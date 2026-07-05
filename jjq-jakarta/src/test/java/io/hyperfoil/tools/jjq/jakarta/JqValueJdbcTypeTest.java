package io.hyperfoil.tools.jjq.jakarta;

import io.hyperfoil.tools.jjq.value.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JqValueJdbcType} and {@link JqValueJavaType}.
 * Tests the JavaType unwrap/wrap methods directly (the core logic that
 * the JdbcType's binder/extractor delegate to) and the JdbcType metadata.
 */
class JqValueJdbcTypeTest {

    private final JqValueJavaType javaType = JqValueJavaType.INSTANCE;
    private final JqValueJdbcType jdbcType = JqValueJdbcType.INSTANCE;

    // ========================================================================
    //  JdbcType metadata
    // ========================================================================

    @Test
    void jdbcTypeCode() {
        assertEquals(Types.VARBINARY, jdbcType.getJdbcTypeCode());
    }

    @Test
    void friendlyName() {
        assertEquals("JqValueBinary", jdbcType.getFriendlyName());
    }

    @Test
    void binderNotNull() {
        assertNotNull(jdbcType.getBinder(javaType));
    }

    @Test
    void extractorNotNull() {
        assertNotNull(jdbcType.getExtractor(javaType));
    }

    // ========================================================================
    //  JavaType — unwrap to byte[]
    // ========================================================================

    @Test
    void unwrapObjectToBytes() {
        JqValue value = JqValues.parse("{\"name\":\"Alice\",\"age\":30}");
        byte[] bytes = javaType.unwrap(value, byte[].class, null);
        assertNotNull(bytes);
        assertEquals(value.toJsonString(), new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    void unwrapArrayToBytes() {
        JqValue value = JqValues.parse("[1,2,3]");
        byte[] bytes = javaType.unwrap(value, byte[].class, null);
        assertEquals("[1,2,3]", new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    void unwrapScalarsToBytes() {
        assertBytesEqual("null", JqNull.NULL);
        assertBytesEqual("true", JqBoolean.TRUE);
        assertBytesEqual("false", JqBoolean.FALSE);
        assertBytesEqual("42", JqNumber.of(42));
        assertBytesEqual("3.14", JqNumber.of(3.14));
        assertBytesEqual("\"hello\"", JqString.of("hello"));
    }

    @Test
    void unwrapNullToBytes() {
        assertNull(javaType.unwrap(null, byte[].class, null));
    }

    // ========================================================================
    //  JavaType — unwrap to String
    // ========================================================================

    @Test
    void unwrapObjectToString() {
        JqValue value = JqValues.parse("{\"key\":\"value\"}");
        String json = javaType.unwrap(value, String.class, null);
        assertEquals(value.toJsonString(), json);
    }

    @Test
    void unwrapNullToString() {
        assertNull(javaType.unwrap(null, String.class, null));
    }

    // ========================================================================
    //  JavaType — unwrap to JqValue (identity)
    // ========================================================================

    @Test
    void unwrapToJqValue() {
        JqValue value = JqValues.parse("{\"a\":1}");
        JqValue result = javaType.unwrap(value, JqValue.class, null);
        assertSame(value, result);
    }

    // ========================================================================
    //  JavaType — wrap from byte[]
    // ========================================================================

    @Test
    void wrapBytesToJqObject() {
        byte[] bytes = "{\"name\":\"Bob\",\"age\":25}".getBytes(StandardCharsets.UTF_8);
        JqValue result = javaType.wrap(bytes, null);
        assertInstanceOf(JqObject.class, result);
        assertEquals("Bob", ((JqObject) result).get("name").stringValue());
        assertEquals(25, ((JqObject) result).get("age").intValue());
    }

    @Test
    void wrapBytesToJqArray() {
        byte[] bytes = "[true,false,null]".getBytes(StandardCharsets.UTF_8);
        JqValue result = javaType.wrap(bytes, null);
        assertInstanceOf(JqArray.class, result);
        assertEquals(3, result.length());
    }

    @Test
    void wrapBytesToScalars() {
        assertEquals(JqNull.NULL, javaType.wrap("null".getBytes(StandardCharsets.UTF_8), null));
        assertEquals(JqBoolean.TRUE, javaType.wrap("true".getBytes(StandardCharsets.UTF_8), null));
        assertEquals(JqNumber.of(42), javaType.wrap("42".getBytes(StandardCharsets.UTF_8), null));
        assertEquals(JqString.of("hi"), javaType.wrap("\"hi\"".getBytes(StandardCharsets.UTF_8), null));
    }

    @Test
    void wrapNullBytes() {
        assertNull(javaType.wrap((byte[]) null, null));
    }

    // ========================================================================
    //  JavaType — wrap from String
    // ========================================================================

    @Test
    void wrapStringToJqValue() {
        JqValue result = javaType.wrap("{\"x\":1}", null);
        assertInstanceOf(JqObject.class, result);
        assertEquals(1, ((JqObject) result).get("x").intValue());
    }

    @Test
    void wrapNullString() {
        assertNull(javaType.wrap((String) null, null));
    }

    // ========================================================================
    //  JavaType — wrap from JqValue (identity)
    // ========================================================================

    @Test
    void wrapJqValueIdentity() {
        JqValue value = JqValues.parse("[1,2]");
        JqValue result = javaType.wrap(value, null);
        assertSame(value, result);
    }

    // ========================================================================
    //  JavaType — fromString / toString
    // ========================================================================

    @Test
    void fromStringParsesJson() {
        JqValue result = javaType.fromString("{\"key\":\"value\"}");
        assertInstanceOf(JqObject.class, result);
    }

    @Test
    void fromStringNull() {
        assertNull(javaType.fromString(null));
    }

    @Test
    void toStringSerializesJson() {
        JqValue value = JqValues.parse("{\"a\":1}");
        assertEquals(value.toJsonString(), javaType.toString(value));
    }

    @Test
    void toStringNull() {
        assertNull(javaType.toString(null));
    }

    // ========================================================================
    //  JavaType — immutability
    // ========================================================================

    @Test
    void mutabilityPlanIsImmutable() {
        assertFalse(javaType.getMutabilityPlan().isMutable());
    }

    @Test
    void javaTypeClass() {
        assertEquals(JqValue.class, javaType.getJavaTypeClass());
    }

    // ========================================================================
    //  Round-trip: byte[] → JqValue → byte[]
    // ========================================================================

    @Test
    void roundTripBytes() {
        String json = "{\"users\":[{\"name\":\"Alice\",\"scores\":[95,87.5]},{\"name\":\"Bob\",\"scores\":[]}],\"count\":2}";
        byte[] original = json.getBytes(StandardCharsets.UTF_8);

        // Simulate: read from DB (wrap bytes), then write back (unwrap to bytes)
        JqValue value = javaType.wrap(original, null);
        byte[] output = javaType.unwrap(value, byte[].class, null);

        // Parse both and compare (byte order may differ due to object key ordering)
        JqValue reparsed = JqValues.parse(output);
        assertEquals(value, reparsed);
    }

    @Test
    void roundTripNestedStructure() {
        JqValue original = JqValues.parse(
                "{\"data\":{\"items\":[{\"id\":1,\"value\":\"test\"},{\"id\":2,\"value\":null}]}}");

        // unwrap → byte[] → wrap → JqValue
        byte[] bytes = javaType.unwrap(original, byte[].class, null);
        JqValue restored = javaType.wrap(bytes, null);
        assertEquals(original, restored);
    }

    @Test
    void roundTripUnicode() {
        JqValue original = JqObject.builder()
                .put("name", "caf\u00e9")       // 2-byte UTF-8
                .put("city", "\u4e16\u754c")     // 3-byte UTF-8 (世界)
                .build();

        byte[] bytes = javaType.unwrap(original, byte[].class, null);
        JqValue restored = javaType.wrap(bytes, null);
        assertEquals(original, restored);
    }

    // ========================================================================
    //  Error handling
    // ========================================================================

    @Test
    void unwrapToUnknownTypeThrows() {
        JqValue value = JqNull.NULL;
        assertThrows(Exception.class, () -> javaType.unwrap(value, Integer.class, null));
    }

    @Test
    void wrapFromUnknownTypeThrows() {
        assertThrows(Exception.class, () -> javaType.wrap(42, null));
    }

    // ========================================================================
    //  Helpers
    // ========================================================================

    private void assertBytesEqual(String expectedJson, JqValue value) {
        byte[] bytes = javaType.unwrap(value, byte[].class, null);
        assertEquals(expectedJson, new String(bytes, StandardCharsets.UTF_8));
    }
}

package io.hyperfoil.tools.jjq.value;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JqValueTest {

    @Test
    void testNull() {
        assertEquals(JqValue.Type.NULL, JqNull.NULL.type());
        assertFalse(JqNull.NULL.isTruthy());
        assertEquals("null", JqNull.NULL.toJsonString());
        assertEquals(0, JqNull.NULL.length());
    }

    @Test
    void testBoolean() {
        assertTrue(JqBoolean.TRUE.isTruthy());
        assertFalse(JqBoolean.FALSE.isTruthy());
        assertEquals("true", JqBoolean.TRUE.toJsonString());
        assertEquals("false", JqBoolean.FALSE.toJsonString());
        assertSame(JqBoolean.TRUE, JqBoolean.of(true));
        assertSame(JqBoolean.FALSE, JqBoolean.of(false));
    }

    @Test
    void testNumber() {
        assertEquals(JqNumber.of(42), JqNumber.of(42));
        assertEquals(JqNumber.of(42), JqNumber.of(42L));
        assertEquals("42", JqNumber.of(42).toJsonString());
        assertEquals("3.14", JqNumber.of(3.14).toJsonString());
        assertTrue(JqNumber.of(0).isTruthy()); // numbers are truthy
    }

    @Test
    void testNumberCache() {
        assertSame(JqNumber.of(0), JqNumber.of(0));
        assertSame(JqNumber.of(1), JqNumber.of(1));
        assertSame(JqNumber.of(-1), JqNumber.of(-1));
        assertSame(JqNumber.of(127), JqNumber.of(127));
    }

    @Test
    void testString() {
        var s = JqString.of("hello");
        assertEquals("hello", s.stringValue());
        assertEquals("\"hello\"", s.toJsonString());
        assertEquals(5, s.length());

        // Escape sequences
        assertEquals("\"he\\nllo\"", JqString.of("he\nllo").toJsonString());
        assertEquals("\"he\\\"llo\"", JqString.of("he\"llo").toJsonString());
    }

    @Test
    void testArray() {
        var arr = JqArray.of(JqNumber.of(1), JqNumber.of(2), JqNumber.of(3));
        assertEquals(3, arr.length());
        assertEquals("[1,2,3]", arr.toJsonString());
        assertEquals(JqNumber.of(1), arr.get(0));
        assertEquals(JqNumber.of(3), arr.get(-1));
        assertEquals(JqNull.NULL, arr.get(10));
    }

    @Test
    void testArraySlice() {
        var arr = JqArray.of(JqNumber.of(1), JqNumber.of(2), JqNumber.of(3), JqNumber.of(4));
        assertEquals("[2,3]", arr.slice(1, 3).toJsonString());
        assertEquals("[3,4]", arr.slice(2, null).toJsonString());
        assertEquals("[1,2]", arr.slice(null, 2).toJsonString());
    }

    @Test
    void testObject() {
        var map = new LinkedHashMap<String, JqValue>();
        map.put("name", JqString.of("Alice"));
        map.put("age", JqNumber.of(30));
        var obj = JqObject.of(map);
        assertEquals(2, obj.length());
        assertEquals(JqString.of("Alice"), obj.get("name"));
        assertEquals(JqNull.NULL, obj.get("missing"));
        assertTrue(obj.has("name"));
        assertFalse(obj.has("missing"));
    }

    @Test
    void testAdd() {
        assertEquals(JqNumber.of(5), JqNumber.of(2).add(JqNumber.of(3)));
        assertEquals(JqString.of("ab"), JqString.of("a").add(JqString.of("b")));
        assertEquals("[1,2,3,4]", JqArray.of(JqNumber.of(1), JqNumber.of(2))
                .add(JqArray.of(JqNumber.of(3), JqNumber.of(4))).toJsonString());
    }

    @Test
    void testSubtract() {
        assertEquals(JqNumber.of(1), JqNumber.of(3).subtract(JqNumber.of(2)));
    }

    @Test
    void testMultiply() {
        assertEquals(JqNumber.of(6), JqNumber.of(2).multiply(JqNumber.of(3)));
    }

    @Test
    void testDivide() {
        assertEquals(JqNumber.of(2), JqNumber.of(6).divide(JqNumber.of(3)));
        // String split
        var result = JqString.of("a,b,c").divide(JqString.of(","));
        assertEquals("[\"a\",\"b\",\"c\"]", result.toJsonString());
    }

    @Test
    void testNegate() {
        assertEquals(JqNumber.of(-5), JqNumber.of(5).negate());
        assertEquals(JqNumber.of(5), JqNumber.of(-5).negate());
    }

    @Test
    void testCompare() {
        assertTrue(JqNumber.of(1).compareTo(JqNumber.of(2)) < 0);
        assertTrue(JqString.of("a").compareTo(JqString.of("b")) < 0);
        assertEquals(0, JqNull.NULL.compareTo(JqNull.NULL));
        // Type ordering: null < boolean < number < string < array < object
        assertTrue(JqNull.NULL.compareTo(JqBoolean.FALSE) < 0);
        assertTrue(JqBoolean.FALSE.compareTo(JqNumber.of(0)) < 0);
        assertTrue(JqNumber.of(0).compareTo(JqString.of("")) < 0);
    }

    @Test
    void testNullAdd() {
        // null + x = x (jq semantics)
        assertEquals(JqNumber.of(5), JqNull.NULL.add(JqNumber.of(5)));
        assertEquals(JqString.of("hello"), JqNull.NULL.add(JqString.of("hello")));
    }
}

package io.hyperfoil.tools.jjq.value;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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

    // ========================================================================
    //  JqObject.Builder tests
    // ========================================================================

    @Test
    void testBuilderEmpty() {
        var obj = JqObject.builder().build();
        assertSame(JqObject.EMPTY, obj);
        assertEquals("{}", obj.toJsonString());
    }

    @Test
    void testBuilderSingleField() {
        var obj = JqObject.builder().put("name", JqString.of("Alice")).build();
        assertEquals("{\"name\":\"Alice\"}", obj.toJsonString());
        assertEquals(JqString.of("Alice"), obj.get("name"));
    }

    @Test
    void testBuilderMultipleFields() {
        var obj = JqObject.builder()
                .put("name", "Alice")
                .put("age", 30L)
                .put("score", 9.5)
                .put("active", true)
                .putNull("extra")
                .build();
        assertEquals(5, obj.length());
        assertEquals(JqString.of("Alice"), obj.get("name"));
        assertEquals(JqNumber.of(30), obj.get("age"));
        assertEquals(JqNumber.of(9.5), obj.get("score"));
        assertEquals(JqBoolean.TRUE, obj.get("active"));
        assertEquals(JqNull.NULL, obj.get("extra"));
    }

    @Test
    void testBuilderDuplicateKeyLastWins() {
        var obj = JqObject.builder()
                .put("x", 1L)
                .put("y", 2L)
                .put("x", 99L)
                .build();
        assertEquals(2, obj.length());
        assertEquals(JqNumber.of(99), obj.get("x"));
        // x should retain its original position (index 0)
        assertEquals("{\"x\":99,\"y\":2}", obj.toJsonString());
    }

    @Test
    void testBuilderPreservesInsertionOrder() {
        var obj = JqObject.builder()
                .put("c", 3L)
                .put("a", 1L)
                .put("b", 2L)
                .build();
        assertEquals("{\"c\":3,\"a\":1,\"b\":2}", obj.toJsonString());
    }

    @Test
    void testBuilderWithExpectedSize() {
        var builder = JqObject.builder(2);
        for (int i = 0; i < 20; i++) {
            builder.put("key" + i, (long) i);
        }
        var obj = builder.build();
        assertEquals(20, obj.length());
        assertEquals(JqNumber.of(0), obj.get("key0"));
        assertEquals(JqNumber.of(19), obj.get("key19"));
    }

    @Test
    void testBuilderPutAllMap() {
        var map = new java.util.LinkedHashMap<String, JqValue>();
        map.put("a", JqNumber.of(1));
        map.put("b", JqNumber.of(2));
        var obj = JqObject.builder().put("x", 99L).putAll(map).build();
        assertEquals(3, obj.size());
        assertEquals(JqNumber.of(99), obj.get("x"));
        assertEquals(JqNumber.of(1), obj.get("a"));
        assertEquals(JqNumber.of(2), obj.get("b"));
    }

    @Test
    void testBuilderPutAllJqObject() {
        var source = JqObject.builder().put("a", 1L).put("b", 2L).build();
        var obj = JqObject.builder().put("x", 99L).putAll(source).build();
        assertEquals(3, obj.size());
        assertEquals(JqNumber.of(99), obj.get("x"));
        assertEquals(JqNumber.of(1), obj.get("a"));
        assertEquals(JqNumber.of(2), obj.get("b"));
    }

    @Test
    void testBuilderPutAllWithDuplicates() {
        var source = JqObject.builder().put("a", 10L).put("b", 20L).build();
        var obj = JqObject.builder().put("a", 1L).putAll(source).build();
        // putAll replaces existing key "a" with source's value
        assertEquals(2, obj.size());
        assertEquals(JqNumber.of(10), obj.get("a"));
        assertEquals(JqNumber.of(20), obj.get("b"));
    }

    // ========================================================================
    //  JqObject.with() tests
    // ========================================================================

    @Test
    void testWithNewField() {
        var obj = JqObject.of("a", JqNumber.of(1));
        var obj2 = obj.with("b", JqNumber.of(2));
        // Original unchanged (immutability)
        assertEquals(1, obj.length());
        assertFalse(obj.has("b"));
        // New object has both fields
        assertEquals(2, obj2.length());
        assertEquals(JqNumber.of(1), obj2.get("a"));
        assertEquals(JqNumber.of(2), obj2.get("b"));
    }

    @Test
    void testWithReplaceField() {
        var obj = JqObject.builder()
                .put("a", 1L)
                .put("b", 2L)
                .put("c", 3L)
                .build();
        var obj2 = obj.with("b", JqNumber.of(99));
        assertEquals(3, obj2.length());
        assertEquals(JqNumber.of(99), obj2.get("b"));
        // Key order preserved: b stays in position 1
        assertEquals("{\"a\":1,\"b\":99,\"c\":3}", obj2.toJsonString());
        // Original unchanged
        assertEquals(JqNumber.of(2), obj.get("b"));
    }

    @Test
    void testWithOnEmpty() {
        var obj = JqObject.EMPTY.with("x", JqNumber.of(42));
        assertEquals(1, obj.length());
        assertEquals(JqNumber.of(42), obj.get("x"));
    }

    // ========================================================================
    //  JqObject.without() tests
    // ========================================================================

    @Test
    void testWithoutExistingKey() {
        var obj = JqObject.builder()
                .put("a", 1L)
                .put("b", 2L)
                .put("c", 3L)
                .build();
        var obj2 = obj.without("b");
        assertEquals(2, obj2.length());
        assertEquals("{\"a\":1,\"c\":3}", obj2.toJsonString());
        // Original unchanged
        assertEquals(3, obj.length());
    }

    @Test
    void testWithoutMissingKey() {
        var obj = JqObject.of("a", JqNumber.of(1));
        var obj2 = obj.without("missing");
        assertSame(obj, obj2); // no-op returns this
    }

    @Test
    void testWithoutLastField() {
        var obj = JqObject.of("a", JqNumber.of(1));
        var obj2 = obj.without("a");
        assertSame(JqObject.EMPTY, obj2);
    }

    @Test
    void testWithoutOnEmpty() {
        var obj = JqObject.EMPTY.without("anything");
        assertSame(JqObject.EMPTY, obj);
    }

    // ========================================================================
    //  JqObject.merge() tests
    // ========================================================================

    @Test
    void testMergeDisjointKeys() {
        var obj1 = JqObject.builder().put("a", 1L).put("b", 2L).build();
        var obj2 = JqObject.builder().put("c", 3L).put("d", 4L).build();
        var merged = obj1.merge(obj2);
        assertEquals(4, merged.length());
        assertEquals("{\"a\":1,\"b\":2,\"c\":3,\"d\":4}", merged.toJsonString());
    }

    @Test
    void testMergeOverlappingKeysPreservesPosition() {
        // jq semantics: {"a":1,"b":2} + {"b":3,"c":4} = {"a":1,"b":3,"c":4}
        var obj1 = JqObject.builder().put("a", 1L).put("b", 2L).build();
        var obj2 = JqObject.builder().put("b", 3L).put("c", 4L).build();
        var merged = obj1.merge(obj2);
        assertEquals(3, merged.length());
        assertEquals(JqNumber.of(3), merged.get("b"));
        // b retains its position from obj1 (index 1)
        assertEquals("{\"a\":1,\"b\":3,\"c\":4}", merged.toJsonString());
    }

    @Test
    void testMergeWithEmpty() {
        var obj = JqObject.of("a", JqNumber.of(1));
        assertSame(obj, obj.merge(JqObject.EMPTY));
        assertSame(obj, JqObject.EMPTY.merge(obj));
    }

    @Test
    void testMergeFullOverlap() {
        var obj1 = JqObject.builder().put("a", 1L).put("b", 2L).build();
        var obj2 = JqObject.builder().put("a", 10L).put("b", 20L).build();
        var merged = obj1.merge(obj2);
        assertEquals(2, merged.length());
        assertEquals("{\"a\":10,\"b\":20}", merged.toJsonString());
    }

    // ========================================================================
    //  JqObject convenience factories tests
    // ========================================================================

    @Test
    void testOfStringValue() {
        var obj = JqObject.of("key", "value");
        assertEquals(JqString.of("value"), obj.get("key"));
    }

    @Test
    void testOfLongValue() {
        var obj = JqObject.of("key", 42L);
        assertEquals(JqNumber.of(42), obj.get("key"));
    }

    @Test
    void testOfDoubleValue() {
        var obj = JqObject.of("key", 3.14);
        assertEquals(JqNumber.of(3.14), obj.get("key"));
    }

    // ========================================================================
    //  JqArray.with() and append() tests
    // ========================================================================

    @Test
    void testArrayWithReplace() {
        var arr = JqArray.of(JqNumber.of(1), JqNumber.of(2), JqNumber.of(3));
        var arr2 = arr.with(1, JqNumber.of(99));
        assertEquals("[1,99,3]", arr2.toJsonString());
        // Original unchanged
        assertEquals("[1,2,3]", arr.toJsonString());
    }

    @Test
    void testArrayWithNegativeIndex() {
        var arr = JqArray.of(JqNumber.of(1), JqNumber.of(2), JqNumber.of(3));
        var arr2 = arr.with(-1, JqNumber.of(99));
        assertEquals("[1,2,99]", arr2.toJsonString());
    }

    @Test
    void testArrayWithOutOfBounds() {
        var arr = JqArray.of(JqNumber.of(1));
        assertThrows(IndexOutOfBoundsException.class, () -> arr.with(5, JqNumber.of(99)));
        assertThrows(IndexOutOfBoundsException.class, () -> arr.with(-5, JqNumber.of(99)));
    }

    @Test
    void testArrayAppend() {
        var arr = JqArray.of(JqNumber.of(1), JqNumber.of(2));
        var arr2 = arr.append(JqNumber.of(3));
        assertEquals("[1,2,3]", arr2.toJsonString());
        // Original unchanged
        assertEquals("[1,2]", arr.toJsonString());
    }

    @Test
    void testArrayAppendToEmpty() {
        var arr = JqArray.EMPTY.append(JqNumber.of(42));
        assertEquals("[42]", arr.toJsonString());
    }

    // ========================================================================
    //  JqArray.ArrayBuilder tests
    // ========================================================================

    @Test
    void testArrayBuilderEmpty() {
        var arr = JqArray.arrayBuilder().build();
        assertSame(JqArray.EMPTY, arr);
    }

    @Test
    void testArrayBuilderPrimitives() {
        var arr = JqArray.arrayBuilder()
                .add("hello")
                .add(42L)
                .add(3.14)
                .add(true)
                .addNull()
                .build();
        assertEquals(5, arr.length());
        assertEquals("[\"hello\",42,3.14,true,null]", arr.toJsonString());
    }

    @Test
    void testArrayBuilderGrowsPastInitialCapacity() {
        var builder = JqArray.arrayBuilder(2);
        for (int i = 0; i < 50; i++) {
            builder.add((long) i);
        }
        var arr = builder.build();
        assertEquals(50, arr.length());
        assertEquals(JqNumber.of(0), arr.get(0));
        assertEquals(JqNumber.of(49), arr.get(49));
    }

    // ========================================================================
    //  JqValue convenience methods tests
    // ========================================================================

    @Test
    void testGetFieldOnObject() {
        var obj = JqObject.of("name", JqString.of("Alice"));
        assertEquals(JqString.of("Alice"), obj.getField("name"));
        assertEquals(JqNull.NULL, obj.getField("missing"));
    }

    @Test
    void testGetFieldOnNonObject() {
        // getField returns JqNull.NULL for non-objects (null-safe navigation)
        assertEquals(JqNull.NULL, JqNumber.of(42).getField("x"));
        assertEquals(JqNull.NULL, JqArray.EMPTY.getField("x"));
        assertEquals(JqNull.NULL, JqString.of("hi").getField("x"));
    }

    @Test
    void testWithFieldOnObject() {
        var obj = JqObject.of("a", JqNumber.of(1));
        JqValue obj2 = obj.withField("b", JqNumber.of(2));
        assertTrue(obj2.isObject());
        assertEquals(JqNumber.of(2), ((JqObject) obj2).get("b"));
    }

    @Test
    void testWithFieldOnNonObject() {
        assertThrows(JqTypeError.class, () -> JqNumber.of(42).withField("x", JqNull.NULL));
    }

    @Test
    void testGetElementOnArray() {
        var arr = JqArray.of(JqNumber.of(10), JqNumber.of(20));
        assertEquals(JqNumber.of(10), arr.getElement(0));
        assertEquals(JqNumber.of(20), arr.getElement(-1));
    }

    @Test
    void testGetElementOnNonArray() {
        // getElement returns JqNull.NULL for non-arrays (null-safe navigation)
        assertEquals(JqNull.NULL, JqNumber.of(42).getElement(0));
        assertEquals(JqNull.NULL, JqObject.EMPTY.getElement(0));
    }

    @Test
    void testWithElementOnArray() {
        var arr = JqArray.of(JqNumber.of(1), JqNumber.of(2));
        JqValue arr2 = arr.withElement(0, JqNumber.of(99));
        assertTrue(arr2.isArray());
        assertEquals(JqNumber.of(99), ((JqArray) arr2).get(0));
    }

    @Test
    void testWithElementOnNonArray() {
        assertThrows(JqTypeError.class, () -> JqObject.EMPTY.withElement(0, JqNull.NULL));
    }

    // ========================================================================
    //  Immutability verification
    // ========================================================================

    @Test
    void testObjectImmutableAfterWith() {
        var original = JqObject.builder().put("a", 1L).put("b", 2L).build();
        var modified = original.with("c", JqNumber.of(3));
        // Verify original is not affected
        assertEquals(2, original.length());
        assertFalse(original.has("c"));
        assertEquals(3, modified.length());
    }

    @Test
    void testObjectImmutableAfterWithout() {
        var original = JqObject.builder().put("a", 1L).put("b", 2L).build();
        var modified = original.without("a");
        assertEquals(2, original.length());
        assertTrue(original.has("a"));
        assertEquals(1, modified.length());
    }

    @Test
    void testObjectImmutableAfterMerge() {
        var obj1 = JqObject.of("a", JqNumber.of(1));
        var obj2 = JqObject.of("b", JqNumber.of(2));
        var merged = obj1.merge(obj2);
        assertEquals(1, obj1.length());
        assertEquals(1, obj2.length());
        assertEquals(2, merged.length());
    }

    @Test
    void testArrayImmutableAfterWith() {
        var original = JqArray.of(JqNumber.of(1), JqNumber.of(2));
        var modified = original.with(0, JqNumber.of(99));
        assertEquals(JqNumber.of(1), original.get(0));
        assertEquals(JqNumber.of(99), modified.get(0));
    }

    @Test
    void testArrayImmutableAfterAppend() {
        var original = JqArray.of(JqNumber.of(1));
        var modified = original.append(JqNumber.of(2));
        assertEquals(1, original.length());
        assertEquals(2, modified.length());
    }

    // ========================================================================
    //  Error handling and edge cases
    // ========================================================================

    @Test
    void testBuilderNullKeyThrows() {
        var builder = JqObject.builder();
        assertThrows(NullPointerException.class, () -> builder.put(null, JqNumber.of(1)));
    }

    @Test
    void testBuilderNullValueBecomesJqNull() {
        var obj = JqObject.builder().put("key", (JqValue) null).build();
        assertEquals(JqNull.NULL, obj.get("key"));
        assertEquals(1, obj.size());
    }

    @Test
    void testWithNullKeyThrows() {
        var obj = JqObject.of("a", JqNumber.of(1));
        assertThrows(NullPointerException.class, () -> obj.with(null, JqNumber.of(2)));
    }

    @Test
    void testWithNullValueThrows() {
        var obj = JqObject.of("a", JqNumber.of(1));
        assertThrows(NullPointerException.class, () -> obj.with("b", null));
    }

    @Test
    void testWithoutNullKeyThrows() {
        var obj = JqObject.of("a", JqNumber.of(1));
        assertThrows(NullPointerException.class, () -> obj.without(null));
    }

    @Test
    void testMergeWithSelf() {
        var obj = JqObject.builder().put("a", 1L).put("b", 2L).build();
        var merged = obj.merge(obj);
        assertEquals(obj, merged);
        assertEquals("{\"a\":1,\"b\":2}", merged.toJsonString());
    }

    @Test
    void testArrayWithNullValueThrows() {
        var arr = JqArray.of(JqNumber.of(1), JqNumber.of(2));
        assertThrows(NullPointerException.class, () -> arr.with(0, null));
    }

    @Test
    void testArrayAppendNullValueThrows() {
        var arr = JqArray.of(JqNumber.of(1));
        assertThrows(NullPointerException.class, () -> arr.append(null));
    }

    @Test
    void testArrayBuilderNullValueThrows() {
        var builder = JqArray.arrayBuilder();
        assertThrows(NullPointerException.class, () -> builder.add((JqValue) null));
    }

    @Test
    void testArrayWithOnEmptyThrows() {
        assertThrows(IndexOutOfBoundsException.class, () -> JqArray.EMPTY.with(0, JqNumber.of(1)));
    }

    @Test
    void testArrayWithNegativeOnSingleElement() {
        var arr = JqArray.of(JqNumber.of(42));
        // -1 on single element = index 0
        var arr2 = arr.with(-1, JqNumber.of(99));
        assertEquals("[99]", arr2.toJsonString());
    }

    @Test
    void testArrayWithNegativeTooLarge() {
        var arr = JqArray.of(JqNumber.of(1), JqNumber.of(2));
        // -3 on size-2 array = index -1 = out of bounds
        assertThrows(IndexOutOfBoundsException.class, () -> arr.with(-3, JqNumber.of(99)));
    }

    @Test
    void testGetFieldOnNull() {
        assertEquals(JqNull.NULL, JqNull.NULL.getField("x"));
    }

    @Test
    void testGetFieldOnBoolean() {
        assertEquals(JqNull.NULL, JqBoolean.TRUE.getField("x"));
    }

    @Test
    void testAsText() {
        assertEquals("hello", JqString.of("hello").asText());
        assertNull(JqNull.NULL.asText());
        assertEquals("42", JqNumber.of(42).asText());
        assertEquals("true", JqBoolean.TRUE.asText());
        assertEquals("[1,2]", JqArray.of(JqNumber.of(1), JqNumber.of(2)).asText());
    }

    @Test
    void testIntValue() {
        assertEquals(42, JqNumber.of(42).intValue());
        assertEquals(3, JqNumber.of(3.7).intValue());
        assertThrows(JqTypeError.class, () -> JqString.of("x").intValue());
    }

    @Test
    void testIsEmpty() {
        assertTrue(JqNull.NULL.isEmpty());
        assertTrue(JqArray.EMPTY.isEmpty());
        assertTrue(JqObject.EMPTY.isEmpty());
        assertTrue(JqString.of("").isEmpty());
        assertFalse(JqArray.of(JqNumber.of(1)).isEmpty());
        assertFalse(JqObject.of("a", JqNumber.of(1)).isEmpty());
        assertFalse(JqString.of("x").isEmpty());
        assertFalse(JqNumber.of(0).isEmpty());
        assertFalse(JqBoolean.FALSE.isEmpty());
    }

    @Test
    void testArraySizeFirstLast() {
        var arr = JqArray.of(JqNumber.of(10), JqNumber.of(20), JqNumber.of(30));
        assertEquals(3, arr.size());
        assertEquals(JqNumber.of(10), arr.first());
        assertEquals(JqNumber.of(30), arr.last());
        // Empty
        assertEquals(0, JqArray.EMPTY.size());
        assertEquals(JqNull.NULL, JqArray.EMPTY.first());
        assertEquals(JqNull.NULL, JqArray.EMPTY.last());
    }

    @Test
    void testIsContainerAndScalar() {
        assertTrue(JqArray.EMPTY.isContainer());
        assertTrue(JqObject.EMPTY.isContainer());
        assertFalse(JqNull.NULL.isContainer());
        assertFalse(JqNumber.of(1).isContainer());
        assertFalse(JqString.of("x").isContainer());
        assertFalse(JqBoolean.TRUE.isContainer());
        // isScalar is the inverse
        assertTrue(JqNull.NULL.isScalar());
        assertTrue(JqNumber.of(1).isScalar());
        assertFalse(JqArray.EMPTY.isScalar());
        assertFalse(JqObject.EMPTY.isScalar());
    }

    @Test
    void testArrayIterable() {
        var arr = JqArray.of(JqNumber.of(1), JqNumber.of(2), JqNumber.of(3));
        int sum = 0;
        for (JqValue v : arr) {
            sum += ((JqNumber) v).intValue();
        }
        assertEquals(6, sum);
    }

    @Test
    void testArrayStream() {
        var arr = JqArray.of(JqNumber.of(1), JqNumber.of(2), JqNumber.of(3));
        long count = arr.stream().filter(v -> v instanceof JqNumber n && n.longValue() > 1).count();
        assertEquals(2, count);
    }

    @Test
    void testObjectKeysValuesEntries() {
        var obj = JqObject.builder().put("a", 1L).put("b", 2L).build();
        assertEquals(java.util.Set.of("a", "b"), obj.keys());
        assertEquals(2, obj.values().size());
        assertEquals(2, obj.entries().size());
    }

    @Test
    void testObjectSize() {
        assertEquals(0, JqObject.EMPTY.size());
        assertEquals(2, JqObject.builder().put("a", 1L).put("b", 2L).build().size());
    }

    @Test
    void testTryDouble() {
        // From JqNumber
        assertEquals(42.0, JqNumber.of(42).tryDouble());
        assertEquals(3.14, JqNumber.of(3.14).tryDouble());
        // From numeric JqString
        assertEquals(42.0, JqString.of("42").tryDouble());
        assertEquals(3.14, JqString.of("3.14").tryDouble());
        assertEquals(-1.5, JqString.of("-1.5").tryDouble());
        // From non-numeric JqString
        assertNull(JqString.of("hello").tryDouble());
        assertNull(JqString.of("").tryDouble());
        // From other types
        assertNull(JqNull.NULL.tryDouble());
        assertNull(JqBoolean.TRUE.tryDouble());
        assertNull(JqArray.EMPTY.tryDouble());
        assertNull(JqObject.EMPTY.tryDouble());
    }

    @Test
    void testTryLong() {
        // From JqNumber (integral)
        assertEquals(Long.valueOf(42), JqNumber.of(42).tryLong());
        assertEquals(Long.valueOf(-1), JqNumber.of(-1).tryLong());
        // From JqNumber (non-integral — truncates)
        assertEquals(Long.valueOf(3), JqNumber.of(3.7).tryLong());
        // From numeric JqString
        assertEquals(Long.valueOf(42), JqString.of("42").tryLong());
        assertEquals(Long.valueOf(-100), JqString.of("-100").tryLong());
        // From non-integer string
        assertNull(JqString.of("3.14").tryLong());
        assertNull(JqString.of("hello").tryLong());
        assertNull(JqString.of("").tryLong());
        // From other types
        assertNull(JqNull.NULL.tryLong());
        assertNull(JqBoolean.TRUE.tryLong());
        assertNull(JqArray.EMPTY.tryLong());
        assertNull(JqObject.EMPTY.tryLong());
    }

    @Test
    void testTryInt() {
        // From JqNumber (integral)
        assertEquals(Integer.valueOf(42), JqNumber.of(42).tryInt());
        assertEquals(Integer.valueOf(-1), JqNumber.of(-1).tryInt());
        // From JqNumber (non-integral — truncates)
        assertEquals(Integer.valueOf(3), JqNumber.of(3.7).tryInt());
        // From numeric JqString
        assertEquals(Integer.valueOf(42), JqString.of("42").tryInt());
        assertEquals(Integer.valueOf(-100), JqString.of("-100").tryInt());
        // From non-integer string
        assertNull(JqString.of("3.14").tryInt());
        assertNull(JqString.of("hello").tryInt());
        assertNull(JqString.of("").tryInt());
        // From other types
        assertNull(JqNull.NULL.tryInt());
        assertNull(JqBoolean.TRUE.tryInt());
    }

    @Test
    void testForEachOnObject() {
        var obj = JqObject.builder().put("a", 1L).put("b", 2L).put("c", 3L).build();
        var keys = new java.util.ArrayList<String>();
        var vals = new java.util.ArrayList<Long>();
        obj.forEach((k, v) -> { keys.add(k); vals.add(v.longValue()); });
        assertEquals(List.of("a", "b", "c"), keys);
        assertEquals(List.of(1L, 2L, 3L), vals);
    }

    @Test
    void testForEachOnEmptyObject() {
        var keys = new java.util.ArrayList<String>();
        JqObject.EMPTY.forEach((k, v) -> keys.add(k));
        assertTrue(keys.isEmpty());
    }

    @Test
    void testAtJsonPointer() {
        var json = JqValues.parse("{\"a\":{\"b\":[{\"c\":42},{\"c\":99}]}}");
        // Deep navigation
        assertEquals(JqNumber.of(42), json.at("/a/b/0/c"));
        assertEquals(JqNumber.of(99), json.at("/a/b/1/c"));
        // Missing path
        assertEquals(JqNull.NULL, json.at("/a/x/y"));
        assertEquals(JqNull.NULL, json.at("/a/b/99"));
        // Empty pointer returns self (RFC 6901: "" references whole document)
        assertSame(json, json.at(""));
        // "/" references the empty-string key (RFC 6901 section 5)
        assertEquals(JqNull.NULL, json.at("/"));  // no "" key in this object
    }

    @Test
    void testAtWithEscapedTokens() {
        // RFC 6901: ~0 = ~, ~1 = /
        var json = JqValues.parse("{\"a/b\":{\"c~d\":42}}");
        assertEquals(JqNumber.of(42), json.at("/a~1b/c~0d"));
    }

    @Test
    void testAtOnArray() {
        var json = JqValues.parse("[[1,2],[3,4]]");
        assertEquals(JqNumber.of(3), json.at("/1/0"));
    }

    @Test
    void testRequiredString() {
        var obj = JqObject.of("name", JqString.of("Alice"));
        assertEquals(JqString.of("Alice"), obj.required("name"));
        assertThrows(JqTypeError.class, () -> obj.required("missing"));
    }

    @Test
    void testRequiredInt() {
        var arr = JqArray.of(JqNumber.of(10), JqNumber.of(20));
        assertEquals(JqNumber.of(10), arr.required(0));
        assertEquals(JqNumber.of(20), arr.required(1));
        assertThrows(JqTypeError.class, () -> arr.required(5));
        assertThrows(JqTypeError.class, () -> JqObject.EMPTY.required(0));
    }

    @Test
    void testIsIntegralNumber() {
        assertTrue(JqNumber.of(42).isIntegralNumber());
        assertFalse(JqNumber.of(3.14).isIntegralNumber());
        assertFalse(JqString.of("42").isIntegralNumber());
        assertFalse(JqNull.NULL.isIntegralNumber());
    }

    @Test
    void testIsFloatingPointNumber() {
        assertFalse(JqNumber.of(42).isFloatingPointNumber());
        assertTrue(JqNumber.of(3.14).isFloatingPointNumber());
        assertFalse(JqString.of("3.14").isFloatingPointNumber());
        assertFalse(JqNull.NULL.isFloatingPointNumber());
    }

    // ========================================================================
    //  Serialization round-trip tests (issue #34)
    // ========================================================================

    @Test
    void testSerializationNull() throws Exception {
        assertSame(JqNull.NULL, roundTripSerialize(JqNull.NULL));
    }

    @Test
    void testSerializationBooleans() throws Exception {
        assertSame(JqBoolean.TRUE, roundTripSerialize(JqBoolean.TRUE));
        assertSame(JqBoolean.FALSE, roundTripSerialize(JqBoolean.FALSE));
    }

    @Test
    void testSerializationCachedNumber() throws Exception {
        JqNumber original = JqNumber.of(42);
        JqNumber deserialized = (JqNumber) roundTripSerialize(original);
        assertSame(original, deserialized, "Cached numbers should preserve identity");
    }

    @Test
    void testSerializationLargeNumber() throws Exception {
        JqNumber original = JqNumber.of(999999L);
        JqNumber deserialized = (JqNumber) roundTripSerialize(original);
        assertEquals(original, deserialized);
        assertEquals(999999L, deserialized.longValue());
    }

    @Test
    void testSerializationDouble() throws Exception {
        JqNumber original = JqNumber.of(3.14);
        JqNumber deserialized = (JqNumber) roundTripSerialize(original);
        assertEquals(3.14, deserialized.doubleValue());
    }

    @Test
    void testSerializationString() throws Exception {
        JqString original = JqString.of("hello world");
        JqString deserialized = (JqString) roundTripSerialize(original);
        assertEquals("hello world", deserialized.stringValue());
    }

    @Test
    void testSerializationDeferredString() throws Exception {
        // Parse creates deferred strings — verify they survive serialization
        JqValue parsed = JqValues.parse("{\"name\":\"Alice\"}");
        JqValue deserialized = roundTripSerialize(parsed);
        assertEquals("Alice", deserialized.getField("name").stringValue());
    }

    @Test
    void testSerializationArray() throws Exception {
        JqArray original = JqArray.of(JqNumber.of(1), JqString.of("two"), JqBoolean.TRUE);
        JqArray deserialized = (JqArray) roundTripSerialize(original);
        assertEquals(3, deserialized.size());
        assertEquals(JqNumber.of(1), deserialized.get(0));
        assertEquals(JqString.of("two"), deserialized.get(1));
        assertSame(JqBoolean.TRUE, deserialized.get(2));
    }

    @Test
    void testSerializationObject() throws Exception {
        JqObject original = JqObject.builder().put("name", "Alice").put("age", 30L).build();
        JqObject deserialized = (JqObject) roundTripSerialize(original);
        assertEquals(2, deserialized.size());
        assertEquals(JqString.of("Alice"), deserialized.get("name"));
        assertEquals(JqNumber.of(30), deserialized.get("age"));
        // Verify insertion order preserved
        assertEquals("{\"name\":\"Alice\",\"age\":30}", deserialized.toJsonString());
    }

    @Test
    void testSerializationLargeObjectHashIndex() throws Exception {
        // Object with >32 keys — verifies hash index is rebuilt after deserialization
        var builder = JqObject.builder(40);
        for (int i = 0; i < 40; i++) builder.put("field_" + i, (long) i);
        JqObject original = builder.build();
        JqObject deserialized = (JqObject) roundTripSerialize(original);
        assertEquals(40, deserialized.size());
        for (int i = 0; i < 40; i++) {
            assertEquals(JqNumber.of(i), deserialized.get("field_" + i));
        }
    }

    @Test
    void testSerializationMapBackedObject() throws Exception {
        // Map-backed object (simulates lazy adapter) — verifies conversion to array-backed
        var map = new java.util.LinkedHashMap<String, JqValue>();
        map.put("x", JqNumber.of(1));
        map.put("y", JqNumber.of(2));
        JqObject original = JqObject.ofTrusted((java.util.Map<String, JqValue>) map);
        JqObject deserialized = (JqObject) roundTripSerialize(original);
        assertEquals(2, deserialized.size());
        assertEquals(JqNumber.of(1), deserialized.get("x"));
        assertEquals(JqNumber.of(2), deserialized.get("y"));
    }

    @Test
    void testSerializationNestedDocument() throws Exception {
        JqValue original = JqValues.parse(
                "{\"users\":[{\"name\":\"Alice\",\"scores\":[1,2,3]},{\"name\":\"Bob\",\"scores\":[4,5]}],\"active\":true}");
        JqValue deserialized = roundTripSerialize(original);
        assertEquals(original.toJsonString(), deserialized.toJsonString());
        assertEquals(original, deserialized);
    }

    @Test
    void testSerializationEmptyContainers() throws Exception {
        assertSame(JqObject.EMPTY, ((JqObject) roundTripSerialize(JqObject.EMPTY)));
        // JqArray.EMPTY may not preserve identity (no readResolve for EMPTY), but should be equal
        JqArray emptyArr = (JqArray) roundTripSerialize(JqArray.EMPTY);
        assertEquals(0, emptyArr.size());
    }

    private static JqValue roundTripSerialize(JqValue value) throws Exception {
        var baos = new java.io.ByteArrayOutputStream();
        try (var oos = new java.io.ObjectOutputStream(baos)) {
            oos.writeObject(value);
        }
        try (var ois = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(baos.toByteArray()))) {
            return (JqValue) ois.readObject();
        }
    }

    // ========================================================================
    //  JqValues.parse(InputStream) tests
    // ========================================================================

    @Test
    void testParseInputStream() throws Exception {
        String json = "{\"name\":\"Alice\",\"age\":30}";
        var stream = new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JqValue result = JqValues.parse(stream);
        assertEquals(JqString.of("Alice"), result.getField("name"));
        assertEquals(JqNumber.of(30), result.getField("age"));
    }

    @Test
    void testParseInputStreamEmpty() throws Exception {
        var stream = new java.io.ByteArrayInputStream("null".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(JqNull.NULL, JqValues.parse(stream));
    }

    // ========================================================================
    //  JqValue.asInt(int) tests
    // ========================================================================

    @Test
    void testAsInt() {
        assertEquals(42, JqNumber.of(42).asInt(0));
        assertEquals(3, JqNumber.of(3.7).asInt(0));
        assertEquals(0, JqString.of("hello").asInt(0));
        assertEquals(-1, JqNull.NULL.asInt(-1));
        assertEquals(99, JqBoolean.TRUE.asInt(99));
    }

    // ========================================================================
    //  JqValues.toPrettyJsonString() tests
    // ========================================================================

    @Test
    void testPrettyPrintScalar() {
        assertEquals("42", JqValues.toPrettyJsonString(JqNumber.of(42)));
        assertEquals("\"hello\"", JqValues.toPrettyJsonString(JqString.of("hello")));
        assertEquals("null", JqValues.toPrettyJsonString(JqNull.NULL));
    }

    @Test
    void testPrettyPrintObject() {
        var obj = JqObject.builder().put("name", "Alice").put("age", 30L).build();
        String pretty = JqValues.toPrettyJsonString(obj);
        assertEquals("""
                {
                  "name": "Alice",
                  "age": 30
                }""", pretty);
    }

    @Test
    void testPrettyPrintArray() {
        var arr = JqArray.of(JqNumber.of(1), JqNumber.of(2), JqNumber.of(3));
        String pretty = JqValues.toPrettyJsonString(arr);
        assertEquals("""
                [
                  1,
                  2,
                  3
                ]""", pretty);
    }

    @Test
    void testPrettyPrintNested() {
        var json = JqValues.parse("{\"users\":[{\"name\":\"Alice\"},{\"name\":\"Bob\"}]}");
        String pretty = JqValues.toPrettyJsonString(json);
        assertEquals("""
                {
                  "users": [
                    {
                      "name": "Alice"
                    },
                    {
                      "name": "Bob"
                    }
                  ]
                }""", pretty);
    }

    @Test
    void testPrettyPrintEmpty() {
        assertEquals("{}", JqValues.toPrettyJsonString(JqObject.EMPTY));
        assertEquals("[]", JqValues.toPrettyJsonString(JqArray.EMPTY));
    }

    // ========================================================================
    //  JqNumber.of(Number) tests
    // ========================================================================

    @Test
    void testNumberOfInteger() {
        assertEquals(JqNumber.of(42L), JqNumber.of((Number) Integer.valueOf(42)));
        assertEquals(JqNumber.of(-1L), JqNumber.of((Number) Integer.valueOf(-1)));
    }

    @Test
    void testNumberOfFloat() {
        // Float 3.14f has limited precision — verify it converts to a double-backed JqNumber
        JqNumber n = JqNumber.of((Number) Float.valueOf(3.14f));
        assertFalse(n.isIntegral());
        assertEquals(3.14f, (float) n.doubleValue(), 0.0001f);
    }

    @Test
    void testNumberOfShortAndByte() {
        assertEquals(JqNumber.of(7L), JqNumber.of((Number) Short.valueOf((short) 7)));
        assertEquals(JqNumber.of(3L), JqNumber.of((Number) Byte.valueOf((byte) 3)));
    }

    @Test
    void testNumberOfBigDecimal() {
        assertEquals(JqNumber.of(new java.math.BigDecimal("3.14159")), JqNumber.of((Number) new java.math.BigDecimal("3.14159")));
    }

    @Test
    void testNumberOfIntegralDouble() {
        // Integer-valued doubles should be promoted to long-backed
        JqNumber n = JqNumber.of((Number) Double.valueOf(42.0));
        assertTrue(n.isIntegral());
        assertEquals(42L, n.longValue());
    }

    // ========================================================================
    //  JqValue.toJavaObject() tests
    // ========================================================================

    @Test
    void testToJavaObjectNull() {
        assertNull(JqNull.NULL.toJavaObject());
    }

    @Test
    void testToJavaObjectBoolean() {
        assertEquals(Boolean.TRUE, JqBoolean.TRUE.toJavaObject());
        assertEquals(Boolean.FALSE, JqBoolean.FALSE.toJavaObject());
    }

    @Test
    void testToJavaObjectNumber() {
        // Integral → Long
        assertEquals(42L, JqNumber.of(42).toJavaObject());
        // Floating point → Double
        assertEquals(3.14, JqNumber.of(3.14).toJavaObject());
    }

    @Test
    void testToJavaObjectString() {
        assertEquals("hello", JqString.of("hello").toJavaObject());
    }

    @Test
    void testToJavaObjectArray() {
        var arr = JqArray.of(JqNumber.of(1), JqString.of("two"), JqBoolean.TRUE);
        Object result = arr.toJavaObject();
        assertInstanceOf(java.util.ArrayList.class, result);
        @SuppressWarnings("unchecked")
        var list = (java.util.List<Object>) result;
        assertEquals(3, list.size());
        assertEquals(1L, list.get(0));
        assertEquals("two", list.get(1));
        assertEquals(Boolean.TRUE, list.get(2));
    }

    @Test
    void testToJavaObjectObject() {
        var obj = JqObject.builder().put("name", "Alice").put("age", 30L).build();
        Object result = obj.toJavaObject();
        assertInstanceOf(java.util.LinkedHashMap.class, result);
        @SuppressWarnings("unchecked")
        var map = (java.util.Map<String, Object>) result;
        assertEquals(2, map.size());
        assertEquals("Alice", map.get("name"));
        assertEquals(30L, map.get("age"));
    }

    @Test
    void testToJavaObjectNested() {
        var json = JqValues.parse("{\"users\":[{\"name\":\"Alice\",\"age\":30},{\"name\":\"Bob\",\"age\":25}]}");
        Object result = json.toJavaObject();
        assertInstanceOf(java.util.LinkedHashMap.class, result);
        @SuppressWarnings("unchecked")
        var map = (java.util.Map<String, Object>) result;
        @SuppressWarnings("unchecked")
        var users = (java.util.List<Object>) map.get("users");
        assertEquals(2, users.size());
        @SuppressWarnings("unchecked")
        var alice = (java.util.Map<String, Object>) users.get(0);
        assertEquals("Alice", alice.get("name"));
        assertEquals(30L, alice.get("age"));
    }

    // ========================================================================
    //  JqValues.fromJavaObject() tests
    // ========================================================================

    @Test
    void testFromJavaObjectNull() {
        assertEquals(JqNull.NULL, JqValues.fromJavaObject(null));
    }

    @Test
    void testFromJavaObjectPassthrough() {
        JqValue original = JqString.of("passthrough");
        assertSame(original, JqValues.fromJavaObject(original));
    }

    @Test
    void testFromJavaObjectPrimitives() {
        assertEquals(JqString.of("hello"), JqValues.fromJavaObject("hello"));
        assertEquals(JqNumber.of(42), JqValues.fromJavaObject(42));
        assertEquals(JqNumber.of(42L), JqValues.fromJavaObject(42L));
        assertEquals(JqNumber.of(3.14), JqValues.fromJavaObject(3.14));
        assertEquals(JqBoolean.TRUE, JqValues.fromJavaObject(true));
        assertEquals(JqBoolean.FALSE, JqValues.fromJavaObject(false));
    }

    @Test
    void testFromJavaObjectMap() {
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("name", "Alice");
        map.put("age", 30);
        JqValue result = JqValues.fromJavaObject(map);
        assertInstanceOf(JqObject.class, result);
        assertEquals(JqString.of("Alice"), result.getField("name"));
        assertEquals(JqNumber.of(30), result.getField("age"));
    }

    @Test
    void testFromJavaObjectList() {
        var list = java.util.List.of(1, "two", true);
        JqValue result = JqValues.fromJavaObject(list);
        assertInstanceOf(JqArray.class, result);
        assertEquals(JqNumber.of(1), result.getElement(0));
        assertEquals(JqString.of("two"), result.getElement(1));
        assertEquals(JqBoolean.TRUE, result.getElement(2));
    }

    @Test
    void testFromJavaObjectNested() {
        var inner = new java.util.LinkedHashMap<String, Object>();
        inner.put("x", 1);
        inner.put("y", 2);
        var outer = new java.util.LinkedHashMap<String, Object>();
        outer.put("point", inner);
        outer.put("labels", java.util.List.of("a", "b"));
        JqValue result = JqValues.fromJavaObject(outer);
        assertEquals(JqNumber.of(1), result.getField("point").getField("x"));
        assertEquals(JqString.of("b"), result.getField("labels").getElement(1));
    }

    @Test
    void testFromJavaObjectFallback() {
        // Unknown type → toString()
        var sb = new StringBuilder("hello");
        assertEquals(JqString.of("hello"), JqValues.fromJavaObject(sb));
    }

    // ========================================================================
    //  Round-trip: toJavaObject() → fromJavaObject()
    // ========================================================================

    @Test
    void testRoundTrip() {
        var original = JqValues.parse("{\"name\":\"Alice\",\"scores\":[1,2,3],\"active\":true,\"extra\":null}");
        Object java = original.toJavaObject();
        JqValue roundTripped = JqValues.fromJavaObject(java);
        // Note: JqNull fields become null in Java Map, then back to JqNull.NULL
        assertEquals(original.toJsonString(), roundTripped.toJsonString());
    }

    @Test
    void testFluentNavigation() {
        var json = JqValues.parse("{\"a\":{\"b\":[{\"c\":42}]}}");
        assertEquals(JqNumber.of(42), json.getField("a").getField("b").getElement(0).getField("c"));
        // Non-existent path returns JqNull.NULL at each step
        assertEquals(JqNull.NULL, json.getField("x").getField("y").getElement(0));
    }

    @Test
    void testHasOnJqValue() {
        var obj = JqObject.of("name", JqString.of("Alice"));
        assertTrue(obj.has("name"));
        assertFalse(obj.has("missing"));
        // has(String) on non-objects returns false
        assertFalse(JqNumber.of(42).has("x"));
        assertFalse(JqNull.NULL.has("x"));
    }

    @Test
    void testHasIntOnJqValue() {
        var arr = JqArray.of(JqNumber.of(1), JqNumber.of(2), JqNumber.of(3));
        assertTrue(arr.has(0));
        assertTrue(arr.has(2));
        assertTrue(arr.has(-1)); // last element
        assertFalse(arr.has(5));
        assertFalse(arr.has(-5));
        // has(int) on non-arrays returns false
        assertFalse(JqObject.EMPTY.has(0));
        assertFalse(JqNull.NULL.has(0));
    }

    @Test
    void testWithFieldOnNull() {
        assertThrows(JqTypeError.class, () -> JqNull.NULL.withField("x", JqNumber.of(1)));
    }

    @Test
    void testWithFieldOnString() {
        assertThrows(JqTypeError.class, () -> JqString.of("hi").withField("x", JqNumber.of(1)));
    }

    @Test
    void testGetElementOnNull() {
        assertEquals(JqNull.NULL, JqNull.NULL.getElement(0));
    }

    @Test
    void testGetElementOnString() {
        assertEquals(JqNull.NULL, JqString.of("hi").getElement(0));
    }

    @Test
    void testWithElementOnNull() {
        assertThrows(JqTypeError.class, () -> JqNull.NULL.withElement(0, JqNumber.of(1)));
    }

    @Test
    void testWithElementOnString() {
        assertThrows(JqTypeError.class, () -> JqString.of("hi").withElement(0, JqNumber.of(1)));
    }

    @Test
    void testWithElementOnNumber() {
        assertThrows(JqTypeError.class, () -> JqNumber.of(42).withElement(0, JqNull.NULL));
    }

    @Test
    void testWithElementOnBoolean() {
        assertThrows(JqTypeError.class, () -> JqBoolean.FALSE.withElement(0, JqNull.NULL));
    }

    @Test
    void testObjectWithEmptyStringKey() {
        // Empty string is a valid JSON key
        var obj = JqObject.builder().put("", "value").build();
        assertEquals(JqString.of("value"), obj.get(""));
        var obj2 = obj.with("", JqString.of("updated"));
        assertEquals(JqString.of("updated"), obj2.get(""));
        assertEquals(1, obj2.length());
    }

    @Test
    void testMergePreservesAllKeyPositions() {
        // More complex merge with interleaved keys
        // {"a":1,"b":2,"c":3} + {"c":30,"a":10,"d":4} = {"a":10,"b":2,"c":30,"d":4}
        var obj1 = JqObject.builder().put("a", 1L).put("b", 2L).put("c", 3L).build();
        var obj2 = JqObject.builder().put("c", 30L).put("a", 10L).put("d", 4L).build();
        var merged = obj1.merge(obj2);
        assertEquals(4, merged.length());
        assertEquals("{\"a\":10,\"b\":2,\"c\":30,\"d\":4}", merged.toJsonString());
    }

    @Test
    void testWithFieldChaining() {
        // Chaining with() creates multiple intermediate copies -- verify correctness
        JqValue result = JqObject.EMPTY
                .withField("a", JqNumber.of(1))
                .withField("b", JqNumber.of(2))
                .withField("c", JqNumber.of(3));
        assertTrue(result.isObject());
        assertEquals(3, result.length());
        assertEquals("{\"a\":1,\"b\":2,\"c\":3}", result.toJsonString());
    }

    @Test
    void testWithoutFirstMiddleLast() {
        var obj = JqObject.builder().put("a", 1L).put("b", 2L).put("c", 3L).build();
        // Remove first
        assertEquals("{\"b\":2,\"c\":3}", obj.without("a").toJsonString());
        // Remove middle
        assertEquals("{\"a\":1,\"c\":3}", obj.without("b").toJsonString());
        // Remove last
        assertEquals("{\"a\":1,\"b\":2}", obj.without("c").toJsonString());
    }

    // ========================================================================
    //  Hash-indexed JqObject.get() and has() for large objects
    // ========================================================================

    @Test
    void testLargeObjectGet() {
        // Build object with more keys than HASH_THRESHOLD (16)
        var builder = JqObject.builder(32);
        for (int i = 0; i < 32; i++) {
            builder.put("field_" + i, (long) i);
        }
        var obj = builder.build();
        // Verify all keys accessible via hash-indexed path
        for (int i = 0; i < 32; i++) {
            assertEquals(JqNumber.of(i), obj.get("field_" + i));
        }
        // Missing key returns JqNull
        assertEquals(JqNull.NULL, obj.get("nonexistent"));
    }

    @Test
    void testLargeObjectHas() {
        var builder = JqObject.builder(32);
        for (int i = 0; i < 32; i++) {
            builder.put("key_" + i, (long) i);
        }
        var obj = builder.build();
        for (int i = 0; i < 32; i++) {
            assertTrue(obj.has("key_" + i), "Should have key_" + i);
        }
        assertFalse(obj.has("missing"));
    }

    @Test
    void testSmallObjectStaysLinearScan() {
        // Objects at or below threshold should still work correctly
        var builder = JqObject.builder(JqObject.HASH_THRESHOLD);
        for (int i = 0; i < JqObject.HASH_THRESHOLD; i++) {
            builder.put("k" + i, (long) i);
        }
        var obj = builder.build();
        for (int i = 0; i < JqObject.HASH_THRESHOLD; i++) {
            assertEquals(JqNumber.of(i), obj.get("k" + i));
            assertTrue(obj.has("k" + i));
        }
        assertEquals(JqNull.NULL, obj.get("missing"));
        assertFalse(obj.has("missing"));
    }

    @Test
    void testLargeObjectEquality() {
        // Two large objects with same fields should be equal
        var builder1 = JqObject.builder(20);
        var builder2 = JqObject.builder(20);
        for (int i = 0; i < 20; i++) {
            builder1.put("f" + i, (long) i);
            builder2.put("f" + i, (long) i);
        }
        assertEquals(builder1.build(), builder2.build());
    }

    @Test
    void testLargeObjectSerialization() {
        var builder = JqObject.builder(20);
        for (int i = 0; i < 20; i++) {
            builder.put("k" + i, (long) i);
        }
        var obj = builder.build();
        // Serialize, re-parse, verify equal
        String json = obj.toJsonString();
        var reparsed = JqValues.parse(json);
        assertEquals(obj, reparsed);
    }

    @Test
    void testExactlyAtThreshold() {
        // Object with exactly HASH_THRESHOLD keys -- should use linear scan
        var builder = JqObject.builder(JqObject.HASH_THRESHOLD);
        for (int i = 0; i < JqObject.HASH_THRESHOLD; i++) {
            builder.put("key_" + i, (long) i);
        }
        var obj = builder.build();
        assertEquals(JqObject.HASH_THRESHOLD, obj.length());
        assertEquals(JqNumber.of(0), obj.get("key_0"));
        assertEquals(JqNumber.of(JqObject.HASH_THRESHOLD - 1),
                obj.get("key_" + (JqObject.HASH_THRESHOLD - 1)));
    }

    @Test
    void testOneAboveThreshold() {
        // Object with HASH_THRESHOLD + 1 keys -- should use hash index
        int n = JqObject.HASH_THRESHOLD + 1;
        var builder = JqObject.builder(n);
        for (int i = 0; i < n; i++) {
            builder.put("key_" + i, (long) i);
        }
        var obj = builder.build();
        assertEquals(n, obj.length());
        // First and last key should be accessible
        assertEquals(JqNumber.of(0), obj.get("key_0"));
        assertEquals(JqNumber.of(n - 1), obj.get("key_" + (n - 1)));
        assertTrue(obj.has("key_0"));
        assertFalse(obj.has("nope"));
    }

    @Test
    void testLargeObjectWithPcpLikeKeys() {
        // Simulate PCP time series keys (the production hotspot)
        String[] pcpKeys = {
            "mem.util.used", "mem.util.free", "mem.util.bufmem",
            "mem.util.cached", "mem.util.active", "mem.util.inactive",
            "kernel.all.cpu.user", "kernel.all.cpu.sys", "kernel.all.cpu.idle",
            "kernel.all.cpu.nice", "kernel.all.cpu.wait.total",
            "disk.all.read", "disk.all.write", "disk.all.total",
            "network.interface.in.bytes", "network.interface.out.bytes",
            "kernel.all.load", // 17 keys -- above threshold
        };
        var builder = JqObject.builder(pcpKeys.length);
        for (int i = 0; i < pcpKeys.length; i++) {
            builder.put(pcpKeys[i], (long) i);
        }
        var obj = builder.build();
        // Verify specific metric lookup (the benchmark hotspot)
        assertEquals(JqNumber.of(0), obj.get("mem.util.used"));
        assertEquals(JqNumber.of(16), obj.get("kernel.all.load"));
        assertEquals(JqNull.NULL, obj.get("nonexistent.metric"));
        assertTrue(obj.has("mem.util.used"));
        assertFalse(obj.has("nonexistent.metric"));
    }

    // ========================================================================
    //  Map-backed JqObject with/without/merge tests
    // ========================================================================

    @Test
    void testWithOnMapBackedObject() {
        // Create a map-backed object (simulates lazy adapter)
        var map = new LinkedHashMap<String, JqValue>();
        map.put("a", JqNumber.of(1));
        map.put("b", JqNumber.of(2));
        var obj = JqObject.ofTrusted((java.util.Map<String, JqValue>) map);
        var obj2 = obj.with("c", JqNumber.of(3));
        assertEquals(3, obj2.length());
        assertEquals(JqNumber.of(3), obj2.get("c"));
    }

    @Test
    void testWithoutOnMapBackedObject() {
        var map = new LinkedHashMap<String, JqValue>();
        map.put("a", JqNumber.of(1));
        map.put("b", JqNumber.of(2));
        var obj = JqObject.ofTrusted((java.util.Map<String, JqValue>) map);
        var obj2 = obj.without("a");
        assertEquals(1, obj2.length());
        assertEquals(JqNull.NULL, obj2.get("a"));
        assertEquals(JqNumber.of(2), obj2.get("b"));
    }

    @Test
    void testMergeMapBackedObjects() {
        var map1 = new LinkedHashMap<String, JqValue>();
        map1.put("a", JqNumber.of(1));
        var obj1 = JqObject.ofTrusted((java.util.Map<String, JqValue>) map1);

        var obj2 = JqObject.of("b", JqNumber.of(2));
        var merged = obj1.merge(obj2);
        assertEquals(2, merged.length());
        assertEquals(JqNumber.of(1), merged.get("a"));
        assertEquals(JqNumber.of(2), merged.get("b"));
    }

    // ========================================================================
    //  byte[] parser tests -- verify identical output to String parser
    // ========================================================================

    @Test
    void testByteParserScalars() {
        assertByteParseSame("null");
        assertByteParseSame("true");
        assertByteParseSame("false");
        assertByteParseSame("42");
        assertByteParseSame("-17");
        assertByteParseSame("3.14");
        assertByteParseSame("1e10");
        assertByteParseSame("3.14e-2");
        assertByteParseSame("-0.5");
        assertByteParseSame("0");
        assertByteParseSame("\"\"");
        assertByteParseSame("\"hello\"");
        assertByteParseSame("\"hello world\"");
    }

    @Test
    void testByteParserEscapedStrings() {
        assertByteParseSame("\"hello\\nworld\"");
        assertByteParseSame("\"tab\\there\"");
        assertByteParseSame("\"quote\\\"inside\"");
        assertByteParseSame("\"back\\\\slash\"");
        assertByteParseSame("\"unicode\\u0041\"");  // \u0041 = A
    }

    @Test
    void testByteParserArrays() {
        assertByteParseSame("[]");
        assertByteParseSame("[1,2,3]");
        assertByteParseSame("[\"a\",\"b\"]");
        assertByteParseSame("[[1],[2,3]]");
        assertByteParseSame("[true,false,null,42,\"str\"]");
    }

    @Test
    void testByteParserObjects() {
        assertByteParseSame("{}");
        assertByteParseSame("{\"name\":\"Alice\",\"age\":30}");
        assertByteParseSame("{\"a\":{\"b\":{\"c\":42}}}");
    }

    @Test
    void testByteParserComplex() {
        assertByteParseSame("[{\"x\":1},{\"x\":2}]");
        assertByteParseSame("{\"arr\":[1,2,3],\"obj\":{\"a\":\"b\"}}");
        assertByteParseSame("{\"results\":[{\"load\":{\"avThroughput\":1000.5}}]}");
    }

    @Test
    void testByteParserUtf8() {
        // Multi-byte UTF-8 characters
        assertByteParseSame("{\"city\":\"T\\u00f6ky\\u00f6\"}");
        // Direct UTF-8 in string
        String utf8Json = "{\"emoji\":\"hello\"}"; // simple ASCII for now
        assertByteParseSame(utf8Json);
    }

    @Test
    void testByteParserWhitespace() {
        String json = "  {  \"a\"  :  1  ,  \"b\"  :  [  2  ,  3  ]  }  ";
        assertByteParseSame(json);
    }

    @Test
    void testByteParserBOM() {
        // UTF-8 BOM: EF BB BF
        byte[] withBom = new byte[]{(byte)0xEF, (byte)0xBB, (byte)0xBF, '4', '2'};
        assertEquals(JqNumber.of(42), JqValues.parse(withBom));
    }

    @Test
    void testByteParserParseAll() {
        String jsonl = "{\"a\":1}\n{\"b\":2}\n{\"c\":3}";
        byte[] bytes = jsonl.getBytes(StandardCharsets.UTF_8);
        var fromString = JqValues.parseAll(jsonl);
        var fromBytes = JqValues.parseAll(bytes);
        assertEquals(fromString.size(), fromBytes.size());
        for (int i = 0; i < fromString.size(); i++) {
            assertEquals(fromString.get(i).toJsonString(), fromBytes.get(i).toJsonString());
        }
    }

    // ========================================================================
    //  SWAR string scanning edge cases
    //  Tests boundary conditions around the 8-byte SWAR scanning window
    // ========================================================================

    @Test
    void testByteParserStringLengths1to16() {
        // Test strings of every length from 1 to 16 characters
        // to exercise both SWAR path (>=8 bytes) and scalar tail (<8 bytes)
        for (int len = 1; len <= 16; len++) {
            String value = "a".repeat(len);
            String json = "\"" + value + "\"";
            assertByteParseSame(json);
        }
    }

    @Test
    void testByteParserQuoteAtEachPosition() {
        // String where the closing quote falls at each position 0-15
        // within SWAR scanning windows. Tests that getIndex() correctly
        // identifies the first match position.
        for (int len = 0; len <= 15; len++) {
            String value = "x".repeat(len);
            String json = "\"" + value + "\"";
            JqValue result = JqValues.parse(json.getBytes(StandardCharsets.UTF_8));
            assertEquals(JqString.of(value), result,
                    "Failed for string length " + len);
        }
    }

    @Test
    void testByteParserBackslashAtEachPosition() {
        // String with backslash at each position 0-15 to test
        // the SWAR -> scalar fallback at every boundary
        for (int pos = 0; pos <= 15; pos++) {
            String prefix = "x".repeat(pos);
            String json = "\"" + prefix + "\\n\"";
            assertByteParseSame(json);
        }
    }

    @Test
    void testByteParserExactly8ByteString() {
        // String content exactly 8 bytes -- fills one SWAR word
        assertByteParseSame("\"abcdefgh\"");
    }

    @Test
    void testByteParserExactly7ByteString() {
        // String content exactly 7 bytes -- scalar tail only, no SWAR
        assertByteParseSame("\"abcdefg\"");
    }

    @Test
    void testByteParserExactly9ByteString() {
        // 9 bytes -- one SWAR iteration + 1 scalar byte
        assertByteParseSame("\"abcdefghi\"");
    }

    @Test
    void testByteParserExactly16ByteString() {
        // 16 bytes -- two full SWAR iterations
        assertByteParseSame("\"abcdefghijklmnop\"");
    }

    @Test
    void testByteParserLongString() {
        // 100+ bytes -- many SWAR iterations + scalar tail
        String value = "abcdefghij".repeat(10);
        assertByteParseSame("\"" + value + "\"");
    }

    @Test
    void testByteParserMultipleStringsInObject() {
        // Object with many string fields -- exercises key scanning (SWAR)
        // and value scanning (SWAR) across multiple fields
        var sb = new StringBuilder("{");
        for (int i = 0; i < 20; i++) {
            if (i > 0) sb.append(",");
            String key = "field_" + i + "_name";
            String val = "value_" + i + "_data_" + "x".repeat(i);
            sb.append("\"").append(key).append("\":\"").append(val).append("\"");
        }
        sb.append("}");
        assertByteParseSame(sb.toString());
    }

    @Test
    void testByteParserEscapeAt8ByteBoundary() {
        // Escape sequence spanning the 8-byte SWAR boundary
        // "1234567\n" -- backslash at position 7, \n at position 8
        assertByteParseSame("\"1234567\\n\"");
        // "12345678\n" -- backslash at position 8 (start of second SWAR word)
        assertByteParseSame("\"12345678\\n\"");
    }

    @Test
    void testByteParserEscapedQuoteInSwarWindow() {
        // Escaped quote within SWAR scanning range
        // The SWAR should detect the backslash, fall to scalar path,
        // which then correctly skips the escaped quote
        assertByteParseSame("\"hello\\\"world\"");
        assertByteParseSame("\"12345678\\\"end\"");  // escaped quote after 8 chars
        assertByteParseSame("\"1234\\\"5678end\"");   // escaped quote at position 4
    }

    @Test
    void testByteParserUnicodeEscapeInSwarWindow() {
        // Unicode escape sequences within SWAR scanning range
        assertByteParseSame("\"prefix\\u0041suffix\"");  // \u0041 = A
        assertByteParseSame("\"12345678\\u0042end\"");   // unicode after 8 chars
    }

    @Test
    void testByteParserManyShortStrings() {
        // Array of very short strings -- each string is < 8 bytes,
        // exercises the scalar tail path exclusively
        assertByteParseSame("[\"a\",\"bb\",\"ccc\",\"dddd\",\"eeeee\",\"ffffff\",\"ggggggg\"]");
    }

    @Test
    void testByteParserEmptyAndSingleCharStrings() {
        assertByteParseSame("\"\"");
        assertByteParseSame("\"a\"");
        assertByteParseSame("\"\\n\"");
        assertByteParseSame("\"\\\"\"");
    }

    @Test
    void testByteParserObjectKeysVariedLengths() {
        // Object keys of varied lengths to exercise SWAR key scanning
        assertByteParseSame("{\"a\":1}");
        assertByteParseSame("{\"abcdefg\":1}");          // 7 bytes (scalar only)
        assertByteParseSame("{\"abcdefgh\":1}");         // 8 bytes (one SWAR word)
        assertByteParseSame("{\"abcdefghijklmnop\":1}"); // 16 bytes (two SWAR words)
        assertByteParseSame("{\"avThroughput\":1}");     // typical h5m key
        assertByteParseSame("{\"config.QUARKUS_VERSION\":\"3.8.1\"}"); // longer key
    }

    @Test
    void testByteParserRoundTripConsistency() {
        // Parse from bytes, serialize to JSON, re-parse from String,
        // verify the values are equal
        String[] inputs = {
            "{\"name\":\"Alice\",\"scores\":[1,2,3],\"active\":true}",
            "[{\"id\":1,\"label\":\"first\"},{\"id\":2,\"label\":\"second_item_with_longer_name\"}]",
            "{\"nested\":{\"deep\":{\"value\":\"found_it_here\"}}}",
        };
        for (String json : inputs) {
            JqValue fromBytes = JqValues.parse(json.getBytes(StandardCharsets.UTF_8));
            String serialized = fromBytes.toJsonString();
            JqValue reParsed = JqValues.parse(serialized);
            assertEquals(reParsed, fromBytes,
                    "Round-trip failed for: " + json);
        }
    }

    private void assertByteParseSame(String json) {
        JqValue fromString = JqValues.parse(json);
        JqValue fromBytes = JqValues.parse(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(fromString.toJsonString(), fromBytes.toJsonString(),
                "Byte parser produced different output for: " + json);
        // Also verify equals works (tests materialization for deferred strings)
        assertEquals(fromString, fromBytes,
                "Byte parser value not equal for: " + json);
    }

    // ========================================================================
    //  SwarUtil.loadInt + packPartialQuad tests
    // ========================================================================

    @Test
    void testLoadIntBigEndian() {
        byte[] data = { 'a', 'b', 'c', 'd', 'e', 'f' }; // 0x61 0x62 0x63 0x64 ...
        int q = SwarUtil.loadInt(data, 0);
        // big-endian: first byte in highest 8 bits
        assertEquals(0x61626364, q, "loadInt should be big-endian");
        int q2 = SwarUtil.loadInt(data, 2);
        assertEquals(0x63646566, q2, "loadInt at offset 2");
    }

    @Test
    void testPackPartialQuad() {
        byte[] data = { 'i', 'd' }; // "id" — 2 bytes
        int q = SwarUtil.packPartialQuad(data, 0, 2);
        // big-endian zero-padded: 0x69640000
        assertEquals(0x69640000, q, "packPartialQuad for 2-byte key");

        byte[] data3 = { 'a', 'b', 'c' };
        int q3 = SwarUtil.packPartialQuad(data3, 0, 3);
        assertEquals(0x61626300, q3, "packPartialQuad for 3-byte key");

        byte[] data1 = { 'x' };
        int q1 = SwarUtil.packPartialQuad(data1, 0, 1);
        assertEquals(0x78000000, q1, "packPartialQuad for 1-byte key");
    }

    @Test
    void testPackPartialQuadMatchesLoadInt() {
        // For a 4-byte key, packPartialQuad(0, 4) should produce the same result as loadInt
        byte[] data = { 't', 'y', 'p', 'e' }; // "type"
        int fromLoad = SwarUtil.loadInt(data, 0);
        int fromPack = SwarUtil.packPartialQuad(data, 0, 4);
        assertEquals(fromLoad, fromPack, "loadInt and packPartialQuad should agree for 4 bytes");
    }

    // ========================================================================
    //  Intern table: quad-based verification + multi-slot probing
    // ========================================================================

    @Test
    void testInternFieldNameCacheHit() {
        // Intern a name, then re-intern — should return same reference
        String name = "testInternHit_" + System.nanoTime(); // unique to avoid collisions
        String interned1 = JqValues.internFieldName(name);
        String interned2 = JqValues.internFieldName(name);
        assertSame(interned1, interned2, "Same name should return cached reference");
    }

    @Test
    void testInternFieldNameShortKeys() {
        // Short keys (1-3 bytes) go through packPartialQuad
        String k1 = "a_" + System.nanoTime();
        String k2 = "ab_" + System.nanoTime();
        String k3 = "abc_" + System.nanoTime();
        assertSame(JqValues.internFieldName(k1), JqValues.internFieldName(k1));
        assertSame(JqValues.internFieldName(k2), JqValues.internFieldName(k2));
        assertSame(JqValues.internFieldName(k3), JqValues.internFieldName(k3));
    }

    @Test
    void testInternFieldNameLongKey() {
        // Key > 12 bytes — exercises matchBytesFrom path
        String longKey = "very_long_field_name_" + System.nanoTime();
        assertTrue(longKey.length() > 12, "Key should be > 12 bytes");
        String interned1 = JqValues.internFieldName(longKey);
        String interned2 = JqValues.internFieldName(longKey);
        assertSame(interned1, interned2, "Long key should still cache-hit");
    }

    @Test
    void testInternedJsonKeyWithProbing() {
        // Intern a name, then verify internedJsonKey returns the pre-computed form
        String name = "qtest_" + System.nanoTime();
        String interned = JqValues.internFieldName(name);
        String jsonKey = JqValues.internedJsonKey(interned);
        assertNotNull(jsonKey, "internedJsonKey should find the entry");
        assertEquals("\"" + name + "\":", jsonKey, "JSON key form should match");
    }

    @Test
    void testByteParserInternQuadVerification() {
        // Parse JSON with byte[] — exercises internKeyWithHash with quad verification
        // Key lengths: 2-byte, 4-byte, 8-byte, 13-byte (crosses quad boundary)
        String json = "{\"id\":1,\"type\":\"x\",\"hostname\":\"server01\",\"mem.util.used\":42}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        // Parse twice — second parse should hit quad cache
        JqValue v1 = JqValues.parse(bytes);
        JqValue v2 = JqValues.parse(bytes);

        assertEquals(v1.toJsonString(), v2.toJsonString(), "Repeated parses should produce same output");

        // Verify field access works correctly (uses interned keys)
        JqObject obj = (JqObject) v2;
        assertEquals(1, obj.get("id").intValue());
        assertEquals("x", obj.get("type").stringValue());
        assertEquals("server01", obj.get("hostname").stringValue());
        assertEquals(42, obj.get("mem.util.used").intValue());
    }

    @Test
    void testByteParserVariedKeyLengths() {
        // Exercises all quad paths: 1-byte, 2-byte, 3-byte, 4-byte, 5-byte,
        // 8-byte, 12-byte (exactly 3 quads), 13-byte (overflow to matchBytesFrom)
        String json = "{" +
                "\"a\":1," +
                "\"ab\":2," +
                "\"abc\":3," +
                "\"abcd\":4," +
                "\"abcde\":5," +
                "\"abcdefgh\":8," +
                "\"abcdefghijkl\":12," +
                "\"abcdefghijklm\":13" +
                "}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        // Parse 3 times to exercise cache hit paths
        for (int i = 0; i < 3; i++) {
            JqValue v = JqValues.parse(bytes);
            JqObject obj = (JqObject) v;
            assertEquals(1, obj.get("a").intValue(), "1-byte key");
            assertEquals(2, obj.get("ab").intValue(), "2-byte key");
            assertEquals(3, obj.get("abc").intValue(), "3-byte key");
            assertEquals(4, obj.get("abcd").intValue(), "4-byte key");
            assertEquals(5, obj.get("abcde").intValue(), "5-byte key");
            assertEquals(8, obj.get("abcdefgh").intValue(), "8-byte key");
            assertEquals(12, obj.get("abcdefghijkl").intValue(), "12-byte key (exact 3 quads)");
            assertEquals(13, obj.get("abcdefghijklm").intValue(), "13-byte key (overflow)");
        }
    }

    @Test
    void testStringAndByteParserInternConsistency() {
        // Parse same JSON with String and byte[] parsers — both should produce
        // identical results and share interned field names
        String json = "{\"name\":\"test\",\"value\":42,\"nested\":{\"deep\":true}}";
        JqValue fromString = JqValues.parse(json);
        JqValue fromBytes = JqValues.parse(json.getBytes(StandardCharsets.UTF_8));

        assertEquals(fromString.toJsonString(), fromBytes.toJsonString());
        assertEquals(fromString, fromBytes);

        // Both should be able to get internedJsonKey for the same field names
        JqObject sObj = (JqObject) fromString;
        JqObject bObj = (JqObject) fromBytes;
        assertEquals(sObj.get("name"), bObj.get("name"));
        assertEquals(sObj.get("value"), bObj.get("value"));
        assertEquals(sObj.get("nested"), bObj.get("nested"));
    }

    @Test
    void testHighLoadInternTable() {
        // Intern many unique keys to exercise probing and potential evictions
        // With 1024 slots and max 4 probes, ~250 unique keys should be manageable
        for (int i = 0; i < 250; i++) {
            String key = "field_" + i;
            String interned = JqValues.internFieldName(key);
            // Re-intern should return same reference (if not evicted)
            String interned2 = JqValues.internFieldName(key);
            assertEquals(interned, interned2, "Key should survive re-interning: " + key);
        }
    }

    @Test
    void testByteParserHighUniqueKeyDocument() {
        // Build a JSON document with 100 unique keys and parse it
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < 100; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"unique_field_").append(i).append("\":").append(i);
        }
        sb.append("}");
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);

        // Parse twice — second should hit cache for all keys
        JqValue v1 = JqValues.parse(bytes);
        JqValue v2 = JqValues.parse(bytes);

        JqObject obj1 = (JqObject) v1;
        JqObject obj2 = (JqObject) v2;
        for (int i = 0; i < 100; i++) {
            assertEquals(i, obj1.get("unique_field_" + i).intValue());
            assertEquals(i, obj2.get("unique_field_" + i).intValue());
        }
    }

    // ========================================================================
    //  typeStructure + mergeTypeStructures tests
    // ========================================================================

    @Test
    void testTypeStructureScalars() {
        assertEquals(JqString.of("null"), JqValues.typeStructure(JqNull.NULL));
        assertEquals(JqString.of("boolean"), JqValues.typeStructure(JqBoolean.TRUE));
        assertEquals(JqString.of("boolean"), JqValues.typeStructure(JqBoolean.FALSE));
        assertEquals(JqString.of("string"), JqValues.typeStructure(JqString.of("hello")));
        assertEquals(JqString.of("integer"), JqValues.typeStructure(JqNumber.of(42)));
        assertEquals(JqString.of("integer"), JqValues.typeStructure(JqNumber.of(0)));
        assertEquals(JqString.of("integer"), JqValues.typeStructure(JqNumber.of(-1)));
        assertEquals(JqString.of("number"), JqValues.typeStructure(JqNumber.of(3.14)));
        assertEquals(JqString.of("number"), JqValues.typeStructure(JqNumber.of(0.5)));
        // Note: JqNumber.of(0.0) produces an integral number (0L), so it's "integer"
        assertEquals(JqString.of("integer"), JqValues.typeStructure(JqNumber.of(0.0)));
    }

    @Test
    void testTypeStructureEmptyContainers() {
        assertEquals(JqArray.EMPTY, JqValues.typeStructure(JqArray.EMPTY));
        assertEquals(JqValues.parse("{}"), JqValues.typeStructure(JqValues.parse("{}")));
    }

    @Test
    void testTypeStructureFlatObject() {
        JqValue input = JqValues.parse("{\"name\":\"Alice\",\"age\":30,\"active\":true}");
        JqValue expected = JqValues.parse("{\"name\":\"string\",\"age\":\"integer\",\"active\":\"boolean\"}");
        assertEquals(expected, JqValues.typeStructure(input));
    }

    @Test
    void testTypeStructureNestedObject() {
        JqValue input = JqValues.parse("{\"user\":{\"name\":\"Bob\",\"score\":9.5},\"ok\":true}");
        JqValue expected = JqValues.parse("{\"user\":{\"name\":\"string\",\"score\":\"number\"},\"ok\":\"boolean\"}");
        assertEquals(expected, JqValues.typeStructure(input));
    }

    @Test
    void testTypeStructureHomogeneousArray() {
        JqValue input = JqValues.parse("[1, 2, 3]");
        JqValue expected = JqValues.parse("[\"integer\"]");
        assertEquals(expected, JqValues.typeStructure(input));
    }

    @Test
    void testTypeStructureMixedNumericArray() {
        // integer + number promotes to number
        JqValue input = JqValues.parse("[1, 2.5, 3]");
        JqValue expected = JqValues.parse("[\"number\"]");
        assertEquals(expected, JqValues.typeStructure(input));
    }

    @Test
    void testTypeStructureMixedTypeArray() {
        // Incompatible types: first wins (integer), but number promotes
        JqValue input = JqValues.parse("[1, \"hello\", 2.5]");
        // Element 0: "integer", element 1: merge("integer","string") → "integer" (first wins)
        // Element 2: merge("integer","number") → "number" (promotion)
        JqValue expected = JqValues.parse("[\"number\"]");
        assertEquals(expected, JqValues.typeStructure(input));
    }

    @Test
    void testTypeStructureArrayOfObjects() {
        JqValue input = JqValues.parse("[{\"a\":1,\"b\":\"x\"},{\"a\":2,\"b\":\"y\"}]");
        JqValue expected = JqValues.parse("[{\"a\":\"integer\",\"b\":\"string\"}]");
        assertEquals(expected, JqValues.typeStructure(input));
    }

    @Test
    void testTypeStructureNestedArrays() {
        JqValue input = JqValues.parse("[[1,2],[3,4]]");
        JqValue expected = JqValues.parse("[[\"integer\"]]");
        assertEquals(expected, JqValues.typeStructure(input));
    }

    @Test
    void testTypeStructureNullInObject() {
        JqValue input = JqValues.parse("{\"a\":null,\"b\":1}");
        JqValue expected = JqValues.parse("{\"a\":\"null\",\"b\":\"integer\"}");
        assertEquals(expected, JqValues.typeStructure(input));
    }

    @Test
    void testTypeStructureProductionLike() {
        // Mimics h5m production shape: nested objects with arrays of metrics
        JqValue input = JqValues.parse(
                "{\"name\":\"test\",\"data\":[{\"cpu\":0.5,\"mem\":1024},{\"cpu\":0.8,\"mem\":2048}]}");
        JqValue expected = JqValues.parse(
                "{\"name\":\"string\",\"data\":[{\"cpu\":\"number\",\"mem\":\"integer\"}]}");
        assertEquals(expected, JqValues.typeStructure(input));
    }

    // --- mergeTypeStructures tests ---

    @Test
    void testMergeIdenticalStructures() {
        JqValue a = JqValues.parse("{\"name\":\"string\",\"age\":\"integer\"}");
        assertEquals(a, JqValues.mergeTypeStructures(a, a));
    }

    @Test
    void testMergeSupersetKeys() {
        JqValue a = JqValues.parse("{\"name\":\"string\"}");
        JqValue b = JqValues.parse("{\"name\":\"string\",\"age\":\"integer\"}");
        JqValue merged = JqValues.mergeTypeStructures(a, b);
        JqObject obj = (JqObject) merged;
        assertEquals(JqString.of("string"), obj.get("name"));
        assertEquals(JqString.of("integer"), obj.get("age"));
    }

    @Test
    void testMergeTypeStructureDisjointKeys() {
        JqValue a = JqValues.parse("{\"x\":\"string\"}");
        JqValue b = JqValues.parse("{\"y\":\"integer\"}");
        JqValue merged = JqValues.mergeTypeStructures(a, b);
        JqObject obj = (JqObject) merged;
        assertEquals(JqString.of("string"), obj.get("x"));
        assertEquals(JqString.of("integer"), obj.get("y"));
    }

    @Test
    void testMergeIntegerNumberPromotion() {
        JqValue a = JqString.of("integer");
        JqValue b = JqString.of("number");
        assertEquals(JqString.of("number"), JqValues.mergeTypeStructures(a, b));
        assertEquals(JqString.of("number"), JqValues.mergeTypeStructures(b, a)); // symmetric
    }

    @Test
    void testMergeIncompatibleLeafTypes() {
        JqValue a = JqString.of("string");
        JqValue b = JqString.of("boolean");
        // Incompatible: keeps first
        assertEquals(JqString.of("string"), JqValues.mergeTypeStructures(a, b));
        assertEquals(JqString.of("boolean"), JqValues.mergeTypeStructures(b, a));
    }

    @Test
    void testMergeEmptyWithNonEmptyArray() {
        JqValue empty = JqArray.EMPTY;
        JqValue nonEmpty = JqValues.parse("[\"integer\"]");
        assertEquals(nonEmpty, JqValues.mergeTypeStructures(empty, nonEmpty));
        assertEquals(nonEmpty, JqValues.mergeTypeStructures(nonEmpty, empty));
    }

    @Test
    void testMergeArraysWithDifferentElementTypes() {
        JqValue a = JqValues.parse("[\"integer\"]");
        JqValue b = JqValues.parse("[\"number\"]");
        JqValue expected = JqValues.parse("[\"number\"]");
        assertEquals(expected, JqValues.mergeTypeStructures(a, b));
    }

    @Test
    void testMergeNestedObjectStructures() {
        JqValue a = JqValues.parse("{\"data\":{\"cpu\":\"number\"}}");
        JqValue b = JqValues.parse("{\"data\":{\"cpu\":\"number\",\"mem\":\"integer\"}}");
        JqValue merged = JqValues.mergeTypeStructures(a, b);
        JqObject data = (JqObject) ((JqObject) merged).get("data");
        assertEquals(JqString.of("number"), data.get("cpu"));
        assertEquals(JqString.of("integer"), data.get("mem"));
    }

    @Test
    void testMergeMultipleDocuments() {
        // Simulates FolderService.structure() — merging schemas from multiple uploads
        JqValue doc1 = JqValues.parse("{\"name\":\"test1\",\"value\":42}");
        JqValue doc2 = JqValues.parse("{\"name\":\"test2\",\"value\":3.14,\"extra\":true}");
        JqValue doc3 = JqValues.parse("{\"name\":\"test3\",\"value\":100}");

        JqValue s1 = JqValues.typeStructure(doc1);
        JqValue s2 = JqValues.typeStructure(doc2);
        JqValue s3 = JqValues.typeStructure(doc3);

        JqValue merged = JqValues.mergeTypeStructures(s1, s2);
        merged = JqValues.mergeTypeStructures(merged, s3);

        JqObject result = (JqObject) merged;
        assertEquals(JqString.of("string"), result.get("name"));
        assertEquals(JqString.of("number"), result.get("value")); // integer + number → number
        assertEquals(JqString.of("boolean"), result.get("extra")); // from doc2 only
    }

    // --- edge cases ---

    @Test
    void testTypeStructureNullJavaInputThrowsNPE() {
        assertThrows(NullPointerException.class, () -> JqValues.typeStructure(null));
    }

    @Test
    void testMergeTypeStructuresNullFirstArgThrowsNPE() {
        JqValue a = JqString.of("string");
        assertThrows(NullPointerException.class, () -> JqValues.mergeTypeStructures(null, a));
    }

    @Test
    void testMergeTypeStructuresNullSecondArgReturnFirst() {
        // When b is null, a.equals(null) returns false, instanceof checks fail,
        // and the method returns a (first argument). This matches "keep first" semantics.
        JqValue a = JqString.of("string");
        assertEquals(a, JqValues.mergeTypeStructures(a, null));
    }

    @Test
    void testTypeStructureSingleElementArray() {
        JqValue input = JqValues.parse("[42]");
        JqValue expected = JqValues.parse("[\"integer\"]");
        assertEquals(expected, JqValues.typeStructure(input));
    }

    @Test
    void testTypeStructureAllNullObject() {
        JqValue input = JqValues.parse("{\"a\":null,\"b\":null,\"c\":null}");
        JqValue expected = JqValues.parse("{\"a\":\"null\",\"b\":\"null\",\"c\":\"null\"}");
        assertEquals(expected, JqValues.typeStructure(input));
    }

    @Test
    void testTypeStructureEmptyStringValue() {
        JqValue input = JqValues.parse("{\"name\":\"\"}");
        JqValue expected = JqValues.parse("{\"name\":\"string\"}");
        assertEquals(expected, JqValues.typeStructure(input));
    }

    @Test
    void testTypeStructureLargeInteger() {
        // Long.MAX_VALUE is still integral
        JqValue input = JqNumber.of(Long.MAX_VALUE);
        assertEquals(JqString.of("integer"), JqValues.typeStructure(input));
    }

    @Test
    void testTypeStructureBigDecimalNumber() {
        // Parsed from JSON: high-precision decimal is "number"
        JqValue input = JqValues.parse("3.14159265358979323846");
        assertEquals(JqString.of("number"), JqValues.typeStructure(input));
    }

    @Test
    void testTypeStructureDeeplyNested() {
        JqValue input = JqValues.parse("{\"a\":{\"b\":{\"c\":{\"d\":42}}}}");
        JqValue expected = JqValues.parse("{\"a\":{\"b\":{\"c\":{\"d\":\"integer\"}}}}");
        assertEquals(expected, JqValues.typeStructure(input));
    }

    @Test
    void testTypeStructureArrayOfMixedObjects() {
        // Objects with different key sets → merged keys
        JqValue input = JqValues.parse("[{\"a\":1},{\"b\":\"x\"},{\"a\":2,\"c\":true}]");
        JqValue result = JqValues.typeStructure(input);
        JqObject elem = (JqObject) ((JqArray) result).get(0);
        assertEquals(JqString.of("integer"), elem.get("a"));
        assertEquals(JqString.of("string"), elem.get("b"));
        assertEquals(JqString.of("boolean"), elem.get("c"));
    }

    @Test
    void testTypeStructureIdempotent() {
        // Applying typeStructure twice should be stable
        JqValue input = JqValues.parse("{\"name\":\"Alice\",\"scores\":[1,2,3]}");
        JqValue once = JqValues.typeStructure(input);
        JqValue twice = JqValues.typeStructure(once);
        // Second pass: all leaves are strings → all become "string"
        JqObject obj = (JqObject) twice;
        assertEquals(JqString.of("string"), obj.get("name"));
        // scores was ["integer"], second pass: ["string"]
        JqArray scores = (JqArray) obj.get("scores");
        assertEquals(JqString.of("string"), scores.get(0));
    }

    @Test
    void testMergeObjectWithNonObject() {
        // Container mismatch: object vs string → keep first
        JqValue a = JqValues.parse("{\"x\":\"string\"}");
        JqValue b = JqString.of("string");
        assertEquals(a, JqValues.mergeTypeStructures(a, b));
        assertEquals(b, JqValues.mergeTypeStructures(b, a));
    }

    @Test
    void testMergeArrayWithNonArray() {
        // Container mismatch: array vs string → keep first
        JqValue a = JqValues.parse("[\"integer\"]");
        JqValue b = JqString.of("string");
        assertEquals(a, JqValues.mergeTypeStructures(a, b));
        assertEquals(b, JqValues.mergeTypeStructures(b, a));
    }

    @Test
    void testMergeNullTypeStructures() {
        // Both "null" type → identical, return as-is
        JqValue a = JqString.of("null");
        JqValue b = JqString.of("null");
        assertEquals(a, JqValues.mergeTypeStructures(a, b));
    }

    @Test
    void testMergeNullWithOtherType() {
        // "null" + "string" → incompatible, keep first
        JqValue a = JqString.of("null");
        JqValue b = JqString.of("string");
        assertEquals(a, JqValues.mergeTypeStructures(a, b));
        assertEquals(b, JqValues.mergeTypeStructures(b, a));
    }

    @Test
    void testTypeStructureArrayOfNulls() {
        JqValue input = JqValues.parse("[null, null, null]");
        JqValue expected = JqValues.parse("[\"null\"]");
        assertEquals(expected, JqValues.typeStructure(input));
    }

    @Test
    void testMergeObjectsWithOverlappingAndUniqueKeys() {
        // a has {x, y}, b has {y, z} → merged has {x, y, z}
        JqValue a = JqValues.parse("{\"x\":\"string\",\"y\":\"integer\"}");
        JqValue b = JqValues.parse("{\"y\":\"number\",\"z\":\"boolean\"}");
        JqValue merged = JqValues.mergeTypeStructures(a, b);
        JqObject obj = (JqObject) merged;
        assertEquals(JqString.of("string"), obj.get("x"));    // only in a
        assertEquals(JqString.of("number"), obj.get("y"));     // integer + number → number
        assertEquals(JqString.of("boolean"), obj.get("z"));    // only in b
    }

    // ========================================================================
    //  serializeToBytes tests
    // ========================================================================

    @Test
    void testSerializeToBytesScalars() {
        assertBytesEquivalent(JqNull.NULL);
        assertBytesEquivalent(JqBoolean.TRUE);
        assertBytesEquivalent(JqBoolean.FALSE);
        assertBytesEquivalent(JqNumber.of(0));
        assertBytesEquivalent(JqNumber.of(42));
        assertBytesEquivalent(JqNumber.of(-1));
        assertBytesEquivalent(JqNumber.of(Long.MAX_VALUE));
        assertBytesEquivalent(JqNumber.of(Long.MIN_VALUE));
        assertBytesEquivalent(JqNumber.of(3.14));
        assertBytesEquivalent(JqString.of("hello"));
        assertBytesEquivalent(JqString.of(""));
    }

    @Test
    void testSerializeToBytesObject() {
        assertBytesEquivalent(JqValues.parse("{\"name\":\"Alice\",\"age\":30}"));
        assertBytesEquivalent(JqValues.parse("{}"));
    }

    @Test
    void testSerializeToBytesArray() {
        assertBytesEquivalent(JqValues.parse("[1,2,3]"));
        assertBytesEquivalent(JqValues.parse("[]"));
        assertBytesEquivalent(JqValues.parse("[\"a\",\"b\",\"c\"]"));
    }

    @Test
    void testSerializeToBytesNested() {
        assertBytesEquivalent(JqValues.parse(
                "{\"users\":[{\"name\":\"Alice\",\"scores\":[95,87]},{\"name\":\"Bob\",\"scores\":[]}]}"));
    }

    @Test
    void testSerializeToBytesUnicode() {
        // Multi-byte UTF-8: café, emoji, CJK
        assertBytesEquivalent(JqString.of("caf\u00e9"));       // 2-byte UTF-8
        assertBytesEquivalent(JqString.of("\u4e16\u754c"));     // 3-byte UTF-8 (Chinese: 世界)
        assertBytesEquivalent(JqString.of("hello \ud83d\ude00")); // 4-byte UTF-8 (emoji)
    }

    @Test
    void testSerializeToBytesEscapedStrings() {
        assertBytesEquivalent(JqString.of("line1\nline2"));
        assertBytesEquivalent(JqString.of("tab\there"));
        assertBytesEquivalent(JqString.of("quote\"inside"));
        assertBytesEquivalent(JqString.of("back\\slash"));
        assertBytesEquivalent(JqString.of("\b\f\r"));
    }

    @Test
    void testSerializeToBytesRoundTrip() {
        // Parse from bytes, serialize back to bytes, parse again — must be equal
        String json = "{\"name\":\"Alice\",\"age\":30,\"scores\":[95,87.5],\"active\":true,\"data\":null}";
        byte[] original = json.getBytes(StandardCharsets.UTF_8);
        JqValue parsed = JqValues.parse(original);
        byte[] serialized = JqValues.serializeToBytes(parsed);
        JqValue reparsed = JqValues.parse(serialized);
        assertEquals(parsed, reparsed, "Round-trip parse→bytes→parse should produce equal values");
    }

    @Test
    void testSerializeToBytesRoundTripFromBytes() {
        // Parse from bytes (creates deferred-bytes strings), serialize to bytes
        // This exercises the zero-copy path for deferred-bytes strings
        String json = "{\"host\":\"server01\",\"cpu\":0.75,\"mem\":1024,\"tags\":[\"prod\",\"us-east\"]}";
        byte[] input = json.getBytes(StandardCharsets.UTF_8);
        JqValue parsed = JqValues.parse(input);
        byte[] output = JqValues.serializeToBytes(parsed);
        // Verify byte-for-byte equivalence with the char-based path
        String fromBytes = new String(output, StandardCharsets.UTF_8);
        assertEquals(parsed.toJsonString(), fromBytes,
                "Byte serialization should produce same output as char serialization");
    }

    @Test
    void testSerializeToBytesLargeDocument() {
        // Build a ~100KB document
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < 1000; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"field_").append(i).append("\":\"value_").append(i).append("\"");
        }
        sb.append("}");
        JqValue large = JqValues.parse(sb.toString());
        assertBytesEquivalent(large);
    }

    @Test
    void testSerializeToBytesNullValues() {
        assertBytesEquivalent(JqValues.parse("{\"a\":null,\"b\":[null,null]}"));
    }

    @Test
    void testSerializeToBytesNumberFormats() {
        // Various number formats
        assertBytesEquivalent(JqValues.parse("0"));
        assertBytesEquivalent(JqValues.parse("-0"));
        assertBytesEquivalent(JqValues.parse("1e10"));
        assertBytesEquivalent(JqValues.parse("3.14159265358979323846"));
    }

    /** Assert that serializeToBytes produces the same output as toJsonString().getBytes(UTF_8) */
    private void assertBytesEquivalent(JqValue value) {
        byte[] fromBytes = JqValues.serializeToBytes(value);
        byte[] fromString = value.toJsonString().getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(fromString, fromBytes,
                "serializeToBytes should produce same output as toJsonString().getBytes() for: "
                        + value.toJsonString());
    }
}

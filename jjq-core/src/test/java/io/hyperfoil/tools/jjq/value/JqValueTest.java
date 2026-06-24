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
}

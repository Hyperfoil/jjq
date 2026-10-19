package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boxed scalar nullability (Jackson/JSON-B parity): absent keys and explicit
 * JSON nulls bind {@code null} for boxed targets, while primitives keep
 * their JVM zero-values. Non-null inputs convert exactly like the static
 * coercion helpers, on both the reflective and generated paths.
 */
class JqMapperBoxedNullTest {

    private final JqMapper mapper = JqMapper.create();

    record BoxedScalars(Integer i, Long l, Double d, Float f, Boolean b,
                        Short s, Byte y, Character c, int prim, boolean primFlag) {}

    @Test
    void boxedAbsentBindsNullPrimitivesKeepZero() {
        BoxedScalars r = mapper.fromJqValue(JqValues.parse("{}"), BoxedScalars.class);
        assertNull(r.i());
        assertNull(r.l());
        assertNull(r.d());
        assertNull(r.f());
        assertNull(r.b());
        assertNull(r.s());
        assertNull(r.y());
        assertNull(r.c());
        assertEquals(0, r.prim());
        assertFalse(r.primFlag());
    }

    @Test
    void boxedExplicitNullBindsNull() {
        BoxedScalars r = mapper.fromJqValue(JqValues.parse(
                "{\"i\":null,\"l\":null,\"d\":null,\"f\":null,\"b\":null,\"s\":null,\"y\":null,\"c\":null}"),
                BoxedScalars.class);
        assertNull(r.i());
        assertNull(r.l());
        assertNull(r.d());
        assertNull(r.f());
        assertNull(r.b());
        assertNull(r.s());
        assertNull(r.y());
        assertNull(r.c());
    }

    @Test
    void boxedValuesConvertExactly() {
        BoxedScalars r = mapper.fromJqValue(JqValues.parse(
                "{\"i\":42,\"l\":4200000000,\"d\":4.5,\"f\":2.5,\"b\":true,\"s\":100,\"y\":7,\"c\":\"A\","
                        + "\"prim\":3,\"primFlag\":true}"),
                BoxedScalars.class);
        assertEquals(42, r.i());
        assertEquals(4200000000L, r.l());
        assertEquals(4.5, r.d());
        assertEquals(2.5f, r.f());
        assertTrue(r.b());
        assertEquals((short) 100, r.s());
        assertEquals((byte) 7, r.y());
        assertEquals('A', r.c());
        assertEquals(3, r.prim());
        assertTrue(r.primFlag());
    }

    @Test
    void boxedMismatchStillFails() {
        JqValue bad = JqValues.parse("{\"i\":{},\"l\":0,\"d\":0,\"f\":0,\"b\":false,\"s\":0,\"y\":0,\"c\":\"A\","
                + "\"prim\":0,\"primFlag\":false}");
        ShapeMismatchException e = assertThrows(ShapeMismatchException.class,
                () -> mapper.fromJqValue(bad, BoxedScalars.class));
        assertEquals("$.i", e.path());
    }

    @Test
    void boxedRoundTripPreservesNull() {
        BoxedScalars original = new BoxedScalars(null, 1L, null, null, null, null, null, null, 0, false);
        assertEquals(original, mapper.fromJqValue(mapper.toJqValue(original), BoxedScalars.class));
    }
}

package io.hyperfoil.tools.jjq.builtin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BuiltinRegistryTest {

    @Test
    void loadsOnFirstLookupAndCaches() {
        var registry = new BuiltinRegistry();
        BuiltinFunction first = registry.get("keys", 0);
        assertNotNull(first);
        assertSame(first, registry.get("keys", 0));
    }

    @Test
    void customOverrideWinsBeforeLoad() {
        var registry = new BuiltinRegistry();
        BuiltinFunction custom = (input, args, env, eval, out) -> {};
        registry.register("keys", 0, custom);
        assertSame(custom, registry.get("keys", 0));
    }

    @Test
    void customOverrideWinsAfterLoad() {
        var registry = new BuiltinRegistry();
        assertNotNull(registry.get("keys", 0));
        BuiltinFunction custom = (input, args, env, eval, out) -> {};
        registry.register("keys", 0, custom);
        assertSame(custom, registry.get("keys", 0));
    }

    @Test
    void unknownReturnsNull() {
        var registry = new BuiltinRegistry();
        assertNull(registry.get("nope", 0));
        assertNull(registry.get("nope", 0));
    }

    @Test
    void absPreservesNonFinite() {
        // Direct registry invocation (pins the evaluator path, not the VM fast path)
        var registry = new BuiltinRegistry();
        var fn = registry.get("abs", 0);
        assertNotNull(fn);
        var out = new java.util.ArrayList<io.hyperfoil.tools.jjq.value.JqValue>();
        fn.apply(io.hyperfoil.tools.jjq.value.JqNumber.of(Double.POSITIVE_INFINITY),
                java.util.List.of(), null, null, out::add);
        assertEquals(io.hyperfoil.tools.jjq.value.JqNumber.of(Double.POSITIVE_INFINITY), out.get(0));
        out.clear();
        fn.apply(io.hyperfoil.tools.jjq.value.JqNumber.of(Double.NaN),
                java.util.List.of(), null, null, out::add);
        var nan = out.get(0);
        assertTrue(nan instanceof io.hyperfoil.tools.jjq.value.JqNumber n && Double.isNaN(n.doubleValue()));
    }

    @Test
    void keySetListsAllDefaultsWithoutLoading() {
        var registry = new BuiltinRegistry();
        var keys = registry.keySet();
        assertTrue(keys.contains("keys/0"), "default keys listed");
        assertTrue(keys.contains("map/1"), "default keys listed");
        assertTrue(keys.contains("sort_by/1"), "default keys listed");
        // Every loadable default is listed (keeps the key literal in sync)
        for (String key : keys) {
            String[] parts = key.split("/");
            assertNotNull(registry.get(parts[0], Integer.parseInt(parts[1])), key);
        }
    }

    // ---- floor/ceil/round preserve non-finite values (issue #142) ----

    private static io.hyperfoil.tools.jjq.value.JqValue applyMath(
            BuiltinRegistry registry, String name, io.hyperfoil.tools.jjq.value.JqValue input) {
        var fn = registry.get(name, 0);
        assertNotNull(fn, name);
        var out = new java.util.ArrayList<io.hyperfoil.tools.jjq.value.JqValue>();
        fn.apply(input, java.util.List.of(), null, null, out::add);
        assertEquals(1, out.size(), name);
        return out.get(0);
    }

    @Test
    void floorCeilRoundPreserveNonFinite() {
        var registry = new BuiltinRegistry();
        var inf = io.hyperfoil.tools.jjq.value.JqNumber.of(Double.POSITIVE_INFINITY);
        var nan = io.hyperfoil.tools.jjq.value.JqNumber.of(Double.NaN);
        assertEquals(inf, applyMath(registry, "floor", inf));
        assertEquals(inf, applyMath(registry, "ceil", inf));
        assertEquals(inf, applyMath(registry, "round", inf));
        assertTrue(applyMath(registry, "floor", nan)
                instanceof io.hyperfoil.tools.jjq.value.JqNumber n && Double.isNaN(n.doubleValue()));
        assertTrue(applyMath(registry, "ceil", nan)
                instanceof io.hyperfoil.tools.jjq.value.JqNumber n && Double.isNaN(n.doubleValue()));
        assertTrue(applyMath(registry, "round", nan)
                instanceof io.hyperfoil.tools.jjq.value.JqNumber n && Double.isNaN(n.doubleValue()));
    }

    @Test
    void floorCeilRoundUnchangedForFinite() {
        // The cast removal must not change any finite behavior
        var registry = new BuiltinRegistry();
        assertEquals(io.hyperfoil.tools.jjq.value.JqNumber.of(3),
                applyMath(registry, "floor", io.hyperfoil.tools.jjq.value.JqNumber.of(3.7)));
        assertEquals(io.hyperfoil.tools.jjq.value.JqNumber.of(-4),
                applyMath(registry, "floor", io.hyperfoil.tools.jjq.value.JqNumber.of(-3.2)));
        assertEquals(io.hyperfoil.tools.jjq.value.JqNumber.of(4),
                applyMath(registry, "ceil", io.hyperfoil.tools.jjq.value.JqNumber.of(3.2)));
        assertEquals(io.hyperfoil.tools.jjq.value.JqNumber.of(3),
                applyMath(registry, "round", io.hyperfoil.tools.jjq.value.JqNumber.of(2.5)));
        assertEquals(io.hyperfoil.tools.jjq.value.JqNumber.of(1000000),
                applyMath(registry, "floor", io.hyperfoil.tools.jjq.value.JqNumber.of(1000000.0)));
    }
}

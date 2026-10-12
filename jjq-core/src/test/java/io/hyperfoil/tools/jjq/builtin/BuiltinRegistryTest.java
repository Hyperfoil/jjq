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
}

package io.hyperfoil.tools.jjq.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The augmentation-time mapper producer must construct mappings and bridges
 * with direct constructor calls: {@code Class.forName} /
 * {@code getDeclaredConstructor} / {@code newInstance} have no place in the
 * default startup path. Inspected at the bytecode level because behavior
 * tests alone cannot tell direct construction from reflective loading.
 */
class JjqProducerGenerationTest {

    @Test
    void generatedProducerConstructsDirectly() {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        JjqProcessor.generateMapperProducer((name, data) -> classes.put(name, data),
                List.of("com.example.Foo_JqMapping"), true,
                List.of("com.example.TestBridge"), List.of("com.example.Foo_EnumBinding"),
                Map.of(ShapeBase.class, new io.hyperfoil.tools.jjq.mapper.PolyConfig("method",
                        Map.of("A", ShapeA.class, "B", ShapeB.class), true)),
                Map.of(NodeBase.class, new io.hyperfoil.tools.jjq.mapper.KeyPolyConfig(
                        List.of(new io.hyperfoil.tools.jjq.mapper.KeyPolyConfig.KeyRule("a", NodeA.class)),
                        NodeB.class)));

        assertEquals(
                List.of("io/hyperfoil/tools/jjq/quarkus/generated/JjqMapperProducer.class"),
                List.copyOf(classes.keySet()));
        String pool = new String(classes.values().iterator().next(), StandardCharsets.ISO_8859_1);
        for (String expected : List.of(
                "com/example/Foo_JqMapping",
                "com/example/TestBridge",
                "com/example/Foo_EnumBinding",
                "failOnUnknownProperties",
                "registerEnum",
                "registerPoly",
                "registerKeys",
                "PolyConfig",
                "KeyPolyConfig",
                "produceMapper")) {
            assertTrue(pool.contains(expected), "emitted class should reference " + expected);
        }
        for (String banned : List.of(
                "forName",
                "getDeclaredConstructor",
                "newInstance",
                "getAnnotation",
                "getPermittedSubclasses")) {
            assertFalse(pool.contains(banned), "emitted class must not reference " + banned);
        }
    }

    @Test
    void generatedProducerHandlesEmptyModel() {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        JjqProcessor.generateMapperProducer((name, data) -> classes.put(name, data),
                List.of(), false, List.of(), List.of(), Map.of(), Map.of());

        assertEquals(1, classes.size());
        String pool = new String(classes.values().iterator().next(), StandardCharsets.ISO_8859_1);
        assertTrue(pool.contains("produceMapper"));
        assertFalse(pool.contains("forName"));
    }

    sealed interface ShapeBase permits ShapeA, ShapeB {}

    record ShapeA(String method, String a) implements ShapeBase {}

    record ShapeB(String method, String b) implements ShapeBase {}

    @io.hyperfoil.tools.jjq.mapper.JqKeys(value = {
            @io.hyperfoil.tools.jjq.mapper.JqKey(value = "a", type = NodeA.class)
    }, defaultSubtype = NodeB.class)
    sealed interface NodeBase permits NodeA, NodeB {}

    record NodeA(int a) implements NodeBase {}

    record NodeB(int b) implements NodeBase {}
}

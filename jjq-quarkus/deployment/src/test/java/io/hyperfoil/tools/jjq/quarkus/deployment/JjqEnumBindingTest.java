package io.hyperfoil.tools.jjq.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.hyperfoil.tools.jjq.mapper.EnumBinding;
import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.mapper.JqMapperException;
import io.hyperfoil.tools.jjq.mapper.TypeConverter;
import io.hyperfoil.tools.jjq.mapper.jackson.JacksonAnnotationBridge;
import io.hyperfoil.tools.jjq.quarkus.test.AuthHolder;
import io.quarkus.gizmo2.ClassOutput;

/**
 * Augmentation-emitted enum bindings execute model member calls directly:
 * the bytes load, link, and round-trip without reflective member
 * invocation. Uses the real {@code AuthHolder.AuthType} fixture so the
 * generated code is verified against genuine bridge-resolved metadata,
 * not synthetic shapes.
 */
class JjqEnumBindingTest {

    private static final Class<AuthHolder.AuthType> AUTH_TYPE = AuthHolder.AuthType.class;

    private static TypeConverter.EnumHandlers handlers() {
        return TypeConverter.resolveEnumHandlers(AUTH_TYPE, List.of(new JacksonAnnotationBridge()))
                .orElseThrow(() -> new AssertionError("fixture must resolve handlers"));
    }

    private static byte[] emit() {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        ClassOutput output = (name, data) -> classes.put(name, data);
        JjqProcessor.generateEnumBinding(output, AUTH_TYPE, handlers());
        assertEquals(
                List.of("io/hyperfoil/tools/jjq/quarkus/test/AuthHolder_AuthType_EnumBinding.class"),
                List.copyOf(classes.keySet()));
        return classes.values().iterator().next();
    }

    private static EnumBinding<AuthHolder.AuthType> sharedBinding;

    private static synchronized EnumBinding<AuthHolder.AuthType> load() throws Exception {
        if (sharedBinding == null) {
            Class<?> defined = MethodHandles.privateLookupIn(AuthHolder.class, MethodHandles.lookup())
                    .defineClass(emit());
            Object instance = defined.getDeclaredConstructor().newInstance();
            @SuppressWarnings("unchecked")
            EnumBinding<AuthHolder.AuthType> binding = (EnumBinding<AuthHolder.AuthType>) instance;
            sharedBinding = binding;
        }
        return sharedBinding;
    }

    @Test
    void emittedBindingRoundTripsWithoutReflection() throws Exception {
        EnumBinding<AuthHolder.AuthType> binding = load();
        JqMapper mapper = JqMapper.builder().registerEnum(binding).build();

        assertEquals(AuthHolder.AuthType.API_KEY,
                mapper.fromJqValue(io.hyperfoil.tools.jjq.value.JqValues.parse("\"api-key\""), AUTH_TYPE));
        assertEquals("\"api-key\"", mapper.toJqValue(AuthHolder.AuthType.API_KEY).toJsonString());
        assertEquals("\"oauth\"", mapper.toJqValue(AuthHolder.AuthType.OAUTH).toJsonString());
    }

    @Test
    void emittedBindingReportsCreatorFailures() throws Exception {
        EnumBinding<AuthHolder.AuthType> binding = load();
        JqMapper mapper = JqMapper.builder().registerEnum(binding).build();

        JqMapperException e = assertThrows(JqMapperException.class,
                () -> mapper.fromJqValue(io.hyperfoil.tools.jjq.value.JqValues.parse("\"nope\""), AUTH_TYPE));
        assertTrue(e.getMessage().contains("Failed to invoke @JsonCreator"));
    }

    @Test
    void emittedBytesContainDirectCallsOnly() {
        String pool = new String(emit(), StandardCharsets.ISO_8859_1);
        for (String expected : List.of("toWire", "fromWire")) {
            assertTrue(pool.contains(expected), "emitted class should reference " + expected);
        }
        for (String banned : List.of(
                "java/lang/reflect/Method",
                "java/lang/reflect/Constructor",
                "getDeclaredConstructor",
                "Method.invoke",
                "Constructor.newInstance",
                "forName")) {
            assertFalse(pool.contains(banned), "emitted class must not reference " + banned);
        }
    }

    @Test
    void unsupportedShapesStayReflective() {
        // A creator is required: without one there is no reflective member
        // invocation to replace, so generation refuses and the caller keeps
        // the reflective fallback (same rule covers leniency, non-public
        // members, and generic creator arguments).
        assertThrows(IllegalArgumentException.class, () -> JjqProcessor.generateEnumBinding(
                (name, data) -> {
                },
                AUTH_TYPE,
                new TypeConverter.EnumHandlers(null, null, null, null, Map.of(), null)));
    }

    @Test
    void resolutionWithoutBridgesFindsNothing() {
        Optional<TypeConverter.EnumHandlers> resolved =
                TypeConverter.resolveEnumHandlers(AUTH_TYPE, List.of());
        assertTrue(resolved.isEmpty());
    }
}

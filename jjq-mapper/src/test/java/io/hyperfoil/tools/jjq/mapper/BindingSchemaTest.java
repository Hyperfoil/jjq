package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.value.JqNull;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for the build-time binding schema (issue #171).
 *
 * <p>The schema reuses the semantics already resolved by
 * {@code FieldMapping}, {@code TypeConverter} and the annotation
 * processor: wire names, required presence, primitive/boxed missing
 * behavior, aliases and unknown-property policy. It must never
 * weaken validation: unsupported mappings fall back, invalid
 * configurations keep failing.
 */
class BindingSchemaTest {

    private final JqMapper mapper = JqMapper.create();
    private final JqMapper strictMapper =
            JqMapper.builder().failOnUnknownProperties(true).build();

    record Flat(String name, int age, long score, boolean active,
                Integer optAge, Long optScore, Boolean optActive) {}

    @Test
    void flatRecordIsEligible() {
        BindingSchema schema = mapper.bindingSchemaFor(Flat.class);
        assertTrue(schema.eligibleForDirectReader(), schema.ineligibilityReasons().toString());
        assertEquals(Flat.class, schema.targetType());
        assertEquals(7, schema.fields().size());
        assertEquals(BindingSchema.Construction.RECORD, schema.construction());
    }

    @Test
    void flatFieldKindsAndPrimitiveFlags() {
        BindingSchema schema = mapper.bindingSchemaFor(Flat.class);
        assertEquals(TypeConverter.Kind.STRING, schema.field("name").kind());
        assertEquals(TypeConverter.Kind.INT, schema.field("age").kind());
        assertEquals(TypeConverter.Kind.LONG, schema.field("score").kind());
        assertEquals(TypeConverter.Kind.BOOLEAN, schema.field("active").kind());
        assertTrue(schema.field("age").primitive());
        assertFalse(schema.field("optAge").primitive());
        assertEquals(TypeConverter.Kind.INT, schema.field("optAge").kind());
    }

    @Test
    void missingBindsNullForBoxedAndZeroForPrimitive() {
        BindingSchema schema = mapper.bindingSchemaFor(Flat.class);
        assertEquals(BindingSchema.MissingPolicy.BIND_NULL,
                schema.field("optAge").missingPolicy());
        assertEquals(BindingSchema.MissingPolicy.BIND_PRIMITIVE_DEFAULT,
                schema.field("age").missingPolicy());
        // Behavior parity with the tree-based mapper.
        Flat r = mapper.fromJqValue(JqValues.parse("{\"name\":\"a\"}"), Flat.class);
        assertNull(r.optAge());
        assertEquals(0, r.age());
        assertFalse(r.active());
    }

    @JqNaming(JqNaming.Strategy.SNAKE_CASE)
    record Snake(String userName, int ageYears) {}

    record Renamed(@JqName("user_name") String userName, int age) {}

    @Test
    void namingStrategyAndExplicitRenameDriveWireNames() {
        BindingSchema snake = mapper.bindingSchemaFor(Snake.class);
        assertEquals("user_name", snake.fields().get(0).wireName());
        assertEquals("age_years", snake.fields().get(1).wireName());
        BindingSchema renamed = mapper.bindingSchemaFor(Renamed.class);
        assertEquals("user_name", renamed.fields().get(0).wireName());
        assertEquals("age", renamed.fields().get(1).wireName());
        // Behavior parity: renamed wire binds through the mapper.
        Renamed r = mapper.fromJqValue(
                JqValues.parse("{\"user_name\":\"a\",\"age\":3}"), Renamed.class);
        assertEquals(new Renamed("a", 3), r);
    }

    record RequiredBox(@JqRequired String name, int age) {}

    record RequiredPrim(@JqRequired int age, String name) {}

    @Test
    void requiredMeansPresentAndExplicitNullCountsAsPresent() {
        BindingSchema box = mapper.bindingSchemaFor(RequiredBox.class);
        assertEquals(List.of("name"), box.required().stream().toList());
        assertEquals(BindingSchema.MissingPolicy.FAIL, box.field("name").missingPolicy());
        // Missing fails, explicit null passes required and binds null.
        assertThrows(JqMapperException.class, () -> mapper.fromJqValue(
                JqValues.parse("{\"age\":1}"), RequiredBox.class));
        RequiredBox present = mapper.fromJqValue(
                JqValues.parse("{\"name\":null,\"age\":1}"), RequiredBox.class);
        assertNull(present.name());

        BindingSchema prim = mapper.bindingSchemaFor(RequiredPrim.class);
        assertEquals(List.of("age"), prim.required().stream().toList());
        RequiredPrim nullPrim = mapper.fromJqValue(
                JqValues.parse("{\"age\":null}"), RequiredPrim.class);
        assertEquals(0, nullPrim.age());
    }

    record Aliased(@JqAlias({"user_name", "userName"}) String name, int age) {}

    @Test
    void aliasPrecedenceIsIndependentOfInputOrder() {
        BindingSchema schema = mapper.bindingSchemaFor(Aliased.class);
        assertEquals(List.of("user_name", "userName"), schema.field("name").aliases());
        assertTrue(schema.knownKeys().contains("name"));
        assertTrue(schema.knownKeys().contains("user_name"));
        assertTrue(schema.knownKeys().contains("userName"));
        // Alias-only binds; primary wins regardless of document order.
        assertEquals("a", mapper.fromJqValue(
                JqValues.parse("{\"user_name\":\"a\",\"age\":1}"), Aliased.class).name());
        assertEquals("p", mapper.fromJqValue(JqValues.parse(
                "{\"user_name\":\"alias\",\"name\":\"p\",\"age\":1}"), Aliased.class).name());
        assertEquals("p", mapper.fromJqValue(JqValues.parse(
                "{\"name\":\"p\",\"user_name\":\"alias\",\"age\":1}"), Aliased.class).name());
    }

    @Test
    void acceptedCoercionsAreNotReplacedByStrictTypeAssertions() {
        // A number for a String field binds today; the schema must not
        // re-describe String fields as raw-JSON strings-only.
        Flat r = mapper.fromJqValue(
                JqValues.parse("{\"name\":42,\"age\":1,\"score\":2,\"active\":false}"),
                Flat.class);
        assertEquals("42", r.name());
        assertEquals(TypeConverter.Kind.STRING, mapper.bindingSchemaFor(Flat.class)
                .field("name").kind());
    }

    record Point(int x, int y) {}

    @Test
    void unknownPolicyFollowsMapperFlagAndKnownKeys() {
        BindingSchema lenient = mapper.bindingSchemaFor(Point.class);
        assertEquals(BindingSchema.UnknownPolicy.IGNORE_UNKNOWN, lenient.unknownPolicy());
        BindingSchema strict = strictMapper.bindingSchemaFor(Point.class);
        assertEquals(BindingSchema.UnknownPolicy.FAIL_ON_UNKNOWN, strict.unknownPolicy());
        assertEquals(lenient.knownKeys(), strict.knownKeys());
        assertTrue(strict.knownKeys().containsAll(List.of("x", "y")));
    }

    static class Extras {
        public String name;
        private final Map<String, JqValue> extras = new java.util.LinkedHashMap<>();

        public Map<String, JqValue> getExtras() { return extras; }

        @JqAnySetter
        public void setExtra(String key, JqValue value) { extras.put(key, value); }
    }

    @Test
    void anySetterForwardsUnknowns() {
        BindingSchema schema = mapper.bindingSchemaFor(Extras.class);
        assertEquals(BindingSchema.UnknownPolicy.FORWARD_TO_ANY_SETTER, schema.unknownPolicy());
        assertFalse(schema.eligibleForDirectReader());
        assertTrue(schema.ineligibilityReasons().stream().anyMatch(s -> s.contains("any-setter")),
                schema.ineligibilityReasons().toString());
    }

    record StringList(List<String> tags) {}

    record IntList(List<Integer> counts) {}

    @Test
    void genericFieldTypesAreRetained() {
        BindingSchema strings = mapper.bindingSchemaFor(StringList.class);
        BindingSchema ints = mapper.bindingSchemaFor(IntList.class);
        Type stringArg = strings.field("tags").genericType();
        Type intArg = ints.field("counts").genericType();
        assertNotEquals(stringArg.getTypeName(), intArg.getTypeName());
        // Collections are valid mappings but not MVP direct-reader targets.
        assertFalse(strings.eligibleForDirectReader());
        assertTrue(strings.ineligibilityReasons().stream().anyMatch(s -> s.contains("tags")),
                strings.ineligibilityReasons().toString());
    }

    record Nested(Point point, String label) {}

    record WithProgram(@JqField(".a.b") String deep, String label) {}

    public static class UpperConverter implements ValueConverter<String> {
        @Override
        public String fromJqValue(JqValue value) {
            return value.isNull() ? null : value.stringValue().toUpperCase();
        }

        @Override
        public JqValue toJqValue(String value) {
            return value == null ? JqNull.NULL
                    : io.hyperfoil.tools.jjq.value.JqString.of(value);
        }
    }

    record Converted(@JqConverter(UpperConverter.class) String name) {}

    static class Pojo {
        public String name;
        public int age;
    }

    record Delegated(String value) {
        @JqCreator
        public static Delegated parse(String value) {
            return new Delegated(value);
        }
    }

    @JqId("id")
    record Identified(String id, String name) {}

    record Ignored(@JqIgnore String skip, String keep) {}

    @Test
    void unsupportedMappingsFallBackWithReasons() {
        assertIneligibleWithReason(Nested.class, "point");
        assertIneligibleWithReason(WithProgram.class, "deep");
        assertIneligibleWithReason(Converted.class, "name");
        assertIneligibleWithReason(Pojo.class, "POJO");
        assertIneligibleWithReason(Delegated.class, "creator");
        assertIneligibleWithReason(Identified.class, "identity");
        assertIneligibleWithReason(Extras.class, "any-setter");
        assertIneligibleWithReason(StringList.class, "tags");
    }

    private void assertIneligibleWithReason(Class<?> type, String fragment) {
        BindingSchema schema = mapper.bindingSchemaFor(type);
        assertFalse(schema.eligibleForDirectReader(), type.getSimpleName());
        assertFalse(schema.ineligibilityReasons().isEmpty(), type.getSimpleName());
        assertTrue(schema.ineligibilityReasons().stream().anyMatch(s -> s.contains(fragment)),
                type.getSimpleName() + ": " + schema.ineligibilityReasons());
    }

    @Test
    void ignoredFieldsStayOutOfKnownKeysButKeepEligibility() {
        BindingSchema schema = mapper.bindingSchemaFor(Ignored.class);
        assertTrue(schema.eligibleForDirectReader(), schema.ineligibilityReasons().toString());
        assertTrue(schema.knownKeys().contains("keep"));
        assertFalse(schema.knownKeys().contains("skip"));
        assertTrue(schema.required().isEmpty());
    }

    record Conflict(@JqName("a") @JqField(".a") String x) {}

    @Test
    void invalidMappingConfigurationStillFails() {
        assertThrows(JqMapperException.class, () -> mapper.bindingSchemaFor(Conflict.class));
    }

    record TwoRequired(@JqRequired String first, int middle, @JqRequired long last) {}

    @Test
    void requiredKeepsDeclarationOrder() {
        BindingSchema schema = mapper.bindingSchemaFor(TwoRequired.class);
        assertEquals(List.of("first", "last"), List.copyOf(schema.required()));
        // First-missing-in-declaration-order matches checkRequired exactly.
        JqMapperException e = assertThrows(JqMapperException.class, () -> mapper.fromJqValue(
                JqValues.parse("{\"middle\":1}"), TwoRequired.class));
        assertTrue(e.getMessage().contains("\"first\""), e.getMessage());
    }

    @Test
    void schemaIsImmutableAndReusable() {        BindingSchema schema = mapper.bindingSchemaFor(Flat.class);
        assertThrows(UnsupportedOperationException.class, () -> schema.fields().add(null));
        assertThrows(UnsupportedOperationException.class, () -> schema.required().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> schema.knownKeys().add("x"));
        assertThrows(UnsupportedOperationException.class,
                () -> schema.ineligibilityReasons().add("x"));
        // No per-request state: a second compilation agrees.
        assertEquals(schema.fields(), mapper.bindingSchemaFor(Flat.class).fields());
        assertEquals(schema.required(), mapper.bindingSchemaFor(Flat.class).required());
    }
}

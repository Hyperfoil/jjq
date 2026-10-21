package io.hyperfoil.tools.jjq.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jboss.jandex.Index;
import org.junit.jupiter.api.Test;

import io.hyperfoil.tools.jjq.mapper.BindingSchema;
import io.hyperfoil.tools.jjq.mapper.DirectReadFallbackException;
import io.hyperfoil.tools.jjq.mapper.DirectReader;
import io.hyperfoil.tools.jjq.mapper.JqMapped;
import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.mapper.JqMapperException;
import io.hyperfoil.tools.jjq.value.JqValues;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;

/**
 * Bytecode generation tests for direct byte-to-record readers (issue #173).
 *
 * <p>Generated readers are loaded into the test runtime package (the same
 * colocation the augmentation uses) and driven through the cursor protocol
 * exactly as #174 will: canonical inputs must construct through the
 * specialized route, everything else must fall back with today's behavior
 * intact. Differential oracle is strict parse plus the annotation-processor
 * generated mapping, so both sides share the same binding rules.
 */
class DirectReaderGenerationTest {

    @JqMapped
    record FlatOrder(String name, int age, long score, boolean active, Integer optAge) {}

    @JqMapped
    record Renamed(@io.hyperfoil.tools.jjq.mapper.JqName("user_name") String userName, int age) {}

    @JqMapped
    record Required(@io.hyperfoil.tools.jjq.mapper.JqRequired String name,
            @io.hyperfoil.tools.jjq.mapper.JqRequired int age) {}

    @JqMapped
    record Watched(String name) {
        static int constructions;

        Watched {
            constructions++;
        }
    }

    @JqMapped
    record Point(int x, int y) {}

    @JqMapped
    record Nested(Point point, String label) {}

    @JqMapped
    record WithAlias(@io.hyperfoil.tools.jjq.mapper.JqAlias("n") String name) {}

    @JqMapped
    record WithProgram(@io.hyperfoil.tools.jjq.mapper.JqField(".a") String a) {}

    @JqMapped
    record Delegated(String value) {
        @io.hyperfoil.tools.jjq.mapper.JqCreator
        public static Delegated parse(String value) {
            return new Delegated(value);
        }
    }

    private record Hidden(String a) {}

    static class Pojo {
        public String name;
    }

    @JqMapped
    record Wide20(String s00, int i01, long l02, boolean b03, Integer s04,
            String s05, int i06, long l07, boolean b08, Long s09,
            String s10, int i11, long l12, boolean b13, Boolean s14,
            String s15, int i16, long l17, boolean b18, String s19) {}

    @JqMapped
    record Wide65(int f00, int f01, int f02, int f03, int f04, int f05, int f06, int f07,
            int f08, int f09, int f10, int f11, int f12, int f13, int f14, int f15,
            int f16, int f17, int f18, int f19, int f20, int f21, int f22, int f23,
            int f24, int f25, int f26, int f27, int f28, int f29, int f30, int f31,
            int f32, int f33, int f34, int f35, int f36, int f37, int f38, int f39,
            int f40, int f41, int f42, int f43, int f44, int f45, int f46, int f47,
            int f48, int f49, int f50, int f51, int f52, int f53, int f54, int f55,
            int f56, int f57, int f58, int f59, int f60, int f61, int f62, int f63,
            int f64) {}

    static class Endpoints {
        @POST
        public String create(FlatOrder in) {
            return "ok";
        }

        @PUT
        public String update(Renamed in) {
            return "ok";
        }

        @PATCH
        public String patch(Nested in, String extra) {
            return "ok";
        }

        @GET
        public String get() {
            return "ok";
        }

        public String helper(FlatOrder in) {
            return "ok";
        }
    }

    private final JqMapper oracle = JqMapper.create();

    private static final Map<Class<?>, DirectReader<?>> READERS = new LinkedHashMap<>();

    static DirectReader<?> loadReader(Class<?> type) throws Throwable {
        DirectReader<?> cached = READERS.get(type);
        if (cached != null) return cached;
        BindingSchema schema = JqMapper.create().bindingSchemaFor(type);
        assertTrue(schema.eligibleForDirectReader(), schema.ineligibilityReasons().toString());
        Map<String, byte[]> classes = new LinkedHashMap<>();
        JjqProcessor.generateDirectReader((name, data) -> classes.put(name, data), type, schema);
        String readerBinary = JjqProcessor.directReaderClassNameFor(type.getName());
        assertEquals(List.of(readerBinary.replace('.', '/') + ".class"), List.copyOf(classes.keySet()));
        byte[] bytes = classes.values().iterator().next();
        Class<?> readerClass = MethodhandlesLookupHolder.lookup()
                .defineClass(bytes);
        assertEquals(readerBinary, readerClass.getName());
        Object reader = readerClass.getDeclaredConstructor().newInstance();
        assertTrue(reader instanceof DirectReader);
        assertEquals(type, ((DirectReader<?>) reader).type());
        READERS.put(type, (DirectReader<?>) reader);
        return (DirectReader<?>) reader;
    }

    /** Defines generated classes in the test runtime package (production colocation). */
    static final class MethodhandlesLookupHolder {
        static java.lang.invoke.MethodHandles.Lookup lookup() throws IllegalAccessException {
            return MethodHandles.privateLookupIn(DirectReaderGenerationTest.class,
                    MethodHandles.lookup());
        }
    }

    /** Read-or-fallback driver mirroring the #174 dispatch (default mapper config). */
    record Outcome(Object value, boolean fellBack) {}

    Outcome drive(DirectReader<?> reader, String doc) {
        byte[] bytes = doc.getBytes(StandardCharsets.UTF_8);
        try {
            @SuppressWarnings("unchecked")
            Object result = ((DirectReader<Object>) reader).read(bytes, oracle);
            return new Outcome(result, false);
        } catch (DirectReadFallbackException e) {
            try {
                return new Outcome(oracle.fromJqValue(JqValues.parseStrict(bytes), reader.type()), true);
            } catch (RuntimeException ex) {
                return new Outcome(ex.getClass().getName() + ": " + ex.getMessage(), true);
            }
        }
    }

    /**
     * Curated canonical inputs must complete through the specialized route:
     * a fallback here fails the test (this is what keeps the suite from
     * silently measuring the existing path).
     */
    Object readDirect(DirectReader<?> reader, String doc) {
        Outcome outcome = drive(reader, doc);
        assertFalse(outcome.fellBack(), "specialized route fell back for " + doc);
        return outcome.value();
    }

    /**
     * Non-canonical inputs must fall back (never complete, never throw
     * anything but the fallback signal or an oracle-identical error).
     */
    Outcome assertFallback(DirectReader<?> reader, String doc) {
        Outcome outcome = drive(reader, doc);
        assertTrue(outcome.fellBack(), "specialized route completed for " + doc);
        return outcome;
    }

    Object readOrFallback(DirectReader<?> reader, String doc) {
        return drive(reader, doc).value();
    }

    @Test
    void generatedMappingOracleIsStrong() throws Throwable {
        // The annotation processor must have emitted real mappings for the
        // fixtures, or the differential oracle silently weakens to reflection.
        assertNotNull(Class.forName(
                JjqProcessor.mappingClassNameFor(FlatOrder.class.getName())));
    }

    @Test
    void flatRecordCompletesOrderedAndReordered() throws Throwable {
        DirectReader<?> reader = loadReader(FlatOrder.class);
        assertEquals(new FlatOrder("ann", 30, 100L, true, 7),
                readDirect(reader, "{\"name\":\"ann\",\"age\":30,\"score\":100,\"active\":true,\"optAge\":7}"));
        assertEquals(new FlatOrder("ann", 30, 100L, true, 7),
                readDirect(reader, "{\"optAge\":7,\"active\":true,\"score\":100,\"age\":30,\"name\":\"ann\"}"));
        assertEquals(new FlatOrder("a", 0, 0L, false, null),
                readDirect(reader, "{\"name\":\"a\"}"));
        assertEquals(new FlatOrder(null, 0, 0L, false, null),
                readDirect(reader, "{}"));
    }

    @Test
    void nullsAndDefaultsMatchBinding() throws Throwable {
        DirectReader<?> reader = loadReader(FlatOrder.class);
        assertEquals(new FlatOrder(null, 5, 0L, false, null),
                readDirect(reader, "{\"name\":null,\"age\":5,\"score\":null,\"active\":null}"));
    }

    @Test
    void renamesBindByWireName() throws Throwable {
        DirectReader<?> reader = loadReader(Renamed.class);
        assertEquals(new Renamed("ann", 3),
                readDirect(reader, "{\"user_name\":\"ann\",\"age\":3}"));
        // The Java name alone is unknown on the fast path; the existing path
        // reports it the same way (ignored by the default mapper).
        Outcome outcome = assertFallback(reader, "{\"userName\":\"ann\",\"age\":3}");
        assertEquals(new Renamed(null, 3), outcome.value());
    }

    @Test
    void requiredReportsFirstMissingExactly() throws Throwable {
        DirectReader<?> reader = loadReader(Required.class);
        assertEquals(new Required("a", 1), readDirect(reader, "{\"name\":\"a\",\"age\":1}"));
        // The reader reports with the reflection naming convention
        // (Class.getSimpleName); top-level DTOs agree under every convention.
        JqMapperException e = assertThrows(JqMapperException.class,
                () -> readDirect(reader, "{\"age\":1}"));
        assertEquals("Missing required field \"name\" for Required at '$.name'", e.getMessage());
        assertEquals(List.of("name"), e.pathSegments());
    }

    @Test
    void syntaxErrorsWinOverBindingErrors() throws Throwable {
        DirectReader<?> reader = loadReader(Required.class);
        // Missing required field plus trailing garbage: the reader must fall
        // back (never report the binding error), and the existing path
        // reports the syntax error first — exactly as without the reader.
        Object oracleError;
        try {
            oracle.fromJqValue(JqValues.parseStrict(bytes("{} trailing")), Required.class);
            oracleError = null;
        } catch (RuntimeException ex) {
            oracleError = ex.getClass().getName() + ": " + ex.getMessage();
        }
        assertNotNull(oracleError);
        Outcome outcome = assertFallback(reader, "{} trailing");
        assertEquals(oracleError, outcome.value());
    }

    static byte[] bytes(String doc) {
        return doc.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void constructorNeverRunsBeforeValidation() throws Throwable {
        DirectReader<?> reader = loadReader(Watched.class);
        Watched.constructions = 0;
        Object first = readOrFallback(reader, "{\"name\":\"a\"}");
        assertEquals(1, Watched.constructions);
        assertEquals("a", ((Watched) first).name());
        Watched.constructions = 0;
        // Coercion fallback: the constructor must not run before the retry.
        Object second = readOrFallback(reader, "{\"name\":42}");
        assertEquals(1, Watched.constructions);
        assertEquals("42", ((Watched) second).name());
        Watched.constructions = 0;
        Outcome malformed = assertFallback(reader, "{\"name\":");
        assertEquals(0, Watched.constructions);
        assertTrue(((String) malformed.value()).startsWith(
                IllegalArgumentException.class.getName()), (String) malformed.value());
    }

    @Test
    void canonicalValuesCompleteThroughTheSpecializedRoute() throws Throwable {
        DirectReader<?> reader = loadReader(FlatOrder.class);
        assertEquals(new FlatOrder("\u00e9\ud83d\ude00", -1, -9223372036854775808L, true, null),
                readDirect(reader,
                        "{\"name\":\"\\u00e9\\ud83d\\ude00\",\"age\":-1,\"score\":-9223372036854775808,\"active\":true}"));
        assertEquals(new FlatOrder("a", 2147483647, 9223372036854775807L, false, null),
                readDirect(reader,
                        "{\"name\":\"a\",\"age\":2147483647,\"score\":9223372036854775807,\"active\":false}"));
        assertEquals(new FlatOrder("a", 1, 2L, false, null),
                readDirect(reader, "{\"name\":\"a\",\"age\":1,\"score\":2}"));
    }

    @Test
    void coercionsAndUnknownsFallBackWithIdenticalOutcomes() throws Throwable {
        DirectReader<?> reader = loadReader(FlatOrder.class);
        // Each entry: the reader must fall back, and the fallback outcome
        // must equal the oracle outcome (strict parse plus the generated
        // mapping), whether that is a bound value or an error rendering.
        String[] docs = {
            "{\"name\":42,\"age\":1,\"score\":2,\"active\":false}",
            "{\"name\":\"a\",\"age\":\"42\",\"score\":2,\"active\":false}",
            "{\"name\":\"a\",\"age\":1,\"score\":2,\"active\":1}",
            "{\"name\":\"a\",\"age\":1.5,\"score\":2,\"active\":false}",
            "{\"name\":\"a\",\"age\":1,\"score\":2,\"active\":false,\"extra\":1}",
            "{\"name\":\"a\",\"age\":[1],\"score\":2,\"active\":false}",
            "{\"name\":\"a\",\"age\":{\"n\":1},\"score\":2,\"active\":false}",
            "{\"name\":\"a\",\"age\":1,\"score\":2,\"active\":false,\"age\":3}",
            "{\"name\":\"a\",\"age\":2147483648,\"score\":2,\"active\":false}",
            "{\"name\":\"a\",\"age\":1,\"score\":9223372036854775808,\"active\":false}",
            "{\"name\":\"a\",\"age\":007,\"score\":2,\"active\":false}",
            "{\"name\":\"a\",\"age\":1,\"score\":2,\"active\":tru}",
            "{\"name\":\"a\" \"age\":1}",
            "{\"name\":\"a\",}",
            "{,\"name\":\"a\"}",
            "{\"name\":\"a\"} trailing",
            "{\"n\\u0061me\":\"a\"}",
            "[]",
            "{\"name\":\"unterminated}",
        };
        for (String doc : docs) {
            Object expected;
            try {
                expected = oracle.fromJqValue(JqValues.parseStrict(bytes(doc)), FlatOrder.class);
            } catch (RuntimeException e) {
                expected = e.getClass().getName() + ": " + e.getMessage();
            }
            Outcome outcome = assertFallback(reader, doc);
            assertEquals(expected, outcome.value(), "fallback outcome mismatch for " + doc);
        }
    }

    @Test
    void strictUnknownPolicyFallsBackIdentically() {
        BindingSchema schema = JqMapper.builder().failOnUnknownProperties(true).build()
                .bindingSchemaFor(FlatOrder.class);
        assertTrue(schema.eligibleForDirectReader());
        // Unknown keys never complete; the strict mapper then reports them.
        JqMapper strict = JqMapper.builder().failOnUnknownProperties(true).build();
        JqMapperException e = assertThrows(JqMapperException.class, () -> strict.fromJqValue(
                JqValues.parseStrict(bytes("{\"name\":\"a\",\"bogus\":1}")), FlatOrder.class));
        assertTrue(e.getMessage().contains("\"bogus\""), e.getMessage());
    }

    @Test
    void unsupportedShapesKeepTheExistingPath() {
        assertSkip(Nested.class, "point");
        assertSkip(WithAlias.class, "alias");
        assertSkip(WithProgram.class, "@JqField");
        assertSkip(Delegated.class, "creator");
        assertSkip(Pojo.class, "records");
        assertSkip(Hidden.class, "private");
        assertSkip(Wide65.class, "64");
    }

    static void assertSkip(Class<?> type, String fragment) {
        BindingSchema schema = JqMapper.create().bindingSchemaFor(type);
        Map<String, byte[]> classes = new LinkedHashMap<>();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> JjqProcessor.generateDirectReader((name, data) -> classes.put(name, data),
                        type, schema),
                type.getSimpleName());
        assertTrue(e.getMessage().contains(fragment),
                type.getSimpleName() + ": " + e.getMessage());
        assertTrue(classes.isEmpty(), type.getSimpleName());
    }

    @Test
    void requestBodyDiscoveryFindsMutatingParams() throws Exception {
        var index = Index.of(Endpoints.class);
        var mapped = java.util.Set.of(FlatOrder.class.getName(), Renamed.class.getName(),
                Nested.class.getName());
        var found = JjqProcessor.requestBodyClassNames(index, mapped);
        assertEquals(java.util.Set.of(FlatOrder.class.getName(), Renamed.class.getName(),
                Nested.class.getName()), found);
    }

    @Test
    void generatedReaderUsesNoReflection() throws Throwable {
        // FlatOrder covers the value readers; Required covers the
        // required-field failure construction.
        for (Class<?> type : List.of(FlatOrder.class, Required.class)) {
            BindingSchema schema = JqMapper.create().bindingSchemaFor(type);
            Map<String, byte[]> classes = new LinkedHashMap<>();
            JjqProcessor.generateDirectReader((name, data) -> classes.put(name, data),
                    type, schema);
            String pool = new String(classes.values().iterator().next(), StandardCharsets.ISO_8859_1);
            for (String expected : List.of(
                    "DirectReader", "JqCursor", "strictCursor", "fieldIndex",
                    "DirectReadFallbackException", "readString", "readInt")) {
                assertTrue(pool.contains(expected),
                        type.getSimpleName() + ": emitted class should reference " + expected);
            }
            if (type == FlatOrder.class) {
                String flatPool = new String(classes.values().iterator().next(),
                        StandardCharsets.ISO_8859_1);
                assertTrue(flatPool.contains("readLong"), "long fields need readLong");
                assertTrue(flatPool.contains("readBoolean"), "boolean fields need readBoolean");
            }
            for (String banned : List.of(
                    "forName",
                    "getDeclaredConstructor",
                    "newInstance",
                    "getAnnotation",
                    "MethodHandle",
                    "invokeWithArguments")) {
                assertFalse(pool.contains(banned),
                        type.getSimpleName() + ": emitted class must not reference " + banned);
            }
        }
        assertRequiredMessage();
    }

    static void assertRequiredMessage() {
        BindingSchema schema = JqMapper.create().bindingSchemaFor(Required.class);
        Map<String, byte[]> classes = new LinkedHashMap<>();
        JjqProcessor.generateDirectReader((name, data) -> classes.put(name, data),
                Required.class, schema);
        String pool = new String(classes.values().iterator().next(), StandardCharsets.ISO_8859_1);
        assertTrue(pool.contains("Missing required field"), "required check must be emitted");
    }

    @Test
    void generatedSizesStayBounded() throws Throwable {
        assertBounded(FlatOrder.class, 6 * 1024);
        assertBounded(Wide20.class, 16 * 1024);
    }

    static void assertBounded(Class<?> type, int max) throws Throwable {
        BindingSchema schema = JqMapper.create().bindingSchemaFor(type);
        assertTrue(schema.eligibleForDirectReader(), type.getSimpleName());
        Map<String, byte[]> classes = new LinkedHashMap<>();
        JjqProcessor.generateDirectReader((name, data) -> classes.put(name, data), type, schema);
        int size = classes.values().iterator().next().length;
        assertTrue(size < max, type.getSimpleName() + " reader is " + size + " bytes (limit " + max + ")");
    }
}

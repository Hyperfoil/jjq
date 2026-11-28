package io.hyperfoil.tools.jjq.quarkus.deployment;

import io.hyperfoil.tools.jjq.mapper.BindingSchema;
import io.hyperfoil.tools.jjq.mapper.DirectReader;
import io.hyperfoil.tools.jjq.mapper.DirectReadFallbackException;
import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bytecode generation tests for flat-POJO direct readers (issue #4):
 * openapi-generator and JavaBean models construct through a no-arg
 * constructor plus setters or field writes, and must decode through the
 * same cursor path as record readers with identical fallback behavior.
 */
class DirectReaderPojoTest {

    /** openapi-generator shape: private fields, public getters/setters. */
    static class ApiHero {
        private Long id;
        private String name;
        private String otherName;
        private Integer level;
        private String picture;
        private String powers;

        public ApiHero() {}

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getOtherName() { return otherName; }
        public void setOtherName(String otherName) { this.otherName = otherName; }
        public Integer getLevel() { return level; }
        public void setLevel(Integer level) { this.level = level; }
        public String getPicture() { return picture; }
        public void setPicture(String picture) { this.picture = picture; }
        public String getPowers() { return powers; }
        public void setPowers(String powers) { this.powers = powers; }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ApiHero h)) return false;
            return Objects.equals(id, h.id) && Objects.equals(name, h.name)
                    && Objects.equals(otherName, h.otherName) && Objects.equals(level, h.level)
                    && Objects.equals(picture, h.picture) && Objects.equals(powers, h.powers);
        }

        @Override
        public int hashCode() { return Objects.hash(id, name, otherName, level, picture, powers); }
    }

    /** Public-field shape: direct field stores, no setters at all. */
    static class FieldPojo {
        public String name;
        public int age;
        public long score;
        public boolean active;

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof FieldPojo f)) return false;
            return age == f.age && score == f.score && active == f.active
                    && Objects.equals(name, f.name);
        }

        @Override
        public int hashCode() { return Objects.hash(name, age, score, active); }
    }

    /** Mixed shape: primitive via setter, boxed via field, absent binding. */
    static class MixedPojo {
        public Integer boxed;
        private int primitive;

        public MixedPojo() {}

        public int getPrimitive() { return primitive; }
        public void setPrimitive(int primitive) { this.primitive = primitive; }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof MixedPojo m)) return false;
            return primitive == m.primitive && Objects.equals(boxed, m.boxed);
        }

        @Override
        public int hashCode() { return Objects.hash(primitive, boxed); }
    }

    /** Package-private no-arg constructor: the colocated reader can invoke it. */
    static class PackageCtorPojo {
        public String name;

        PackageCtorPojo() {}

        @Override
        public boolean equals(Object o) {
            return o instanceof PackageCtorPojo p && Objects.equals(name, p.name);
        }

        @Override
        public int hashCode() { return Objects.hash(name); }
    }

    /** Fluent setters returning this: return value must be discarded. */
    static class FluentPojo {
        private String name;
        private int age;

        public FluentPojo() {}

        public FluentPojo setName(String name) { this.name = name; return this; }
        public FluentPojo setAge(int age) { this.age = age; return this; }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof FluentPojo f)) return false;
            return age == f.age && Objects.equals(name, f.name);
        }

        @Override
        public int hashCode() { return Objects.hash(name, age); }
    }

    /** Private no-arg constructor: stays on the reflective path. */
    static class PrivateCtorPojo {
        public String name;

        private PrivateCtorPojo() {}
    }

    /** Getter-only property: nothing writable for the generated reader. */
    static class ReadOnlyPojo {
        public String other;
        private String derived;

        public ReadOnlyPojo() {}

        public String getDerived() { return derived; }
    }

    private final JqMapper oracle = JqMapper.create();

    private static final Map<Class<?>, DirectReader<?>> READERS = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    DirectReader<Object> reader(Class<?> type) throws Throwable {
        DirectReader<?> cached = READERS.get(type);
        if (cached != null) return (DirectReader<Object>) cached;
        BindingSchema schema = oracle.bindingSchemaFor(type);
        assertTrue(schema.eligibleForDirectReader(),
                type.getSimpleName() + ": " + schema.ineligibilityReasons());
        Map<String, byte[]> classes = new LinkedHashMap<>();
        JjqProcessor.generateDirectReader((name, data) -> classes.put(name, data), type, schema);
        Class<?> readerClass = DirectReaderGenerationTest.MethodhandlesLookupHolder.lookup()
                .defineClass(classes.values().iterator().next());
        DirectReader<?> reader = (DirectReader<?>) readerClass.getDeclaredConstructor().newInstance();
        READERS.put(type, reader);
        return (DirectReader<Object>) reader;
    }

    record Outcome(Object value, boolean fellBack) {}

    Outcome drive(DirectReader<Object> reader, String doc) {
        byte[] bytes = doc.getBytes(StandardCharsets.UTF_8);
        try {
            return new Outcome(reader.read(bytes, oracle), false);
        } catch (DirectReadFallbackException e) {
            try {
                return new Outcome(oracle.fromJqValue(JqValues.parseStrict(bytes), reader.type()), true);
            } catch (RuntimeException ex) {
                return new Outcome(ex.getClass().getName() + ": " + ex.getMessage(), true);
            }
        }
    }

    Object readDirect(DirectReader<Object> reader, String doc) {
        Outcome outcome = drive(reader, doc);
        assertFalse(outcome.fellBack(), "specialized route fell back for " + doc);
        return outcome.value();
    }

    @Test
    void apiHeroShapeDecodesDirectly() throws Throwable {
        DirectReader<Object> reader = reader(ApiHero.class);
        String doc = "{\"id\":7,\"name\":\"Chewbacca\",\"otherName\":\"\","
                + "\"level\":30,\"picture\":\"https://x/p.jpg\",\"powers\":\"Strength\"}";
        ApiHero hero = (ApiHero) readDirect(reader, doc);
        ApiHero expected = new ApiHero();
        expected.setId(7L);
        expected.setName("Chewbacca");
        expected.setOtherName("");
        expected.setLevel(30);
        expected.setPicture("https://x/p.jpg");
        expected.setPowers("Strength");
        assertEquals(expected, hero);
    }

    @Test
    void publicFieldPojoDecodesDirectly() throws Throwable {
        DirectReader<Object> reader = reader(FieldPojo.class);
        FieldPojo pojo = (FieldPojo) readDirect(reader,
                "{\"name\":\"ann\",\"age\":41,\"score\":9876543210,\"active\":true}");
        assertEquals("ann", pojo.name);
        assertEquals(41, pojo.age);
    }

    @Test
    void mixedPojoBindsDefaultsAndNulls() throws Throwable {
        DirectReader<Object> reader = reader(MixedPojo.class);
        MixedPojo absent = (MixedPojo) readDirect(reader, "{\"primitive\":5}");
        assertEquals(5, absent.getPrimitive());
        assertNull(absent.boxed);
        MixedPojo explicitNull = (MixedPojo) readDirect(reader,
                "{\"primitive\":1,\"boxed\":null}");
        assertEquals(1, explicitNull.getPrimitive());
        assertNull(explicitNull.boxed);
        MixedPojo full = (MixedPojo) readDirect(reader, "{\"primitive\":2,\"boxed\":9}");
        assertEquals(2, full.getPrimitive());
        assertEquals(9, full.boxed);
    }

    @Test
    void packageConstructorAndFluentSettersDecode() throws Throwable {
        Object pkg = readDirect(reader(PackageCtorPojo.class), "{\"name\":\"x\"}");
        PackageCtorPojo expectedPkg = new PackageCtorPojo();
        expectedPkg.name = "x";
        assertEquals(expectedPkg, pkg);
        Object fluent = readDirect(reader(FluentPojo.class), "{\"name\":\"y\",\"age\":3}");
        assertEquals(new FluentPojo().setName("y").setAge(3), fluent);
    }

    @Test
    void fallbackShapesMatchTheTreePath() throws Throwable {
        DirectReader<Object> reader = reader(ApiHero.class);
        for (String doc : new String[] {
                "{\"id\":1,\"name\":\"a\"}",                     // absent optionals
                "{\"unknown\":1,\"name\":\"a\"}",                // unknown key: fallback
                "{\"id\":1,\"id\":2}",                           // duplicate: fallback
                "{\"id\":1.5}",                                  // coercion: fallback
                "  {\"id\"  :  1  ,  \"name\":\"spaced\"}",      // whitespace is fine
        }) {
            Outcome outcome = drive(reader, doc);
            Outcome treeOutcome = treeOnly(reader.type(), doc);
            assertEquals(treeOutcome.value(), outcome.value(), doc);
            if (doc.contains("unknown") || doc.contains("1.5") || doc.contains("\"id\":2")) {
                assertTrue(outcome.fellBack(), doc);
            }
        }
    }

    @Test
    void malformedInputBehavesIdentically() throws Throwable {
        DirectReader<Object> reader = reader(ApiHero.class);
        for (String doc : new String[] {
                "{\"name\":\"a\"",   // truncated
                "{\"name\"}",        // missing colon
                "{\"name\":}",       // missing value
                "",                  // empty
        }) {
            Outcome direct = drive(reader, doc);
            Outcome tree = treeOnly(reader.type(), doc);
            assertEquals(tree.value(), direct.value(), doc);
        }
    }

    /** The tree path alone, as the fallback would run it. */
    Outcome treeOnly(Class<?> type, String doc) {
        byte[] bytes = doc.getBytes(StandardCharsets.UTF_8);
        try {
            return new Outcome(oracle.fromJqValue(JqValues.parseStrict(bytes), type), false);
        } catch (RuntimeException ex) {
            return new Outcome(ex.getClass().getName() + ": " + ex.getMessage(), true);
        }
    }

    @Test
    void eligibilityReasonsNameTheBlockingMember() {
        List<String> reasons = oracle.bindingSchemaFor(PrivateCtorPojo.class).ineligibilityReasons();
        assertEquals(1, reasons.size());
        assertTrue(reasons.get(0).contains("private no-arg constructor"), reasons.toString());

        List<String> readOnly = oracle.bindingSchemaFor(ReadOnlyPojo.class).ineligibilityReasons();
        assertEquals(1, readOnly.size());
        assertTrue(readOnly.get(0).contains("derived"), readOnly.toString());
    }

    @Test
    void recordsStillGenerateUnchanged() throws Throwable {
        // The record path must be untouched by the POJO extension.
        record Point(int x, int y) {}
        Set<String> names = new LinkedHashSet<>();
        BindingSchema schema = oracle.bindingSchemaFor(Point.class);
        assertTrue(schema.eligibleForDirectReader());
        JjqProcessor.generateDirectReader((name, data) -> names.add(name), Point.class, schema);
        assertEquals(Set.of(JjqProcessor.directReaderClassNameFor(Point.class.getName())
                .replace('.', '/') + ".class"), names);
    }
}

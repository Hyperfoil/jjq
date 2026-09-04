package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.value.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JqMapperTest {

    private final JqMapper mapper = JqMapper.create();

    // ---- Simple records ----

    record SimpleRecord(String name, int age, boolean active) {}

    @Test
    void fromJqValue_simpleRecord() {
        JqValue json = JqValues.parse("{\"name\":\"Alice\",\"age\":30,\"active\":true}");
        SimpleRecord r = mapper.fromJqValue(json, SimpleRecord.class);
        assertEquals("Alice", r.name());
        assertEquals(30, r.age());
        assertTrue(r.active());
    }

    @Test
    void toJqValue_simpleRecord() {
        JqValue result = mapper.toJqValue(new SimpleRecord("Bob", 25, false));
        assertInstanceOf(JqObject.class, result);
        assertEquals("Bob", result.getField("name").stringValue());
        assertEquals(25L, result.getField("age").longValue());
        assertFalse(result.getField("active").booleanValue());
    }

    @Test
    void roundTrip_simpleRecord() {
        SimpleRecord original = new SimpleRecord("Alice", 30, true);
        JqValue json = mapper.toJqValue(original);
        SimpleRecord restored = mapper.fromJqValue(json, SimpleRecord.class);
        assertEquals(original, restored);
    }

    // ---- Numeric types ----

    record NumericRecord(int i, long l, double d, float f, short s, byte b, BigDecimal bd) {}

    @Test
    void fromJqValue_numericTypes() {
        JqValue json = JqValues.parse("{\"i\":42,\"l\":9999999999,\"d\":3.14,\"f\":2.5,\"s\":100,\"b\":7,\"bd\":3.14}");
        NumericRecord r = mapper.fromJqValue(json, NumericRecord.class);
        assertEquals(42, r.i());
        assertEquals(9999999999L, r.l());
        assertEquals(3.14, r.d(), 0.001);
        assertEquals(2.5f, r.f(), 0.01f);
        assertEquals((short) 100, r.s());
        assertEquals((byte) 7, r.b());
        assertEquals(3.14, r.bd().doubleValue(), 0.001);
    }

    @Test
    void roundTrip_numericTypes() {
        NumericRecord original = new NumericRecord(42, 9999999999L, 3.14, 2.5f, (short) 100, (byte) 7, new BigDecimal("3.14"));
        JqValue json = mapper.toJqValue(original);
        NumericRecord restored = mapper.fromJqValue(json, NumericRecord.class);
        assertEquals(original.i(), restored.i());
        assertEquals(original.l(), restored.l());
        assertEquals(original.d(), restored.d(), 0.001);
        assertEquals(original.bd().doubleValue(), restored.bd().doubleValue(), 0.001);
    }

    // ---- Nested records ----

    record Address(String city, String zip) {}
    record Person(String name, Address address) {}

    @Test
    void fromJqValue_nestedRecord() {
        JqValue json = JqValues.parse("{\"name\":\"Alice\",\"address\":{\"city\":\"NYC\",\"zip\":\"10001\"}}");
        Person p = mapper.fromJqValue(json, Person.class);
        assertEquals("Alice", p.name());
        assertNotNull(p.address());
        assertEquals("NYC", p.address().city());
        assertEquals("10001", p.address().zip());
    }

    @Test
    void toJqValue_nestedRecord() {
        Person p = new Person("Bob", new Address("SF", "94105"));
        JqValue json = mapper.toJqValue(p);
        assertEquals("Bob", json.getField("name").stringValue());
        assertEquals("SF", json.at("/address/city").stringValue());
        assertEquals("94105", json.at("/address/zip").stringValue());
    }

    @Test
    void roundTrip_nestedRecord() {
        Person original = new Person("Alice", new Address("NYC", "10001"));
        JqValue json = mapper.toJqValue(original);
        Person restored = mapper.fromJqValue(json, Person.class);
        assertEquals(original, restored);
    }

    // ---- Lists ----

    record WithList(String name, List<Integer> scores) {}

    @Test
    void fromJqValue_list() {
        JqValue json = JqValues.parse("{\"name\":\"Alice\",\"scores\":[95,87,92]}");
        WithList r = mapper.fromJqValue(json, WithList.class);
        assertEquals("Alice", r.name());
        assertEquals(List.of(95, 87, 92), r.scores());
    }

    @Test
    void toJqValue_list() {
        WithList r = new WithList("Bob", List.of(80, 90, 100));
        JqValue json = mapper.toJqValue(r);
        assertEquals("[80,90,100]", json.getField("scores").toJsonString());
    }

    @Test
    void roundTrip_list() {
        WithList original = new WithList("Alice", List.of(95, 87, 92));
        JqValue json = mapper.toJqValue(original);
        WithList restored = mapper.fromJqValue(json, WithList.class);
        assertEquals(original, restored);
    }

    // ---- List of records ----

    record Item(String name, double price) {}
    record Order(String id, List<Item> items) {}

    @Test
    void fromJqValue_listOfRecords() {
        JqValue json = JqValues.parse("""
                {"id":"order-1","items":[
                    {"name":"Widget","price":9.99},
                    {"name":"Gadget","price":24.99}
                ]}""");
        Order order = mapper.fromJqValue(json, Order.class);
        assertEquals("order-1", order.id());
        assertEquals(2, order.items().size());
        assertEquals("Widget", order.items().get(0).name());
        assertEquals(24.99, order.items().get(1).price(), 0.001);
    }

    @Test
    void roundTrip_listOfRecords() {
        Order original = new Order("order-1", List.of(
                new Item("Widget", 9.99),
                new Item("Gadget", 24.99)
        ));
        JqValue json = mapper.toJqValue(original);
        Order restored = mapper.fromJqValue(json, Order.class);
        assertEquals(original, restored);
    }

    // ---- Maps ----

    record WithMap(String name, Map<String, Integer> scores) {}

    @Test
    void fromJqValue_map() {
        JqValue json = JqValues.parse("{\"name\":\"Alice\",\"scores\":{\"math\":95,\"english\":87}}");
        WithMap r = mapper.fromJqValue(json, WithMap.class);
        assertEquals("Alice", r.name());
        assertEquals(95, r.scores().get("math"));
        assertEquals(87, r.scores().get("english"));
    }

    @Test
    void toJqValue_map() {
        WithMap r = new WithMap("Bob", Map.of("math", 90, "english", 80));
        JqValue json = mapper.toJqValue(r);
        assertInstanceOf(JqObject.class, json.getField("scores"));
    }

    // ---- Optional ----

    record WithOptional(String name, Optional<String> email) {}

    @Test
    void fromJqValue_optionalPresent() {
        JqValue json = JqValues.parse("{\"name\":\"Alice\",\"email\":\"alice@example.com\"}");
        WithOptional r = mapper.fromJqValue(json, WithOptional.class);
        assertEquals("Alice", r.name());
        assertTrue(r.email().isPresent());
        assertEquals("alice@example.com", r.email().get());
    }

    @Test
    void fromJqValue_optionalMissing() {
        JqValue json = JqValues.parse("{\"name\":\"Alice\"}");
        WithOptional r = mapper.fromJqValue(json, WithOptional.class);
        assertEquals("Alice", r.name());
        // .email returns JqNull.NULL for missing key, which maps to Optional.empty()
        // Actually JqNull → defaultValue → null for Optional → but we handle Optional specially
        assertNotNull(r.email());
    }

    @Test
    void toJqValue_optional() {
        WithOptional with = new WithOptional("Alice", Optional.of("alice@example.com"));
        JqValue json = mapper.toJqValue(with);
        assertEquals("alice@example.com", json.getField("email").stringValue());

        WithOptional without = new WithOptional("Bob", Optional.empty());
        JqValue json2 = mapper.toJqValue(without);
        assertTrue(json2.getField("email").isNull());
    }

    // ---- Enums ----

    enum Status { ACTIVE, INACTIVE }
    record WithEnum(String name, Status status) {}

    @Test
    void fromJqValue_enum() {
        JqValue json = JqValues.parse("{\"name\":\"Alice\",\"status\":\"ACTIVE\"}");
        WithEnum r = mapper.fromJqValue(json, WithEnum.class);
        assertEquals(Status.ACTIVE, r.status());
    }

    @Test
    void toJqValue_enum() {
        JqValue json = mapper.toJqValue(new WithEnum("Bob", Status.INACTIVE));
        assertEquals("INACTIVE", json.getField("status").stringValue());
    }

    @Test
    void roundTrip_enum() {
        WithEnum original = new WithEnum("Alice", Status.ACTIVE);
        assertEquals(original, mapper.fromJqValue(mapper.toJqValue(original), WithEnum.class));
    }

    // ---- JqValue passthrough ----

    record WithJqValue(String name, JqValue metadata) {}

    @Test
    void fromJqValue_jqValuePassthrough() {
        JqValue json = JqValues.parse("{\"name\":\"Alice\",\"metadata\":{\"x\":1,\"y\":[2,3]}}");
        WithJqValue r = mapper.fromJqValue(json, WithJqValue.class);
        assertEquals("Alice", r.name());
        assertInstanceOf(JqObject.class, r.metadata());
        assertEquals(1L, r.metadata().getField("x").longValue());
    }

    @Test
    void toJqValue_jqValuePassthrough() {
        JqValue meta = JqValues.parse("{\"x\":1}");
        JqValue json = mapper.toJqValue(new WithJqValue("Bob", meta));
        assertEquals("{\"x\":1}", json.getField("metadata").toJsonString());
    }

    // ---- Missing fields ----

    @Test
    void fromJqValue_missingFields() {
        JqValue json = JqValues.parse("{\"name\":\"Alice\"}");
        SimpleRecord r = mapper.fromJqValue(json, SimpleRecord.class);
        assertEquals("Alice", r.name());
        assertEquals(0, r.age()); // default for int
        assertFalse(r.active()); // default for boolean
    }

    // ---- Null values ----

    record WithNullable(String name, String email) {}

    @Test
    void fromJqValue_nullValue() {
        JqValue json = JqValues.parse("{\"name\":\"Alice\",\"email\":null}");
        WithNullable r = mapper.fromJqValue(json, WithNullable.class);
        assertEquals("Alice", r.name());
        assertNull(r.email());
    }

    // ---- Convenience methods ----

    @Test
    void fromJson_string() {
        SimpleRecord r = mapper.fromJson("{\"name\":\"Alice\",\"age\":30,\"active\":true}", SimpleRecord.class);
        assertEquals("Alice", r.name());
        assertEquals(30, r.age());
    }

    @Test
    void fromJson_bytes() {
        byte[] json = "{\"name\":\"Bob\",\"age\":25,\"active\":false}".getBytes();
        SimpleRecord r = mapper.fromJson(json, SimpleRecord.class);
        assertEquals("Bob", r.name());
        assertEquals(25, r.age());
    }

    @Test
    void toJson_string() {
        String json = mapper.toJson(new SimpleRecord("Alice", 30, true));
        assertTrue(json.contains("\"name\":\"Alice\""));
        assertTrue(json.contains("\"age\":30"));
    }

    @Test
    void toJsonBytes() {
        byte[] bytes = mapper.toJsonBytes(new SimpleRecord("Alice", 30, true));
        String json = new String(bytes);
        assertTrue(json.contains("\"name\":\"Alice\""));
    }

    // ---- Error handling ----

    @Test
    void fromJqValue_nonRecordThrows() {
        assertThrows(JqMapperException.class,
                () -> mapper.fromJqValue(JqValues.parse("{}"), String.class));
    }

    @Test
    void toJqValue_null() {
        JqValue result = mapper.toJqValue(null);
        assertEquals(JqNull.NULL, result);
    }

    // ---- @JqField annotation ----

    record Annotated(
            String user,
            @JqField(".config.timeout") int timeout,
            @JqField(".data[0].name") String firstName
    ) {}

    @Test
    void fromJqValue_jqFieldAnnotation() {
        JqValue json = JqValues.parse("""
                {"user":"Alice","config":{"timeout":30},"data":[{"name":"first"},{"name":"second"}]}""");
        Annotated r = mapper.fromJqValue(json, Annotated.class);
        assertEquals("Alice", r.user());
        assertEquals(30, r.timeout());
        assertEquals("first", r.firstName());
    }

    // ---- @JqIgnore annotation ----

    record WithIgnored(
            String name,
            @JqIgnore String secret
    ) {}

    @Test
    void fromJqValue_jqIgnore() {
        JqValue json = JqValues.parse("{\"name\":\"Alice\",\"secret\":\"password123\"}");
        WithIgnored r = mapper.fromJqValue(json, WithIgnored.class);
        assertEquals("Alice", r.name());
        assertNull(r.secret()); // ignored, gets default
    }

    @Test
    void toJqValue_jqIgnore() {
        JqValue json = mapper.toJqValue(new WithIgnored("Alice", "password123"));
        assertEquals("Alice", json.getField("name").stringValue());
        assertTrue(json.getField("secret").isNull()); // not serialized
    }

    // ---- Thread safety ----

    @Test
    void mapper_isThreadSafe() throws Exception {
        int threadCount = 8;
        int iterations = 1000;
        var errors = new java.util.concurrent.atomic.AtomicInteger(0);
        var latch = new java.util.concurrent.CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    for (int i = 0; i < iterations; i++) {
                        SimpleRecord original = new SimpleRecord("Thread-" + i, i, i % 2 == 0);
                        JqValue json = mapper.toJqValue(original);
                        SimpleRecord restored = mapper.fromJqValue(json, SimpleRecord.class);
                        if (!original.equals(restored)) errors.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }
        latch.await();
        assertEquals(0, errors.get());
    }

    // ---- Deep nesting ----

    record L3(String value) {}
    record L2(String name, L3 inner) {}
    record L1(String id, L2 nested) {}

    @Test
    void roundTrip_deepNesting() {
        L1 original = new L1("root", new L2("mid", new L3("leaf")));
        JqValue json = mapper.toJqValue(original);
        L1 restored = mapper.fromJqValue(json, L1.class);
        assertEquals(original, restored);
        assertEquals("leaf", json.at("/nested/inner/value").stringValue());
    }

    // ---- Fast-path forEach extraction ----

    @Test
    void fastPath_exactFieldMatch() {
        // JSON fields match record fields exactly — should use forEach fast path
        JqValue json = JqValues.parse("{\"name\":\"Alice\",\"age\":30,\"active\":true}");
        SimpleRecord r = mapper.fromJqValue(json, SimpleRecord.class);
        assertEquals("Alice", r.name());
        assertEquals(30, r.age());
        assertTrue(r.active());
    }

    @Test
    void fastPath_extraFieldsInJson() {
        // JSON has extra fields not in the record — fast path detects size mismatch,
        // falls back to per-field extraction which ignores extra fields
        JqValue json = JqValues.parse("{\"name\":\"Alice\",\"age\":30,\"active\":true,\"extra\":\"ignored\"}");
        SimpleRecord r = mapper.fromJqValue(json, SimpleRecord.class);
        assertEquals("Alice", r.name());
        assertEquals(30, r.age());
        assertTrue(r.active());
    }

    @Test
    void fastPath_fewerFieldsInJson() {
        // JSON has fewer fields — fast path detects size mismatch
        JqValue json = JqValues.parse("{\"name\":\"Alice\"}");
        SimpleRecord r = mapper.fromJqValue(json, SimpleRecord.class);
        assertEquals("Alice", r.name());
        assertEquals(0, r.age());
        assertFalse(r.active());
    }

    @Test
    void fastPath_differentFieldOrder() {
        // JSON fields in different order — forEach still matches by name
        JqValue json = JqValues.parse("{\"active\":true,\"name\":\"Bob\",\"age\":25}");
        SimpleRecord r = mapper.fromJqValue(json, SimpleRecord.class);
        assertEquals("Bob", r.name());
        assertEquals(25, r.age());
        assertTrue(r.active());
    }

    @Test
    void fastPath_annotatedFieldsFallBack() {
        // Record with @JqField — fast path is disabled, falls back to per-field extraction
        JqValue json = JqValues.parse("{\"user\":\"Alice\",\"config\":{\"timeout\":30},\"data\":[{\"name\":\"first\"}]}");
        Annotated r = mapper.fromJqValue(json, Annotated.class);
        assertEquals("Alice", r.user());
        assertEquals(30, r.timeout());
        assertEquals("first", r.firstName());
    }

    // ---- Generated mapping discovery ----

    @Test
    void reflectionFallback_whenNoGeneratedMapping() {
        // No generated User_JqMapping class exists — should fall back to reflection
        // and still work correctly (this verifies the loadGenerated null path)
        JqValue json = JqValues.parse("{\"name\":\"Alice\",\"age\":30,\"active\":true}");
        SimpleRecord r = mapper.fromJqValue(json, SimpleRecord.class);
        assertEquals("Alice", r.name());
        assertEquals(30, r.age());
    }

    @Test
    void jqMappedAnnotation_presentOnType() {
        // Verify @JqMapped is a valid annotation that can be applied to records
        // (this test ensures the annotation compiles and is retained at runtime)
        assertTrue(MappedRecord.class.isAnnotationPresent(JqMapped.class));
    }

    @JqMapped
    record MappedRecord(String name, int value) {}

    @Test
    void jqMappedRecord_worksWithReflection() {
        // @JqMapped record should work with reflection-based mapping
        // (no generated class exists yet — processor not running)
        JqValue json = JqValues.parse("{\"name\":\"test\",\"value\":42}");
        MappedRecord r = mapper.fromJqValue(json, MappedRecord.class);
        assertEquals("test", r.name());
        assertEquals(42, r.value());

        JqValue back = mapper.toJqValue(r);
        assertEquals("test", back.getField("name").stringValue());
        assertEquals(42L, back.getField("value").longValue());
    }

    // ---- Builder API ----

    @Test
    void builder_registerAndUse() {
        // Create a custom generated mapping to verify the builder uses it
        var customMapping = new GeneratedMapping<SimpleRecord>() {
            @Override
            public SimpleRecord fromJqValue(JqValue input, JqMapper mapper) {
                // Return a sentinel value to prove this mapping was used
                return new SimpleRecord("from-builder", 99, true);
            }
            @Override
            public JqValue toJqValue(SimpleRecord instance, JqMapper mapper) {
                return JqValues.parse("{\"name\":\"from-builder\",\"age\":99,\"active\":true}");
            }
            @Override
            public Class<SimpleRecord> type() { return SimpleRecord.class; }
        };

        JqMapper builderMapper = JqMapper.builder()
                .register(customMapping)
                .build();

        JqValue json = JqValues.parse("{\"name\":\"Alice\",\"age\":30,\"active\":true}");
        SimpleRecord r = builderMapper.fromJqValue(json, SimpleRecord.class);

        // The custom mapping ignores input and returns the sentinel
        assertEquals("from-builder", r.name());
        assertEquals(99, r.age());
    }

    @Test
    void builder_emptyBuildFallsBackToDiscovery() {
        // An empty builder should still discover mappings via Class.forName / reflection
        JqMapper builderMapper = JqMapper.builder().build();

        JqValue json = JqValues.parse("{\"name\":\"Alice\",\"age\":30,\"active\":true}");
        SimpleRecord r = builderMapper.fromJqValue(json, SimpleRecord.class);

        assertEquals("Alice", r.name());
        assertEquals(30, r.age());
        assertTrue(r.active());
    }

    @Test
    void builder_registerMultipleMappings() {
        var simpleMapping = new GeneratedMapping<SimpleRecord>() {
            @Override
            public SimpleRecord fromJqValue(JqValue input, JqMapper mapper) {
                return new SimpleRecord("simple-registered", 1, false);
            }
            @Override
            public JqValue toJqValue(SimpleRecord instance, JqMapper mapper) {
                return JqValues.parse("{}");
            }
            @Override
            public Class<SimpleRecord> type() { return SimpleRecord.class; }
        };

        var numericMapping = new GeneratedMapping<NumericRecord>() {
            @Override
            public NumericRecord fromJqValue(JqValue input, JqMapper mapper) {
                return new NumericRecord(42, 42L, 42.0, 42.0f, (short) 42, (byte) 42, null);
            }
            @Override
            public JqValue toJqValue(NumericRecord instance, JqMapper mapper) {
                return JqValues.parse("{}");
            }
            @Override
            public Class<NumericRecord> type() { return NumericRecord.class; }
        };

        JqMapper builderMapper = JqMapper.builder()
                .register(simpleMapping)
                .register(numericMapping)
                .build();

        JqValue json = JqValues.parse("{}");
        assertEquals("simple-registered", builderMapper.fromJqValue(json, SimpleRecord.class).name());
        assertEquals(42, builderMapper.fromJqValue(json, NumericRecord.class).i());
    }
}

package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.mapper.spi.AnnotationBridge;
import io.hyperfoil.tools.jjq.value.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    // ---- Source text for String binding (issue #126) ----

    record StringHolder(String value) {}

    @Test
    void sourcedScalarsBindSourceTextToString() {
        assertEquals("0022", mapper.fromJqValue(
                JqObject.builder().put("value", JqNumber.of(18).withSourceText("0022")).build(),
                StringHolder.class).value());
        assertEquals("no", mapper.fromJqValue(
                JqObject.builder().put("value", JqBoolean.FALSE.withSourceText("no")).build(),
                StringHolder.class).value());
    }

    @Test
    void canonicalScalarsRenderAsJsonForString() {
        // No source attached: behavior unchanged
        assertEquals("18", mapper.fromJqValue(
                JqObject.builder().put("value", JqNumber.of(18)).build(),
                StringHolder.class).value());
        assertEquals("false", mapper.fromJqValue(
                JqObject.builder().put("value", JqBoolean.FALSE).build(),
                StringHolder.class).value());
    }

    @Test
    void asStringCheckedMirrorsSourceText() {
        // Generated-mapping path must match TypeConverter STRING semantics
        assertEquals("0022", GeneratedMapping.asStringChecked(JqNumber.of(18).withSourceText("0022")));
        assertEquals("18", GeneratedMapping.asStringChecked(JqNumber.of(18)));
        assertEquals("Alice", GeneratedMapping.asStringChecked(JqString.of("Alice")));
    }

    @Test
    void sourceTextPreservesTypedSemantics() {
        JqNumber n = JqNumber.of(18).withSourceText("0022");
        assertEquals("0022", n.sourceText());
        assertEquals("18", n.toJsonString());
        assertEquals(JqNumber.of(18), n);
        assertEquals(JqNumber.of(18).hashCode(), n.hashCode());
        assertNull(JqNumber.of(18).sourceText());
        assertNull(JqBoolean.TRUE.sourceText());
        assertSame(JqBoolean.TRUE, JqBoolean.of(true));
    }

    // ---- Java-only fields are invisible (issue #95.2, Jackson parity) ----

    static class WithCodeOnlyFlag {
        public String name;
        private boolean expandAtLogin;

        WithCodeOnlyFlag() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @Test
    void privateFieldWithoutGetterIsOmitted() {
        WithCodeOnlyFlag bean = new WithCodeOnlyFlag();
        bean.setName("x");
        JqValue result = mapper.toJqValue(bean);
        assertEquals("x", result.getField("name").stringValue());
        assertFalse(result.has("expandAtLogin"),
                "Private field with no getter must not leak: " + result.toJsonString());
    }

    static class NoAccessorsAtAll {
        private int hidden = 3;

        NoAccessorsAtAll() {}
    }

    @Test
    void fullySkippedClassStillConstructs() {
        // A class whose fields are all invisible serializes as {} and still
        // constructs (Jackson parity: empty bean, ignored properties).
        JqValue result = mapper.toJqValue(new NoAccessorsAtAll());
        assertEquals("{}", result.toJsonString());
        assertNotNull(mapper.fromJqValue(JqValues.parse("{\"hidden\":9}"), NoAccessorsAtAll.class));
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

    // ---- Sets (issue #102) ----

    record WithSet(String name, Set<String> tags) {}

    @Test
    void fromJqValue_set() {
        JqValue json = JqValues.parse("{\"name\":\"Alice\",\"tags\":[\"b\",\"a\",\"b\"]}");
        WithSet r = mapper.fromJqValue(json, WithSet.class);
        assertEquals("Alice", r.name());
        assertEquals(Set.of("b", "a"), r.tags());
    }

    @Test
    void fromJqValue_setOfRecords() {
        record TagBag(String id, Set<Address> places) {}
        JqValue json = JqValues.parse("""
                {"id":"t1","places":[
                    {"city":"NYC","zip":"10001"},
                    {"city":"SF","zip":"94105"}
                ]}""");
        TagBag bag = mapper.fromJqValue(json, TagBag.class);
        assertEquals("t1", bag.id());
        assertEquals(2, bag.places().size());
        assertTrue(bag.places().contains(new Address("NYC", "10001")));
    }

    @Test
    void fromJqValue_setNullBindsEmpty() {
        JqValue json = JqValues.parse("{\"name\":\"Alice\",\"tags\":null}");
        WithSet r = mapper.fromJqValue(json, WithSet.class);
        assertEquals(Set.of(), r.tags());
    }

    @Test
    void fromJqValue_setPreservesDeclaredImpl() {
        // Issue scope item 2: a declared HashSet must bind as one (was always
        // LinkedHashSet); LinkedHashSet stays as is.
        record HashSetHolder(java.util.HashSet<String> tags) {}
        record LinkedHolder(java.util.LinkedHashSet<String> tags) {}

        HashSetHolder h = mapper.fromJqValue(JqValues.parse("{\"tags\":[\"b\",\"a\"]}"),
                HashSetHolder.class);
        assertEquals(java.util.HashSet.class, h.tags().getClass());
        assertEquals(java.util.Set.of("b", "a"), h.tags());

        LinkedHolder l = mapper.fromJqValue(JqValues.parse("{\"tags\":[\"b\",\"a\"]}"),
                LinkedHolder.class);
        assertEquals(java.util.LinkedHashSet.class, l.tags().getClass());
    }

    @Test
    void fromJqValue_setMismatchHasPath() {
        JqValue json = JqValues.parse("{\"name\":\"Alice\",\"tags\":\"oops\"}");
        io.hyperfoil.tools.jjq.mapper.JqMapperException e = assertThrows(
                io.hyperfoil.tools.jjq.mapper.JqMapperException.class,
                () -> mapper.fromJqValue(json, WithSet.class));
        assertTrue(e.getMessage().contains("tags"), e.getMessage());
        assertTrue(e instanceof io.hyperfoil.tools.jjq.mapper.ShapeMismatchException);
    }

    @Test
    void toJqValue_set() {
        WithSet r = new WithSet("Bob", new java.util.LinkedHashSet<>(List.of("x", "y")));
        JqValue json = mapper.toJqValue(r);
        assertInstanceOf(JqArray.class, json.getField("tags"));
        assertEquals(2, ((JqArray) json.getField("tags")).size());
    }

    @Test
    void roundTrip_set() {
        WithSet original = new WithSet("Alice", new java.util.LinkedHashSet<>(List.of("b", "a")));
        JqValue json = mapper.toJqValue(original);
        WithSet restored = mapper.fromJqValue(json, WithSet.class);
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

    // ---- Shape mismatches (issue #84) ----

    record WithPackages(String name, List<String> packages) {}

    @Test
    void fromJqValue_scalarForListThrows() {
        JqValue json = JqValues.parse("{\"name\":\"t\",\"packages\":\"git\"}");
        JqMapperException e = assertThrows(JqMapperException.class,
                () -> mapper.fromJqValue(json, WithPackages.class));
        assertTrue(e.getMessage().contains("packages"), e.getMessage());
    }

    @Test
    void fromJqValue_objectForListThrows() {
        JqValue json = JqValues.parse("{\"name\":\"t\",\"packages\":{\"a\":1}}");
        assertThrows(JqMapperException.class,
                () -> mapper.fromJqValue(json, WithPackages.class));
    }

    record WithMeta(String name, Map<String, Integer> meta) {}

    @Test
    void fromJqValue_scalarForMapThrows() {
        JqValue json = JqValues.parse("{\"name\":\"t\",\"meta\":42}");
        JqMapperException e = assertThrows(JqMapperException.class,
                () -> mapper.fromJqValue(json, WithMeta.class));
        assertTrue(e.getMessage().contains("meta"), e.getMessage());
    }

    record Tool(String name) {}
    record Bundle(String id, List<Tool> tools) {}

    @Test
    void fromJqValue_scalarElementThrowsWithPath() {
        JqValue json = JqValues.parse("{\"id\":\"b\",\"tools\":[{\"name\":\"ok\"},\"oops\"]}");
        JqMapperException e = assertThrows(JqMapperException.class,
                () -> mapper.fromJqValue(json, Bundle.class));
        String msg = e.getMessage();
        assertTrue(msg.contains("tools"), msg);
        assertTrue(msg.contains("[1]"), msg);
    }

    static class PlainTool {
        private String name;

        public PlainTool() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    record PlainBundle(String id, List<PlainTool> tools) {}

    @Test
    void fromJqValue_plainPojoElements() {
        // Object elements bind structurally even without @JqMapped
        JqValue json = JqValues.parse("{\"id\":\"b\",\"tools\":[{\"name\":\"ok\"}]}");
        PlainBundle b = mapper.fromJqValue(json, PlainBundle.class);
        assertEquals("ok", b.tools().get(0).getName());
        // Scalar elements fail fast with a path instead of corrupting the list
        JqValue bad = JqValues.parse("{\"id\":\"b\",\"tools\":[\"oops\"]}");
        JqMapperException e = assertThrows(JqMapperException.class,
                () -> mapper.fromJqValue(bad, PlainBundle.class));
        assertTrue(e.getMessage().contains("tools"), e.getMessage());
    }

    @Test
    void fromJqValue_scalarForPojoThrows() {
        JqValue json = JqValues.parse("\"hi\"");
        assertThrows(JqMapperException.class, () -> mapper.fromJqValue(json, SimplePojo.class));
        assertThrows(JqMapperException.class, () -> mapper.fromJqValue(json, SimpleRecord.class));
    }

    @Test
    void fromJqValue_nullInputLenient() {
        SimpleRecord r = mapper.fromJqValue(JqNull.NULL, SimpleRecord.class);
        assertNull(r.name());
        assertEquals(0, r.age());
    }

    // ---- Array/object to scalar (issue #85) ----

    record WithType(String type) {}

    record WithAutoClone(@JqName("auto-clone-repos") String autoCloneRepos) {}

    @Test
    void fromJqValue_arrayForStringThrows() {
        JqValue json = JqValues.parse("{\"type\":[\"kvm\"]}");
        JqMapperException e = assertThrows(JqMapperException.class,
                () -> mapper.fromJqValue(json, WithType.class));
        assertTrue(e.getMessage().contains("type"), e.getMessage());
    }

    @Test
    void fromJqValue_objectForStringThrows() {
        JqValue json = JqValues.parse("{\"type\":{\"kind\":\"kvm\"}}");
        assertThrows(JqMapperException.class,
                () -> mapper.fromJqValue(json, WithType.class));
    }

    @Test
    void fromJqValue_scalarToStringCoerces() {
        assertEquals("42", mapper.fromJqValue(JqValues.parse("{\"type\":42}"), WithType.class).type());
        assertEquals("true", mapper.fromJqValue(JqValues.parse("{\"type\":true}"), WithType.class).type());
    }

    record WithCount(String name, int count) {}
    record WithFlag(String name, boolean active) {}

    @Test
    void fromJqValue_containerForNumbersThrows() {
        assertThrows(JqMapperException.class, () ->
                mapper.fromJqValue(JqValues.parse("{\"name\":\"t\",\"count\":[1]}"), WithCount.class));
        assertThrows(JqMapperException.class, () ->
                mapper.fromJqValue(JqValues.parse("{\"name\":\"t\",\"count\":{\"n\":1}}"), WithCount.class));
        assertThrows(JqMapperException.class, () ->
                mapper.fromJqValue(JqValues.parse("{\"name\":\"t\",\"active\":[true]}"), WithFlag.class));
    }

    // ---- Structured shape info (issue #86) ----

    @Test
    void shapeMismatchExposesExpectedActual() {
        JqValue json = JqValues.parse("{\"name\":\"t\",\"packages\":\"git\"}");
        ShapeMismatchException e = assertThrows(ShapeMismatchException.class,
                () -> mapper.fromJqValue(json, WithPackages.class));
        assertEquals(JqValue.Type.ARRAY, e.expected());
        assertEquals(JqValue.Type.STRING, e.actual());
        assertEquals("$.packages", e.path());
        // Human message unchanged (no regex needed, but still human-readable)
        assertTrue(e.getMessage().contains("Cannot bind"), e.getMessage());
    }

    @Test
    void shapeMismatchElementHasFullPath() {
        JqValue json = JqValues.parse("{\"id\":\"b\",\"tools\":[{\"name\":\"ok\"},\"oops\"]}");
        ShapeMismatchException e = assertThrows(ShapeMismatchException.class,
                () -> mapper.fromJqValue(json, Bundle.class));
        assertEquals(JqValue.Type.OBJECT, e.expected());
        assertEquals(JqValue.Type.STRING, e.actual());
        assertEquals("$.tools[1]", e.path());
    }

    @Test
    void shapeMismatchContainerForScalar() {
        JqValue json = JqValues.parse("{\"type\":[\"kvm\"]}");
        ShapeMismatchException e = assertThrows(ShapeMismatchException.class,
                () -> mapper.fromJqValue(json, WithType.class));
        assertEquals(JqValue.Type.STRING, e.expected());
        assertEquals(JqValue.Type.ARRAY, e.actual());
        assertEquals("$.type", e.path());
    }

    // ---- Structured path segments + display form (issue #128.4) ----

    @Test
    void pathSegmentsAreStructured() {
        JqValue json = JqValues.parse("{\"id\":\"b\",\"tools\":[{\"name\":\"ok\"},\"oops\"]}");
        ShapeMismatchException e = assertThrows(ShapeMismatchException.class,
                () -> mapper.fromJqValue(json, Bundle.class));
        assertEquals(java.util.List.of("tools", 1), e.pathSegments());
        assertEquals("$.tools[1]", e.path());
    }

    @Test
    void displayPathDropsJqPunctuation() {
        JqValue json = JqValues.parse("{\"id\":\"b\",\"tools\":[{\"name\":\"ok\"},\"oops\"]}");
        ShapeMismatchException e = assertThrows(ShapeMismatchException.class,
                () -> mapper.fromJqValue(json, Bundle.class));
        assertEquals("tools[1]", e.displayPath());
    }

    @Test
    void displayPathBareSingleNonIdentifier() {
        JqValue json = JqValues.parse("{\"auto-clone-repos\":[]}");
        ShapeMismatchException e = assertThrows(ShapeMismatchException.class,
                () -> mapper.fromJqValue(json, WithAutoClone.class));
        assertEquals("$[\"auto-clone-repos\"]", e.path());
        assertEquals("auto-clone-repos", e.displayPath());
        assertEquals(java.util.List.of("auto-clone-repos"), e.pathSegments());
    }

    // ---- Strict boolean coercion (issue #128.2) ----

    record WithGui(String name, boolean gui) {}

    @Test
    void nonBooleanStringForBooleanThrows() {
        JqValue json = JqValues.parse("{\"name\":\"t\",\"gui\":\"maybe\"}");
        ShapeMismatchException e = assertThrows(ShapeMismatchException.class,
                () -> mapper.fromJqValue(json, WithGui.class));
        assertEquals(JqValue.Type.BOOLEAN, e.expected());
        assertEquals(JqValue.Type.STRING, e.actual());
        assertEquals("$.gui", e.path());
    }

    @Test
    void trueFalseStringsBindBoolean() {
        // Jackson parity: "true"/"false" in any case bind
        assertTrue(mapper.fromJqValue(
                JqValues.parse("{\"name\":\"t\",\"gui\":\"TRUE\"}"), WithGui.class).gui());
        assertFalse(mapper.fromJqValue(
                JqValues.parse("{\"name\":\"t\",\"gui\":\"False\"}"), WithGui.class).gui());
    }

    @Test
    void numbersBindBooleanByZeroRule() {
        // Jackson parity: 0 is false, anything else is true (both paths unified)
        assertTrue(mapper.fromJqValue(
                JqValues.parse("{\"name\":\"t\",\"gui\":1}"), WithGui.class).gui());
        assertFalse(mapper.fromJqValue(
                JqValues.parse("{\"name\":\"t\",\"gui\":0}"), WithGui.class).gui());
    }

    @Test
    void asBooleanCheckedMirrors() {
        // Generated-mapping path must match TypeConverter BOOLEAN semantics
        assertTrue(GeneratedMapping.asBooleanChecked(JqBoolean.TRUE));
        assertTrue(GeneratedMapping.asBooleanChecked(JqString.of("true")));
        assertFalse(GeneratedMapping.asBooleanChecked(JqString.of("False")));
        assertTrue(GeneratedMapping.asBooleanChecked(JqNumber.of(2)));
        assertFalse(GeneratedMapping.asBooleanChecked(JqNumber.of(0)));
        assertThrows(ShapeMismatchException.class,
                () -> GeneratedMapping.asBooleanChecked(JqString.of("maybe")));
    }

    // ---- Jackson-parity numeric coercion (issue #135) ----

    record Nums(int i, long l, double d) {}

    @Test
    void numericStringsCoerce() {
        Nums r = mapper.fromJqValue(
                JqValues.parse("{\"i\":\"42\",\"l\":\"4200000000\",\"d\":\"4.5\"}"), Nums.class);
        assertEquals(42, r.i());
        assertEquals(4200000000L, r.l());
        assertEquals(4.5, r.d());
    }

    @Test
    void garbageStringsThrowForNumbers() {
        ShapeMismatchException e = assertThrows(ShapeMismatchException.class,
                () -> mapper.fromJqValue(JqValues.parse("{\"i\":\"x\",\"l\":0,\"d\":0}"), Nums.class));
        assertEquals(JqValue.Type.NUMBER, e.expected());
        assertEquals(JqValue.Type.STRING, e.actual());
        assertEquals("$.i", e.path());
        assertThrows(ShapeMismatchException.class,
                () -> mapper.fromJqValue(JqValues.parse("{\"i\":0,\"l\":\"\",\"d\":0}"), Nums.class));
        assertThrows(ShapeMismatchException.class,
                () -> mapper.fromJqValue(JqValues.parse("{\"i\":0,\"l\":0,\"d\":\"4.5.6\"}"), Nums.class));
    }

    // ---- Range + fraction strictness for integral targets (#133) ----

    @Test
    void outOfRangeNumbersThrowForInt() {
        assertThrows(ShapeMismatchException.class,
                () -> mapper.fromJqValue(JqValues.parse("{\"i\":99999999999,\"l\":0,\"d\":0}"), Nums.class));
        assertThrows(ShapeMismatchException.class,
                () -> mapper.fromJqValue(JqValues.parse("{\"i\":\"99999999999\",\"l\":0,\"d\":0}"), Nums.class));
    }

    @Test
    void fractionsThrowForIntegralTargets() {
        ShapeMismatchException e = assertThrows(ShapeMismatchException.class,
                () -> mapper.fromJqValue(JqValues.parse("{\"i\":2.5,\"l\":0,\"d\":0}"), Nums.class));
        assertEquals(JqValue.Type.NUMBER, e.expected());
        assertEquals("$.i", e.path());
        assertThrows(ShapeMismatchException.class,
                () -> mapper.fromJqValue(JqValues.parse("{\"i\":0,\"l\":2.5,\"d\":0}"), Nums.class));
    }

    @Test
    void wholeDoublesStillBindIntegralTargets() {
        // No loss: integral values bind regardless of representation
        Nums r = mapper.fromJqValue(JqValues.parse("{\"i\":4.0,\"l\":4.0,\"d\":4.5}"), Nums.class);
        assertEquals(4, r.i());
        assertEquals(4L, r.l());
        assertEquals(4.5, r.d());
    }

    @Test
    void rangeCheckedHelpersMirror() {
        assertEquals(42, GeneratedMapping.asIntChecked(JqNumber.of(42)));
        assertThrows(ShapeMismatchException.class,
                () -> GeneratedMapping.asIntChecked(JqNumber.of(99999999999L)));
        assertThrows(ShapeMismatchException.class,
                () -> GeneratedMapping.asIntChecked(JqNumber.of(2.5)));
        assertEquals(4, GeneratedMapping.asIntChecked(JqNumber.of(4.0)));
    }

    // ---- Non-finite values have no integral/decimal form (#131 follow-up) ----

    @Test
    void nonFiniteNumbersThrowForIntegralAndDecimalTargets() {
        // jq never holds non-finite values (clamps or nulls them); binding
        // them as 0 would be a silent wrong value, so they mismatch instead
        for (double special : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            JqValue v = JqNumber.of(special);
            assertThrows(ShapeMismatchException.class,
                    () -> mapper.fromJqValue(JqObject.builder().put("v", v).build(), Biggies.class));
            assertThrows(ShapeMismatchException.class,
                    () -> mapper.fromJqValue(JqObject.builder().put("i", v).put("l", 0L).put("d", 0.0).build(), Nums.class));
        }
    }

    @Test
    void nonFiniteNumbersStayExactForDoubleTargets() {
        // No conversion involved: doubles pass through untouched
        assertTrue(Double.isNaN(mapper.fromJqValue(
                JqObject.builder().put("i", 0).put("l", 0L).put("d", JqNumber.of(Double.NaN)).build(), Nums.class).d()));
        assertTrue(Double.isInfinite(mapper.fromJqValue(
                JqObject.builder().put("i", 0).put("l", 0L).put("d", JqNumber.of(Double.POSITIVE_INFINITY)).build(), Nums.class).d()));
    }

    @Test
    void nonFiniteCheckedHelpersMirror() {
        assertThrows(ShapeMismatchException.class,
                () -> GeneratedMapping.asBigDecimalChecked(JqNumber.of(Double.NaN)));
        assertThrows(ShapeMismatchException.class,
                () -> GeneratedMapping.asLongChecked(JqNumber.of(Double.POSITIVE_INFINITY)));
        assertEquals(Double.NaN, GeneratedMapping.asDoubleChecked(JqNumber.of(Double.NaN)));
    }

    @Test
    void booleansThrowForNumbers() {
        // jq's tonumber rejects booleans; Jackson's 0/1 rule loses (policy)
        ShapeMismatchException e = assertThrows(ShapeMismatchException.class,
                () -> mapper.fromJqValue(JqValues.parse("{\"i\":true,\"l\":0,\"d\":0}"), Nums.class));
        assertEquals(JqValue.Type.NUMBER, e.expected());
        assertEquals(JqValue.Type.BOOLEAN, e.actual());
        assertEquals("$.i", e.path());
        assertThrows(ShapeMismatchException.class,
                () -> mapper.fromJqValue(JqValues.parse("{\"i\":0,\"l\":false,\"d\":0}"), Nums.class));
        assertThrows(ShapeMismatchException.class,
                () -> mapper.fromJqValue(JqValues.parse("{\"i\":0,\"l\":0,\"d\":true}"), Nums.class));
        assertThrows(ShapeMismatchException.class,
                () -> mapper.fromJqValue(JqValues.parse("{\"v\":true}"), Biggies.class));
    }

    record Chars(char c) {}

    @Test
    void numberToCharUsesCodePoint() {
        assertEquals('A', mapper.fromJqValue(
                JqValues.parse("{\"c\":65}"), Chars.class).c());
    }

    @Test
    void multiCharStringToCharThrows() {
        assertThrows(ShapeMismatchException.class,
                () -> mapper.fromJqValue(JqValues.parse("{\"c\":\"ab\"}"), Chars.class));
    }

    @Test
    void emptyStringToCharStaysNul() {
        // Jackson parity for the empty corner (unchanged behavior)
        assertEquals('\0', mapper.fromJqValue(
                JqValues.parse("{\"c\":\"\"}"), Chars.class).c());
    }

    record Biggies(java.math.BigDecimal v) {}

    @Test
    void bigDecimalParsesStrings() {
        assertEquals(new java.math.BigDecimal("3.14"), mapper.fromJqValue(
                JqValues.parse("{\"v\":\"3.14\"}"), Biggies.class).v());
        assertThrows(ShapeMismatchException.class,
                () -> mapper.fromJqValue(JqValues.parse("{\"v\":\"abc\"}"), Biggies.class));
    }

    @Test
    void numericCheckedHelpersMirror() {
        // Generated-mapping path must match TypeConverter numeric semantics
        assertEquals(42L, GeneratedMapping.asLongChecked(JqString.of("42")));
        assertEquals(0L, GeneratedMapping.asLongChecked(null));
        assertEquals(4.5, GeneratedMapping.asDoubleChecked(JqString.of("4.5")));
        assertEquals('A', GeneratedMapping.asCharChecked(JqNumber.of(65)));
        assertEquals(new java.math.BigDecimal("3.14"),
                GeneratedMapping.asBigDecimalChecked(JqString.of("3.14")));
        assertNull(GeneratedMapping.asBigDecimalChecked(null));
        assertThrows(ShapeMismatchException.class,
                () -> GeneratedMapping.asLongChecked(JqString.of("abc")));
        assertThrows(ShapeMismatchException.class,
                () -> GeneratedMapping.asLongChecked(JqBoolean.TRUE));
        assertThrows(ShapeMismatchException.class,
                () -> GeneratedMapping.asDoubleChecked(JqBoolean.FALSE));
        assertThrows(ShapeMismatchException.class,
                () -> GeneratedMapping.asBigDecimalChecked(JqBoolean.TRUE));
        assertThrows(ShapeMismatchException.class,
                () -> GeneratedMapping.asCharChecked(JqString.of("ab")));
    }

    @Test
    void nonMismatchErrorsAreNotShapeMismatch() {
        // Constructor failures stay plain JqMapperException (no structured shapes)
        JqMapperException e = assertThrows(JqMapperException.class,
                () -> mapper.fromJqValue(JqValues.parse("{\"name\":\"x\"}"), NoDefaultConstructor.class));
        assertFalse(e instanceof ShapeMismatchException, e.getClass().getName());
    }

    // ---- failOnUnknownProperties (opt-in strict, Jackson parity) ----

    record Point(int x, int y) {}

    static class StrictPojo {
        public String name;
        public int age;
    }

    record IgnoredHolder(String keep, @JqIgnore String skip) {}

    static class ExtrasStrict {
        public String name;
        private final Map<String, JqValue> extras = new LinkedHashMap<>();

        public Map<String, JqValue> getExtras() { return extras; }

        @JqAnySetter
        public void setExtra(String key, JqValue value) { extras.put(key, value); }
    }

    record NestedStrict(Point point, String label) {}

    private final JqMapper strictMapper = JqMapper.builder().failOnUnknownProperties(true).build();

    @Test
    void strictRecordRejectsUnknown() {
        JqMapperException e = assertThrows(JqMapperException.class, () -> strictMapper.fromJqValue(
                JqValues.parse("{\"x\":1,\"y\":2,\"z\":3}"), Point.class));
        assertTrue(e.getMessage().contains("\"z\""), e.getMessage());
        assertTrue(e.getMessage().contains("Point"), e.getMessage());
        assertEquals(List.of("z"), e.pathSegments());
    }

    @Test
    void strictRecordKnownPasses() {
        assertEquals(new Point(1, 2),
                strictMapper.fromJqValue(JqValues.parse("{\"x\":1,\"y\":2}"), Point.class));
    }

    @Test
    void lenientDefaultIgnoresUnknown() {
        assertEquals(new Point(1, 2),
                mapper.fromJqValue(JqValues.parse("{\"x\":1,\"y\":2,\"z\":3}"), Point.class));
    }

    @Test
    void strictMissingStillLenient() {
        assertEquals(new Point(1, 0),
                strictMapper.fromJqValue(JqValues.parse("{\"x\":1}"), Point.class));
    }

    @Test
    void strictPojoRejectsUnknown() {
        JqMapperException e = assertThrows(JqMapperException.class, () -> strictMapper.fromJqValue(
                JqValues.parse("{\"name\":\"a\",\"age\":1,\"extra\":true}"), StrictPojo.class));
        assertTrue(e.getMessage().contains("\"extra\""), e.getMessage());
        StrictPojo p = strictMapper.fromJqValue(
                JqValues.parse("{\"name\":\"a\",\"age\":1}"), StrictPojo.class);
        assertEquals("a", p.name);
        assertEquals(1, p.age);
    }

    @Test
    void strictIgnoredKeyPasses() {
        IgnoredHolder h = strictMapper.fromJqValue(
                JqValues.parse("{\"keep\":\"a\",\"skip\":\"b\"}"), IgnoredHolder.class);
        assertEquals("a", h.keep());
    }

    @Test
    void strictAnySetterForwardsWithoutFailure() {
        ExtrasStrict e = strictMapper.fromJqValue(
                JqValues.parse("{\"name\":\"a\",\"zzz\":42}"), ExtrasStrict.class);
        assertEquals("a", e.name);
        assertEquals(42L, e.extras.get("zzz").longValue());
    }

    @Test
    void strictNestedPathPointsAtUnknown() {
        JqMapperException e = assertThrows(JqMapperException.class, () -> strictMapper.fromJqValue(
                JqValues.parse("{\"point\":{\"x\":1,\"y\":2,\"z\":3},\"label\":\"a\"}"), NestedStrict.class));
        assertEquals(List.of("point", "z"), e.pathSegments());
    }

    @Test
    void strictPolyIgnoresDiscriminatorButFlagsUnknown() {
        Shape s = strictMapper.fromJqValue(
                JqValues.parse("{\"type\":\"Circle\",\"radius\":1.5}"), Shape.class);
        assertEquals(new Circle(1.5), s);
        JqMapperException e = assertThrows(JqMapperException.class, () -> strictMapper.fromJqValue(
                JqValues.parse("{\"type\":\"Circle\",\"radius\":1.5,\"zzz\":1}"), Shape.class));
        assertTrue(e.getMessage().contains("\"zzz\""), e.getMessage());
    }

    // ---- @JqCreator (delegating + property-based, records and POJOs) ----

    record UserId(String value) {
        @JqCreator
        public static UserId parse(String value) {
            if (value == null || value.isEmpty()) throw new IllegalArgumentException("empty id");
            return new UserId(value);
        }
    }

    record UserIdHolder(UserId id, String name) {}

    record ValidatedUser(String name, int age) {
        @JqCreator
        public static ValidatedUser create(@JqName("user_name") String name, @JqName("age") int age) {
            if (age < 0) throw new IllegalArgumentException("negative age");
            return new ValidatedUser(name, age);
        }
    }

    static class NoDefault {
        final String name;

        @JqCreator
        public NoDefault(@JqName("name") String name) { this.name = name; }

        public String getName() { return name; }
    }

    static class Slug {
        final String value;

        private Slug(String value) { this.value = value; }

        @JqCreator
        public static Slug from(String value) { return new Slug(value); }

        public String getValue() { return value; }
    }

    static class Greeter {
        final String first;
        final String last;
        String nickname;

        @JqCreator
        public Greeter(@JqName("first") String first, @JqName("last") String last) {
            this.first = first;
            this.last = last;
        }

        public String getFirst() { return first; }
        public String getLast() { return last; }
        public String getNickname() { return nickname; }
        public void setNickname(String nickname) { this.nickname = nickname; }
    }

    static class ZeroArg {
        @JqCreator
        public static ZeroArg create() { return new ZeroArg(); }
    }

    static class InstanceCreator {
        @JqCreator
        public InstanceCreator make(String name) { return new InstanceCreator(); }
    }

    @Test
    void creatorDelegatingBindsScalar() {
        assertEquals(new UserId("abc"), mapper.fromJqValue(JqValues.parse("\"abc\""), UserId.class));
        assertEquals(new UserId("abc"), mapper.fromJqValue(
                JqValues.parse("{\"id\":\"abc\",\"name\":\"x\"}"), UserIdHolder.class).id());
    }

    @Test
    void creatorDelegatingRejectsObjectAndNull() {
        assertThrows(ShapeMismatchException.class, () -> mapper.fromJqValue(
                JqValues.parse("{\"value\":\"abc\"}"), UserId.class));
        assertNull(mapper.fromJqValue(JqNull.NULL, UserId.class));
    }

    @Test
    void creatorFailureWraps() {
        JqMapperException e = assertThrows(JqMapperException.class, () ->
                mapper.fromJqValue(JqValues.parse("\"\""), UserId.class));
        assertTrue(e.getMessage().contains("UserId"), e.getMessage());
    }

    @Test
    void creatorPropertiesBindRenamedAndDefaulted() {
        ValidatedUser u = mapper.fromJqValue(
                JqValues.parse("{\"user_name\":\"Ann\",\"age\":3}"), ValidatedUser.class);
        assertEquals(new ValidatedUser("Ann", 3), u);
        // Missing params stay lenient (same as canonical construction)
        ValidatedUser d = mapper.fromJqValue(JqValues.parse("{\"user_name\":\"Bo\"}"), ValidatedUser.class);
        assertEquals(new ValidatedUser("Bo", 0), d);
        // Creator validation runs
        assertThrows(JqMapperException.class, () -> mapper.fromJqValue(
                JqValues.parse("{\"user_name\":\"Cy\",\"age\":-1}"), ValidatedUser.class));
    }

    @Test
    void creatorPropertiesSerializeViaAccessors() {
        assertEquals("{\"name\":\"Ann\",\"age\":3}",
                mapper.toJqValue(new ValidatedUser("Ann", 3)).toJsonString());
    }

    @Test
    void creatorConstructorBindsPojoWithoutNoArg() {
        NoDefault n = mapper.fromJqValue(JqValues.parse("{\"name\":\"x\"}"), NoDefault.class);
        assertEquals("x", n.name);
        assertEquals("{\"name\":\"x\"}", mapper.toJqValue(n).toJsonString());
    }

    @Test
    void creatorDelegatingBindsPojoScalar() {
        Slug s = mapper.fromJqValue(JqValues.parse("\"hi\""), Slug.class);
        assertEquals("hi", s.value);
        assertEquals("{\"value\":\"hi\"}", mapper.toJqValue(s).toJsonString());
    }

    @Test
    void creatorPropertiesThenSettersOnPojo() {
        Greeter g = mapper.fromJqValue(
                JqValues.parse("{\"first\":\"A\",\"last\":\"B\",\"nickname\":\"Al\"}"), Greeter.class);
        assertEquals("A", g.first);
        assertEquals("B", g.last);
        assertEquals("Al", g.nickname);
    }

    @Test
    void creatorInvalidFailsFast() {
        JqMapperException zero = assertThrows(JqMapperException.class, () ->
                mapper.fromJqValue(JqValues.parse("{\"a\":1}"), ZeroArg.class));
        assertFalse(zero instanceof ShapeMismatchException, zero.getClass().getName());
        JqMapperException instance = assertThrows(JqMapperException.class, () ->
                mapper.fromJqValue(JqValues.parse("\"x\""), InstanceCreator.class));
        assertFalse(instance instanceof ShapeMismatchException, instance.getClass().getName());
    }

    // ---- @JqBuilder (Lombok and Jackson styles) ----

    @JqBuilder(value = LombokUser.Builder.class, factory = "builder", prefix = "")
    static class LombokUser {
        final String name;
        final int age;

        private LombokUser(String name, int age) { this.name = name; this.age = age; }

        public String getName() { return name; }
        public int getAge() { return age; }

        public static Builder builder() { return new Builder(); }

        public static class Builder {
            private String name;
            private int age = 18;

            public Builder name(String name) { this.name = name; return this; }
            public Builder age(int age) { this.age = age; return this; }
            public LombokUser build() { return new LombokUser(name, age); }
        }
    }

    @JqBuilder(LegacyUser.LegacyBuilder.class)
    static class LegacyUser {
        private String name;
        private int age;

        public String getName() { return name; }
        public int getAge() { return age; }

        public static class LegacyBuilder {
            private String name;
            private int age;

            public LegacyBuilder() {}

            public LegacyBuilder withName(String name) { this.name = name; return this; }
            public void withAge(int age) { this.age = age; }

            public LegacyUser build() {
                if (name == null) throw new IllegalArgumentException("name required");
                LegacyUser u = new LegacyUser();
                u.name = name;
                u.age = age;
                return u;
            }
        }
    }

    @JqBuilder(value = GapBuilder.class)
    static class GapHost {
        public String name;
        public String missing;
    }

    static class GapBuilder {
        public GapBuilder() {}

        public GapBuilder withName(String name) { return this; }

        public GapHost build() { return new GapHost(); }
    }

    @JqBuilder(value = BothBuilder.class)
    static class BothStrategies {
        @JqCreator
        public static BothStrategies create(String name) { return new BothStrategies(); }
    }

    static class BothBuilder {
        public BothBuilder() {}

        public BothStrategies build() { return new BothStrategies(); }
    }

    @Test
    void builderLombokStyleBinds() {
        LombokUser u = mapper.fromJqValue(
                JqValues.parse("{\"name\":\"Ann\",\"age\":3}"), LombokUser.class);
        assertEquals("Ann", u.name);
        assertEquals(3, u.age);
        // Builder defaults survive missing keys (same leniency as setters)
        LombokUser d = mapper.fromJqValue(JqValues.parse("{\"name\":\"Bo\"}"), LombokUser.class);
        assertEquals("Bo", d.name);
        assertEquals(18, d.age);
        // Ser still uses getters; tree/String/bytes agree
        assertEquals("{\"name\":\"Ann\",\"age\":3}", mapper.toJqValue(u).toJsonString());
        assertEquals(mapper.toJqValue(u).toJsonString(), mapper.toJson(u));
        assertBytesMatch(u);
    }

    @Test
    void builderWithPrefixAndVoidBinderBinds() {
        LegacyUser u = mapper.fromJqValue(
                JqValues.parse("{\"name\":\"Ann\",\"age\":3}"), LegacyUser.class);
        assertEquals("Ann", u.name);
        assertEquals(3, u.age);
    }

    @Test
    void builderValidationWraps() {
        JqMapperException e = assertThrows(JqMapperException.class, () ->
                mapper.fromJqValue(JqValues.parse("{\"age\":3}"), LegacyUser.class));
        assertTrue(e.getMessage().contains("LegacyUser"), e.getMessage());
    }

    @Test
    void builderFieldFailureHasPath() {
        JqMapperException e = assertThrows(JqMapperException.class, () ->
                mapper.fromJqValue(JqValues.parse("{\"name\":\"Ann\",\"age\":\"old\"}"), LegacyUser.class));
        assertEquals(List.of("age"), e.pathSegments());
    }

    @Test
    void builderMissingBinderFailsFast() {
        // GapBuilder has no withMissing binder (message names the gap).
        JqMapperException e = assertThrows(JqMapperException.class, () ->
                mapper.fromJqValue(JqValues.parse("{\"name\":\"a\"}"), GapHost.class));
        assertTrue(e.getMessage().contains("missing"), e.getMessage());
        assertFalse(e instanceof ShapeMismatchException, e.getClass().getName());
    }

    @Test
    void builderCreatorConflictFailsFast() {
        JqMapperException e = assertThrows(JqMapperException.class, () ->
                mapper.fromJqValue(JqValues.parse("{\"name\":\"a\"}"), BothStrategies.class));
        assertFalse(e instanceof ShapeMismatchException, e.getClass().getName());
    }

    @Test
    void builderStrictUnknownFails() {
        JqMapperException e = assertThrows(JqMapperException.class, () -> strictMapper.fromJqValue(
                JqValues.parse("{\"name\":\"Ann\",\"age\":3,\"zzz\":1}"), LegacyUser.class));
        assertTrue(e.getMessage().contains("\"zzz\""), e.getMessage());
    }

    // ---- naming strategies ----

    @Test
    void namingTransforms() {
        assertEquals("metric_name", JqNaming.Strategy.SNAKE_CASE.transform("metricName"));
        assertEquals("http_url", JqNaming.Strategy.SNAKE_CASE.transform("httpURL"));
        assertEquals("already_snake", JqNaming.Strategy.SNAKE_CASE.transform("already_snake"));
        assertEquals("host_paths", JqNaming.Strategy.SNAKE_CASE.transform("host-paths"));
        assertEquals("host-paths", JqNaming.Strategy.KEBAB_CASE.transform("hostPaths"));
        assertEquals("host-paths", JqNaming.Strategy.KEBAB_CASE.transform("host_paths"));
        assertEquals("host.paths", JqNaming.Strategy.LOWER_DOT_CASE.transform("hostPaths"));
        assertEquals("METRIC_NAME", JqNaming.Strategy.UPPER_SNAKE_CASE.transform("metricName"));
        assertEquals("url", JqNaming.Strategy.LOWER_CAMEL_CASE.transform("URL"));
        assertEquals("urlValue", JqNaming.Strategy.LOWER_CAMEL_CASE.transform("URLValue"));
        assertEquals("name", JqNaming.Strategy.LOWER_CAMEL_CASE.transform("Name"));
        assertEquals("name", JqNaming.Strategy.LOWER_CAMEL_CASE.transform("name"));
        assertEquals("Name", JqNaming.Strategy.UPPER_CAMEL_CASE.transform("name"));
        assertEquals("HostPaths", JqNaming.Strategy.UPPER_CAMEL_CASE.transform("host_paths"));
        assertEquals("hostpaths", JqNaming.Strategy.LOWER_CASE.transform("hostPaths"));
        assertEquals("name", JqNaming.Strategy.IDENTITY.transform("name"));
        assertNull(JqNaming.Strategy.SNAKE_CASE.transform(null));
        assertEquals("", JqNaming.Strategy.KEBAB_CASE.transform(""));
    }

    @JqNaming(JqNaming.Strategy.KEBAB_CASE)
    record KebabRecord(String hostPaths, int maxRetries) {}

    @Test
    void namingKebabRecordBinds() {
        KebabRecord r = mapper.fromJqValue(
                JqValues.parse("{\"host-paths\":\"a\",\"max-retries\":3}"), KebabRecord.class);
        assertEquals("a", r.hostPaths());
        assertEquals(3, r.maxRetries());
        assertEquals("{\"host-paths\":\"a\",\"max-retries\":3}",
                mapper.toJqValue(r).toJsonString());
    }

    // ---- @JqAlias (first present wins, primary serializes) ----

    record Aliased(@JqAlias({"host_name", "hostname"}) String hostName, int port) {}

    @Test
    void aliasBindsEveryName() {
        assertEquals("x", mapper.fromJqValue(
                JqValues.parse("{\"hostName\":\"x\",\"port\":1}"), Aliased.class).hostName());
        assertEquals("x", mapper.fromJqValue(
                JqValues.parse("{\"host_name\":\"x\",\"port\":1}"), Aliased.class).hostName());
        assertEquals("x", mapper.fromJqValue(
                JqValues.parse("{\"hostname\":\"x\",\"port\":1}"), Aliased.class).hostName());
        // Reordered docs fall through positional to the slow path, same result
        assertEquals(2, mapper.fromJqValue(
                JqValues.parse("{\"port\":2,\"hostname\":\"x\"}"), Aliased.class).port());
    }

    @Test
    void aliasExplicitNullStaysNull() {
        assertNull(mapper.fromJqValue(
                JqValues.parse("{\"hostname\":null,\"port\":1}"), Aliased.class).hostName());
    }

    @Test
    void aliasSerializesPrimary() {
        assertEquals("{\"hostName\":\"x\",\"port\":1}",
                mapper.toJqValue(new Aliased("x", 1)).toJsonString());
        assertEquals(mapper.toJqValue(new Aliased("x", 1)).toJsonString(),
                mapper.toJson(new Aliased("x", 1)));
        assertBytesMatch(new Aliased("x", 1));
    }

    @Test
    void aliasProgramConflictFailsFast() {
        record BadAlias(@JqAlias("x") @JqField(".a") String a) {}
        JqMapperException e = assertThrows(JqMapperException.class, () ->
                mapper.fromJqValue(JqValues.parse("{\"a\":\"x\"}"), BadAlias.class));
        assertFalse(e instanceof ShapeMismatchException, e.getClass().getName());
        assertTrue(e.getMessage().contains("@JqAlias"), e.getMessage());
    }

    @Test
    void aliasKnownToStrictAndAnySetter() {
        // Alias keys bind (never "unknown")...
        Aliased a = strictMapper.fromJqValue(
                JqValues.parse("{\"hostname\":\"x\",\"port\":1}"), Aliased.class);
        assertEquals("x", a.hostName());
        // ...while genuinely unknown keys still fail.
        JqMapperException e = assertThrows(JqMapperException.class, () -> strictMapper.fromJqValue(
                JqValues.parse("{\"hostname\":\"x\",\"port\":1,\"zzz\":1}"), Aliased.class));
        assertTrue(e.getMessage().contains("\"zzz\""), e.getMessage());
    }

    // ---- @JqOrder (serialization order only) ----

    @JqOrder({"id", "name"})
    record Ordered(String name, String id, int age) {}

    @JqOrder(alphabetic = true)
    record AlphaOrdered(String zebra, String apple, String mango) {}

    @JqOrder({"zzz"})
    record BadOrder(String a) {}

    @Test
    void orderExplicitFirst() {
        assertEquals("{\"id\":\"1\",\"name\":\"a\",\"age\":3}",
                mapper.toJqValue(new Ordered("a", "1", 3)).toJsonString());
        // Deser unaffected by order (reordered input binds)
        assertEquals(new Ordered("a", "1", 3), mapper.fromJqValue(
                JqValues.parse("{\"age\":3,\"name\":\"a\",\"id\":\"1\"}"), Ordered.class));
    }

    @Test
    void orderAlphabeticTail() {
        assertEquals("{\"apple\":\"a\",\"mango\":\"m\",\"zebra\":\"z\"}",
                mapper.toJqValue(new AlphaOrdered("z", "a", "m")).toJsonString());
    }

    @Test
    void orderUnknownFailsFast() {
        JqMapperException e = assertThrows(JqMapperException.class, () ->
                mapper.fromJqValue(JqValues.parse("{\"a\":\"x\"}"), BadOrder.class));
        assertFalse(e instanceof ShapeMismatchException, e.getClass().getName());
        assertTrue(e.getMessage().contains("zzz"), e.getMessage());
    }

    // ---- @JqRequired (absent keys fail, explicit nulls bind) ----

    record RequiredPoint(@JqRequired int x, int y) {}

    static class RequiredPojo {
        @JqRequired
        public String name;
        public int age;
    }

    record RequiredProgram(@JqRequired @JqField(".a.b") String deep, String other) {}

    record RequiredCreator(String name, int age) {
        @JqCreator
        public static RequiredCreator create(@JqName("user_name") @JqRequired String name,
                                             @JqName("age") int age) {
            return new RequiredCreator(name, age);
        }
    }

    record MootTrap(@JqRequired String name, int age) {
        @JqCreator
        public static MootTrap create(@JqName("user_name") String name, @JqName("age") int age) {
            return new MootTrap(name, age);
        }
    }

    @Test
    void requiredMissingFails() {
        JqMapperException e = assertThrows(JqMapperException.class, () ->
                mapper.fromJqValue(JqValues.parse("{\"y\":2}"), RequiredPoint.class));
        assertTrue(e.getMessage().contains("\"x\""), e.getMessage());
        assertTrue(e.getMessage().contains("RequiredPoint"), e.getMessage());
        assertEquals(List.of("x"), e.pathSegments());
    }

    @Test
    void requiredPresentBindsAndNullBinds() {
        assertEquals(new RequiredPoint(1, 2),
                mapper.fromJqValue(JqValues.parse("{\"x\":1,\"y\":2}"), RequiredPoint.class));
        // Explicit null counts as present (null handling unchanged)
        RequiredPojo n = mapper.fromJqValue(JqValues.parse("{\"name\":null}"), RequiredPojo.class);
        assertNull(n.name);
        assertEquals(0, n.age);
    }

    @Test
    void requiredPojoMissingFails() {
        JqMapperException e = assertThrows(JqMapperException.class, () ->
                mapper.fromJqValue(JqValues.parse("{\"age\":1}"), RequiredPojo.class));
        assertEquals(List.of("name"), e.pathSegments());
    }

    @Test
    void requiredProgramFailsFast() {
        JqMapperException e = assertThrows(JqMapperException.class, () ->
                mapper.fromJqValue(JqValues.parse("{\"other\":\"x\"}"), RequiredProgram.class));
        assertFalse(e instanceof ShapeMismatchException, e.getClass().getName());
        assertTrue(e.getMessage().contains("direct lookup"), e.getMessage());
    }

    @Test
    void requiredCreatorParamFails() {
        // Creator binds by parameter name: the component wire is irrelevant.
        JqMapperException e = assertThrows(JqMapperException.class, () ->
                mapper.fromJqValue(JqValues.parse("{\"age\":3}"), RequiredCreator.class));
        assertEquals(List.of("user_name"), e.pathSegments());
        assertEquals(new RequiredCreator("Ann", 3), mapper.fromJqValue(
                JqValues.parse("{\"user_name\":\"Ann\",\"age\":3}"), RequiredCreator.class));
    }

    @Test
    void requiredMootTrapFailsFast() {
        // Component marker on a wire the creator never binds: explicit error
        // at mapping creation, not a silently unenforced marker.
        JqMapperException e = assertThrows(JqMapperException.class, () ->
                mapper.fromJqValue(JqValues.parse("{\"user_name\":\"Ann\",\"age\":3}"), MootTrap.class));
        assertFalse(e instanceof ShapeMismatchException, e.getClass().getName());
        assertTrue(e.getMessage().contains("never bound"), e.getMessage());
    }

    @Test
    void requiredBuilderFieldFails() {
        JqMapperException e = assertThrows(JqMapperException.class, () ->
                mapper.fromJqValue(JqValues.parse("{\"age\":3}"), ReqBuilderHost.class));
        assertEquals(List.of("name"), e.pathSegments());
    }

    @JqBuilder(value = ReqBuilder.class)
    static class ReqBuilderHost {
        @JqRequired
        public String name;
        public int age;

        public String getName() { return name; }
        public int getAge() { return age; }
    }

    static class ReqBuilder {
        public ReqBuilder() {}

        public ReqBuilder withName(String name) { return this; }
        public ReqBuilder withAge(int age) { return this; }

        public ReqBuilderHost build() { return new ReqBuilderHost(); }
    }

    // ---- java.time (Phase B: ISO strings canonical, epoch accepted for instant-like) ----

    record TimeHolder(java.time.Instant instant, java.time.LocalDate date, java.time.LocalTime time,
            java.time.LocalDateTime dateTime, java.time.OffsetDateTime offsetDateTime,
            java.time.ZonedDateTime zonedDateTime, java.time.Duration duration, java.time.Period period) {}

    private static TimeHolder sampleTimes() {
        return new TimeHolder(java.time.Instant.parse("2026-09-05T12:00:00Z"),
                java.time.LocalDate.parse("2026-10-14"), java.time.LocalTime.parse("12:34:56"),
                java.time.LocalDateTime.parse("2026-10-14T12:34:56"),
                java.time.OffsetDateTime.parse("2026-10-14T12:34:56+02:00"),
                java.time.ZonedDateTime.parse("2026-10-14T12:34:56+02:00"),
                java.time.Duration.parse("PT1H30M"), java.time.Period.parse("P1Y2M3D"));
    }

    @Test
    void timeTypesBindIsoStrings() {
        TimeHolder h = mapper.fromJqValue(JqValues.parse(
                "{\"instant\":\"2026-09-05T12:00:00Z\",\"date\":\"2026-10-14\",\"time\":\"12:34:56\","
                + "\"dateTime\":\"2026-10-14T12:34:56\",\"offsetDateTime\":\"2026-10-14T12:34:56+02:00\","
                + "\"zonedDateTime\":\"2026-10-14T12:34:56+02:00\","
                + "\"duration\":\"PT1H30M\",\"period\":\"P1Y2M3D\"}"), TimeHolder.class);
        assertEquals(sampleTimes(), h);
    }

    @Test
    void instantLikeBindEpochNumbers() {
        TimeHolder h = mapper.fromJqValue(JqValues.parse(
                "{\"instant\":0,\"date\":\"2026-10-14\",\"time\":\"12:34:56\","
                + "\"dateTime\":\"2026-10-14T12:34:56\",\"offsetDateTime\":0,"
                + "\"zonedDateTime\":0,\"duration\":90,\"period\":\"P1Y2M3D\"}"), TimeHolder.class);
        assertEquals(java.time.Instant.EPOCH, h.instant());
        assertEquals(java.time.OffsetDateTime.parse("1970-01-01T00:00:00Z"), h.offsetDateTime());
        assertEquals(java.time.ZonedDateTime.parse("1970-01-01T00:00:00Z"), h.zonedDateTime());
        assertEquals(java.time.Duration.ofSeconds(90), h.duration());
        // Fractional epoch carries nanos
        TimeHolder f = mapper.fromJqValue(JqValues.parse(
                "{\"instant\":0.5,\"date\":\"2026-10-14\",\"time\":\"12:34:56\","
                + "\"dateTime\":\"2026-10-14T12:34:56\",\"offsetDateTime\":\"2026-10-14T12:34:56+02:00\","
                + "\"zonedDateTime\":\"2026-10-14T12:34:56+02:00\","
                + "\"duration\":1.5,\"period\":\"P1Y2M3D\"}"), TimeHolder.class);
        assertEquals(java.time.Instant.ofEpochSecond(0, 500_000_000), f.instant());
        assertEquals(java.time.Duration.ofMillis(1500), f.duration());
    }

    @Test
    void timeTypesRejectWrongShapes() {
        // Numbers are meaningless for calendar types
        assertThrows(ShapeMismatchException.class, () -> mapper.fromJqValue(
                JqValues.parse("{\"date\":20261014}"), TimeHolder.class));
        // Garbage strings mismatch (never leak DateTimeParseException)
        assertThrows(ShapeMismatchException.class, () -> mapper.fromJqValue(
                JqValues.parse("{\"instant\":\"yesterday\"}"), TimeHolder.class));
        assertThrows(ShapeMismatchException.class, () -> mapper.fromJqValue(
                JqValues.parse("{\"duration\":\"an hour\"}"), TimeHolder.class));
        // Booleans and containers mismatch
        assertThrows(ShapeMismatchException.class, () -> mapper.fromJqValue(
                JqValues.parse("{\"instant\":true}"), TimeHolder.class));
        assertThrows(ShapeMismatchException.class, () -> mapper.fromJqValue(
                JqValues.parse("{\"date\":[2026,10,14]}"), TimeHolder.class));
    }

    @Test
    void timeNullsStayNull() {
        TimeHolder h = mapper.fromJqValue(JqValues.parse(
                "{\"instant\":null,\"date\":null,\"time\":null,\"dateTime\":null,"
                + "\"offsetDateTime\":null,\"zonedDateTime\":null,\"duration\":null,\"period\":null}"),
                TimeHolder.class);
        assertEquals(new TimeHolder(null, null, null, null, null, null, null, null), h);
    }

    @Test
    void timeTypesRoundTrip() {
        TimeHolder original = sampleTimes();
        assertEquals(original, mapper.fromJqValue(mapper.toJqValue(original), TimeHolder.class));
        assertEquals(mapper.toJqValue(original).toJsonString(), mapper.toJson(original));
        assertBytesMatch(original);
    }

    @Test
    void timeCheckedHelpersMirror() {
        assertEquals(java.time.Instant.parse("2026-09-05T12:00:00Z"),
                GeneratedMapping.asInstantChecked(JqString.of("2026-09-05T12:00:00Z")));
        assertEquals(java.time.Instant.EPOCH, GeneratedMapping.asInstantChecked(JqNumber.of(0)));
        assertNull(GeneratedMapping.asInstantChecked(null));
        assertThrows(ShapeMismatchException.class,
                () -> GeneratedMapping.asInstantChecked(JqString.of("yesterday")));
        assertThrows(ShapeMismatchException.class,
                () -> GeneratedMapping.asInstantChecked(JqBoolean.TRUE));
        assertEquals(java.time.LocalDate.parse("2026-10-14"),
                GeneratedMapping.asLocalDateChecked(JqString.of("2026-10-14")));
        assertNull(GeneratedMapping.asLocalDateChecked(null));
        assertThrows(ShapeMismatchException.class,
                () -> GeneratedMapping.asLocalDateChecked(JqNumber.of(20261014)));
    }

    // ---- Arrays and wider collections ----

    record ArrayHolder(String[] names, int[] counts, long[] big, double[] scores, boolean[] flags) {}
    record BoxedArrayHolder(Integer[] nums, java.util.UUID[] ids) {}
    record BigIntArrayHolder(java.math.BigInteger[] values) {}
    record RecordArrayHolder(Address[] addresses) {}
    record LinkedHolder(java.util.LinkedList<String> items) {}
    record CollectionHolder(java.util.Collection<String> items) {}
    record QueueHolder(java.util.Queue<String> items) {}
    record SortedMapHolder(java.util.TreeMap<String, Integer> counts) {}
    record SortedMapIfaceHolder(java.util.SortedMap<String, Integer> counts) {}

    @Test
    void arraysBindNatively() {
        ArrayHolder h = mapper.fromJqValue(JqValues.parse(
                "{\"names\":[\"a\",\"b\"],\"counts\":[1,2,3],\"big\":[9223372036854775807],"
                + "\"scores\":[1.5,2.5],\"flags\":[true,false]}"), ArrayHolder.class);
        assertArrayEquals(new String[]{"a", "b"}, h.names());
        assertArrayEquals(new int[]{1, 2, 3}, h.counts());
        assertArrayEquals(new long[]{9223372036854775807L}, h.big());
        assertArrayEquals(new double[]{1.5, 2.5}, h.scores());
        assertArrayEquals(new boolean[]{true, false}, h.flags());
    }

    @Test
    void arraysRejectNonArraysAndLossy() {
        assertThrows(ShapeMismatchException.class, () -> mapper.fromJqValue(
                JqValues.parse("{\"names\":\"ab\"}"), ArrayHolder.class));
        assertThrows(ShapeMismatchException.class, () -> mapper.fromJqValue(
                JqValues.parse("{\"counts\":[1.5]}"), ArrayHolder.class));
    }

    @Test
    void arrayNullStaysNull() {
        ArrayHolder h = mapper.fromJqValue(JqValues.parse(
                "{\"names\":null,\"counts\":null,\"big\":null,\"scores\":null,\"flags\":null}"),
                ArrayHolder.class);
        assertNull(h.names());
        assertNull(h.counts());
    }

    @Test
    void boxedAndNestedArraysBind() {
        java.util.UUID id = java.util.UUID.randomUUID();
        BoxedArrayHolder h = mapper.fromJqValue(JqValues.parse(
                "{\"nums\":[1,null,3],\"ids\":[\"" + id + "\"]}"), BoxedArrayHolder.class);
        // Null elements follow the same leniency as List<Integer> (nulls stay zero)
        assertArrayEquals(new Integer[]{1, 0, 3}, h.nums());
        assertArrayEquals(new java.util.UUID[]{id}, h.ids());
        BigIntArrayHolder b = mapper.fromJqValue(JqValues.parse(
                "{\"values\":[12345678901234567890123]}"), BigIntArrayHolder.class);
        assertArrayEquals(new java.math.BigInteger[]{new java.math.BigInteger("12345678901234567890123")},
                b.values());
        RecordArrayHolder r = mapper.fromJqValue(JqValues.parse(
                "{\"addresses\":[{\"city\":\"NYC\",\"zip\":\"10001\"}]}"), RecordArrayHolder.class);
        assertEquals(1, r.addresses().length);
        assertEquals("NYC", r.addresses()[0].city());
    }

    @Test
    void arraysRoundTrip() {
        ArrayHolder original = new ArrayHolder(new String[]{"a"}, new int[]{1},
                new long[]{2L}, new double[]{3.0}, new boolean[]{true});
        // Records hold arrays by identity: compare component-wise, not by equals
        ArrayHolder back = mapper.fromJqValue(mapper.toJqValue(original), ArrayHolder.class);
        assertArrayEquals(original.names(), back.names());
        assertArrayEquals(original.counts(), back.counts());
        assertArrayEquals(original.big(), back.big());
        assertArrayEquals(original.scores(), back.scores());
        assertArrayEquals(original.flags(), back.flags());
        assertEquals(mapper.toJqValue(original).toJsonString(), mapper.toJson(original));
        assertBytesMatch(original);
        // Top-level arrays serialize without a mapping
        assertEquals("[1,2,3]", mapper.toJson(new int[]{1, 2, 3}));
        assertEquals("[\"a\",\"b\"]", mapper.toJson(new String[]{"a", "b"}));
    }

    @Test
    void widerCollectionsBind() {
        LinkedHolder l = mapper.fromJqValue(JqValues.parse("{\"items\":[\"a\",\"b\"]}"), LinkedHolder.class);
        assertEquals(new java.util.LinkedList<>(List.of("a", "b")), l.items());
        CollectionHolder c = mapper.fromJqValue(JqValues.parse("{\"items\":[\"a\"]}"), CollectionHolder.class);
        assertEquals(List.of("a"), List.copyOf(c.items()));
        QueueHolder q = mapper.fromJqValue(JqValues.parse("{\"items\":[\"a\",\"b\"]}"), QueueHolder.class);
        assertEquals(List.of("a", "b"), List.copyOf(q.items()));
        SortedMapHolder s = mapper.fromJqValue(
                JqValues.parse("{\"counts\":{\"b\":2,\"a\":1}}"), SortedMapHolder.class);
        assertEquals(new java.util.TreeMap<>(Map.of("b", 2, "a", 1)), s.counts());
        assertEquals(List.of("a", "b"), List.copyOf(s.counts().keySet()));
        SortedMapIfaceHolder si = mapper.fromJqValue(
                JqValues.parse("{\"counts\":{\"a\":1}}"), SortedMapIfaceHolder.class);
        assertEquals(Map.of("a", 1), si.counts());
    }

    @Test
    void widerCollectionsRejectNonArrays() {
        assertThrows(ShapeMismatchException.class, () -> mapper.fromJqValue(
                JqValues.parse("{\"items\":\"ab\"}"), LinkedHolder.class));
        assertThrows(ShapeMismatchException.class, () -> mapper.fromJqValue(
                JqValues.parse("{\"counts\":[1]}"), SortedMapHolder.class));
    }

    // ---- BigInteger / UUID / URI / URL (JDK value types) ----

    record BigIntHolder(java.math.BigInteger v) {}
    record UuidHolder(java.util.UUID id) {}
    record UriHolder(java.net.URI uri) {}
    record UrlHolder(java.net.URL url) {}
    record MixedIds(java.util.UUID id, java.net.URI uri, java.math.BigInteger n) {}
    record OptUuid(java.util.Optional<java.util.UUID> id) {}

    @Test
    void bigIntegerBindsNumbersExactly() {
        assertEquals(java.math.BigInteger.valueOf(42), mapper.fromJqValue(
                JqValues.parse("{\"v\":42}"), BigIntHolder.class).v());
        assertEquals(new java.math.BigInteger("9223372036854775808"), mapper.fromJqValue(
                JqValues.parse("{\"v\":9223372036854775808}"), BigIntHolder.class).v());
        assertEquals(java.math.BigInteger.valueOf(3), mapper.fromJqValue(
                JqValues.parse("{\"v\":3.0}"), BigIntHolder.class).v());
        assertEquals(new java.math.BigInteger("42"), mapper.fromJqValue(
                JqValues.parse("{\"v\":\"42\"}"), BigIntHolder.class).v());
        assertNull(mapper.fromJqValue(JqValues.parse("{\"v\":null}"), BigIntHolder.class).v());
    }

    @Test
    void bigIntegerRejectsLossyShapes() {
        assertThrows(ShapeMismatchException.class, () -> mapper.fromJqValue(
                JqValues.parse("{\"v\":3.5}"), BigIntHolder.class));
        assertThrows(ShapeMismatchException.class, () -> mapper.fromJqValue(
                JqValues.parse("{\"v\":\"abc\"}"), BigIntHolder.class));
        assertThrows(ShapeMismatchException.class, () -> mapper.fromJqValue(
                JqValues.parse("{\"v\":true}"), BigIntHolder.class));
        assertThrows(ShapeMismatchException.class, () -> mapper.fromJqValue(
                JqValues.parse("{\"v\":[1]}"), BigIntHolder.class));
    }

    @Test
    void uuidBindsStrings() {
        java.util.UUID id = java.util.UUID.randomUUID();
        assertEquals(id, mapper.fromJqValue(
                JqValues.parse("{\"id\":\"" + id + "\"}"), UuidHolder.class).id());
        assertNull(mapper.fromJqValue(JqValues.parse("{\"id\":null}"), UuidHolder.class).id());
        assertThrows(ShapeMismatchException.class, () -> mapper.fromJqValue(
                JqValues.parse("{\"id\":\"not-a-uuid\"}"), UuidHolder.class));
        assertThrows(ShapeMismatchException.class, () -> mapper.fromJqValue(
                JqValues.parse("{\"id\":42}"), UuidHolder.class));
        assertThrows(ShapeMismatchException.class, () -> mapper.fromJqValue(
                JqValues.parse("{\"id\":true}"), UuidHolder.class));
    }

    @Test
    void uriBindsStrings() throws Exception {
        assertEquals(new java.net.URI("https://example.com/x?q=1"), mapper.fromJqValue(
                JqValues.parse("{\"uri\":\"https://example.com/x?q=1\"}"), UriHolder.class).uri());
        assertEquals(new java.net.URI("path/relative"), mapper.fromJqValue(
                JqValues.parse("{\"uri\":\"path/relative\"}"), UriHolder.class).uri());
        assertNull(mapper.fromJqValue(JqValues.parse("{\"uri\":null}"), UriHolder.class).uri());
        assertThrows(ShapeMismatchException.class, () -> mapper.fromJqValue(
                JqValues.parse("{\"uri\":\"http://exa mple.com\"}"), UriHolder.class));
        assertThrows(ShapeMismatchException.class, () -> mapper.fromJqValue(
                JqValues.parse("{\"uri\":42}"), UriHolder.class));
    }

    @Test
    void urlBindsAbsoluteStrings() throws Exception {
        assertEquals(new java.net.URI("https://example.com/").toURL(), mapper.fromJqValue(
                JqValues.parse("{\"url\":\"https://example.com/\"}"), UrlHolder.class).url());
        assertNull(mapper.fromJqValue(JqValues.parse("{\"url\":null}"), UrlHolder.class).url());
        assertThrows(ShapeMismatchException.class, () -> mapper.fromJqValue(
                JqValues.parse("{\"url\":\"path/relative\"}"), UrlHolder.class));
        assertThrows(ShapeMismatchException.class, () -> mapper.fromJqValue(
                JqValues.parse("{\"url\":\"not a url\"}"), UrlHolder.class));
    }

    @Test
    void jdkValueTypesRoundTrip() throws Exception {
        MixedIds original = new MixedIds(java.util.UUID.randomUUID(),
                new java.net.URI("https://example.com/a"), new java.math.BigInteger("12345678901234567890123"));
        assertEquals(original, mapper.fromJqValue(mapper.toJqValue(original), MixedIds.class));
        assertEquals(mapper.toJqValue(original).toJsonString(), mapper.toJson(original));
        assertBytesMatch(original);
    }

    @Test
    void optionalUuidBinds() {
        java.util.UUID id = java.util.UUID.randomUUID();
        assertEquals(java.util.Optional.of(id), mapper.fromJqValue(
                JqValues.parse("{\"id\":\"" + id + "\"}"), OptUuid.class).id());
        assertEquals(java.util.Optional.empty(), mapper.fromJqValue(
                JqValues.parse("{\"id\":null}"), OptUuid.class).id());
    }

    @Test
    void jdkCheckedHelpersMirror() {
        assertEquals(new java.math.BigInteger("42"),
                GeneratedMapping.asBigIntegerChecked(JqString.of("42")));
        assertNull(GeneratedMapping.asBigIntegerChecked(null));
        assertThrows(ShapeMismatchException.class,
                () -> GeneratedMapping.asBigIntegerChecked(JqString.of("abc")));
        assertThrows(ShapeMismatchException.class,
                () -> GeneratedMapping.asBigIntegerChecked(JqBoolean.TRUE));
        java.util.UUID id = java.util.UUID.randomUUID();
        assertEquals(id, GeneratedMapping.asUuidChecked(JqString.of(id.toString())));
        assertNull(GeneratedMapping.asUuidChecked(null));
        assertThrows(ShapeMismatchException.class,
                () -> GeneratedMapping.asUuidChecked(JqString.of("nope")));
        assertThrows(ShapeMismatchException.class,
                () -> GeneratedMapping.asUuidChecked(JqNumber.of(42)));
    }

    // ---- Any-setter / any-getter (issue #87) ----

    static class ExtrasPojo {
        private String name;
        @JqIgnore
        private String internal;
        private final Map<String, JqValue> extras = new LinkedHashMap<>();

        public ExtrasPojo() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getInternal() { return internal; }
        public void setInternal(String internal) { this.internal = internal; }

        @JqAnySetter
        public void setExtra(String key, JqValue value) { extras.put(key, value); }

        @JqAnyGetter
        public Map<String, JqValue> getExtras() { return extras; }
    }

    @Test
    void anySetter_capturesUnknownKeys() {
        JqValue json = JqValues.parse("""
                {"name":"t","testProxyTool":{"token":"tpt-fixture"},"count":3}""");
        ExtrasPojo p = mapper.fromJqValue(json, ExtrasPojo.class);
        assertEquals("t", p.getName());
        assertEquals(2, p.getExtras().size());
        assertEquals("tpt-fixture", p.getExtras().get("testProxyTool").getField("token").stringValue());
        assertEquals(3L, p.getExtras().get("count").longValue());
    }

    @Test
    void anySetter_skipsKnownAndIgnoredKeys() {
        JqValue json = JqValues.parse("{\"name\":\"t\",\"internal\":\"x\"}");
        ExtrasPojo p = mapper.fromJqValue(json, ExtrasPojo.class);
        assertEquals("t", p.getName());
        // @JqIgnore fields are never bound...
        assertNull(p.getInternal());
        // ...nor forwarded to extras
        assertTrue(p.getExtras().isEmpty());
    }

    @Test
    void anySetter_forwardsExplicitNull() {
        JqValue json = JqValues.parse("{\"name\":\"t\",\"mystery\":null}");
        ExtrasPojo p = mapper.fromJqValue(json, ExtrasPojo.class);
        assertTrue(p.getExtras().get("mystery").isNull());
    }

    @Test
    void anyGetter_roundTrip() {
        JqValue json = JqValues.parse("{\"name\":\"t\",\"testProxyTool\":{\"token\":\"tpt-fixture\"}}");
        ExtrasPojo p = mapper.fromJqValue(json, ExtrasPojo.class);
        JqValue out = mapper.toJqValue(p);
        assertEquals("t", out.getField("name").stringValue());
        assertEquals("tpt-fixture", out.getField("testProxyTool").getField("token").stringValue());
    }

    @Test
    void anyGetter_backingFieldNotEmitted() {
        // The any-getter method must not ALSO be emitted as a bean property (issue #88.1)
        JqValue json = JqValues.parse("{\"name\":\"t\",\"testProxyTool\":{\"token\":\"tpt-fixture\"}}");
        ExtrasPojo p = mapper.fromJqValue(json, ExtrasPojo.class);
        String out = mapper.toJqValue(p).toJsonString();
        assertFalse(out.contains("\"extras\""), out);
    }

    // ---- Nested plain POJOs (issue #88.3) ----

    static class NestedPlain {
        private String url;

        public NestedPlain() {}

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }

    record WithNestedPojo(String name, NestedPlain nested) {}

    @Test
    void toJqValue_nestedPlainPojoMapped() {
        WithNestedPojo original = new WithNestedPojo("t", new NestedPlain());
        original.nested().setUrl("https://example.com");
        JqValue out = mapper.toJqValue(original);
        assertEquals("https://example.com", out.getField("nested").getField("url").stringValue());
    }

    @Test
    void roundTrip_nestedPlainPojo() {
        WithNestedPojo original = new WithNestedPojo("t", new NestedPlain());
        original.nested().setUrl("https://example.com");
        WithNestedPojo restored = mapper.fromJqValue(mapper.toJqValue(original), WithNestedPojo.class);
        assertEquals("t", restored.name());
        assertEquals("https://example.com", restored.nested().getUrl());
    }

    @Test
    void toJqValue_jdkTypesStillStringified() {
        // JDK types keep the lenient toString fallback on the nested path (no behavior change)
        record WithDate(String name, java.util.Date when) {}
        WithDate original = new WithDate("t", new java.util.Date(0));
        JqValue out = mapper.toJqValue(original);
        assertTrue(out.getField("when").isString(), out.getField("when").getClass().getSimpleName());
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

    // ---- POJO support ----

    static class SimplePojo {
        private String name;
        private int age;
        private boolean active;

        public SimplePojo() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
    }

    @Test
    void fromJqValue_simplePojo() {
        JqValue json = JqValues.parse("{\"name\":\"Alice\",\"age\":30,\"active\":true}");
        SimplePojo p = mapper.fromJqValue(json, SimplePojo.class);
        assertEquals("Alice", p.getName());
        assertEquals(30, p.getAge());
        assertTrue(p.isActive());
    }

    @Test
    void toJqValue_simplePojo() {
        SimplePojo p = new SimplePojo();
        p.setName("Bob");
        p.setAge(25);
        p.setActive(false);
        JqValue result = mapper.toJqValue(p);
        assertInstanceOf(JqObject.class, result);
        assertEquals("Bob", result.getField("name").stringValue());
        assertEquals(25L, result.getField("age").longValue());
        assertFalse(result.getField("active").booleanValue());
    }

    @Test
    void roundTrip_simplePojo() {
        SimplePojo original = new SimplePojo();
        original.setName("Alice");
        original.setAge(30);
        original.setActive(true);
        JqValue json = mapper.toJqValue(original);
        SimplePojo restored = mapper.fromJqValue(json, SimplePojo.class);
        assertEquals(original.getName(), restored.getName());
        assertEquals(original.getAge(), restored.getAge());
        assertEquals(original.isActive(), restored.isActive());
    }

    // ---- POJO field initializers (issue #82) ----

    static class DefaultedPojo {
        private String strategy = "SET";
        private String separator = " ";
        private int retries = 3;

        public DefaultedPojo() {}

        public String getStrategy() { return strategy; }
        public void setStrategy(String strategy) { this.strategy = strategy; }
        public String getSeparator() { return separator; }
        public void setSeparator(String separator) { this.separator = separator; }
        public int getRetries() { return retries; }
        public void setRetries(int retries) { this.retries = retries; }
    }

    @Test
    void fromJqValue_pojoAbsentKeysKeepDefaults() {
        JqValue json = JqValues.parse("{\"strategy\":\"APPEND\"}");
        DefaultedPojo p = mapper.fromJqValue(json, DefaultedPojo.class);
        assertEquals("APPEND", p.getStrategy());
        assertEquals(" ", p.getSeparator());
        assertEquals(3, p.getRetries());
    }

    @Test
    void fromJqValue_pojoExplicitNullOverwritesDefault() {
        JqValue json = JqValues.parse("{\"strategy\":null}");
        DefaultedPojo p = mapper.fromJqValue(json, DefaultedPojo.class);
        assertNull(p.getStrategy());
        assertEquals(" ", p.getSeparator());
        assertEquals(3, p.getRetries());
    }

    // ---- POJO with public fields ----

    static class PublicFieldPojo {
        public String name;
        public int value;

        public PublicFieldPojo() {}
    }

    @Test
    void fromJqValue_publicFieldPojo() {
        JqValue json = JqValues.parse("{\"name\":\"test\",\"value\":42}");
        PublicFieldPojo p = mapper.fromJqValue(json, PublicFieldPojo.class);
        assertEquals("test", p.name);
        assertEquals(42, p.value);
    }

    @Test
    void toJqValue_publicFieldPojo() {
        PublicFieldPojo p = new PublicFieldPojo();
        p.name = "test";
        p.value = 42;
        JqValue result = mapper.toJqValue(p);
        assertEquals("test", result.getField("name").stringValue());
        assertEquals(42L, result.getField("value").longValue());
    }

    // ---- POJO with @JqIgnore ----

    static class PojoWithIgnore {
        private String name;
        @JqIgnore
        private String secret;
        private int value;

        public PojoWithIgnore() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public int getValue() { return value; }
        public void setValue(int value) { this.value = value; }
    }

    @Test
    void fromJqValue_pojoWithIgnore() {
        JqValue json = JqValues.parse("{\"name\":\"Alice\",\"secret\":\"hidden\",\"value\":42}");
        PojoWithIgnore p = mapper.fromJqValue(json, PojoWithIgnore.class);
        assertEquals("Alice", p.getName());
        assertNull(p.getSecret()); // ignored field should be null
        assertEquals(42, p.getValue());
    }

    @Test
    void toJqValue_pojoWithIgnore() {
        PojoWithIgnore p = new PojoWithIgnore();
        p.setName("Alice");
        p.setSecret("hidden");
        p.setValue(42);
        JqValue result = mapper.toJqValue(p);
        assertEquals("Alice", result.getField("name").stringValue());
        assertEquals(42L, result.getField("value").longValue());
        // Secret should NOT be in the output
        assertTrue(result.getField("secret") instanceof JqNull);
    }

    // ---- POJO without no-arg constructor ----

    static class NoDefaultConstructor {
        private final String name;
        NoDefaultConstructor(String name) { this.name = name; }
        public String getName() { return name; }
    }

    @Test
    void fromJqValue_pojoNoConstructor_throws() {
        JqValue json = JqValues.parse("{\"name\":\"test\"}");
        assertThrows(JqMapperException.class, () ->
                mapper.fromJqValue(json, NoDefaultConstructor.class));
    }

    // ---- Nested POJO ----

    // ---- POJO with boolean isX() getter ----

    static class BooleanPojo {
        private boolean enabled;
        private String label;

        public BooleanPojo() {}

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
    }

    @Test
    void fromJqValue_booleanIsGetter() {
        JqValue json = JqValues.parse("{\"enabled\":true,\"label\":\"test\"}");
        BooleanPojo p = mapper.fromJqValue(json, BooleanPojo.class);
        assertTrue(p.isEnabled());
        assertEquals("test", p.getLabel());
    }

    // ---- @JqInclude tests ----

    @JqInclude(JqInclude.Include.NON_NULL)
    record NonNullRecord(String data, String error, String debug) {}

    @Test
    void toJqValue_nonNullRecord_excludesNulls() {
        JqValue result = mapper.toJqValue(new NonNullRecord("hello", null, null));
        String json = result.toJsonString();
        assertTrue(json.contains("\"data\""), "Should include non-null data: " + json);
        assertFalse(json.contains("\"error\""), "Should exclude null error: " + json);
        assertFalse(json.contains("\"debug\""), "Should exclude null debug: " + json);
    }

    @Test
    void toJqValue_nonNullRecord_includesAllWhenNoNulls() {
        JqValue result = mapper.toJqValue(new NonNullRecord("hello", "oops", "trace"));
        String json = result.toJsonString();
        assertTrue(json.contains("\"data\""), json);
        assertTrue(json.contains("\"error\""), json);
        assertTrue(json.contains("\"debug\""), json);
    }

    @JqInclude(JqInclude.Include.NON_NULL)
    record NonNullWithOverride(
            String name,
            @JqInclude(JqInclude.Include.ALWAYS) String status // always included, even if null
    ) {}

    @Test
    void toJqValue_nonNullWithFieldOverride() {
        JqValue result = mapper.toJqValue(new NonNullWithOverride(null, null));
        String json = result.toJsonString();
        assertFalse(json.contains("\"name\""), "name should be excluded (NON_NULL): " + json);
        assertTrue(json.contains("\"status\""), "status should be included (ALWAYS override): " + json);
    }

    @JqInclude(JqInclude.Include.NON_EMPTY)
    record NonEmptyRecord(String name, String value, List<String> items) {}

    @Test
    void toJqValue_nonEmpty_excludesEmptyStringsAndCollections() {
        JqValue result = mapper.toJqValue(new NonEmptyRecord("Alice", "", List.of()));
        String json = result.toJsonString();
        assertTrue(json.contains("\"name\""), "Non-empty name should be included: " + json);
        assertFalse(json.contains("\"value\""), "Empty string should be excluded: " + json);
        assertFalse(json.contains("\"items\""), "Empty list should be excluded: " + json);
    }

    @JqInclude(JqInclude.Include.NON_DEFAULT)
    record NonDefaultRecord(String name, int count, boolean active, double score) {}

    @Test
    void toJqValue_nonDefault_excludesDefaults() {
        JqValue result = mapper.toJqValue(new NonDefaultRecord(null, 0, false, 0.0));
        String json = result.toJsonString();
        assertEquals("{}", json, "All defaults should be excluded");
    }

    @Test
    void toJqValue_nonDefault_includesNonDefaults() {
        JqValue result = mapper.toJqValue(new NonDefaultRecord("Alice", 5, true, 3.14));
        String json = result.toJsonString();
        assertTrue(json.contains("\"name\""), json);
        assertTrue(json.contains("\"count\""), json);
        assertTrue(json.contains("\"active\""), json);
        assertTrue(json.contains("\"score\""), json);
    }

    // ---- @JqInclude on POJO ----

    @JqInclude(JqInclude.Include.NON_NULL)
    static class NonNullPojo {
        private String name;
        private String optional;

        public NonNullPojo() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getOptional() { return optional; }
        public void setOptional(String optional) { this.optional = optional; }
    }

    @Test
    void toJqValue_nonNullPojo() {
        NonNullPojo p = new NonNullPojo();
        p.setName("Alice");
        // optional left null
        JqValue result = mapper.toJqValue(p);
        String json = result.toJsonString();
        assertTrue(json.contains("\"name\""), "Non-null name should be included: " + json);
        assertFalse(json.contains("\"optional\""), "Null optional should be excluded: " + json);
    }

    // ---- @JqNaming tests ----

    @JqNaming(JqNaming.Strategy.SNAKE_CASE)
    record SnakeCaseRecord(String userName, int userAge, boolean isActive) {}

    @Test
    void fromJqValue_snakeCaseRecord() {
        JqValue json = JqValues.parse("{\"user_name\":\"Alice\",\"user_age\":30,\"is_active\":true}");
        SnakeCaseRecord r = mapper.fromJqValue(json, SnakeCaseRecord.class);
        assertEquals("Alice", r.userName());
        assertEquals(30, r.userAge());
        assertTrue(r.isActive());
    }

    @Test
    void toJqValue_snakeCaseRecord() {
        JqValue result = mapper.toJqValue(new SnakeCaseRecord("Bob", 25, false));
        String json = result.toJsonString();
        assertTrue(json.contains("\"user_name\""), "Should use snake_case key: " + json);
        assertTrue(json.contains("\"user_age\""), "Should use snake_case key: " + json);
        assertTrue(json.contains("\"is_active\""), "Should use snake_case key: " + json);
        assertFalse(json.contains("\"userName\""), "Should not use camelCase: " + json);
    }

    @Test
    void roundTrip_snakeCaseRecord() {
        SnakeCaseRecord original = new SnakeCaseRecord("Alice", 30, true);
        JqValue json = mapper.toJqValue(original);
        SnakeCaseRecord restored = mapper.fromJqValue(json, SnakeCaseRecord.class);
        assertEquals(original, restored);
    }

    @JqNaming(JqNaming.Strategy.SNAKE_CASE)
    record SnakeCaseWithOverride(
            String firstName,
            @JqField(".custom_key") String customField
    ) {}

    @Test
    void fromJqValue_snakeCaseWithFieldOverride() {
        JqValue json = JqValues.parse("{\"first_name\":\"Alice\",\"custom_key\":\"value\"}");
        SnakeCaseWithOverride r = mapper.fromJqValue(json, SnakeCaseWithOverride.class);
        assertEquals("Alice", r.firstName());
        assertEquals("value", r.customField());
    }

    @Test
    void toSnakeCase_utility() {
        assertEquals("metric_name", JqNaming.Strategy.toSnakeCase("metricName"));
        assertEquals("is_active", JqNaming.Strategy.toSnakeCase("isActive"));
        assertEquals("http_url", JqNaming.Strategy.toSnakeCase("httpURL"));
        assertEquals("already_snake", JqNaming.Strategy.toSnakeCase("already_snake"));
        assertEquals("a", JqNaming.Strategy.toSnakeCase("a"));
        assertEquals("", JqNaming.Strategy.toSnakeCase(""));
        assertEquals("abc", JqNaming.Strategy.toSnakeCase("ABC"));
    }

    // ---- @JqNaming on POJO ----

    @JqNaming(JqNaming.Strategy.SNAKE_CASE)
    static class SnakeCasePojo {
        private String userName;
        private int userAge;

        public SnakeCasePojo() {}

        public String getUserName() { return userName; }
        public void setUserName(String userName) { this.userName = userName; }
        public int getUserAge() { return userAge; }
        public void setUserAge(int userAge) { this.userAge = userAge; }
    }

    @Test
    void fromJqValue_snakeCasePojo() {
        JqValue json = JqValues.parse("{\"user_name\":\"Alice\",\"user_age\":30}");
        SnakeCasePojo p = mapper.fromJqValue(json, SnakeCasePojo.class);
        assertEquals("Alice", p.getUserName());
        assertEquals(30, p.getUserAge());
    }

    @Test
    void toJqValue_snakeCasePojo() {
        SnakeCasePojo p = new SnakeCasePojo();
        p.setUserName("Bob");
        p.setUserAge(25);
        JqValue result = mapper.toJqValue(p);
        String json = result.toJsonString();
        assertTrue(json.contains("\"user_name\""), "Should use snake_case: " + json);
        assertTrue(json.contains("\"user_age\""), "Should use snake_case: " + json);
    }

    // ---- @JqConverter tests ----

    /** Test converter: Instant <-> ISO-8601 string */
    public static class InstantConverter implements ValueConverter<Instant> {
        @Override
        public Instant fromJqValue(JqValue value) {
            return value.isNull() ? null : Instant.parse(value.stringValue());
        }
        @Override
        public JqValue toJqValue(Instant value) {
            return value == null ? JqNull.NULL : JqString.of(value.toString());
        }
    }

    /** Test converter: int[] <-> JSON array of numbers */
    public static class IntArrayConverter implements ValueConverter<int[]> {
        @Override
        public int[] fromJqValue(JqValue value) {
            if (value.isNull() || !(value instanceof JqArray arr)) return new int[0];
            int[] result = new int[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                result[i] = (int) arr.get(i).longValue();
            }
            return result;
        }
        @Override
        public JqValue toJqValue(int[] value) {
            if (value == null) return JqNull.NULL;
            JqValue[] elements = new JqValue[value.length];
            for (int i = 0; i < value.length; i++) {
                elements[i] = JqNumber.of(value[i]);
            }
            return JqArray.of(elements);
        }
    }

    record EventRecord(
            String name,
            @JqConverter(InstantConverter.class) Instant timestamp
    ) {}

    @Test
    void fromJqValue_withConverter() {
        JqValue json = JqValues.parse("{\"name\":\"deploy\",\"timestamp\":\"2026-09-05T12:00:00Z\"}");
        EventRecord r = mapper.fromJqValue(json, EventRecord.class);
        assertEquals("deploy", r.name());
        assertEquals(Instant.parse("2026-09-05T12:00:00Z"), r.timestamp());
    }

    @Test
    void toJqValue_withConverter() {
        Instant ts = Instant.parse("2026-09-05T12:00:00Z");
        JqValue result = mapper.toJqValue(new EventRecord("deploy", ts));
        assertEquals("deploy", result.getField("name").stringValue());
        assertEquals("2026-09-05T12:00:00Z", result.getField("timestamp").stringValue());
    }

    @Test
    void roundTrip_withConverter() {
        EventRecord original = new EventRecord("deploy", Instant.parse("2026-09-05T12:00:00Z"));
        JqValue json = mapper.toJqValue(original);
        EventRecord restored = mapper.fromJqValue(json, EventRecord.class);
        assertEquals(original, restored);
    }

    @Test
    void fromJqValue_converterWithNullValue() {
        JqValue json = JqValues.parse("{\"name\":\"deploy\",\"timestamp\":null}");
        EventRecord r = mapper.fromJqValue(json, EventRecord.class);
        assertEquals("deploy", r.name());
        assertNull(r.timestamp());
    }

    record DataRecord(
            String label,
            @JqConverter(IntArrayConverter.class) int[] values
    ) {}

    @Test
    void roundTrip_intArrayConverter() {
        DataRecord original = new DataRecord("temps", new int[]{72, 75, 68, 80});
        JqValue json = mapper.toJqValue(original);
        assertEquals("[72,75,68,80]", json.getField("values").toJsonString());

        DataRecord restored = mapper.fromJqValue(json, DataRecord.class);
        assertEquals("temps", restored.label());
        assertArrayEquals(new int[]{72, 75, 68, 80}, restored.values());
    }

    // ---- @JqConverter on POJO ----

    static class EventPojo {
        private String name;
        @JqConverter(InstantConverter.class)
        private Instant timestamp;

        public EventPojo() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Instant getTimestamp() { return timestamp; }
        public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    }

    @Test
    void fromJqValue_pojoWithConverter() {
        JqValue json = JqValues.parse("{\"name\":\"deploy\",\"timestamp\":\"2026-09-05T12:00:00Z\"}");
        EventPojo p = mapper.fromJqValue(json, EventPojo.class);
        assertEquals("deploy", p.getName());
        assertEquals(Instant.parse("2026-09-05T12:00:00Z"), p.getTimestamp());
    }

    @Test
    void toJqValue_pojoWithConverter() {
        EventPojo p = new EventPojo();
        p.setName("deploy");
        p.setTimestamp(Instant.parse("2026-09-05T12:00:00Z"));
        JqValue result = mapper.toJqValue(p);
        assertEquals("2026-09-05T12:00:00Z", result.getField("timestamp").stringValue());
    }

    // ---- AnnotationBridge tests ----

    /** Mock bridge that renames "name" to "full_name" and ignores fields named "secret". */
    static class MockBridge implements AnnotationBridge {
        @Override
        public String resolveFieldName(AnnotatedElement element) {
            String fieldName = getFieldName(element);
            if ("name".equals(fieldName)) return "full_name";
            return null;
        }
        @Override
        public boolean isIgnored(AnnotatedElement element) {
            return "secret".equals(getFieldName(element));
        }
        @Override
        public JqInclude.Include resolveInclusion(AnnotatedElement element) {
            return null; // no override
        }
        @Override
        public JqNaming.Strategy resolveNaming(Class<?> type) {
            return null; // no override
        }
        private String getFieldName(AnnotatedElement element) {
            if (element instanceof RecordComponent rc) return rc.getName();
            if (element instanceof Field f) return f.getName();
            return null;
        }
    }

    record BridgeRecord(String name, int age, String secret) {}

    @Test
    void bridge_renamesField() {
        JqMapper bridgeMapper = JqMapper.builder()
                .bridge(new MockBridge())
                .build();
        JqValue json = JqValues.parse("{\"full_name\":\"Alice\",\"age\":30,\"secret\":\"hidden\"}");
        BridgeRecord r = bridgeMapper.fromJqValue(json, BridgeRecord.class);
        assertEquals("Alice", r.name()); // "full_name" -> name via bridge
        assertEquals(30, r.age());
    }

    @Test
    void bridge_ignoresField() {
        JqMapper bridgeMapper = JqMapper.builder()
                .bridge(new MockBridge())
                .build();
        JqValue json = JqValues.parse("{\"full_name\":\"Alice\",\"age\":30,\"secret\":\"hidden\"}");
        BridgeRecord r = bridgeMapper.fromJqValue(json, BridgeRecord.class);
        assertNull(r.secret()); // bridge ignores "secret"
    }

    @Test
    void bridge_serializesWithRenamedKey() {
        JqMapper bridgeMapper = JqMapper.builder()
                .bridge(new MockBridge())
                .build();
        JqValue result = bridgeMapper.toJqValue(new BridgeRecord("Alice", 30, "hidden"));
        String json = result.toJsonString();
        assertTrue(json.contains("\"full_name\""), "Should use bridge-renamed key: " + json);
        assertFalse(json.contains("\"secret\""), "Should exclude bridge-ignored field: " + json);
    }

    @Test
    void bridge_jqAnnotationWinsOverBridge() {
        JqMapper bridgeMapper = JqMapper.builder()
                .bridge(new MockBridge())
                .build();
        // BridgeRecordWithJqField has @JqField on name — should override bridge
        JqValue json = JqValues.parse("{\"custom\":\"Alice\",\"age\":30,\"secret\":\"hidden\"}");
        BridgeRecordWithJqField r = bridgeMapper.fromJqValue(json, BridgeRecordWithJqField.class);
        assertEquals("Alice", r.name()); // @JqField wins over bridge
    }

    record BridgeRecordWithJqField(@JqField(".custom") String name, int age, String secret) {}

    // ---- @JqName ----

    record RenamedRecord(@JqName("host-paths") List<String> hostPaths, String name) {}

    @Test
    void fromJqValue_jqName() {
        JqValue json = JqValues.parse("{\"host-paths\":[\"/a\"],\"name\":\"t\"}");
        RenamedRecord r = mapper.fromJqValue(json, RenamedRecord.class);
        assertEquals(List.of("/a"), r.hostPaths());
        assertEquals("t", r.name());
    }

    @Test
    void toJqValue_jqName() {
        RenamedRecord r = new RenamedRecord(List.of("/a"), "t");
        String out = mapper.toJqValue(r).toJsonString();
        assertTrue(out.contains("\"host-paths\":[\"/a\"]"), out);
        assertFalse(out.contains("hostPaths"), out);
    }

    @Test
    void roundTrip_jqName() {
        RenamedRecord original = new RenamedRecord(List.of("/a", "/b"), "t");
        RenamedRecord restored = mapper.fromJqValue(mapper.toJqValue(original), RenamedRecord.class);
        assertEquals(original, restored);
    }

    @JqNaming(JqNaming.Strategy.SNAKE_CASE)
    record SnakeRenamed(@JqName("host-paths") List<String> hostPaths, String displayName) {}

    @Test
    void jqName_beatsNamingStrategy() {
        // Explicit rename wins; every other field still uses the strategy
        JqValue json = JqValues.parse("{\"host-paths\":[\"/a\"],\"display_name\":\"d\"}");
        SnakeRenamed r = mapper.fromJqValue(json, SnakeRenamed.class);
        assertEquals(List.of("/a"), r.hostPaths());
        assertEquals("d", r.displayName());
        String out = mapper.toJqValue(r).toJsonString();
        assertTrue(out.contains("\"host-paths\""), out);
        assertTrue(out.contains("\"display_name\""), out);
    }

    record NativeBeatsBridge(@JqName("given-name") String name, int age) {}

    @Test
    void jqName_beatsBridge() {
        // MockBridge renames name -> full_name; the native rename wins
        JqMapper bridgeMapper = JqMapper.builder()
                .bridge(new MockBridge())
                .build();
        JqValue json = JqValues.parse("{\"given-name\":\"Alice\",\"age\":30}");
        NativeBeatsBridge r = bridgeMapper.fromJqValue(json, NativeBeatsBridge.class);
        assertEquals("Alice", r.name());
        String out = bridgeMapper.toJqValue(r).toJsonString();
        assertTrue(out.contains("\"given-name\""), out);
        assertFalse(out.contains("full_name"), out);
    }

    record ConflictingNameAndField(@JqField(".custom") @JqName("other") String name) {}

    @Test
    void jqName_conflictsWithJqField() {
        assertThrows(JqMapperException.class,
                () -> mapper.fromJqValue(JqValues.parse("{}"), ConflictingNameAndField.class));
    }

    static class NamedPojo {
        @JqName("host-paths")
        public List<String> hostPaths;
        public String name;

        public NamedPojo() {}
    }

    @Test
    void fromJqValue_jqNamePojo() {
        JqValue json = JqValues.parse("{\"host-paths\":[\"/a\"],\"name\":\"t\"}");
        NamedPojo p = mapper.fromJqValue(json, NamedPojo.class);
        assertEquals(List.of("/a"), p.hostPaths);
        assertEquals("t", p.name);
    }

    @Test
    void toJqValue_jqNamePojo() {
        NamedPojo p = new NamedPojo();
        p.hostPaths = List.of("/a");
        p.name = "t";
        String out = mapper.toJqValue(p).toJsonString();
        assertTrue(out.contains("\"host-paths\":[\"/a\"]"), out);
        assertFalse(out.contains("hostPaths"), out);
    }

    // ---- @JqName on enum constants ----

    enum AccountType {
        @JqName("api-key") API_KEY,
        @JqName("oauth") OAUTH,
        ENTERPRISE
    }

    @Test
    void enum_jqNameWireNames() {
        assertEquals("\"api-key\"", mapper.toJqValue(AccountType.API_KEY).toJsonString());
        assertEquals("\"oauth\"", mapper.toJqValue(AccountType.OAUTH).toJsonString());
        assertEquals("\"ENTERPRISE\"", mapper.toJqValue(AccountType.ENTERPRISE).toJsonString());

        assertEquals(AccountType.API_KEY,
                mapper.fromJqValue(JqValues.parse("\"api-key\""), AccountType.class));
        assertEquals(AccountType.OAUTH,
                mapper.fromJqValue(JqValues.parse("\"oauth\""), AccountType.class));
        assertEquals(AccountType.ENTERPRISE,
                mapper.fromJqValue(JqValues.parse("\"ENTERPRISE\""), AccountType.class));
    }

    @Test
    void enum_jqNameUnknownFails() {
        // Strict mapping: unknown wire values still fail (leniency is a
        // hand-written @JqConverter)
        assertThrows(RuntimeException.class,
                () -> mapper.fromJqValue(JqValues.parse("\"nope\""), AccountType.class));
    }

    static class EnumRenamingBridge implements AnnotationBridge {
        @Override
        public String resolveFieldName(AnnotatedElement element) {
            if (element instanceof Field f && "API_KEY".equals(f.getName())) return "bridge-key";
            return null;
        }
        @Override
        public boolean isIgnored(AnnotatedElement element) { return false; }
        @Override
        public JqInclude.Include resolveInclusion(AnnotatedElement element) { return null; }
        @Override
        public JqNaming.Strategy resolveNaming(Class<?> type) { return null; }
    }

    @Test
    void enum_jqNameBeatsBridge() {
        JqMapper bridgeMapper = JqMapper.builder()
                .bridge(new EnumRenamingBridge())
                .build();
        assertEquals(AccountType.API_KEY,
                bridgeMapper.fromJqValue(JqValues.parse("\"api-key\""), AccountType.class));
        assertEquals("\"api-key\"",
                bridgeMapper.toJqValue(AccountType.API_KEY).toJsonString());
    }

    // ---- @JqAccess ----

    record WriteOnlyRecord(@JqAccess(JqAccess.Access.WRITE_ONLY) String legacy, String name) {}

    @Test
    void access_writeOnlyBindsButNeverSerializes() {
        WriteOnlyRecord r = mapper.fromJqValue(
                JqValues.parse("{\"legacy\":\"old\",\"name\":\"t\"}"), WriteOnlyRecord.class);
        assertEquals("old", r.legacy());
        assertEquals("t", r.name());

        // Absent keys tolerated
        WriteOnlyRecord absent = mapper.fromJqValue(JqValues.parse("{\"name\":\"t\"}"),
                WriteOnlyRecord.class);
        assertNull(absent.legacy());

        String out = mapper.toJqValue(r).toJsonString();
        assertFalse(out.contains("legacy"), out);
        assertTrue(out.contains("\"name\":\"t\""), out);
    }

    record ReadOnlyRecord(@JqAccess(JqAccess.Access.READ_ONLY) String computed, String name) {}

    @Test
    void access_readOnlySerializesButNeverBinds() {
        ReadOnlyRecord r = mapper.fromJqValue(
                JqValues.parse("{\"computed\":\"x\",\"name\":\"t\"}"), ReadOnlyRecord.class);
        assertNull(r.computed());
        assertEquals("t", r.name());

        String out = mapper.toJqValue(new ReadOnlyRecord("c", "t")).toJsonString();
        assertTrue(out.contains("\"computed\":\"c\""), out);
        assertTrue(out.contains("\"name\":\"t\""), out);
    }

    static class AccessPojo {
        @JqAccess(JqAccess.Access.WRITE_ONLY)
        public String legacy;
        public String name;

        public AccessPojo() {}
    }

    @Test
    void access_writeOnlyPojo() {
        AccessPojo p = mapper.fromJqValue(
                JqValues.parse("{\"legacy\":\"old\",\"name\":\"t\"}"), AccessPojo.class);
        assertEquals("old", p.legacy);

        String out = mapper.toJqValue(p).toJsonString();
        assertFalse(out.contains("legacy"), out);
        assertTrue(out.contains("\"name\":\"t\""), out);
    }

    // ---- @JqVisibility ----

    @JqVisibility(getters = JqVisibility.Visibility.NONE,
            isGetters = JqVisibility.Visibility.NONE)
    static class SmartGetterPojo {
        private String apiKey = "stored";
        private boolean active;

        public SmartGetterPojo() {}

        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public void setActive(boolean active) { this.active = active; }

        public String getApiKey() {
            throw new AssertionError("smart getter must not be bound");
        }

        public boolean isActive() {
            throw new AssertionError("smart is-getter must not be bound");
        }
    }

    @Test
    void visibility_suppressedGettersReadFields() {
        SmartGetterPojo p = mapper.fromJqValue(
                JqValues.parse("{\"apiKey\":\"k\",\"active\":true}"), SmartGetterPojo.class);
        // Ser reads the stored fields directly (no throw)
        String out = mapper.toJqValue(p).toJsonString();
        assertTrue(out.contains("\"apiKey\":\"k\""), out);
        assertTrue(out.contains("\"active\":true"), out);

        SmartGetterPojo restored = mapper.fromJqValue(JqValues.parse(out), SmartGetterPojo.class);
        assertEquals(out, mapper.toJqValue(restored).toJsonString());
    }

    // ---- Any-methods from interfaces ----

    interface ExtrasInterface {
        @JqAnySetter
        default void setExtra(String key, JqValue value) { extrasMap().put(key, value); }

        @JqAnyGetter
        default Map<String, JqValue> getExtras() { return extrasMap(); }

        Map<String, JqValue> extrasMap();
    }

    static class InterfaceImpl implements ExtrasInterface {
        public String name;
        private final Map<String, JqValue> extras = new LinkedHashMap<>();

        public InterfaceImpl() {}

        @Override
        public Map<String, JqValue> extrasMap() { return extras; }
    }

    @Test
    void anyMethods_interfaceDefaultsForward() {
        InterfaceImpl p = mapper.fromJqValue(
                JqValues.parse("{\"name\":\"t\",\"mystery\":{\"a\":1}}"), InterfaceImpl.class);
        assertEquals("t", p.name);
        assertEquals(1L, p.extras.get("mystery").getField("a").longValue());

        String out = mapper.toJson(p);
        assertTrue(out.contains("\"mystery\""), out);
        assertFalse(out.contains("\"extras\""), out);
    }

    // ---- Sorted and enum sets ----

    @Test
    void fromJqValue_sortedSetOrdersNaturally() {
        record SortedHolder(java.util.SortedSet<String> tags) {}

        SortedHolder h = mapper.fromJqValue(JqValues.parse("{\"tags\":[\"b\",\"a\",\"b\"]}"),
                SortedHolder.class);
        assertEquals(java.util.TreeSet.class, h.tags().getClass());
        assertEquals(java.util.List.of("a", "b"), new java.util.ArrayList<>(h.tags()));
    }

    enum Level { LOW, HIGH }

    @Test
    void fromJqValue_enumSetBinds() {
        record LevelHolder(java.util.EnumSet<Level> levels) {}

        LevelHolder h = mapper.fromJqValue(JqValues.parse("{\"levels\":[\"HIGH\",\"LOW\",\"HIGH\"]}"),
                LevelHolder.class);
        assertEquals(java.util.EnumSet.of(Level.HIGH, Level.LOW), h.levels());
    }

    @Test
    @SuppressWarnings("rawtypes")
    void fromJqValue_enumSetRejectsNonEnum() {
        record BadSet(java.util.EnumSet tags) {}

        io.hyperfoil.tools.jjq.mapper.JqMapperException e = assertThrows(
                io.hyperfoil.tools.jjq.mapper.JqMapperException.class,
                () -> mapper.fromJqValue(JqValues.parse("{\"tags\":[\"a\"]}"), BadSet.class));
        assertTrue(e.getMessage().contains("EnumSet"), e.getMessage());
    }

    // ---- @JqAdapter ----

    record JqFromBox(String v) {
        static JqFromBox fromWire(JqValue jv) {
            if (jv.isNull()) throw new AssertionError("from must not be called on null");
            return new JqFromBox(jv.asText("?"));
        }

        JqValue toWire() {
            return JqString.of(v);
        }
    }

    record StrFromBox(String v) {
        static StrFromBox fromWire(String wire) {
            if (wire == null) throw new AssertionError("from must not be called on null");
            return new StrFromBox(wire.toUpperCase());
        }

        static String toWire(StrFromBox box) {
            return box.v().toLowerCase();
        }
    }

    record StaticJqBox(String v) {
        static StaticJqBox fromWire(JqValue jv) {
            return new StaticJqBox(jv.asText("?"));
        }

        static JqValue toWire(StaticJqBox box) {
            if (box == null) throw new AssertionError("to must not be called on null");
            return JqString.of(box.v());
        }
    }

    record InstStrBox(String v) {
        static InstStrBox fromWire(JqValue jv) {
            return new InstStrBox(jv.asText("?"));
        }

        String wireValue() {
            return v;
        }
    }

    record Adapted(
            @JqAdapter(from = "fromWire", to = "toWire") JqFromBox a,
            @JqAdapter(from = "fromWire", to = "toWire") StrFromBox b,
            @JqAdapter(from = "fromWire", to = "toWire") StaticJqBox c,
            @JqAdapter(from = "fromWire", to = "wireValue") InstStrBox d) {}

    @Test
    void adapter_allShapesRoundTrip() {
        Adapted original = new Adapted(new JqFromBox("a"), new StrFromBox("B"),
                new StaticJqBox("c"), new InstStrBox("D"));
        JqValue json = mapper.toJqValue(original);
        assertEquals("a", json.getField("a").stringValue());
        assertEquals("b", json.getField("b").stringValue());
        assertEquals("c", json.getField("c").stringValue());
        assertEquals("D", json.getField("d").stringValue());

        Adapted restored = mapper.fromJqValue(json, Adapted.class);
        assertEquals(original, restored);
    }

    record NullableBox(@JqAdapter(from = "fromWire", to = "toWire") JqFromBox box) {}

    @Test
    void adapter_nullInputSkipsFrom() {
        NullableBox r = mapper.fromJqValue(JqValues.parse("{\"box\":null}"), NullableBox.class);
        assertNull(r.box());
    }

    record NullableStaticBox(@JqAdapter(from = "fromWire", to = "toWire") StaticJqBox box) {}

    @Test
    void adapter_nullFieldSkipsTo() {
        JqValue json = mapper.toJqValue(new NullableStaticBox(null));
        assertTrue(json.getField("box").isNull());
    }

    static class MissingType {
        MissingType(String v) {}
    }

    record MissingFrom(@JqAdapter(from = "nope", to = "nope") MissingType f) {}

    @Test
    void adapter_missingMethodFails() {
        JqMapperException e = assertThrows(JqMapperException.class,
                () -> mapper.fromJqValue(JqValues.parse("{}"), MissingFrom.class));
        assertTrue(e.getMessage().contains("nope"), e.getMessage());
    }

    static class BadArityType {
        static BadArityType fromWire(String a, String b) { return new BadArityType(); }
        static String toWire(BadArityType t) { return "x"; }
    }

    record BadArity(@JqAdapter(from = "fromWire", to = "toWire") BadArityType f) {}

    @Test
    void adapter_missignedFromFails() {
        JqMapperException e = assertThrows(JqMapperException.class,
                () -> mapper.fromJqValue(JqValues.parse("{}"), BadArity.class));
        assertTrue(e.getMessage().contains("fromWire"), e.getMessage());
    }

    static class NonStaticFrom {
        NonStaticFrom fromWire(JqValue v) { return new NonStaticFrom(); }
        static String toWire(NonStaticFrom t) { return "x"; }
    }

    record NonStatic(@JqAdapter(from = "fromWire", to = "toWire") NonStaticFrom f) {}

    @Test
    void adapter_nonStaticFromFails() {
        JqMapperException e = assertThrows(JqMapperException.class,
                () -> mapper.fromJqValue(JqValues.parse("{}"), NonStatic.class));
        assertTrue(e.getMessage().contains("fromWire"), e.getMessage());
    }

    static class PassConverter implements ValueConverter<String> {
        @Override
        public String fromJqValue(JqValue value) { return value.stringValue(); }

        @Override
        public JqValue toJqValue(String value) { return JqString.of(value); }
    }

    record AdapterConflict(
            @JqAdapter(from = "fromWire", to = "toWire") @JqConverter(PassConverter.class) String f) {}

    @Test
    void adapter_conflictsWithConverter() {
        JqMapperException e = assertThrows(JqMapperException.class,
                () -> mapper.fromJqValue(JqValues.parse("{}"), AdapterConflict.class));
        assertTrue(e.getMessage().contains("conflict"), e.getMessage());
    }

    // ---- @JqEnum lenient parsing ----

    @JqEnum(normalize = {JqEnum.Normalize.TRIM, JqEnum.Normalize.LOWERCASE,
            JqEnum.Normalize.SEPARATOR_FOLD},
            onUnknown = JqEnum.OnUnknown.NULL)
    enum LenientAccount {
        @JqName("api-key") API_KEY,
        @JqName("oauth") OAUTH
    }

    @Test
    void enum_lenientNormalizes() {
        assertEquals(LenientAccount.API_KEY,
                mapper.fromJqValue(JqValues.parse("\"api-key\""), LenientAccount.class));
        assertEquals(LenientAccount.API_KEY,
                mapper.fromJqValue(JqValues.parse("\"  API_KEY  \""), LenientAccount.class));
        assertEquals(LenientAccount.OAUTH,
                mapper.fromJqValue(JqValues.parse("\"OAuth\""), LenientAccount.class));
    }

    @Test
    void enum_lenientUnknownBindsNull() {
        assertNull(mapper.fromJqValue(JqValues.parse("\"typo\""), LenientAccount.class));
    }

    @JqEnum(onUnknown = JqEnum.OnUnknown.FAIL)
    enum StrictAccount {
        @JqName("api-key") API_KEY
    }

    @Test
    void enum_failUnknownThrows() {
        assertEquals(StrictAccount.API_KEY,
                mapper.fromJqValue(JqValues.parse("\"api-key\""), StrictAccount.class));
        assertThrows(RuntimeException.class,
                () -> mapper.fromJqValue(JqValues.parse("\"typo\""), StrictAccount.class));
    }

    @JqEnum(normalize = {JqEnum.Normalize.LOWERCASE}, onUnknown = JqEnum.OnUnknown.NULL)
    enum LenientMixed {
        @JqName("api-key") API_KEY,
        ENTERPRISE
    }

    @Test
    void enum_exactNameFallbackSurvives() {
        // Unnamed constants keep exact binding; normalization does not invent names
        assertEquals(LenientMixed.ENTERPRISE,
                mapper.fromJqValue(JqValues.parse("\"ENTERPRISE\""), LenientMixed.class));
        assertNull(mapper.fromJqValue(JqValues.parse("\"enterprise\""), LenientMixed.class));
    }

    @JqEnum(normalize = {JqEnum.Normalize.SEPARATOR_FOLD}, onUnknown = JqEnum.OnUnknown.NULL)
    enum Colliding {
        @JqName("a-b") AB,
        @JqName("a_b") CD
    }

    @Test
    void enum_duplicateNormalizedWireFails() {
        JqMapperException e = assertThrows(JqMapperException.class,
                () -> mapper.fromJqValue(JqValues.parse("\"a-b\""), Colliding.class));
        assertTrue(e.getMessage().contains("a-b"), e.getMessage());
    }

    enum CreatorLenient {
        API_KEY, OAUTH;

        public static CreatorLenient fromWire(String wire) {
            if ("api-key".equals(wire)) return API_KEY;
            return null;
        }
    }

    @JqEnum(onUnknown = JqEnum.OnUnknown.FAIL)
    enum CreatorNative {
        API_KEY, OAUTH;

        public static CreatorNative fromWire(String wire) {
            if ("api-key".equals(wire)) return API_KEY;
            return null;
        }
    }

    static class CreatorBridge implements AnnotationBridge {
        @Override
        public String resolveFieldName(AnnotatedElement element) { return null; }
        @Override
        public boolean isIgnored(AnnotatedElement element) { return false; }
        @Override
        public JqInclude.Include resolveInclusion(AnnotatedElement element) { return null; }
        @Override
        public JqNaming.Strategy resolveNaming(Class<?> type) { return null; }
        @Override
        public java.lang.reflect.Executable resolveJsonCreator(Class<?> type) {
            try {
                if (type == CreatorLenient.class) {
                    return CreatorLenient.class.getMethod("fromWire", String.class);
                }
                if (type == CreatorNative.class) {
                    return CreatorNative.class.getMethod("fromWire", String.class);
                }
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
            return null;
        }
    }

    @Test
    void enum_creatorHonoredWithoutJqEnum() {
        JqMapper bridgeMapper = JqMapper.builder()
                .bridge(new CreatorBridge())
                .build();
        assertEquals(CreatorLenient.API_KEY,
                bridgeMapper.fromJqValue(JqValues.parse("\"api-key\""), CreatorLenient.class));
        assertNull(bridgeMapper.fromJqValue(JqValues.parse("\"whatever\""), CreatorLenient.class));
    }

    @Test
    void enum_nativeBeatsCreator() {
        // @JqEnum takes over deserialization: the lenient creator is ignored,
        // unknown values fail strictly instead of binding null.
        JqMapper bridgeMapper = JqMapper.builder()
                .bridge(new CreatorBridge())
                .build();
        assertThrows(RuntimeException.class,
                () -> bridgeMapper.fromJqValue(JqValues.parse("\"whatever\""), CreatorNative.class));
    }

    // ---- Bridge on POJO ----

    static class BridgePojo {
        private String name;
        private int age;
        private String secret;

        public BridgePojo() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
    }

    @Test
    void bridge_worksWithPojo() {
        JqMapper bridgeMapper = JqMapper.builder()
                .bridge(new MockBridge())
                .build();
        JqValue json = JqValues.parse("{\"full_name\":\"Bob\",\"age\":25,\"secret\":\"hidden\"}");
        BridgePojo p = bridgeMapper.fromJqValue(json, BridgePojo.class);
        assertEquals("Bob", p.getName());
        assertEquals(25, p.getAge());
        assertNull(p.getSecret()); // bridge ignores "secret"
    }

    // ========================================================================
    //  Polymorphic @JqConverter spike — mirrors incus-spawn's StdDeserializer patterns
    // ========================================================================

    // ---- Pattern 1: ToolRef — string-or-single-key-map (incus-spawn ToolDef.ToolRef) ----

    record ToolRefSpike(String name, Map<String, String> params) {
        ToolRefSpike(String name) { this(name, Map.of()); }
    }

    /** Converter: array element is either "name" (string) or {"name": {param: val}} (single-key map). */
    public static class ToolRefListConverter implements ValueConverter<List<ToolRefSpike>> {
        @Override
        public List<ToolRefSpike> fromJqValue(JqValue value) {
            if (value.isNull() || !(value instanceof JqArray arr)) return List.of();
            var result = new java.util.ArrayList<ToolRefSpike>();
            for (JqValue elem : arr) {
                if (elem.isString()) {
                    result.add(new ToolRefSpike(elem.stringValue()));
                } else if (elem.isObject()) {
                    var obj = (JqObject) elem;
                    for (var entry : obj.entries()) {
                        var params = new java.util.LinkedHashMap<String, String>();
                        if (entry.getValue().isObject()) {
                            entry.getValue().objectValue().forEach((k, v) -> params.put(k, v.asString("")));
                        }
                        result.add(new ToolRefSpike(entry.getKey(), params));
                    }
                }
            }
            return result;
        }

        @Override
        public JqValue toJqValue(List<ToolRefSpike> refs) {
            if (refs == null) return JqNull.NULL;
            var arr = JqArray.arrayBuilder(refs.size());
            for (var ref : refs) {
                if (ref.params().isEmpty()) {
                    arr.add(ref.name());
                } else {
                    var paramsBuilder = JqObject.builder(ref.params().size());
                    ref.params().forEach(paramsBuilder::put);
                    arr.add(JqObject.of(ref.name(), paramsBuilder.build()));
                }
            }
            return arr.build();
        }
    }

    record ToolConfig(String name, @JqConverter(ToolRefListConverter.class) List<ToolRefSpike> requires) {}

    @Test
    void converter_toolRefStringOrMap() {
        JqValue json = JqValues.parse("""
                {"name":"myTool","requires":["maven-3",{"idea-backend":{"memory":"8g"}}]}
                """);
        ToolConfig config = mapper.fromJqValue(json, ToolConfig.class);
        assertEquals("myTool", config.name());
        assertEquals(2, config.requires().size());
        assertEquals("maven-3", config.requires().get(0).name());
        assertEquals(Map.of(), config.requires().get(0).params());
        assertEquals("idea-backend", config.requires().get(1).name());
        assertEquals("8g", config.requires().get(1).params().get("memory"));
    }

    @Test
    void converter_toolRefRoundTrip() {
        var original = new ToolConfig("test", List.of(
                new ToolRefSpike("maven-3"),
                new ToolRefSpike("idea", Map.of("memory", "8g", "plugins", "true"))));
        JqValue json = mapper.toJqValue(original);
        ToolConfig restored = mapper.fromJqValue(json, ToolConfig.class);
        assertEquals(original.name(), restored.name());
        assertEquals(original.requires().size(), restored.requires().size());
        assertEquals("maven-3", restored.requires().get(0).name());
        assertEquals("8g", restored.requires().get(1).params().get("memory"));
    }

    // ---- Pattern 2: EnvEntry — string-or-object (incus-spawn EnvEntry) ----

    record EnvEntrySpike(String name, String value, String strategy) {
        static EnvEntrySpike raw(String line) { return new EnvEntrySpike(null, line, "raw"); }
        static EnvEntrySpike set(String name, String value) { return new EnvEntrySpike(name, value, "set"); }
    }

    public static class EnvEntryListConverter implements ValueConverter<List<EnvEntrySpike>> {
        @Override
        public List<EnvEntrySpike> fromJqValue(JqValue value) {
            if (value.isNull() || !(value instanceof JqArray arr)) return List.of();
            var result = new java.util.ArrayList<EnvEntrySpike>();
            for (JqValue elem : arr) {
                if (elem.isString()) {
                    result.add(EnvEntrySpike.raw(elem.stringValue()));
                } else if (elem.isObject()) {
                    result.add(new EnvEntrySpike(
                            elem.getField("name").asString(null),
                            elem.getField("value").asString(null),
                            elem.getField("strategy").asString("set")));
                }
            }
            return result;
        }

        @Override
        public JqValue toJqValue(List<EnvEntrySpike> entries) {
            if (entries == null) return JqNull.NULL;
            var arr = JqArray.arrayBuilder(entries.size());
            for (var e : entries) {
                if ("raw".equals(e.strategy())) {
                    arr.add(e.value());
                } else {
                    arr.add(JqObject.builder(3)
                            .put("name", e.name())
                            .put("value", e.value())
                            .put("strategy", e.strategy())
                            .build());
                }
            }
            return arr.build();
        }
    }

    record ImageConfig(String name, @JqConverter(EnvEntryListConverter.class) List<EnvEntrySpike> env) {}

    @Test
    void converter_envEntryStringOrObject() {
        JqValue json = JqValues.parse("""
                {"name":"myImage","env":["export FOO=bar",{"name":"PATH","value":"/usr/bin","strategy":"prepend"}]}
                """);
        ImageConfig config = mapper.fromJqValue(json, ImageConfig.class);
        assertEquals("myImage", config.name());
        assertEquals(2, config.env().size());
        assertEquals("raw", config.env().get(0).strategy());
        assertEquals("export FOO=bar", config.env().get(0).value());
        assertEquals("PATH", config.env().get(1).name());
        assertEquals("prepend", config.env().get(1).strategy());
    }

    // ---- Pattern 3: SkillsDef — list-or-object (incus-spawn ImageDef.SkillsDef) ----

    record SkillsDefSpike(String repo, List<String> list) {
        SkillsDefSpike(List<String> list) { this(null, list); }
    }

    public static class SkillsDefConverter implements ValueConverter<SkillsDefSpike> {
        @Override
        public SkillsDefSpike fromJqValue(JqValue value) {
            if (value.isNull()) return new SkillsDefSpike(null, List.of());
            if (value instanceof JqArray arr) {
                // List shorthand: skills: [...]
                return new SkillsDefSpike(arr.arrayValue().stream()
                        .map(v -> v.asString("")).toList());
            }
            // Object form: skills: {repo: ..., list: [...]}
            return new SkillsDefSpike(
                    value.getField("repo").asString(null),
                    value.getField("list").isNull() ? List.of()
                            : ((JqArray) value.getField("list")).arrayValue().stream()
                            .map(v -> v.asString("")).toList());
        }

        @Override
        public JqValue toJqValue(SkillsDefSpike skills) {
            if (skills == null) return JqNull.NULL;
            if (skills.repo() == null) {
                return JqArray.of(skills.list().stream().map(JqString::of).toArray(JqValue[]::new));
            }
            return JqObject.builder(2)
                    .put("repo", skills.repo())
                    .putArray("list", arr -> skills.list().forEach(arr::add))
                    .build();
        }
    }

    record SkillsConfig(String name, @JqConverter(SkillsDefConverter.class) SkillsDefSpike skills) {}

    @Test
    void converter_skillsDefListShorthand() {
        JqValue json = JqValues.parse("""
                {"name":"myImage","skills":["security-review","code-review"]}
                """);
        SkillsConfig config = mapper.fromJqValue(json, SkillsConfig.class);
        assertNull(config.skills().repo());
        assertEquals(List.of("security-review", "code-review"), config.skills().list());
    }

    @Test
    void converter_skillsDefObjectForm() {
        JqValue json = JqValues.parse("""
                {"name":"myImage","skills":{"repo":"https://github.com/skills","list":["review","test"]}}
                """);
        SkillsConfig config = mapper.fromJqValue(json, SkillsConfig.class);
        assertEquals("https://github.com/skills", config.skills().repo());
        assertEquals(List.of("review", "test"), config.skills().list());
    }

    @Test
    void converter_skillsDefRoundTrip() {
        // List shorthand round-trip
        var list = new SkillsConfig("img", new SkillsDefSpike(List.of("a", "b")));
        JqValue json = mapper.toJqValue(list);
        assertTrue(json.getField("skills") instanceof JqArray);
        SkillsConfig restored = mapper.fromJqValue(json, SkillsConfig.class);
        assertEquals(list.skills().list(), restored.skills().list());

        // Object form round-trip
        var obj = new SkillsConfig("img", new SkillsDefSpike("https://repo", List.of("x")));
        JqValue json2 = mapper.toJqValue(obj);
        assertTrue(json2.getField("skills") instanceof JqObject);
        SkillsConfig restored2 = mapper.fromJqValue(json2, SkillsConfig.class);
        assertEquals("https://repo", restored2.skills().repo());
    }

    // ---- Verify @JqConverter composes with @JqField ----

    record DeepConverterRecord(
            String name,
            @JqField(".data.entries") @JqConverter(EnvEntryListConverter.class) List<EnvEntrySpike> env
    ) {}

    @Test
    void converter_composesWithJqField() {
        JqValue json = JqValues.parse("""
                {"name":"test","data":{"entries":["export X=1",{"name":"Y","value":"2","strategy":"set"}]}}
                """);
        DeepConverterRecord r = mapper.fromJqValue(json, DeepConverterRecord.class);
        assertEquals("test", r.name());
        assertEquals(2, r.env().size());
        assertEquals("export X=1", r.env().get(0).value());
        assertEquals("Y", r.env().get(1).name());
    }

    // ---- char / Character support ----

    record CharRecord(String name, char initial, Character grade) {}

    @Test
    void fromJqValue_charRecord() {
        JqValue json = JqValues.parse("{\"name\":\"Alice\",\"initial\":\"A\",\"grade\":\"B\"}");
        CharRecord r = mapper.fromJqValue(json, CharRecord.class);
        assertEquals("Alice", r.name());
        assertEquals('A', r.initial());
        assertEquals('B', r.grade());
    }

    @Test
    void toJqValue_charRecord() {
        JqValue result = mapper.toJqValue(new CharRecord("Alice", 'A', 'B'));
        assertEquals("Alice", result.getField("name").stringValue());
        // char serializes as string
        assertEquals("A", result.getField("initial").stringValue());
        assertEquals("B", result.getField("grade").stringValue());
    }

    @Test
    void roundTrip_charRecord() {
        CharRecord original = new CharRecord("Alice", 'A', 'B');
        JqValue json = mapper.toJqValue(original);
        CharRecord restored = mapper.fromJqValue(json, CharRecord.class);
        assertEquals(original, restored);
    }

    @Test
    void fromJqValue_charDefaults() {
        // Missing fields should get default char values
        JqValue json = JqValues.parse("{\"name\":\"Alice\"}");
        CharRecord r = mapper.fromJqValue(json, CharRecord.class);
        assertEquals('\0', r.initial());
        assertEquals('\0', r.grade()); // Character (boxed) gets '\0' default from TypeConverter
    }

    // ---- Optional with non-String scalar types ----

    record OptionalScalarsRecord(
            Optional<Integer> count,
            Optional<Long> bigCount,
            Optional<Double> ratio,
            Optional<Float> smallRatio,
            Optional<Boolean> flag,
            Optional<Short> shortVal,
            Optional<Byte> byteVal,
            Optional<BigDecimal> precise
    ) {}

    @Test
    void fromJqValue_optionalScalars_present() {
        JqValue json = JqValues.parse("""
                {"count":42,"bigCount":999999,"ratio":3.14,"smallRatio":2.5,
                 "flag":true,"shortVal":100,"byteVal":7,"precise":3.14159}""");
        OptionalScalarsRecord r = mapper.fromJqValue(json, OptionalScalarsRecord.class);
        assertEquals(Optional.of(42), r.count());
        assertEquals(Optional.of(999999L), r.bigCount());
        assertEquals(3.14, r.ratio().orElse(0.0), 0.001);
        assertEquals(2.5f, r.smallRatio().orElse(0f), 0.01f);
        assertEquals(Optional.of(true), r.flag());
        assertEquals(Optional.of((short) 100), r.shortVal());
        assertEquals(Optional.of((byte) 7), r.byteVal());
        assertTrue(r.precise().isPresent());
    }

    @Test
    void fromJqValue_optionalScalars_empty() {
        JqValue json = JqValues.parse("{}");
        OptionalScalarsRecord r = mapper.fromJqValue(json, OptionalScalarsRecord.class);
        assertEquals(Optional.empty(), r.count());
        assertEquals(Optional.empty(), r.bigCount());
        assertEquals(Optional.empty(), r.ratio());
        assertEquals(Optional.empty(), r.smallRatio());
        assertEquals(Optional.empty(), r.flag());
        assertEquals(Optional.empty(), r.shortVal());
        assertEquals(Optional.empty(), r.byteVal());
        assertEquals(Optional.empty(), r.precise());
    }

    // ---- NON_EMPTY with Set ----

    @JqInclude(JqInclude.Include.NON_EMPTY)
    record NonEmptySetRecord(String name, java.util.Set<String> tags) {}

    @Test
    void toJqValue_nonEmpty_excludesEmptySet() {
        JqValue result = mapper.toJqValue(new NonEmptySetRecord("test", java.util.Set.of()));
        String json = result.toJsonString();
        assertTrue(json.contains("\"name\""), "Non-empty name should be included: " + json);
        assertFalse(json.contains("\"tags\""), "Empty set should be excluded: " + json);
    }

    @Test
    void toJqValue_nonEmpty_includesNonEmptySet() {
        JqValue result = mapper.toJqValue(new NonEmptySetRecord("test", java.util.Set.of("a", "b")));
        String json = result.toJsonString();
        assertTrue(json.contains("\"tags\""), "Non-empty set should be included: " + json);
    }

    // ---- NON_DEFAULT with BigDecimal ----

    @JqInclude(JqInclude.Include.NON_DEFAULT)
    record NonDefaultBigDecimalRecord(String name, BigDecimal value) {}

    @Test
    void toJqValue_nonDefault_excludesNullBigDecimal() {
        JqValue result = mapper.toJqValue(new NonDefaultBigDecimalRecord("test", null));
        String json = result.toJsonString();
        assertFalse(json.contains("\"value\""), "Null BigDecimal should be excluded: " + json);
    }

    @Test
    void toJqValue_nonDefault_includesNonNullBigDecimal() {
        JqValue result = mapper.toJqValue(new NonDefaultBigDecimalRecord("test", BigDecimal.TEN));
        String json = result.toJsonString();
        assertTrue(json.contains("\"value\""), "Non-null BigDecimal should be included: " + json);
    }

    // ---- POJO with char ----

    static class CharPojo {
        private char initial;
        private Character grade;

        public CharPojo() {}

        public char getInitial() { return initial; }
        public void setInitial(char initial) { this.initial = initial; }
        public Character getGrade() { return grade; }
        public void setGrade(Character grade) { this.grade = grade; }
    }

    @Test
    void fromJqValue_charPojo() {
        JqValue json = JqValues.parse("{\"initial\":\"X\",\"grade\":\"A\"}");
        CharPojo p = mapper.fromJqValue(json, CharPojo.class);
        assertEquals('X', p.getInitial());
        assertEquals('A', p.getGrade());
    }

    @Test
    void toJqValue_charPojo() {
        CharPojo p = new CharPojo();
        p.setInitial('X');
        p.setGrade('A');
        JqValue result = mapper.toJqValue(p);
        assertEquals("X", result.getField("initial").stringValue());
        assertEquals("A", result.getField("grade").stringValue());
    }

    // ---- fromJqValue with Type parameter ----

    @Test
    void fromJqValue_genericType_listOfRecords() {
        JqValue json = JqValues.parse("[{\"city\":\"NYC\",\"zip\":\"10001\"},{\"city\":\"SF\",\"zip\":\"94105\"}]");
        java.lang.reflect.Type listType = new TypeToken<List<Address>>(){}.getType();
        List<Address> addresses = mapper.fromJqValue(json, listType);
        assertEquals(2, addresses.size());
        assertEquals("NYC", addresses.get(0).city());
        assertEquals("SF", addresses.get(1).city());
    }

    @Test
    void fromJqValue_genericType_mapOfRecords() {
        // Issue #101: the documented TypeToken pattern, now shipped
        JqValue json = JqValues.parse("{\"home\":{\"city\":\"NYC\",\"zip\":\"10001\"}}");
        java.lang.reflect.Type mapType = new TypeToken<Map<String, Address>>(){}.getType();
        Map<String, Address> addresses = mapper.fromJqValue(json, mapType);
        assertEquals(1, addresses.size());
        assertEquals("NYC", addresses.get("home").city());
    }

    // ---- toJson vs toJqValue consistency ----

    @Test
    void toJson_matchesToJqValue_simple() {
        SimpleRecord r = new SimpleRecord("Alice", 30, true);
        String viaJqValue = mapper.toJqValue(r).toJsonString();
        String viaToJson = mapper.toJson(r);
        assertEquals(viaJqValue, viaToJson, "toJson should produce same output as toJqValue().toJsonString()");
    }

    @Test
    void toJson_matchesToJqValue_nested() {
        Person p = new Person("Bob", new Address("SF", "94105"));
        String viaJqValue = mapper.toJqValue(p).toJsonString();
        String viaToJson = mapper.toJson(p);
        assertEquals(viaJqValue, viaToJson);
    }

    @Test
    void toJson_matchesToJqValue_list() {
        WithList r = new WithList("Alice", List.of(95, 87, 92));
        String viaJqValue = mapper.toJqValue(r).toJsonString();
        String viaToJson = mapper.toJson(r);
        assertEquals(viaJqValue, viaToJson);
    }

    @Test
    void toJson_matchesToJqValue_enum() {
        WithEnum r = new WithEnum("Alice", Status.ACTIVE);
        String viaJqValue = mapper.toJqValue(r).toJsonString();
        String viaToJson = mapper.toJson(r);
        assertEquals(viaJqValue, viaToJson);
    }

    @Test
    void toJson_matchesToJqValue_pojo() {
        SimplePojo p = new SimplePojo();
        p.setName("Bob");
        p.setAge(25);
        p.setActive(false);
        String viaJqValue = mapper.toJqValue(p).toJsonString();
        String viaToJson = mapper.toJson(p);
        assertEquals(viaJqValue, viaToJson);
    }

    @Test
    void toJson_null() {
        assertEquals("null", mapper.toJson(null));
    }

    @Test
    void toJson_matchesToJqValue_nonNull() {
        NonNullRecord r = new NonNullRecord("hello", null, null);
        String viaJqValue = mapper.toJqValue(r).toJsonString();
        String viaToJson = mapper.toJson(r);
        assertEquals(viaJqValue, viaToJson);
    }

    @Test
    void toJson_matchesToJqValue_nonDefault() {
        NonDefaultRecord r = new NonDefaultRecord(null, 0, false, 0.0);
        String viaJqValue = mapper.toJqValue(r).toJsonString();
        String viaToJson = mapper.toJson(r);
        assertEquals(viaJqValue, viaToJson);
    }

    @Test
    void toJson_matchesToJqValue_converter() {
        EventRecord r = new EventRecord("deploy", Instant.parse("2026-09-05T12:00:00Z"));
        String viaJqValue = mapper.toJqValue(r).toJsonString();
        String viaToJson = mapper.toJson(r);
        assertEquals(viaJqValue, viaToJson);
    }

    @Test
    void toJson_matchesToJqValue_charRecord() {
        CharRecord r = new CharRecord("Alice", 'A', 'B');
        String viaJqValue = mapper.toJqValue(r).toJsonString();
        String viaToJson = mapper.toJson(r);
        assertEquals(viaJqValue, viaToJson);
    }

    record DoubleRecord(String name, double score, Double ratio, float factor, Float multiplier) {}

    @Test
    void toJson_matchesToJqValue_doubleRecord() {
        DoubleRecord r = new DoubleRecord("test", 3.0, 2.5, 1.5f, 0.5f);
        String viaJqValue = mapper.toJqValue(r).toJsonString();
        String viaToJson = mapper.toJson(r);
        assertEquals(viaJqValue, viaToJson);
    }

    @Test
    void toJson_matchesToJqValue_nullableBoxed() {
        DoubleRecord r = new DoubleRecord("test", 3.0, null, 1.5f, null);
        String viaJqValue = mapper.toJqValue(r).toJsonString();
        String viaToJson = mapper.toJson(r);
        assertEquals(viaJqValue, viaToJson);
    }

    record BigDecimalRecord(String name, BigDecimal plain, BigDecimal sci, BigDecimal integral) {}

    @Test
    void toJson_matchesToJqValue_bigDecimalRecord() {
        BigDecimalRecord r = new BigDecimalRecord("test",
                new BigDecimal("3.14"), new BigDecimal("1E+3"), new BigDecimal("100"));
        String viaJqValue = mapper.toJqValue(r).toJsonString();
        String viaToJson = mapper.toJson(r);
        assertEquals(viaJqValue, viaToJson);
    }

    record NullableCharRecord(String name, Character grade) {}

    @Test
    void toJson_matchesToJqValue_nullableCharacter() {
        NullableCharRecord r = new NullableCharRecord("test", null);
        String viaJqValue = mapper.toJqValue(r).toJsonString();
        String viaToJson = mapper.toJson(r);
        assertEquals(viaJqValue, viaToJson);
    }

    // ---- toJsonBytes vs toJqValue consistency ----

    private void assertBytesMatch(Object value) {
        byte[] viaTree = JqValues.serializeToBytes(mapper.toJqValue(value));
        byte[] viaDirect = mapper.toJsonBytes(value);
        assertArrayEquals(viaTree, viaDirect,
                "toJsonBytes should produce same bytes as toJqValue + serializeToBytes");
    }

    @Test
    void toJsonBytes_matchesTree_simple() {
        assertBytesMatch(new SimpleRecord("Alice", 30, true));
    }

    @Test
    void toJsonBytes_matchesTree_nested() {
        assertBytesMatch(new Person("Bob", new Address("SF", "94105")));
    }

    @Test
    void toJsonBytes_matchesTree_list() {
        assertBytesMatch(new WithList("Alice", List.of(95, 87, 92)));
    }

    @Test
    void toJsonBytes_matchesTree_enum() {
        assertBytesMatch(new WithEnum("Alice", Status.ACTIVE));
    }

    @Test
    void toJsonBytes_matchesTree_pojo() {
        SimplePojo p = new SimplePojo();
        p.setName("Bob");
        p.setAge(25);
        p.setActive(false);
        assertBytesMatch(p);
    }

    @Test
    void toJsonBytes_null() {
        assertArrayEquals("null".getBytes(), mapper.toJsonBytes(null));
    }

    @Test
    void toJsonBytes_matchesTree_nonNull() {
        assertBytesMatch(new NonNullRecord("hello", null, null));
    }

    @Test
    void toJsonBytes_matchesTree_nonDefault() {
        assertBytesMatch(new NonDefaultRecord(null, 0, false, 0.0));
        assertBytesMatch(new NonDefaultRecord("Alice", 5, true, 3.14));
    }

    @Test
    void toJsonBytes_matchesTree_converter() {
        assertBytesMatch(new EventRecord("deploy", Instant.parse("2026-09-05T12:00:00Z")));
    }

    @Test
    void toJsonBytes_matchesTree_charRecord() {
        assertBytesMatch(new CharRecord("Alice", 'A', 'B'));
    }

    @Test
    void toJsonBytes_matchesTree_doubleRecord() {
        assertBytesMatch(new DoubleRecord("test", 3.0, 2.5, 1.5f, 0.5f));
    }

    @Test
    void toJsonBytes_matchesTree_nullableBoxed() {
        assertBytesMatch(new DoubleRecord("test", 3.0, null, 1.5f, null));
    }

    @Test
    void toJsonBytes_matchesTree_bigDecimalRecord() {
        assertBytesMatch(new BigDecimalRecord("test",
                new BigDecimal("3.14"), new BigDecimal("1E+3"), new BigDecimal("100")));
    }

    @Test
    void toJsonBytes_matchesTree_nullableCharacter() {
        assertBytesMatch(new NullableCharRecord("test", null));
    }

    @Test
    void toJsonBytes_matchesTree_map() {
        assertBytesMatch(new WithMap("Alice", Map.of("math", 95)));
    }

    @Test
    void toJsonBytes_matchesTree_optional() {
        assertBytesMatch(new WithOptional("Alice", Optional.of("a@b.c")));
        assertBytesMatch(new WithOptional("Bob", Optional.empty()));
    }

    // ---- Sealed polymorphism (@JqType) ----

    @JqType
    sealed interface Shape permits Circle, Rectangle {}

    record Circle(double radius) implements Shape {}

    @JqName("rect")
    record Rectangle(double width, double height) implements Shape {}

    @JqType(discriminator = "kind")
    sealed interface KindedShape permits KindedCircle {}

    record KindedCircle(double radius) implements KindedShape {}

    @JqType
    static class NotSealedBase {
        String a;
    }

    record Drawing(Shape shape, String name) {}

    @Test
    void polyDeserSelectsSubtype() {
        Shape s = mapper.fromJqValue(JqValues.parse("{\"type\":\"Circle\",\"radius\":1.5}"), Shape.class);
        assertEquals(new Circle(1.5), s);
    }

    @Test
    void polyDeserUsesJqNameWireValue() {
        Shape s = mapper.fromJqValue(
                JqValues.parse("{\"type\":\"rect\",\"width\":2.0,\"height\":3.0}"), Shape.class);
        assertEquals(new Rectangle(2.0, 3.0), s);
    }

    @Test
    void polyDeserUnknownTypeFails() {
        assertThrows(ShapeMismatchException.class, () -> mapper.fromJqValue(
                JqValues.parse("{\"type\":\"Triangle\",\"a\":1}"), Shape.class));
    }

    @Test
    void polyDeserMissingDiscriminatorFails() {
        assertThrows(ShapeMismatchException.class, () ->
                mapper.fromJqValue(JqValues.parse("{\"radius\":1.5}"), Shape.class));
    }

    @Test
    void polyDeserScalarFails() {
        assertThrows(ShapeMismatchException.class, () -> mapper.fromJqValue(JqValues.parse("42"), Shape.class));
    }

    @Test
    void polyDeserNullYieldsNull() {
        assertNull(mapper.fromJqValue(JqNull.NULL, Shape.class));
    }

    @Test
    void polySerInjectsDiscriminator() {
        JqValue tree = mapper.toJqValue(new Circle(1.5));
        assertEquals("Circle", tree.getField("type").stringValue());
        assertEquals(1.5, tree.getField("radius").doubleValue());
        JqValue rect = mapper.toJqValue(new Rectangle(2.0, 3.0));
        assertEquals("rect", rect.getField("type").stringValue());
    }

    @Test
    void polyRoundTrip() {
        for (Shape s : List.of(new Circle(1.5), new Rectangle(2.0, 3.0))) {
            assertEquals(s, mapper.fromJqValue(mapper.toJqValue(s), Shape.class));
        }
    }

    @Test
    void polyNestedField() {
        Drawing d = mapper.fromJqValue(
                JqValues.parse("{\"shape\":{\"type\":\"Circle\",\"radius\":1.5},\"name\":\"sketch\"}"),
                Drawing.class);
        assertEquals(new Drawing(new Circle(1.5), "sketch"), d);
        assertEquals("Circle",
                mapper.toJqValue(d).getField("shape").getField("type").stringValue());
    }

    @Test
    void polyHeterogeneousList() {
        JqValue json = JqValues.parse(
                "[{\"type\":\"Circle\",\"radius\":1.5},{\"type\":\"rect\",\"width\":2.0,\"height\":3.0}]");
        java.lang.reflect.Type listType = new TypeToken<List<Shape>>(){}.getType();
        List<Shape> shapes = mapper.fromJqValue(json, listType);
        assertEquals(List.of(new Circle(1.5), new Rectangle(2.0, 3.0)), shapes);
    }

    @Test
    void polyCustomDiscriminator() {
        KindedShape s = mapper.fromJqValue(
                JqValues.parse("{\"kind\":\"KindedCircle\",\"radius\":4.0}"), KindedShape.class);
        assertEquals(new KindedCircle(4.0), s);
        assertEquals("KindedCircle", mapper.toJqValue(s).getField("kind").stringValue());
    }

    @Test
    void polyDirectSubtypeIgnoresDiscriminator() {
        // Binding straight to the subtype stays lenient: the discriminator
        // reads as an unknown key, fields bind normally.
        Circle c = mapper.fromJqValue(
                JqValues.parse("{\"type\":\"Circle\",\"radius\":1.5}"), Circle.class);
        assertEquals(new Circle(1.5), c);
    }

    @Test
    void polyNonSealedBaseFailsFast() {
        assertThrows(JqMapperException.class, () ->
                mapper.fromJqValue(JqValues.parse("{\"a\":\"x\"}"), NotSealedBase.class));
    }

    static class CircleStubMapping extends GeneratedMapping<Circle> {
        boolean usedFrom;
        boolean usedTo;

        @Override
        public Class<Circle> type() { return Circle.class; }

        @Override
        public Circle fromJqValue(JqValue input, JqMapper mapper) {
            usedFrom = true;
            return new Circle(input.getField("radius").doubleValue());
        }

        @Override
        public JqValue toJqValue(Circle instance, JqMapper mapper) {
            usedTo = true;
            return JqObject.builder(1).put("radius", instance.radius()).build();
        }
    }

    @Test
    void polyDeserDelegatesToRegisteredGeneratedMapping() {
        var stub = new CircleStubMapping();
        JqMapper m = JqMapper.builder().register(stub).build();
        Shape s = m.fromJqValue(JqValues.parse("{\"type\":\"Circle\",\"radius\":2.5}"), Shape.class);
        assertTrue(stub.usedFrom, "poly dispatch should delegate to the subtype mapping");
        assertEquals(new Circle(2.5), s);
    }

    @Test
    void polySerWrapsRegisteredGeneratedMapping() {
        var stub = new CircleStubMapping();
        JqMapper m = JqMapper.builder().register(stub).build();
        JqValue tree = m.toJqValue(new Circle(2.5));
        assertTrue(stub.usedTo, "ser should go through the subtype mapping");
        assertEquals("Circle", tree.getField("type").stringValue());
        assertEquals(2.5, tree.getField("radius").doubleValue());
    }

    @Test
    void polySerDirectAppendJsonMatchesTree() {
        // Reflection path: appendJson defaults to toJqValue().appendTo(),
        // so the injected discriminator rides along identically.
        String viaTree = mapper.toJqValue(new Circle(1.5)).toJsonString();
        StringBuilder sb = new StringBuilder();
        mapper.appendJson(new Circle(1.5), sb);
        assertEquals(viaTree, sb.toString());
    }

    // ---- POJO field inheritance ----

    static class Animal {
        public String name;
    }

    static class Dog extends Animal {
        public boolean good;
    }

    static class BaseWithTag {
        public String tag = "base";
    }

    static class SubWithTag extends BaseWithTag {
        public String tag = "sub";
    }

    @Test
    void pojoSubclassBindsInheritedFields() {
        Dog d = mapper.fromJqValue(JqValues.parse("{\"name\":\"Rex\",\"good\":true}"), Dog.class);
        assertEquals("Rex", d.name);
        assertTrue(d.good);
    }

    @Test
    void pojoSubclassSerializesInheritedFields() {
        Dog d = new Dog();
        d.name = "Rex";
        d.good = true;
        JqValue tree = mapper.toJqValue(d);
        assertEquals("Rex", tree.getField("name").stringValue());
        assertTrue(tree.getField("good").booleanValue());
    }

    @Test
    void pojoSubclassFieldShadowsSuperclass() {
        SubWithTag s = mapper.fromJqValue(JqValues.parse("{\"tag\":\"x\"}"), SubWithTag.class);
        assertEquals("x", ((SubWithTag) s).tag);
        assertEquals("x", mapper.toJqValue(s).getField("tag").stringValue());
    }
}

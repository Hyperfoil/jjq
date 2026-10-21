package io.hyperfoil.tools.jjq.jakarta;

import io.hyperfoil.tools.jjq.mapper.JqField;
import io.hyperfoil.tools.jjq.mapper.JqIgnore;
import io.hyperfoil.tools.jjq.mapper.JqMapped;
import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.mapper.JqType;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class JqMappedMessageBodyTest {

    private final JqMappedMessageBodyReader reader = new JqMappedMessageBodyReader();
    private final JqMappedMessageBodyWriter writer = new JqMappedMessageBodyWriter();

    // ---- Test records ----

    @JqMapped
    record SimpleUser(String name, int age, boolean active) {}

    @JqMapped
    record WithJqField(
            String user,
            @JqField(".config.timeout") int timeout
    ) {}

    @JqMapped
    record WithIgnored(
            String name,
            @JqIgnore String secret
    ) {}

    // Non-annotated record — should NOT be handled
    record PlainRecord(String name) {}

    // ---- isReadable / isWriteable ----

    @Test
    void isReadable_jqMappedRecord() {
        assertTrue(reader.isReadable(SimpleUser.class, SimpleUser.class, null, null));
    }

    @Test
    void isReadable_nonAnnotatedRecord() {
        assertFalse(reader.isReadable(PlainRecord.class, PlainRecord.class, null, null));
    }

    @Test
    void isReadable_plainClass() {
        assertFalse(reader.isReadable(String.class, String.class, null, null));
    }

    @Test
    void isWriteable_jqMappedRecord() {
        assertTrue(writer.isWriteable(SimpleUser.class, SimpleUser.class, null, null));
    }

    @Test
    void isWriteable_nonAnnotatedRecord() {
        assertFalse(writer.isWriteable(PlainRecord.class, PlainRecord.class, null, null));
    }

    @io.hyperfoil.tools.jjq.mapper.JqType(discriminator = "method",
            existingProperty = true, mappings = {
                    @io.hyperfoil.tools.jjq.mapper.JqSubtype(value = "A", type = PolyA.class),
                    @io.hyperfoil.tools.jjq.mapper.JqSubtype(value = "B", type = PolyB.class)
            })
    sealed interface PolyFamily permits PolyA, PolyB {}

    record PolyA(String method, String a) implements PolyFamily {}

    record PolyB(String method, String b) implements PolyFamily {}

    @io.hyperfoil.tools.jjq.mapper.JqKeys(value = {
            @io.hyperfoil.tools.jjq.mapper.JqKey(value = "a", type = KeyA.class)
    }, defaultSubtype = KeyB.class)
    sealed interface KeyFamily permits KeyA, KeyB {}

    record KeyA(int a) implements KeyFamily {}

    record KeyB(int b) implements KeyFamily {}

    @Test
    void isReadable_polyFamilies() {
        assertTrue(reader.isReadable(PolyFamily.class, PolyFamily.class, null, null));
        assertTrue(reader.isReadable(KeyFamily.class, KeyFamily.class, null, null));
    }

    @Test
    void isWriteable_polyFamilies() {
        assertTrue(writer.isWriteable(PolyFamily.class, PolyFamily.class, null, null));
        assertTrue(writer.isWriteable(KeyFamily.class, KeyFamily.class, null, null));
    }

    @Test
    void readFrom_polyFamilyDispatches() throws IOException {
        Object result = readJson("{\"method\":\"B\",\"b\":\"x\"}", PolyFamily.class);
        assertEquals(new PolyB("B", "x"), result);
        Object keys = readJson("{\"a\":1}", KeyFamily.class);
        assertEquals(new KeyA(1), keys);
    }

    // ---- Read (deserialize) ----

    @Test
    void readFrom_simpleRecord() throws IOException {
        String json = "{\"name\":\"Alice\",\"age\":30,\"active\":true}";
        Object result = readJson(json, SimpleUser.class);
        assertInstanceOf(SimpleUser.class, result);
        SimpleUser user = (SimpleUser) result;
        assertEquals("Alice", user.name());
        assertEquals(30, user.age());
        assertTrue(user.active());
    }

    @Test
    void readFrom_withJqField() throws IOException {
        String json = "{\"user\":\"Alice\",\"config\":{\"timeout\":30}}";
        Object result = readJson(json, WithJqField.class);
        assertInstanceOf(WithJqField.class, result);
        WithJqField wf = (WithJqField) result;
        assertEquals("Alice", wf.user());
        assertEquals(30, wf.timeout());
    }

    @Test
    void readFrom_withIgnored() throws IOException {
        String json = "{\"name\":\"Alice\",\"secret\":\"password123\"}";
        Object result = readJson(json, WithIgnored.class);
        assertInstanceOf(WithIgnored.class, result);
        WithIgnored wi = (WithIgnored) result;
        assertEquals("Alice", wi.name());
        assertNull(wi.secret());
    }

    @Test
    void readFrom_emptyStream() throws IOException {
        Object result = readJson("", SimpleUser.class);
        assertNull(result);
    }

    @Test
    void readFrom_nullLiteral_returnsNull() throws IOException {
        assertNull(readJson("null", SimpleUser.class));
    }

    @Test
    void readFrom_malformedJson_isBadRequest() {
        JqBadRequestException e = assertThrows(JqBadRequestException.class,
                () -> readJson("{bad", SimpleUser.class));
        assertEquals(400, e.status());
        assertFalse(e.getMessage().isBlank());
    }

    @Test
    void readFrom_trailingContent_isBadRequest() {
        assertThrows(JqBadRequestException.class,
                () -> readJson("{\"name\":\"Alice\",\"age\":30,\"active\":true} trailing", SimpleUser.class));
    }

    @Test
    void readFrom_duplicateKeys_isBadRequest() {
        assertThrows(JqBadRequestException.class,
                () -> readJson("{\"name\":\"Alice\",\"name\":\"Bob\",\"age\":30,\"active\":true}", SimpleUser.class));
    }

    @Test
    void readFrom_shapeMismatch_isBadRequest() {
        assertThrows(JqBadRequestException.class, () -> readJson("[1,2,3]", SimpleUser.class));
    }

    @Test
    void readFrom_unknownField_ignoredByDefault() throws IOException {
        Object result = readJson(
                "{\"name\":\"Alice\",\"age\":30,\"active\":true,\"nickname\":\"Al\"}", SimpleUser.class);
        assertEquals(new SimpleUser("Alice", 30, true), result);
    }

    @Test
    void readFrom_unknownField_strictMapper_isBadRequest() {
        var strictReader = new JqMappedMessageBodyReader(
                JqMapper.builder().failOnUnknownProperties(true).build());
        assertThrows(JqBadRequestException.class, () -> readJson(strictReader,
                "{\"name\":\"Alice\",\"age\":30,\"active\":true,\"nickname\":\"Al\"}", SimpleUser.class));
    }

    // ---- Write (serialize) ----

    @Test
    void writeTo_simpleRecord() throws IOException {
        SimpleUser user = new SimpleUser("Bob", 25, false);
        String json = writeJson(user);
        assertTrue(json.contains("\"name\":\"Bob\""));
        assertTrue(json.contains("\"age\":25"));
        assertTrue(json.contains("\"active\":false"));
    }

    @Test
    void writeTo_null() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.writeTo(null, SimpleUser.class, SimpleUser.class, null, null, null, out);
        assertEquals(0, out.size());
    }

    @Test
    void writeTo_withIgnored() throws IOException {
        WithIgnored wi = new WithIgnored("Alice", "password123");
        String json = writeJson(wi);
        assertTrue(json.contains("\"name\":\"Alice\""));
        // secret should not appear in output
        assertFalse(json.contains("password123"));
    }

    // ---- Round-trip ----

    @Test
    void roundTrip() throws IOException {
        SimpleUser original = new SimpleUser("Alice", 30, true);
        String json = writeJson(original);
        Object restored = readJson(json, SimpleUser.class);
        assertEquals(original, restored);
    }

    // ---- Collections of mapped types (issue #152) ----

    @JqType
    sealed interface Pet permits Cat, Dog {}

    record Cat(int lives) implements Pet {}

    record Dog(boolean good) implements Pet {}

    private static java.lang.reflect.Type listOfUsers() {
        return new io.hyperfoil.tools.jjq.mapper.TypeToken<java.util.List<SimpleUser>>() {}.getType();
    }

    private static java.lang.reflect.Type setOfUsers() {
        return new io.hyperfoil.tools.jjq.mapper.TypeToken<java.util.Set<SimpleUser>>() {}.getType();
    }

    private static java.lang.reflect.Type mapOfUsers() {
        return new io.hyperfoil.tools.jjq.mapper.TypeToken<java.util.Map<String, SimpleUser>>() {}.getType();
    }

    private static java.lang.reflect.Type listOfPets() {
        return new io.hyperfoil.tools.jjq.mapper.TypeToken<java.util.List<Pet>>() {}.getType();
    }

    private static java.lang.reflect.Type listOfJqValues() {
        return new io.hyperfoil.tools.jjq.mapper.TypeToken<java.util.List<JqValue>>() {}.getType();
    }

    @Test
    void isReadable_collectionOfMapped() {
        assertTrue(reader.isReadable(java.util.List.class, listOfUsers(), null, null));
        assertTrue(reader.isReadable(java.util.Set.class, setOfUsers(), null, null));
        assertTrue(reader.isReadable(java.util.Map.class, mapOfUsers(), null, null));
        assertTrue(reader.isReadable(SimpleUser[].class, SimpleUser[].class, null, null));
        assertTrue(reader.isReadable(java.util.List.class, listOfPets(), null, null));
        assertTrue(reader.isReadable(java.util.List.class, listOfJqValues(), null, null));
    }

    @Test
    void isReadable_collectionOfScalarsDeclines() {
        java.lang.reflect.Type strings =
                new io.hyperfoil.tools.jjq.mapper.TypeToken<java.util.List<String>>() {}.getType();
        java.lang.reflect.Type stringMap =
                new io.hyperfoil.tools.jjq.mapper.TypeToken<java.util.Map<String, String>>() {}.getType();
        java.lang.reflect.Type nested =
                new io.hyperfoil.tools.jjq.mapper.TypeToken<java.util.List<java.util.List<SimpleUser>>>() {}.getType();
        java.lang.reflect.Type objectKeys =
                new io.hyperfoil.tools.jjq.mapper.TypeToken<java.util.Map<Object, SimpleUser>>() {}.getType();
        assertFalse(reader.isReadable(java.util.List.class, strings, null, null));
        assertFalse(reader.isReadable(java.util.Map.class, stringMap, null, null));
        assertFalse(reader.isReadable(java.util.List.class, nested, null, null));
        assertFalse(reader.isReadable(java.util.Map.class, objectKeys, null, null));
        assertFalse(reader.isReadable(java.util.List.class, java.util.List.class, null, null));
        assertFalse(reader.isReadable(String.class, String.class, null, null));
    }

    @Test
    void isWriteable_collectionOfMapped() {
        assertTrue(writer.isWriteable(java.util.List.class, listOfUsers(), null, null));
        assertTrue(writer.isWriteable(java.util.Map.class, mapOfUsers(), null, null));
        assertFalse(writer.isWriteable(java.util.List.class, java.util.List.class, null, null));
    }

    @Test
    void readFrom_listRoundTrips() throws IOException {
        Object result = readGeneric(
                "[{\"name\":\"Alice\",\"age\":30,\"active\":true},{\"name\":\"Bob\",\"age\":25,\"active\":false}]",
                java.util.List.class, listOfUsers());
        assertEquals(java.util.List.of(
                new SimpleUser("Alice", 30, true),
                new SimpleUser("Bob", 25, false)), result);
    }

    @Test
    void readFrom_setAndMapRoundTrip() throws IOException {
        Object set = readGeneric("[{\"name\":\"Alice\",\"age\":30,\"active\":true}]",
                java.util.Set.class, setOfUsers());
        assertEquals(java.util.Set.of(new SimpleUser("Alice", 30, true)), set);
        Object map = readGeneric("{\"lead\":{\"name\":\"Alice\",\"age\":30,\"active\":true}}",
                java.util.Map.class, mapOfUsers());
        assertEquals(java.util.Map.of("lead", new SimpleUser("Alice", 30, true)), map);
    }

    @Test
    void readFrom_arrayRoundTrips() throws IOException {
        Object result = readGeneric("[{\"name\":\"Alice\",\"age\":30,\"active\":true}]",
                SimpleUser[].class, SimpleUser[].class);
        assertTrue(result instanceof SimpleUser[]);
        assertEquals(1, ((SimpleUser[]) result).length);
        assertEquals(new SimpleUser("Alice", 30, true), ((SimpleUser[]) result)[0]);
    }

    @Test
    void readFrom_familyListDispatchesPerElement() throws IOException {
        Object result = readGeneric(
                "[{\"type\":\"Cat\",\"lives\":9},{\"type\":\"Dog\",\"good\":true}]",
                java.util.List.class, listOfPets());
        assertEquals(java.util.List.of(new Cat(9), new Dog(true)), result);
    }

    @Test
    void readFrom_elementFailureCarriesIndexPath() {
        JqBadRequestException e = assertThrows(JqBadRequestException.class, () -> readGeneric(
                "[{\"name\":\"Alice\",\"age\":30,\"active\":true},{\"name\":\"Bob\",\"age\":{}}]",
                java.util.List.class, listOfUsers()));
        assertTrue(e.getMessage().contains("[1]"), e.getMessage());
        assertTrue(e.getMessage().contains("age"), e.getMessage());
    }

    @Test
    void readFrom_nullLiteralList_returnsNull() throws IOException {
        assertNull(readGeneric("null", java.util.List.class, listOfUsers()));
    }

    @Test
    void readFrom_emptyArray_bindsEmpty() throws IOException {
        assertEquals(java.util.List.of(), readGeneric("[]", java.util.List.class, listOfUsers()));
    }

    @Test
    void readFrom_strictUnknownInElement_isBadRequest() {
        var strictReader = new JqMappedMessageBodyReader(
                JqMapper.builder().failOnUnknownProperties(true).build());
        JqBadRequestException e = assertThrows(JqBadRequestException.class, () -> readGeneric(strictReader,
                "[{\"name\":\"Alice\",\"age\":30,\"active\":true,\"nickname\":\"Al\"}]",
                java.util.List.class, listOfUsers()));
        assertTrue(e.getMessage().contains("nickname"), e.getMessage());
    }

    @Test
    void writeTo_listRoundTrips() throws IOException {
        String json = writeGeneric(
                java.util.List.of(new SimpleUser("Alice", 30, true), new SimpleUser("Bob", 25, false)),
                java.util.List.class, listOfUsers());
        assertEquals(java.util.List.of(
                new SimpleUser("Alice", 30, true),
                new SimpleUser("Bob", 25, false)),
                readGeneric(json, java.util.List.class, listOfUsers()));
    }

    @Test
    void writeTo_injectedDiscriminatorSurvivesList() throws IOException {
        String json = writeGeneric(java.util.List.of(new Cat(9), new Dog(true)),
                java.util.List.class, listOfPets());
        assertTrue(json.contains("\"type\":\"Cat\""), json);
        assertTrue(json.contains("\"type\":\"Dog\""), json);
        assertEquals(java.util.List.of(new Cat(9), new Dog(true)),
                readGeneric(json, java.util.List.class, listOfPets()));
    }

    @Test
    void writeTo_mapAndJqValueList() throws IOException {
        String map = writeGeneric(java.util.Map.of("lead", new SimpleUser("Alice", 30, true)),
                java.util.Map.class, mapOfUsers());
        assertTrue(map.contains("\"lead\""), map);
        String values = writeGeneric(java.util.List.of(JqValues.parse("{\"a\":1}")),
                java.util.List.class, listOfJqValues());
        assertEquals("[{\"a\":1}]", values);
    }

    // ---- Helpers ----

    private Object readJson(String json, Class<?> type) throws IOException {
        return readGeneric(json, type, type);
    }

    @SuppressWarnings("unchecked")
    private Object readJson(JqMappedMessageBodyReader reader, String json, Class<?> type) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return reader.readFrom(
                (Class<Object>) type, type, null, null, null,
                new ByteArrayInputStream(bytes));
    }

    @SuppressWarnings("unchecked")
    private Object readGeneric(String json, Class<?> type, java.lang.reflect.Type genericType)
            throws IOException {
        return readGeneric(reader, json, type, genericType);
    }

    @SuppressWarnings("unchecked")
    private Object readGeneric(JqMappedMessageBodyReader reader, String json,
                               Class<?> type, java.lang.reflect.Type genericType) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return reader.readFrom(
                (Class<Object>) type, genericType, null, null, null,
                new ByteArrayInputStream(bytes));
    }

    private String writeJson(Object value) throws IOException {
        return writeGeneric(value, value.getClass(), value.getClass());
    }

    private String writeGeneric(Object value, Class<?> type, java.lang.reflect.Type genericType)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.writeTo(value, type, genericType, null, null, null, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    // ---- Strict dispatch through registered readers (issue #174) ----

    /** Stub reader with scripted outcomes and invocation tracking. */
    static final class StubReader implements io.hyperfoil.tools.jjq.mapper.DirectReader<SimpleUser> {
        int invocations;
        boolean fallBack;

        @Override
        public Class<SimpleUser> type() {
            return SimpleUser.class;
        }

        @Override
        public SimpleUser read(byte[] json, JqMapper mapper) {
            invocations++;
            if (fallBack) throw new io.hyperfoil.tools.jjq.mapper.DirectReadFallbackException();
            return new SimpleUser("direct", 1, true);
        }
    }

    private static JqMappedMessageBodyReader readerWith(StubReader stub) {
        return new JqMappedMessageBodyReader(
                JqMapper.builder().registerDirectReader(stub).build());
    }

    @Test
    void readFrom_registeredReaderServesCanonicalInput() throws IOException {
        StubReader stub = new StubReader();
        Object result = readJson(readerWith(stub),
                "{\"name\":\"Alice\",\"age\":30,\"active\":true}", SimpleUser.class);
        assertEquals(new SimpleUser("direct", 1, true), result);
        assertEquals(1, stub.invocations);
    }

    @Test
    void readFrom_readerFallbackBindsExactly() throws IOException {
        StubReader stub = new StubReader();
        stub.fallBack = true;
        // Coercion binds through the fallback exactly as without a reader.
        Object result = readJson(readerWith(stub),
                "{\"name\":42,\"age\":30,\"active\":true}", SimpleUser.class);
        assertEquals(new SimpleUser("42", 30, true), result);
        assertEquals(1, stub.invocations);
    }

    @Test
    void readFrom_readerFallbackKeepsErrorClassification() throws IOException {
        StubReader stub = new StubReader();
        stub.fallBack = true;
        JqMappedMessageBodyReader withReader = readerWith(stub);
        // Parse errors stay parse errors (400 "Invalid JSON").
        JqBadRequestException parse = assertThrows(JqBadRequestException.class,
                () -> readJson(withReader, "{bad", SimpleUser.class));
        assertEquals(400, parse.status());
        // Binding errors stay binding errors.
        assertThrows(JqBadRequestException.class,
                () -> readJson(withReader, "{\"name\":{},\"age\":30,\"active\":true}", SimpleUser.class));
        // Empty and null bodies still bind null without consulting the reader
        // for empty input; JSON null consults it first, then binds null.
        assertNull(readJson(withReader, "", SimpleUser.class));
        assertNull(readJson(withReader, "null", SimpleUser.class));
        assertEquals(3, stub.invocations);
    }

    @Test
    void readFrom_collectionPathIgnoresElementReaders() throws IOException {
        // Element readers never serve the generic collection path (issue
        // #174 MVP): lists bind element-wise through the tree exactly as
        // without a reader.
        StubReader stub = new StubReader();
        Object result = readGeneric(readerWith(stub),
                "[{\"name\":\"Alice\",\"age\":30,\"active\":true}]",
                java.util.List.class, listOfUsers());
        assertEquals(java.util.List.of(new SimpleUser("Alice", 30, true)), result);
        assertEquals(0, stub.invocations);
    }
}

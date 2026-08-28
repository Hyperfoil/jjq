package io.hyperfoil.tools.jjq.jakarta;

import io.hyperfoil.tools.jjq.mapper.JqField;
import io.hyperfoil.tools.jjq.mapper.JqIgnore;
import io.hyperfoil.tools.jjq.mapper.JqMapped;
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

    // ---- Helpers ----

    @SuppressWarnings("unchecked")
    private Object readJson(String json, Class<?> type) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return reader.readFrom(
                (Class<Object>) type, type, null, null, null,
                new ByteArrayInputStream(bytes));
    }

    private String writeJson(Object value) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.writeTo(value, value.getClass(), value.getClass(), null, null, null, out);
        return out.toString(StandardCharsets.UTF_8);
    }
}

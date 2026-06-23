package io.hyperfoil.tools.jjq.jakarta;

import io.hyperfoil.tools.jjq.value.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class JqValueMessageBodyWriterTest {

    private final JqValueMessageBodyWriter writer = new JqValueMessageBodyWriter();

    @Test
    void isWriteable_jqValueTypes() {
        assertTrue(writer.isWriteable(JqValue.class, null, null, null));
        assertTrue(writer.isWriteable(JqObject.class, null, null, null));
        assertTrue(writer.isWriteable(JqArray.class, null, null, null));
        assertTrue(writer.isWriteable(JqString.class, null, null, null));
        assertFalse(writer.isWriteable(String.class, null, null, null));
        assertFalse(writer.isWriteable(Object.class, null, null, null));
    }

    @Test
    void writeTo_object() throws IOException {
        JqObject obj = JqObject.builder()
                .put("name", "Alice")
                .put("age", 30)
                .build();

        String json = writeToString(obj);
        assertTrue(json.contains("\"name\":\"Alice\""));
        assertTrue(json.contains("\"age\":30"));
    }

    @Test
    void writeTo_array() throws IOException {
        JqArray arr = JqArray.of(JqNumber.of(1), JqNumber.of(2), JqNumber.of(3));

        assertEquals("[1,2,3]", writeToString(arr));
    }

    @Test
    void writeTo_null_producesEmptyOutput() throws IOException {
        var out = new ByteArrayOutputStream();
        writer.writeTo(null, JqValue.class, null, null, null, null, out);

        assertEquals(0, out.size());
    }

    @Test
    void writeTo_scalarValues() throws IOException {
        assertEquals("42", writeToString(JqNumber.of(42)));
        assertEquals("\"hello\"", writeToString(JqString.of("hello")));
        assertEquals("true", writeToString(JqBoolean.TRUE));
        assertEquals("false", writeToString(JqBoolean.FALSE));
        assertEquals("null", writeToString(JqNull.NULL));
    }

    @Test
    void writeTo_nestedStructure() throws IOException {
        JqObject nested = JqObject.builder()
                .put("data", JqObject.builder()
                        .put("items", JqArray.of(
                                JqObject.builder().put("id", 1).build(),
                                JqObject.builder().put("id", 2).build()))
                        .build())
                .build();

        String json = writeToString(nested);
        // Verify it's valid JSON by re-parsing
        JqValue reparsed = JqValues.parse(json);
        assertEquals(nested, reparsed);
    }

    @Test
    void writeTo_stringWithEscapes() throws IOException {
        JqString str = JqString.of("hello\nworld\t!");
        String json = writeToString(str);
        assertTrue(json.contains("\\n"));
        assertTrue(json.contains("\\t"));
    }

    @Test
    void roundTrip_readThenWrite() throws IOException {
        var reader = new JqValueMessageBodyReader();
        String original = "{\"name\":\"Alice\",\"scores\":[95,87,92],\"active\":true}";

        // Read
        JqValue parsed = reader.readFrom(JqValue.class, null, null, null, null,
                new java.io.ByteArrayInputStream(original.getBytes(StandardCharsets.UTF_8)));

        // Write
        String serialized = writeToString(parsed);

        // Re-read and verify equality
        JqValue reparsed = reader.readFrom(JqValue.class, null, null, null, null,
                new java.io.ByteArrayInputStream(serialized.getBytes(StandardCharsets.UTF_8)));
        assertEquals(parsed, reparsed);
    }

    private String writeToString(JqValue value) throws IOException {
        var out = new ByteArrayOutputStream();
        writer.writeTo(value, JqValue.class, null, null, null, null, out);
        return out.toString(StandardCharsets.UTF_8);
    }
}

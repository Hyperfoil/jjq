package io.hyperfoil.tools.jjq.jakarta;

import io.hyperfoil.tools.jjq.value.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class JqValueMessageBodyReaderTest {

    private final JqValueMessageBodyReader reader = new JqValueMessageBodyReader();

    @Test
    void isReadable_jqValueTypes() {
        assertTrue(reader.isReadable(JqValue.class, null, null, null));
        assertTrue(reader.isReadable(JqObject.class, null, null, null));
        assertTrue(reader.isReadable(JqArray.class, null, null, null));
        assertTrue(reader.isReadable(JqString.class, null, null, null));
        assertTrue(reader.isReadable(JqNumber.class, null, null, null));
        assertFalse(reader.isReadable(String.class, null, null, null));
        assertFalse(reader.isReadable(Object.class, null, null, null));
    }

    @Test
    void readFrom_jsonObject() throws IOException {
        String json = "{\"name\":\"Alice\",\"age\":30}";
        JqValue result = reader.readFrom(JqValue.class, null, null, null, null,
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertInstanceOf(JqObject.class, result);
        JqObject obj = (JqObject) result;
        assertEquals("Alice", obj.get("name").stringValue());
        assertEquals(30, ((JqNumber) obj.get("age")).longValue());
    }

    @Test
    void readFrom_jsonArray() throws IOException {
        String json = "[1,2,3]";
        JqValue result = reader.readFrom(JqValue.class, null, null, null, null,
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertInstanceOf(JqArray.class, result);
        assertEquals(3, ((JqArray) result).arrayValue().size());
    }

    @Test
    void readFrom_emptyStream_returnsNull() throws IOException {
        JqValue result = reader.readFrom(JqValue.class, null, null, null, null,
                new ByteArrayInputStream(new byte[0]));

        assertNull(result);
    }

    @Test
    void readFrom_utf8Content() throws IOException {
        String json = "{\"greeting\":\"hello world\"}";
        JqValue result = reader.readFrom(JqValue.class, null, null, null, null,
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertInstanceOf(JqObject.class, result);
        assertEquals("hello world", ((JqObject) result).get("greeting").stringValue());
    }

    @Test
    void readFrom_scalarValues() throws IOException {
        assertEquals(42L, ((JqNumber) readJson("42")).longValue());
        assertEquals("hello", readJson("\"hello\"").stringValue());
        assertInstanceOf(JqNull.class, readJson("null"));
        assertInstanceOf(JqBoolean.class, readJson("true"));
    }

    private JqValue readJson(String json) throws IOException {
        return reader.readFrom(JqValue.class, null, null, null, null,
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }
}

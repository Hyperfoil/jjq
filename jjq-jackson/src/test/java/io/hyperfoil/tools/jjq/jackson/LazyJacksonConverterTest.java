package io.hyperfoil.tools.jjq.jackson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.value.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LazyJacksonConverterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void scalarTypes() throws Exception {
        assertEquals(JqNull.NULL, LazyJacksonConverter.fromJsonNode(MAPPER.readTree("null")));
        assertEquals(JqBoolean.TRUE, LazyJacksonConverter.fromJsonNode(MAPPER.readTree("true")));
        assertEquals(JqString.of("hello"), LazyJacksonConverter.fromJsonNode(MAPPER.readTree("\"hello\"")));
        assertEquals(JqNumber.of(42), LazyJacksonConverter.fromJsonNode(MAPPER.readTree("42")));
    }

    @Test
    void lazyObjectFieldAccess() throws Exception {
        JsonNode node = MAPPER.readTree("{\"name\":\"Alice\",\"age\":30,\"address\":{\"city\":\"NYC\"}}");
        JqValue lazy = LazyJacksonConverter.fromJsonNode(node);

        assertInstanceOf(JqObject.class, lazy);
        // Accessing a field converts only that field
        assertEquals(JqString.of("Alice"), ((JqObject) lazy).objectValue().get("name"));
        assertEquals(JqNumber.of(30), ((JqObject) lazy).objectValue().get("age"));
    }

    @Test
    void lazyNestedObject() throws Exception {
        JsonNode node = MAPPER.readTree("{\"a\":{\"b\":{\"c\":42}}}");
        JqValue lazy = LazyJacksonConverter.fromJsonNode(node);

        // Deep access works through lazy chain
        JqValue a = ((JqObject) lazy).objectValue().get("a");
        assertInstanceOf(JqObject.class, a);
        JqValue b = ((JqObject) a).objectValue().get("b");
        assertInstanceOf(JqObject.class, b);
        JqValue c = ((JqObject) b).objectValue().get("c");
        assertEquals(JqNumber.of(42), c);
    }

    @Test
    void lazyArrayAccess() throws Exception {
        JsonNode node = MAPPER.readTree("[1,2,3,4,5]");
        JqValue lazy = LazyJacksonConverter.fromJsonNode(node);

        assertInstanceOf(JqArray.class, lazy);
        var arr = (JqArray) lazy;
        assertEquals(5, arr.arrayValue().size());
        assertEquals(JqNumber.of(3), arr.get(2));
    }

    @Test
    void lazyArrayWithObjects() throws Exception {
        JsonNode node = MAPPER.readTree("[{\"name\":\"Alice\"},{\"name\":\"Bob\"}]");
        JqValue lazy = LazyJacksonConverter.fromJsonNode(node);

        var arr = (JqArray) lazy;
        JqValue first = arr.get(0);
        assertInstanceOf(JqObject.class, first);
        assertEquals(JqString.of("Alice"), ((JqObject) first).objectValue().get("name"));
    }

    @Test
    void filterOnlyAccessesNeededFields() throws Exception {
        // A large object where the filter only accesses .name
        String json = "{\"name\":\"Alice\",\"bio\":\"" + "x".repeat(10000) + "\",\"data\":[1,2,3]}";
        JsonNode node = MAPPER.readTree(json);
        JqValue lazy = LazyJacksonConverter.fromJsonNode(node);

        JqProgram program = JqProgram.compile(".name");
        List<JqValue> results = program.applyAll(lazy);
        assertEquals(1, results.size());
        assertEquals(JqString.of("Alice"), results.getFirst());
    }

    @Test
    void iterationConvertsAllElements() throws Exception {
        JsonNode node = MAPPER.readTree("[1,2,3]");
        JqValue lazy = LazyJacksonConverter.fromJsonNode(node);

        JqProgram program = JqProgram.compile("[.[] | . * 2]");
        List<JqValue> results = program.applyAll(lazy);
        assertEquals(1, results.size());
        assertEquals("[2,4,6]", results.getFirst().toJsonString());
    }

    @Test
    void emptyObjectAndArray() throws Exception {
        assertEquals(JqObject.EMPTY, LazyJacksonConverter.fromJsonNode(MAPPER.readTree("{}")));
        assertEquals(JqArray.EMPTY, LazyJacksonConverter.fromJsonNode(MAPPER.readTree("[]")));
    }

    @Test
    void engineUsesLazyByDefault() throws Exception {
        JacksonJqEngine engine = new JacksonJqEngine(MAPPER);
        JqProgram program = engine.compile(".name");

        JsonNode input = MAPPER.readTree("{\"name\":\"Alice\",\"unused\":{\"deep\":{\"nested\":true}}}");
        List<JsonNode> results = engine.apply(program, input);
        assertEquals(1, results.size());
        assertEquals("Alice", results.getFirst().textValue());
    }

    @Test
    void toJsonStringWorks() throws Exception {
        JsonNode node = MAPPER.readTree("{\"a\":1,\"b\":[2,3]}");
        JqValue lazy = LazyJacksonConverter.fromJsonNode(node);
        assertEquals("{\"a\":1,\"b\":[2,3]}", lazy.toJsonString());
    }
}

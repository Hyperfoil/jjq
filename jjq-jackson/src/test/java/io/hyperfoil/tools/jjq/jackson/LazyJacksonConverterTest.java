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
    void smallObjectsUseEagerConversionThreshold() throws Exception {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < LazyJacksonConverter.EAGER_OBJECT_FIELD_THRESHOLD; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append("f").append(i).append('"').append(':').append(i);
        }
        sb.append('}');

        JsonNode node = MAPPER.readTree(sb.toString());
        JqObject value = (JqObject) LazyJacksonConverter.fromJsonNode(node);

        assertFalse(value.objectValue().getClass().getName().contains("LazyObjectMap"));
        assertEquals(LazyJacksonConverter.EAGER_OBJECT_FIELD_THRESHOLD, value.objectValue().size());
    }

    @Test
    void largerObjectsStayLazy() throws Exception {
        StringBuilder sb = new StringBuilder("{");
        int fieldCount = LazyJacksonConverter.EAGER_OBJECT_FIELD_THRESHOLD + 1;
        for (int i = 0; i < fieldCount; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append("f").append(i).append('"').append(':').append(i);
        }
        sb.append('}');

        JsonNode node = MAPPER.readTree(sb.toString());
        JqObject value = (JqObject) LazyJacksonConverter.fromJsonNode(node);

        assertTrue(value.objectValue().getClass().getName().contains("LazyObjectMap"));
    }

    @Test
    void defaultThresholdIsExpected() {
        assertEquals(LazyJacksonConverter.DEFAULT_EAGER_OBJECT_FIELD_THRESHOLD,
                LazyJacksonConverter.EAGER_OBJECT_FIELD_THRESHOLD);
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
    void toJsonNodeDoesNotForceFullLazyObjectMaterialization() throws Exception {
        JsonNode node = MAPPER.readTree(largeObjectJson());
        JqObject lazy = (JqObject) JacksonConverter.fromJsonNodeLazy(node);

        assertFalse(LazyJacksonConverter.isFullyConverted(lazy));
        assertEquals(0, LazyJacksonConverter.convertedEntryCount(lazy));

        // Touch one field only
        assertEquals(JqNumber.of(1), lazy.get("a"));
        assertFalse(LazyJacksonConverter.isFullyConverted(lazy));
        assertEquals(1, LazyJacksonConverter.convertedEntryCount(lazy));

        JsonNode restored = JacksonConverter.toJsonNode(lazy);
        assertEquals(node, restored);

        // Still not fully materialized after serialization path
        assertFalse(LazyJacksonConverter.isFullyConverted(lazy));
        assertEquals(1, LazyJacksonConverter.convertedEntryCount(lazy));
    }

    @Test
    void identityPassthroughAfterSingleFieldAccess() throws Exception {
        JsonNode node = MAPPER.readTree(largeObjectJson());
        JqObject lazy = (JqObject) JacksonConverter.fromJsonNodeLazy(node);

        assertEquals(JqNumber.of(2), lazy.get("b"));
        JsonNode restored = JacksonConverter.toJsonNode(lazy);

        assertSame(node, restored);
        assertFalse(LazyJacksonConverter.isFullyConverted(lazy));
        assertEquals(1, LazyJacksonConverter.convertedEntryCount(lazy));
    }

    private static String largeObjectJson() {
        return "{\"a\":1,\"b\":2,\"c\":3,\"d\":4,\"e\":5,\"f\":6,\"g\":7,\"h\":8,\"i\":9,\"j\":10,\"k\":11,\"l\":12,\"m\":13,\"n\":14,\"o\":15,\"p\":16,\"q\":17}";
    }

    @Test
    void keySetIsCachedAndPreservesInsertionOrder() throws Exception {
        JsonNode node = MAPPER.readTree(largeObjectJson());
        JqObject lazy = (JqObject) JacksonConverter.fromJsonNodeLazy(node);

        var keySet1 = lazy.objectValue().keySet();
        var keySet2 = lazy.objectValue().keySet();

        // Same instance returned on repeated calls (cached)
        assertSame(keySet1, keySet2);

        // Preserves insertion order
        var expectedOrder = List.of("a", "b", "c", "d", "e", "f", "g", "h",
                "i", "j", "k", "l", "m", "n", "o", "p", "q");
        assertEquals(expectedOrder, List.copyOf(keySet1));

        // Key set is unmodifiable
        assertThrows(UnsupportedOperationException.class, () -> keySet1.add("z"));
    }

    @Test
    void keySetCachedAfterPartialAccess() throws Exception {
        JsonNode node = MAPPER.readTree(largeObjectJson());
        JqObject lazy = (JqObject) JacksonConverter.fromJsonNodeLazy(node);

        // Access a field first, then check keySet is still correct and cached
        assertEquals(JqNumber.of(3), lazy.get("c"));

        var keySet1 = lazy.objectValue().keySet();
        var keySet2 = lazy.objectValue().keySet();
        assertSame(keySet1, keySet2);
        assertEquals(17, keySet1.size());
    }

    @Test
    void toJsonStringWorks() throws Exception {
        JsonNode node = MAPPER.readTree("{\"a\":1,\"b\":[2,3]}");
        JqValue lazy = LazyJacksonConverter.fromJsonNode(node);
        assertEquals("{\"a\":1,\"b\":[2,3]}", lazy.toJsonString());
    }
}

package io.hyperfoil.tools.jjq.fastjson2;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.hyperfoil.tools.jjq.value.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class FastjsonEngineTest {

    private final FastjsonEngine engine = new FastjsonEngine();

    @Test
    void testSimpleFieldAccess() {
        var results = engine.apply(".name", "{\"name\":\"Alice\",\"age\":30}");
        assertEquals(1, results.size());
        assertEquals(JqString.of("Alice"), results.getFirst());
    }

    @Test
    void testArrayIteration() {
        var results = engine.apply(".[] | . * 2", "[1,2,3]");
        assertEquals(3, results.size());
        assertEquals(JqNumber.of(2), results.get(0));
        assertEquals(JqNumber.of(4), results.get(1));
        assertEquals(JqNumber.of(6), results.get(2));
    }

    @Test
    void testCompileAndReuse() {
        var program = engine.compile(".name");
        var r1 = program.applyAll(FastjsonEngine.fromJson("{\"name\":\"Alice\"}"));
        var r2 = program.applyAll(FastjsonEngine.fromJson("{\"name\":\"Bob\"}"));
        assertEquals(JqString.of("Alice"), r1.getFirst());
        assertEquals(JqString.of("Bob"), r2.getFirst());
    }

    @Test
    void testApplyToStrings() {
        var results = engine.applyToStrings(".name", "{\"name\":\"Alice\"}");
        assertEquals(List.of("\"Alice\""), results);
    }

    @Test
    void testApplyToBytes() {
        byte[] input = "{\"x\":42}".getBytes(StandardCharsets.UTF_8);
        byte[] output = engine.applyToBytes(".x", input);
        assertEquals("42", new String(output, StandardCharsets.UTF_8));
    }

    @Test
    void testFromFastjsonObject() {
        var obj = new JSONObject();
        obj.put("name", "Alice");
        obj.put("age", 30);
        JqValue value = FastjsonEngine.fromFastjson(obj);
        assertInstanceOf(JqObject.class, value);
        assertEquals(JqString.of("Alice"), ((JqObject) value).get("name"));
    }

    @Test
    void testToFastjson() {
        var jqObj = JqObject.of("name", JqString.of("Alice"));
        Object fj = FastjsonEngine.toFastjson(jqObj);
        assertInstanceOf(JSONObject.class, fj);
        assertEquals("Alice", ((JSONObject) fj).getString("name"));
    }

    @Test
    void testComplexQuery() {
        String json = """
                {
                    "users": [
                        {"name": "Alice", "age": 30},
                        {"name": "Bob", "age": 25},
                        {"name": "Carol", "age": 35}
                    ]
                }
                """;
        var results = engine.apply("[.users[] | select(.age > 28) | .name]", json);
        assertEquals(1, results.size());
        assertEquals("[\"Alice\",\"Carol\"]", results.getFirst().toJsonString());
    }

    @Test
    void testMapWithTransform() {
        var results = engine.apply("map(. + 10)", "[1,2,3]");
        assertEquals("[11,12,13]", results.getFirst().toJsonString());
    }

    @Test
    void testReduceSum() {
        var results = engine.apply("reduce .[] as $x (0; . + $x)", "[1,2,3,4,5]");
        assertEquals(JqNumber.of(15), results.getFirst());
    }

    @Test
    void testObjectConstruction() {
        var results = engine.apply("{name, email}",
                "{\"name\":\"Alice\",\"email\":\"alice@example.com\",\"age\":30}");
        assertEquals("{\"name\":\"Alice\",\"email\":\"alice@example.com\"}",
                results.getFirst().toJsonString());
    }

    @Test
    void testStringInterpolation() {
        // String interpolation is a Phase 1 feature but complex to test
        // For now test basic string operations
        var results = engine.apply(".name | ascii_upcase",
                "{\"name\":\"alice\"}");
        assertEquals(JqString.of("ALICE"), results.getFirst());
    }

    @Nested
    class LazyConversion {
        @Test
        void lazyObjectFieldAccess() {
            var obj = new JSONObject();
            obj.put("name", "Alice");
            obj.put("age", 30);
            JqValue value = FastjsonEngine.fromFastjsonLazy(obj);
            assertInstanceOf(JqObject.class, value);
            assertEquals(JqString.of("Alice"), ((JqObject) value).get("name"));
            assertEquals(JqNumber.of(30), ((JqObject) value).get("age"));
        }

        @Test
        void lazyArrayIndexAccess() {
            var arr = new JSONArray();
            arr.add(1);
            arr.add(2);
            arr.add(3);
            JqValue value = FastjsonEngine.fromFastjsonLazy(arr);
            assertInstanceOf(JqArray.class, value);
            assertEquals(3, ((JqArray) value).arrayValue().size());
            assertEquals(JqNumber.of(2), ((JqArray) value).arrayValue().get(1));
        }

        @Test
        void lazyNestedObjectsStayLazy() {
            var inner = new JSONObject();
            inner.put("x", 42);
            var outer = new JSONObject();
            outer.put("nested", inner);
            JqValue value = FastjsonEngine.fromFastjsonLazy(outer);
            JqValue nested = ((JqObject) value).get("nested");
            assertInstanceOf(JqObject.class, nested);
            assertEquals(JqNumber.of(42), ((JqObject) nested).get("x"));
        }

        @Test
        void lazyFromJsonString() {
            JqValue value = FastjsonEngine.fromJsonLazy("{\"a\":1,\"b\":[2,3]}");
            assertInstanceOf(JqObject.class, value);
            assertEquals(JqNumber.of(1), ((JqObject) value).get("a"));
            JqValue b = ((JqObject) value).get("b");
            assertInstanceOf(JqArray.class, b);
            assertEquals(JqNumber.of(3), ((JqArray) b).arrayValue().get(1));
        }

        @Test
        void lazyConversionProducesCorrectResults() {
            // Applying a jq filter through lazy conversion should produce same results
            var program = engine.compile(".users[] | .name");
            String json = "{\"users\":[{\"name\":\"Alice\"},{\"name\":\"Bob\"}]}";
            JqValue lazyInput = FastjsonEngine.fromJsonLazy(json);
            JqValue eagerInput = FastjsonEngine.fromJson(json);
            assertEquals(program.applyAll(eagerInput), program.applyAll(lazyInput));
        }
    }

    @Nested
    class ByteBufferMode {
        @Test
        void applyToBytesWithProgram() {
            var program = engine.compile(".x + 1");
            byte[] input = "{\"x\":41}".getBytes(StandardCharsets.UTF_8);
            byte[] output = engine.applyToBytes(program, input);
            assertEquals("42", new String(output, StandardCharsets.UTF_8));
        }

        @Test
        void applyToBytesWithOffsetLength() {
            var program = engine.compile(".a");
            // Embed JSON in a larger buffer with padding
            String padding = "XXXXX";
            String json = "{\"a\":99}";
            byte[] buffer = (padding + json + padding).getBytes(StandardCharsets.UTF_8);
            byte[] output = engine.applyToBytes(program, buffer, padding.length(), json.length());
            assertEquals("99", new String(output, StandardCharsets.UTF_8));
        }

        @Test
        void applyBufferStream() {
            var program = engine.compile(".[] | . * 2");
            byte[] buffer = "[1,2,3]".getBytes(StandardCharsets.UTF_8);
            List<JqValue> results = engine.applyBuffer(program, buffer, 0, buffer.length).toList();
            assertEquals(3, results.size());
            assertEquals(JqNumber.of(2), results.get(0));
            assertEquals(JqNumber.of(4), results.get(1));
            assertEquals(JqNumber.of(6), results.get(2));
        }
    }

    @Nested
    class JsonStreamProcessing {
        @Test
        void multipleJsonValuesFromStream() {
            var program = engine.compile(". + 1");
            byte[] input = "1\n2\n3".getBytes(StandardCharsets.UTF_8);
            List<JqValue> results = engine.applyToJsonStream(program, input, 0, input.length).toList();
            assertEquals(3, results.size());
            assertEquals(JqNumber.of(2), results.get(0));
            assertEquals(JqNumber.of(3), results.get(1));
            assertEquals(JqNumber.of(4), results.get(2));
        }

        @Test
        void multipleJsonObjectsFromStream() {
            var program = engine.compile(".name");
            String input = "{\"name\":\"Alice\"}\n{\"name\":\"Bob\"}";
            byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
            List<JqValue> results = engine.applyToJsonStream(program, bytes, 0, bytes.length).toList();
            assertEquals(2, results.size());
            assertEquals(JqString.of("Alice"), results.get(0));
            assertEquals(JqString.of("Bob"), results.get(1));
        }

        @Test
        void streamFromInputStream() {
            var program = engine.compile(". * 10");
            var is = new ByteArrayInputStream("5\n7".getBytes(StandardCharsets.UTF_8));
            List<JqValue> results = engine.applyToJsonStream(program, is).toList();
            assertEquals(2, results.size());
            assertEquals(JqNumber.of(50), results.get(0));
            assertEquals(JqNumber.of(70), results.get(1));
        }

        @Test
        void emptyStreamReturnsEmpty() {
            var program = engine.compile(".");
            byte[] input = "   ".getBytes(StandardCharsets.UTF_8);
            List<JqValue> results = engine.applyToJsonStream(program, input, 0, input.length).toList();
            assertTrue(results.isEmpty());
        }
    }
}

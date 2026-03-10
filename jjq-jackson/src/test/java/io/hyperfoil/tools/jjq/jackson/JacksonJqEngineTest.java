package io.hyperfoil.tools.jjq.jackson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.evaluator.Environment;
import io.hyperfoil.tools.jjq.value.JqString;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JacksonJqEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final JacksonJqEngine engine = new JacksonJqEngine(MAPPER);

    @Test
    void simpleFieldAccess() throws Exception {
        JsonNode input = MAPPER.readTree("{\"name\":\"Alice\",\"age\":30}");
        List<JsonNode> results = engine.apply(".name", input);
        assertEquals(1, results.size());
        assertEquals("Alice", results.getFirst().textValue());
    }

    @Test
    void arrayIteration() throws Exception {
        JsonNode input = MAPPER.readTree("{\"users\":[{\"name\":\"Alice\"},{\"name\":\"Bob\"}]}");
        List<JsonNode> results = engine.apply(".users[] | .name", input);
        assertEquals(2, results.size());
        assertEquals("Alice", results.get(0).textValue());
        assertEquals("Bob", results.get(1).textValue());
    }

    @Test
    void objectConstruction() throws Exception {
        JsonNode input = MAPPER.readTree("{\"first\":\"Alice\",\"last\":\"Smith\",\"age\":30}");
        List<JsonNode> results = engine.apply("{name: (.first + \" \" + .last), age}", input);
        assertEquals(1, results.size());
        JsonNode result = results.getFirst();
        assertEquals("Alice Smith", result.get("name").textValue());
        assertEquals(30, result.get("age").intValue());
    }

    @Test
    void filterWithSelect() throws Exception {
        JsonNode input = MAPPER.readTree("[1,2,3,4,5,6,7,8,9,10]");
        List<JsonNode> results = engine.apply("[.[] | select(. > 5)]", input);
        assertEquals(1, results.size());
        assertEquals(5, results.getFirst().size());
    }

    @Test
    void reduce() throws Exception {
        JsonNode input = MAPPER.readTree("[1,2,3,4,5]");
        List<JsonNode> results = engine.apply("reduce .[] as $x (0; . + $x)", input);
        assertEquals(1, results.size());
        assertEquals(15, results.getFirst().intValue());
    }

    @Test
    void precompiledProgram() throws Exception {
        JqProgram program = engine.compile(".name");

        JsonNode input1 = MAPPER.readTree("{\"name\":\"Alice\"}");
        JsonNode input2 = MAPPER.readTree("{\"name\":\"Bob\"}");

        assertEquals("Alice", engine.applyFirst(program, input1).textValue());
        assertEquals("Bob", engine.applyFirst(program, input2).textValue());
    }

    @Test
    void withVariables() throws Exception {
        JqProgram program = engine.compile(".[] | select(.name == $target)");
        JsonNode input = MAPPER.readTree("[{\"name\":\"Alice\"},{\"name\":\"Bob\"},{\"name\":\"Charlie\"}]");

        Environment env = new Environment();
        env.setVariable("target", JqString.of("Bob"));

        List<JsonNode> results = engine.apply(program, input, env);
        assertEquals(1, results.size());
        assertEquals("Bob", results.getFirst().get("name").textValue());
    }

    @Test
    void applyFromString() throws Exception {
        List<JsonNode> results = engine.apply(".name", "{\"name\":\"Alice\"}");
        assertEquals(1, results.size());
        assertEquals("Alice", results.getFirst().textValue());
    }

    @Test
    void applyToString() throws Exception {
        JqProgram program = engine.compile("{name: .name, upper: (.name | ascii_upcase)}");
        JsonNode input = MAPPER.readTree("{\"name\":\"alice\"}");
        String result = engine.applyToString(program, input);
        assertTrue(result.contains("\"name\""));
        assertTrue(result.contains("\"ALICE\""));
    }

    @Test
    void nullInput() {
        List<JsonNode> results = engine.apply(".", (JsonNode) null);
        assertEquals(1, results.size());
        assertTrue(results.getFirst().isNull());
    }

    @Test
    void complexTransformation() throws Exception {
        String json = """
                {
                  "orders": [
                    {"id": 1, "items": [{"price": 10}, {"price": 20}]},
                    {"id": 2, "items": [{"price": 30}]}
                  ]
                }
                """;
        JsonNode input = MAPPER.readTree(json);
        JqProgram program = engine.compile(
                ".orders[] | {id, total: ([.items[].price] | add)}");
        List<JsonNode> results = engine.apply(program, input);
        assertEquals(2, results.size());
        assertEquals(1, results.get(0).get("id").intValue());
        assertEquals(30, results.get(0).get("total").intValue());
        assertEquals(2, results.get(1).get("id").intValue());
        assertEquals(30, results.get(1).get("total").intValue());
    }
}

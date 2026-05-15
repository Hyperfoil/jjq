package io.hyperfoil.tools.jjq.jackson;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.evaluator.Environment;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.List;

/**
 * High-level jq engine with Jackson integration.
 * Thread-safe: compiled programs are cached and immutable.
 *
 * <pre>{@code
 * JacksonJqEngine engine = new JacksonJqEngine(objectMapper);
 *
 * // One-shot: parse, compile, execute, convert back
 * List<JsonNode> results = engine.apply(".users[] | .name", jsonNode);
 *
 * // Pre-compiled for repeated use
 * JqProgram program = engine.compile(".users[] | {name, email}");
 * List<JsonNode> r1 = engine.apply(program, request1);
 * List<JsonNode> r2 = engine.apply(program, request2);
 * }</pre>
 */
public final class JacksonJqEngine {

    private final ObjectMapper mapper;

    public JacksonJqEngine() {
        this(new ObjectMapper());
    }

    public JacksonJqEngine(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Compile a jq filter expression. The returned program is thread-safe
     * and should be reused across requests.
     */
    public JqProgram compile(String expression) {
        return JqProgram.compile(expression);
    }

    /**
     * Apply a jq filter to a Jackson JsonNode, returning results as JsonNodes.
     * For repeated use with the same filter, prefer {@link #compile(String)} + {@link #apply(JqProgram, JsonNode)}.
     */
    public List<JsonNode> apply(String expression, JsonNode input) {
        return apply(compile(expression), input);
    }

    /**
     * Apply a pre-compiled program to a Jackson JsonNode.
     */
    public List<JsonNode> apply(JqProgram program, JsonNode input) {
        JqValue jqInput = JacksonConverter.fromJsonNodeLazy(input);
        List<JqValue> results = program.applyAll(jqInput);
        return results.stream()
                .map(v -> toJsonNodeWithPassthrough(v, jqInput, input))
                .toList();
    }

    /**
     * Apply a pre-compiled program with variables (e.g., from query parameters).
     */
    public List<JsonNode> apply(JqProgram program, JsonNode input, Environment env) {
        JqValue jqInput = JacksonConverter.fromJsonNodeLazy(input);
        List<JqValue> results = program.applyAll(jqInput, env);
        return results.stream()
                .map(v -> toJsonNodeWithPassthrough(v, jqInput, input))
                .toList();
    }

    /**
     * Apply a jq filter and return the first result.
     * Returns {@code NullNode} if the filter produces no output.
     */
    public JsonNode applyFirst(JqProgram program, JsonNode input) {
        JqValue jqInput = JacksonConverter.fromJsonNodeLazy(input);
        JqValue result = program.apply(jqInput);
        return toJsonNodeWithPassthrough(result, jqInput, input);
    }

    /**
     * Convert a JqValue back to JsonNode, with a fast path for identity passthrough.
     * If the result is the same JqValue instance as the input (identity filter, or
     * passthrough), return the original JsonNode directly without reconstruction.
     */
    private JsonNode toJsonNodeWithPassthrough(JqValue result, JqValue jqInput, JsonNode originalInput) {
        if (result == jqInput && originalInput != null) return originalInput;
        return JacksonConverter.toJsonNode(result, mapper);
    }

    /**
     * Apply a jq filter to a JSON string, returning results as JsonNodes.
     */
    public List<JsonNode> apply(String expression, String json) throws JsonProcessingException {
        JsonNode input = mapper.readTree(json);
        return apply(expression, input);
    }

    /**
     * Apply a jq filter and return results as a JSON string.
     */
    public String applyToString(JqProgram program, JsonNode input) {
        List<JsonNode> results = apply(program, input);
        var sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(results.get(i).toString());
        }
        return sb.toString();
    }

    /**
     * The ObjectMapper used by this engine.
     */
    public ObjectMapper mapper() {
        return mapper;
    }
}

package io.hyperfoil.tools.jjq.jakarta;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.Test;

import io.quarkiverse.httpproblem.HttpProblem;
import io.quarkiverse.httpproblem.validation.Violation;

/**
 * {@code JqProblemWriter} must render byte-identical bodies to the
 * engine's {@code JsonbProblemSerializer} across shapes, so dropping
 * {@code quarkus-jsonb} changes no wire bytes (issue #176).
 */
class JqProblemWriterTest {

    private final JqProblemWriter writer = new JqProblemWriter();

    static HttpProblem forbidden() {
        return HttpProblem.builder()
                .withStatus(Response.Status.FORBIDDEN)
                .withTitle("Forbidden")
                .build();
    }

    static HttpProblem forbiddenWithDetail() {
        return HttpProblem.builder()
                .withStatus(Response.Status.FORBIDDEN)
                .withTitle("Forbidden")
                .withDetail("Missing role admin")
                .withInstance(URI.create("/api/heroes/1"))
                .build();
    }

    static HttpProblem validation() {
        // Note: no null-valued parameters — the engine's own
        // ctx.serialize(key, null) fails with "incomplete JSON", so nulls
        // have no engine baseline to diff against.
        Map<String, Object> errors = new LinkedHashMap<>();
        errors.put("name", "size must be between 3 and 50");
        errors.put("level", "must be greater than 0");
        return HttpProblem.builder()
                .withStatus(Response.Status.BAD_REQUEST)
                .withTitle("Constraint Violation")
                .withDetail("Invalid hero")
                .with("violations", errors)
                .with("count", 2)
                .with("flag", true)
                .build();
    }

    static HttpProblem serverError() {
        return HttpProblem.builder()
                .withStatus(Response.Status.INTERNAL_SERVER_ERROR)
                .withTitle("Internal Server Error")
                .withType(URI.create("https://example.com/probs/out-of-credit"))
                .build();
    }

    static HttpProblem validationViolations() {
        // Real http-problem value types: Violation has public final fields
        // but no no-arg constructor, so the generic POJO mapper cannot bind
        // it — the writer maps it explicitly (nested list included).
        List<Violation> violations = List.of(
                Violation.In.body.field("name").message("must not be blank"),
                Violation.In.query.field("page").message("must be greater than 0"));
        return HttpProblem.builder()
                .withStatus(Response.Status.BAD_REQUEST)
                .withTitle("Constraint Violation")
                .withDetail("Invalid query")
                .with("violations", violations)
                .with("nested", Map.of("issues", violations))
                .build();
    }

    static String engineRender(HttpProblem problem) throws Exception {
        Jsonb jsonb = JsonbBuilder.newBuilder().withConfig(new JsonbConfig()
                .withSerializers(
                        new io.quarkiverse.httpproblem.jsonb.JsonbProblemSerializer()))
                .build();
        try {
            return jsonb.toJson(problem);
        } finally {
            jsonb.close();
        }
    }

    static String writerRender(JqProblemWriter writer, HttpProblem problem) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(writer.isWriteable(HttpProblem.class, HttpProblem.class, null,
                null));
        writer.writeTo(problem, HttpProblem.class, HttpProblem.class, null,
                null, null, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void rendersByteIdenticalToEngine() throws Exception {
        for (HttpProblem problem : List.of(
                forbidden(), forbiddenWithDetail(), validation(), validationViolations(), serverError())) {
            assertEquals(engineRender(problem), writerRender(writer, problem),
                    "problem shape diverged: " + problem.getTitle());
        }
    }

    @Test
    void writeToNullWritesNothing() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.writeTo(null, HttpProblem.class, HttpProblem.class, null,
                null, null, out);
        assertEquals(0, out.size());
    }
}

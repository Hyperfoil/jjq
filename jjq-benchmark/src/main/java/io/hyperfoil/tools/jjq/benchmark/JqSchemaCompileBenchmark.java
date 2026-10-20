package io.hyperfoil.tools.jjq.benchmark;

import io.hyperfoil.tools.jjq.schema.JqSchema;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Schema compilation cost (issue #167): schema tree to immutable
 * validator, with and without schema parsing.
 *
 * <p>{@code parseAndCompile} includes JSON parsing of the schema text;
 * {@code compile} starts from an already-parsed tree. The {@code run}
 * schema is Hyperfoil's real draft-07 run schema (9KB, 11 local refs).
 *
 * <h3>Running</h3>
 * <pre>
 *   mvn package -pl jjq-core,jjq-schema,jjq-benchmark -DskipTests -Pbenchmark
 *   java -jar jjq-benchmark/target/jjq-benchmark-*.jar JqSchemaCompileBenchmark -prof gc -rf json -rff schema-compile.json
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgs = {"-Xmx2g", "-Xms2g"})
@State(Scope.Benchmark)
public class JqSchemaCompileBenchmark {

    @Param({"tiny", "object20", "run"})
    String schema;

    private JqValue schemaTree;
    private byte[] schemaBytes;

    @Setup
    public void setup() {
        String text;
        switch (schema) {
            case "tiny" -> text = "{\"type\":\"object\",\"required\":[\"a\"],"
                    + "\"properties\":{\"a\":{\"type\":\"string\"}},\"additionalProperties\":false}";
            case "object20" -> text = objectSchema(20);
            case "run" -> text = resource("benchmark-data/schema-run.json");
            default -> throw new IllegalStateException("unknown schema " + schema);
        }
        schemaBytes = text.getBytes(StandardCharsets.UTF_8);
        schemaTree = JqValues.parse(schemaBytes);
        // Precondition: the fixture compiles before timing.
        JqSchema.compile(schemaTree);
    }

    private static String objectSchema(int fields) {
        StringBuilder sb = new StringBuilder("{\"type\":\"object\",\"required\":[");
        for (int i = 0; i < fields; i++) {
            if (i > 0) sb.append(',');
            sb.append("\"f").append(i).append('"');
        }
        sb.append("],\"properties\":{");
        for (int i = 0; i < fields; i++) {
            if (i > 0) sb.append(',');
            sb.append("\"f").append(i).append("\":{\"type\":\"string\"}");
        }
        return sb.append("},\"additionalProperties\":false}").toString();
    }

    private static String resource(String name) {
        try (var in = JqSchemaCompileBenchmark.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("missing resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Benchmark
    public JqSchema compile() {
        return JqSchema.compile(schemaTree);
    }

    @Benchmark
    public JqSchema parseAndCompile() {
        return JqSchema.compile(JqValues.parse(schemaBytes));
    }
}

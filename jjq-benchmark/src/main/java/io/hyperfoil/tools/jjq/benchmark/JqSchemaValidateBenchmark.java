package io.hyperfoil.tools.jjq.benchmark;

import io.hyperfoil.tools.jjq.schema.JqSchema;
import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Schema validation cost (issue #167), split so each row measures one
 * thing: boolean verdicts on preparsed trees, reporting verdicts,
 * fresh parse plus validation (first materialization included), and
 * error serialization of precomputed diagnostics.
 *
 * <p>{@code invalidEarly} fails on the first visited field,
 * {@code invalidLate} on the last; repeated validation of preparsed
 * trees reuses materialized values, so first-use cost is only visible
 * in {@code parseAndValidate}.</p>
 *
 * <h3>Running</h3>
 * <pre>
 *   mvn package -pl jjq-core,jjq-schema,jjq-benchmark -DskipTests -Pbenchmark
 *   java -jar jjq-benchmark/target/jjq-benchmark-*.jar JqSchemaValidateBenchmark -prof gc -rf json -rff schema-validate.json
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgs = {"-Xmx2g", "-Xms2g"})
@State(Scope.Benchmark)
public class JqSchemaValidateBenchmark {

    @Param({"tiny", "object20", "run"})
    String schema;

    @Param({"valid", "invalidEarly", "invalidLate"})
    String doc;

    private JqSchema compiled;
    private JqValue document;
    private byte[] documentBytes;
    private JqArray errors;

    @Setup
    public void setup() {
        String schemaText;
        String docText;
        switch (schema) {
            case "tiny" -> {
                schemaText = "{\"type\":\"object\",\"required\":[\"a\"],"
                        + "\"properties\":{\"a\":{\"type\":\"string\"}},\"additionalProperties\":false}";
                docText = switch (doc) {
                    case "valid" -> "{\"a\":\"x\"}";
                    default -> "{\"a\":1}";
                };
            }
            case "object20" -> {
                schemaText = objectSchema(20);
                docText = switch (doc) {
                    case "valid" -> objectDoc(20, -1);
                    case "invalidEarly" -> objectDoc(20, 0);
                    default -> objectDoc(20, 19);
                };
            }
            case "run" -> {
                schemaText = resource("benchmark-data/schema-run.json");
                String info = "{\"id\":\"ABCD\",\"benchmark\":\"b\",\"startTime\":1,\"terminateTime\":2}";
                docText = switch (doc) {
                    case "valid" -> "{\"info\":" + info + ",\"total\":[],\"phase\":{}}";
                    case "invalidEarly" -> "{\"info\":{\"id\":1,\"benchmark\":\"b\","
                            + "\"startTime\":1,\"terminateTime\":2},\"total\":[],\"phase\":{}}";
                    default -> "{\"info\":" + info + ",\"total\":[],\"phase\":{},\"zzz\":1}";
                };
            }
            default -> throw new IllegalStateException("unknown schema " + schema);
        }
        compiled = JqSchema.compile(JqValues.parse(schemaText));
        documentBytes = docText.getBytes(StandardCharsets.UTF_8);
        document = JqValues.parse(documentBytes);
        // Preconditions: the matrix says what each cell must do.
        boolean expected = doc.equals("valid");
        if (compiled.isValid(document) != expected) {
            throw new IllegalStateException(
                    "fixture mismatch: " + schema + "/" + doc + " expected valid=" + expected);
        }
        errors = compiled.validate(document);
        if ((errors.size() == 0) == !expected) {
            throw new IllegalStateException(
                    "error mismatch: " + schema + "/" + doc + " expected valid=" + expected);
        }
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

    private static String objectDoc(int fields, int wrong) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < fields; i++) {
            if (i > 0) sb.append(',');
            sb.append("\"f").append(i).append("\":");
            sb.append(i == wrong ? "1" : "\"v\"");
        }
        return sb.append('}').toString();
    }

    private static String resource(String name) {
        try (var in = JqSchemaValidateBenchmark.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("missing resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Benchmark
    public boolean validateBoolean() {
        return compiled.isValid(document);
    }

    @Benchmark
    public int validateReport() {
        return compiled.validate(document).size();
    }

    @Benchmark
    public boolean parseAndValidate() {
        return compiled.isValid(JqValues.parse(documentBytes));
    }

    @Benchmark
    public String serializeErrors() {
        return errors.toJsonString();
    }
}

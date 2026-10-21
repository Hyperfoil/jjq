package io.hyperfoil.tools.jjq.benchmark;

import io.hyperfoil.tools.jjq.mapper.BindingSchema;
import io.hyperfoil.tools.jjq.mapper.DirectReadFallbackException;
import io.hyperfoil.tools.jjq.mapper.DirectReader;
import io.hyperfoil.tools.jjq.mapper.JqMapped;
import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import io.quarkus.gizmo2.ClassOutput;
import org.openjdk.jmh.annotations.*;

import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Bytes-to-DTO deserialization: strict parse plus generated mapping (today)
 * against generated direct readers (issue #170), at matched strictness.
 *
 * <p>Both sides enforce the same contract — full strict validation,
 * required presence, and identical binding behavior — so the comparison
 * measures removed work (no {@code JqValue} tree, no second traversal),
 * not weakened validation. Fallback and error rows pin the cost of leaving
 * the fast path: they must stay near the baseline, never silently complete.</p>
 *
 * <p>Readers are emitted by the same generator the augmentation uses
 * ({@code JjqProcessor.generateDirectReader}) and loaded here; setup fails
 * loudly unless every canonical document completes and every fallback
 * document falls back.</p>
 *
 * <h3>Running</h3>
 * <pre>
 * mvn package -Pbenchmark -DskipTests
 * java -jar jjq-benchmark/target/jjq-benchmark-*.jar JqDirectReaderBenchmark -prof gc -rf json -rff direct.json
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(value = 3, jvmArgs = {"-Xmx2g", "-Xms2g"})
@State(Scope.Benchmark)
public class JqDirectReaderBenchmark {

    // ========================================================================
    //  Eligible fixture types (flat, scalar-only, like real request DTOs)
    // ========================================================================

    @JqMapped
    public record Flat5(String name, int age, long score, boolean active, Integer optAge) {}

    @JqMapped
    public record Flat20(String s00, int i01, long l02, boolean b03, Integer e04,
            String s05, int i06, long l07, boolean b08, Long e09,
            String s10, int i11, long l12, boolean b13, Boolean e14,
            String s15, int i16, long l17, boolean b18, String s19) {}

    // ========================================================================
    //  Corpus (order matters: canonical rows must complete, rest must fall back)
    // ========================================================================

    /** Which fixture the current iteration decodes. */
    @Param({"flat5", "flat20"})
    public String fixture;

    /**
     * Document shape. Canonical shapes complete through the reader;
     * {@code coerce} and {@code unknown} fall back (the fallback outcome
     * always equals the baseline outcome).
     */
    @Param({"ordered", "reordered", "partial", "nulls", "escaped", "unicode",
            "coerce", "unknown"})
    public String shape;

    private byte[] document;
    private Class<?> targetType;
    private DirectReader<?> reader;
    private JqMapper baselineMapper;
    private JqMapper dispatchMapper;

    private static String flat5(String shape) {
        return switch (shape) {
            case "ordered" -> "{\"name\":\"ann\",\"age\":30,\"score\":100,\"active\":true,\"optAge\":7}";
            case "reordered" -> "{\"optAge\":7,\"active\":true,\"score\":100,\"age\":30,\"name\":\"ann\"}";
            case "partial" -> "{\"name\":\"ann\"}";
            case "nulls" -> "{\"name\":null,\"age\":5,\"score\":null,\"active\":null}";
            case "escaped" -> "{\"name\":\"a\\\"b\\\\c\\/d\\u00e9\",\"age\":1,\"score\":2,\"active\":false}";
            case "unicode" -> "{\"name\":\"caf\u00e9\u4e2d\ud83d\ude00\",\"age\":1,\"score\":2,\"active\":false}";
            case "coerce" -> "{\"name\":42,\"age\":1,\"score\":2,\"active\":false}";
            case "unknown" -> "{\"name\":\"ann\",\"age\":1,\"score\":2,\"active\":false,\"extra\":1}";
            default -> throw new IllegalArgumentException(shape);
        };
    }

    private static String flat20(String shape) {
        String ordered = "{\"s00\":\"a\",\"i01\":1,\"l02\":2,\"b03\":true,\"e04\":4,"
                + "\"s05\":\"e\",\"i06\":6,\"l07\":7,\"b08\":false,\"e09\":9,"
                + "\"s10\":\"j\",\"i11\":11,\"l12\":12,\"b13\":true,\"e14\":null,"
                + "\"s15\":\"o\",\"i16\":16,\"l17\":17,\"b18\":false,\"s19\":\"s\"}";
        return switch (shape) {
            case "ordered" -> ordered;
            case "reordered" ->
                    "{\"s19\":\"s\",\"b18\":false,\"l17\":17,\"i16\":16,\"s15\":\"o\",\"e14\":null,"
                    + "\"b13\":true,\"l12\":12,\"i11\":11,\"s10\":\"j\",\"e09\":9,\"b08\":false,"
                    + "\"l07\":7,\"i06\":6,\"s05\":\"e\",\"e04\":4,\"b03\":true,\"l02\":2,"
                    + "\"i01\":1,\"s00\":\"a\"}";
            case "partial" -> "{\"s00\":\"a\",\"i01\":1}";
            case "nulls" -> "{\"s00\":null,\"i01\":1,\"l02\":null,\"b03\":null,\"e04\":null}";
            case "escaped" -> ordered.replace("\"a\"", "\"a\\\"b\"");
            case "unicode" -> ordered.replace("\"a\"", "\"caf\u00e9\"");
            case "coerce" -> ordered.replace("\"i01\":1", "\"i01\":\"1\"");
            case "unknown" -> ordered.replaceFirst("\\}$", ",\"zz\":1}");
            default -> throw new IllegalArgumentException(shape);
        };
    }

    /** Canonical shapes complete; everything else falls back or throws. */
    private static boolean expectComplete(String shape) {
        return switch (shape) {
            case "ordered", "reordered", "partial", "nulls", "escaped", "unicode" -> true;
            default -> false;
        };
    }

    @Setup
    public void setup() throws Throwable {
        String json = fixture.equals("flat5") ? flat5(shape) : flat20(shape);
        document = json.getBytes(StandardCharsets.UTF_8);
        targetType = fixture.equals("flat5") ? Flat5.class : Flat20.class;

        baselineMapper = JqMapper.create();
        // Warm the baseline mapping (generated _JqMapping discovery included).
        baselineMapper.fromJqValue(JqValues.parseStrict(documentFor("ordered")), targetType);

        BindingSchema schema = baselineMapper.bindingSchemaFor(targetType);
        if (!schema.eligibleForDirectReader()) {
            throw new IllegalStateException("fixture lost eligibility: "
                    + schema.ineligibilityReasons());
        }
        Map<String, byte[]> classes = new LinkedHashMap<>();
        ClassOutput output = classes::put;
        io.hyperfoil.tools.jjq.quarkus.deployment.JjqProcessor.generateDirectReader(
                output, targetType, schema);
        if (classes.size() != 1) {
            throw new IllegalStateException("expected one reader class, got " + classes.keySet());
        }
        byte[] readerBytes = classes.values().iterator().next();
        // Anchor on this class (not getClass()): JMH runs a generated
        // subclass in another package, which cannot define our package mate.
        Class<?> readerClass = MethodHandles.privateLookupIn(JqDirectReaderBenchmark.class,
                        MethodHandles.lookup())
                .defineClass(readerBytes);
        @SuppressWarnings("unchecked")
        DirectReader<Object> generated = (DirectReader<Object>) readerClass.getDeclaredConstructor()
                .newInstance();
        if (!generated.type().equals(targetType)) {
            throw new IllegalStateException("reader serves " + generated.type());
        }
        reader = generated;
        dispatchMapper = JqMapper.builder().registerDirectReader(generated).build();

        // Pin the route per shape: canonical documents must complete through
        // the reader (a silent fallback here would measure the baseline
        // twice), fallback documents must fall back (never complete).
        boolean completed;
        try {
            generated.read(document, dispatchMapper);
            completed = true;
        } catch (DirectReadFallbackException | IllegalArgumentException e) {
            // Fallback signal, or a reader-shared strict site throwing
            // identically to the baseline.
            completed = false;
        }
        if (completed != expectComplete(shape)) {
            throw new IllegalStateException(
                    "shape " + shape + " completed=" + completed + " (fixture " + fixture + ")");
        }
    }

    private byte[] documentFor(String canonicalShape) {
        String json = fixture.equals("flat5") ? flat5(canonicalShape) : flat20(canonicalShape);
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** Today's path: strict parse of the whole document, then bind. */
    @Benchmark
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object baselineParseMap() {
        return ((JqMapper) baselineMapper).fromJqValue(
                JqValues.parseStrict(document), (Class) targetType);
    }

    /** Control: strict parse alone, for decomposing parse vs bind cost. */
    @Benchmark
    public JqValue baselineParseOnly() {
        return JqValues.parseStrict(document);
    }

    /** Candidate: generated direct strict reader, no tree. */
    @Benchmark
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object directRead() {
        return ((DirectReader<Object>) reader).read(document, dispatchMapper);
    }

    /** REST path: dispatch through the mapper (reader lookup + fallback). */
    @Benchmark
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object strictDispatch() {
        return ((JqMapper) dispatchMapper).fromJsonStrict(document, (Class) targetType);
    }
}

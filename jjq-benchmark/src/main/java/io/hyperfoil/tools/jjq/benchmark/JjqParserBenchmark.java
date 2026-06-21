package io.hyperfoil.tools.jjq.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.jackson.JacksonConverter;
import io.hyperfoil.tools.jjq.jackson.JacksonJqEngine;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Parser, round-trip, and Jackson integration benchmarks for baseline
 * performance characterization.
 *
 * <h3>Parser benchmarks</h3>
 * Measure {@link JqValues#parse(String)} throughput on varied inputs
 * (string-heavy, number-heavy, deeply nested, flat arrays) at 4 sizes
 * (1KB, 10KB, 100KB, 1MB).
 *
 * <h3>Round-trip benchmarks</h3>
 * Parse JSON string -> apply jq filter -> serialize back via
 * {@code toJsonString()}. Captures the full pipeline cost.
 *
 * <h3>Jackson round-trip benchmarks</h3>
 * {@code JsonNode -> fromJsonNodeLazy() -> VM execute -> toJsonNode()}.
 * Measures the h5m-realistic integration path through {@link JacksonJqEngine}.
 *
 * <h3>Running with profilers</h3>
 * <pre>
 *   # Allocation rate
 *   java -jar jjq-benchmark-*.jar JjqParserBenchmark -prof gc
 *
 *   # Allocation flamegraph (async-profiler)
 *   java -jar jjq-benchmark-*.jar JjqParserBenchmark \
 *     -prof "async:libPath=.../libasyncProfiler.so;output=flamegraph;event=alloc"
 *
 *   # Hardware counters (Linux)
 *   java -jar jjq-benchmark-*.jar JjqParserBenchmark -prof perfnorm
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class JjqParserBenchmark {

    // ========================================================================
    //  Parser benchmark inputs (loaded from resource files)
    // ========================================================================

    // String-heavy inputs
    private String stringsJson1kb;
    private String stringsJson10kb;
    private String stringsJson100kb;
    private String stringsJson1mb;

    // Number-heavy inputs
    private String numbersJson1kb;
    private String numbersJson10kb;
    private String numbersJson100kb;
    private String numbersJson1mb;

    // Nested object inputs (h5m-style)
    private String nestedJson1kb;
    private String nestedJson10kb;
    private String nestedJson100kb;
    private String nestedJson1mb;

    // Flat mixed-type array inputs
    private String flatJson1kb;
    private String flatJson10kb;
    private String flatJson100kb;
    private String flatJson1mb;

    // ========================================================================
    //  Round-trip benchmark state
    // ========================================================================

    private JqProgram progIdentity;
    private JqProgram progFieldAccess;
    private JqProgram progCollectIterate;
    private JqProgram progObjectConstruct;

    private String roundTripInput; // ~10KB string-heavy input

    // ========================================================================
    //  Jackson round-trip benchmark state
    // ========================================================================

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private JacksonJqEngine engine;

    private JsonNode jacksonNestedInput;  // h5m-style nested input as JsonNode
    private JqProgram jacksonProgIdentity;
    private JqProgram jacksonProgFieldAccess;
    private JqProgram jacksonProgCollectIterate;

    @Setup
    public void setup() throws IOException {
        // Load parser benchmark inputs from resources
        stringsJson1kb = loadResource("benchmark-data/strings-1kb.json");
        stringsJson10kb = loadResource("benchmark-data/strings-10kb.json");
        stringsJson100kb = loadResource("benchmark-data/strings-100kb.json");
        stringsJson1mb = loadResource("benchmark-data/strings-1mb.json");

        numbersJson1kb = loadResource("benchmark-data/numbers-1kb.json");
        numbersJson10kb = loadResource("benchmark-data/numbers-10kb.json");
        numbersJson100kb = loadResource("benchmark-data/numbers-100kb.json");
        numbersJson1mb = loadResource("benchmark-data/numbers-1mb.json");

        nestedJson1kb = loadResource("benchmark-data/nested-1kb.json");
        nestedJson10kb = loadResource("benchmark-data/nested-10kb.json");
        nestedJson100kb = loadResource("benchmark-data/nested-100kb.json");
        nestedJson1mb = loadResource("benchmark-data/nested-1mb.json");

        flatJson1kb = loadResource("benchmark-data/flat-1kb.json");
        flatJson10kb = loadResource("benchmark-data/flat-10kb.json");
        flatJson100kb = loadResource("benchmark-data/flat-100kb.json");
        flatJson1mb = loadResource("benchmark-data/flat-1mb.json");

        // Round-trip programs
        progIdentity = JqProgram.compile(".");
        progFieldAccess = JqProgram.compile("[.[] | .name]");
        progCollectIterate = JqProgram.compile("[.[] | .cpu]");
        progObjectConstruct = JqProgram.compile("[.[] | {name, dept}]");

        roundTripInput = stringsJson10kb;

        // Jackson round-trip state
        engine = new JacksonJqEngine(MAPPER);
        jacksonNestedInput = MAPPER.readTree(nestedJson10kb);
        jacksonProgIdentity = engine.compile(".");
        jacksonProgFieldAccess = engine.compile(".[0].config.QUARKUS_VERSION");
        jacksonProgCollectIterate = engine.compile(
                "[.[0].results[].load.avThroughput]");
    }

    private static String loadResource(String name) throws IOException {
        try (InputStream is = JjqParserBenchmark.class.getClassLoader()
                .getResourceAsStream(name)) {
            if (is == null) throw new IOException("Resource not found: " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ========================================================================
    //  Parser benchmarks: string-heavy
    // ========================================================================

    @Benchmark
    public JqValue parse_strings_1kb() { return JqValues.parse(stringsJson1kb); }

    @Benchmark
    public JqValue parse_strings_10kb() { return JqValues.parse(stringsJson10kb); }

    @Benchmark
    public JqValue parse_strings_100kb() { return JqValues.parse(stringsJson100kb); }

    @Benchmark
    public JqValue parse_strings_1mb() { return JqValues.parse(stringsJson1mb); }

    // ========================================================================
    //  Parser benchmarks: number-heavy
    // ========================================================================

    @Benchmark
    public JqValue parse_numbers_1kb() { return JqValues.parse(numbersJson1kb); }

    @Benchmark
    public JqValue parse_numbers_10kb() { return JqValues.parse(numbersJson10kb); }

    @Benchmark
    public JqValue parse_numbers_100kb() { return JqValues.parse(numbersJson100kb); }

    @Benchmark
    public JqValue parse_numbers_1mb() { return JqValues.parse(numbersJson1mb); }

    // ========================================================================
    //  Parser benchmarks: nested objects (h5m-style)
    // ========================================================================

    @Benchmark
    public JqValue parse_nested_1kb() { return JqValues.parse(nestedJson1kb); }

    @Benchmark
    public JqValue parse_nested_10kb() { return JqValues.parse(nestedJson10kb); }

    @Benchmark
    public JqValue parse_nested_100kb() { return JqValues.parse(nestedJson100kb); }

    @Benchmark
    public JqValue parse_nested_1mb() { return JqValues.parse(nestedJson1mb); }

    // ========================================================================
    //  Parser benchmarks: flat mixed-type arrays
    // ========================================================================

    @Benchmark
    public JqValue parse_flat_1kb() { return JqValues.parse(flatJson1kb); }

    @Benchmark
    public JqValue parse_flat_10kb() { return JqValues.parse(flatJson10kb); }

    @Benchmark
    public JqValue parse_flat_100kb() { return JqValues.parse(flatJson100kb); }

    @Benchmark
    public JqValue parse_flat_1mb() { return JqValues.parse(flatJson1mb); }

    // ========================================================================
    //  Round-trip benchmarks: parse -> filter -> serialize
    // ========================================================================

    @Benchmark
    public String roundTrip_identity() {
        JqValue input = JqValues.parse(roundTripInput);
        JqValue result = progIdentity.apply(input);
        return result.toJsonString();
    }

    @Benchmark
    public String roundTrip_fieldAccess() {
        JqValue input = JqValues.parse(roundTripInput);
        JqValue result = progFieldAccess.apply(input);
        return result.toJsonString();
    }

    @Benchmark
    public String roundTrip_collectIterate() {
        JqValue input = JqValues.parse(numbersJson10kb);
        JqValue result = progCollectIterate.apply(input);
        return result.toJsonString();
    }

    @Benchmark
    public String roundTrip_objectConstruct() {
        JqValue input = JqValues.parse(roundTripInput);
        JqValue result = progObjectConstruct.apply(input);
        return result.toJsonString();
    }

    // ========================================================================
    //  Jackson round-trip benchmarks: JsonNode -> JqValue -> VM -> JsonNode
    // ========================================================================

    @Benchmark
    public List<JsonNode> jackson_roundTrip_identity() {
        return engine.apply(jacksonProgIdentity, jacksonNestedInput);
    }

    @Benchmark
    public JsonNode jackson_roundTrip_fieldAccess() {
        return engine.applyFirst(jacksonProgFieldAccess, jacksonNestedInput);
    }

    @Benchmark
    public List<JsonNode> jackson_roundTrip_collectIterate() {
        return engine.apply(jacksonProgCollectIterate, jacksonNestedInput);
    }
}

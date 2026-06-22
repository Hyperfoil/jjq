package io.hyperfoil.tools.jjq.benchmark;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyperfoil.tools.jjq.value.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Compares field access and navigation speed across three JSON object models:
 * Jackson {@link JsonNode}, jjq {@link JqValue}, and fastjson2 {@link JSONObject}.
 *
 * <p>Uses each library's <b>native API</b> for field access (not jq filters),
 * isolating the object model navigation cost from the jq VM cost.</p>
 *
 * <p>Uses the nested h5m-style benchmark data which has a realistic
 * structure with config, results, and runtime entries.
 * For a 14MB production upload, see {@link JsonProductionBenchmark}.</p>
 *
 * <h3>Running</h3>
 * <pre>
 *   ./scripts/run-benchmarks.sh JsonAccessComparisonBenchmark
 *
 *   # With allocation profiling
 *   java --enable-preview -jar jjq-benchmark-*.jar JsonAccessComparisonBenchmark \
 *     -prof gc -rf json -rff access-comparison.json
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgs = {"-Xmx2g", "-Xms2g", "--enable-preview"})
@State(Scope.Benchmark)
public class JsonAccessComparisonBenchmark {

    @Param({"1kb", "10kb"})
    String size;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Nested h5m-style data pre-parsed into each library
    private JsonNode jacksonNested;
    private JqValue jjqNested;
    private Object fastjsonNested;

    // Strings-style data (array of user objects) for iteration benchmarks
    private JsonNode jacksonStrings;
    private JqValue jjqStrings;
    private Object fastjsonStrings;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        String nestedJson = loadResource("benchmark-data/nested-" + size + ".json");
        jacksonNested = MAPPER.readTree(nestedJson);
        jjqNested = JqValues.parse(nestedJson);
        fastjsonNested = JSON.parse(nestedJson);

        String stringsJson = loadResource("benchmark-data/strings-" + size + ".json");
        jacksonStrings = MAPPER.readTree(stringsJson);
        jjqStrings = JqValues.parse(stringsJson);
        fastjsonStrings = JSON.parse(stringsJson);

    }

    // ========================================================================
    //  Single field access (top-level)
    // ========================================================================

    @Benchmark
    public JsonNode access_singleField_jackson() {
        // nested data is an array; access first element's config
        return jacksonNested.get(0).get("config");
    }

    @Benchmark
    public JqValue access_singleField_jjq() {
        JqArray arr = (JqArray) jjqNested;
        JqObject first = (JqObject) arr.get(0);
        return first.get("config");
    }

    @Benchmark
    public Object access_singleField_fastjson2() {
        JSONArray arr = (JSONArray) fastjsonNested;
        return arr.getJSONObject(0).get("config");
    }

    // ========================================================================
    //  Deep field access (4 levels: .[0].results.<runtime>.load.avThroughput)
    // ========================================================================

    @Benchmark
    public JsonNode access_deepField_jackson() {
        return jacksonNested.get(0).get("results").get("quarkus-jvm")
                .get("load").get("avThroughput");
    }

    @Benchmark
    public JqValue access_deepField_jjq() {
        JqObject first = (JqObject) ((JqArray) jjqNested).get(0);
        JqObject results = (JqObject) first.get("results");
        JqObject runtime = (JqObject) results.get("quarkus-jvm");
        JqObject load = (JqObject) runtime.get("load");
        return load.get("avThroughput");
    }

    @Benchmark
    public Object access_deepField_fastjson2() {
        JSONArray arr = (JSONArray) fastjsonNested;
        return arr.getJSONObject(0).getJSONObject("results")
                .getJSONObject("quarkus-jvm").getJSONObject("load")
                .get("avThroughput");
    }

    // ========================================================================
    //  Iterate array + access field on each element
    // ========================================================================

    @Benchmark
    public void access_iterate_jackson(Blackhole bh) {
        for (JsonNode elem : jacksonStrings) {
            bh.consume(elem.get("name"));
        }
    }

    @Benchmark
    public void access_iterate_jjq(Blackhole bh) {
        for (JqValue elem : ((JqArray) jjqStrings).arrayValue()) {
            bh.consume(((JqObject) elem).get("name"));
        }
    }

    @Benchmark
    public void access_iterate_fastjson2(Blackhole bh) {
        JSONArray arr = (JSONArray) fastjsonStrings;
        for (int i = 0; i < arr.size(); i++) {
            bh.consume(arr.getJSONObject(i).get("name"));
        }
    }

    private static String loadResource(String name) throws IOException {
        try (InputStream is = JsonAccessComparisonBenchmark.class.getClassLoader()
                .getResourceAsStream(name)) {
            if (is == null) throw new IOException("Resource not found: " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

package io.hyperfoil.tools.jjq.benchmark;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyperfoil.tools.jjq.fastjson2.FastjsonEngine;
import io.hyperfoil.tools.jjq.jackson.JacksonConverter;
import io.hyperfoil.tools.jjq.value.*;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks parsing, serialization, field access, and conversion on a
 * real-world 14MB production upload file (anonymized rhivos-perf-comprehensive).
 *
 * <p>Separated from the parameterized comparison benchmarks to avoid
 * running the expensive 14MB operations for every {@code @Param} combination.
 * This class has no {@code @Param} annotations.</p>
 *
 * <p>The production file has 351,749 JSON nodes (93% strings, 6% numbers),
 * max depth 10, with heavy {@code pcp_time_series} arrays typical of
 * h5m/Horreum benchmark uploads.</p>
 *
 * <h3>Running</h3>
 * <pre>
 *   ./scripts/run-benchmarks.sh JsonProductionBenchmark
 *
 *   # With allocation profiling (essential for memory comparison)
 *   java --enable-preview -jar jjq-benchmark-*.jar JsonProductionBenchmark \
 *     -prof gc -rf json -rff production-comparison.json
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgs = {"-Xmx4g", "-Xms4g", "--enable-preview"})
@State(Scope.Benchmark)
public class JsonProductionBenchmark {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RESOURCE = "benchmark-data/production-upload-14mb.json";

    // Raw JSON string for parse benchmarks
    private String productionJson;

    // Pre-parsed into each library's native type for serialize/access benchmarks
    private JsonNode jacksonValue;
    private JqValue jjqValue;
    private Object fastjsonValue;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        productionJson = loadResource(RESOURCE);
        jacksonValue = MAPPER.readTree(productionJson);
        jjqValue = JqValues.parse(productionJson);
        fastjsonValue = JSON.parse(productionJson);
    }

    // ========================================================================
    //  Parse: JSON string -> native object model
    // ========================================================================

    @Benchmark
    public JsonNode parse_jackson() throws IOException {
        return MAPPER.readTree(productionJson);
    }

    @Benchmark
    public JqValue parse_jjq() {
        return JqValues.parse(productionJson);
    }

    @Benchmark
    public Object parse_fastjson2() {
        return JSON.parse(productionJson);
    }

    // ========================================================================
    //  Serialize: native object model -> JSON string
    // ========================================================================

    @Benchmark
    public String serialize_jackson() throws IOException {
        return MAPPER.writeValueAsString(jacksonValue);
    }

    @Benchmark
    public String serialize_jjq() {
        return jjqValue.toJsonString();
    }

    @Benchmark
    public String serialize_fastjson2() {
        return JSON.toJSONString(fastjsonValue);
    }

    // ========================================================================
    //  Field access: navigate to a deeply nested value
    //  Path: .autobench_workload.data[0].results
    // ========================================================================

    @Benchmark
    public JsonNode access_deepField_jackson() {
        return jacksonValue.get("autobench_workload").get("data")
                .get(0).get("results");
    }

    @Benchmark
    public JqValue access_deepField_jjq() {
        JqObject root = (JqObject) jjqValue;
        JqObject wl = (JqObject) root.get("autobench_workload");
        JqArray data = (JqArray) wl.get("data");
        JqObject first = (JqObject) data.get(0);
        return first.get("results");
    }

    @Benchmark
    public Object access_deepField_fastjson2() {
        JSONObject root = (JSONObject) fastjsonValue;
        return root.getJSONObject("autobench_workload").getJSONArray("data")
                .getJSONObject(0).get("results");
    }

    // ========================================================================
    //  Conversion: Jackson <-> JqValue (what h5m currently pays)
    // ========================================================================

    @Benchmark
    public JqValue convert_jackson_to_jjq_lazy() {
        return JacksonConverter.fromJsonNodeLazy(jacksonValue);
    }

    @Benchmark
    public JqValue convert_jackson_to_jjq_eager() {
        return JacksonConverter.fromJsonNode(jacksonValue);
    }

    @Benchmark
    public JsonNode convert_jjq_to_jackson() {
        return JacksonConverter.toJsonNode(jjqValue, MAPPER);
    }

    // ========================================================================
    //  Conversion: Fastjson2 <-> JqValue
    // ========================================================================

    @Benchmark
    public JqValue convert_fastjson_to_jjq_lazy() {
        return FastjsonEngine.fromFastjsonLazy(fastjsonValue);
    }

    @Benchmark
    public Object convert_jjq_to_fastjson() {
        return FastjsonEngine.toFastjson(jjqValue);
    }

    private static String loadResource(String name) throws IOException {
        try (InputStream is = JsonProductionBenchmark.class.getClassLoader()
                .getResourceAsStream(name)) {
            if (is == null) throw new IOException("Resource not found: " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

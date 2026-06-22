package io.hyperfoil.tools.jjq.benchmark;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Compares JSON serialization throughput across three libraries:
 * Jackson ({@link ObjectMapper#writeValueAsString}),
 * jjq ({@link JqValue#toJsonString}),
 * and fastjson2 ({@link JSON#toJSONString}).
 *
 * <p>Pre-parses inputs into each library's native types during setup,
 * so the benchmark measures pure serialization cost only.
 * For a 14MB production upload, see {@link JsonProductionBenchmark}.</p>
 *
 * <h3>Running</h3>
 * <pre>
 *   ./scripts/run-benchmarks.sh JsonSerializeComparisonBenchmark
 *
 *   # With allocation profiling
 *   java --enable-preview -jar jjq-benchmark-*.jar JsonSerializeComparisonBenchmark \
 *     -prof gc -rf json -rff serialize-comparison.json
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgs = {"-Xmx2g", "-Xms2g", "--enable-preview"})
@State(Scope.Benchmark)
public class JsonSerializeComparisonBenchmark {

    @Param({"flat", "strings", "numbers", "nested"})
    String structure;

    @Param({"1kb", "10kb", "100kb", "1mb"})
    String size;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Pre-parsed into each library's native type
    private JsonNode jacksonValue;
    private JqValue jjqValue;
    private Object fastjsonValue;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        String paramJson = loadResource("benchmark-data/" + structure + "-" + size + ".json");
        jacksonValue = MAPPER.readTree(paramJson);
        jjqValue = JqValues.parse(paramJson);
        fastjsonValue = JSON.parse(paramJson);
    }

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

    private static String loadResource(String name) throws IOException {
        try (InputStream is = JsonSerializeComparisonBenchmark.class.getClassLoader()
                .getResourceAsStream(name)) {
            if (is == null) throw new IOException("Resource not found: " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

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
 * Compares JSON parsing throughput and allocation across three libraries:
 * Jackson ({@link ObjectMapper#readTree}), jjq ({@link JqValues#parse}),
 * and fastjson2 ({@link JSON#parse}).
 *
 * <p>Uses {@code @Param} to test 4 JSON structures at 4 sizes (16 combinations).
 * For a 14MB production upload, see {@link JsonProductionBenchmark}.</p>
 *
 * <h3>Running</h3>
 * <pre>
 *   # Throughput only
 *   ./scripts/run-benchmarks.sh JsonParseComparisonBenchmark
 *
 *   # With allocation profiling (recommended)
 *   java --enable-preview -jar jjq-benchmark-*.jar JsonParseComparisonBenchmark \
 *     -prof gc -rf json -rff parse-comparison.json
 *
 *   # With allocation flamegraph
 *   java --enable-preview -jar jjq-benchmark-*.jar JsonParseComparisonBenchmark \
 *     -prof "async:event=alloc;output=flamegraph" -prof gc \
 *     -rf json -rff parse-comparison.json
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgs = {"-Xmx2g", "-Xms2g", "--enable-preview"})
@State(Scope.Benchmark)
public class JsonParseComparisonBenchmark {

    @Param({"flat", "strings", "numbers", "nested"})
    String structure;

    @Param({"1kb", "10kb", "100kb", "1mb"})
    String size;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String paramJson;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        paramJson = loadResource("benchmark-data/" + structure + "-" + size + ".json");
    }

    @Benchmark
    public JsonNode parse_jackson() throws IOException {
        return MAPPER.readTree(paramJson);
    }

    @Benchmark
    public JqValue parse_jjq() {
        return JqValues.parse(paramJson);
    }

    @Benchmark
    public Object parse_fastjson2() {
        return JSON.parse(paramJson);
    }

    private static String loadResource(String name) throws IOException {
        try (InputStream is = JsonParseComparisonBenchmark.class.getClassLoader()
                .getResourceAsStream(name)) {
            if (is == null) throw new IOException("Resource not found: " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

package io.hyperfoil.tools.jjq.benchmark;

import com.alibaba.fastjson2.JSON;
import io.hyperfoil.tools.jjq.fastjson2.FastjsonEngine;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Measures the overhead of converting between JSON library object models
 * and jjq's {@link JqValue} types. This quantifies the cost h5m currently
 * pays for the {@code JsonNode <-> JqValue} round-trip in
 * {@code NodeService.calculateJqValues()}.
 *
 * <p>Eliminating these conversions is the primary motivation for
 * <a href="https://github.com/Hyperfoil/h5m/issues/150">h5m#150</a>.</p>
 *
 * <h3>Running</h3>
 * <pre>
 *   ./scripts/run-benchmarks.sh JsonConversionBenchmark
 *
 *   # With allocation profiling (critical for this benchmark)
 *   java --enable-preview -jar jjq-benchmark-*.jar JsonConversionBenchmark \
 *     -prof gc -rf json -rff conversion-comparison.json
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgs = {"-Xmx2g", "-Xms2g", "--enable-preview"})
@State(Scope.Benchmark)
public class JsonConversionBenchmark {

    @Param({"1kb", "10kb", "100kb"})
    String size;

    // Pre-parsed into each library's native type
    private Object fastjsonValue;
    private JqValue jjqValue;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        String json = loadResource("benchmark-data/nested-" + size + ".json");
        fastjsonValue = JSON.parse(json);
        jjqValue = JqValues.parse(json);
    }

    // ========================================================================
    //  Fastjson2 -> JqValue
    // ========================================================================

    @Benchmark
    public JqValue convert_fastjson_to_jjq_lazy() {
        return FastjsonEngine.fromFastjsonLazy(fastjsonValue);
    }

    @Benchmark
    public JqValue convert_fastjson_to_jjq_eager() {
        return FastjsonEngine.fromFastjson(fastjsonValue);
    }

    // ========================================================================
    //  JqValue -> Fastjson2
    // ========================================================================

    @Benchmark
    public Object convert_jjq_to_fastjson() {
        return FastjsonEngine.toFastjson(jjqValue);
    }

    private static String loadResource(String name) throws IOException {
        try (InputStream is = JsonConversionBenchmark.class.getClassLoader()
                .getResourceAsStream(name)) {
            if (is == null) throw new IOException("Resource not found: " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

package io.hyperfoil.tools.jjq.benchmark;

import io.hyperfoil.tools.jjq.value.*;
import org.openjdk.jmh.annotations.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks {@link JqObject} construction cost across object widths.
 *
 * <p>Regression guard for the O(n^2) duplicate scan once found in
 * {@code Builder.put} (50K puts took 1.8s): widths span the indexed
 * range boundary (32 keys) up to 50K-key machine-generated shapes
 * (i18n bundles, OpenAPI path maps, entity tables, wide-column rows).
 * Scaling must stay near-linear; any return to super-linear growth
 * across these widths is a builder regression, not parser code.
 *
 * <h3>Running</h3>
 * <pre>
 *   mvn package -Pbenchmark -pl jjq-benchmark -DskipTests
 *   java --enable-preview -jar jjq-benchmark/target/jjq-benchmark-*.jar \
 *     JqObjectBuildBenchmark -prof gc
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgs = {"-Xmx2g", "-Xms2g"})
@State(Scope.Benchmark)
public class JqObjectBuildBenchmark {

    @Param({"100", "1000", "10000", "50000"})
    int width;

    private String[] keys;
    private JqValue[] values;
    private Map<String, JqValue> wideMap;

    @Setup(Level.Trial)
    public void setup() {
        // Interned keys, as the parser produces; prebuilt to isolate build cost
        keys = new String[width];
        values = new JqValue[width];
        wideMap = new LinkedHashMap<>(width * 2);
        for (int i = 0; i < width; i++) {
            keys[i] = JqValues.internFieldName("key-" + i);
            values[i] = JqNumber.of(i);
            wideMap.put(keys[i], values[i]);
        }
    }

    @Benchmark
    public JqObject build_putLoop() {
        var builder = JqObject.builder(width);
        for (int i = 0; i < width; i++) {
            builder.put(keys[i], values[i]);
        }
        return builder.build();
    }

    @Benchmark
    public JqValue build_fromJavaObject() {
        return JqValues.fromJavaObject(wideMap);
    }
}

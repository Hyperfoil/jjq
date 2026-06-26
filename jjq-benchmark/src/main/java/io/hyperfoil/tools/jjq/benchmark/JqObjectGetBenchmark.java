package io.hyperfoil.tools.jjq.benchmark;

import io.hyperfoil.tools.jjq.value.*;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * Benchmarks {@link JqObject#get(String)} latency across different object sizes
 * to find the optimal threshold for switching from linear scan to hash lookup.
 *
 * <h3>Running</h3>
 * <pre>
 *   mvn package -Pbenchmark -pl jjq-benchmark -DskipTests
 *   java --enable-preview -jar jjq-benchmark/target/jjq-benchmark-*.jar \
 *     JqObjectGetBenchmark -prof gc
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgs = {"-Xmx2g", "-Xms2g"})
@State(Scope.Benchmark)
public class JqObjectGetBenchmark {

    @Param({"1", "2", "4", "8", "12", "16", "20", "24", "32", "64", "128"})
    int objectSize;

    @Param({"first", "middle", "last", "missing"})
    String keyPosition;

    private JqObject object;
    private String targetKey;

    @Setup(Level.Trial)
    public void setup() {
        // Build object with interned field names (same as parser produces)
        var builder = JqObject.builder(objectSize);
        for (int i = 0; i < objectSize; i++) {
            String key = JqValues.internFieldName("field_" + i);
            builder.put(key, JqNumber.of(i));
        }
        object = builder.build();

        // Select target key based on position parameter
        targetKey = switch (keyPosition) {
            case "first" -> JqValues.internFieldName("field_0");
            case "middle" -> JqValues.internFieldName("field_" + (objectSize / 2));
            case "last" -> JqValues.internFieldName("field_" + (objectSize - 1));
            case "missing" -> JqValues.internFieldName("nonexistent_field");
            default -> throw new IllegalArgumentException("Unknown position: " + keyPosition);
        };
    }

    @Benchmark
    public JqValue get() {
        return object.get(targetKey);
    }

    @Benchmark
    public boolean has() {
        return object.has(targetKey);
    }
}

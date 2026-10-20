package io.hyperfoil.tools.jjq.benchmark;

import io.hyperfoil.tools.jjq.schema.JqSchema;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.openjdk.jmh.annotations.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * One compiled schema shared across validation threads (issue #167):
 * proves the immutable graph needs no per-thread state. Each thread
 * validates its own preparsed document; only the schema is shared.
 *
 * <h3>Running</h3>
 * <pre>
 *   mvn package -pl jjq-core,jjq-schema,jjq-benchmark -DskipTests -Pbenchmark
 *   java -jar jjq-benchmark/target/jjq-benchmark-*.jar JqSchemaSharedBenchmark -prof gc -rf json -rff schema-shared.json
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgs = {"-Xmx2g", "-Xms2g"})
@State(Scope.Benchmark)
public class JqSchemaSharedBenchmark {

    private JqSchema compiled;

    @Setup
    public void setup() {
        StringBuilder schema = new StringBuilder("{\"type\":\"object\",\"required\":[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) schema.append(',');
            schema.append("\"f").append(i).append('"');
        }
        schema.append("],\"properties\":{");
        for (int i = 0; i < 20; i++) {
            if (i > 0) schema.append(',');
            schema.append("\"f").append(i).append("\":{\"type\":\"string\"}");
        }
        schema.append("},\"additionalProperties\":false}");
        compiled = JqSchema.compile(
                JqValues.parse(schema.toString().getBytes(StandardCharsets.UTF_8)));
    }

    @State(Scope.Thread)
    public static class ThreadDoc {
        JqValue document;

        @Setup(Level.Trial)
        public void setup() {
            StringBuilder doc = new StringBuilder("{");
            for (int i = 0; i < 20; i++) {
                if (i > 0) doc.append(',');
                doc.append("\"f").append(i).append("\":\"v\"");
            }
            doc.append('}');
            document = JqValues.parse(doc.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    @Benchmark
    @Threads(4)
    public boolean validateShared(ThreadDoc thread) {
        return compiled.isValid(thread.document);
    }
}

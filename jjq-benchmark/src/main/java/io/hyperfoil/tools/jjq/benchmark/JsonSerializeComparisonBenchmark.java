package io.hyperfoil.tools.jjq.benchmark;

import com.alibaba.fastjson2.JSON;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
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

    // Sink destinations: measure serialization without the final
    // String/byte[] materialization. Never compare these rows against
    // final-String rows; they measure different work.
    //
    // Close-ignoring sinks (not nullWriter/nullOutputStream): Jackson
    // closes its generator after every write, which would close a shared
    // null sink on the first op and fail every later one.
    private Writer sinkWriter;
    private OutputStream sinkStream;

    private static final class SinkWriter extends Writer {
        @Override
        public void write(char[] cbuf, int off, int len) { }
        @Override
        public void flush() { }
        @Override
        public void close() { }
    }

    private static final class SinkStream extends OutputStream {
        @Override
        public void write(int b) { }
        @Override
        public void write(byte[] b, int off, int len) { }
        @Override
        public void close() { }
    }

    @Setup(Level.Trial)
    public void setup() throws IOException {
        String paramJson = loadResource("benchmark-data/" + structure + "-" + size + ".json");
        jacksonValue = MAPPER.readTree(paramJson);
        jjqValue = JqValues.parse(paramJson);
        fastjsonValue = JSON.parse(paramJson);
        sinkWriter = new SinkWriter();
        sinkStream = new SinkStream();
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

    // ========================================================================
    //  Serialize to a sink (#207 phase 0): separate rows per destination.
    //  Writer/OutputStream rows skip the final String/byte[] copy, so they
    //  are comparable across libraries but never against the rows above.
    // ========================================================================

    @Benchmark
    public void serialize_jjq_toWriter() throws IOException {
        JqValues.serializeTo(jjqValue, sinkWriter);
    }

    @Benchmark
    public void serialize_jackson_toWriter() throws IOException {
        MAPPER.writeValue(sinkWriter, jacksonValue);
    }

    @Benchmark
    public void serialize_jjq_toStream() throws IOException {
        JqValues.serializeTo(jjqValue, sinkStream);
    }

    @Benchmark
    public void serialize_jackson_toStream() throws IOException {
        MAPPER.writeValue(sinkStream, jacksonValue);
    }

    private static String loadResource(String name) throws IOException {
        try (InputStream is = JsonSerializeComparisonBenchmark.class.getClassLoader()
                .getResourceAsStream(name)) {
            if (is == null) throw new IOException("Resource not found: " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

package io.hyperfoil.tools.jjq.benchmark;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Small number-heavy documents (issue #149): isolates the BigDecimal
 * fallback for 19-20 digit integers and the small-int residual from the
 * surrounding document cost.
 *
 * <p>Each document is a single-key object so per-op time is dominated by
 * the one number's code path. Lenient variants separate strict-mode
 * overhead; {@code toJava} variants separate plain-Java conversion from
 * parsing. Jackson 3 {@code readTree} rows are reference context only
 * (tree-building, not the issue's Jackson 2 streaming baseline).</p>
 *
 * <h3>Running</h3>
 * <pre>
 *   mvn package -pl jjq-core,jjq-jackson,jjq-fastjson2,jjq-mapper,jjq-benchmark -DskipTests -Pbenchmark
 *   java -jar jjq-benchmark/target/jjq-benchmark-*.jar SmallNumberParseBenchmark -prof gc -rf json -rff small-numbers.json
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgs = {"-Xmx2g", "-Xms2g"})
@State(Scope.Benchmark)
public class SmallNumberParseBenchmark {

    private static final ObjectMapper JACKSON = new ObjectMapper();

    private byte[] smallInt;
    private byte[] u64;
    private byte[] overU64;
    private byte[] exp;
    private byte[] float100;
    private byte[] float15;
    private byte[] edge;

    private JqValue preSmallInt;
    private JqValue preU64;
    private JqValue preExp;
    private JqValue preEdge;

    @Setup
    public void setup() {
        smallInt = doc("{\"a\":1}");
        u64 = doc("{\"a\":18446744073709551615}");
        overU64 = doc("{\"a\":18446744073709551616}");
        exp = doc("{\"a\":1e20}");
        float100 = doc("{\"a\":100.0}");
        float15 = doc("{\"a\":1.5}");
        edge = doc("{\"i\":-3,\"u\":18446744073709551615,\"big\":18446744073709551616,"
                + "\"f\":1.5,\"e\":1e20,\"z\":-0,\"h\":100.0}");

        preSmallInt = JqValues.parse(smallInt);
        preU64 = JqValues.parse(u64);
        preExp = JqValues.parse(exp);
        preEdge = JqValues.parse(edge);
    }

    private static byte[] doc(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    // ---- strict parse (byte[]) ----

    @Benchmark
    public JqValue strictSmallInt() {
        return JqValues.parseStrict(smallInt);
    }

    @Benchmark
    public JqValue strictU64() {
        return JqValues.parseStrict(u64);
    }

    @Benchmark
    public JqValue strictOverU64() {
        return JqValues.parseStrict(overU64);
    }

    @Benchmark
    public JqValue strictExp() {
        return JqValues.parseStrict(exp);
    }

    @Benchmark
    public JqValue strictFloat100() {
        return JqValues.parseStrict(float100);
    }

    @Benchmark
    public JqValue strictFloat15() {
        return JqValues.parseStrict(float15);
    }

    @Benchmark
    public JqValue strictEdge() {
        return JqValues.parseStrict(edge);
    }

    // ---- lenient parse (strict-overhead separation) ----

    @Benchmark
    public JqValue lenientSmallInt() {
        return JqValues.parse(smallInt);
    }

    @Benchmark
    public JqValue lenientU64() {
        return JqValues.parse(u64);
    }

    // ---- plain-Java conversion (parse vs conversion separation) ----

    @Benchmark
    public Object javaSmallInt() {
        return preSmallInt.toJavaObject();
    }

    @Benchmark
    public Object javaU64() {
        return preU64.toJavaObject();
    }

    @Benchmark
    public Object javaExp() {
        return preExp.toJavaObject();
    }

    @Benchmark
    public Object javaEdge() {
        return preEdge.toJavaObject();
    }

    // ---- Jackson 3 tree reference (context only, not the streaming baseline) ----

    @Benchmark
    public JsonNode jacksonSmallInt() throws IOException {
        return JACKSON.readTree(smallInt);
    }

    @Benchmark
    public JsonNode jacksonEdge() throws IOException {
        return JACKSON.readTree(edge);
    }
}

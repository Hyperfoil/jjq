package io.hyperfoil.tools.jjq.benchmark;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.json.JsonFactory;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.openjdk.jmh.annotations.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Measures escape handling performance with varied inputs to reveal
 * branch misprediction costs hidden by single-input benchmarks.
 *
 * <p>Key technique from Franz Nigro: pre-generate N different inputs with
 * configurable escape character density, cycle through them per invocation.
 * The CPU branch predictor can't learn a single pattern across N different
 * strings, revealing realistic misprediction costs.</p>
 *
 * <p>Finding: h5m's 14MB production file has <b>0.0000% escape density</b>
 * across 675,843 strings / 11.6M characters. The branch predictor is 100%
 * accurate for this workload. This benchmark explores what happens at
 * non-zero densities for other jjq users.</p>
 *
 * <h3>Running</h3>
 * <pre>
 * mvn package -pl jjq-core,jjq-jackson,jjq-fastjson2,jjq-mapper,jjq-mapper-processor,jjq-benchmark -DskipTests -Pbenchmark
 * java -jar jjq-benchmark/target/jjq-benchmark-*.jar JqEscapeBenchmark
 *
 * # With hardware counters (look for branch-misses)
 * java -jar jjq-benchmark/target/jjq-benchmark-*.jar JqEscapeBenchmark -prof perfnorm
 * </pre>
 *
 * @see <a href="https://github.com/franz1981/java-puzzles/commit/8fb833c9ce7ce51de1a651b09c8253448425bc60">
 *      Franz Nigro's SmallRye config benchmark</a>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsPrepend = {"-XX:-UseOnStackReplacement"})
@State(Scope.Benchmark)
public class JqEscapeBenchmark {

    private static final int SEED = 42;

    /** Escape characters that can appear in JSON strings. */
    private static final char[] ESCAPE_CHARS = {'"', '\\', '\n', '\r', '\t', '\b', '\f', 0x01, 0x02, 0x1F};

    /** Printable ASCII characters that don't need escaping. */
    private static final char[] CLEAN_CHARS;
    static {
        // 0x20 (space) through 0x7E (~), excluding " and \
        CLEAN_CHARS = new char[93]; // 0x7E - 0x20 + 1 - 2 (for " and \)
        int idx = 0;
        for (char c = 0x20; c <= 0x7E; c++) {
            if (c != '"' && c != '\\') {
                CLEAN_CHARS[idx++] = c;
            }
        }
    }

    // ========================================================================
    //  Parameters
    // ========================================================================

    /** Probability (0-100) that each character is an escape character. */
    @Param({"0", "1", "3", "5", "10"})
    int escapePercentage;

    /** Length of each generated string. */
    @Param({"100"})
    int stringLength;

    /** Number of distinct inputs to cycle through. */
    @Param({"10000"})
    int samples;

    // ========================================================================
    //  State
    // ========================================================================

    private String[] stringDataSet;
    private byte[][] bytesDataSet;
    private JqValue[] jqValueDataSet;
    private int nextIdx;

    // Reusable buffers
    private StringBuilder sb;

    // Jackson comparison
    private JsonFactory jsonFactory;
    private ByteArrayOutputStream jacksonOut;

    @Setup
    public void setup() {
        Random rnd = new Random(SEED);

        stringDataSet = new String[samples];
        bytesDataSet = new byte[samples][];
        jqValueDataSet = new JqValue[samples];

        for (int i = 0; i < samples; i++) {
            stringDataSet[i] = generateString(rnd, stringLength, escapePercentage);
            // Build JSON string: "value" — for the parse benchmark
            String jsonStr = "\"" + JqString.escapeForBenchmark(stringDataSet[i]) + "\"";
            bytesDataSet[i] = jsonStr.getBytes(StandardCharsets.UTF_8);
            jqValueDataSet[i] = JqString.of(stringDataSet[i]);
        }

        nextIdx = 0;
        sb = new StringBuilder(stringLength * 2);
        jsonFactory = new JsonFactory();
        jacksonOut = new ByteArrayOutputStream(stringLength * 2);

        // Warmup: ensure all code paths are compiled
        for (int i = 0; i < Math.min(100, samples); i++) {
            sb.setLength(0);
            JqString.escapeJson(stringDataSet[i], sb);
        }
    }

    // ========================================================================
    //  Input cycling — Franz's technique
    // ========================================================================

    private String nextString() {
        int idx = nextIdx;
        nextIdx = (idx + 1) % samples;
        return stringDataSet[idx];
    }

    private byte[] nextBytes() {
        int idx = nextIdx;
        nextIdx = (idx + 1) % samples;
        return bytesDataSet[idx];
    }

    private JqValue nextJqValue() {
        int idx = nextIdx;
        nextIdx = (idx + 1) % samples;
        return jqValueDataSet[idx];
    }

    // ========================================================================
    //  Serialization benchmarks — escape detection + output
    // ========================================================================

    /** jjq char-based: escapeJson(String, StringBuilder) */
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @Benchmark
    public String ser_jjq_escapeJson() {
        String input = nextString();
        sb.setLength(0);
        JqString.escapeJson(input, sb);
        return sb.toString();
    }

    /** jjq byte-based: serializeToBytes on JqString */
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @Benchmark
    public byte[] ser_jjq_serializeToBytes() {
        JqValue input = nextJqValue();
        return JqValues.serializeToBytes(input);
    }

    /** jjq scan-only: needsEscaping (no output, just detection) */
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @Benchmark
    public boolean ser_jjq_needsEscaping() {
        return JqString.needsEscaping(nextString());
    }

    /** Jackson: writeString via JsonGenerator */
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @Benchmark
    public byte[] ser_jackson_writeString() throws IOException {
        jacksonOut.reset();
        try (JsonGenerator gen = jsonFactory.createGenerator(ObjectWriteContext.empty(), jacksonOut)) {
            gen.writeString(nextString());
        }
        return jacksonOut.toByteArray();
    }

    // ========================================================================
    //  Parse benchmarks — finding escapes during parsing
    // ========================================================================

    /** jjq byte parser: parse a JSON string value from bytes */
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @Benchmark
    public JqValue parse_jjq_bytes() {
        return JqValues.parse(nextBytes());
    }

    // ========================================================================
    //  Baseline — measure cycling overhead
    // ========================================================================

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @Benchmark
    public String baseline_nextInput() {
        return nextString();
    }

    // ========================================================================
    //  Input generation
    // ========================================================================

    /**
     * Generate a string with the given escape character probability.
     * Escape characters are drawn randomly from ESCAPE_CHARS.
     * Non-escape characters are drawn from printable ASCII (0x20-0x7E, excluding " and \).
     */
    private static String generateString(Random rnd, int length, int escapePercentage) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            if (rnd.nextInt(100) < escapePercentage) {
                chars[i] = ESCAPE_CHARS[rnd.nextInt(ESCAPE_CHARS.length)];
            } else {
                chars[i] = CLEAN_CHARS[rnd.nextInt(CLEAN_CHARS.length)];
            }
        }
        return new String(chars);
    }
}

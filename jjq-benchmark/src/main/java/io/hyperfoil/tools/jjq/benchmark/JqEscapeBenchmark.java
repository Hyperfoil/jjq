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
 * <p>Issue #156 matrix: {@code script} varies the String coder case
 * (ASCII Latin-1 vs true UTF-16 BMP vs supplementary pairs), {@code
 * escapePosition} plants the first escape early or late, and {@code
 * profileMode} primes the process-wide {@code String.charAt} profile
 * before warmup. The default matrix stays small; select wider sweeps
 * explicitly, for example:</p>
 * <pre>
 * -p script=ascii,latin1,bmp,supplementary,mixed -p profileMode=clean,primed,mixed
 * -p stringLength=16,100,512 -p escapePosition=random,early,late
 * </pre>
 * <p>Evidence runs use at least 3 forks, {@code -prof gc}, and JSON output;
 * compare supported JDK 21/25 with JDK 27 where available.</p>
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
@Fork(value = 3, jvmArgsPrepend = {"-XX:-UseOnStackReplacement"})
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

    /** Non-ASCII Latin-1 clean characters (single UTF-16 units, non-ASCII coder). */
    private static final char[] LATIN1_CLEAN =
            {'\u00e9', '\u00fc', '\u00f1', '\u00a9', '\u00ae', '\u00e0', '\u00e8', '\u00ea',
             '\u00f4', '\u00fb', '\u00e7', '\u00df', '\u00e6', '\u00f8', '\u00e5'};

    /** BMP clean characters (CJK, true UTF-16 coder). */
    private static final char[] BMP_CLEAN =
            {'\u4e16', '\u754c', '\u4e2d', '\u6587', '\u65e5', '\u8bed', '\uac00', '\ub098'};

    /** Supplementary clean code points (surrogate pairs). */
    private static final int[] SUPPLEMENTARY_CLEAN =
            {0x1F600, 0x1F601, 0x1F602, 0x1F923, 0x1F680, 0x1F4A1};

    /** UTF-16 sample for profile priming and mixed-profile warmup. */
    private static final String UTF16_PRIME = "\u4e16\u754c\u754c\u4e16\u00e9\u0141";

    // Sink for priming work so setup-time loops are not eliminated.
    private int primeSink;

    // ========================================================================
    //  Parameters
    // ========================================================================

    /** Probability (0-100) that each character is an escape character. */
    @Param({"0", "1", "3", "5", "10"})
    int escapePercentage;

    /** Length of each generated string, in chars. */
    @Param({"16", "100", "512"})
    int stringLength;

    /** Number of distinct inputs to cycle through. */
    @Param({"10000"})
    int samples;

    /**
     * Clean-character pool: {@code ascii} (Latin-1 coder), {@code latin1}
     * (non-ASCII Latin-1, still single UTF-16 units), {@code bmp} (CJK),
     * {@code supplementary} (surrogate pairs), {@code mixed} (all of them).
     * Latin-1 non-ASCII and true UTF-16 are different coder cases.
     */
    @Param({"ascii", "mixed"})
    String script;

    /**
     * Where the first escape lands when {@code escapePercentage > 0}:
     * {@code random} (probabilistic draw only), {@code early} (forced at
     * index 0), {@code late} (forced at the last char boundary).
     */
    @Param({"random"})
    String escapePosition;

    /**
     * JIT profile priming in setup, before warmup compiles the loops:
     * {@code clean} (nothing), {@code primed} (UTF-16 {@code charAt} calls,
     * poisoning the process-wide profile), {@code mixed} (primed plus UTF-16
     * traffic through the measured methods). Verify compiled behavior with
     * assembly inspection rather than assuming the prime took effect.
     */
    @Param({"clean"})
    String profileMode;

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
        primeCharAtProfile();
        Random rnd = new Random(SEED);

        stringDataSet = new String[samples];
        bytesDataSet = new byte[samples][];
        jqValueDataSet = new JqValue[samples];

        for (int i = 0; i < samples; i++) {
            stringDataSet[i] = generateString(rnd, stringLength, escapePercentage, script, escapePosition);
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

        if ("mixed".equals(profileMode)) {
            // Realistic mixed steady state: UTF-16 traffic through the
            // measured methods during setup, before warmup compiles them.
            for (int i = 0; i < 200; i++) {
                sb.setLength(0);
                JqString.escapeJson(UTF16_PRIME, sb);
                JqValues.serializeToBytes(JqString.of(UTF16_PRIME));
            }
        }
    }

    /**
     * Poison the process-wide {@code String.charAt} profile before warmup
     * compiles the measured loops (unless {@code profileMode} is clean).
     * A single interpreter-era UTF-16 call is the documented pollution
     * mechanism; the sink field keeps the loop from being eliminated.
     */
    private void primeCharAtProfile() {
        if ("clean".equals(profileMode)) return;
        int sink = 0;
        for (int i = 0; i < 1000; i++) {
            sink += UTF16_PRIME.charAt(i & (UTF16_PRIME.length() - 1));
        }
        primeSink = sink;
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

    /** jjq byte parser plus forced materialization (unescaping, if any). */
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @Benchmark
    public String parse_jjq_bytes_materialized() {
        return JqValues.parse(nextBytes()).stringValue();
    }

    /** Untouched parse-to-bytes round trip (deferred source-copy path). */
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @Benchmark
    public byte[] roundtrip_jjq_parseSerialize() {
        return JqValues.serializeToBytes(JqValues.parse(nextBytes()));
    }

    /**
     * First-use serialization: construction is part of the measured op,
     * so no memoized escape verdict carries over between invocations.
     */
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @Benchmark
    public byte[] ser_jjq_serializeToBytes_firstUse() {
        return JqValues.serializeToBytes(JqString.of(nextString()));
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
     * Non-escape characters come from the {@code script} pool; supplementary
     * code points append as surrogate pairs without splitting.
     * Package-visible for scratch verification of dataset round-trips.
     */
    static String generateString(Random rnd, int length, int escapePercentage,
            String script, String escapePosition) {
        StringBuilder sb = new StringBuilder(length + 8);
        while (sb.length() < length) {
            if (rnd.nextInt(100) < escapePercentage) {
                sb.append(ESCAPE_CHARS[rnd.nextInt(ESCAPE_CHARS.length)]);
            } else if (sb.length() + 1 >= length
                    && ("supplementary".equals(script) || "mixed".equals(script))) {
                // No surrogate pair at the boundary: exact nominal length.
                sb.append(CLEAN_CHARS[rnd.nextInt(CLEAN_CHARS.length)]);
            } else {
                appendCleanChar(rnd, sb, script);
            }
        }
        if (escapePercentage > 0 && !"random".equals(escapePosition) && sb.length() > 0) {
            // Plant one escape on a whole code point, never inside a pair.
            char esc = ESCAPE_CHARS[rnd.nextInt(ESCAPE_CHARS.length)];
            int[] cps = sb.toString().codePoints().toArray();
            cps["early".equals(escapePosition) ? 0 : cps.length - 1] = esc;
            sb.setLength(0);
            for (int cp : cps) sb.appendCodePoint(cp);
            while (sb.length() < length) {
                // Replacing a pair with one escape shortens the string;
                // refill with ASCII to hold the nominal length.
                sb.append(CLEAN_CHARS[rnd.nextInt(CLEAN_CHARS.length)]);
            }
        }
        return sb.toString();
    }

    /** Append one clean character from the script pool (pairs stay whole). */
    private static void appendCleanChar(Random rnd, StringBuilder sb, String script) {
        switch (script) {
            case "latin1" -> {
                if (rnd.nextBoolean()) sb.append(CLEAN_CHARS[rnd.nextInt(CLEAN_CHARS.length)]);
                else sb.append(LATIN1_CLEAN[rnd.nextInt(LATIN1_CLEAN.length)]);
            }
            case "bmp" -> {
                if (rnd.nextBoolean()) sb.append(CLEAN_CHARS[rnd.nextInt(CLEAN_CHARS.length)]);
                else sb.append(BMP_CLEAN[rnd.nextInt(BMP_CLEAN.length)]);
            }
            case "supplementary" -> {
                if (rnd.nextInt(10) < 7) sb.append(CLEAN_CHARS[rnd.nextInt(CLEAN_CHARS.length)]);
                else sb.appendCodePoint(SUPPLEMENTARY_CLEAN[rnd.nextInt(SUPPLEMENTARY_CLEAN.length)]);
            }
            case "mixed" -> {
                int pick = rnd.nextInt(100);
                if (pick < 60) sb.append(CLEAN_CHARS[rnd.nextInt(CLEAN_CHARS.length)]);
                else if (pick < 75) sb.append(LATIN1_CLEAN[rnd.nextInt(LATIN1_CLEAN.length)]);
                else if (pick < 90) sb.append(BMP_CLEAN[rnd.nextInt(BMP_CLEAN.length)]);
                else sb.appendCodePoint(SUPPLEMENTARY_CLEAN[rnd.nextInt(SUPPLEMENTARY_CLEAN.length)]);
            }
            default -> sb.append(CLEAN_CHARS[rnd.nextInt(CLEAN_CHARS.length)]);
        }
    }
}

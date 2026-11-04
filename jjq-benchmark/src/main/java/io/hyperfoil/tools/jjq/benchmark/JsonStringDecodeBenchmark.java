package io.hyperfoil.tools.jjq.benchmark;

import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.openjdk.jmh.annotations.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Fixed decoder cases, separating deferred scanning from forced decoding. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgs = {"-Xms2g", "-Xmx2g"})
@State(Scope.Thread)
public class JsonStringDecodeBenchmark {
    @Param({"asciiEscaped", "mixedEscaped", "asciiClean", "mixedClean",
            "tinyEscaped", "scanMixedEscaped", "cursorMixedEscaped"})
    public String fixture;

    private byte[][] inputs;
    private String[] expected;
    private int next;
    private int operation;

    @Setup
    public void setup() {
        boolean mixed = fixture.contains("Mixed") || fixture.startsWith("mixed");
        boolean escaped = !fixture.endsWith("Clean");
        operation = fixture.startsWith("scan") ? 1 : fixture.startsWith("cursor") ? 2 : 0;
        String unit = mixed ? "\u00E9\u4E2D\uD83D\uDE00abcdefghijklmnop" : "0123abcdefghijklmnop";
        String body = escaped ? (unit + "\n\"\\\t").repeat(20) : unit.repeat(24);
        if (fixture.equals("tinyEscaped")) body = "x\n\"\\y";
        inputs = new byte[64][];
        expected = new String[64];
        for (int i = 0; i < inputs.length; i++) {
            expected[i] = body + i;
            inputs[i] = ("\"" + JqString.escapeForBenchmark(expected[i]) + "\"")
                    .getBytes(StandardCharsets.UTF_8);
            if (!expected[i].equals(JqValues.parse(inputs[i]).stringValue())) {
                throw new IllegalStateException("Materialization precondition: " + fixture);
            }
            if (operation == 2 && !expected[i].equals(readCursor(inputs[i]))) {
                throw new IllegalStateException("Cursor did not complete: " + fixture);
            }
        }
    }

    @Benchmark
    public Object decode() {
        byte[] input = inputs[next];
        next = (next + 1) & 63;
        if (operation == 1) return JqValues.parse(input);
        if (operation == 2) return readCursor(input);
        // Fresh value each time: materialization must not become a memo hit.
        return JqValues.parse(input).stringValue();
    }

    private static String readCursor(byte[] input) {
        var cursor = JqValues.strictCursor(input);
        if (!cursor.beginDocument()) throw new IllegalStateException("Empty cursor input");
        String value = cursor.readString();
        if (value == null || !cursor.endInput()) throw new IllegalStateException("Cursor fallback");
        return value;
    }
}

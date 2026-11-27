package io.hyperfoil.tools.jjq.benchmark;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import io.hyperfoil.tools.jjq.value.BytOutput;

/**
 * {@code BytOutput.writeLong} micro-benchmark. The superheroes A/B
 * profile showed long formatting at ~2% of server CPU — 200 longs per
 * list-all response (ids and levels), all of it in the two division
 * loops of the count-then-emit form.
 *
 * <p>Distributions match observed REST shapes: sequential ids
 * (1-4 digits), hero levels (1-6 digits), epoch-millisecond
 * timestamps (13 digits), and the full long range with sign and
 * magnitude edges mixed in. A {@link Long#toString(long)} control
 * row anchors the JDK's own rendering cost on the same values.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsPrepend = {"-XX:-UseOnStackReplacement"})
@State(Scope.Benchmark)
public class JqWriteLongBenchmark {

    /** Value distributions, shaped after the superheroes A/B profile. */
    @Param({"ids", "levels", "epochMillis", "fullRange"})
    String distribution;

    private static final int SAMPLES = 1024;

    /** Sign and magnitude edges sprinkled into the full-range mix. */
    private static final long[] EDGES = {
            Long.MAX_VALUE, Long.MIN_VALUE, -1L, 0L, 10L, 100L, 1_000L,
            10_000L, 100_000L, 1_000_000L, 10_000_000L, 100_000_000L,
            1_000_000_000L, 100_000_000_000L, 10_000_000_000_000L,
            1_000_000_000_000_000L};

    private long[] values;
    private int nextIdx;
    private BytOutput out;

    @Setup
    public void setup() {
        Random rnd = new Random(42);
        values = new long[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            switch (distribution) {
                case "ids" -> values[i] = 1 + i; // sequential: 1-4 digits
                case "levels" -> values[i] = 1 + rnd.nextInt(200_000); // 1-6 digits
                case "epochMillis" -> values[i] = 1_700_000_000_000L + rnd.nextInt(1_000_000_000); // 13 digits
                case "fullRange" -> values[i] = (i % 16 == 0)
                        ? EDGES[(i / 16) % EDGES.length]
                        : rnd.nextLong();
                default -> throw new IllegalStateException(distribution);
            }
        }
        out = new BytOutput(64);
    }

    private long nextValue() {
        int idx = nextIdx;
        nextIdx = (idx + 1) % SAMPLES;
        return values[idx];
    }

    /** Reused buffer, reset per value: the generated mapper's shape. */
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @Benchmark
    public byte[] ser_jjq_writeLong() {
        out.reset();
        out.writeLong(nextValue());
        return out.toByteArray();
    }

    /** JDK control on the same values. */
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @Benchmark
    public String ser_jdk_longToString() {
        return Long.toString(nextValue());
    }
}

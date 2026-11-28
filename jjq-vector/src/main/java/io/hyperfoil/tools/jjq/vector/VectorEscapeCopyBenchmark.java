package io.hyperfoil.tools.jjq.vector;

import java.nio.charset.StandardCharsets;
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

/**
 * Kernel-level gate benchmark for the escape-copy kernel (#1B): the
 * scalar arm mirrors the byte-level scanner in {@code BytOutput} (the
 * production fallback), the vector arm is the kernel, and the
 * dispatched arm adds the enable check BytOutput performs.
 *
 * <p>Provided-scope JMH: run on demand (JDK 27+, explicit
 * {@code --add-modules jdk.incubator.vector}), e.g.
 * <pre>
 * java --add-modules jdk.incubator.vector -cp \
 *   jjq-vector/target/classes:jjq-core/target/classes:$JMH_CORE:$JOPT_SIMPLE \
 *   org.openjdk.jmh.Main VectorEscapeCopyBenchmark \
 *   -prof gc -rf json -rff out.json
 * </pre>
 * Gate: vector beats scalar on clean shapes by a wide margin growing
 * with length, break shapes tie (one masked handoff per value), zero
 * allocation delta.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsPrepend = {"-XX:-UseOnStackReplacement"})
@State(Scope.Benchmark)
public class VectorEscapeCopyBenchmark {

    @Param({"64", "512", "2048", "8192"})
    int length;

    /** clean: all ASCII; mid: quote at length/2; dense: quote every 8. */
    @Param({"clean", "mid", "dense"})
    String shape;

    byte[] input;
    byte[] dst;

    @Setup
    public void setup() {
        var rnd = new Random(20720);
        StringBuilder sb = new StringBuilder(length + 8);
        while (sb.length() < length) {
            char c = (char) (' ' + rnd.nextInt(95));
            if (c == '"' || c == '\\' || c == '?') c = 'a';
            if ("mid".equals(shape) && sb.length() == length / 2) c = '"';
            if ("dense".equals(shape) && sb.length() % 8 == 0) c = '"';
            sb.append(c);
        }
        input = sb.toString().getBytes(StandardCharsets.UTF_8);
        dst = new byte[length + 16];
    }

    /** Scalar arm mirroring BytOutput's byte-level scanner. */
    private static int copyScalar(byte[] a, int start, int end, byte[] d, int dstOff) {
        int i = start;
        int p = dstOff;
        for (; i < end; i++) {
            int b = a[i] & 0xFF;
            if (b < 0x20 || b == '"' || b == '\\') break;
            d[p++] = a[i];
        }
        return i - start;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int scalar() {
        return copyScalar(input, 0, input.length, dst, 8);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int vector() {
        return KERNEL.copyCleanUtf8(input, 0, input.length, dst, 8, true);
    }

    /** Production dispatch shape: the enable check BytOutput performs. */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int dispatched() {
        if (KERNEL.isEnabled()) {
            return KERNEL.copyCleanUtf8(input, 0, input.length, dst, 8, true);
        }
        return copyScalar(input, 0, input.length, dst, 8);
    }

    private static final VectorEscapeCopyKernel KERNEL = new VectorEscapeCopyKernel();
}

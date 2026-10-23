package io.hyperfoil.tools.jjq.vector;

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
 * Stage-1 gate benchmark (issue #187): scalar vs vector scan arms over
 * caller-provided Latin-1 bytes, plus the dispatched entry point.
 *
 * <p>Provided-scope JMH: run on demand (JDK 27+, explicit
 * {@code --add-modules jdk.incubator.vector}), e.g.
 * <pre>
 * java --add-modules jdk.incubator.vector -cp \
 *   jjq-vector/target/classes:$JMH_CORE:$JOPT_SIMPLE \
 *   org.openjdk.jmh.Main VectorScanBenchmark \
 *   -jvm $TEMURIN27/bin/java -prof gc -rf json -rff out.json
 * </pre>
 * Gate: vector beats scalar by &gt;3x on long-clean, ties-or-better
 * elsewhere, zero allocation delta.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsPrepend = {"-XX:-UseOnStackReplacement"})
@State(Scope.Benchmark)
public class VectorScanBenchmark {

    @Param({"512", "4096"})
    int length;

    @Param({"clean", "dense"})
    String shape;

    byte[] input;

    @Setup
    public void setup() {
        var rnd = new Random(187);
        input = new byte[length];
        for (int i = 0; i < length; i++) {
            byte b = (byte) (0x20 + rnd.nextInt(0x5F));
            if (b == '"' || b == '\\') b = 'a';
            if ("dense".equals(shape) && i % 40 == 0) b = '"';
            input[i] = b;
        }
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int scalar() {
        return VectorKernels.scanScalar(input, 0, length);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int vector() {
        return VectorKernels.scanVector(input, 0, length);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int dispatched() {
        return VectorKernels.scanFirstEscape(input, 0, length);
    }
}

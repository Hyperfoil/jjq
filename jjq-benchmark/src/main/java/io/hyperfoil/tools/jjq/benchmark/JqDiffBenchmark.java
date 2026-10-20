package io.hyperfoil.tools.jjq.benchmark;

import io.hyperfoil.tools.jjq.diff.JqDiff;
import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * Structural diff cost (issue #168): unchanged documents, sparse
 * changes, mid-array inserts, moves, the above-cap fallback, patch
 * application, and report rendering.
 *
 * <h3>Running</h3>
 * <pre>
 *   mvn package -pl jjq-core,jjq-benchmark -DskipTests -Pbenchmark
 *   java -jar jjq-benchmark/target/jjq-benchmark-*.jar JqDiffBenchmark -prof gc -rf json -rff diff.json
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgs = {"-Xmx2g", "-Xms2g"})
@State(Scope.Benchmark)
public class JqDiffBenchmark {

    private JqValue smallA;
    private JqValue smallB;
    private JqValue largeA;
    private JqValue largeB;
    private JqValue shuffledA;
    private JqValue shuffledB;
    private JqArray smallPatch;

    @Setup
    public void setup() {
        smallA = JqValues.parse("{\"a\":1,\"b\":[1,2,3],\"c\":{\"d\":\"x\"}}");
        smallB = JqValues.parse("{\"a\":1,\"b\":[1,9,3],\"c\":{\"d\":\"x\"}}");
        largeA = JqValues.parse(uniformArray(500, 0));
        largeB = JqValues.parse(uniformArray(500, 0));
        shuffledA = JqValues.parse(shuffledArray(600, false));
        shuffledB = JqValues.parse(shuffledArray(600, true));
        smallPatch = JqDiff.diff(smallA, smallB);
        // Preconditions: fixtures differ, and the patch round-trips.
        if (smallPatch.size() == 0) throw new IllegalStateException("small fixtures equal");
        if (!io.hyperfoil.tools.jjq.diff.JqPatch.apply(smallA, smallPatch).equals(smallB)) {
            throw new IllegalStateException("small patch does not round-trip");
        }
    }

    private static String uniformArray(int size, int seed) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(i + seed).append(",\"v\":\"x\"}");
        }
        return sb.append(']').toString();
    }

    private static String shuffledArray(int size, boolean reverse) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append(',');
            sb.append(reverse ? size - 1 - i : i);
        }
        return sb.append(']').toString();
    }

    @Benchmark
    public JqArray diffUnchanged() {
        return JqDiff.diff(largeA, largeB);
    }

    @Benchmark
    public JqArray diffSmallChange() {
        return JqDiff.diff(smallA, smallB);
    }

    @Benchmark
    public JqArray diffShuffled() {
        return JqDiff.diff(shuffledA, shuffledB);
    }

    @Benchmark
    public JqValue applyPatch() {
        return io.hyperfoil.tools.jjq.diff.JqPatch.apply(smallA, smallPatch);
    }

    @Benchmark
    public String renderReport() {
        return JqDiff.render(smallA, smallB);
    }
}

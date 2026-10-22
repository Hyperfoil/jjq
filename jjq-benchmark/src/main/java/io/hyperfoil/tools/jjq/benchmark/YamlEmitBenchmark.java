package io.hyperfoil.tools.jjq.benchmark;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.yaml.JqYaml;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Baseline for YAML emission (issue #143: the emitter never had a
 * benchmark). Parses once per trial (unmeasured setup) and measures
 * {@code JqYaml.toYaml} over the parsed tree, across the same
 * config/maps/mixed shapes as {@link YamlParseBaselineBenchmark}.
 *
 * <h3>Running</h3>
 * <pre>
 *   java --enable-preview -jar jjq-benchmark-*.jar YamlEmitBenchmark \
 *     -prof gc -rf json -rff yaml-emit.json
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgs = {"-Xmx2g", "-Xms2g", "--enable-preview"})
@State(Scope.Benchmark)
public class YamlEmitBenchmark {

    @Param({"config", "maps", "mixed"})
    String structure;

    @Param({"100kb", "1mb"})
    String size;

    private JqValue tree;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        String yaml;
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("benchmark-data/" + structure + "-" + size + ".yaml")) {
            if (in == null) throw new IOException("Missing resource: " + structure + "-" + size);
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        tree = JqYaml.parse(yaml);
    }

    @Benchmark
    public String emit_toYaml() {
        return JqYaml.toYaml(tree);
    }
}

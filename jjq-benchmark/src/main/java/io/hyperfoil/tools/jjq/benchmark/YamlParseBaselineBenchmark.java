package io.hyperfoil.tools.jjq.benchmark;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.yaml.JqYaml;
import org.openjdk.jmh.annotations.*;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Baseline for the native-YAML-parser epic (#90, Phase 0: #91).
 *
 * <p>Measures the current SnakeYAML-based {@code JqYaml} path against
 * SnakeYAML primitives (compose-only floor, construct-to-Java) and Jackson
 * YAML databind, across config/maps/mixed structures at 10kb/100kb/1mb plus
 * a hand-written isx-shaped 2kb config. The native parser (Phase 1, #92)
 * must beat {@code parse_jqyaml} here.</p>
 *
 * <h3>Running</h3>
 * <pre>
 *   # Throughput only
 *   ./scripts/run-benchmarks.sh YamlParseBaselineBenchmark
 *
 *   # With allocation profiling (recommended)
 *   java --enable-preview -jar jjq-benchmark-*.jar YamlParseBaselineBenchmark \
 *     -prof gc -rf json -rff yaml-baseline.json
 *
 *   # Allocation hotspots for Phase 1 design input
 *   java --enable-preview -jar jjq-benchmark-*.jar YamlParseBaselineBenchmark \
 *     -prof "async:event=alloc;output=flamegraph" -prof gc \
 *     -rf json -rff yaml-baseline-alloc.json
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgs = {"-Xmx2g", "-Xms2g", "--enable-preview"})
@State(Scope.Benchmark)
public class YamlParseBaselineBenchmark {

    @Param({"config", "maps", "mixed"})
    String structure;

    @Param({"10kb", "100kb", "1mb"})
    String size;

    private static final Yaml SNAKE = new Yaml();
    private static final YAMLMapper JACKSON_YAML = new YAMLMapper();

    private String paramYaml;
    private byte[] paramYamlBytes;
    private String config2kb;
    private byte[] config2kbBytes;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        paramYaml = loadResource("benchmark-data/" + structure + "-" + size + ".yaml");
        paramYamlBytes = paramYaml.getBytes(StandardCharsets.UTF_8);
        config2kb = loadResource("benchmark-data/config-2kb.yaml");
        config2kbBytes = config2kb.getBytes(StandardCharsets.UTF_8);
    }

    private static String loadResource(String name) throws IOException {
        try (InputStream in = YamlParseBaselineBenchmark.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IOException("Missing resource: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Benchmark
    public JqValue parse_jqyaml() {
        return JqYaml.parse(paramYaml);
    }

    @Benchmark
    public JqValue parse_jqyaml_stream() {
        return JqYaml.parse(new ByteArrayInputStream(paramYamlBytes));
    }

    @Benchmark
    public Object parse_snakeyaml_compose() {
        // Parse-cost floor: node tree only, no conversion (isolates convert cost in parse_jqyaml)
        return SNAKE.compose(new java.io.StringReader(paramYaml));
    }

    @Benchmark
    public Object parse_snakeyaml_load() {
        // Construct-to-Java reference (what plain SnakeYAML users pay)
        return SNAKE.load(paramYaml);
    }

    @Benchmark
    public JsonNode parse_jackson_yaml() throws Exception {
        return JACKSON_YAML.readTree(paramYaml);
    }

    // ---- isx-shaped 2kb config (the size class that actually matters for isx) ----

    @Benchmark
    public JqValue parse_jqyaml_config2kb() {
        return JqYaml.parse(config2kb);
    }

    @Benchmark
    public Object parse_snakeyaml_config2kb() {
        return SNAKE.load(config2kb);
    }

    @Benchmark
    public JsonNode parse_jackson_config2kb() throws Exception {
        return JACKSON_YAML.readTree(config2kb);
    }
}

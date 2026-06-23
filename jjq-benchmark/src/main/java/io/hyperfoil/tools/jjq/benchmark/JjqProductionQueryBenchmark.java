package io.hyperfoil.tools.jjq.benchmark;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Production-scale jq query benchmarks on the 14MB rhivos-perf-comprehensive
 * upload file. Measures jq expression execution on realistic data, filling
 * the gap between small-input VM benchmarks ({@link JjqBenchmark}) and
 * parse/serialize benchmarks ({@link JsonProductionBenchmark}).
 *
 * <p>The production file has 351,749 JSON nodes (93% strings, 6% numbers),
 * max depth 10, 17 top-level keys, with heavy {@code pcp_time_series} arrays
 * (502 entries x 127 keys each) typical of h5m/Horreum benchmark uploads.</p>
 *
 * <p>All benchmarks use {@link JqProgram#apply(JqValue)} /
 * {@link JqProgram#applyAll(JqValue)} — the actual h5m API path including
 * ThreadLocal VM caching. The document is pre-parsed in {@code @Setup} so
 * benchmarks measure pure jq execution time, not parsing.</p>
 *
 * <h3>Running</h3>
 * <pre>
 *   # Build
 *   mvn package -pl jjq-benchmark -DskipTests
 *
 *   # Baseline with GC profiling
 *   java --enable-preview -jar jjq-benchmark/target/jjq-benchmark-0.1.4-SNAPSHOT.jar \
 *     JjqProductionQueryBenchmark -prof gc \
 *     -rf json -rff benchmark-results/production-query-gc.json
 *
 *   # CPU flame graph (async-profiler)
 *   java --enable-preview -jar jjq-benchmark/target/jjq-benchmark-0.1.4-SNAPSHOT.jar \
 *     JjqProductionQueryBenchmark \
 *     -prof "async:libPath=../async-profiler/build/lib/libasyncProfiler.so;output=flamegraph;dir=profiles/production-query;event=cpu" \
 *     -rf json -rff benchmark-results/production-query-cpu.json
 *
 *   # Allocation flame graph (async-profiler)
 *   java --enable-preview -jar jjq-benchmark/target/jjq-benchmark-0.1.4-SNAPSHOT.jar \
 *     JjqProductionQueryBenchmark \
 *     -prof "async:libPath=../async-profiler/build/lib/libasyncProfiler.so;output=flamegraph;dir=profiles/production-query-alloc;event=alloc" \
 *     -rf json -rff benchmark-results/production-query-alloc.json
 *
 *   # Hardware counters
 *   java --enable-preview -jar jjq-benchmark/target/jjq-benchmark-0.1.4-SNAPSHOT.jar \
 *     JjqProductionQueryBenchmark -prof perfnorm \
 *     -rf json -rff benchmark-results/production-query-perfnorm.json
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgs = {"-Xmx4g", "-Xms4g", "--enable-preview"})
@State(Scope.Benchmark)
public class JjqProductionQueryBenchmark {

    private static final String RESOURCE = "benchmark-data/production-upload-14mb.json";

    // Pre-parsed 14MB document
    private JqValue productionDoc;

    // Category 1: Needle in haystack
    private JqProgram progTopField;
    private JqProgram progDeepField;
    private JqProgram progDeepFieldChain;

    // Category 2: Iterate + extract
    private JqProgram progIterateExtract;
    private JqProgram progIterateDeep;
    private JqProgram progIterateStressResults;

    // Category 3: Large array operations (PCP time series: 502 x 127 keys)
    private JqProgram progLength;
    private JqProgram progKeys;
    private JqProgram progExtractMetric;

    // Category 4: Round-trip (filter + serialize)
    private JqProgram progRoundTripIdentity;
    private JqProgram progRoundTripWorkload;
    private JqProgram progRoundTripSmall;

    // Category 5: Object construction
    private JqProgram progObjectConstruct;
    private JqProgram progCollectConfig;
    private JqProgram progPcpFirstEntry;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        byte[] bytes = loadResourceBytes(RESOURCE);
        productionDoc = JqValues.parse(bytes);

        // Category 1: Needle in haystack
        progTopField = JqProgram.compile(".user");
        progDeepField = JqProgram.compile(".autobench_workload.data[0].results");
        progDeepFieldChain = JqProgram.compile(".autobench_workload.data[0].results.autobench_results");

        // Category 2: Iterate + extract
        progIterateExtract = JqProgram.compile("[.stressng_workload.data[] | .sample_uuid]");
        progIterateDeep = JqProgram.compile("[.autobench_workload.data[] | .results]");
        progIterateStressResults = JqProgram.compile("[.stressng_workload.data[] | .test_results]");

        // Category 3: Large array operations
        progLength = JqProgram.compile(".autobench_workload.data[0].pcp_time_series | length");
        progKeys = JqProgram.compile(".autobench_workload.data[0].pcp_time_series[0] | keys");
        progExtractMetric = JqProgram.compile("[.autobench_workload.data[0].pcp_time_series[] | .[\"mem.util.used\"]]");

        // Category 4: Round-trip
        progRoundTripIdentity = JqProgram.compile(".");
        progRoundTripWorkload = JqProgram.compile(".autobench_workload");
        progRoundTripSmall = JqProgram.compile(".user");

        // Category 5: Object construction
        progObjectConstruct = JqProgram.compile("{user, uuid, run_id, start_time, end_time}");
        progCollectConfig = JqProgram.compile(".rhivos_config | {build, model, kernel, architecture}");
        progPcpFirstEntry = JqProgram.compile(".autobench_workload.data[0].pcp_time_series[0]");
    }

    // ========================================================================
    //  Category 1: Needle in haystack (h5m dominant pattern)
    //  Parse 14MB, extract one small value, discard the rest.
    // ========================================================================

    /** Single field access on 17-key root object. */
    @Benchmark
    public JqValue prod_topField() {
        return progTopField.apply(productionDoc);
    }

    /** 4-level chained field access: .autobench_workload.data[0].results */
    @Benchmark
    public JqValue prod_deepField() {
        return progDeepField.apply(productionDoc);
    }

    /** 5-level deep field chain: .autobench_workload.data[0].results.autobench_results */
    @Benchmark
    public JqValue prod_deepFieldChain() {
        return progDeepFieldChain.apply(productionDoc);
    }

    // ========================================================================
    //  Category 2: Iterate + extract on arrays
    //  Exercises collect-iterate fused opcode on real data.
    // ========================================================================

    /** Collect sample_uuid from 6 stressng data entries. */
    @Benchmark
    public JqValue prod_iterateExtract() {
        return progIterateExtract.apply(productionDoc);
    }

    /** Extract results from autobench data entries (1 element). */
    @Benchmark
    public JqValue prod_iterateDeep() {
        return progIterateDeep.apply(productionDoc);
    }

    /** Extract test_results from 6 stressng data entries. */
    @Benchmark
    public JqValue prod_iterateStressResults() {
        return progIterateStressResults.apply(productionDoc);
    }

    // ========================================================================
    //  Category 3: Large array operations
    //  PCP time series: 502 entries, each with 127 keys.
    // ========================================================================

    /** Length of 502-element PCP time series array. */
    @Benchmark
    public JqValue prod_length() {
        return progLength.apply(productionDoc);
    }

    /** Sorted keys of a 127-key PCP time series entry. */
    @Benchmark
    public JqValue prod_keys() {
        return progKeys.apply(productionDoc);
    }

    /** Extract one metric from all 502 PCP time series entries. */
    @Benchmark
    public JqValue prod_extractMetric() {
        return progExtractMetric.apply(productionDoc);
    }

    // ========================================================================
    //  Category 4: Round-trip (jq filter + serialize result)
    // ========================================================================

    /** Identity filter on 14MB document + serialize entire result. */
    @Benchmark
    public String prod_roundTrip_identity() {
        return progRoundTripIdentity.apply(productionDoc).toJsonString();
    }

    /** Extract autobench_workload subtree + serialize. */
    @Benchmark
    public String prod_roundTrip_workload() {
        return progRoundTripWorkload.apply(productionDoc).toJsonString();
    }

    /** Extract tiny value (.user) + serialize. */
    @Benchmark
    public String prod_roundTrip_small() {
        return progRoundTripSmall.apply(productionDoc).toJsonString();
    }

    // ========================================================================
    //  Category 5: Object construction / transformation
    // ========================================================================

    /** Build new 5-field object from selected top-level fields. */
    @Benchmark
    public JqValue prod_objectConstruct() {
        return progObjectConstruct.apply(productionDoc);
    }

    /** Pipe into nested object + construct 4-field config summary. */
    @Benchmark
    public JqValue prod_collectConfig() {
        return progCollectConfig.apply(productionDoc);
    }

    /** Extract single 127-key object from 502-element PCP array. */
    @Benchmark
    public JqValue prod_pcpFirstEntry() {
        return progPcpFirstEntry.apply(productionDoc);
    }

    // ========================================================================
    //  Utility
    // ========================================================================

    private static byte[] loadResourceBytes(String name) throws IOException {
        try (InputStream is = JjqProductionQueryBenchmark.class.getClassLoader()
                .getResourceAsStream(name)) {
            if (is == null) throw new IOException("Resource not found: " + name);
            return is.readAllBytes();
        }
    }
}

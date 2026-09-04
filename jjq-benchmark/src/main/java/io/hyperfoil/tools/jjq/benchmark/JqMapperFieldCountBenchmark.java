package io.hyperfoil.tools.jjq.benchmark;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * Investigates x86_64 register spilling in generated mappings by measuring
 * per-field deserialization cost at varying field counts (5, 10, 20).
 *
 * <p>Background: Franz Nigro (Red Hat) discovered that on x86_64, C2 inlines
 * N field accessor calls into a single generated method, exhausting the 16 GP
 * registers and causing register spills (push/pop to stack). ARM (31 GP regs)
 * is unaffected. See issue #64.</p>
 *
 * <p>If per-field cost stays constant (~4-5 ns) across field counts, no spilling.
 * If per-field cost increases at 20 fields, register spilling is confirmed.</p>
 *
 * <h3>Running</h3>
 * <pre>
 * mvn package -pl jjq-core,jjq-jackson,jjq-fastjson2,jjq-mapper,jjq-mapper-processor,jjq-benchmark -DskipTests -Pbenchmark
 * java -jar jjq-benchmark/target/jjq-benchmark-*.jar JqMapperFieldCountBenchmark
 *
 * # With allocation profiling
 * java -jar jjq-benchmark/target/jjq-benchmark-*.jar JqMapperFieldCountBenchmark -prof gc
 *
 * # With assembly output (look for stack spills: mov [rsp+...])
 * java -jar jjq-benchmark/target/jjq-benchmark-*.jar JqMapperFieldCountBenchmark -prof perfasm
 * </pre>
 *
 * <h3>Analyzing results</h3>
 * <pre>
 * Per-field cost = total_ns / field_count
 *
 * No spilling:  5f=22ns (4.4/f), 10f=44ns (4.4/f), 20f=88ns (4.4/f)
 * Spilling:     5f=22ns (4.4/f), 10f=55ns (5.5/f), 20f=160ns (8.0/f)
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgs = {"-Xmx2g", "-Xms2g"})
@State(Scope.Benchmark)
public class JqMapperFieldCountBenchmark {

    private JqMapper mapper;
    private static final ObjectMapper JACKSON = new ObjectMapper();

    // Pre-parsed JqValues
    private JqValue jq5, jq10, jq20;

    // Pre-parsed Jackson JsonNodes
    private JsonNode jn5, jn10, jn20;

    @Setup
    public void setup() throws Exception {
        mapper = JqMapper.create();

        // Generate JSON and pre-parse
        String json5 = FieldCountRecords.generateJson(5);
        String json10 = FieldCountRecords.generateJson(10);
        String json20 = FieldCountRecords.generateJson(20);

        jq5 = JqValues.parse(json5.getBytes());
        jq10 = JqValues.parse(json10.getBytes());
        jq20 = JqValues.parse(json20.getBytes());

        jn5 = JACKSON.readTree(json5);
        jn10 = JACKSON.readTree(json10);
        jn20 = JACKSON.readTree(json20);

        // Warmup all mapper caches (class introspection + generated mapping discovery)
        mapper.fromJqValue(jq5, FieldCountRecords.Gen5.class);
        mapper.fromJqValue(jq10, FieldCountRecords.Gen10.class);
        mapper.fromJqValue(jq20, FieldCountRecords.Gen20.class);
        mapper.fromJqValue(jq5, FieldCountRecords.Refl5.class);
        mapper.fromJqValue(jq10, FieldCountRecords.Refl10.class);
        mapper.fromJqValue(jq20, FieldCountRecords.Refl20.class);
        JACKSON.treeToValue(jn5, FieldCountRecords.Gen5.class);
        JACKSON.treeToValue(jn10, FieldCountRecords.Gen10.class);
        JACKSON.treeToValue(jn20, FieldCountRecords.Gen20.class);
    }

    // ========================================================================
    //  Generated mapping (processor-generated, @JqMapped)
    // ========================================================================

    @Benchmark
    public FieldCountRecords.Gen5 generated_05fields() {
        return mapper.fromJqValue(jq5, FieldCountRecords.Gen5.class);
    }

    @Benchmark
    public FieldCountRecords.Gen10 generated_10fields() {
        return mapper.fromJqValue(jq10, FieldCountRecords.Gen10.class);
    }

    @Benchmark
    public FieldCountRecords.Gen20 generated_20fields() {
        return mapper.fromJqValue(jq20, FieldCountRecords.Gen20.class);
    }

    // ========================================================================
    //  Reflection mapping (no @JqMapped, runtime introspection)
    // ========================================================================

    @Benchmark
    public FieldCountRecords.Refl5 reflection_05fields() {
        return mapper.fromJqValue(jq5, FieldCountRecords.Refl5.class);
    }

    @Benchmark
    public FieldCountRecords.Refl10 reflection_10fields() {
        return mapper.fromJqValue(jq10, FieldCountRecords.Refl10.class);
    }

    @Benchmark
    public FieldCountRecords.Refl20 reflection_20fields() {
        return mapper.fromJqValue(jq20, FieldCountRecords.Refl20.class);
    }

    // ========================================================================
    //  Jackson treeToValue (pre-parsed JsonNode → record)
    // ========================================================================

    @Benchmark
    public FieldCountRecords.Gen5 jackson_05fields() throws JacksonException {
        return JACKSON.treeToValue(jn5, FieldCountRecords.Gen5.class);
    }

    @Benchmark
    public FieldCountRecords.Gen10 jackson_10fields() throws JacksonException {
        return JACKSON.treeToValue(jn10, FieldCountRecords.Gen10.class);
    }

    @Benchmark
    public FieldCountRecords.Gen20 jackson_20fields() throws JacksonException {
        return JACKSON.treeToValue(jn20, FieldCountRecords.Gen20.class);
    }
}

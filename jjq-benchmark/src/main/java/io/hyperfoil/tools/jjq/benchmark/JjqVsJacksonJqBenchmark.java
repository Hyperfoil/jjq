package io.hyperfoil.tools.jjq.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.builtin.BuiltinRegistry;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import io.hyperfoil.tools.jjq.vm.VirtualMachine;
import net.thisptr.jackson.jq.BuiltinFunctionLoader;
import net.thisptr.jackson.jq.JsonQuery;
import net.thisptr.jackson.jq.Scope;
import net.thisptr.jackson.jq.Versions;
import org.openjdk.jmh.annotations.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Head-to-head comparison of jjq (tree-walker + VM) vs jackson-jq.
 *
 * <p>All three engines execute the same jq filters on the same input data.
 * Programs are pre-compiled; this measures pure execution throughput.
 *
 * <p>Run with:
 * <pre>
 *   mvn package -pl jjq-benchmark -DskipTests
 *   java --enable-preview -jar jjq-benchmark/target/benchmarks.jar JjqVsJacksonJqBenchmark
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgs = "--enable-preview")
@State(org.openjdk.jmh.annotations.Scope.Benchmark)
public class JjqVsJacksonJqBenchmark {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // --- jjq state ---
    private JqProgram jjqIdentity, jjqFieldAccess, jjqPipeArith, jjqIterateMap, jjqComplexFilter, jjqReduce, jjqIterateMedium;
    private VirtualMachine vmIdentity, vmFieldAccess, vmPipeArith, vmIterateMap, vmComplexFilter, vmReduce, vmIterateMedium;
    private JqValue jjqSimpleObj, jjqSmallArray, jjqMediumArray;

    // --- jackson-jq state ---
    private net.thisptr.jackson.jq.Scope jacksonScope;
    private JsonQuery jkqIdentity, jkqFieldAccess, jkqPipeArith, jkqIterateMap, jkqComplexFilter, jkqReduce, jkqIterateMedium;
    private JsonNode jkqSimpleObj, jkqSmallArray, jkqMediumArray;

    @Setup
    public void setup() throws Exception {
        // --- jjq setup ---
        BuiltinRegistry builtins = new BuiltinRegistry();
        jjqIdentity = JqProgram.compile(".", builtins);
        jjqFieldAccess = JqProgram.compile(".name", builtins);
        jjqPipeArith = JqProgram.compile(".a | . + 1", builtins);
        jjqIterateMap = JqProgram.compile("[.[] | . * 2]", builtins);
        jjqComplexFilter = JqProgram.compile("[.[] | select(. > 5) | . * 2]", builtins);
        jjqReduce = JqProgram.compile("reduce .[] as $x (0; . + $x)", builtins);
        jjqIterateMedium = JqProgram.compile("[.[] | . * 2]", builtins);

        vmIdentity = new VirtualMachine(jjqIdentity.getBytecode(), builtins);
        vmFieldAccess = new VirtualMachine(jjqFieldAccess.getBytecode(), builtins);
        vmPipeArith = new VirtualMachine(jjqPipeArith.getBytecode(), builtins);
        vmIterateMap = new VirtualMachine(jjqIterateMap.getBytecode(), builtins);
        vmComplexFilter = new VirtualMachine(jjqComplexFilter.getBytecode(), builtins);
        vmReduce = new VirtualMachine(jjqReduce.getBytecode(), builtins);
        vmIterateMedium = new VirtualMachine(jjqIterateMedium.getBytecode(), builtins);

        jjqSimpleObj = JqValues.parse("{\"name\":\"Alice\",\"age\":30,\"a\":42}");
        jjqSmallArray = JqValues.parse("[1,2,3,4,5,6,7,8,9,10]");
        jjqMediumArray = JqValues.parse(buildArray(100));

        // --- jackson-jq setup ---
        jacksonScope = net.thisptr.jackson.jq.Scope.newEmptyScope();
        BuiltinFunctionLoader.getInstance().loadFunctions(Versions.JQ_1_6, jacksonScope);

        jkqIdentity = JsonQuery.compile(".", Versions.JQ_1_6);
        jkqFieldAccess = JsonQuery.compile(".name", Versions.JQ_1_6);
        jkqPipeArith = JsonQuery.compile(".a | . + 1", Versions.JQ_1_6);
        jkqIterateMap = JsonQuery.compile("[.[] | . * 2]", Versions.JQ_1_6);
        jkqComplexFilter = JsonQuery.compile("[.[] | select(. > 5) | . * 2]", Versions.JQ_1_6);
        jkqReduce = JsonQuery.compile("reduce .[] as $x (0; . + $x)", Versions.JQ_1_6);
        jkqIterateMedium = JsonQuery.compile("[.[] | . * 2]", Versions.JQ_1_6);

        jkqSimpleObj = MAPPER.readTree("{\"name\":\"Alice\",\"age\":30,\"a\":42}");
        jkqSmallArray = MAPPER.readTree("[1,2,3,4,5,6,7,8,9,10]");
        jkqMediumArray = MAPPER.readTree(buildArray(100));
    }

    private String buildArray(int size) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append(",");
            sb.append(i);
        }
        sb.append("]");
        return sb.toString();
    }

    // ======================================================================
    //  Identity: .
    // ======================================================================

    @Benchmark
    public List<JqValue> jjq_tree_identity() {
        return jjqIdentity.applyTreeWalker(jjqSimpleObj);
    }

    @Benchmark
    public List<JqValue> jjq_vm_identity() {
        return vmIdentity.execute(jjqSimpleObj);
    }

    @Benchmark
    public List<JsonNode> jackson_jq_identity() throws Exception {
        var out = new ArrayList<JsonNode>();
        jkqIdentity.apply(jacksonScope, jkqSimpleObj, out::add);
        return out;
    }

    // ======================================================================
    //  Field access: .name
    // ======================================================================

    @Benchmark
    public List<JqValue> jjq_tree_fieldAccess() {
        return jjqFieldAccess.applyTreeWalker(jjqSimpleObj);
    }

    @Benchmark
    public List<JqValue> jjq_vm_fieldAccess() {
        return vmFieldAccess.execute(jjqSimpleObj);
    }

    @Benchmark
    public List<JsonNode> jackson_jq_fieldAccess() throws Exception {
        var out = new ArrayList<JsonNode>();
        jkqFieldAccess.apply(jacksonScope, jkqSimpleObj, out::add);
        return out;
    }

    // ======================================================================
    //  Pipe + arithmetic: .a | . + 1
    // ======================================================================

    @Benchmark
    public List<JqValue> jjq_tree_pipeArith() {
        return jjqPipeArith.applyTreeWalker(jjqSimpleObj);
    }

    @Benchmark
    public List<JqValue> jjq_vm_pipeArith() {
        return vmPipeArith.execute(jjqSimpleObj);
    }

    @Benchmark
    public List<JsonNode> jackson_jq_pipeArith() throws Exception {
        var out = new ArrayList<JsonNode>();
        jkqPipeArith.apply(jacksonScope, jkqSimpleObj, out::add);
        return out;
    }

    // ======================================================================
    //  Iterate + map (small): [.[] | . * 2]  on 10-element array
    // ======================================================================

    @Benchmark
    public List<JqValue> jjq_tree_iterateMap() {
        return jjqIterateMap.applyTreeWalker(jjqSmallArray);
    }

    @Benchmark
    public List<JqValue> jjq_vm_iterateMap() {
        return vmIterateMap.execute(jjqSmallArray);
    }

    @Benchmark
    public List<JsonNode> jackson_jq_iterateMap() throws Exception {
        var out = new ArrayList<JsonNode>();
        jkqIterateMap.apply(jacksonScope, jkqSmallArray, out::add);
        return out;
    }

    // ======================================================================
    //  Complex filter: [.[] | select(. > 5) | . * 2]  on 10-element array
    // ======================================================================

    @Benchmark
    public List<JqValue> jjq_tree_complexFilter() {
        return jjqComplexFilter.applyTreeWalker(jjqSmallArray);
    }

    @Benchmark
    public List<JqValue> jjq_vm_complexFilter() {
        return vmComplexFilter.execute(jjqSmallArray);
    }

    @Benchmark
    public List<JsonNode> jackson_jq_complexFilter() throws Exception {
        var out = new ArrayList<JsonNode>();
        jkqComplexFilter.apply(jacksonScope, jkqSmallArray, out::add);
        return out;
    }

    // ======================================================================
    //  Reduce: reduce .[] as $x (0; . + $x)  on 10-element array
    // ======================================================================

    @Benchmark
    public List<JqValue> jjq_tree_reduce() {
        return jjqReduce.applyTreeWalker(jjqSmallArray);
    }

    @Benchmark
    public List<JqValue> jjq_vm_reduce() {
        return vmReduce.execute(jjqSmallArray);
    }

    @Benchmark
    public List<JsonNode> jackson_jq_reduce() throws Exception {
        var out = new ArrayList<JsonNode>();
        jkqReduce.apply(jacksonScope, jkqSmallArray, out::add);
        return out;
    }

    // ======================================================================
    //  Iterate + map (medium): [.[] | . * 2]  on 100-element array
    // ======================================================================

    @Benchmark
    public List<JqValue> jjq_tree_iterateMap_medium() {
        return jjqIterateMedium.applyTreeWalker(jjqMediumArray);
    }

    @Benchmark
    public List<JqValue> jjq_vm_iterateMap_medium() {
        return vmIterateMedium.execute(jjqMediumArray);
    }

    @Benchmark
    public List<JsonNode> jackson_jq_iterateMap_medium() throws Exception {
        var out = new ArrayList<JsonNode>();
        jkqIterateMedium.apply(jacksonScope, jkqMediumArray, out::add);
        return out;
    }

    // ======================================================================
    //  executeOne() variants — zero-allocation path for single-output programs
    // ======================================================================

    @Benchmark
    public JqValue jjq_vm_identity_one() {
        return vmIdentity.executeOne(jjqSimpleObj);
    }

    @Benchmark
    public JqValue jjq_vm_fieldAccess_one() {
        return vmFieldAccess.executeOne(jjqSimpleObj);
    }

    @Benchmark
    public JqValue jjq_vm_pipeArith_one() {
        return vmPipeArith.executeOne(jjqSimpleObj);
    }

    @Benchmark
    public JqValue jjq_vm_iterateMap_one() {
        return vmIterateMap.executeOne(jjqSmallArray);
    }

    @Benchmark
    public JqValue jjq_vm_complexFilter_one() {
        return vmComplexFilter.executeOne(jjqSmallArray);
    }

    @Benchmark
    public JqValue jjq_vm_reduce_one() {
        return vmReduce.executeOne(jjqSmallArray);
    }

    @Benchmark
    public JqValue jjq_vm_iterateMap_medium_one() {
        return vmIterateMedium.executeOne(jjqMediumArray);
    }

    // ======================================================================
    //  Parse benchmarks (compile time)
    // ======================================================================

    @Benchmark
    public JqProgram jjq_parse_simple() {
        return JqProgram.compile(".name");
    }

    @Benchmark
    public JsonQuery jackson_jq_parse_simple() throws Exception {
        return JsonQuery.compile(".name", Versions.JQ_1_6);
    }

    @Benchmark
    public JqProgram jjq_parse_complex() {
        return JqProgram.compile("[.[] | select(. > 5) | . * 2 + 1]");
    }

    @Benchmark
    public JsonQuery jackson_jq_parse_complex() throws Exception {
        return JsonQuery.compile("[.[] | select(. > 5) | . * 2 + 1]", Versions.JQ_1_6);
    }
}

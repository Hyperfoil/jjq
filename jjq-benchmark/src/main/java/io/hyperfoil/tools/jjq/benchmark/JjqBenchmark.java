package io.hyperfoil.tools.jjq.benchmark;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.builtin.BuiltinRegistry;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import io.hyperfoil.tools.jjq.vm.VirtualMachine;
import org.openjdk.jmh.annotations.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class JjqBenchmark {

    // Pre-compiled VMs (reuse across invocations — measures pure VM execution)
    private VirtualMachine identityVM;
    private VirtualMachine fieldAccessVM;
    private VirtualMachine pipeAndArithVM;
    private VirtualMachine iterateMapVM;
    private VirtualMachine complexFilterVM;
    private VirtualMachine reduceVM;
    private VirtualMachine chainedFieldVM;
    private VirtualMachine builtinLengthVM;
    private VirtualMachine builtinSortVM;
    private VirtualMachine builtinAddVM;
    private VirtualMachine collectIterateFieldVM;

    // Pre-compiled programs (measures JqProgram.apply() path with VM caching)
    private JqProgram progFieldAccess;
    private JqProgram progIterateMap;
    private JqProgram progCollectIterateField;
    private JqProgram progReduce;

    // Input values
    private JqValue simpleObj;
    private JqValue nestedObj;
    private JqValue smallArray;
    private JqValue mediumArray;
    private JqValue h5mResultsObj;

    @Setup
    public void setup() {
        BuiltinRegistry builtins = new BuiltinRegistry();

        identityVM = new VirtualMachine(JqProgram.compile(".", builtins).getBytecode(), builtins);
        fieldAccessVM = new VirtualMachine(JqProgram.compile(".name", builtins).getBytecode(), builtins);
        pipeAndArithVM = new VirtualMachine(JqProgram.compile(".a | . + 1", builtins).getBytecode(), builtins);
        iterateMapVM = new VirtualMachine(JqProgram.compile("[.[] | . * 2]", builtins).getBytecode(), builtins);
        complexFilterVM = new VirtualMachine(JqProgram.compile("[.[] | select(. > 5) | . * 2]", builtins).getBytecode(), builtins);
        reduceVM = new VirtualMachine(JqProgram.compile("reduce .[] as $x (0; . + $x)", builtins).getBytecode(), builtins);
        chainedFieldVM = new VirtualMachine(JqProgram.compile(".a.b", builtins).getBytecode(), builtins);
        builtinLengthVM = new VirtualMachine(JqProgram.compile("length", builtins).getBytecode(), builtins);
        builtinSortVM = new VirtualMachine(JqProgram.compile("sort", builtins).getBytecode(), builtins);
        builtinAddVM = new VirtualMachine(JqProgram.compile("add", builtins).getBytecode(), builtins);
        // h5m production pattern: [.results[].load.avThroughput]
        collectIterateFieldVM = new VirtualMachine(
                JqProgram.compile("[.results[].load.avThroughput]", builtins).getBytecode(), builtins);

        // Programs for JqProgram.apply() path benchmarks (measures VM caching)
        progFieldAccess = JqProgram.compile(".name", builtins);
        progIterateMap = JqProgram.compile("[.[] | . * 2]", builtins);
        progCollectIterateField = JqProgram.compile("[.results[].load.avThroughput]", builtins);
        progReduce = JqProgram.compile("reduce .[] as $x (0; . + $x)", builtins);

        simpleObj = JqValues.parse("{\"name\":\"Alice\",\"age\":30,\"a\":42}");
        nestedObj = JqValues.parse("{\"a\":{\"b\":42,\"c\":\"hello\"}}");
        smallArray = JqValues.parse("[1,2,3,4,5,6,7,8,9,10]");

        var sb = new StringBuilder("[");
        for (int i = 0; i < 100; i++) {
            if (i > 0) sb.append(",");
            sb.append(i);
        }
        sb.append("]");
        mediumArray = JqValues.parse(sb.toString());

        // h5m-style nested results object (mirrors real Horreum/qvss benchmark data)
        var h5mSb = new StringBuilder("{\"results\":[");
        String[] runtimes = {"quarkus3-jvm", "quarkus3-native", "spring-boot-jvm", "spring-boot-native", "micronaut-jvm"};
        for (int i = 0; i < runtimes.length; i++) {
            if (i > 0) h5mSb.append(",");
            h5mSb.append(String.format(
                "{\"name\":\"%s\",\"load\":{\"avThroughput\":%.1f,\"maxRss\":%d},\"rss\":{\"avStartupRss\":%d},\"build\":{\"avBuildTime\":%.1f}}",
                runtimes[i], 1000.0 + i * 250.5, 200 + i * 50, 80 + i * 15, 5.0 + i * 1.2
            ));
        }
        h5mSb.append("]}");
        h5mResultsObj = JqValues.parse(h5mSb.toString());
    }

    // --- VM benchmarks ---

    @Benchmark
    public List<JqValue> vm_identity() {
        return identityVM.execute(simpleObj);
    }

    @Benchmark
    public List<JqValue> vm_fieldAccess() {
        return fieldAccessVM.execute(simpleObj);
    }

    @Benchmark
    public List<JqValue> vm_pipeAndArith() {
        return pipeAndArithVM.execute(simpleObj);
    }

    @Benchmark
    public List<JqValue> vm_iterateMap() {
        return iterateMapVM.execute(smallArray);
    }

    @Benchmark
    public List<JqValue> vm_complexFilter() {
        return complexFilterVM.execute(smallArray);
    }

    @Benchmark
    public List<JqValue> vm_reduce() {
        return reduceVM.execute(smallArray);
    }

    @Benchmark
    public List<JqValue> vm_iterateMap_medium() {
        return iterateMapVM.execute(mediumArray);
    }

    // --- VM benchmarks: fused collect-iterate with field source (h5m pattern) ---

    @Benchmark
    public List<JqValue> vm_collectIterateField() {
        return collectIterateFieldVM.execute(h5mResultsObj);
    }

    // --- VM benchmarks: inlined builtins & compound field access ---

    @Benchmark
    public List<JqValue> vm_chainedField() {
        return chainedFieldVM.execute(nestedObj);
    }

    @Benchmark
    public List<JqValue> vm_builtinLength() {
        return builtinLengthVM.execute(smallArray);
    }

    @Benchmark
    public List<JqValue> vm_builtinSort() {
        return builtinSortVM.execute(smallArray);
    }

    @Benchmark
    public List<JqValue> vm_builtinAdd() {
        return builtinAddVM.execute(smallArray);
    }

    // --- JqProgram.apply() benchmarks (h5m path — measures VM caching benefit) ---

    @Benchmark
    public JqValue prog_fieldAccess() {
        return progFieldAccess.apply(simpleObj);
    }

    @Benchmark
    public List<JqValue> prog_iterateMap() {
        return progIterateMap.applyAll(smallArray);
    }

    @Benchmark
    public List<JqValue> prog_collectIterateField() {
        return progCollectIterateField.applyAll(h5mResultsObj);
    }

    @Benchmark
    public JqValue prog_reduce() {
        return progReduce.apply(smallArray);
    }

    // --- Parse benchmarks ---

    @Benchmark
    public JqProgram parse_simple() {
        return JqProgram.compile(".name");
    }

    @Benchmark
    public JqProgram parse_complex() {
        return JqProgram.compile("[.[] | select(. > 5) | . * 2 + 1]");
    }
}

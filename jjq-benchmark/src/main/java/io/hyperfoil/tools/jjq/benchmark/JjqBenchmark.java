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

    // Pre-compiled programs
    private JqProgram identityProg;
    private JqProgram fieldAccessProg;
    private JqProgram pipeAndArithProg;
    private JqProgram iterateMapProg;
    private JqProgram complexFilterProg;
    private JqProgram reduceProg;
    private JqProgram chainedFieldProg;
    private JqProgram builtinLengthProg;
    private JqProgram builtinSortProg;
    private JqProgram builtinAddProg;

    // Pre-compiled VMs (reuse across invocations)
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

    // Input values
    private JqValue simpleObj;
    private JqValue nestedObj;
    private JqValue smallArray;
    private JqValue mediumArray;

    @Setup
    public void setup() {
        BuiltinRegistry builtins = new BuiltinRegistry();
        identityProg = JqProgram.compile(".", builtins);
        fieldAccessProg = JqProgram.compile(".name", builtins);
        pipeAndArithProg = JqProgram.compile(".a | . + 1", builtins);
        iterateMapProg = JqProgram.compile("[.[] | . * 2]", builtins);
        complexFilterProg = JqProgram.compile("[.[] | select(. > 5) | . * 2]", builtins);
        reduceProg = JqProgram.compile("reduce .[] as $x (0; . + $x)", builtins);
        chainedFieldProg = JqProgram.compile(".a.b", builtins);
        builtinLengthProg = JqProgram.compile("length", builtins);
        builtinSortProg = JqProgram.compile("sort", builtins);
        builtinAddProg = JqProgram.compile("add", builtins);

        // Pre-create VMs (they pre-allocate stacks)
        identityVM = new VirtualMachine(identityProg.getBytecode(), builtins);
        fieldAccessVM = new VirtualMachine(fieldAccessProg.getBytecode(), builtins);
        pipeAndArithVM = new VirtualMachine(pipeAndArithProg.getBytecode(), builtins);
        iterateMapVM = new VirtualMachine(iterateMapProg.getBytecode(), builtins);
        complexFilterVM = new VirtualMachine(complexFilterProg.getBytecode(), builtins);
        reduceVM = new VirtualMachine(reduceProg.getBytecode(), builtins);
        chainedFieldVM = new VirtualMachine(chainedFieldProg.getBytecode(), builtins);
        builtinLengthVM = new VirtualMachine(builtinLengthProg.getBytecode(), builtins);
        builtinSortVM = new VirtualMachine(builtinSortProg.getBytecode(), builtins);
        builtinAddVM = new VirtualMachine(builtinAddProg.getBytecode(), builtins);

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
    }

    // --- Tree-walker benchmarks ---

    @Benchmark
    public List<JqValue> treeWalker_identity() {
        return identityProg.applyTreeWalker(simpleObj);
    }

    @Benchmark
    public List<JqValue> treeWalker_fieldAccess() {
        return fieldAccessProg.applyTreeWalker(simpleObj);
    }

    @Benchmark
    public List<JqValue> treeWalker_pipeAndArith() {
        return pipeAndArithProg.applyTreeWalker(simpleObj);
    }

    @Benchmark
    public List<JqValue> treeWalker_iterateMap() {
        return iterateMapProg.applyTreeWalker(smallArray);
    }

    @Benchmark
    public List<JqValue> treeWalker_complexFilter() {
        return complexFilterProg.applyTreeWalker(smallArray);
    }

    @Benchmark
    public List<JqValue> treeWalker_reduce() {
        return reduceProg.applyTreeWalker(smallArray);
    }

    @Benchmark
    public List<JqValue> treeWalker_iterateMap_medium() {
        return iterateMapProg.applyTreeWalker(mediumArray);
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

    // --- Tree-walker benchmarks: same ops for comparison ---

    @Benchmark
    public List<JqValue> treeWalker_chainedField() {
        return chainedFieldProg.applyTreeWalker(nestedObj);
    }

    @Benchmark
    public List<JqValue> treeWalker_builtinLength() {
        return builtinLengthProg.applyTreeWalker(smallArray);
    }

    @Benchmark
    public List<JqValue> treeWalker_builtinSort() {
        return builtinSortProg.applyTreeWalker(smallArray);
    }

    @Benchmark
    public List<JqValue> treeWalker_builtinAdd() {
        return builtinAddProg.applyTreeWalker(smallArray);
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

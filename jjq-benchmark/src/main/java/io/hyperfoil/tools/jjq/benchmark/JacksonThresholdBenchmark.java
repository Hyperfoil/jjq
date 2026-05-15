package io.hyperfoil.tools.jjq.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.builtin.BuiltinRegistry;
import io.hyperfoil.tools.jjq.jackson.JacksonConverter;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.vm.VirtualMachine;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgs = "--enable-preview")
@State(Scope.Benchmark)
public class JacksonThresholdBenchmark {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode smallInputNode;
    private JsonNode mediumInputNode;
    private JsonNode largeInputNode;

    private VirtualMachine identityVm;
    private VirtualMachine singleFieldVm;
    private VirtualMachine multiFieldVm;

    @Setup
    public void setup() throws Exception {
        smallInputNode = MAPPER.readTree(buildObjectJson(5));
        mediumInputNode = MAPPER.readTree(buildObjectJson(20));
        largeInputNode = MAPPER.readTree(buildObjectJson(100));

        BuiltinRegistry builtins = BuiltinRegistry.getDefault();
        JqProgram identityProgram = JqProgram.compile(".", builtins);
        JqProgram singleFieldProgram = JqProgram.compile(".f0", builtins);
        JqProgram multiFieldProgram = JqProgram.compile("{f0, f1, f2, f3}", builtins);
        identityVm = new VirtualMachine(identityProgram.getBytecode(), builtins);
        singleFieldVm = new VirtualMachine(singleFieldProgram.getBytecode(), builtins);
        multiFieldVm = new VirtualMachine(multiFieldProgram.getBytecode(), builtins);
    }

    @Benchmark
    public JsonNode small_identity_roundtrip() {
        JqValue in = JacksonConverter.fromJsonNodeLazy(smallInputNode);
        JqValue out = identityVm.executeOne(in);
        return JacksonConverter.toJsonNode(out);
    }

    @Benchmark
    public JsonNode medium_identity_roundtrip() {
        JqValue in = JacksonConverter.fromJsonNodeLazy(mediumInputNode);
        JqValue out = identityVm.executeOne(in);
        return JacksonConverter.toJsonNode(out);
    }

    @Benchmark
    public JsonNode large_identity_roundtrip() {
        JqValue in = JacksonConverter.fromJsonNodeLazy(largeInputNode);
        JqValue out = identityVm.executeOne(in);
        return JacksonConverter.toJsonNode(out);
    }

    @Benchmark
    public JsonNode small_singleField() {
        JqValue in = JacksonConverter.fromJsonNodeLazy(smallInputNode);
        JqValue out = singleFieldVm.executeOne(in);
        return JacksonConverter.toJsonNode(out);
    }

    @Benchmark
    public JsonNode medium_singleField() {
        JqValue in = JacksonConverter.fromJsonNodeLazy(mediumInputNode);
        JqValue out = singleFieldVm.executeOne(in);
        return JacksonConverter.toJsonNode(out);
    }

    @Benchmark
    public JsonNode large_singleField() {
        JqValue in = JacksonConverter.fromJsonNodeLazy(largeInputNode);
        JqValue out = singleFieldVm.executeOne(in);
        return JacksonConverter.toJsonNode(out);
    }

    @Benchmark
    public JsonNode small_multiField() {
        JqValue in = JacksonConverter.fromJsonNodeLazy(smallInputNode);
        JqValue out = multiFieldVm.executeOne(in);
        return JacksonConverter.toJsonNode(out);
    }

    @Benchmark
    public JsonNode medium_multiField() {
        JqValue in = JacksonConverter.fromJsonNodeLazy(mediumInputNode);
        JqValue out = multiFieldVm.executeOne(in);
        return JacksonConverter.toJsonNode(out);
    }

    @Benchmark
    public JsonNode large_multiField() {
        JqValue in = JacksonConverter.fromJsonNodeLazy(largeInputNode);
        JqValue out = multiFieldVm.executeOne(in);
        return JacksonConverter.toJsonNode(out);
    }

    private static String buildObjectJson(int fields) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < fields; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append("f").append(i).append('"').append(':').append(i);
        }
        sb.append('}');
        return sb.toString();
    }
}

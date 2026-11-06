package io.hyperfoil.tools.jjq.benchmark;

import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.value.CharOutput;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.util.concurrent.TimeUnit;

/** Five-case #207 feasibility pilot, not the full escape parameter matrix. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgs = {"-Xms2g", "-Xmx2g"})
@State(Scope.Thread)
public class CharOutputBenchmark {
    @Param({"simple", "nested", "pojo", "escaped", "writer100kb"})
    public String shape;

    @Param({"builder", "buffered"})
    public String backend;

    private boolean buffered;
    private JqMapper mapper;
    private Object value;
    private JqValue tree;
    private ConsumingWriter writer;

    @Setup
    public void setup() throws Exception {
        buffered = "buffered".equals(backend);
        if (!buffered && !"builder".equals(backend)) throw new IllegalArgumentException(backend);
        mapper = JqMapper.create();
        writer = new ConsumingWriter();
        value = switch (shape) {
            case "simple" -> new BenchmarkRecords.SimpleRecord("Alice", 30, 98.5, true, "alice@example.com");
            case "nested" -> new BenchmarkRecords.PersonRecord("Alice", 30,
                    new BenchmarkRecords.Address("New York", "10001", "US"));
            case "pojo" -> nestedPojo();
            case "escaped" -> new BenchmarkRecords.PersonRecord("\"Alice\\\n\u00E9\u4E2D\uD83D\uDE00", 30,
                    new BenchmarkRecords.Address("New\tYork", "\u0000zip\"", "\u4E2D\uD83D\uDE00\\"));
            case "writer100kb" -> null;
            default -> throw new IllegalArgumentException(shape);
        };
        if (value != null) {
            assertGenerated(value.getClass());
            if (value instanceof BenchmarkRecords.PersonRecord) assertGenerated(BenchmarkRecords.Address.class);
            if (value instanceof BenchmarkRecords.PojoPerson) assertGenerated(BenchmarkRecords.PojoAddress.class);
            StringBuilder legacy = new StringBuilder();
            mapper.appendJson(value, legacy);
            String builderJson = legacy.toString();
            String bufferedJson = mapper.toJsonBuffered(value);
            if (!builderJson.equals(bufferedJson)) throw new IllegalStateException("String parity: " + shape);
            if (!builderJson.equals(mapper.toJson(value))) throw new IllegalStateException("Default parity: " + shape);
            StringWriter check = new StringWriter();
            mapper.writeJsonBuffered(value, check);
            if (!builderJson.equals(check.toString())) throw new IllegalStateException("Writer parity: " + shape);
        } else {
            try (InputStream input = getClass().getClassLoader()
                    .getResourceAsStream("benchmark-data/nested-100kb.json")) {
                if (input == null) throw new IllegalStateException("Missing 100KB fixture");
                tree = JqValues.parse(input.readAllBytes());
            }
            StringWriter builder = new StringWriter();
            StringWriter candidate = new StringWriter();
            JqValues.serializeTo(tree, builder);
            JqValues.serializeBufferedTo(tree, candidate);
            if (!builder.toString().equals(candidate.toString())) throw new IllegalStateException("Tree parity");
        }
    }

    private static BenchmarkRecords.PojoPerson nestedPojo() {
        var address = new BenchmarkRecords.PojoAddress();
        address.setCity("New York");
        address.setZip("10001");
        address.setCountry("US");
        var person = new BenchmarkRecords.PojoPerson();
        person.setName("Alice");
        person.setAge(30);
        person.setAddress(address);
        return person;
    }

    private static void assertGenerated(Class<?> type) throws ReflectiveOperationException {
        Class<?> mapping = Class.forName(type.getName().replace('$', '_') + "_JqMapping", true, type.getClassLoader());
        // The prototype must not silently inherit Mapping's tree fallback.
        mapping.getDeclaredMethod("appendJson", type, CharOutput.class, JqMapper.class);
    }

    @Benchmark
    public String serialize(Blackhole blackhole) throws IOException {
        if (tree == null) {
            if (buffered) return mapper.toJsonBuffered(value);
            StringBuilder sb = JqValues.acquireSerializerBuffer();
            mapper.appendJson(value, sb);
            return JqValues.releaseSerializerBuffer(sb);
        }
        writer.blackhole = blackhole;
        writer.written = 0;
        if (buffered) JqValues.serializeBufferedTo(tree, writer);
        else JqValues.serializeTo(tree, writer);
        blackhole.consume(writer.written);
        return null;
    }

    /** Opaque consuming destination, so C2 cannot discard the buffer stores. */
    public static final class ConsumingWriter extends Writer {
        private Blackhole blackhole;
        private int written;

        @Override
        @CompilerControl(CompilerControl.Mode.DONT_INLINE)
        public void write(char[] chars, int offset, int length) {
            blackhole.consume(chars);
            blackhole.consume(offset);
            blackhole.consume(length);
            written += length;
        }

        @Override
        public void flush() { }

        @Override
        public void close() { }
    }
}

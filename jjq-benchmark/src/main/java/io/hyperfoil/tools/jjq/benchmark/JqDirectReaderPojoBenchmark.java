package io.hyperfoil.tools.jjq.benchmark;

import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import io.hyperfoil.tools.jjq.mapper.BindingSchema;
import io.hyperfoil.tools.jjq.mapper.DirectReader;
import io.hyperfoil.tools.jjq.mapper.JqMapped;
import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.quarkus.deployment.JjqProcessor;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import io.quarkus.gizmo2.ClassOutput;

/**
 * Flat-POJO direct-reader benchmark (issue #4): the openapi-generator
 * model shape — private fields, public getters/setters, public no-arg
 * constructor — decoded through the generated reader versus the
 * strict-parse-plus-bind tree path.
 *
 * <p>The tree rows are unchanged code in every build, so the
 * direct-vs-tree comparison within this benchmark is the gate: the
 * baseline jar cannot run these cells (POJO readers did not exist
 * there), and the record benchmark covers cross-build regressions.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(value = 3, jvmArgs = {"-Xmx2g", "-Xms2g"})
@State(Scope.Benchmark)
public class JqDirectReaderPojoBenchmark {

    /** openapi-generator shape: private fields, public accessors. */
    @JqMapped
    public static class Flat5Pojo {
        private String name;
        private int age;
        private long score;
        private boolean active;
        private Integer optAge;

        public Flat5Pojo() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }
        public long getScore() { return score; }
        public void setScore(long score) { this.score = score; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
        public Integer getOptAge() { return optAge; }
        public void setOptAge(Integer optAge) { this.optAge = optAge; }
    }

    /** Wide JavaBean shape: setter writes across 20 fields. */
    @JqMapped
    public static class Flat20Pojo {
        private String s00;
        private int i01;
        private long l02;
        private boolean b03;
        private Integer e04;
        private String s05;
        private int i06;
        private long l07;
        private boolean b08;
        private Long e09;
        private String s10;
        private int i11;
        private long l12;
        private boolean b13;
        private Boolean e14;
        private String s15;
        private int i16;
        private long l17;
        private boolean b18;
        private String s19;

        public Flat20Pojo() {}

        public String getS00() { return s00; }
        public void setS00(String v) { this.s00 = v; }
        public int getI01() { return i01; }
        public void setI01(int v) { this.i01 = v; }
        public long getL02() { return l02; }
        public void setL02(long v) { this.l02 = v; }
        public boolean isB03() { return b03; }
        public void setB03(boolean v) { this.b03 = v; }
        public Integer getE04() { return e04; }
        public void setE04(Integer v) { this.e04 = v; }
        public String getS05() { return s05; }
        public void setS05(String v) { this.s05 = v; }
        public int getI06() { return i06; }
        public void setI06(int v) { this.i06 = v; }
        public long getL07() { return l07; }
        public void setL07(long v) { this.l07 = v; }
        public boolean isB08() { return b08; }
        public void setB08(boolean v) { this.b08 = v; }
        public Long getE09() { return e09; }
        public void setE09(Long v) { this.e09 = v; }
        public String getS10() { return s10; }
        public void setS10(String v) { this.s10 = v; }
        public int getI11() { return i11; }
        public void setI11(int v) { this.i11 = v; }
        public long getL12() { return l12; }
        public void setL12(long v) { this.l12 = v; }
        public boolean isB13() { return b13; }
        public void setB13(boolean v) { this.b13 = v; }
        public Boolean getE14() { return e14; }
        public void setE14(Boolean v) { this.e14 = v; }
        public String getS15() { return s15; }
        public void setS15(String v) { this.s15 = v; }
        public int getI16() { return i16; }
        public void setI16(int v) { this.i16 = v; }
        public long getL17() { return l17; }
        public void setL17(long v) { this.l17 = v; }
        public boolean isB18() { return b18; }
        public void setB18(boolean v) { this.b18 = v; }
        public String getS19() { return s19; }
        public void setS19(String v) { this.s19 = v; }
    }

    /** Which fixture the current iteration decodes. */
    @Param({"flat5pojo", "flat20pojo"})
    public String fixture;

    /**
     * Document shape. Canonical shapes complete through the reader;
     * {@code coerce} and {@code unknown} fall back (the fallback outcome
     * always equals the baseline outcome).
     */
    @Param({"ordered", "reordered", "partial", "nulls", "escaped", "coerce", "unknown"})
    public String shape;

    private byte[] document;
    private Class<?> targetType;
    private DirectReader<?> reader;
    private JqMapper baselineMapper;
    private JqMapper dispatchMapper;

    private String document(String shape) {
        if (fixture.equals("flat5pojo")) {
            return switch (shape) {
                case "ordered" -> "{\"name\":\"ann\",\"age\":30,\"score\":100,\"active\":true,\"optAge\":7}";
                case "reordered" -> "{\"optAge\":7,\"active\":true,\"score\":100,\"age\":30,\"name\":\"ann\"}";
                case "partial" -> "{\"name\":\"ann\"}";
                case "nulls" -> "{\"name\":null,\"age\":5,\"score\":null,\"active\":null}";
                case "escaped" -> "{\"name\":\"a\\\"b\\\\c\\/d\\u00e9\",\"age\":1,\"score\":2,\"active\":false}";
                case "coerce" -> "{\"name\":42,\"age\":1,\"score\":2,\"active\":false}";
                case "unknown" -> "{\"name\":\"ann\",\"age\":1,\"score\":2,\"active\":false,\"extra\":1}";
                default -> throw new IllegalArgumentException(shape);
            };
        }
        String ordered = "{\"s00\":\"a\",\"i01\":1,\"l02\":2,\"b03\":true,\"e04\":4,"
                + "\"s05\":\"e\",\"i06\":6,\"l07\":7,\"b08\":false,\"e09\":9,"
                + "\"s10\":\"j\",\"i11\":11,\"l12\":12,\"b13\":true,\"e14\":null,"
                + "\"s15\":\"o\",\"i16\":16,\"l17\":17,\"b18\":false,\"s19\":\"s\"}";
        return switch (shape) {
            case "ordered" -> ordered;
            case "reordered" ->
                    "{\"s19\":\"s\",\"b18\":false,\"l17\":17,\"i16\":16,\"s15\":\"o\",\"e14\":null,"
                    + "\"b13\":true,\"l12\":12,\"i11\":11,\"s10\":\"j\",\"e09\":9,\"b08\":false,"
                    + "\"l07\":7,\"i06\":6,\"s05\":\"e\",\"e04\":4,\"b03\":true,\"l02\":2,"
                    + "\"i01\":1,\"s00\":\"a\"}";
            case "partial" -> "{\"s00\":\"a\",\"i01\":1}";
            case "nulls" -> "{\"s00\":null,\"i01\":1,\"l02\":null,\"b03\":null,\"e04\":null}";
            case "escaped" -> ordered.replace("\"a\"", "\"a\\\"b\"");
            case "coerce" -> ordered.replace("\"i01\":1", "\"i01\":\"1\"");
            case "unknown" -> ordered.replaceFirst("\\}$", ",\"zz\":1}");
            default -> throw new IllegalArgumentException(shape);
        };
    }

    /** Canonical shapes complete; everything else falls back or throws. */
    private static boolean expectComplete(String shape) {
        return switch (shape) {
            case "ordered", "reordered", "partial", "nulls", "escaped" -> true;
            default -> false;
        };
    }

    @Setup
    public void setup() throws Throwable {
        document = document(shape).getBytes(StandardCharsets.UTF_8);
        targetType = fixture.equals("flat5pojo") ? Flat5Pojo.class : Flat20Pojo.class;

        baselineMapper = JqMapper.create();
        baselineMapper.fromJqValue(
                JqValues.parseStrict(document("ordered").getBytes(StandardCharsets.UTF_8)),
                targetType);

        BindingSchema schema = baselineMapper.bindingSchemaFor(targetType);
        if (!schema.eligibleForDirectReader()) {
            throw new IllegalStateException("fixture lost eligibility: "
                    + schema.ineligibilityReasons());
        }
        Map<String, byte[]> classes = new LinkedHashMap<>();
        ClassOutput output = classes::put;
        JjqProcessor.generateDirectReader(output, targetType, schema);
        byte[] readerBytes = classes.values().iterator().next();
        Class<?> readerClass = MethodHandles.privateLookupIn(
                JqDirectReaderPojoBenchmark.class, MethodHandles.lookup())
                .defineClass(readerBytes);
        @SuppressWarnings("unchecked")
        DirectReader<Object> generated = (DirectReader<Object>) readerClass.getDeclaredConstructor()
                .newInstance();
        reader = generated;
        dispatchMapper = JqMapper.builder().registerDirectReader(generated).build();

        boolean completed;
        try {
            generated.read(document, dispatchMapper);
            completed = true;
        } catch (RuntimeException e) {
            completed = false;
        }
        if (completed != expectComplete(shape)) {
            throw new IllegalStateException(
                    "shape " + shape + " completed=" + completed + " (fixture " + fixture + ")");
        }
    }

    /** The tree path: strict parse of the whole document, then bind. */
    @Benchmark
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object baselineParseMap() {
        return ((JqMapper) baselineMapper).fromJqValue(
                JqValues.parseStrict(document), (Class) targetType);
    }

    /** Control: strict parse alone, for decomposing parse vs bind cost. */
    @Benchmark
    public JqValue baselineParseOnly() {
        return JqValues.parseStrict(document);
    }

    /** Candidate: generated direct strict reader, no tree. */
    @Benchmark
    @SuppressWarnings("unchecked")
    public Object directRead() {
        return ((DirectReader<Object>) reader).read(document, dispatchMapper);
    }

    /** REST path: dispatch through the mapper (reader lookup + fallback). */
    @Benchmark
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object strictDispatch() {
        return ((JqMapper) dispatchMapper).fromJsonStrict(document, (Class) targetType);
    }
}

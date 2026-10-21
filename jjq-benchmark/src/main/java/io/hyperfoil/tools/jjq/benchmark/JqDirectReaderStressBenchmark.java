package io.hyperfoil.tools.jjq.benchmark;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

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
import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.quarkus.gizmo2.ClassOutput;

/**
 * Many-reader stress: N distinct flat record types (varying widths) sharing
 * one {@code fromJsonStrict} dispatch site. Answers whether reader count or
 * width introduces a JIT cliff (megamorphic dispatch, code-cache pressure)
 * beyond the single-reader numbers.
 *
 * <p>Record sources are generated and compiled in setup (no annotation
 * processing needed: schemas compile reflectively and readers construct
 * through canonical constructors). Every document is canonical ordered
 * JSON, so all traffic completes through readers — this measures dispatch
 * scalability, not fallback.
 *
 * <pre>
 * java -jar jjq-benchmark/target/jjq-benchmark-*.jar JqDirectReaderStressBenchmark -prof gc
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(value = 3, jvmArgs = {"-Xmx2g", "-Xms2g"})
@State(Scope.Benchmark)
public class JqDirectReaderStressBenchmark {

    /** Number of distinct reader types sharing the dispatch site. */
    @Param({"1", "8", "32", "128"})
    public int activeReaders;

    /** Fields per record. */
    @Param({"8", "32", "64"})
    public int width;

    /** Uniform round-robin or one hot type plus uniform tail. */
    @Param({"uniform", "skewed"})
    public String traffic;

    private byte[][] documents;
    private Class<?>[] types;
    private int[] sequence;
    private int cursor;
    private JqMapper dispatchMapper;
    private JqMapper baselineMapper;

    /** Scalar cycle matching the eligible kinds (String/int/long/boolean/boxed). */
    private static String fieldType(int i) {
        return switch (i % 5) {
            case 0 -> "String";
            case 1 -> "int";
            case 2 -> "long";
            case 3 -> "boolean";
            default -> "Integer";
        };
    }

    private static String fieldValue(int i) {
        return switch (i % 5) {
            case 0 -> "\"v" + i + "\"";
            case 1, 4 -> Integer.toString(i);
            case 2 -> Long.toString(i * 1000L);
            default -> (i % 2 == 0) ? "true" : "false";
        };
    }

    private static String recordSource(String name, int width) {
        StringBuilder params = new StringBuilder();
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < width; i++) {
            if (i > 0) {
                params.append(", ");
                json.append(",");
            }
            params.append(fieldType(i)).append(" f").append(i);
            json.append("\"f").append(i).append("\":").append(fieldValue(i));
        }
        json.append("}");
        return "package stress;\npublic record " + name + "(" + params + ") {}\n// " + json;
    }

    private static String documentOf(String source) {
        int json = source.indexOf("// ") + 3;
        return source.substring(json).trim();
    }

    @Setup
    public void setup() throws Throwable {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("stress needs a JDK compiler");
        }
        List<JavaFileObject> sources = new ArrayList<>();
        Map<String, String> docs = new LinkedHashMap<>();
        for (int r = 0; r < activeReaders; r++) {
            String name = "Stress" + width + "_" + r;
            String source = recordSource(name, width);
            docs.put(name, documentOf(source));
            final String code = source.split("// ")[0];
            sources.add(new SimpleJavaFileObject(
                    java.net.URI.create("string:///stress/" + name + ".java"),
                    JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignore) {
                    return code;
                }
            });
        }
        var diagnostics = new javax.tools.DiagnosticCollector<JavaFileObject>();
        var task = compiler.getTask(null, null, diagnostics,
                List.of("-d", System.getProperty("java.io.tmpdir") + "/stress-classes",
                        "-classpath", System.getProperty("java.class.path")),
                null, sources);
        if (!task.call()) {
            for (var d : diagnostics.getDiagnostics()) System.err.println(d);
            throw new IllegalStateException("stress fixture compilation failed");
        }
        var loader = new java.net.URLClassLoader(
                new java.net.URL[]{new java.io.File(
                        System.getProperty("java.io.tmpdir") + "/stress-classes").toURI().toURL()},
                JqDirectReaderStressBenchmark.class.getClassLoader());

        documents = new byte[activeReaders][];
        types = new Class<?>[activeReaders];
        JqMapper.Builder builder = JqMapper.builder();
        JqMapper probe = JqMapper.builder().build();
        for (int r = 0; r < activeReaders; r++) {
            String simple = "Stress" + width + "_" + r;
            String name = "stress." + simple;
            Class<?> type = Class.forName(name, true, loader);
            BindingSchema schema = probe.bindingSchemaFor(type);
            if (!schema.eligibleForDirectReader()) {
                throw new IllegalStateException(name + " ineligible: "
                        + schema.ineligibilityReasons());
            }
            Map<String, byte[]> classes = new LinkedHashMap<>();
            ClassOutput output = classes::put;
            io.hyperfoil.tools.jjq.quarkus.deployment.JjqProcessor.generateDirectReader(
                    output, type, schema);
            var generated = classes.entrySet().iterator().next();
            // Same loader as the record: write the class file next to the
            // compiled fixtures so names resolve without lookup games.
            // (ClassOutput names are internal-form paths with .class.)
            String internal = generated.getKey();
            String resource = internal.endsWith(".class") ? internal : internal + ".class";
            java.nio.file.Path classFile = java.nio.file.Path.of(
                    System.getProperty("java.io.tmpdir") + "/stress-classes", resource);
            java.nio.file.Files.createDirectories(classFile.getParent());
            java.nio.file.Files.write(classFile, generated.getValue());
            String dotted = resource.substring(0, resource.length() - ".class".length())
                    .replace('/', '.');
            Class<?> readerClass = Class.forName(dotted, true, loader);
            @SuppressWarnings("unchecked")
            DirectReader<Object> reader = (DirectReader<Object>) readerClass
                    .getDeclaredConstructor().newInstance();
            builder.registerDirectReader(reader);
            documents[r] = docs.get(simple).getBytes(StandardCharsets.UTF_8);
            types[r] = type;
        }
        dispatchMapper = builder.build();
        baselineMapper = JqMapper.create();

        // Skewed: 90% type 0, tail uniform. Uniform: round-robin.
        sequence = new int[4096];
        for (int i = 0; i < sequence.length; i++) {
            sequence[i] = traffic.equals("skewed") && (i % 10 != 0) ? 0 : (i % activeReaders);
        }

        // Pin the route: every document must complete through its reader.
        for (int r = 0; r < activeReaders; r++) {
            Object bound = dispatchMapper.fromJsonStrict(documents[r], types[r]);
            if (bound == null || !bound.getClass().equals(types[r])) {
                throw new IllegalStateException("type " + r + " did not complete");
            }
        }
    }

    /** Shared dispatch site across all active readers. */
    @Benchmark
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object dispatchShared() throws Exception {
        int i = sequence[cursor++ & (sequence.length - 1)];
        return ((JqMapper) dispatchMapper).fromJsonStrict(documents[i], (Class) types[i]);
    }

    /** Baseline parse-plus-map for the same rotation (context, not the gate). */
    @Benchmark
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object baselineParseMap() throws Exception {
        int i = sequence[cursor++ & (sequence.length - 1)];
        return ((JqMapper) baselineMapper).fromJsonStrict(documents[i], (Class) types[i]);
    }
}

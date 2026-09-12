package io.hyperfoil.tools.jjq.mapper.processor;

import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.mapper.JqMapped;
import io.hyperfoil.tools.jjq.value.JqValues;
import io.hyperfoil.tools.jjq.value.JqValue;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JqMapperProcessorTest {

    /**
     * Compile a record source with the processor, load the generated mapping,
     * and verify it works end-to-end.
     */
    @Test
    void generatesMapping_simpleRecord() throws Exception {
        String source = """
                package test;
                
                import io.hyperfoil.tools.jjq.mapper.JqMapped;
                
                @JqMapped
                public record SimpleUser(String name, int age, boolean active) {}
                """;

        Class<?> recordClass = compileAndLoad("test.SimpleUser", source);
        Class<?> mappingClass = Class.forName("test.SimpleUser_JqMapping", true, recordClass.getClassLoader());

        assertNotNull(mappingClass);
        assertTrue(io.hyperfoil.tools.jjq.mapper.GeneratedMapping.class.isAssignableFrom(mappingClass));
    }

    @Test
    void generatedMapping_deserializes() throws Exception {
        String source = """
                package test;
                
                import io.hyperfoil.tools.jjq.mapper.JqMapped;
                
                @JqMapped
                public record Person(String name, int age) {}
                """;

        Class<?> recordClass = compileAndLoad("test.Person", source);

        // Use JqMapper which should discover the generated mapping
        JqMapper mapper = JqMapper.create();
        JqValue json = JqValues.parse("{\"name\":\"Alice\",\"age\":30}");

        Object result = mapper.fromJqValue(json, recordClass);
        assertNotNull(result);

        // Verify field values via reflection (since we can't cast to a compile-time type)
        var nameMethod = recordClass.getMethod("name");
        var ageMethod = recordClass.getMethod("age");
        assertEquals("Alice", nameMethod.invoke(result));
        assertEquals(30, ageMethod.invoke(result));
    }

    @Test
    void generatedMapping_serializes() throws Exception {
        String source = """
                package test;
                
                import io.hyperfoil.tools.jjq.mapper.JqMapped;
                
                @JqMapped
                public record Item(String name, double price) {}
                """;

        Class<?> recordClass = compileAndLoad("test.Item", source);

        // Create an instance via reflection
        var ctor = recordClass.getDeclaredConstructor(String.class, double.class);
        Object item = ctor.newInstance("Widget", 9.99);

        JqMapper mapper = JqMapper.create();
        JqValue json = mapper.toJqValue(item);

        assertEquals("Widget", json.getField("name").stringValue());
        assertEquals(9.99, json.getField("price").doubleValue(), 0.001);
    }

    @Test
    void generatedMapping_roundTrip() throws Exception {
        String source = """
                package test;
                
                import io.hyperfoil.tools.jjq.mapper.JqMapped;
                
                @JqMapped
                public record Config(String host, int port, boolean ssl) {}
                """;

        Class<?> recordClass = compileAndLoad("test.Config", source);
        JqMapper mapper = JqMapper.create();

        // Deserialize
        JqValue json = JqValues.parse("{\"host\":\"localhost\",\"port\":8080,\"ssl\":true}");
        Object config = mapper.fromJqValue(json, recordClass);

        // Serialize back
        JqValue serialized = mapper.toJqValue(config);
        assertEquals("localhost", serialized.getField("host").stringValue());
        assertEquals(8080L, serialized.getField("port").longValue());
        assertTrue(serialized.getField("ssl").booleanValue());

        // Deserialize again and compare
        Object config2 = mapper.fromJqValue(serialized, recordClass);
        assertEquals(config, config2);
    }

    @Test
    void pojoClassCompiles() throws Exception {
        String source = """
                package test;
                
                import io.hyperfoil.tools.jjq.mapper.JqMapped;
                
                @JqMapped
                public class SimplePojo {
                    public String name;
                    public int age;
                    
                    public SimplePojo() {}
                }
                """;

        // Should succeed — POJOs with @JqMapped are supported
        Path outDir = Files.createTempDirectory("jjq-proc-test");
        boolean success = compileSource("test.SimplePojo", source, outDir);
        assertTrue(success, "Compilation should succeed for @JqMapped on a class");
        deleteDir(outDir);
    }

    @Test
    void errorOnInterface() throws Exception {
        String source = """
                package test;
                
                import io.hyperfoil.tools.jjq.mapper.JqMapped;
                
                @JqMapped
                public interface NotAClassOrRecord {
                    String name();
                }
                """;

        // Should fail — interfaces are not supported
        Path outDir = Files.createTempDirectory("jjq-proc-test");
        boolean success = compileSource("test.NotAClassOrRecord", source, outDir);
        assertFalse(success, "Compilation should fail for @JqMapped on an interface");
        deleteDir(outDir);
    }

    @Test
    void generatedMapping_charTypes() throws Exception {
        String source = """
                package test;
                
                import io.hyperfoil.tools.jjq.mapper.JqMapped;
                
                @JqMapped
                public record CharRecord(String name, char initial, Character grade) {}
                """;

        Class<?> recordClass = compileAndLoad("test.CharRecord", source);
        JqMapper mapper = JqMapper.create();

        // Deserialize
        JqValue json = JqValues.parse("{\"name\":\"Alice\",\"initial\":\"A\",\"grade\":\"B\"}");
        Object r = mapper.fromJqValue(json, recordClass);
        assertEquals("Alice", recordClass.getMethod("name").invoke(r));
        assertEquals('A', recordClass.getMethod("initial").invoke(r));
        assertEquals('B', recordClass.getMethod("grade").invoke(r));

        // Serialize round-trip
        JqValue serialized = mapper.toJqValue(r);
        assertEquals("A", serialized.getField("initial").stringValue());
        assertEquals("B", serialized.getField("grade").stringValue());

        // Round-trip
        Object restored = mapper.fromJqValue(serialized, recordClass);
        assertEquals(r, restored);
    }

    @Test
    void generatedMapping_optionalScalars() throws Exception {
        String source = """
                package test;
                
                import io.hyperfoil.tools.jjq.mapper.JqMapped;
                import java.util.Optional;
                
                @JqMapped
                public record OptRecord(Optional<Integer> count, Optional<Double> ratio, Optional<Boolean> flag) {}
                """;

        Class<?> recordClass = compileAndLoad("test.OptRecord", source);
        JqMapper mapper = JqMapper.create();

        // Present values
        JqValue json = JqValues.parse("{\"count\":42,\"ratio\":3.14,\"flag\":true}");
        Object r = mapper.fromJqValue(json, recordClass);
        assertEquals(java.util.Optional.of(42), recordClass.getMethod("count").invoke(r));
        assertTrue(((java.util.Optional<?>) recordClass.getMethod("flag").invoke(r)).isPresent());

        // Empty values
        JqValue empty = JqValues.parse("{}");
        Object r2 = mapper.fromJqValue(empty, recordClass);
        assertEquals(java.util.Optional.empty(), recordClass.getMethod("count").invoke(r2));
    }

    @Test
    void generatedMapping_appendJson() throws Exception {
        String source = """
                package test;
                
                import io.hyperfoil.tools.jjq.mapper.JqMapped;
                
                @JqMapped
                public record Config(String host, int port, boolean ssl) {}
                """;

        Class<?> recordClass = compileAndLoad("test.Config", source);
        JqMapper mapper = JqMapper.create();

        // Create instance via reflection
        var ctor = recordClass.getDeclaredConstructor(String.class, int.class, boolean.class);
        Object config = ctor.newInstance("localhost", 8080, true);

        // toJson (uses appendJson internally) should match toJqValue().toJsonString()
        String viaToJson = mapper.toJson(config);
        String viaJqValue = mapper.toJqValue(config).toJsonString();
        assertEquals(viaJqValue, viaToJson,
                "Generated appendJson should produce same output as toJqValue path");
    }

    @Test
    void generatedMapping_appendJson_withStringsNeedingEscape() throws Exception {
        String source = """
                package test;
                
                import io.hyperfoil.tools.jjq.mapper.JqMapped;
                
                @JqMapped
                public record Message(String text, int code) {}
                """;

        Class<?> recordClass = compileAndLoad("test.Message", source);
        JqMapper mapper = JqMapper.create();

        var ctor = recordClass.getDeclaredConstructor(String.class, int.class);
        // String with characters that need JSON escaping
        Object msg = ctor.newInstance("hello \"world\"\nnewline\\backslash", 42);

        String viaToJson = mapper.toJson(msg);
        String viaJqValue = mapper.toJqValue(msg).toJsonString();
        assertEquals(viaJqValue, viaToJson);
        assertTrue(viaToJson.contains("\\\"world\\\""), "Should contain escaped quotes: " + viaToJson);
        assertTrue(viaToJson.contains("\\n"), "Should contain escaped newline: " + viaToJson);
    }

    @Test
    void generatedMapping_appendJson_doubleFormatting() throws Exception {
        String source = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                public record Doubles(double score, Double ratio, float factor, Float multiplier) {}
                """;

        Class<?> recordClass = compileAndLoad("test.Doubles", source);
        JqMapper mapper = JqMapper.create();

        var ctor = recordClass.getDeclaredConstructor(double.class, Double.class, float.class, Float.class);
        Object r = ctor.newInstance(3.0, 2.5, 1.5f, 0.5f);

        String viaToJson = mapper.toJson(r);
        String viaJqValue = mapper.toJqValue(r).toJsonString();
        assertEquals(viaJqValue, viaToJson);
    }

    @Test
    void generatedMapping_appendJson_bigDecimalFormatting() throws Exception {
        String source = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;
                import java.math.BigDecimal;

                @JqMapped
                public record Amounts(BigDecimal plain, BigDecimal sci, BigDecimal integral) {}
                """;

        Class<?> recordClass = compileAndLoad("test.Amounts", source);
        JqMapper mapper = JqMapper.create();

        var ctor = recordClass.getDeclaredConstructor(
                java.math.BigDecimal.class, java.math.BigDecimal.class, java.math.BigDecimal.class);
        Object r = ctor.newInstance(
                new java.math.BigDecimal("3.14"), new java.math.BigDecimal("1E+3"), new java.math.BigDecimal("100"));

        String viaToJson = mapper.toJson(r);
        String viaJqValue = mapper.toJqValue(r).toJsonString();
        assertEquals(viaJqValue, viaToJson);
    }

    @Test
    void generatedMapping_appendJsonBytes_matchesTree() throws Exception {
        String source = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                public record Config(String host, int port, boolean ssl) {}
                """;

        Class<?> recordClass = compileAndLoad("test.Config", source);
        JqMapper mapper = JqMapper.create();

        var ctor = recordClass.getDeclaredConstructor(String.class, int.class, boolean.class);
        Object config = ctor.newInstance("localhost", 8080, true);

        byte[] viaDirect = mapper.toJsonBytes(config);
        byte[] viaTree = io.hyperfoil.tools.jjq.value.JqValues.serializeToBytes(mapper.toJqValue(config));
        assertArrayEquals(viaTree, viaDirect);
    }

    @Test
    void generatedMapping_appendJsonBytes_doubleAndDecimal() throws Exception {
        String source = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;
                import java.math.BigDecimal;

                @JqMapped
                public record Metrics(double score, Double ratio, BigDecimal amount) {}
                """;

        Class<?> recordClass = compileAndLoad("test.Metrics", source);
        JqMapper mapper = JqMapper.create();

        var ctor = recordClass.getDeclaredConstructor(double.class, Double.class, java.math.BigDecimal.class);
        Object r = ctor.newInstance(3.0, 2.5, new java.math.BigDecimal("3.00"));

        byte[] viaDirect = mapper.toJsonBytes(r);
        byte[] viaTree = io.hyperfoil.tools.jjq.value.JqValues.serializeToBytes(mapper.toJqValue(r));
        assertArrayEquals(viaTree, viaDirect);
    }

    @Test
    void generatedMapping_appendJsonBytes_nullableBoxed() throws Exception {
        String source = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                public record Nulled(String name, Integer count, Long big, Boolean flag, Character grade) {}
                """;

        Class<?> recordClass = compileAndLoad("test.Nulled", source);
        JqMapper mapper = JqMapper.create();

        var ctor = recordClass.getDeclaredConstructor(
                String.class, Integer.class, Long.class, Boolean.class, Character.class);
        Object r = ctor.newInstance(null, null, null, null, null);

        byte[] viaDirect = mapper.toJsonBytes(r);
        byte[] viaTree = io.hyperfoil.tools.jjq.value.JqValues.serializeToBytes(mapper.toJqValue(r));
        assertArrayEquals(viaTree, viaDirect);
        assertEquals("{\"name\":null,\"count\":null,\"big\":null,\"flag\":null,\"grade\":null}",
                new String(viaDirect, java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void generatedMapping_appendJson_nullableBoxed() throws Exception {
        String source = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;
                import java.math.BigDecimal;

                @JqMapped
                public record Nullable(String name, Integer count, Long big, Double ratio,
                                       Float multiplier, Boolean flag, Short sh, Byte by,
                                       Character grade, BigDecimal amount) {}
                """;

        Class<?> recordClass = compileAndLoad("test.Nullable", source);
        JqMapper mapper = JqMapper.create();

        var ctor = recordClass.getDeclaredConstructor(String.class, Integer.class, Long.class,
                Double.class, Float.class, Boolean.class, Short.class, Byte.class,
                Character.class, java.math.BigDecimal.class);
        Object r = ctor.newInstance(null, null, null, null, null, null, null, null, null, null);

        // Must not throw — generated toJqValue must handle null boxed fields
        JqValue tree = mapper.toJqValue(r);
        for (String field : new String[]{"name", "count", "big", "ratio", "multiplier",
                "flag", "sh", "by", "grade", "amount"}) {
            assertTrue(tree.getField(field).isNull(), "Field " + field + " should be JSON null");
        }

        String viaToJson = mapper.toJson(r);
        String viaJqValue = tree.toJsonString();
        assertEquals(viaJqValue, viaToJson);
    }

    // ========================================================================
    //  Helpers
    // ========================================================================

    private Class<?> compileAndLoad(String className, String source) throws Exception {
        Path outDir = Files.createTempDirectory("jjq-proc-test");
        boolean success = compileSource(className, source, outDir);
        assertTrue(success, "Compilation failed");

        URLClassLoader loader = new URLClassLoader(
                new URL[]{outDir.toUri().toURL()},
                this.getClass().getClassLoader()
        );
        Class<?> cls = Class.forName(className, true, loader);
        return cls;
    }

    private boolean compileSource(String className, String source, Path outDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No Java compiler available — run tests with a JDK, not a JRE");
        }

        JavaFileObject sourceFile = new InMemorySource(className, source);

        // Build classpath from current classpath
        String classpath = System.getProperty("java.class.path");

        var diagnostics = new javax.tools.DiagnosticCollector<JavaFileObject>();
        var task = compiler.getTask(
                null, // default writer
                null, // default file manager
                diagnostics, // capture diagnostics
                List.of("-d", outDir.toString(), "-classpath", classpath),
                null, // no annotation classes to process
                List.of(sourceFile)
        );

        // Set the annotation processor explicitly
        task.setProcessors(List.of(new JqMapperProcessor()));

        boolean success = task.call();
        if (!success) {
            for (var d : diagnostics.getDiagnostics()) {
                System.err.println(d.getKind() + ": " + d.getMessage(null));
            }
        }
        return success;
    }

    private static class InMemorySource extends SimpleJavaFileObject {
        private final String source;

        InMemorySource(String className, String source) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    private static void deleteDir(Path dir) throws IOException {
        if (Files.exists(dir)) {
            Files.walk(dir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }
}

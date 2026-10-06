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
    void generatedMapping_pojoAbsentKeysKeepDefaults() throws Exception {
        String source = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                public class DefaultedPojo {
                    public String strategy = "SET";
                    public int retries = 3;

                    public DefaultedPojo() {}
                }
                """;

        Class<?> pojoClass = compileAndLoad("test.DefaultedPojo", source);
        JqMapper mapper = JqMapper.create();

        // Absent keys keep field initializers
        JqValue partial = JqValues.parse("{}");
        Object p = mapper.fromJqValue(partial, pojoClass);
        assertEquals("SET", pojoClass.getField("strategy").get(p));
        assertEquals(3, pojoClass.getField("retries").get(p));

        // Explicit null still writes null; present values still written
        JqValue withNull = JqValues.parse("{\"strategy\":null,\"retries\":7}");
        Object p2 = mapper.fromJqValue(withNull, pojoClass);
        assertNull(pojoClass.getField("strategy").get(p2));
        assertEquals(7, pojoClass.getField("retries").get(p2));
    }

    @Test
    void generatedMapping_arrayForStringHasPath() throws Exception {
        String source = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                public record WithType(String type) {}
                """;

        Class<?> cls = compileAndLoad("test.WithType", source);
        JqMapper mapper = JqMapper.create();

        JqValue bad = JqValues.parse("{\"type\":[\"kvm\"]}");
        io.hyperfoil.tools.jjq.mapper.JqMapperException e = assertThrows(
                io.hyperfoil.tools.jjq.mapper.JqMapperException.class,
                () -> mapper.fromJqValue(bad, cls));
        assertTrue(e.getMessage().contains("type"), e.getMessage());

        // Scalar coercion still works in generated code
        JqValue num = JqValues.parse("{\"type\":42}");
        Object ok = mapper.fromJqValue(num, cls);
        assertEquals("42", cls.getMethod("type").invoke(ok));
    }

    @Test
    void generatedMapping_shapeMismatchHasPath() throws Exception {
        String toolSource = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                public record Tool(String name) {}
                """;
        String bundleSource = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;
                import java.util.List;

                @JqMapped
                public record Bundle(String id, List<Tool> tools) {}
                """;

        // Compile both records in one round so each sees the other
        java.net.URLClassLoader loader = compileSources(
                new String[]{"test.Tool", "test.Bundle"},
                new String[]{toolSource, bundleSource});
        Class<?> bundleClass = Class.forName("test.Bundle", true, loader);
        JqMapper mapper = JqMapper.create();

        // Scalar element fails fast with the element path, not a corrupt list
        JqValue bad = JqValues.parse("{\"id\":\"b\",\"tools\":[{\"name\":\"ok\"},\"oops\"]}");
        io.hyperfoil.tools.jjq.mapper.JqMapperException e = assertThrows(
                io.hyperfoil.tools.jjq.mapper.JqMapperException.class,
                () -> mapper.fromJqValue(bad, bundleClass));
        String msg = e.getMessage();
        assertTrue(msg.contains("tools"), msg);
        assertTrue(msg.contains("[1]"), msg);
        assertTrue(e instanceof io.hyperfoil.tools.jjq.mapper.ShapeMismatchException);
        io.hyperfoil.tools.jjq.mapper.ShapeMismatchException se =
                (io.hyperfoil.tools.jjq.mapper.ShapeMismatchException) e;
        assertEquals(JqValue.Type.OBJECT, se.expected());
        assertEquals(JqValue.Type.STRING, se.actual());
        assertEquals("$.tools[1]", se.path());

        // Scalar for the list itself fails with the field path
        JqValue scalarList = JqValues.parse("{\"id\":\"b\",\"tools\":\"oops\"}");
        io.hyperfoil.tools.jjq.mapper.JqMapperException e2 = assertThrows(
                io.hyperfoil.tools.jjq.mapper.JqMapperException.class,
                () -> mapper.fromJqValue(scalarList, bundleClass));
        assertTrue(e2.getMessage().contains("tools"), e2.getMessage());

        // Well-shaped input still binds
        JqValue good = JqValues.parse("{\"id\":\"b\",\"tools\":[{\"name\":\"ok\"}]}");
        Object b = mapper.fromJqValue(good, bundleClass);
        assertEquals("b", bundleClass.getMethod("id").invoke(b));
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

    @Test
    void generatedMapping_nestedRecordRecursion() throws Exception {
        String addressSource = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                public record Address(String city, String zip) {}
                """;
        String personSource = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                public record Person(String name, Address address) {}
                """;

        // Compile both records in one round so each sees the other
        URLClassLoader loader = compileSources(
                new String[]{"test.Address", "test.Person"},
                new String[]{addressSource, personSource});
        Class<?> personClass = Class.forName("test.Person", true, loader);
        Class<?> addressClass = Class.forName("test.Address", true, loader);

        JqMapper mapper = JqMapper.create();

        Object address = addressClass.getDeclaredConstructor(String.class, String.class)
                .newInstance("NYC", "10001");
        Object person = personClass.getDeclaredConstructor(String.class, addressClass)
                .newInstance("Alice", address);

        // String path
        assertEquals(mapper.toJqValue(person).toJsonString(), mapper.toJson(person));
        // Bytes path
        assertArrayEquals(
                io.hyperfoil.tools.jjq.value.JqValues.serializeToBytes(mapper.toJqValue(person)),
                mapper.toJsonBytes(person));

        // Null nested record — both paths must render JSON null
        Object noAddress = personClass.getDeclaredConstructor(String.class, addressClass)
                .newInstance("Bob", null);
        assertEquals(mapper.toJqValue(noAddress).toJsonString(), mapper.toJson(noAddress));
        assertArrayEquals(
                io.hyperfoil.tools.jjq.value.JqValues.serializeToBytes(mapper.toJqValue(noAddress)),
                mapper.toJsonBytes(noAddress));
        assertTrue(mapper.toJson(noAddress).contains("\"address\":null"));
    }

    @Test
    void generatedMapping_threeLevelNesting() throws Exception {
        String l3Source = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                public record L3(String value) {}
                """;
        String l2Source = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                public record L2(String name, L3 inner) {}
                """;
        String l1Source = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                public record L1(String id, L2 nested) {}
                """;

        URLClassLoader loader = compileSources(
                new String[]{"test.L3", "test.L2", "test.L1"},
                new String[]{l3Source, l2Source, l1Source});
        Class<?> l1 = Class.forName("test.L1", true, loader);
        Class<?> l2 = Class.forName("test.L2", true, loader);
        Class<?> l3 = Class.forName("test.L3", true, loader);

        JqMapper mapper = JqMapper.create();
        Object leaf = l3.getDeclaredConstructor(String.class).newInstance("deep");
        Object mid = l2.getDeclaredConstructor(String.class, l3).newInstance("mid", leaf);
        Object root = l1.getDeclaredConstructor(String.class, l2).newInstance("root", mid);

        assertEquals(mapper.toJqValue(root).toJsonString(), mapper.toJson(root));
        assertArrayEquals(
                io.hyperfoil.tools.jjq.value.JqValues.serializeToBytes(mapper.toJqValue(root)),
                mapper.toJsonBytes(root));
    }

    @Test
    void generatedMapping_enumFieldUsesFallback() throws Exception {
        String source = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                public record WithStatus(String name, Status status) {
                    public enum Status { ACTIVE, INACTIVE }
                }
                """;

        Class<?> recordClass = compileAndLoad("test.WithStatus", source);
        Class<?> statusClass = Class.forName("test.WithStatus$Status", true,
                recordClass.getClassLoader());
        JqMapper mapper = JqMapper.create();

        Object active = statusClass.getEnumConstants()[0];
        Object r = recordClass.getDeclaredConstructor(String.class, statusClass)
                .newInstance("Alice", active);

        // Enum must serialize via TypeConverter (name string), not mapper recursion
        assertEquals(mapper.toJqValue(r).toJsonString(), mapper.toJson(r));
        assertTrue(mapper.toJson(r).contains("\"status\":\"ACTIVE\""));
        assertArrayEquals(
                io.hyperfoil.tools.jjq.value.JqValues.serializeToBytes(mapper.toJqValue(r)),
                mapper.toJsonBytes(r));
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

    /** Compile several sources in one round (so nested types see each other) and return a loader. */
    private URLClassLoader compileSources(String[] classNames, String[] sources) throws Exception {
        Path outDir = Files.createTempDirectory("jjq-proc-test");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No Java compiler available — run tests with a JDK, not a JRE");
        }
        var files = new java.util.ArrayList<JavaFileObject>();
        for (int i = 0; i < classNames.length; i++) {
            files.add(new InMemorySource(classNames[i], sources[i]));
        }
        String classpath = System.getProperty("java.class.path");
        var diagnostics = new javax.tools.DiagnosticCollector<JavaFileObject>();
        var task = compiler.getTask(null, null, diagnostics,
                List.of("-d", outDir.toString(), "-classpath", classpath), null, files);
        task.setProcessors(List.of(new JqMapperProcessor()));
        boolean success = task.call();
        if (!success) {
            for (var d : diagnostics.getDiagnostics()) {
                System.err.println(d.getKind() + ": " + d.getMessage(null));
            }
        }
        assertTrue(success, "Compilation failed");
        return new URLClassLoader(
                new URL[]{outDir.toUri().toURL()},
                this.getClass().getClassLoader());
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

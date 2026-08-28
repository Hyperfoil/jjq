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
    void errorOnNonRecord() throws Exception {
        String source = """
                package test;
                
                import io.hyperfoil.tools.jjq.mapper.JqMapped;
                
                @JqMapped
                public class NotARecord {
                    public String name;
                }
                """;

        // Should fail compilation with an error
        Path outDir = Files.createTempDirectory("jjq-proc-test");
        boolean success = compileSource("test.NotARecord", source, outDir);
        assertFalse(success, "Compilation should fail for @JqMapped on a non-record type");
        deleteDir(outDir);
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

        var task = compiler.getTask(
                null, // default writer
                null, // default file manager
                null, // default diagnostic listener
                List.of("-d", outDir.toString(), "-classpath", classpath),
                null, // no annotation classes to process
                List.of(sourceFile)
        );

        // Set the annotation processor explicitly
        task.setProcessors(List.of(new JqMapperProcessor()));

        return task.call();
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

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
    void generatedMapping_anySetterRoundTrip() throws Exception {
        String source = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqAnyGetter;
                import io.hyperfoil.tools.jjq.mapper.JqAnySetter;
                import io.hyperfoil.tools.jjq.mapper.JqMapped;
                import io.hyperfoil.tools.jjq.value.JqValue;
                import java.util.LinkedHashMap;
                import java.util.Map;

                @JqMapped
                public class ExtrasPojo {
                    public String name;
                    private final Map<String, JqValue> extras = new LinkedHashMap<>();

                    public ExtrasPojo() {}

                    @JqAnySetter
                    public void setExtra(String key, JqValue value) { extras.put(key, value); }

                    @JqAnyGetter
                    public Map<String, JqValue> getExtras() { return extras; }
                }
                """;

        Class<?> pojoClass = compileAndLoad("test.ExtrasPojo", source);
        JqMapper mapper = JqMapper.create();

        JqValue json = JqValues.parse("{\"name\":\"t\",\"mystery\":{\"a\":1}}");
        Object p = mapper.fromJqValue(json, pojoClass);
        assertEquals("t", pojoClass.getField("name").get(p));
        Object extras = pojoClass.getMethod("getExtras").invoke(p);
        assertEquals(1L, ((JqValue) ((java.util.Map<?, ?>) extras).get("mystery")).getField("a").longValue());

        // Round-trip through toJson (uses the appendJson fallback with any-getter)
        String out = mapper.toJson(p);
        assertTrue(out.contains("\"testProxyTool\"") || out.contains("\"mystery\""), out);
        // No double emission: the any-getter backing field is not a bean property (issue #88.1)
        assertFalse(out.contains("\"extras\""), out);
        Object p2 = mapper.fromJqValue(JqValues.parse(out), pojoClass);
        assertEquals("t", pojoClass.getField("name").get(p2));
    }

    @Test
    void generatedMapping_jsonValueEnum() throws Exception {
        // Proves the generated path honors @JsonValue/@JsonCreator via the
        // runtime TypeConverter (no codegen support needed)
        String source = """
                package test;

                import com.fasterxml.jackson.annotation.JsonCreator;
                import com.fasterxml.jackson.annotation.JsonValue;
                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                public record WithAuth(String name, AuthType auth) {
                    public enum AuthType {
                        API_KEY, OAUTH;

                        @JsonValue
                        public String toWire() {
                            return name().toLowerCase().replace('_', '-');
                        }

                        @JsonCreator
                        public static AuthType fromWire(String wire) {
                            for (AuthType t : values()) {
                                if (t.toWire().equals(wire)) return t;
                            }
                            throw new IllegalArgumentException("unknown: " + wire);
                        }
                    }
                }
                """;

        Class<?> cls = compileAndLoad("test.WithAuth", source);
        Class<?> authType = Class.forName("test.WithAuth$AuthType", true, cls.getClassLoader());
        // NOTE: generated mapping wins only with the processor; the Jackson bridge
        // must be registered explicitly here (no ServiceLoader in this configuration)
        JqMapper mapper = JqMapper.builder()
                .bridge(new io.hyperfoil.tools.jjq.mapper.jackson.JacksonAnnotationBridge())
                .build();

        Object apiKey = Enum.valueOf((Class<Enum>) authType, "API_KEY");
        Object instance = cls.getDeclaredConstructor(String.class, authType).newInstance("t", apiKey);
        assertEquals("api-key", mapper.toJqValue(instance).getField("auth").stringValue());

        JqValue back = JqValues.parse("{\"name\":\"t\",\"auth\":\"api-key\"}");
        Object restored = mapper.fromJqValue(back, cls);
        assertEquals(apiKey, cls.getMethod("auth").invoke(restored));
    }

    @Test
    void generatedMapping_accessWriteOnlyReadOnly() throws Exception {
        String source = """
                package test;

                import com.fasterxml.jackson.annotation.JsonProperty;
                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                public class AccessPojo {
                    public String name;
                    @JsonProperty(value = "secret", access = JsonProperty.Access.WRITE_ONLY)
                    public String secret;
                    @JsonProperty(value = "id", access = JsonProperty.Access.READ_ONLY)
                    public String id;

                    public AccessPojo() {}
                }
                """;

        Class<?> pojoClass = compileAndLoad("test.AccessPojo", source);
        JqMapper mapper = JqMapper.create();

        // WRITE_ONLY bound on deser, READ_ONLY left default
        JqValue json = JqValues.parse("{\"name\":\"t\",\"secret\":\"s\",\"id\":\"1\"}");
        Object p = mapper.fromJqValue(json, pojoClass);
        assertEquals("t", pojoClass.getField("name").get(p));
        assertEquals("s", pojoClass.getField("secret").get(p));
        assertNull(pojoClass.getField("id").get(p));

        // WRITE_ONLY skipped on ser; READ_ONLY (null here) still emitted as key
        Object p2 = pojoClass.getDeclaredConstructor().newInstance();
        pojoClass.getField("id").set(p2, "1");
        String out = mapper.toJqValue(p2).toJsonString();
        assertFalse(out.contains("secret"), out);
        assertTrue(out.contains("\"id\":\"1\""), out);
    }

    @Test
    void generatedMapping_autoDetectNoneReadsField() throws Exception {
        String source = """
                package test;

                import com.fasterxml.jackson.annotation.JsonAutoDetect;
                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
                        getterVisibility = JsonAutoDetect.Visibility.NONE,
                        isGetterVisibility = JsonAutoDetect.Visibility.NONE)
                public class FieldsOnly {
                    public boolean useVertex;

                    public FieldsOnly() {}

                    public boolean isUseVertex() {
                        throw new AssertionError("derived getter must not be bound");
                    }
                }
                """;

        Class<?> pojoClass = compileAndLoad("test.FieldsOnly", source);
        JqMapper mapper = JqMapper.create();

        JqValue json = JqValues.parse("{\"useVertex\":true}");
        Object p = mapper.fromJqValue(json, pojoClass);
        assertEquals(true, pojoClass.getField("useVertex").get(p));
        // Ser reads the stored field directly (no throw)
        assertTrue(mapper.toJqValue(p).toJsonString().contains("\"useVertex\":true"));
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

    @Test
    void generatedMapping_sameRoundConverterResolves() throws Exception {
        // Issue #100: converter compiled in the same round has no Class object yet —
        // reading annotation.value() directly throws MirroredTypeException.
        String converterSource = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.ValueConverter;
                import io.hyperfoil.tools.jjq.value.JqString;
                import io.hyperfoil.tools.jjq.value.JqValue;

                public class UpperConverter implements ValueConverter<String> {
                    @Override
                    public String fromJqValue(JqValue value) {
                        return value.isNull() ? null : value.stringValue().toUpperCase();
                    }

                    @Override
                    public JqValue toJqValue(String value) {
                        return value == null ? io.hyperfoil.tools.jjq.value.JqNull.NULL : JqString.of(value.toLowerCase());
                    }
                }
                """;
        String recordSource = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqConverter;
                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                public record WithConv(@JqConverter(UpperConverter.class) String name) {}
                """;

        URLClassLoader loader = compileSources(
                new String[]{"test.UpperConverter", "test.WithConv"},
                new String[]{converterSource, recordSource});
        Class<?> recordClass = Class.forName("test.WithConv", true, loader);
        JqMapper mapper = JqMapper.create();

        JqValue json = JqValues.parse("{\"name\":\"hello\"}");
        Object r = mapper.fromJqValue(json, recordClass);
        assertEquals("HELLO", recordClass.getMethod("name").invoke(r));

        JqValue back = mapper.toJqValue(r);
        assertEquals("hello", back.getField("name").stringValue());
    }

    @Test
    void generatedMapping_sameRoundConverterResolvesPojo() throws Exception {
        String converterSource = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.ValueConverter;
                import io.hyperfoil.tools.jjq.value.JqString;
                import io.hyperfoil.tools.jjq.value.JqValue;

                public class LowerConverter implements ValueConverter<String> {
                    @Override
                    public String fromJqValue(JqValue value) {
                        return value.isNull() ? null : value.stringValue().toLowerCase();
                    }

                    @Override
                    public JqValue toJqValue(String value) {
                        return value == null ? io.hyperfoil.tools.jjq.value.JqNull.NULL : JqString.of(value.toUpperCase());
                    }
                }
                """;
        String pojoSource = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqConverter;
                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                public class ConvPojo {
                    @JqConverter(LowerConverter.class)
                    public String name;

                    public ConvPojo() {}
                }
                """;

        URLClassLoader loader = compileSources(
                new String[]{"test.LowerConverter", "test.ConvPojo"},
                new String[]{converterSource, pojoSource});
        Class<?> pojoClass = Class.forName("test.ConvPojo", true, loader);
        JqMapper mapper = JqMapper.create();

        JqValue json = JqValues.parse("{\"name\":\"HELLO\"}");
        Object p = mapper.fromJqValue(json, pojoClass);
        assertEquals("hello", pojoClass.getField("name").get(p));
    }

    @Test
    void generatedMapping_genericCollectionsPojo() throws Exception {
        // Issue #101: generic fields emitted illegal `Map<K,V>.class` literals.
        // The POJO path had no List/Map branches at all (every generic hit the
        // `typeName.class` default); the record Map branch compiled but dropped
        // value types (null genericType -> untyped values).
        String toolSource = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                public record Tool(String name) {}
                """;
        String pojoSource = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;
                import java.util.List;
                import java.util.Map;

                @JqMapped
                public class Bundle {
                    public List<String> tags;
                    public Map<String, Tool> tools;

                    public Bundle() {}
                }
                """;

        URLClassLoader loader = compileSources(
                new String[]{"test.Tool", "test.Bundle"},
                new String[]{toolSource, pojoSource});
        Class<?> bundleClass = Class.forName("test.Bundle", true, loader);
        Class<?> toolClass = Class.forName("test.Tool", true, loader);
        JqMapper mapper = JqMapper.create();

        JqValue json = JqValues.parse(
                "{\"tags\":[\"a\",\"b\"],\"tools\":{\"hammer\":{\"name\":\"hammer\"}}}");
        Object b = mapper.fromJqValue(json, bundleClass);
        assertEquals(java.util.List.of("a", "b"), bundleClass.getField("tags").get(b));
        Object tools = bundleClass.getField("tools").get(b);
        assertInstanceOf(java.util.Map.class, tools);
        // Map values must bind as Tool records, not untyped maps (issue #101)
        Object hammer = ((java.util.Map<?, ?>) tools).get("hammer");
        assertEquals(toolClass, hammer.getClass());
        assertEquals("hammer", toolClass.getMethod("name").invoke(hammer));

        // Serialization round-trips through the same typed values
        JqValue back = mapper.toJqValue(b);
        assertEquals("a", back.getField("tags").getElement(0).stringValue());
        assertEquals("hammer", back.getField("tools").getField("hammer").getField("name").stringValue());
    }

    @Test
    void generatedMapping_genericMapRecordBindsTypedValues() throws Exception {
        String toolSource = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                public record Tool(String name) {}
                """;
        String bundleSource = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;
                import java.util.Map;

                @JqMapped
                public record MapBundle(String id, Map<String, Tool> tools) {}
                """;

        URLClassLoader loader = compileSources(
                new String[]{"test.Tool", "test.MapBundle"},
                new String[]{toolSource, bundleSource});
        Class<?> bundleClass = Class.forName("test.MapBundle", true, loader);
        Class<?> toolClass = Class.forName("test.Tool", true, loader);
        JqMapper mapper = JqMapper.create();

        JqValue json = JqValues.parse("{\"id\":\"b\",\"tools\":{\"hammer\":{\"name\":\"hammer\"}}}");
        Object b = mapper.fromJqValue(json, bundleClass);
        Object tools = bundleClass.getMethod("tools").invoke(b);
        Object hammer = ((java.util.Map<?, ?>) tools).get("hammer");
        assertEquals(toolClass, hammer.getClass());
        assertEquals("hammer", toolClass.getMethod("name").invoke(hammer));
    }

    @Test
    void generatedMapping_setFieldRoundTrip() throws Exception {
        // Issue #102: Set fields previously fell to the untyped DEFAULT
        // fallback (and the old codegen couldn't even name the type).
        // Generated mappings get runtime Set support for free via TypeToken.
        String source = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;
                import java.util.Set;

                @JqMapped
                public record WithTags(String name, Set<String> tags) {}
                """;

        Class<?> recordClass = compileAndLoad("test.WithTags", source);
        JqMapper mapper = JqMapper.create();

        JqValue json = JqValues.parse("{\"name\":\"t\",\"tags\":[\"b\",\"a\",\"b\"]}");
        Object r = mapper.fromJqValue(json, recordClass);
        assertEquals(java.util.Set.of("b", "a"), recordClass.getMethod("tags").invoke(r));

        JqValue back = mapper.toJqValue(r);
        assertInstanceOf(io.hyperfoil.tools.jjq.value.JqArray.class, back.getField("tags"));
        assertEquals(2, ((io.hyperfoil.tools.jjq.value.JqArray) back.getField("tags")).size());
        assertEquals(r, mapper.fromJqValue(back, recordClass));
    }

    @Test
    void generatedMapping_jsonPropertyRenameBinds() throws Exception {
        // Issue #103: codegen ignored bridge renames — the program and has()
        // guard used the Java name, so wire-named fields silently bound defaults.
        String source = """
                package test;

                import com.fasterxml.jackson.annotation.JsonProperty;
                import io.hyperfoil.tools.jjq.mapper.JqMapped;
                import java.util.List;

                @JqMapped
                public class RenamedPojo {
                    @JsonProperty("host-paths")
                    public List<String> hostPaths;

                    public RenamedPojo() {}
                }
                """;

        Class<?> pojoClass = compileAndLoad("test.RenamedPojo", source);
        JqMapper mapper = JqMapper.create();

        JqValue json = JqValues.parse("{\"host-paths\":[\"/a\",\"/b\"]}");
        Object p = mapper.fromJqValue(json, pojoClass);
        assertEquals(List.of("/a", "/b"), pojoClass.getField("hostPaths").get(p));

        // Serialization emits the wire name, not the Java name
        String out = mapper.toJqValue(p).toJsonString();
        assertTrue(out.contains("\"host-paths\""), out);
        assertFalse(out.contains("hostPaths"), out);

        Object roundTripped = mapper.fromJqValue(JqValues.parse(out), pojoClass);
        assertEquals(List.of("/a", "/b"), pojoClass.getField("hostPaths").get(roundTripped));
    }

    @Test
    void generatedMapping_jsonPropertyRenameBindsRecord() throws Exception {
        String source = """
                package test;

                import com.fasterxml.jackson.annotation.JsonProperty;
                import io.hyperfoil.tools.jjq.mapper.JqMapped;
                import java.util.List;

                @JqMapped
                public record RenamedRecord(@JsonProperty("host-paths") List<String> hostPaths) {}
                """;

        Class<?> recordClass = compileAndLoad("test.RenamedRecord", source);
        JqMapper mapper = JqMapper.create();

        JqValue json = JqValues.parse("{\"host-paths\":[\"/a\"]}");
        Object r = mapper.fromJqValue(json, recordClass);
        assertEquals(List.of("/a"), recordClass.getMethod("hostPaths").invoke(r));

        String out = mapper.toJqValue(r).toJsonString();
        assertTrue(out.contains("\"host-paths\""), out);
        assertFalse(out.contains("hostPaths"), out);
    }

    @Test
    void generatedMapping_jsonbPropertyRenameBinds() throws Exception {
        String source = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;
                import jakarta.json.bind.annotation.JsonbProperty;

                @JqMapped
                public class JsonbRenamed {
                    @JsonbProperty("repo-paths")
                    public java.util.List<String> repoPaths;

                    public JsonbRenamed() {}
                }
                """;

        Class<?> pojoClass = compileAndLoad("test.JsonbRenamed", source);
        JqMapper mapper = JqMapper.create();

        JqValue json = JqValues.parse("{\"repo-paths\":[\"/r\"]}");
        Object p = mapper.fromJqValue(json, pojoClass);
        assertEquals(List.of("/r"), pojoClass.getField("repoPaths").get(p));

        String out = mapper.toJqValue(p).toJsonString();
        assertTrue(out.contains("\"repo-paths\""), out);
        assertFalse(out.contains("repoPaths"), out);
    }

    @Test
    void generatedMapping_getterSuppressedStillSerializes() throws Exception {
        // Issue #104: with getters suppressed the generated serializers emitted
        // empty containers, while deser (via setters) worked. Mirrors the
        // reflection fallback ladder: suppressed/absent getter -> field read.
        String source = """
                package test;

                import com.fasterxml.jackson.annotation.JsonAutoDetect;
                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
                        getterVisibility = JsonAutoDetect.Visibility.NONE,
                        isGetterVisibility = JsonAutoDetect.Visibility.NONE)
                public class SuppressedPojo {
                    private String endpoint;
                    private int retries;
                    private boolean enabled;

                    public SuppressedPojo() {}

                    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
                    public void setRetries(int retries) { this.retries = retries; }
                    public void setEnabled(boolean enabled) { this.enabled = enabled; }
                }
                """;

        Class<?> pojoClass = compileAndLoad("test.SuppressedPojo", source);
        JqMapper mapper = JqMapper.create();

        var endpointField = pojoClass.getDeclaredField("endpoint");
        endpointField.setAccessible(true);
        JqValue json = JqValues.parse("{\"endpoint\":\"https://x\",\"retries\":3,\"enabled\":true}");
        Object p = mapper.fromJqValue(json, pojoClass);
        assertEquals("https://x", endpointField.get(p));

        // Serialization must emit every field, not an empty container
        String out = mapper.toJqValue(p).toJsonString();
        assertTrue(out.contains("\"endpoint\":\"https://x\""), out);
        assertTrue(out.contains("\"retries\":3"), out);
        assertTrue(out.contains("\"enabled\":true"), out);

        Object roundTripped = mapper.fromJqValue(JqValues.parse(out), pojoClass);
        assertEquals("https://x", endpointField.get(roundTripped));
    }

    @Test
    void generatedMapping_privateFieldWithoutSetterBindsBothWays() throws Exception {
        // No getter and no setter: the runtime reads/writes via setAccessible;
        // generated code must use the same field fallback on both directions.
        String source = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                public class NoAccessors {
                    private String secret = "default";

                    public NoAccessors() {}
                }
                """;

        Class<?> pojoClass = compileAndLoad("test.NoAccessors", source);
        JqMapper mapper = JqMapper.create();

        var secretField = pojoClass.getDeclaredField("secret");
        secretField.setAccessible(true);
        JqValue json = JqValues.parse("{\"secret\":\"s3cr3t\"}");
        Object p = mapper.fromJqValue(json, pojoClass);
        assertEquals("s3cr3t", secretField.get(p));

        String out = mapper.toJqValue(p).toJsonString();
        assertTrue(out.contains("\"secret\":\"s3cr3t\""), out);
    }

    @Test
    void generatedMapping_bridgeAnyGetterSuppressesBackingField() throws Exception {
        // Issue #105: bridge method-form @JsonAnyGetter/@JsonAnySetter were
        // invisible to the processor — the any-getter was hijacked as a bean
        // getter (double emission) and the write fallback referenced an
        // undeclared F_ constant (fatal). Mirrors the reflection path (#87/#88.1).
        String source = """
                package test;

                import com.fasterxml.jackson.annotation.JsonAnyGetter;
                import com.fasterxml.jackson.annotation.JsonAnySetter;
                import io.hyperfoil.tools.jjq.mapper.JqMapped;
                import io.hyperfoil.tools.jjq.value.JqValue;
                import java.util.LinkedHashMap;
                import java.util.Map;

                @JqMapped
                public class BridgeExtras {
                    public String name;
                    private Map<String, JqValue> extras = new LinkedHashMap<>();

                    public BridgeExtras() {}

                    @JsonAnySetter
                    public void setExtra(String key, JqValue value) { extras.put(key, value); }

                    @JsonAnyGetter
                    public Map<String, JqValue> getExtras() { return extras; }
                }
                """;

        Class<?> pojoClass = compileAndLoad("test.BridgeExtras", source);
        JqMapper mapper = JqMapper.create();

        JqValue json = JqValues.parse("{\"name\":\"t\",\"mystery\":{\"a\":1}}");
        Object p = mapper.fromJqValue(json, pojoClass);
        assertEquals("t", pojoClass.getField("name").get(p));

        // Flattened entries present, backing field never emitted (no double emission)
        String out = mapper.toJson(p);
        assertTrue(out.contains("\"mystery\""), out);
        assertFalse(out.contains("\"extras\""), out);

        Object roundTripped = mapper.fromJqValue(JqValues.parse(out), pojoClass);
        assertEquals("t", pojoClass.getField("name").get(roundTripped));
    }

    @Test
    void generatedMapping_bridgeAnyGetterSuppressesRecordComponent() throws Exception {
        String source = """
                package test;

                import com.fasterxml.jackson.annotation.JsonAnyGetter;
                import io.hyperfoil.tools.jjq.mapper.JqMapped;
                import io.hyperfoil.tools.jjq.value.JqValue;
                import java.util.Map;

                @JqMapped
                public record BridgeRec(String name, Map<String, JqValue> extras) {
                    @JsonAnyGetter
                    public Map<String, JqValue> getExtras() { return extras; }
                }
                """;

        Class<?> recordClass = compileAndLoad("test.BridgeRec", source);
        JqMapper mapper = JqMapper.create();

        JqValue json = JqValues.parse("{\"name\":\"t\",\"k\":1}");
        Object r = mapper.fromJqValue(json, recordClass);
        assertEquals("t", recordClass.getMethod("name").invoke(r));

        String out = mapper.toJqValue(r).toJsonString();
        assertTrue(out.contains("\"name\":\"t\""), out);
        assertFalse(out.contains("\"extras\""), out);
    }

    @Test
    void generatedMapping_objectFieldBindsPlainValues() throws Exception {
        // Issue #106: Object fields were bound via mapper introspection of
        // java.lang.Object (module-closure fatal). The reflection path uses
        // toJavaObject() — generated code must do the same.
        String source = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                public class UntypedPojo {
                    public Object artifactCache;

                    public UntypedPojo() {}
                }
                """;

        Class<?> pojoClass = compileAndLoad("test.UntypedPojo", source);
        JqMapper mapper = JqMapper.create();

        JqValue json = JqValues.parse("{\"artifactCache\":{\"size\":10,\"tags\":[\"a\"]}}");
        Object p = mapper.fromJqValue(json, pojoClass);
        Object cache = pojoClass.getField("artifactCache").get(p);
        assertInstanceOf(java.util.Map.class, cache);
        assertEquals(10L, ((java.util.Map<?, ?>) cache).get("size"));

        // Round-trips verbatim
        JqValue back = mapper.toJqValue(p);
        assertEquals(10L, back.getField("artifactCache").getField("size").longValue());
        Object roundTripped = mapper.fromJqValue(back, pojoClass);
        assertEquals(cache, pojoClass.getField("artifactCache").get(roundTripped));
    }

    @Test
    void generatedMapping_objectFieldBindsPlainValuesRecord() throws Exception {
        String source = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                public record UntypedRecord(String name, Object payload) {}
                """;

        Class<?> recordClass = compileAndLoad("test.UntypedRecord", source);
        JqMapper mapper = JqMapper.create();

        JqValue json = JqValues.parse("{\"name\":\"t\",\"payload\":{\"k\":[1,2]}}");
        Object r = mapper.fromJqValue(json, recordClass);
        assertEquals("t", recordClass.getMethod("name").invoke(r));
        Object payload = recordClass.getMethod("payload").invoke(r);
        assertInstanceOf(java.util.Map.class, payload);

        JqValue back = mapper.toJqValue(r);
        assertEquals("t", back.getField("name").stringValue());
        assertInstanceOf(io.hyperfoil.tools.jjq.value.JqObject.class,
                back.getField("payload"));
    }

    @Test
    void generatedMapping_inheritedAnySetterForwards() throws Exception {
        // Issue #107.1: the runtime finds any-methods via getMethods()
        // (inherited included); the processor only scanned declared methods,
        // so an inherited any-setter silently dropped unknown keys.
        String baseSource = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqAnyGetter;
                import io.hyperfoil.tools.jjq.mapper.JqAnySetter;
                import io.hyperfoil.tools.jjq.value.JqValue;
                import java.util.LinkedHashMap;
                import java.util.Map;

                public class ExtrasBase {
                    private final Map<String, JqValue> extras = new LinkedHashMap<>();

                    @JqAnySetter
                    public void setExtra(String key, JqValue value) { extras.put(key, value); }

                    @JqAnyGetter
                    public Map<String, JqValue> getExtras() { return extras; }
                }
                """;
        String subSource = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                public class ChildPojo extends ExtrasBase {
                    public String name;

                    public ChildPojo() {}
                }
                """;

        URLClassLoader loader = compileSources(
                new String[]{"test.ExtrasBase", "test.ChildPojo"},
                new String[]{baseSource, subSource});
        Class<?> pojoClass = Class.forName("test.ChildPojo", true, loader);
        JqMapper mapper = JqMapper.create();

        JqValue json = JqValues.parse("{\"name\":\"t\",\"mystery\":{\"a\":1}}");
        Object p = mapper.fromJqValue(json, pojoClass);
        assertEquals("t", pojoClass.getField("name").get(p));

        String out = mapper.toJson(p);
        assertTrue(out.contains("\"mystery\""), out);
        assertFalse(out.contains("\"extras\""), out);
    }

    @Test
    void generatedMapping_inheritedBridgeAnyMethods() throws Exception {
        // The exact isx shape: Jackson method-form marks inherited from a base.
        String baseSource = """
                package test;

                import com.fasterxml.jackson.annotation.JsonAnyGetter;
                import com.fasterxml.jackson.annotation.JsonAnySetter;
                import io.hyperfoil.tools.jjq.value.JqValue;
                import java.util.LinkedHashMap;
                import java.util.Map;

                public class JacksonExtrasBase {
                    private final Map<String, JqValue> extras = new LinkedHashMap<>();

                    @JsonAnySetter
                    public void setExtra(String key, JqValue value) { extras.put(key, value); }

                    @JsonAnyGetter
                    public Map<String, JqValue> getExtras() { return extras; }
                }
                """;
        String subSource = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                public class JacksonChild extends JacksonExtrasBase {
                    public String name;

                    public JacksonChild() {}
                }
                """;

        URLClassLoader loader = compileSources(
                new String[]{"test.JacksonExtrasBase", "test.JacksonChild"},
                new String[]{baseSource, subSource});
        Class<?> pojoClass = Class.forName("test.JacksonChild", true, loader);
        JqMapper mapper = JqMapper.create();

        JqValue json = JqValues.parse("{\"name\":\"t\",\"mystery\":{\"a\":1}}");
        Object p = mapper.fromJqValue(json, pojoClass);
        assertEquals("t", pojoClass.getField("name").get(p));

        String out = mapper.toJson(p);
        assertTrue(out.contains("\"mystery\""), out);
        assertFalse(out.contains("\"extras\""), out);
    }

    @Test
    void generatedMapping_jsonIncludeNonEmptyOmits() throws Exception {
        // Issue #107.2: bridged @JsonInclude was never resolved, so all three
        // serializers emitted every field unconditionally.
        String source = """
                package test;

                import com.fasterxml.jackson.annotation.JsonInclude;
                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                public class IncludePojo {
                    public String name;
                    @JsonInclude(JsonInclude.Include.NON_EMPTY)
                    public String email;
                    @JsonInclude(JsonInclude.Include.NON_NULL)
                    public String phone;

                    public IncludePojo() {}
                }
                """;

        Class<?> pojoClass = compileAndLoad("test.IncludePojo", source);
        JqMapper mapper = JqMapper.create();

        Object p = pojoClass.getDeclaredConstructor().newInstance();
        pojoClass.getField("name").set(p, "t");
        pojoClass.getField("email").set(p, "");
        // phone stays null

        String treeOut = mapper.toJqValue(p).toJsonString();
        assertTrue(treeOut.contains("\"name\":\"t\""), treeOut);
        assertFalse(treeOut.contains("email"), treeOut);
        assertFalse(treeOut.contains("phone"), treeOut);

        String jsonOut = mapper.toJson(p);
        assertFalse(jsonOut.contains("email"), jsonOut);
        assertFalse(jsonOut.contains("phone"), jsonOut);

        String bytesOut = new String(mapper.toJsonBytes(p), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(bytesOut.contains("email"), bytesOut);
        assertFalse(bytesOut.contains("phone"), bytesOut);
    }

    @Test
    void generatedMapping_jsonIncludeNonNullOmitsRecord() throws Exception {
        String source = """
                package test;

                import com.fasterxml.jackson.annotation.JsonInclude;
                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                public record IncludeRecord(String name,
                        @JsonInclude(JsonInclude.Include.NON_NULL) String nick) {}
                """;

        Class<?> recordClass = compileAndLoad("test.IncludeRecord", source);
        JqMapper mapper = JqMapper.create();

        Object r = recordClass.getDeclaredConstructor(String.class, String.class)
                .newInstance("t", null);
        String out = mapper.toJqValue(r).toJsonString();
        assertTrue(out.contains("\"name\":\"t\""), out);
        assertFalse(out.contains("nick"), out);
        assertEquals(out, mapper.toJson(r));
    }

    @Test
    void generatedMapping_nonEmptyPrivateFieldCompiles() throws Exception {
        // Issue #108: NON_EMPTY guards emitted `!cast-expr.isEmpty()` — the `!`
        // negated the cast instead of the test. Only triggers when the read
        // expression is a cast (private field reflection fallback).
        String source = """
                package test;

                import com.fasterxml.jackson.annotation.JsonInclude;
                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                public class NonEmptyPrivate {
                    @JsonInclude(JsonInclude.Include.NON_EMPTY)
                    private String apiKey;

                    public NonEmptyPrivate() {}

                    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
                }
                """;

        Class<?> pojoClass = compileAndLoad("test.NonEmptyPrivate", source);
        JqMapper mapper = JqMapper.create();

        JqValue json = JqValues.parse("{\"apiKey\":\"k123\"}");
        Object p = mapper.fromJqValue(json, pojoClass);
        var field = pojoClass.getDeclaredField("apiKey");
        field.setAccessible(true);
        assertEquals("k123", field.get(p));

        String out = mapper.toJqValue(p).toJsonString();
        assertTrue(out.contains("\"apiKey\":\"k123\""), out);

        Object empty = pojoClass.getDeclaredConstructor().newInstance();
        field.set(empty, "");
        assertEquals("{}", mapper.toJqValue(empty).toJsonString());
    }

    @Test
    void generatedMapping_primitiveInclusionGuards() throws Exception {
        // Issue #109: guards compared primitives to null (uncompilable); parity
        // with shouldInclude means primitives are unconditional under
        // NON_NULL/NON_EMPTY (boxed values are never null/empty).
        String source = """
                package test;

                import com.fasterxml.jackson.annotation.JsonInclude;
                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                @JsonInclude(JsonInclude.Include.NON_EMPTY)
                public class PrimitiveInclusion {
                    public boolean licenseConsent;
                    public int retries;
                    public double ratio;
                    public String name;

                    public PrimitiveInclusion() {}
                }
                """;

        Class<?> pojoClass = compileAndLoad("test.PrimitiveInclusion", source);
        JqMapper mapper = JqMapper.create();

        Object p = pojoClass.getDeclaredConstructor().newInstance();
        pojoClass.getField("name").set(p, "t");
        String out = mapper.toJqValue(p).toJsonString();
        assertTrue(out.contains("\"name\":\"t\""), out);
        assertTrue(out.contains("\"licenseConsent\":false"), out);
        assertTrue(out.contains("\"retries\":0"), out);

        Object on = pojoClass.getDeclaredConstructor().newInstance();
        pojoClass.getField("licenseConsent").setBoolean(on, true);
        pojoClass.getField("retries").setInt(on, 3);
        pojoClass.getField("name").set(on, "t");
        String onTree = mapper.toJqValue(on).toJsonString();
        assertTrue(onTree.contains("\"licenseConsent\":true"), onTree);
        assertTrue(onTree.contains("\"retries\":3"), onTree);
        assertEquals(onTree, mapper.toJson(on));
        assertEquals(onTree, new String(mapper.toJsonBytes(on),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void generatedMapping_primitiveInclusionGuardsRecord() throws Exception {
        String source = """
                package test;

                import com.fasterxml.jackson.annotation.JsonInclude;
                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                @JsonInclude(JsonInclude.Include.NON_EMPTY)
                public record PrimRec(boolean flag, int count, String name) {}
                """;

        Class<?> recordClass = compileAndLoad("test.PrimRec", source);
        JqMapper mapper = JqMapper.create();

        Object r = recordClass.getDeclaredConstructor(boolean.class, int.class, String.class)
                .newInstance(false, 0, "t");
        String out = mapper.toJqValue(r).toJsonString();
        assertTrue(out.contains("\"name\":\"t\""), out);
        assertTrue(out.contains("\"flag\":false"), out);
        assertTrue(out.contains("\"count\":0"), out);
        assertEquals(out, mapper.toJson(r));
    }

    @Test
    void converterWithoutNoArgCtor_failsWithNamedError() {
        // Issue #110: a converter that cannot be constructed must fail the
        // build with a named error, not a raw javac error in generated code.
        javax.tools.JavaFileObject converter = com.google.testing.compile.JavaFileObjects
                .forSourceString("test.ArgConverter", """
                        package test;

                        import io.hyperfoil.tools.jjq.mapper.ValueConverter;
                        import io.hyperfoil.tools.jjq.value.JqString;
                        import io.hyperfoil.tools.jjq.value.JqValue;

                        public class ArgConverter implements ValueConverter<String> {
                            public ArgConverter(String arg) {}

                            @Override
                            public String fromJqValue(JqValue value) { return "x"; }

                            @Override
                            public JqValue toJqValue(String value) { return JqString.of(value); }
                        }
                        """);
        javax.tools.JavaFileObject record = com.google.testing.compile.JavaFileObjects
                .forSourceString("test.WithArgConv", """
                        package test;

                        import io.hyperfoil.tools.jjq.mapper.JqConverter;
                        import io.hyperfoil.tools.jjq.mapper.JqMapped;

                        @JqMapped
                        public record WithArgConv(@JqConverter(ArgConverter.class) String name) {}
                        """);
        com.google.testing.compile.Compilation compilation = com.google.testing.compile.Compiler.javac()
                .withProcessors(new JqMapperProcessor())
                .compile(converter, record);
        com.google.testing.compile.CompilationSubject.assertThat(compilation).failed();
        com.google.testing.compile.CompilationSubject.assertThat(compilation)
                .hadErrorContaining(
                    "Converter test.ArgConverter must have an accessible no-arg constructor");
    }

    @Test
    void abstractConverter_failsWithNamedError() {
        javax.tools.JavaFileObject converter = com.google.testing.compile.JavaFileObjects
                .forSourceString("test.AbstractConverter", """
                        package test;

                        import io.hyperfoil.tools.jjq.mapper.ValueConverter;
                        import io.hyperfoil.tools.jjq.value.JqValue;

                        public abstract class AbstractConverter implements ValueConverter<String> {
                            @Override
                            public String fromJqValue(JqValue value) { return "x"; }
                        }
                        """);
        javax.tools.JavaFileObject record = com.google.testing.compile.JavaFileObjects
                .forSourceString("test.WithAbstractConv", """
                        package test;

                        import io.hyperfoil.tools.jjq.mapper.JqConverter;
                        import io.hyperfoil.tools.jjq.mapper.JqMapped;

                        @JqMapped
                        public record WithAbstractConv(@JqConverter(AbstractConverter.class) String name) {}
                        """);
        com.google.testing.compile.Compilation compilation = com.google.testing.compile.Compiler.javac()
                .withProcessors(new JqMapperProcessor())
                .compile(converter, record);
        com.google.testing.compile.CompilationSubject.assertThat(compilation).failed();
        com.google.testing.compile.CompilationSubject.assertThat(compilation)
                .hadErrorContaining("Converter test.AbstractConverter must be a concrete class");
    }

    @Test
    void generatedMapping_jqNameBinds() throws Exception {
        // Issue #111: native wire names end to end (no bridge needed).
        String source = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;
                import io.hyperfoil.tools.jjq.mapper.JqName;
                import java.util.List;

                @JqMapped
                public record NamedRecord(@JqName("host-paths") List<String> hostPaths, String name) {}
                """;

        Class<?> recordClass = compileAndLoad("test.NamedRecord", source);
        JqMapper mapper = JqMapper.create();

        JqValue json = JqValues.parse("{\"host-paths\":[\"/a\"],\"name\":\"t\"}");
        Object r = mapper.fromJqValue(json, recordClass);
        assertEquals(List.of("/a"), recordClass.getMethod("hostPaths").invoke(r));

        String out = mapper.toJqValue(r).toJsonString();
        assertTrue(out.contains("\"host-paths\":[\"/a\"]"), out);
        assertFalse(out.contains("hostPaths"), out);

        Object roundTripped = mapper.fromJqValue(JqValues.parse(out), recordClass);
        assertEquals(r, roundTripped);
    }

    @Test
    void generatedMapping_jqNameBindsPojo() throws Exception {
        String source = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;
                import io.hyperfoil.tools.jjq.mapper.JqName;
                import java.util.List;

                @JqMapped
                public class NamedPojo {
                    @JqName("host-paths")
                    public List<String> hostPaths;

                    public NamedPojo() {}
                }
                """;

        Class<?> pojoClass = compileAndLoad("test.NamedPojo", source);
        JqMapper mapper = JqMapper.create();

        JqValue json = JqValues.parse("{\"host-paths\":[\"/a\",\"/b\"]}");
        Object p = mapper.fromJqValue(json, pojoClass);
        assertEquals(List.of("/a", "/b"), pojoClass.getField("hostPaths").get(p));

        String out = mapper.toJqValue(p).toJsonString();
        assertTrue(out.contains("\"host-paths\""), out);
        assertFalse(out.contains("hostPaths"), out);
    }

    @Test
    void generatedMapping_jqNameConflictsWithJqField() throws Exception {
        javax.tools.JavaFileObject source = com.google.testing.compile.JavaFileObjects
                .forSourceString("test.Conflicting", """
                        package test;

                        import io.hyperfoil.tools.jjq.mapper.JqField;
                        import io.hyperfoil.tools.jjq.mapper.JqMapped;
                        import io.hyperfoil.tools.jjq.mapper.JqName;

                        @JqMapped
                        public record Conflicting(@JqField(".custom") @JqName("other") String name) {}
                        """);
        com.google.testing.compile.Compilation compilation = com.google.testing.compile.Compiler.javac()
                .withProcessors(new JqMapperProcessor())
                .compile(source);
        com.google.testing.compile.CompilationSubject.assertThat(compilation).failed();
        com.google.testing.compile.CompilationSubject.assertThat(compilation)
                .hadErrorContaining("@JqName and @JqField conflict");
    }

    @Test
    void generatedMapping_jqNameEnumConstants() throws Exception {
        // Issue #112: enum fields delegate to the runtime wire-name lookup,
        // so no codegen change is needed — this pins that end to end.
        String source = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqMapped;
                import io.hyperfoil.tools.jjq.mapper.JqName;

                @JqMapped
                public record WithAccount(String name, AccountType account) {
                    public enum AccountType {
                        @JqName("api-key") API_KEY,
                        OAUTH
                    }
                }
                """;

        Class<?> recordClass = compileAndLoad("test.WithAccount", source);
        Class<?> accountType = Class.forName("test.WithAccount$AccountType", true,
                recordClass.getClassLoader());
        JqMapper mapper = JqMapper.create();

        @SuppressWarnings({"unchecked", "rawtypes"})
        Object apiKey = Enum.valueOf((Class<Enum>) accountType, "API_KEY");
        Object r = recordClass.getDeclaredConstructor(String.class, accountType)
                .newInstance("t", apiKey);
        String out = mapper.toJqValue(r).toJsonString();
        assertTrue(out.contains("\"account\":\"api-key\""), out);

        Object back = mapper.fromJqValue(JqValues.parse("{\"name\":\"t\",\"account\":\"api-key\"}"),
                recordClass);
        assertEquals(apiKey, recordClass.getMethod("account").invoke(back));
    }

    @Test
    void generatedMapping_writeOnlySkipsSerialization() throws Exception {
        // Issue #113: native direction control end to end.
        String source = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqAccess;
                import io.hyperfoil.tools.jjq.mapper.JqMapped;
                import io.hyperfoil.tools.jjq.mapper.JqName;

                @JqMapped
                public record LegacyRecord(
                        @JqAccess(JqAccess.Access.WRITE_ONLY) @JqName("host-path") String hostPath,
                        String name) {}
                """;

        Class<?> recordClass = compileAndLoad("test.LegacyRecord", source);
        JqMapper mapper = JqMapper.create();

        JqValue json = JqValues.parse("{\"host-path\":\"/old\",\"name\":\"t\"}");
        Object r = mapper.fromJqValue(json, recordClass);
        assertEquals("/old", recordClass.getMethod("hostPath").invoke(r));

        String out = mapper.toJqValue(r).toJsonString();
        assertFalse(out.contains("host-path"), out);
        assertTrue(out.contains("\"name\":\"t\""), out);
        assertEquals(out, mapper.toJson(r));
    }

    @Test
    void generatedMapping_readOnlySkipsDeserialization() throws Exception {
        String source = """
                package test;

                import io.hyperfoil.tools.jjq.mapper.JqAccess;
                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                public class ReadOnlyPojo {
                    @JqAccess(JqAccess.Access.READ_ONLY)
                    public String computed;
                    public String name;

                    public ReadOnlyPojo() {}
                }
                """;

        Class<?> pojoClass = compileAndLoad("test.ReadOnlyPojo", source);
        JqMapper mapper = JqMapper.create();

        JqValue json = JqValues.parse("{\"computed\":\"x\",\"name\":\"t\"}");
        Object p = mapper.fromJqValue(json, pojoClass);
        assertNull(pojoClass.getField("computed").get(p));
        assertEquals("t", pojoClass.getField("name").get(p));

        pojoClass.getField("computed").set(p, "c");
        String out = mapper.toJqValue(p).toJsonString();
        assertTrue(out.contains("\"computed\":\"c\""), out);
    }

    @Test
    void generatedMapping_nativeAccessBeatsBridge() throws Exception {
        // Native @JqAccess wins over @JsonProperty(access): bridge says
        // WRITE_ONLY (skip ser) but native READ_ONLY applies (skip deser).
        String source = """
                package test;

                import com.fasterxml.jackson.annotation.JsonProperty;
                import io.hyperfoil.tools.jjq.mapper.JqAccess;
                import io.hyperfoil.tools.jjq.mapper.JqMapped;

                @JqMapped
                public class AccessPriority {
                    @JqAccess(JqAccess.Access.READ_ONLY)
                    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
                    public String field;

                    public AccessPriority() {}
                }
                """;

        Class<?> pojoClass = compileAndLoad("test.AccessPriority", source);
        JqMapper mapper = JqMapper.create();

        Object p = mapper.fromJqValue(JqValues.parse("{\"field\":\"x\"}"), pojoClass);
        assertNull(pojoClass.getField("field").get(p));

        pojoClass.getField("field").set(p, "c");
        assertTrue(mapper.toJqValue(p).toJsonString().contains("\"field\":\"c\""));
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

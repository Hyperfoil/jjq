package io.hyperfoil.tools.jjq.yaml;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.value.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JqYamlTest {

    // ---- Basic types ----

    @Test
    void parseSimpleMapping() {
        JqValue result = JqYaml.parse("name: Alice\nage: 30");
        assertEquals("Alice", result.getField("name").stringValue());
        assertEquals(30L, result.getField("age").longValue());
    }

    @Test
    void doubleQuotedLineAndParagraphSeparators() {
        // YAML 1.2 §5.7: \L = U+2028, \P = U+2029 (#125)
        JqValue block = JqYaml.parse("description: \"a\\Lf\\Pb\"");
        assertEquals("a\u2028f\u2029b", block.getField("description").stringValue());
        // Flow context exercises the separate flowEscape() switch
        JqValue flow = JqYaml.parse("[\"a\\Lb\", \"c\\Pd\"]");
        assertEquals("a\u2028b", flow.getElement(0).stringValue());
        assertEquals("c\u2029d", flow.getElement(1).stringValue());
        // Double-quoted flow KEY exercises the separate flowEscape() switch
        JqValue keyed = JqYaml.parse("{\"a\\Lb\": 1}");
        assertEquals(1L, keyed.getField("a\u2028b").longValue());
    }

    @Test
    void doubleQuotedSpecEscapeAlphabet() {
        // Full YAML 1.2 §5.7 escape set — locks that no other arm is missing
        JqValue v = JqYaml.parse("k: \"\\0\\a\\b\\t\\n\\v\\f\\r\\e\\\"\\\\\\ \\/\\N\\_\\L\\P\"");
        assertEquals("\0\7\b\t\n\13\f\r\33\"\\ /\u0085\u00A0\u2028\u2029", v.getField("k").stringValue());
    }

    @Test
    void parseNestedMapping() {
        JqValue result = JqYaml.parse("""
                server:
                  host: localhost
                  port: 8080
                """);
        assertEquals("localhost", result.getField("server").getField("host").stringValue());
        assertEquals(8080L, result.getField("server").getField("port").longValue());
    }

    @Test
    void parseSequence() {
        JqValue result = JqYaml.parse("""
                items:
                  - one
                  - two
                  - three
                """);
        JqValue items = result.getField("items");
        assertInstanceOf(JqArray.class, items);
        assertEquals(3, ((JqArray) items).size());
        assertEquals("one", items.getElement(0).stringValue());
    }

    // ---- Empty values in sequence items stay null (issue #134) ----

    @Test
    void emptyValueInSeqItemKeepsSiblings() {
        JqValue result = JqYaml.parse("- n: 1\n  a:\n  b: 1\n");
        assertEquals("{\"n\":1,\"a\":null,\"b\":1}", result.getElement(0).toJsonString());
    }

    @Test
    void emptyValueInNestedSeqItemKeepsSiblings() {
        JqValue result = JqYaml.parse("x:\n  - n: 1\n    a:\n    b: 1\n    c: 2\n");
        assertEquals("{\"n\":1,\"a\":null,\"b\":1,\"c\":2}",
                result.getField("x").getElement(0).toJsonString());
    }

    @Test
    void emptyValueDoesNotSwallowNextItem() {
        JqValue result = JqYaml.parse("- n: 1\n  a:\n- n: 2\n");
        assertEquals(2, ((JqArray) result).size());
        assertEquals("{\"n\":1,\"a\":null}", result.getElement(0).toJsonString());
        assertEquals("{\"n\":2}", result.getElement(1).toJsonString());
    }

    @Test
    void emptyFirstValueInSeqItemKeepsSiblings() {
        JqValue result = JqYaml.parse("- a:\n  b: 1\n");
        assertEquals("{\"a\":null,\"b\":1}", result.getElement(0).toJsonString());
    }

    @Test
    void parseScalarTypes() {
        JqValue result = JqYaml.parse("""
                string: hello
                integer: 42
                float: 3.14
                bool_true: true
                bool_false: false
                null_value: null
                null_tilde: ~
                """);
        assertEquals("hello", result.getField("string").stringValue());
        assertEquals(42L, result.getField("integer").longValue());
        assertEquals(3.14, result.getField("float").asDouble(0.0), 0.001);
        assertTrue(result.getField("bool_true").booleanValue());
        assertFalse(result.getField("bool_false").booleanValue());
        assertTrue(result.getField("null_value").isNull());
        assertTrue(result.getField("null_tilde").isNull());
    }

    @Test
    void parseEmptyDocument() {
        JqValue result = JqYaml.parse("");
        assertTrue(result.isNull());
    }

    // ---- Multi-document ----

    @Test
    void parseAllMultiDocument() {
        List<JqValue> docs = JqYaml.parseAll("""
                name: first
                ---
                name: second
                ---
                name: third
                """);
        assertEquals(3, docs.size());
        assertEquals("first", docs.get(0).getField("name").stringValue());
        assertEquals("second", docs.get(1).getField("name").stringValue());
        assertEquals("third", docs.get(2).getField("name").stringValue());
    }

    @Test
    void parseAllAsArrayMultiDocument() {
        JqArray docs = JqYaml.parseAllAsArray("""
                name: alpha
                ---
                name: beta
                """);
        assertEquals(2, docs.size());
        // Query across all documents with jq (cast to JqValue to resolve ambiguity)
        List<JqValue> names = JqProgram.compile(".[].name").applyAll((JqValue) docs);
        assertEquals(2, names.size());
        assertEquals("alpha", names.get(0).stringValue());
        assertEquals("beta", names.get(1).stringValue());
    }

    @Test
    void parseAllEmpty() {
        List<JqValue> docs = JqYaml.parseAll("");
        assertTrue(docs.isEmpty());
    }

    // ---- jq queries over YAML ----

    @Test
    void jqQueryOverYaml() {
        JqValue config = JqYaml.parse("""
                java:
                  version: "25"
                  opts:
                    - --enable-preview
                    - -Xmx4g
                """);

        assertEquals("25", JqProgram.compile(".java.version").apply(config).stringValue());
        assertEquals(2, JqProgram.compile(".java.opts | length").apply(config).longValue());
        assertEquals("--enable-preview",
                JqProgram.compile(".java.opts[0]").apply(config).stringValue());
    }

    @Test
    void jqQueryDeepNavigation() {
        JqValue config = JqYaml.parse("""
                database:
                  primary:
                    host: db.example.com
                    port: 5432
                  replicas:
                    - host: replica1.example.com
                      port: 5432
                    - host: replica2.example.com
                      port: 5433
                """);

        assertEquals("db.example.com",
                JqProgram.compile(".database.primary.host").apply(config).stringValue());
        List<JqValue> replicaHosts = JqProgram.compile(".database.replicas[].host").applyAll(config);
        assertEquals(2, replicaHosts.size());
        assertEquals("replica1.example.com", replicaHosts.get(0).stringValue());
        assertEquals("replica2.example.com", replicaHosts.get(1).stringValue());
    }

    // ---- Edge cases ----

    @Test
    void parseHexAndBinary() {
        JqValue result = JqYaml.parse("""
                hex: 0xFF
                binary: 0b1010
                octal: 077
                """);
        assertEquals(255L, result.getField("hex").longValue());
        assertEquals(10L, result.getField("binary").longValue());
        assertEquals(63L, result.getField("octal").longValue());
    }

    @Test
    void parseSpecialFloats() {
        JqValue result = JqYaml.parse("""
                inf: .inf
                neg_inf: -.inf
                nan: .nan
                """);
        assertTrue(Double.isInfinite(result.getField("inf").asDouble(0.0)));
        assertTrue(result.getField("inf").asDouble(0.0) > 0);
        assertTrue(Double.isInfinite(result.getField("neg_inf").asDouble(0.0)));
        assertTrue(result.getField("neg_inf").asDouble(0.0) < 0);
        assertTrue(Double.isNaN(result.getField("nan").asDouble(0.0)));
    }

    @Test
    void parseYamlAnchors() {
        JqValue result = JqYaml.parse("""
                defaults: &defaults
                  timeout: 30
                  retries: 3
                production:
                  <<: *defaults
                  timeout: 60
                """);
        // Anchors should be resolved by SnakeYAML
        assertEquals(60L, result.getField("production").getField("timeout").longValue());
        assertEquals(3L, result.getField("production").getField("retries").longValue());
    }

    @Test
    void parseFlowStyle() {
        JqValue result = JqYaml.parse("{name: Alice, items: [1, 2, 3]}");
        assertEquals("Alice", result.getField("name").stringValue());
        assertEquals(3, ((JqArray) result.getField("items")).size());
    }

    // ---- Round-trip: YAML → JqValue → JSON string ----

    @Test
    void roundTripToJson() {
        JqValue result = JqYaml.parse("""
                name: Alice
                age: 30
                active: true
                """);
        String json = result.toJsonString();
        // Parse the JSON and verify it matches
        JqValue fromJson = JqValues.parse(json);
        assertEquals("Alice", fromJson.getField("name").stringValue());
        assertEquals(30L, fromJson.getField("age").longValue());
        assertTrue(fromJson.getField("active").booleanValue());
    }

    // ---- Kubernetes-style multi-document ----

    @Test
    void parseKubernetesManifest() {
        JqArray resources = JqYaml.parseAllAsArray("""
                apiVersion: v1
                kind: Service
                metadata:
                  name: my-service
                ---
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: my-deployment
                """);

        List<JqValue> kinds = JqProgram.compile(".[].kind").applyAll((JqValue) resources);
        assertEquals(2, kinds.size());
        assertEquals("Service", kinds.get(0).stringValue());
        assertEquals("Deployment", kinds.get(1).stringValue());

        // Extract all names
        List<JqValue> names = JqProgram.compile(".[].metadata.name").applyAll((JqValue) resources);
        assertEquals("my-service", names.get(0).stringValue());
        assertEquals("my-deployment", names.get(1).stringValue());
    }

    // ---- Jackson compat: path(), isMissingNode(), asText(String) ----

    @Test
    void pathNavigation() {
        JqValue config = JqYaml.parse("""
                server:
                  host: localhost
                  port: 8080
                """);
        // path() is an alias for getField()
        assertEquals("localhost", config.path("server").path("host").asString(""));
        assertEquals(8080L, config.path("server").path("port").asLong(0));
        // Missing paths return JqNull
        assertTrue(config.path("nonexistent").isNull());
        assertTrue(config.path("server").path("nonexistent").isMissingNode());
    }

    @Test
    void asTextWithDefault() {
        JqValue config = JqYaml.parse("name: Alice\nage: 30");
        assertEquals("Alice", config.path("name").asText("unknown"));
        assertEquals("unknown", config.path("missing").asText("unknown"));
        // Non-string types return the default (unlike asText() no-arg which returns toJsonString)
        assertEquals("default", config.path("age").asText("default"));
    }

    // ---- fromYaml with JqMapper ----

    record ServerConfig(String host, int port) {}

    @Test
    void fromYamlToRecord() {
        var mapper = io.hyperfoil.tools.jjq.mapper.JqMapper.create();
        ServerConfig config = JqYaml.fromYaml("""
                host: localhost
                port: 8080
                """, mapper, ServerConfig.class);
        assertEquals("localhost", config.host());
        assertEquals(8080, config.port());
    }

    @Test
    void toYamlRoundTrip() {
        JqValue original = JqYaml.parse("name: Alice\nage: 30\nactive: true\n");
        String yaml = JqYaml.toYaml(original);
        JqValue reparsed = JqYaml.parse(yaml);
        assertEquals("Alice", reparsed.getField("name").stringValue());
        assertEquals(30L, reparsed.getField("age").longValue());
        assertTrue(reparsed.getField("active").booleanValue());
    }

    @Test
    void toYamlBlockStyle() {
        JqValue value = JqValues.parse("{\"server\":{\"host\":\"localhost\",\"ports\":[8080,8081]}}");
        String yaml = JqYaml.toYaml(value);
        assertFalse(yaml.contains("{"));
        JqValue reparsed = JqYaml.parse(yaml);
        assertEquals("localhost", reparsed.getField("server").getField("host").stringValue());
        assertEquals(8081L, reparsed.getField("server").getField("ports").getElement(1).longValue());
    }

    @Test
    void toYamlSpecialValues() {
        JqValue value = JqValues.parse("{\"a\":null,\"b\":true,\"c\":1.5,\"d\":\"hello\"}");
        String yaml = JqYaml.toYaml(value);
        JqValue reparsed = JqYaml.parse(yaml);
        assertTrue(reparsed.getField("a").isNull());
        assertTrue(reparsed.getField("b").booleanValue());
        assertEquals(1.5, reparsed.getField("c").doubleValue(), 0.001);
        assertEquals("hello", reparsed.getField("d").stringValue());
    }

    @Test
    void toYamlStreams() throws Exception {
        JqValue value = JqYaml.parse("name: Alice\n");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        JqYaml.toYaml(value, out);
        assertEquals("Alice", JqYaml.parse(out.toString(java.nio.charset.StandardCharsets.UTF_8)).getField("name").stringValue());
        java.io.StringWriter writer = new java.io.StringWriter();
        JqYaml.toYaml(value, writer);
        assertEquals("Alice", JqYaml.parse(writer.toString()).getField("name").stringValue());
    }

    @Test
    void toYamlQuotableStringsRoundTrip() {
        // Strings that would reparse as another type (or break structure)
        // must be quoted by the emitter.
        String[] tricky = {"true", "123", "1.5", "null", "~", "a: b", "a #b",
                "", "  spaced  ", "- dash", "yes", "0x1F", "12:30", "a'b\"c", "tab\there"};
        var builder = JqObject.builder(tricky.length);
        for (int i = 0; i < tricky.length; i++) {
            builder.putUnchecked("k" + i, JqString.of(tricky[i]));
        }
        JqValue original = builder.build();
        String yaml = JqYaml.toYaml(original);
        assertFalse(yaml.contains("{"));
        JqValue reparsed = JqYaml.parse(yaml);
        for (int i = 0; i < tricky.length; i++) {
            assertEquals(tricky[i], reparsed.getField("k" + i).stringValue(), "field k" + i);
            assertTrue(reparsed.getField("k" + i).isString(), "still a string: k" + i);
        }
    }

    @Test
    void toYamlMultilineStringsRoundTrip() {
        JqValue original = JqObject.builder(2)
                .putUnchecked("text", JqString.of("line one\nline two\n"))
                .putUnchecked("list", JqArray.of(JqString.of("a\nb"), JqNumber.of(1)))
                .build();
        String yaml = JqYaml.toYaml(original);
        JqValue reparsed = JqYaml.parse(yaml);
        assertEquals("line one\nline two\n", reparsed.getField("text").stringValue());
        assertEquals("a\nb", reparsed.getField("list").getElement(0).stringValue());
    }

    // ---- Float fidelity + surrogate escaping (#138-#140) ----

    @Test
    void floatPrecisionPreserved() {
        JqValue v = JqYaml.parse("n: 3.14159265358979323846\n");
        assertEquals("3.14159265358979323846", v.getField("n").toJsonString());
        assertEquals(v.getField("n").toJsonString(),
                JqYaml.parse(JqYaml.toYaml(v)).getField("n").toJsonString());
    }

    @Test
    void dotlessExponentStaysFloat() {
        JqValue v = JqYaml.parse("n: 1E+30\n");
        assertEquals("1E+30", v.getField("n").toJsonString());
    }

    @Test
    void loneSurrogateRoundTrips() {
        JqValue original = JqValues.parse("{\"k\":\"\\ud800\"}");
        assertEquals(original.toJsonString(),
                JqYaml.parse(JqYaml.toYaml(original)).toJsonString());
    }

    @Test
    void toYamlEmptyContainersRoundTrip() {
        JqValue original = JqObject.builder(2)
                .putUnchecked("map", JqObject.builder(0).build())
                .putUnchecked("seq", JqArray.of())
                .build();
        String yaml = JqYaml.toYaml(original);
        JqValue reparsed = JqYaml.parse(yaml);
        assertTrue(reparsed.getField("map").isObject());
        assertEquals(0, ((JqObject) reparsed.getField("map")).size());
        assertTrue(reparsed.getField("seq").isArray());
        assertEquals(0, ((JqArray) reparsed.getField("seq")).size());
    }

    @Test
    void toYamlSpecialDoublesRoundTrip() {
        JqValue original = JqObject.builder(3)
                .putUnchecked("nan", JqNumber.of(Double.NaN))
                .putUnchecked("inf", JqNumber.of(Double.POSITIVE_INFINITY))
                .putUnchecked("ninf", JqNumber.of(Double.NEGATIVE_INFINITY))
                .build();
        String yaml = JqYaml.toYaml(original);
        JqValue reparsed = JqYaml.parse(yaml);
        assertTrue(Double.isNaN(reparsed.getField("nan").doubleValue()));
        assertEquals(Double.POSITIVE_INFINITY, reparsed.getField("inf").doubleValue());
        assertEquals(Double.NEGATIVE_INFINITY, reparsed.getField("ninf").doubleValue());
    }

    record DatabaseConfig(String primary, java.util.List<String> replicas) {}

    @Test
    void fromYamlWithList() {
        var mapper = io.hyperfoil.tools.jjq.mapper.JqMapper.create();
        DatabaseConfig config = JqYaml.fromYaml("""
                primary: db.example.com
                replicas:
                  - replica1.example.com
                  - replica2.example.com
                """, mapper, DatabaseConfig.class);
        assertEquals("db.example.com", config.primary());
        assertEquals(2, config.replicas().size());
        assertEquals("replica1.example.com", config.replicas().get(0));
    }

    // ---- Duplicate key detection (issue #83) ----

    @Test
    void duplicateKeysLenientByDefault() {
        JqValue result = JqYaml.parse("name: a\nname: b\n");
        assertEquals("b", result.getField("name").stringValue());
    }

    @Test
    void duplicateKeysStrictThrows() {
        JqYamlException e = assertThrows(JqYamlException.class, () ->
                JqYaml.parse("name: a\nname: b\n", YamlOptions.STRICT));
        assertTrue(e.getMessage().contains("name"));
        assertEquals("name", e.key());
    }

    @Test
    void duplicateKeysStrictNested() {
        JqYamlException e = assertThrows(JqYamlException.class, () ->
                JqYaml.parse("server:\n  host: a\n  host: b\n", YamlOptions.STRICT));
        assertEquals("host", e.key());
    }

    @Test
    void duplicateKeysStrictMergeKeyAllowed() {
        JqValue result = JqYaml.parse("""
                base: &b
                  x: 1
                derived:
                  <<: *b
                  y: 2
                """, YamlOptions.STRICT);
        assertEquals(1L, result.getField("derived").getField("x").longValue());
        assertEquals(2L, result.getField("derived").getField("y").longValue());
    }

    @Test
    void duplicateKeysStrictParseAll() {
        assertThrows(JqYamlException.class, () ->
                JqYaml.parseAll("a: 1\na: 2\n", YamlOptions.STRICT));
        List<JqValue> docs = JqYaml.parseAll("a: 1\n---\nb: 2\n", YamlOptions.STRICT);
        assertEquals(2, docs.size());
    }

    @Test
    void duplicateKeysStrictFromYaml() {
        var mapper = io.hyperfoil.tools.jjq.mapper.JqMapper.create();
        assertThrows(JqYamlException.class, () ->
                JqYaml.fromYaml("host: a\nhost: b\n", mapper, ServerConfig.class, YamlOptions.STRICT));
        ServerConfig config = JqYaml.fromYaml("host: a\nport: 1\n", mapper, ServerConfig.class, YamlOptions.STRICT);
        assertEquals("a", config.host());
    }

    // ---- Malformed UTF-8 handling (issue #131) ----

    private static byte[] latin1(String s) {
        return s.getBytes(java.nio.charset.Charset.forName("ISO-8859-1"));
    }

    @Test
    void streamRejectsMalformedUtf8WithLine() {
        var in = new java.io.ByteArrayInputStream(latin1("name: ok\ndesc: caf\u00e9\n"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> JqYaml.parse(in));
        assertTrue(e.getMessage().contains("line 2"), e.getMessage());
    }

    @Test
    void streamAcceptsValidMultibyte() {
        byte[] bytes = "name: caf\u00e9 \uD83D\uDE00\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        JqValue result = JqYaml.parse(new java.io.ByteArrayInputStream(bytes));
        assertEquals("caf\u00e9 \uD83D\uDE00", result.getField("name").stringValue());
    }

    // ---- Emitter round-trip audit matrix (issue #137) ----
    // Every shape below must survive emit-parse with identical text: a
    // failure is by construction a real save-fidelity regression, never a
    // stale expectation. Runs in milliseconds (tiny docs, no I/O).
    @Test
    void toYamlRoundTripAuditMatrix() {
        List<String> docs = List.of(
            "{\"auto-clone-repos\":1}", "{\"a b\":1}", "{\"a:b\":1}", "{\"a: b\":1}",
            "{\"\":1}", "{\"123\":1}", "{\"true\":1}", "{\"null\":1}", "{\"\":1}",
            "{\" leading\":1}", "{\"trailing \":1}", "{\"a#b\":1}", "{\"a:b\":1}",
            "{\"[a]\":1}", "{\"{a}\":1}", "{\"*a\":1}", "{\"&a\":1}", "{\"!t\":1}",
            "{\"|\":1}", "{\">\":1}", "{\"@a\":1}", "{\"`a\":1}", "{\"\u00a9\":1}",
            "{\"caf\u00e9\":1}",
            // doc markers / directives as values and keys
            "{\"k\":\"...\"}", "{\"k\":\"---\"}", "{\"...\":1}", "{\"---\":1}",
            "{\"k\":\"%foo\"}", "{\"k\":\"#c\"}",
            // special scalars as strings
            "{\"k\":\"yes\"}", "{\"k\":\"0o17\"}", "{\"k\":\"0b1\"}", "{\"k\":\".inf\"}",
            "{\"k\":\".nan\"}", "{\"k\":\"y\"}", "{\"k\":\"n\"}", "{\"k\":\"Yes\"}",
            "{\"k\":\"ON\"}", "{\"k\":\"off\"}", "{\"k\":\"~\"}", "{\"k\":\"1_000\"}",
            "{\"k\":\"0x1F\"}", "{\"k\":\"190:20:30\"}", "{\"k\":\"12:30\"}", "{\"k\":\"0\"}",
            // control chars and unicode in strings
            "{\"k\":\"a\\u0000b\"}", "{\"k\":\"a\\u0008b\"}", "{\"k\":\"a\\u001bb\"}",
            "{\"k\":\"a\\u007fb\"}", "{\"k\":\"a\\u0085b\"}", "{\"k\":\"a\\u2028b\"}",
            "{\"k\":\"a\\u2029b\"}", "{\"k\":\"a\\u00a0b\"}", "{\"k\":\"a\\u0080b\"}",
            "{\"k\":\"a\\ufeffb\"}", "{\"k\":\"\\ud800\"}",
            // multiline shapes
            "{\"k\":\"a\\n\"}", "{\"k\":\"\\n\"}", "{\"k\":\"a\\n\\nb\"}", "{\"k\":\"\\n\\n\"}",
            "{\"k\":\"a\\r\\nb\"}", "{\"k\":\"a\\tb\"}", "{\"k\":\"\\\\\"}",
            "{\"k\":\"ends with backslash\\\\\"}",
            // numbers
            "{\"n\":-0.0}", "{\"n\":1e30}", "{\"n\":3.14159265358979323846}",
            "{\"n\":9223372036854775807}", "{\"n\":0.1}", "{\"n\":-5}",
            // structures
            "{\"a\":{}}", "{\"a\":[]}", "{\"a\":{\"b\":{}}}", "{\"a\":[[]]}",
            "{\"a\":null}", "[null]", "{\"a\":[null]}", "{}",
            "{\"deep\":{\"a\":{\"b\":{\"c\":{\"d\":1}}}}}",
            "{\"list\":[[1],[2]]}", "{\"m\":[{\"a\":1},{\"b\":2}]}", "\"just a scalar\"", "\"\"", "42", "true", "null"
        );
        for (String doc : docs) {
            JqValue original = JqValues.parse(doc);
            JqValue back = JqYaml.parse(JqYaml.toYaml(original));
            assertEquals(original.toJsonString(), back.toJsonString(), "round trip: " + doc);
        }
    }
}

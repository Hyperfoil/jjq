package io.hyperfoil.tools.jjq.yaml;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.value.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JqYamlTest {

    // ---- Basic types ----

    @Test
    void indentedMarkerInsideBlockScalarIsContent() {
        // Issue #150 (YAML 1.2 §9.1.2/§8.1.1): `---` is a document
        // marker only at column 0; indented lines are scalar content.
        JqValue result = JqYaml.parse(
                "description: |\n  harmless\n  --- README.md ---\n  more\nparent: minimal\n");
        assertEquals("harmless\n--- README.md ---\nmore\n",
                result.getField("description").stringValue());
        assertEquals("minimal", result.getField("parent").stringValue());
    }

    @Test
    void bareIndentedMarkerInsideBlockScalarIsContent() {
        JqValue result = JqYaml.parse(
                "description: |\n  harmless\n  ---\n  more\nparent: minimal\n");
        assertEquals("harmless\n---\nmore\n",
                result.getField("description").stringValue());
        assertEquals("minimal", result.getField("parent").stringValue());
    }

    @Test
    void columnZeroMarkerStillEndsBlockScalar() {
        // A real column-0 marker still terminates the scalar document.
        List<JqValue> docs = JqYaml.parseAll(
                "description: |\n  kept\n---\nparent: minimal\n");
        assertEquals(2, docs.size());
        assertEquals("kept\n", docs.get(0).getField("description").stringValue());
        assertEquals("minimal", docs.get(1).getField("parent").stringValue());
    }

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
    void toYamlSeqItemNestedMapSitsDeeper() {
        // Issue #181: a mapping nested under a sequence-item key must be
        // one level deeper than the key, or reparse promotes its entries
        // to siblings and nulls the key. Sequences keep the compact
        // same-indent form (valid, reparses fine).
        JqValue value = JqValues.parse(
                "{\"items\":[{\"name\":\"a\",\"labels\":{\"app\":\"demo\"},\"ports\":[8080]}]}");
        String yaml = JqYaml.toYaml(value);
        assertEquals("""
                items:
                  - name: a
                    labels:\s
                      app: demo
                    ports:\s
                    - 8080
                """, yaml.lines()
                        .filter(l -> !l.isBlank())
                        .collect(java.util.stream.Collectors.joining("\n")) + "\n");
        JqValue reparsed = JqYaml.parse(yaml);
        assertEquals(reparsed.toJsonString(), value.toJsonString());
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
    void toYamlControlCharactersForceDoubleQuotes() {
        // C0 controls (other than tab), DEL and C1 are not printable
        // YAML even inside plain scalars: strict parsers reject the
        // raw bytes, so the emitter must double-quote with escapes
        // (issue #163). Tab stays bare; LF/CR already force quoting.
        String[] controls = {"Base \u0001 x", "a\u0000b", "a\u001bb",
                "a\u007fb", "a\u0080b", "a\u009fb", "a\u0085b"};
        var builder = JqObject.builder(controls.length + 1);
        for (int i = 0; i < controls.length; i++) {
            builder.putUnchecked("k" + i, JqString.of(controls[i]));
        }
        builder.putUnchecked("tab", JqString.of("a\tb"));
        JqValue original = builder.build();
        String yaml = JqYaml.toYaml(original);
        for (String s : controls) {
            assertFalse(yaml.contains(s), "no raw control bytes in output");
        }
        assertTrue(yaml.contains("\"Base \\x01 x\""), "C0 escaped: " + yaml);
        assertTrue(yaml.contains("\\x7f"), "DEL escaped: " + yaml);
        assertTrue(yaml.contains("\\u0080"), "C1 escaped: " + yaml);
        assertTrue(yaml.contains("a\tb"), "tab stays bare: " + yaml);
        JqValue reparsed = JqYaml.parse(yaml);
        for (int i = 0; i < controls.length; i++) {
            assertEquals(controls[i], reparsed.getField("k" + i).stringValue(), "field k" + i);
        }
        assertEquals("a\tb", reparsed.getField("tab").stringValue());
    }

    // ---- Emitter options (issue #164) ----

    @Test
    void emitIndentWidthFour() {
        JqValue v = JqObject.builder(1)
                .putUnchecked("server", JqObject.builder(2)
                        .putUnchecked("host", JqString.of("localhost"))
                        .putUnchecked("ports", JqArray.of(JqNumber.of(8080), JqNumber.of(8081)))
                        .build())
                .build();
        String yaml = JqYaml.toYaml(v, new YamlEmitOptions(4,
                YamlEmitOptions.Style.BLOCK, YamlEmitOptions.Style.BLOCK));
        assertEquals("server:\n    host: localhost\n    ports:\n        - 8080\n        - 8081\n", yaml);
        JqValue back = JqYaml.parse(yaml);
        assertEquals("localhost", back.getField("server").getField("host").stringValue());
        assertEquals(8081L, back.getField("server").getField("ports").getElement(1).longValue());
    }

    @Test
    void emitOptionsDefaultMatchesNoOptions() {
        JqValue v = JqObject.builder(2)
                .putUnchecked("a", JqString.of("x"))
                .putUnchecked("ports", JqArray.of(JqNumber.of(1), JqNumber.of(2)))
                .build();
        assertEquals(JqYaml.toYaml(v), JqYaml.toYaml(v, YamlEmitOptions.DEFAULT));
    }

    @Test
    void emitIndentWidthValidated() {
        assertThrows(IllegalArgumentException.class, () -> new YamlEmitOptions(0,
                YamlEmitOptions.Style.BLOCK, YamlEmitOptions.Style.BLOCK));
        assertThrows(IllegalArgumentException.class, () -> new YamlEmitOptions(11,
                YamlEmitOptions.Style.BLOCK, YamlEmitOptions.Style.BLOCK));
    }

    @Test
    void emitFlowSequence() {
        JqValue v = JqObject.builder(1)
                .putUnchecked("ports", JqArray.of(JqNumber.of(8080), JqNumber.of(8081)))
                .build();
        String yaml = JqYaml.toYaml(v, new YamlEmitOptions(2,
                YamlEmitOptions.Style.FLOW, YamlEmitOptions.Style.BLOCK));
        assertEquals("ports: [8080, 8081]\n", yaml);
        assertEquals("[8080,8081]",
                JqYaml.parse(yaml).getField("ports").toJsonString());
    }

    @Test
    void emitFlowMapping() {
        JqValue v = JqObject.builder(1)
                .putUnchecked("server", JqObject.builder(2)
                        .putUnchecked("host", JqString.of("localhost"))
                        .putUnchecked("port", JqNumber.of(8080))
                        .build())
                .build();
        String yaml = JqYaml.toYaml(v, new YamlEmitOptions(2,
                YamlEmitOptions.Style.BLOCK, YamlEmitOptions.Style.FLOW));
        assertEquals("{server: {host: localhost, port: 8080}}\n", yaml);
        assertEquals("localhost",
                JqYaml.parse(yaml).getField("server").getField("host").stringValue());
    }

    @Test
    void emitFlowQuotesStructuralScalars() {
        // Commas, brackets and ': ' end plain scalars in flow context,
        // so they must be quoted there (block output stays bare).
        JqValue v = JqArray.of(JqString.of("a,b"), JqString.of("a: b"),
                JqString.of("[x]"), JqString.of("plain"));
        String flow = JqYaml.toYaml(v, new YamlEmitOptions(2,
                YamlEmitOptions.Style.FLOW, YamlEmitOptions.Style.FLOW));
        assertEquals("[\"a,b\", \"a: b\", \"[x]\", plain]\n", flow);
        assertEquals(v.toJsonString(), JqYaml.parse(flow).toJsonString());
        String block = JqYaml.toYaml(v, YamlEmitOptions.DEFAULT);
        assertTrue(block.contains("- a,b\n"), "block stays bare: " + block);
    }

    @Test
    void emitNestedFlowStyles() {
        // A flow sequence nested in a block mapping, holding mappings
        // that are forced into flow by the inline context.
        JqValue v = JqObject.builder(1)
                .putUnchecked("items", JqArray.of(
                        JqObject.builder(1).putUnchecked("a", JqNumber.of(1)).build(),
                        JqObject.builder(1).putUnchecked("b", JqNumber.of(2)).build()))
                .build();
        String yaml = JqYaml.toYaml(v, new YamlEmitOptions(2,
                YamlEmitOptions.Style.FLOW, YamlEmitOptions.Style.BLOCK));
        assertEquals("items: [{a: 1}, {b: 2}]\n", yaml);
        assertEquals(v.toJsonString(), JqYaml.parse(yaml).toJsonString());
    }

    // ---- Custom tag constructors (issue #165, explicit tags only) ----

    @Test
    void customScalarTagDispatches() {
        YamlTagConstructors ctors = YamlTagConstructors.EMPTY
                .with("greet", (tag, v) -> JqString.of("hello " + v.stringValue()));
        JqValue v = JqYaml.parse("msg: !greet World\n", ctors);
        assertEquals("hello World", v.getField("msg").stringValue());
    }

    @Test
    void customMappingTagReceivesDecodedMap() {
        YamlTagConstructors ctors = YamlTagConstructors.EMPTY
                .with("point", (tag, v) -> JqString.of(
                        v.getField("x").longValue() + "," + v.getField("y").longValue()));
        JqValue v = JqYaml.parse("p: !point {x: 1, y: 2}\n", ctors);
        assertEquals("1,2", v.getField("p").stringValue());
    }

    @Test
    void customSequenceTagReceivesDecodedList() {
        YamlTagConstructors ctors = YamlTagConstructors.EMPTY
                .with("vec", (tag, v) -> {
                    long sum = 0;
                    for (JqValue e : (JqArray) v) sum += e.longValue();
                    return JqNumber.of(sum);
                });
        JqValue v = JqYaml.parse("v: !vec\n  - 1\n  - 2\n", ctors);
        assertEquals(3L, v.getField("v").longValue());
    }

    @Test
    void customVerbatimTagNormalizes() {
        YamlTagConstructors ctors = YamlTagConstructors.EMPTY
                .with("shout", (tag, v) -> JqString.of(v.stringValue() + "!"));
        JqValue v = JqYaml.parse("msg: !<tag:example.com,2024:shout> hi\n", ctors);
        assertEquals("hi!", v.getField("msg").stringValue());
    }

    @Test
    void unknownTagWithoutHandlerKeepsDefault() {
        JqValue v = JqYaml.parse("k: !nope x\n", YamlTagConstructors.EMPTY);
        assertEquals("x", v.getField("k").stringValue());
    }

    @Test
    void untaggedScalarsNeverConsultHandlers() {
        // Even a `str` handler only fires on explicitly tagged nodes;
        // untagged scalars keep core-schema resolution (no global hook).
        YamlTagConstructors ctors = YamlTagConstructors.EMPTY
                .with("str", (tag, v) -> JqString.of("SHADOWED"));
        assertEquals("plain", JqYaml.parse("k: plain\n", ctors).getField("k").stringValue());
        assertEquals("SHADOWED", JqYaml.parse("k: !!str 42\n", ctors).getField("k").stringValue());
    }

    @Test
    void handlerExceptionPropagates() {
        YamlTagConstructors ctors = YamlTagConstructors.EMPTY
                .with("boom", (tag, v) -> { throw new IllegalStateException("nope"); });
        assertThrows(IllegalStateException.class,
                () -> JqYaml.parse("k: !boom x\n", ctors));
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
    void toYamlEmptyContainersRoundTrip() {        JqValue original = JqObject.builder(2)
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

    @Test
    void strictMergeCollisionExplicitWins() {
        // Explicit keys override merged keys (not duplicates): no throw,
        // explicit value wins (issue #143: merge-guard characterization).
        JqValue result = JqYaml.parse(
                "base: &b\n  x: 1\n  y: 9\nderived:\n  <<: *b\n  x: 2\n",
                YamlOptions.STRICT);
        assertEquals(2L, result.getField("derived").getField("x").longValue());
        assertEquals(9L, result.getField("derived").getField("y").longValue());
    }

    @Test
    void strictMergeAfterExplicitKeepsMergeValue() {
        // Merge entries replace through put semantics even in strict mode:
        // no throw, merge value wins (existing behavior, pinned).
        JqValue result = JqYaml.parse(
                "base: &b\n  x: 1\nderived:\n  x: 5\n  <<: *b\n",
                YamlOptions.STRICT);
        assertEquals(1L, result.getField("derived").getField("x").longValue());
    }

    @Test
    void strictGiantMapMatchesLenient() {
        // 50K unique keys: strict and lenient must agree exactly
        // (issue #143: guards the unchecked strict path at scale).
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50_000; i++) {
            sb.append("key-").append(i).append(": ").append(i % 3 == 0 ? "\"v\"" : i).append('\n');
        }
        String doc = sb.toString();
        JqValue strict = JqYaml.parse(doc, YamlOptions.STRICT);
        JqValue lenient = JqYaml.parse(doc, YamlOptions.LENIENT);
        assertEquals(50_000, strict.size());
        assertEquals(lenient.toJsonString(), strict.toJsonString());
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
    void floatLexedRecordedForYamlFloats() {
        // YAML float spellings carry the same provenance as JSON ones.
        assertTrue(((JqNumber) JqYaml.parse("100.0")).isFloatLexed());
        assertTrue(((JqNumber) JqYaml.parse("-0")).isFloatLexed());
        assertFalse(((JqNumber) JqYaml.parse("100")).isFloatLexed());
    }

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
            "{\"list\":[[1],[2]]}", "{\"m\":[{\"a\":1},{\"b\":2}]}", "\"just a scalar\"", "\"\"", "42", "true", "null",
            // seq-item maps with nested collection values (issue #181)
            "{\"items\":[{\"name\":\"a\",\"labels\":{\"app\":\"demo\",\"tier\":\"backend\"}}]}",
            "{\"items\":[{\"labels\":{\"app\":\"demo\"},\"name\":\"a\"}]}",
            "{\"s\":[{\"l\":[{\"a\":1}]}]}",
            "{\"m\":[{\"a\":[1,2]}]}"
        );
        for (String doc : docs) {
            JqValue original = JqValues.parse(doc);
            JqValue back = JqYaml.parse(JqYaml.toYaml(original));
            assertEquals(original.toJsonString(), back.toJsonString(), "round trip: " + doc);
        }
    }

    // ---- Multiline quoted scalars (issue #159 behavior pins) ----

    @Test
    void multilineDoubleQuotedInMapping() {
        JqValue result = JqYaml.parse("key: \"first\n  second\n  third\"\nnext: 1\n");
        assertEquals("first second third", result.getField("key").stringValue());
        assertEquals(1L, result.getField("next").longValue());
    }

    @Test
    void multilineSingleQuotedInMapping() {
        JqValue result = JqYaml.parse("key: 'first\n  second'\n");
        assertEquals("first second", result.getField("key").stringValue());
    }

    @Test
    void multilineQuotedInSequence() {
        JqValue result = JqYaml.parse("- \"a\n  b\"\n- 'c\n  d'\n");
        assertEquals("a b", result.getElement(0).stringValue());
        assertEquals("c d", result.getElement(1).stringValue());
    }

    @Test
    void multilineQuotedAtDocRoot() {
        assertEquals("a b", JqYaml.parse("\"a\nb\"\n").stringValue());
        assertEquals("a b", JqYaml.parse("'a\nb'\n").stringValue());
    }

    @Test
    void multilineQuotedDoublingAndContinuation() {
        // Single-quote doubling survives folding; backslash-newline joins directly.
        JqValue single = JqYaml.parse("key: 'it''s\n  here'\n");
        assertEquals("it's here", single.getField("key").stringValue());
        JqValue dbl = JqYaml.parse("key: \"a\\\n  b\"\n");
        assertEquals("ab", dbl.getField("key").stringValue());
    }

    @Test
    void multilineQuotedBlankAndEscapes() {
        JqValue result = JqYaml.parse("key: \"a\n\n  b\\nLINE\"\n");
        assertEquals("a\nb\nLINE", result.getField("key").stringValue());
    }

    // ---- Second-sighting key admission ----

    @Test
    void uniqueKeysAreNotGloballyInterned() {
        JqValue v = JqYaml.parse("alpha-1: 1\nalpha-2: 2\n");
        assertEquals(1L, v.getField("alpha-1").longValue());
        assertEquals(2L, v.getField("alpha-2").longValue());
        byte[] a1 = "alpha-1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertNull(JqValues.probeFieldName(a1, 0, a1.length),
                "unique keys must not pollute the global intern table");
        byte[] a2 = "alpha-2".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertNull(JqValues.probeFieldName(a2, 0, a2.length),
                "unique keys must not pollute the global intern table");
    }

    @Test
    void repeatedKeysInternedAndShared() {
        JqValue v = JqYaml.parse(
                "- name: a\n  age: 1\n- name: b\n  age: 2\n- name: c\n  age: 3\n- name: d\n  age: 4\n");
        assertEquals("c", v.getElement(2).getField("name").stringValue());
        assertEquals(4L, v.getElement(3).getField("age").longValue());
        // Repeat sightings admit to the global table (reference-identical).
        String internedName = JqValues.internFieldName("name");
        assertSame(internedName, ((JqObject) v.getElement(1)).keyAt(0));
        assertSame(internedName, ((JqObject) v.getElement(2)).keyAt(0));
        assertSame(internedName, ((JqObject) v.getElement(3)).keyAt(0));
        // Steady-state sharing still engages (shareKeys self-healing).
        assertTrue(((JqObject) v.getElement(2)).sharesKeysWith((JqObject) v.getElement(3)));
    }

    @Test
    void quotedKeysWork() {
        // Quoted keys take the slow String path (unquoteKey never interns —
        // pre-existing scope, out of the byte-path admission gate).
        JqValue v = JqYaml.parse("- \"qk\": 1\n- \"qk\": 2\n");
        assertEquals(1L, v.getElement(0).getField("qk").longValue());
        assertEquals(2L, v.getElement(1).getField("qk").longValue());
    }

    @Test
    void duplicateKeysLastWins() {
        JqValue v = JqYaml.parse("a: 1\na: 2\n");
        assertEquals(2L, v.getField("a").longValue());
    }

    @Test
    void manyUniqueKeysStayOutOfInternTable() {
        // Saturation regression: a full seen-set must evict (keys stay raw),
        // never degrade to admit-everything.
        String tag = "sat-" + System.nanoTime();
        int n = 9000;
        var sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(tag).append('-').append(i).append(": ").append(i).append('\n');
        JqValue v = JqYaml.parse(sb.toString());
        assertEquals((long) (n - 1), v.getField(tag + "-" + (n - 1)).longValue());
        for (int i : new int[]{0, n / 2, n - 1}) {
            byte[] b = (tag + "-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertNull(JqValues.probeFieldName(b, 0, b.length),
                    "unique key " + i + " must not be interned");
        }
    }
}

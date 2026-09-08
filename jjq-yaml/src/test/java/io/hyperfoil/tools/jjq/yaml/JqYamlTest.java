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
}

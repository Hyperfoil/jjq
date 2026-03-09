package io.hyperfoil.tools.jjq.examples;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.fastjson2.FastjsonEngine;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

/**
 * Examples using the FastjsonEngine high-level API for applications
 * that already use fastjson2 for JSON processing.
 *
 * <p>Run with: {@code mvn -pl jjq-examples exec:exec -Dexec.mainClass=io.hyperfoil.tools.jjq.examples.FastjsonEngineExamples}
 */
public class FastjsonEngineExamples {

    public static void main(String[] args) {
        section("1. Quick one-liner queries");
        quickQueries();

        section("2. Working with fastjson2 objects directly");
        fastjsonInterop();

        section("3. Lazy conversion for large documents");
        lazyConversion();

        section("4. Byte buffer processing");
        byteBufferProcessing();

        section("5. JSON stream processing");
        jsonStreamProcessing();

        section("6. Converting results back to fastjson2");
        roundTrip();
    }

    // ---- 1. Quick one-liner queries ----

    static void quickQueries() {
        var engine = new FastjsonEngine();

        // Simple field access — returns List<JqValue>
        List<JqValue> results = engine.apply(".name", "{\"name\":\"Alice\",\"age\":30}");
        System.out.println("  .name => " + results.getFirst().toJsonString());

        // Get results as strings directly
        List<String> strings = engine.applyToStrings(
                "[.[] | select(. > 3)]",
                "[1,2,3,4,5,6]"
        );
        System.out.println("  select(. > 3) => " + strings);

        // Complex transformation in one call
        results = engine.apply(
                "group_by(.dept) | map({dept: .[0].dept, names: map(.name)})",
                """
                [{"name":"Alice","dept":"eng"},{"name":"Bob","dept":"sales"},{"name":"Carol","dept":"eng"}]
                """
        );
        System.out.println("  group by dept => " + results.getFirst().toJsonString());
        System.out.println();
    }

    // ---- 2. fastjson2 interop ----

    /**
     * If your application already uses fastjson2 JSONObject/JSONArray,
     * convert them to JqValue and query with jq.
     */
    static void fastjsonInterop() {
        var engine = new FastjsonEngine();

        // Build data with fastjson2 API (as you would in an existing app)
        var users = new JSONArray();
        for (String[] u : new String[][]{
                {"Alice", "30", "admin"},
                {"Bob", "25", "user"},
                {"Carol", "35", "admin"}
        }) {
            var obj = new JSONObject();
            obj.put("name", u[0]);
            obj.put("age", Integer.parseInt(u[1]));
            obj.put("role", u[2]);
            users.add(obj);
        }

        // Convert fastjson2 -> JqValue and query
        JqValue input = FastjsonEngine.fromFastjson(users);
        JqProgram program = engine.compile("[.[] | select(.role == \"admin\") | .name]");
        List<JqValue> results = program.applyAll(input);
        System.out.println("  Admin names: " + results.getFirst().toJsonString());
        System.out.println();
    }

    // ---- 3. Lazy conversion ----

    /**
     * For large JSON documents, lazy conversion avoids parsing the entire tree
     * upfront. Only accessed fields are converted from fastjson2 types.
     */
    static void lazyConversion() {
        // Simulate a large document where we only need one field
        var sb = new StringBuilder("{\"metadata\":{\"id\":12345},\"payload\":[");
        for (int i = 0; i < 1000; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"x\":").append(i).append(",\"y\":").append(i * 2).append("}");
        }
        sb.append("]}");

        String largeJson = sb.toString();

        // Lazy: only converts what the filter touches
        JqValue lazy = FastjsonEngine.fromJsonLazy(largeJson);
        JqProgram program = JqProgram.compile(".metadata.id");
        List<JqValue> results = program.applyAll(lazy);
        System.out.println("  Extracted .metadata.id from large doc: " + results.getFirst().toJsonString());
        System.out.println("  (payload array with 1000 elements was NOT converted)");
        System.out.println();
    }

    // ---- 4. Byte buffer processing ----

    /**
     * Process JSON directly from byte arrays without intermediate String creation.
     * Useful for network buffers, file I/O, or message queues.
     */
    static void byteBufferProcessing() {
        var engine = new FastjsonEngine();

        // Simulate receiving JSON as bytes (e.g., from a network socket)
        byte[] jsonBytes = "{\"temperature\":22.5,\"humidity\":65,\"location\":\"sensor-42\"}"
                .getBytes(StandardCharsets.UTF_8);

        // Process bytes -> bytes
        JqProgram program = engine.compile("{loc: .location, temp_f: (.temperature * 9/5 + 32)}");
        byte[] resultBytes = engine.applyToBytes(program, jsonBytes);
        System.out.println("  Input bytes:  " + new String(jsonBytes, StandardCharsets.UTF_8));
        System.out.println("  Output bytes: " + new String(resultBytes, StandardCharsets.UTF_8));

        // Process a sub-region of a larger buffer
        String padded = "HEADER" + "{\"value\":42}" + "FOOTER";
        byte[] buffer = padded.getBytes(StandardCharsets.UTF_8);
        JqProgram valueProgram = engine.compile(".value");
        byte[] result = engine.applyToBytes(valueProgram, buffer, 6, 12);
        System.out.println("  Sub-region:   " + new String(result, StandardCharsets.UTF_8));
        System.out.println();
    }

    // ---- 5. JSON stream processing ----

    /**
     * Process a stream of multiple JSON values (like NDJSON / JSON Lines).
     * Each value is parsed independently and the filter applied to each.
     */
    static void jsonStreamProcessing() {
        var engine = new FastjsonEngine();

        // Simulate a JSON Lines stream (one JSON value per line)
        String jsonLines = """
                {"event":"login","user":"alice","ts":1700000001}
                {"event":"purchase","user":"bob","ts":1700000002,"amount":29.99}
                {"event":"login","user":"carol","ts":1700000003}
                {"event":"purchase","user":"alice","ts":1700000004,"amount":49.99}
                {"event":"logout","user":"bob","ts":1700000005}
                """;

        JqProgram filterPurchases = engine.compile(
                "select(.event == \"purchase\") | {user, amount}"
        );

        var inputStream = new ByteArrayInputStream(jsonLines.getBytes(StandardCharsets.UTF_8));
        Stream<JqValue> results = engine.applyToJsonStream(filterPurchases, inputStream);

        System.out.println("  Purchase events from stream:");
        results.forEach(r -> System.out.println("    " + r.toJsonString()));
        System.out.println();
    }

    // ---- 6. Round-trip: JqValue -> fastjson2 ----

    /**
     * Convert jq results back to fastjson2 objects for further processing
     * with your existing fastjson2-based code.
     */
    static void roundTrip() {
        var engine = new FastjsonEngine();

        List<JqValue> results = engine.apply(
                "{name, age}",
                "{\"name\":\"Alice\",\"age\":30,\"email\":\"alice@example.com\"}"
        );

        // Convert JqValue back to fastjson2 JSONObject
        Object fastjsonObj = FastjsonEngine.toFastjson(results.getFirst());

        if (fastjsonObj instanceof JSONObject json) {
            // Now use fastjson2 API as usual
            System.out.println("  Back to JSONObject: " + json.toJSONString());
            System.out.println("  json.getString(\"name\"): " + json.getString("name"));
            System.out.println("  json.getIntValue(\"age\"): " + json.getIntValue("age"));
        }
        System.out.println();
    }

    static void section(String title) {
        System.out.println();
        System.out.println("=== " + title + " ===");
        System.out.println();
    }
}

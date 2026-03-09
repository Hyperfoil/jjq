package io.hyperfoil.tools.jjq.examples;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.evaluator.Environment;
import io.hyperfoil.tools.jjq.value.*;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * Examples of using jjq to query in-memory JSON data from Java applications.
 *
 * <p>This shows how to integrate jjq into applications that already have data
 * in Java objects and want to use jq expressions for flexible querying,
 * filtering, and transformation without writing custom Java code for each query.
 *
 * <p>Run with: {@code mvn -pl jjq-examples exec:exec -Dexec.mainClass=io.hyperfoil.tools.jjq.examples.InMemoryQueryExamples}
 */
public class InMemoryQueryExamples {

    public static void main(String[] args) {
        section("1. Querying application data with jq");
        queryApplicationData();

        section("2. Dynamic filtering with user-provided expressions");
        dynamicFiltering();

        section("3. Using variables for parameterized queries");
        parameterizedQueries();

        section("4. Data transformation pipeline");
        transformationPipeline();

        section("5. Aggregating metrics");
        aggregateMetrics();

        section("6. Config extraction");
        configExtraction();

        section("7. Batch processing with compiled queries");
        batchProcessing();
    }

    // ---- 1. Querying application data ----

    /**
     * Imagine your application has a REST response or in-memory data structure.
     * Use jq expressions to extract exactly what you need without writing
     * custom Java traversal code.
     */
    static void queryApplicationData() {
        // Simulate data that might come from an API, database, or in-memory cache
        JqValue data = JqValues.parse("""
                {
                  "servers": [
                    {"host": "web-1", "cpu": 45.2, "memory": 72.1, "status": "healthy"},
                    {"host": "web-2", "cpu": 89.7, "memory": 91.3, "status": "warning"},
                    {"host": "db-1", "cpu": 23.1, "memory": 55.8, "status": "healthy"},
                    {"host": "db-2", "cpu": 67.4, "memory": 88.9, "status": "warning"},
                    {"host": "cache-1", "cpu": 12.5, "memory": 34.2, "status": "healthy"}
                  ]
                }
                """);

        // Find servers with high CPU
        query("High CPU servers",
                "[.servers[] | select(.cpu > 60) | {host, cpu}]",
                data);

        // Get all hostnames
        query("All hostnames",
                "[.servers[].host]",
                data);

        // Count by status
        query("Count by status",
                ".servers | group_by(.status) | map({status: .[0].status, count: length})",
                data);

        // Average CPU across all servers
        query("Average CPU",
                ".servers | map(.cpu) | add / length",
                data);
    }

    // ---- 2. Dynamic filtering ----

    /**
     * Let users provide jq expressions at runtime for flexible data exploration.
     * The expression is compiled and applied to your data.
     */
    static void dynamicFiltering() {
        JqValue products = JqValues.parse("""
                [
                  {"name": "Laptop", "category": "electronics", "price": 999.99, "stock": 15},
                  {"name": "Mouse", "category": "electronics", "price": 29.99, "stock": 150},
                  {"name": "Desk", "category": "furniture", "price": 349.99, "stock": 8},
                  {"name": "Chair", "category": "furniture", "price": 449.99, "stock": 12},
                  {"name": "Monitor", "category": "electronics", "price": 599.99, "stock": 25},
                  {"name": "Keyboard", "category": "electronics", "price": 79.99, "stock": 200}
                ]
                """);

        // Simulate different user queries against the same dataset
        String[] userQueries = {
                "[.[] | select(.price < 100) | .name]",                           // Cheap items
                "[.[] | select(.category == \"electronics\")] | length",           // Count electronics
                "group_by(.category) | map({category: .[0].category, avg_price: (map(.price) | add / length)})",
                "[.[] | select(.stock < 20)] | sort_by(.stock) | map({name, stock})",  // Low stock
        };

        for (String userQuery : userQueries) {
            query("User query", userQuery, products);
        }
    }

    // ---- 3. Parameterized queries ----

    /**
     * Pass Java variables into jq expressions using the Environment.
     * This is the safe way to inject values — no string concatenation needed.
     */
    static void parameterizedQueries() {
        JqValue employees = JqValues.parse("""
                [
                  {"name": "Alice", "dept": "engineering", "salary": 120000},
                  {"name": "Bob", "dept": "engineering", "salary": 110000},
                  {"name": "Carol", "dept": "marketing", "salary": 95000},
                  {"name": "Dave", "dept": "engineering", "salary": 130000},
                  {"name": "Eve", "dept": "marketing", "salary": 105000}
                ]
                """);

        // Compile once with variable references
        JqProgram byDept = JqProgram.compile("[.[] | select(.dept == $department)]");
        JqProgram aboveSalary = JqProgram.compile("[.[] | select(.salary > $threshold) | .name]");

        // Apply with different variable values
        Environment env1 = new Environment();
        env1.setVariable("department", JqString.of("engineering"));
        List<JqValue> engineers = byDept.applyAll(employees, env1);
        System.out.println("  Engineers: " + engineers.getFirst().toJsonString());

        Environment env2 = new Environment();
        env2.setVariable("department", JqString.of("marketing"));
        List<JqValue> marketers = byDept.applyAll(employees, env2);
        System.out.println("  Marketers: " + marketers.getFirst().toJsonString());

        Environment env3 = new Environment();
        env3.setVariable("threshold", JqNumber.of(115000));
        List<JqValue> highEarners = aboveSalary.applyAll(employees, env3);
        System.out.println("  Earn > $115k: " + highEarners.getFirst().toJsonString());
        System.out.println();
    }

    // ---- 4. Data transformation pipeline ----

    /**
     * Chain multiple jq operations to transform data through a pipeline.
     * Each step is a separate compiled program for clarity and reusability.
     */
    static void transformationPipeline() {
        JqValue rawData = JqValues.parse("""
                {
                  "results": [
                    {"timestamp": "2024-01-15T10:30:00Z", "value": 42.5, "tags": ["prod", "us-east"]},
                    {"timestamp": "2024-01-15T10:31:00Z", "value": 38.1, "tags": ["prod", "us-west"]},
                    {"timestamp": "2024-01-15T10:32:00Z", "value": 55.3, "tags": ["staging", "us-east"]},
                    {"timestamp": "2024-01-15T10:33:00Z", "value": 41.7, "tags": ["prod", "eu-west"]},
                    {"timestamp": "2024-01-15T10:34:00Z", "value": 62.0, "tags": ["prod", "us-east"]}
                  ]
                }
                """);

        // Step 1: Extract only prod results
        JqProgram filterProd = JqProgram.compile(
                "{results: [.results[] | select(.tags | contains([\"prod\"]))]}"
        );

        // Step 2: Compute summary statistics
        JqProgram summarize = JqProgram.compile(
                "{count: (.results | length), avg: (.results | map(.value) | add / length), min: (.results | map(.value) | min), max: (.results | map(.value) | max)}"
        );

        // Apply pipeline
        JqValue prodOnly = filterProd.apply(rawData);
        System.out.println("  After filtering prod: " + prodOnly.toJsonString());

        JqValue summary = summarize.apply(prodOnly);
        System.out.println("  Summary: " + summary.toJsonString());
        System.out.println();
    }

    // ---- 5. Aggregating metrics ----

    /**
     * Use jq's powerful aggregation to compute metrics over structured data.
     */
    static void aggregateMetrics() {
        JqValue logs = JqValues.parse("""
                [
                  {"endpoint": "/api/users", "status": 200, "latency_ms": 45},
                  {"endpoint": "/api/users", "status": 200, "latency_ms": 52},
                  {"endpoint": "/api/users", "status": 500, "latency_ms": 1200},
                  {"endpoint": "/api/orders", "status": 200, "latency_ms": 78},
                  {"endpoint": "/api/orders", "status": 200, "latency_ms": 65},
                  {"endpoint": "/api/orders", "status": 404, "latency_ms": 12},
                  {"endpoint": "/api/users", "status": 200, "latency_ms": 48},
                  {"endpoint": "/api/orders", "status": 200, "latency_ms": 82}
                ]
                """);

        // Per-endpoint stats
        query("Per-endpoint stats",
                "group_by(.endpoint) | map({endpoint: .[0].endpoint, total: length, success: (map(select(.status == 200)) | length), avg_latency: (map(.latency_ms) | add / length | floor)})",
                logs);

        // Error rate
        query("Overall error rate",
                "{total: length, errors: (map(select(.status >= 400)) | length), error_rate: (((map(select(.status >= 400)) | length) * 100 / length | tostring) + \"%\")}",
                logs);
    }

    // ---- 6. Config extraction ----

    /**
     * Extract configuration values from nested JSON config structures.
     */
    static void configExtraction() {
        JqValue config = JqValues.parse("""
                {
                  "app": {
                    "name": "myservice",
                    "version": "2.1.0",
                    "features": {
                      "cache": {"enabled": true, "ttl": 300},
                      "rateLimit": {"enabled": true, "maxRequests": 1000},
                      "darkMode": {"enabled": false}
                    }
                  },
                  "database": {
                    "host": "db.example.com",
                    "port": 5432,
                    "pool": {"min": 5, "max": 20}
                  }
                }
                """);

        // Get all enabled features
        query("Enabled features",
                "[.app.features | to_entries[] | select(.value.enabled) | .key]",
                config);

        // Flatten to dotted paths for logging
        query("Database config",
                ".database | {host, port, pool_max: .pool.max}",
                config);

        // Extract specific nested value with default
        query("Cache TTL (with default)",
                ".app.features.cache.ttl // 60",
                config);
    }

    // ---- 7. Batch processing ----

    /**
     * Process multiple records efficiently with a pre-compiled program.
     */
    static void batchProcessing() {
        // Pre-compile the transformation
        JqProgram transform = JqProgram.compile(
                "{name: .name, total: ([.items[].price] | add), item_count: (.items | length)}"
        );

        // Simulate a batch of order records
        String[] orderRecords = {
                "{\"name\":\"Order-1\",\"items\":[{\"price\":9.99},{\"price\":24.99},{\"price\":14.50}]}",
                "{\"name\":\"Order-2\",\"items\":[{\"price\":49.99}]}",
                "{\"name\":\"Order-3\",\"items\":[{\"price\":9.99},{\"price\":9.99},{\"price\":9.99},{\"price\":9.99}]}",
        };

        System.out.println("  Processing " + orderRecords.length + " orders:");
        for (String record : orderRecords) {
            JqValue input = JqValues.parse(record);
            JqValue result = transform.apply(input);
            System.out.println("    " + result.toJsonString());
        }
        System.out.println();
    }

    // ---- Helpers ----

    static void query(String label, String filter, JqValue data) {
        JqProgram program = JqProgram.compile(filter);
        List<JqValue> results = program.applyAll(data);
        System.out.println("  " + label + ":");
        System.out.println("    jq: " + filter.lines().map(String::trim).reduce((a, b) -> a + " " + b).orElse(filter));
        for (JqValue r : results) {
            System.out.println("    => " + r.toJsonString());
        }
        System.out.println();
    }

    static void section(String title) {
        System.out.println();
        System.out.println("=== " + title + " ===");
        System.out.println();
    }
}

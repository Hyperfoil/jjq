package io.hyperfoil.tools.jjq.benchmark;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.builtin.BuiltinRegistry;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import io.hyperfoil.tools.jjq.vm.VirtualMachine;
import org.openjdk.jmh.annotations.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Allocation-focused benchmark with varied, realistic JSON inputs.
 * Exercises objects, nested structures, string manipulation, arrays of objects,
 * mixed types, and larger payloads to stress the full value-type hierarchy.
 *
 * <p>Run with allocation profiling:
 * <pre>
 *   mvn package -pl jjq-benchmark -DskipTests
 *   java --enable-preview -jar jjq-benchmark/target/jjq-benchmark-*.jar JjqAllocBenchmark \
 *     -prof "async:libPath=.../libasyncProfiler.so;output=text;event=alloc"
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgs = "--enable-preview")
@State(Scope.Benchmark)
public class JjqAllocBenchmark {

    // --- VMs ---
    private VirtualMachine vmDeepField;
    private VirtualMachine vmObjectConstruct;
    private VirtualMachine vmNestedIterate;
    private VirtualMachine vmStringConcat;
    private VirtualMachine vmMixedTypeFilter;
    private VirtualMachine vmKeysValues;
    private VirtualMachine vmLargeReduce;
    private VirtualMachine vmFlattenUnique;
    private VirtualMachine vmConditionalMap;
    private VirtualMachine vmMultiOutput;
    private VirtualMachine vmVariableBind;
    private VirtualMachine vmTryCatch;
    private VirtualMachine vmObjectIterate;
    private VirtualMachine vmToFromJson;
    private VirtualMachine vmChainedPipe;

    // --- Inputs ---
    private JqValue deepNested;        // deeply nested object
    private JqValue userRecords;       // array of user objects
    private JqValue mixedArray;        // array with mixed types
    private JqValue nestedArrays;      // array of arrays
    private JqValue largeIntArray;     // 500-element int array
    private JqValue stringArray;       // array of strings
    private JqValue configObject;      // complex config-like object
    private JqValue jsonStrings;       // array of JSON-encoded strings

    @Setup
    public void setup() {
        BuiltinRegistry builtins = new BuiltinRegistry();

        // --- Build varied JSON inputs ---

        // Deep nested object (3-4 levels, mixed types)
        deepNested = JqValues.parse("""
            {
              "server": {
                "host": "example.com",
                "port": 8080,
                "tls": {
                  "enabled": true,
                  "cert": "/etc/ssl/cert.pem",
                  "protocols": ["TLSv1.2", "TLSv1.3"]
                },
                "limits": {
                  "maxConn": 1000,
                  "timeout": 30,
                  "rateLimit": { "rps": 500, "burst": 50 }
                }
              },
              "database": {
                "url": "jdbc:postgresql://db:5432/app",
                "pool": { "min": 5, "max": 20, "idle": 300 }
              },
              "logging": { "level": "INFO", "format": "json" }
            }
            """);

        // Array of user record objects (20 records)
        var userSb = new StringBuilder("[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) userSb.append(",");
            userSb.append(String.format(
                "{\"id\":%d,\"name\":\"user_%d\",\"age\":%d,\"active\":%s,\"score\":%.1f,\"tags\":[\"tag%d\",\"tag%d\"]}",
                i, i, 20 + (i % 50), i % 3 != 0 ? "true" : "false",
                (i * 7.3), i % 5, (i + 1) % 5
            ));
        }
        userSb.append("]");
        userRecords = JqValues.parse(userSb.toString());

        // Mixed-type array (numbers, strings, booleans, nulls, nested objects)
        mixedArray = JqValues.parse("""
            [1, "hello", true, null, 3.14, {"x": 1}, [1,2,3], "world", false, 42,
             -7, "test", null, {"y": "val"}, 0, "", true, 100, [4,5], "end"]
            """);

        // Nested arrays (for flatten/unique)
        nestedArrays = JqValues.parse("""
            [[1,2,3],[4,5,6],[1,3,5],[7,8,9],[2,4,6],[1,2,3],[10,11,12],
             [3,6,9],[5,10,15],[1,4,7],[2,5,8],[3,6,9],[11,13,17],[4,8,12]]
            """);

        // Large int array (500 elements with values beyond small cache)
        var largeSb = new StringBuilder("[");
        for (int i = 0; i < 500; i++) {
            if (i > 0) largeSb.append(",");
            largeSb.append(i * 7 - 250); // range: -250 to 3243
        }
        largeSb.append("]");
        largeIntArray = JqValues.parse(largeSb.toString());

        // String array
        stringArray = JqValues.parse("""
            ["alice","bob","charlie","dave","eve","frank","grace","heidi",
             "ivan","judy","karl","laura","mallory","nancy","oscar","pat",
             "quinn","rachel","steve","trent","ursula","victor","wendy","xena"]
            """);

        // Complex config-like object with many fields
        configObject = JqValues.parse("""
            {
              "appName": "benchmark",
              "version": "2.1.0",
              "features": {
                "auth": true, "cache": true, "metrics": false,
                "rateLimit": true, "cors": false
              },
              "endpoints": [
                {"path": "/api/users", "method": "GET", "auth": true, "rateLimit": 100},
                {"path": "/api/users", "method": "POST", "auth": true, "rateLimit": 10},
                {"path": "/api/health", "method": "GET", "auth": false, "rateLimit": 1000},
                {"path": "/api/metrics", "method": "GET", "auth": true, "rateLimit": 50},
                {"path": "/api/config", "method": "PUT", "auth": true, "rateLimit": 5}
              ],
              "tags": ["production", "v2", "stable"]
            }
            """);

        // Array of JSON-encoded strings (for fromjson/tojson)
        jsonStrings = JqValues.parse("""
            ["{\\\"a\\\":1}", "{\\\"b\\\":2}", "{\\\"c\\\":3}", "{\\\"d\\\":4}",
             "{\\\"e\\\":5}", "{\\\"f\\\":6}", "{\\\"g\\\":7}", "{\\\"h\\\":8}"]
            """);

        // --- Compile programs and create VMs ---

        // Deep field access with pipes: .server.tls.protocols | length
        vmDeepField = createVM(".server.limits.rateLimit.rps", builtins);

        // Object construction: {name, score, active}
        vmObjectConstruct = createVM("[.[] | {name: .name, score: .score, active: .active}]", builtins);

        // Nested iterate: flatten then map
        vmNestedIterate = createVM("[.[] | .[] | . * 2]", builtins);

        // String operations via pipe
        vmStringConcat = createVM("[.[] | . + \"-suffix\"]", builtins);

        // Mixed-type filter with type checking
        vmMixedTypeFilter = createVM("[.[] | select(type == \"number\") | . * 2]", builtins);

        // keys/values on complex object
        vmKeysValues = createVM(".features | keys", builtins);

        // Large reduce (500 elements, values beyond cache)
        vmLargeReduce = createVM("reduce .[] as $x (0; . + $x)", builtins);

        // Flatten + unique on nested arrays
        vmFlattenUnique = createVM("flatten | unique", builtins);

        // Conditional map with if/then/else
        vmConditionalMap = createVM("[.[] | if .active then .name else .name + \" (inactive)\" end]", builtins);

        // Multi-output: comma operator producing multiple results
        vmMultiOutput = createVM(".server.host, .server.port, .database.url", builtins);

        // Variable binding: . as $x | ...
        vmVariableBind = createVM("[.[] | . as $x | $x * $x]", builtins);

        // Try-catch on mixed data
        vmTryCatch = createVM("[.[] | (. * 2)? // \"skip\"]", builtins);

        // Object iteration: keys and values from object
        vmObjectIterate = createVM("[.endpoints[] | .path]", builtins);

        // tojson round-trip
        vmToFromJson = createVM("[.[] | fromjson | keys | .[0]]", builtins);

        // Chained pipe with multiple transforms
        vmChainedPipe = createVM("[.[] | select(.auth) | .path] | unique | length", builtins);
    }

    private VirtualMachine createVM(String expr, BuiltinRegistry builtins) {
        JqProgram prog = JqProgram.compile(expr, builtins);
        return new VirtualMachine(prog.getBytecode(), builtins);
    }

    // ======================================================================
    //  Deep field access on nested object
    // ======================================================================
    @Benchmark
    public List<JqValue> deepField() {
        return vmDeepField.execute(deepNested);
    }

    // ======================================================================
    //  Object construction from array of records
    // ======================================================================
    @Benchmark
    public List<JqValue> objectConstruct() {
        return vmObjectConstruct.execute(userRecords);
    }

    // ======================================================================
    //  Nested iterate (flatten-like): [[1,2],[3,4]] → [2,4,6,8]
    // ======================================================================
    @Benchmark
    public List<JqValue> nestedIterate() {
        return vmNestedIterate.execute(nestedArrays);
    }

    // ======================================================================
    //  String concatenation on array of strings
    // ======================================================================
    @Benchmark
    public List<JqValue> stringConcat() {
        return vmStringConcat.execute(stringArray);
    }

    // ======================================================================
    //  Mixed-type filter: select numbers from mixed array
    // ======================================================================
    @Benchmark
    public List<JqValue> mixedTypeFilter() {
        return vmMixedTypeFilter.execute(mixedArray);
    }

    // ======================================================================
    //  Keys extraction from object
    // ======================================================================
    @Benchmark
    public List<JqValue> keysValues() {
        return vmKeysValues.execute(configObject);
    }

    // ======================================================================
    //  Large reduce (500 elements, values beyond cache range)
    // ======================================================================
    @Benchmark
    public List<JqValue> largeReduce() {
        return vmLargeReduce.execute(largeIntArray);
    }

    // ======================================================================
    //  Flatten + unique on nested arrays
    // ======================================================================
    @Benchmark
    public List<JqValue> flattenUnique() {
        return vmFlattenUnique.execute(nestedArrays);
    }

    // ======================================================================
    //  Conditional map with if/then/else on user records
    // ======================================================================
    @Benchmark
    public List<JqValue> conditionalMap() {
        return vmConditionalMap.execute(userRecords);
    }

    // ======================================================================
    //  Multi-output via comma operator on nested object
    // ======================================================================
    @Benchmark
    public List<JqValue> multiOutput() {
        return vmMultiOutput.execute(deepNested);
    }

    // ======================================================================
    //  Variable binding: [.[] | . as $x | $x * $x]
    // ======================================================================
    @Benchmark
    public List<JqValue> variableBind() {
        return vmVariableBind.execute(largeIntArray);
    }

    // ======================================================================
    //  Try-catch on mixed data
    // ======================================================================
    @Benchmark
    public List<JqValue> tryCatch() {
        return vmTryCatch.execute(mixedArray);
    }

    // ======================================================================
    //  Object iteration: extract paths from endpoints
    // ======================================================================
    @Benchmark
    public List<JqValue> objectIterate() {
        return vmObjectIterate.execute(configObject);
    }

    // ======================================================================
    //  fromjson + keys: parse JSON strings
    // ======================================================================
    @Benchmark
    public List<JqValue> toFromJson() {
        return vmToFromJson.execute(jsonStrings);
    }

    // ======================================================================
    //  Chained pipe: select + unique + length
    // ======================================================================
    @Benchmark
    public List<JqValue> chainedPipe() {
        return vmChainedPipe.execute(configObject);
    }
}

# jjq User Guide

This guide covers using jjq both as a command-line tool and as an embedded library in Java applications.

## Table of Contents

- [CLI Usage](#cli-usage)
  - [Installation](#installation)
  - [Basic Usage](#basic-usage)
  - [Options Reference](#options-reference)
  - [CLI Examples](#cli-examples)
- [Java Library](#java-library)
  - [Getting Started](#getting-started)
  - [Core Concepts](#core-concepts)
  - [Choosing the Right API Method](#choosing-the-right-api-method)
  - [Working with Values](#working-with-values)
  - [Variables and Parameterized Queries](#variables-and-parameterized-queries)
  - [Error Handling](#error-handling)
  - [FastjsonEngine (High-Level API)](#fastjsonengine-high-level-api)
- [Enterprise Integration](#enterprise-integration)
  - [Thread Safety and Concurrency](#thread-safety-and-concurrency)
  - [Performance Best Practices](#performance-best-practices)
  - [REST API Transformation Layer](#rest-api-transformation-layer)
  - [Message Queue Processing](#message-queue-processing)
  - [Configuration Extraction](#configuration-extraction)
  - [User-Defined Query Endpoints](#user-defined-query-endpoints)
  - [Monitoring and Metrics Aggregation](#monitoring-and-metrics-aggregation)
  - [Data Pipeline Processing](#data-pipeline-processing)
  - [Batch Processing](#batch-processing)
- [jq Language Reference](#jq-language-reference)
- [Known Limitations](#known-limitations)
- [API Reference](#api-reference)

---

## CLI Usage

### Installation

Build the CLI from source:

```bash
# Requires Java 21+
mvn package -pl jjq-cli -DskipTests

# The executable JAR is at:
java -jar jjq-cli/target/jjq-cli-0.1.0-SNAPSHOT.jar '.name' <<< '{"name":"Alice"}'
```

You can create a shell alias for convenience:

```bash
alias jjq='java -jar /path/to/jjq-cli-0.1.0-SNAPSHOT.jar'
```

#### Native Binary (GraalVM)

For instant startup (~3ms vs ~100ms on JVM), build a native binary using GraalVM:

```bash
# Requires GraalVM 21+ (or any JDK with native-image installed)
mvn package -pl jjq-core,jjq-cli -Pnative -DskipTests

# The native binary is at jjq-cli/target/jjq
./jjq-cli/target/jjq '.name' <<< '{"name":"Alice"}'
```

The native binary is a self-contained ~17MB executable with no JVM dependency. Copy it anywhere on your `PATH`:

```bash
cp jjq-cli/target/jjq ~/.local/bin/jjq
```

The CLI module has zero external dependencies (only `jjq-core`), so native-image compilation requires no reflection configuration or resource bundles.

### Basic Usage

```
jjq [OPTIONS] FILTER [FILE...]
```

jjq reads JSON from files or stdin, applies the filter expression, and writes results to stdout. Like `jq`, it supports **JSONL / NDJSON** — multiple whitespace-separated JSON values are each processed independently through the filter.

```bash
# From stdin
echo '{"name":"Alice","age":30}' | jjq '.name'
# "Alice"

# From a file
jjq '.users[]' data.json

# From multiple files
jjq '.status' server1.json server2.json server3.json

# JSONL / NDJSON (one JSON value per line)
printf '{"name":"Alice"}\n{"name":"Bob"}\n{"name":"Charlie"}\n' | jjq '.name'
# "Alice"
# "Bob"
# "Charlie"

# Whitespace-separated values also work (not just newlines)
echo '1 2 3' | jjq '. * 10'
# 10
# 20
# 30
```

### Options Reference

| Option | Description |
|--------|-------------|
| `-c, --compact-output` | Compact output (no pretty-printing) |
| `-r, --raw-output` | Output raw strings without JSON quotes |
| `-R, --raw-input` | Read each input line as a JSON string |
| `-s, --slurp` | Collect all inputs into a single array |
| `-n, --null-input` | Use `null` as input (ignore stdin/files) |
| `-e, --exit-status` | Set exit status based on output |
| `-S, --sort-keys` | Sort object keys alphabetically in output |
| `-j, --join-output` | Don't print newlines between outputs |
| `-f, --from-file FILE` | Read filter expression from a file |
| `-C, --color-output` | Force colored output |
| `-M, --monochrome-output` | Disable colored output |
| `--arg NAME VALUE` | Set `$NAME` to a string value |
| `--argjson NAME JSON` | Set `$NAME` to a parsed JSON value |
| `--tab` | Use tab characters for indentation |
| `--indent N` | Use N spaces for indentation (default: 2) |

### CLI Examples

**Field access and navigation:**

```bash
# Simple field
echo '{"name":"Alice","age":30}' | jjq '.name'
# "Alice"

# Nested fields
echo '{"a":{"b":{"c":42}}}' | jjq '.a.b.c'
# 42

# Raw string output (strip quotes)
echo '{"name":"Alice"}' | jjq -r '.name'
# Alice
```

**Array operations:**

```bash
# Iterate elements
echo '[1,2,3]' | jjq '.[]'
# 1
# 2
# 3

# Index and slice
echo '[10,20,30,40,50]' | jjq '.[2]'
# 30

echo '[10,20,30,40,50]' | jjq '.[1:3]'
# [20,30]

# Last two elements
echo '[10,20,30,40,50]' | jjq '.[-2:]'
# [40,50]
```

**Filtering and transformation:**

```bash
# Filter with select
echo '[1,2,3,4,5,6]' | jjq '[.[] | select(. > 3)]'
# [4,5,6]

# Transform each element
echo '[1,2,3,4,5]' | jjq 'map(. * 2)'
# [2,4,6,8,10]

# Filter objects by field value
echo '[{"name":"Alice","age":30},{"name":"Bob","age":25}]' | jjq '[.[] | select(.age >= 30)]'
# [{"name":"Alice","age":30}]
```

**Object construction:**

```bash
# Pick specific fields
echo '{"name":"Alice","email":"a@b.com","age":30,"role":"admin"}' | jjq '{name, email}'
# {"name":"Alice","email":"a@b.com"}

# Rename and reshape
echo '{"first":"Alice","last":"Smith","age":30}' | jjq '{full_name: (.first + " " + .last), age}'
# {"full_name":"Alice Smith","age":30}
```

**Aggregation:**

```bash
# Sum
echo '[1,2,3,4,5]' | jjq 'add'
# 15

echo '[1,2,3,4,5]' | jjq 'reduce .[] as $x (0; . + $x)'
# 15

# Group and count
echo '[{"dept":"eng","name":"A"},{"dept":"sales","name":"B"},{"dept":"eng","name":"C"}]' | \
  jjq 'group_by(.dept) | map({dept: .[0].dept, count: length})'
# [{"dept":"eng","count":2},{"dept":"sales","count":1}]
```

**Variables:**

```bash
# String variable
echo '{"items":[1,2,3]}' | jjq --arg key items '.[$key]'
# [1,2,3]

# JSON variable
echo '[1,2,3,4,5]' | jjq --argjson min 3 '[.[] | select(. >= $min)]'
# [3,4,5]
```

**Working with JSONL / NDJSON:**

```bash
# Process a JSONL file (one JSON object per line)
printf '{"name":"Alice","score":90}\n{"name":"Bob","score":75}\n{"name":"Charlie","score":85}\n' > data.jsonl
jjq 'select(.score >= 80) | .name' data.jsonl
# "Alice"
# "Charlie"

# Slurp JSONL into an array for aggregation
jjq -s 'map(.score) | add / length' data.jsonl
# 83.33333333333333

# Multi-line JSON values in a stream (not just single-line)
printf '{\n  "a": 1\n}\n{\n  "b": 2\n}\n' | jjq -c '.'
# {"a":1}
# {"b":2}

# Mixed types in a stream
printf '"hello"\n42\ntrue\n[1,2]\n' | jjq -c 'type'
# "string"
# "number"
# "boolean"
# "array"
```

**Working with files and streams:**

```bash
# Read filter from file
echo '[.[] | select(.active)] | length' > filter.jq
jjq -f filter.jq users.json

# Slurp multiple inputs into one array
echo '1' > a.json
echo '2' > b.json
jjq -s 'add' a.json b.json
# 3

# Compact output (for piping)
echo '{"a":1,"b":2}' | jjq -c '.a'
# 1

# Null input (generate data)
jjq -n '{now: now | todate}'
```

---

## Java Library

### Getting Started

Add jjq to your Maven project:

```xml
<dependency>
    <groupId>io.hyperfoil.tools</groupId>
    <artifactId>jjq-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

`jjq-core` has **zero external dependencies**. For fastjson2 integration, add:

```xml
<dependency>
    <groupId>io.hyperfoil.tools</groupId>
    <artifactId>jjq-fastjson2</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### Core Concepts

jjq follows a **compile-once, apply-many** pattern. A `JqProgram` is compiled from a jq expression string and can then be applied to any number of inputs. Programs are immutable and thread-safe.

```java
import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;

// 1. Compile the program (do this once)
JqProgram program = JqProgram.compile(".users[] | {name, email}");

// 2. Parse or build input
JqValue input = JqValues.parse("""
    {"users": [
        {"name": "Alice", "email": "alice@example.com"},
        {"name": "Bob", "email": "bob@example.com"}
    ]}
    """);

// 3. Apply and get results
JqValue first = program.apply(input);          // first result only
List<JqValue> all = program.applyAll(input);   // all results
```

### Choosing the Right API Method

`JqProgram` provides several execution methods. The primary API uses the **bytecode VM**, which is the fastest execution engine (up to 16x faster than alternatives). Always prefer the VM methods.

| Method | Engine | Returns | Best for |
|--------|--------|---------|----------|
| **`apply(input)`** | VM | First `JqValue` | Single-output filters (field access, arithmetic, reduce, object construction). **Zero-allocation** for simple programs. |
| **`applyAll(input)`** | VM | `List<JqValue>` | Multi-output filters (`.[]`, generators, comma expressions) |
| **`apply(input, output)`** | VM | void (streams to `Consumer`) | Processing results without collecting into a list |

**Use `apply()` by default.** It returns a single `JqValue` with zero allocation overhead for programs that produce exactly one output. This covers the vast majority of use cases: field access, arithmetic, object construction, `reduce`, array construction (`[...]`), and any filter wrapped in `[...]`.

```java
// RECOMMENDED: apply() for single-output programs
JqProgram getName = JqProgram.compile(".name");
JqValue name = getName.apply(input);  // zero-allocation, returns JqValue directly

// Use applyAll() only when multiple outputs are expected
JqProgram iterate = JqProgram.compile(".users[]");
List<JqValue> users = iterate.applyAll(input);  // returns all outputs

// Stream results without collecting
JqProgram stream = JqProgram.compile(".events[]");
stream.apply(input, event -> processEvent(event));
```

**How to know which method to use:**

- If your filter is wrapped in `[...]` (array construction), it always produces one output — use `apply()`.
- Filters like `.field`, `.a.b.c`, `. + 1`, `reduce`, `{key: .val}` produce one output — use `apply()`.
- Filters like `.[]`, `.a, .b`, `range(n)` produce multiple outputs — use `applyAll()`.
- When in doubt, use `applyAll()` — it works for any number of outputs.

### Processing Multiple Inputs (JSONL-style)

In enterprise applications, you often have multiple JSON objects in memory — from a message queue, database results, or API responses — that you want to process through the same filter. jjq supports this natively with multi-input methods that reuse a single VM instance for efficiency.

**Parsing JSONL strings:**

```java
// Parse a JSONL / NDJSON string into multiple values
List<JqValue> inputs = JqValues.parseAll("""
    {"name":"Alice","age":30}
    {"name":"Bob","age":25}
    {"name":"Charlie","age":35}
    """);
// Returns 3 separate JqValue objects
```

**Processing multiple in-memory values:**

```java
JqProgram program = JqProgram.compile("select(.age >= 30) | .name");

// Option 1: Collect all results into a list
List<JqValue> inputs = List.of(user1, user2, user3);
List<JqValue> results = program.applyAll(inputs);

// Option 2: Stream results lazily
program.stream(inputs).forEach(name ->
    System.out.println(name.stringValue())
);

// Option 3: Callback for each result
program.applyAll(inputs, result ->
    sendToDownstream(result)
);
```

**With variables:**

```java
Environment env = new Environment();
env.setVariable("minAge", JqNumber.of(30));

JqProgram program = JqProgram.compile("select(.age >= $minAge)");
List<JqValue> results = program.applyAll(inputs, env);
```

**Why use `applyAll(Iterable)` instead of looping with `apply()`?**

The multi-input methods create **one** `VirtualMachine` and reuse it across all inputs. Calling `apply()` or `applyAll(singleInput)` in a loop creates a new VM each time, re-allocating stacks and re-analyzing the program. For high-throughput processing (thousands of records), the multi-input API is measurably faster.

```java
// GOOD: single VM reused across all inputs
List<JqValue> results = program.applyAll(inputs);

// ALSO FINE but slower: new VM per input
List<JqValue> results = new ArrayList<>();
for (JqValue input : inputs) {
    results.addAll(program.applyAll(input));  // new VM each time
}
```

### Working with Values

jjq uses its own value types that map directly to JSON:

| JSON Type | jjq Type | Create | Extract |
|-----------|----------|--------|---------|
| `null` | `JqNull` | `JqNull.NULL` | `value.isNull()` |
| `true`/`false` | `JqBoolean` | `JqBoolean.of(true)` | `value.booleanValue()` |
| numbers | `JqNumber` | `JqNumber.of(42)`, `JqNumber.of(3.14)` | `value.longValue()`, `value.doubleValue()` |
| strings | `JqString` | `JqString.of("hello")` | `value.stringValue()` |
| arrays | `JqArray` | `JqArray.of(elem1, elem2)` | `value.arrayValue()` (returns `List<JqValue>`) |
| objects | `JqObject` | `JqObject.of(map)` | `value.objectValue()` (returns `Map<String, JqValue>`) |

**Parsing JSON strings:**

```java
JqValue value = JqValues.parse("{\"name\":\"Alice\",\"age\":30}");
```

**Building values programmatically:**

```java
// Build without parsing JSON — useful when data comes from Java objects
JqValue user = JqObject.of(Map.of(
    "name", JqString.of("Alice"),
    "age", JqNumber.of(30),
    "roles", JqArray.of(JqString.of("admin"), JqString.of("user"))
));
```

**Extracting values from results:**

```java
JqProgram program = JqProgram.compile("{name, age}");
JqValue result = program.apply(input);

// Type-check before extracting
if (result.isObject()) {
    Map<String, JqValue> obj = result.objectValue();
    String name = obj.get("name").stringValue();
    long age = obj.get("age").longValue();
}

// Or use JqObject.get() which returns JqNull for missing keys
JqValue nameVal = ((JqObject) result).get("name");  // never null, returns JqNull.NULL if missing
```

**Type checking:**

```java
JqValue val = program.apply(input);

switch (val.type()) {
    case NULL    -> handleNull();
    case BOOLEAN -> handleBoolean(val.booleanValue());
    case NUMBER  -> handleNumber(val.longValue());
    case STRING  -> handleString(val.stringValue());
    case ARRAY   -> handleArray(val.arrayValue());
    case OBJECT  -> handleObject(val.objectValue());
}

// Or use pattern matching
if (val instanceof JqString s) {
    System.out.println(s.stringValue());
} else if (val instanceof JqNumber n) {
    System.out.println(n.longValue());
}
```

**Value properties:**

- All `JqValue` instances are **immutable** — safe to cache, store, and share.
- `JqNumber` uses a `long` fast-path internally, falling back to `BigDecimal` for decimals. NaN and Infinity are supported.
- `JqObject` preserves insertion order (backed by `LinkedHashMap`).
- `JqArray` is backed by an unmodifiable `List<JqValue>`.

### Variables and Parameterized Queries

Use `Environment` to pass variables into jq expressions. This is the safe way to inject values — no string concatenation or injection risk.

```java
import io.hyperfoil.tools.jjq.evaluator.Environment;

JqProgram query = JqProgram.compile("[.[] | select(.dept == $department)]");

Environment env = new Environment();
env.setVariable("department", JqString.of("engineering"));

JqValue result = query.apply(input, env);
```

Variables can be any `JqValue` type:

```java
env.setVariable("name", JqString.of("Alice"));        // string
env.setVariable("threshold", JqNumber.of(100));        // number
env.setVariable("tags", JqArray.of(                    // array
    JqString.of("prod"), JqString.of("us-east")
));
env.setVariable("active", JqBoolean.TRUE);             // boolean
```

**Important:** `Environment` objects are **not thread-safe**. Create a new `Environment` per request or per thread. The `JqProgram` itself is thread-safe — only the `Environment` needs to be separate.

### Error Handling

jjq uses unchecked exceptions. All exceptions extend `RuntimeException`.

```java
import io.hyperfoil.tools.jjq.evaluator.JqException;
import io.hyperfoil.tools.jjq.parser.ParseException;
import io.hyperfoil.tools.jjq.value.JqTypeError;

// Compile-time errors (bad syntax)
try {
    JqProgram.compile(".foo ||| .bar");
} catch (ParseException e) {
    // Invalid filter syntax
    System.err.println("Parse error: " + e.getMessage());
}

// Runtime errors (type mismatches, missing fields on wrong types)
try {
    JqValue result = program.apply(input);
} catch (JqTypeError e) {
    // e.g., trying to index a number with a string
    System.err.println("Type error: " + e.getMessage());
} catch (JqException e) {
    // General runtime error
    System.err.println("jq error: " + e.getMessage());
}
```

**For user-facing applications**, wrap compilation and execution separately:

```java
public JqProgram compileUserFilter(String filter) {
    try {
        return JqProgram.compile(filter);
    } catch (ParseException e) {
        throw new BadRequestException("Invalid filter: " + e.getMessage());
    }
}

public JqValue executeFilter(JqProgram program, JqValue input) {
    try {
        return program.apply(input);
    } catch (JqException e) {
        throw new ProcessingException("Filter execution failed: " + e.getMessage());
    }
}
```

### FastjsonEngine (High-Level API)

If your application already uses fastjson2, the `FastjsonEngine` provides convenient integration:

```java
import io.hyperfoil.tools.jjq.fastjson2.FastjsonEngine;

FastjsonEngine engine = new FastjsonEngine();

// One-liner: compile + parse + apply
List<JqValue> results = engine.apply(".name", "{\"name\":\"Alice\"}");

// Get results as JSON strings
List<String> jsonStrings = engine.applyToStrings("[.[] | . * 2]", "[1,2,3]");
```

**Byte buffer processing** (no intermediate String creation):

```java
byte[] jsonBytes = readFromNetwork();
JqProgram program = engine.compile("{loc: .location, temp: .temperature}");
byte[] resultBytes = engine.applyToBytes(program, jsonBytes);
writeToNetwork(resultBytes);
```

**JSON Lines / NDJSON stream processing:**

```java
JqProgram filter = engine.compile("select(.level == \"ERROR\") | {msg, ts}");
InputStream logStream = openLogFile();

Stream<JqValue> errors = engine.applyToJsonStream(filter, logStream);
errors.forEach(err -> alertOnError(err));
```

**Lazy conversion** (for large documents where you only access a subset):

```java
// Only converts nested values when actually accessed by the filter
JqValue lazy = FastjsonEngine.fromJsonLazy(largeJsonString);
JqValue id = JqProgram.compile(".metadata.id").apply(lazy);
// The rest of the document (e.g., a 10MB payload array) was never converted
```

**fastjson2 interop:**

```java
// Convert fastjson2 objects to JqValue
JSONObject obj = JSONObject.parseObject(json);
JqValue input = FastjsonEngine.fromFastjson(obj);

// Convert results back to fastjson2
JqValue result = program.apply(input);
JSONObject output = (JSONObject) FastjsonEngine.toFastjson(result);
```

---

## Enterprise Integration

### Thread Safety and Concurrency

`JqProgram` is the central thread-safety primitive in jjq. Understanding what is safe to share and what must be per-thread is critical for server applications.

**Thread-safe (share freely):**
- `JqProgram` — immutable after compilation; share across all threads
- All `JqValue` instances — immutable by design
- `BuiltinRegistry.getDefault()` — singleton, read-only after initialization
- `FastjsonEngine` — stateless, safe to share

**Not thread-safe (create per request/thread):**
- `Environment` — mutable variable store; create a new instance per request

**Pattern for concurrent use:**

```java
public class JqService {
    // Compile once at startup — thread-safe, share across all requests
    private final JqProgram extractUser = JqProgram.compile("{name, email, role}");
    private final JqProgram filterActive = JqProgram.compile("[.[] | select(.active)]");
    private final JqProgram summarize = JqProgram.compile(
        "{total: length, by_role: (group_by(.role) | map({role: .[0].role, count: length}))}"
    );

    public JqValue getUser(JqValue data) {
        return extractUser.apply(data);  // safe to call concurrently
    }

    public JqValue queryByDept(JqValue data, String dept) {
        JqProgram query = JqProgram.compile("[.[] | select(.dept == $dept)]");
        Environment env = new Environment();  // per-request
        env.setVariable("dept", JqString.of(dept));
        return query.apply(data, env);
    }
}
```

### Performance Best Practices

1. **Always use `apply()` over `applyAll()` when you expect a single result.** The `apply()` method uses a zero-allocation code path in the VM for single-output programs like field access, arithmetic, reduce, and array/object construction. This is measurably faster.

2. **Compile programs once and reuse.** Compilation parses the expression and generates bytecode. Reusing the compiled `JqProgram` avoids this cost on every request.

    ```java
    // GOOD: compile once
    private static final JqProgram TRANSFORM = JqProgram.compile("{name, age}");

    public JqValue transform(JqValue input) {
        return TRANSFORM.apply(input);
    }

    // BAD: re-compiles on every call
    public JqValue transformSlow(JqValue input) {
        return JqProgram.compile("{name, age}").apply(input);
    }
    ```

3. **Build `JqValue` directly instead of parsing JSON strings** when data originates from Java objects. Parsing JSON is fast but building values directly avoids the round-trip.

    ```java
    // GOOD: build directly from Java data
    JqValue input = JqObject.of(Map.of(
        "name", JqString.of(user.getName()),
        "score", JqNumber.of(user.getScore())
    ));

    // ALSO FINE: parse JSON (convenient for external data)
    JqValue input = JqValues.parse(jsonString);
    ```

4. **Use `FastjsonEngine.fromJsonLazy()` for large documents** where only a subset of fields are accessed. This defers conversion of nested arrays and objects until they are actually traversed.

5. **Use byte buffer APIs** (`FastjsonEngine.applyToBytes()`) for network services to avoid `String` allocation for large payloads.

6. **Wrap multiple outputs in `[...]` when possible** to get a single array result and use `apply()` instead of `applyAll()`.

    ```java
    // Prefer this (single output, use apply())
    JqProgram prog = JqProgram.compile("[.users[] | .name]");
    JqValue names = prog.apply(input);  // returns ["Alice","Bob"]

    // Over this (multiple outputs, requires applyAll())
    JqProgram prog = JqProgram.compile(".users[] | .name");
    List<JqValue> names = prog.applyAll(input);  // returns ["Alice", "Bob"] as separate items
    ```

### REST API Transformation Layer

Use jjq to build flexible API response transformations without hardcoding field mappings:

```java
@Path("/api/data")
public class DataResource {

    // Pre-compiled transforms for known use cases
    private static final JqProgram SUMMARY = JqProgram.compile(
        "{total: (.items | length), revenue: ([.items[].price] | add)}"
    );

    @GET
    @Path("/summary")
    public Response getSummary() {
        JqValue data = loadData();
        JqValue summary = SUMMARY.apply(data);
        return Response.ok(summary.toJsonString()).build();
    }

    // Let clients specify their own projection
    @GET
    @Path("/query")
    public Response query(@QueryParam("filter") String filter) {
        // Validate and compile the user's filter
        JqProgram program;
        try {
            program = JqProgram.compile(filter);
        } catch (ParseException e) {
            return Response.status(400)
                .entity("{\"error\":\"Invalid filter: " + e.getMessage() + "\"}")
                .build();
        }

        try {
            JqValue data = loadData();
            JqValue result = program.apply(data);
            return Response.ok(result.toJsonString()).build();
        } catch (JqException e) {
            return Response.status(422)
                .entity("{\"error\":\"Filter execution failed: " + e.getMessage() + "\"}")
                .build();
        }
    }
}
```

### Message Queue Processing

Apply jq filters to messages from Kafka, RabbitMQ, or other message queues:

```java
public class MessageProcessor {

    private final JqProgram extractPayload = JqProgram.compile(".payload");
    private final JqProgram routingFilter = JqProgram.compile(
        "select(.headers.type == $msgType) | .payload"
    );

    public void processMessage(byte[] messageBytes) {
        // Use byte buffer processing for efficiency
        FastjsonEngine engine = new FastjsonEngine();
        JqValue message = FastjsonEngine.fromJson(new String(messageBytes));

        JqValue payload = extractPayload.apply(message);
        handlePayload(payload);
    }

    public void processFiltered(JqValue message, String messageType) {
        Environment env = new Environment();
        env.setVariable("msgType", JqString.of(messageType));

        List<JqValue> results = routingFilter.applyAll(message, env);
        // Empty list means the message didn't match the filter (select returned empty)
        for (JqValue payload : results) {
            handlePayload(payload);
        }
    }
}
```

### Configuration Extraction

Use jq expressions to navigate complex configuration structures:

```java
public class ConfigService {

    private final JqValue config;

    public ConfigService(String configJson) {
        this.config = JqValues.parse(configJson);
    }

    // Type-safe config extraction with jq
    public String getString(String jqPath) {
        JqValue val = JqProgram.compile(jqPath).apply(config);
        return val.isString() ? val.stringValue() : null;
    }

    public long getLong(String jqPath, long defaultValue) {
        JqValue val = JqProgram.compile(jqPath).apply(config);
        return val.isNumber() ? val.longValue() : defaultValue;
    }

    public boolean isEnabled(String feature) {
        JqProgram check = JqProgram.compile(
            ".features." + feature + ".enabled // false"
        );
        return check.apply(config).booleanValue();
    }

    public List<String> getEnabledFeatures() {
        JqProgram query = JqProgram.compile(
            "[.features | to_entries[] | select(.value.enabled) | .key]"
        );
        JqValue result = query.apply(config);
        return result.arrayValue().stream()
            .map(JqValue::stringValue)
            .toList();
    }
}
```

### User-Defined Query Endpoints

Let users save and execute their own jq queries against application data. This is useful for dashboards, reporting tools, and data exploration features.

```java
public class QueryEngine {

    // Cache compiled programs to avoid re-compilation
    private final ConcurrentHashMap<String, JqProgram> programCache = new ConcurrentHashMap<>();

    public JqProgram getOrCompile(String expression) {
        return programCache.computeIfAbsent(expression, expr -> {
            try {
                return JqProgram.compile(expr);
            } catch (ParseException e) {
                throw new IllegalArgumentException("Invalid query: " + e.getMessage(), e);
            }
        });
    }

    public String executeQuery(String expression, String inputJson,
                                Map<String, String> variables) {
        JqProgram program = getOrCompile(expression);
        JqValue input = JqValues.parse(inputJson);

        Environment env = new Environment();
        for (var entry : variables.entrySet()) {
            env.setVariable(entry.getKey(), JqString.of(entry.getValue()));
        }

        JqValue result = program.apply(input, env);
        return result.toJsonString();
    }
}
```

### Monitoring and Metrics Aggregation

Use jq to compute metrics from structured monitoring data:

```java
public class MetricsAggregator {

    private static final JqProgram ERROR_RATE = JqProgram.compile("""
        {
          total: length,
          errors: (map(select(.status >= 400)) | length),
          error_pct: ((map(select(.status >= 400)) | length) * 100.0 / length)
        }
        """);

    private static final JqProgram LATENCY_STATS = JqProgram.compile("""
        {
          p50: (sort | .[length / 2 | floor]),
          p95: (sort | .[length * 0.95 | floor]),
          p99: (sort | .[length * 0.99 | floor]),
          avg: (add / length),
          max: max
        }
        """);

    private static final JqProgram PER_ENDPOINT = JqProgram.compile("""
        group_by(.endpoint) | map({
          endpoint: .[0].endpoint,
          count: length,
          avg_latency: (map(.latency_ms) | add / length | floor),
          error_count: (map(select(.status >= 400)) | length)
        })
        """);

    public JqValue computeErrorRate(JqValue requestLogs) {
        return ERROR_RATE.apply(requestLogs);
    }

    public JqValue computeLatencyStats(JqValue latencyValues) {
        return LATENCY_STATS.apply(latencyValues);
    }

    public JqValue computePerEndpoint(JqValue requestLogs) {
        return PER_ENDPOINT.apply(requestLogs);
    }
}
```

### Data Pipeline Processing

Chain jq transformations as a lightweight data pipeline:

```java
public class DataPipeline {

    private final List<JqProgram> stages;

    public DataPipeline(String... expressions) {
        this.stages = Arrays.stream(expressions)
            .map(JqProgram::compile)
            .toList();
    }

    public JqValue process(JqValue input) {
        JqValue current = input;
        for (JqProgram stage : stages) {
            current = stage.apply(current);
        }
        return current;
    }

    // Example usage
    public static void main(String[] args) {
        DataPipeline pipeline = new DataPipeline(
            // Stage 1: Filter to production data
            "{results: [.results[] | select(.tags | contains([\"prod\"]))]}",
            // Stage 2: Extract metrics
            ".results | map({host: .host, cpu: .cpu})",
            // Stage 3: Compute summary
            "{count: length, avg_cpu: (map(.cpu) | add / length), max_cpu: (map(.cpu) | max)}"
        );

        JqValue input = JqValues.parse(loadMonitoringData());
        JqValue summary = pipeline.process(input);
    }
}
```

### Batch Processing

Process large numbers of records efficiently using the multi-input API, which reuses a single VM across all inputs:

```java
public class BatchProcessor {

    private final JqProgram transform;

    public BatchProcessor(String expression) {
        this.transform = JqProgram.compile(expression);
    }

    // Process all records through a single VM (most efficient)
    public List<JqValue> processAll(List<JqValue> records) {
        return transform.applyAll(records);
    }

    // Stream results without collecting
    public void processAll(List<JqValue> records, Consumer<JqValue> handler) {
        transform.applyAll(records, handler);
    }

    // Process records in parallel (JqProgram is thread-safe)
    public List<JqValue> processParallel(List<JqValue> records) {
        return records.parallelStream()
            .map(transform::apply)
            .toList();
    }

    // Parse and process a JSONL string
    public List<JqValue> processJsonl(String jsonlContent) {
        List<JqValue> inputs = JqValues.parseAll(jsonlContent);
        return transform.applyAll(inputs);
    }

    // Stream processing with fastjson2
    public void processStream(InputStream ndjsonInput, Consumer<JqValue> handler) {
        FastjsonEngine engine = new FastjsonEngine();
        engine.applyToJsonStream(transform, ndjsonInput)
            .forEach(handler);
    }
}
```

---

## jq Language Reference

This is a quick reference for jq syntax supported by jjq. For the complete jq manual, see [jqlang.github.io/jq/manual](https://jqlang.github.io/jq/manual/).

### Basics

| Syntax | Description | Example |
|--------|-------------|---------|
| `.` | Identity | `. => input unchanged` |
| `.foo` | Field access | `.name => "Alice"` |
| `.foo.bar` | Nested field | `.a.b => value at a.b` |
| `.foo?` | Optional field (no error if missing) | `.missing? => nothing` |
| `.[n]` | Array index | `.[0] => first element` |
| `.[m:n]` | Array slice | `.[1:3] => elements 1,2` |
| `.[]` | Iterate all elements | `.[] => each element` |
| `.[]?` | Optional iterate | `.[]? => each element or nothing` |

### Operators

| Syntax | Description |
|--------|-------------|
| `\|` | Pipe (chain filters) |
| `,` | Multiple outputs |
| `+`, `-`, `*`, `/`, `%` | Arithmetic |
| `==`, `!=`, `<`, `<=`, `>`, `>=` | Comparison |
| `and`, `or`, `not` | Logical |
| `//` | Alternative (default value) |

### Construction

| Syntax | Description |
|--------|-------------|
| `[expr]` | Array construction |
| `{key: expr}` | Object construction |
| `{name}` | Shorthand for `{name: .name}` |
| `{(expr): expr}` | Computed key |
| `"Hello \(expr)"` | String interpolation |

### Control Flow

| Syntax | Description |
|--------|-------------|
| `if cond then a elif cond then b else c end` | Conditional |
| `try expr` | Suppress errors |
| `try expr catch handler` | Handle errors |
| `label $name \| expr` | Label for break |

### Binding and Definitions

| Syntax | Description |
|--------|-------------|
| `expr as $var \| body` | Variable binding |
| `expr as [$a, $b] \| body` | Destructuring array bind |
| `expr as {key: $v} \| body` | Destructuring object bind |
| `reduce .[] as $x (init; update)` | Reduce/fold |
| `foreach .[] as $x (init; update)` | Stateful iteration |
| `foreach .[] as $x (init; update; extract)` | With extraction |
| `def name(params): body;` | Function definition |

### Assignment / Update

| Syntax | Description |
|--------|-------------|
| `.foo = expr` | Set field |
| `.foo \|= expr` | Update field |
| `.foo += expr` | Add-assign |
| `.foo -= expr` | Subtract-assign |
| `.foo *= expr` | Multiply-assign |
| `.foo //= expr` | Alternative-assign |

### Key Builtins

**Type and conversion:** `type`, `length`, `keys`, `values`, `has(key)`, `in(obj)`, `contains(val)`, `tostring`, `tonumber`, `ascii_upcase`, `ascii_downcase`

**Arrays:** `map(f)`, `select(f)`, `sort`, `sort_by(f)`, `group_by(f)`, `unique`, `unique_by(f)`, `reverse`, `flatten`, `add`, `any`, `all`, `first`, `last`, `nth(n)`, `range(n)`, `min`, `max`, `min_by(f)`, `max_by(f)`, `indices(val)`, `transpose`, `combinations`

**Strings:** `split(sep)`, `join(sep)`, `test(regex)`, `match(regex)`, `capture(regex)`, `sub(re; rep)`, `gsub(re; rep)`, `ltrimstr(s)`, `rtrimstr(s)`, `startswith(s)`, `endswith(s)`, `ascii_upcase`, `ascii_downcase`

**Objects:** `to_entries`, `from_entries`, `with_entries(f)`, `keys`, `values`, `has(key)`

**Paths:** `path(expr)`, `paths`, `paths(filter)`, `getpath(p)`, `setpath(p; v)`, `delpaths(ps)`, `del(expr)`

**Math:** `floor`, `ceil`, `round`, `sqrt`, `log`, `log2`, `log10`, `exp`, `pow(x;y)`, `fabs`, `nan`, `infinite`, `isinfinite`, `isnan`, `isnormal`

**I/O formats:** `@base64`, `@base64d`, `@uri`, `@csv`, `@tsv`, `@html`, `@json`, `@sh`, `@text`, `tojson`, `fromjson`

**Date/time:** `now`, `todate`, `fromdate`, `gmtime`, `mktime`, `strftime(fmt)`, `strptime(fmt)`

**Iteration:** `recurse`, `recurse(f)`, `walk(f)`, `limit(n; f)`, `first(f)`, `last(f)`, `until(cond; update)`, `while(cond; update)`, `repeat(f)`

---

## Known Limitations

jjq passes 485 of 508 upstream jq tests (95.5%). The remaining differences are documented below so you can determine whether they affect your use case.

### Module system not supported

jjq does not implement jq's module system. The `import`, `include`, and `modulemeta` keywords will produce a parse error. This means you cannot split jq code across multiple files using jq's built-in module mechanism.

**Workaround:** Define functions inline within your filter, or compose programs at the Java API level by chaining multiple `JqProgram` instances in a pipeline.

### Big integer precision

jq uses arbitrary-precision integers internally and clamps values to IEEE 754 double precision only on output. jjq uses `long` with `BigDecimal` fallback, which can produce slightly different results for integers beyond the safe 64-bit floating-point range (greater than 2^53).

```
# jq:  13911860366432393 - 10  =>  13911860366432382
# jjq: 13911860366432393 - 10  =>  13911860366432383
```

Normal-range integer and floating-point arithmetic works correctly. This only affects edge cases with very large integers that exceed the precision of IEEE 754 doubles.

### Deep nesting

Deeply nested structures (10,000+ levels) can cause `StackOverflowError` during JSON serialization or parsing. This is a JVM stack depth limitation rather than a jjq design issue. Typical real-world JSON is unaffected.

### Minor error message differences

A few jq error messages differ slightly from jjq:

- **try-catch error propagation:** One edge case involving re-thrown errors inside nested `try-catch` blocks behaves differently than jq. The error is thrown at the top level instead of being caught by the outer try-catch.
- **fromjson parse errors:** Column numbers in JSON parse error messages may differ from jq (e.g., column 2 vs column 5) due to differences in the underlying JSON parser (fastjson2 vs jq's built-in parser).
- **Control characters in error messages:** Null bytes and other control characters in error messages are rendered as `\u0000` instead of jq's `\0` notation. This is a cosmetic difference that only affects error message strings, not program behavior.

These differences do not affect the correctness of filter evaluation — they only change the string content of error messages in edge cases.

---

## API Reference

### JqProgram

```java
// Compilation
static JqProgram compile(String expression)
static JqProgram compile(String expression, BuiltinRegistry builtins)

// Primary API (bytecode VM — recommended)
JqValue apply(JqValue input)                       // first result, zero-alloc
JqValue apply(JqValue input, Environment env)      // with variables
List<JqValue> applyAll(JqValue input)              // all results
List<JqValue> applyAll(JqValue input, Environment env)
void apply(JqValue input, Consumer<JqValue> output) // streaming

// Multi-input API (JSONL-style, reuses single VM)
List<JqValue> applyAll(Iterable<JqValue> inputs)   // all inputs, all results
List<JqValue> applyAll(Iterable<JqValue> inputs, Environment env)
void applyAll(Iterable<JqValue> inputs, Consumer<JqValue> output)
Stream<JqValue> stream(Iterable<JqValue> inputs)   // stream of results

// Utilities
String expression()                                // original filter string
Bytecode getBytecode()                             // compiled bytecode
```

### JqValues

```java
static JqValue parse(String json)              // parse a single JSON value
static List<JqValue> parseAll(String json)     // parse JSONL / whitespace-separated JSON values
```

### JqValue

```java
// Type
Type type()                          // NULL, BOOLEAN, NUMBER, STRING, ARRAY, OBJECT
boolean isNull(), isBoolean(), isNumber(), isString(), isArray(), isObject()
boolean isTruthy()                   // false and null are falsy

// Value extraction
boolean booleanValue()
long longValue()
double doubleValue()
BigDecimal decimalValue()
String stringValue()
List<JqValue> arrayValue()
Map<String, JqValue> objectValue()

// Operators
JqValue add(JqValue other)           // +
JqValue subtract(JqValue other)      // -
JqValue multiply(JqValue other)      // *
JqValue divide(JqValue other)        // /
JqValue modulo(JqValue other)        // %
JqValue negate()                     // unary -

// Output
String toJsonString()
int length()
```

### Environment

```java
void setVariable(String name, JqValue value)
JqValue getVariable(String name)
boolean hasVariable(String name)
Environment child()
```

### FastjsonEngine

```java
// Compilation
JqProgram compile(String expression)

// Apply
List<JqValue> apply(String expression, String json)
List<String> applyToStrings(String expression, String json)

// Byte buffers
byte[] applyToBytes(String expression, byte[] jsonBytes)
byte[] applyToBytes(JqProgram program, byte[] buffer, int offset, int length)

// Streaming
Stream<JqValue> applyToJsonStream(JqProgram program, InputStream input)
Stream<JqValue> applyToJsonStream(JqProgram program, byte[] buffer, int offset, int length)

// Conversion
static JqValue fromJson(String json)
static JqValue fromJsonLazy(String json)
static JqValue fromFastjson(Object obj)
static JqValue fromFastjsonLazy(Object obj)
static Object toFastjson(JqValue value)
```

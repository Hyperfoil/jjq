# jjq

High-performance pure Java [jq](https://jqlang.github.io/jq/) implementation with a bytecode-compiled VM, deferred string parsing, and zero-allocation query execution on large documents.

jjq provides a complete jq filter engine with zero native dependencies, making it portable across all JVM platforms. It executes field access queries in **3 nanoseconds with zero allocation** on a 14MB production document, parses with **26% less allocation than Jackson**, and serializes **60% faster**.

## Features

- **Full jq syntax** — pipes, field access, iteration, array/object construction, string interpolation, reduce, foreach, try-catch, label-break, destructuring bind, function definitions, and more
- **179 builtin functions** — comprehensive coverage of jq's standard library including math, string, array, object, path, date/time, and format operations
- **Bytecode VM** — fused iteration opcodes, whole-program shape detection, constant folding, peephole optimization, pre-allocated stacks
- **Fast JSON parsing** — direct digit accumulation, deferred string values, byte[]-based parsing with SWAR scanning, field name interning. 1.3-2.4x faster than Jackson on 10KB inputs; comparable on large files (interning trades parse throughput for zero-allocation queries)
- **Zero-allocation queries** — field access, deep field chains, keys, and length on pre-parsed documents produce zero garbage
- **Thread-safe** — compiled programs are immutable and can be shared across threads
- **Hibernate + JAX-RS integration** — `jjq-jakarta` module provides FormatMapper and MessageBodyReader/Writer for direct `JqValue` persistence and REST endpoints
- **Multiple JSON adapters** — Jackson, fastjson2, and byte[] adapters with lazy zero-copy conversion
- **Java 21+** — leverages sealed classes, records, and pattern matching

## Modules

| Module | Description |
|--------|-------------|
| `jjq-core` | Lexer, parser, AST, bytecode VM, builtins (zero external dependencies) |
| `jjq-jackson` | Jackson databind adapter — `JsonNode` ↔ `JqValue` conversion with lazy wrapping |
| `jjq-fastjson2` | fastjson2 adapter with lazy conversion and streaming APIs |
| `jjq-jakarta` | Hibernate `FormatMapper` and JAX-RS `MessageBodyReader`/`Writer` for `JqValue` persistence and REST |
| `jjq-jsonata` | Compile-time [JSONata](https://jsonata.org)-to-jq transpiler — 468/1219 conformance tests passing |
| `jjq-cli` | Command-line interface (zero dependencies, GraalVM native-image ready) |
| `jjq-test-suite` | 466 conformance tests + 508 upstream jq tests (96.7% passing) |
| `jjq-benchmark` | JMH benchmarks: library comparison, production queries, allocation profiling |

## Quick Start

### Maven

```xml
<dependency>
    <groupId>io.hyperfoil.tools</groupId>
    <artifactId>jjq-core</artifactId>
    <version>0.1.4-SNAPSHOT</version>
</dependency>

<!-- For Jackson integration -->
<dependency>
    <groupId>io.hyperfoil.tools</groupId>
    <artifactId>jjq-jackson</artifactId>
    <version>0.1.4-SNAPSHOT</version>
</dependency>

<!-- For Hibernate/JAX-RS integration -->
<dependency>
    <groupId>io.hyperfoil.tools</groupId>
    <artifactId>jjq-jakarta</artifactId>
    <version>0.1.4-SNAPSHOT</version>
</dependency>

<!-- For JSONata support -->
<dependency>
    <groupId>io.hyperfoil.tools</groupId>
    <artifactId>jjq-jsonata</artifactId>
    <version>0.1.4-SNAPSHOT</version>
</dependency>
```

### Core API (zero dependencies)

```java
import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.value.JqValues;
import io.hyperfoil.tools.jjq.value.JqValue;

// Compile once, apply many times (thread-safe)
JqProgram program = JqProgram.compile(".users[] | {name, email}");

JqValue input = JqValues.parse("""
    {"users": [
        {"name": "Alice", "email": "alice@example.com", "age": 30},
        {"name": "Bob", "email": "bob@example.com", "age": 25}
    ]}
    """);

List<JqValue> results = program.applyAll(input);
results.forEach(r -> System.out.println(r.toJsonString()));
// {"name":"Alice","email":"alice@example.com"}
// {"name":"Bob","email":"bob@example.com"}
```

### Parse from byte[] (fastest path)

```java
// Parse directly from bytes — no intermediate String allocation
byte[] jsonBytes = Files.readAllBytes(Path.of("data.json"));
JqValue data = JqValues.parse(jsonBytes);
```

### Serialize to byte[] (zero-copy path)

```java
// Serialize directly to UTF-8 bytes — no intermediate String allocation
byte[] output = JqValues.serializeToBytes(data);

// Also works with OutputStream (uses the byte path internally)
JqValues.serializeTo(data, outputStream);
```

For documents parsed from `byte[]`, deferred string values are copied as raw bytes without constructing Java Strings or re-encoding UTF-8. This makes the `parse(byte[])` -> `serializeToBytes()` round-trip optimal for pass-through workloads like database persistence and message queues.

### Navigate and extract values

For repeated queries, **always use `JqProgram.compile()`** -- it compiles once and executes in nanoseconds:

```java
// Compile once at startup (thread-safe, reusable)
JqProgram getName = JqProgram.compile(".user");
JqProgram getResults = JqProgram.compile(".autobench_workload.data[0].results");

// Apply many times (3 ns per field access, zero allocation)
JqValue user = getName.apply(data);
JqValue results = getResults.apply(data);
```

For **programmatic navigation** (traversing results, iterating dynamic field names), use the null-safe convenience methods:

```java
// Null-safe chaining -- returns JqNull.NULL for missing paths or type mismatches
JqValue results = data.getField("workload").getField("data").getElement(0).getField("results");

// JSON Pointer navigation (RFC 6901)
JqValue results = data.at("/workload/data/0/results");

// Safe value extraction with defaults
String name = data.getField("user").asString("unknown");
long count = data.getField("count").asLong(0);
int page = data.getField("page").asInt(1);
double score = data.getField("score").asDouble(0.0);

// Coercing extractors -- extract numbers from JqNumber or numeric strings
Double value = data.getField("metric").tryDouble();   // null if not numeric
Long id = data.getField("id").tryLong();               // null if not parseable
Integer port = data.getField("port").tryInt();          // null if not parseable

// Fail-fast access -- throws JqTypeError if missing
JqValue required = data.required("user");              // throws if missing
JqValue element = data.required(0);                    // throws if out of bounds

// Type checks
data.isNull();              data.isString();
data.isNumber();            data.isBoolean();
data.isArray();             data.isObject();
data.isIntegralNumber();    data.isFloatingPointNumber();

// Check existence
if (data.has("user") && !data.getField("items").isEmpty()) {
    // ...
}

// Iterate arrays directly (JqArray implements Iterable<JqValue>)
JqArray items = (JqArray) data.getField("items");
for (JqValue item : items) {
    System.out.println(item.getField("name").asText());
}

// Stream support
List<String> names = items.stream()
    .filter(item -> item.getField("active").asBoolean(false))
    .map(item -> item.getField("name").asText())
    .toList();

// Object accessors — forEach avoids Map.Entry allocation for array-backed objects
JqObject config = (JqObject) data.getField("config");
config.forEach((key, val) -> System.out.println(key + "=" + val));
for (String key : config.keys()) { /* ... */ }
for (var entry : config.entries()) { /* ... */ }
```

### Parse from InputStream

```java
// Parse directly from an InputStream (reads all bytes, then parses)
try (InputStream in = connection.getInputStream()) {
    JqValue data = JqValues.parse(in);
}
```

### Pretty-print JSON

```java
// Compact (default)
String compact = value.toJsonString();  // {"name":"Alice","age":30}

// Pretty-printed with 2-space indentation
String pretty = JqValues.toPrettyJsonString(value);
// {
//   "name": "Alice",
//   "age": 30
// }
```

### Convert between JqValue and Java types

Recursive conversion for integrating with libraries that operate on `Map`/`List`/primitives (JSONata engines, GraalVM polyglot, JDBC, template engines):

```java
// Java → JqValue (null → JqNull.NULL, Map → JqObject, List → JqArray, etc.)
Map<String, Object> javaMap = Map.of("name", "Alice", "scores", List.of(1, 2, 3));
JqValue value = JqValues.fromJavaObject(javaMap);

// JqValue → Java (JqNull → null, JqObject → LinkedHashMap, JqArray → ArrayList, etc.)
Object javaObj = value.toJavaObject();

// JqNumber.of(Number) — accepts any Number subtype (Integer, Long, Double, Float, BigDecimal, etc.)
JqNumber n = JqNumber.of(someNumber);  // auto-promotes integral types to long-backed
```

### Type structure (schema inference)

Compute a type skeleton from a JSON document — replaces leaf values with their type names while preserving the object/array structure. Useful for schema inference, data profiling, and structural comparison:

```java
JqValue input = JqValues.parse("""
    {"name": "Alice", "age": 30, "scores": [95, 87.5]}
    """);

JqValue schema = JqValues.typeStructure(input);
// {"name":"string","age":"integer","scores":["number"]}
```

Type mapping: `null` -> `"null"`, `boolean` -> `"boolean"`, integral number -> `"integer"`, floating-point -> `"number"`, `string` -> `"string"`. Arrays merge element schemas into a single representative.

Merge schemas from multiple documents to build a unified type structure:

```java
JqValue doc1 = JqValues.parse("{\"value\": 42}");
JqValue doc2 = JqValues.parse("{\"value\": 3.14, \"extra\": true}");

JqValue merged = JqValues.mergeTypeStructures(
    JqValues.typeStructure(doc1),
    JqValues.typeStructure(doc2));
// {"value":"number","extra":"boolean"}
// "integer" + "number" promoted to "number"; keys unioned
```

### Build JSON values

```java
import io.hyperfoil.tools.jjq.value.*;

// Object builder with type-safe convenience methods
JqObject result = JqObject.builder()
    .put("name", "Alice")
    .put("age", 30)
    .put("score", 95.5)
    .put("active", true)
    .put("data", someJqValue)   // null is treated as JqNull.NULL
    .build();

// Array builder
JqArray items = JqArray.arrayBuilder()
    .add("first")
    .add("second")
    .add(42)
    .build();

// Copy-on-write modification (immutable — returns new instances)
JqObject updated = result.with("status", JqString.of("verified"));
JqObject merged = result.merge(otherObject);
JqObject deepMerged = result.deepMerge(otherObject);  // recursive merge for nested objects
JqObject without = result.without("age");
```

### Jackson integration

```java
import io.hyperfoil.tools.jjq.jackson.JacksonJqEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

ObjectMapper mapper = new ObjectMapper();
JacksonJqEngine engine = new JacksonJqEngine(mapper);

JqProgram program = engine.compile(".users[] | {name, email}");
JsonNode input = mapper.readTree(requestBody);
List<JsonNode> results = engine.apply(program, input);
```

### JSONata support (jjq-jsonata)

Run [JSONata](https://jsonata.org) expressions through jjq's optimized bytecode VM. The transpiler converts JSONata to jq at compile time — zero runtime overhead:

```java
import io.hyperfoil.tools.jjq.jsonata.JsonataCompiler;

// Compile JSONata to jq (one-time cost, ~microseconds)
JqProgram program = JsonataCompiler.compile("$sum(orders.price)");

// Execute through jjq's bytecode VM — same performance as native jq
JqValue result = program.apply(data);
```

Supports navigation, operators, predicates, 35+ built-in functions, implicit array mapping, variable binding, lambdas (`$map`/`$filter`/`$reduce`), `~>` pipe operator, `**` recursive descent, and more. See the [jjq-jsonata README](jjq-jsonata/README.md) for full details and conformance status.

### Hibernate / JAX-RS integration (jjq-jakarta)

Use `JqValue` directly as a Hibernate entity field type and in REST endpoints:

```java
// Entity with JqValue field (mapped to JSONB column)
@Entity
public class MyEntity {
    @Column(columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    @Mutability(Immutability.class)
    public JqValue data;
}

// REST endpoint accepting and returning JqValue (auto-parsed/serialized)
@POST @Path("/upload")
public Response upload(JqValue data) {
    service.store(data);
    return Response.ok().build();
}

@GET @Path("/{id}")
public JqValue get(@PathParam("id") long id) {
    return service.load(id);
}
```

Register the FormatMapper for Quarkus:
```java
@ApplicationScoped
@PersistenceUnitExtension
@JsonFormat
public class MyFormatMapper extends JqValueFormatMapper {}
```

See the [jjq-jakarta README](jjq-jakarta/README.md) for full setup details.

### With variables

```java
import io.hyperfoil.tools.jjq.evaluator.Environment;

JqProgram program = JqProgram.compile(".[] | select(.name == $target)");
Environment env = new Environment();
env.setVariable("target", JqString.of("Alice"));

List<JqValue> results = program.applyAll(input, env);
```

### Processing multiple inputs (JSONL)

```java
List<JqValue> inputs = JqValues.parseAll("""
    {"name":"Alice","age":30}
    {"name":"Bob","age":25}
    """);

JqProgram program = JqProgram.compile(".name");
List<JqValue> names = program.applyAll(inputs);
```

## CLI Usage

```
jjq [OPTIONS] FILTER [FILE...]
```

### Options

| Option | Description |
|--------|-------------|
| `-c, --compact-output` | Compact output (no pretty-printing) |
| `-r, --raw-output` | Output raw strings (no JSON quotes) |
| `-R, --raw-input` | Read each input line as a string |
| `-s, --slurp` | Read all inputs into an array |
| `-n, --null-input` | Use `null` as input |
| `-e, --exit-status` | Set exit status based on output |
| `-S, --sort-keys` | Sort object keys in output |
| `-j, --join-output` | Don't print newlines between outputs |
| `-f, --from-file FILE` | Read filter from file |
| `--arg NAME VALUE` | Set `$NAME` to string VALUE |
| `--argjson NAME JSON` | Set `$NAME` to parsed JSON value |
| `--tab` | Use tab for indentation |
| `--indent N` | Use N spaces for indentation (default: 2) |

### Examples

```bash
# Field access
echo '{"name":"Alice","age":30}' | jjq '.name'
# "Alice"

# Filter and transform
echo '[1,2,3,4,5]' | jjq '[.[] | select(. > 2) | . * 10]'
# [30,40,50]

# Object construction
echo '{"first":"Alice","last":"Smith","age":30}' | jjq '{full: (.first + " " + .last), age}'
# {"full":"Alice Smith","age":30}

# Reduce
echo '[1,2,3,4,5]' | jjq 'reduce .[] as $x (0; . + $x)'
# 15

# Process JSONL (one JSON value per line)
printf '{"name":"Alice"}\n{"name":"Bob"}\n' | jjq '.name'
# "Alice"
# "Bob"
```

## Performance

### JSON Parsing: jjq vs Jackson vs fastjson2

Parsing throughput on varied input types (ops/us, higher is better). Measured with JMH, 3 forks, JDK 25. Percentages are vs Jackson (String) for the same structure:

| Input type | Size | Jackson | Jackson (bytes) | jjq (String) | jjq (byte[]) | fastjson2 |
|-----------|------|---------|-----------------|--------------|--------------|-----------|
| flat | 10KB | 0.032 | 0.030 | 0.050 (+56%) | 0.071 (+137%) | 0.055 |
| strings | 10KB | 0.046 | 0.053 | 0.055 (+20%) | 0.098 (+85%) | 0.063 |
| numbers | 10KB | 0.025 | 0.028 | 0.039 (+56%) | 0.049 (+75%) | 0.043 |
| nested | 10KB | 0.026 | 0.032 | 0.030 (+15%) | 0.041 (+28%) | 0.059 |
| **Production 14MB** | **14MB** | **0.000034** | **0.000047** | **0.000033** | **0.000040** | 0.000059 |

On 10KB inputs, jjq's byte[] parser is **1.3-2.4x faster than Jackson**. On the 14MB production file, jjq trades some parse throughput for field name interning and eager hash indexing — optimizations that enable zero-allocation queries on the parsed document. Parse allocation is **26% lower than Jackson** (29.7 MB vs 40.3 MB) thanks to interned field names and a lightweight open-addressing hash index.

### Production Query Execution on 14MB Document

Query execution on a pre-parsed 14MB production upload (351K nodes, 3,668 objects). Times via `JqProgram.apply()` — the h5m API path including compiled program dispatch:

| Query | Expression | Time | Allocation |
|-------|-----------|------|------------|
| Top-level field | `.user` | **3.1 ns** | **0 B** |
| Deep field (4 levels) | `.autobench_workload.data[0].results` | 63 ns | 0 B |
| Keys (127-key object) | `.data[0].pcp_time_series[0] \| keys` | 69 ns | 0 B |
| Array length | `.data[0].pcp_time_series \| length` | 64 ns | 0 B |
| PCP entry (127-key object) | `.data[0].pcp_time_series[0]` | 69 ns | 0 B |
| Object construction | `{user, uuid, run_id, start_time, end_time}` | 95 ns | 168 B |
| Config extract | `.rhivos_config \| {build, model, kernel, architecture}` | 105 ns | 144 B |
| Iterate + extract | `[.stressng_workload.data[] \| .sample_uuid]` | 150 ns | 80 B |
| Extract metric (502 entries) | `[.pcp_time_series[] \| .["mem.util.used"]]` | 13 us | 2.1 KB |
| Round-trip (extract + serialize) | `.user` | 15 ns | 56 B |

Seven of fifteen production benchmarks achieve **zero allocation per query** — the result comes directly from the pre-parsed document with no object creation. Single field access bypasses the VM entirely via inlined fast path detection.

### Serialization

| Input type | Size | Jackson | jjq | jjq/Jackson |
|-----------|------|---------|-----|-------------|
| strings | 10KB | 0.075 | 0.141 | **188%** |
| numbers | 10KB | 0.044 | 0.055 | 125% |
| nested | 10KB | 0.048 | 0.084 | **175%** |
| **Production 14MB** | **14MB** | **0.000054** | **0.000087** | **160%** |

jjq serializes **60% faster than Jackson** on the 14MB production file with **14% lower allocation** (47.2 MB vs 54.7 MB). The speedup comes from pre-computed JSON key forms (no escape scanning for interned field names) and type-specialized `appendTo` dispatch.

### Memory Efficiency

Parse allocation comparison on the 14MB production file:

| Library | Parse allocation | vs Jackson |
|---------|-----------------|------------|
| **jjq** | **29.7 MB** | **-26%** |
| Jackson | 40.3 MB | baseline |
| fastjson2 | 57.7 MB | +43% |

jjq achieves the lowest parse allocation through field name interning (shared String instances across repeated schemas), deferred string values (no allocation for untouched strings), and a lightweight open-addressing hash index (flat `int[]` instead of `HashMap` nodes).

### CLI Performance: jjq native-image vs jq 1.8.1

GraalVM native-image comparison on the 14MB production file (hyperfine, best of 3+ warmup runs):

| Test | jq 1.8.1 | jjq (native) | Notes |
|------|----------|--------------|-------|
| Startup (`'.' /dev/null`) | 1.4 ms | 1.7 ms | Both sub-2ms |
| Field access (`.user`) | 122 ms | 168 ms | Parse-dominated |
| Deep field (`.a.b[0].c`) | 122 ms | 168 ms | Parse-dominated |
| Object construction | 123 ms | 168 ms | Parse-dominated |
| **Identity round-trip** (parse + serialize) | 245 ms | **213 ms** | **jjq 15% faster** |

For CLI one-shot usage, jq 1.8.1's C parser is ~36% faster on parse-dominated workloads. jjq wins on full round-trips (parse + serialize) due to faster serialization. The native-image binary is 15 MB vs jq's 36 KB.

**jjq's strength is the library use case** — parse once, query many times. In h5m, a 14MB upload is parsed once and queried with dozens of jq expressions. The interning, zero-allocation queries, and pre-compiled bytecode VM amortize across all queries, achieving 3 ns field access with zero garbage. This is not measurable in CLI one-shot benchmarks where parse time dominates.

## Architecture

```
jq expression string
        |
   [Lexer]              Hand-written, keyword-aware
        |
   Token stream
        |
   [Parser]              Pratt parser (top-down operator precedence)
        |
   AST (JqExpr)          ~35 sealed record types
        |
   [Compiler]            AST -> Bytecode with fusion + folding
        |
   [VirtualMachine]      Stack-based, FORK/BACKTRACK for generators
        |
   Output (JqValue)
```

### Bytecode VM

- **74 opcodes** with fused iteration (COLLECT_ITERATE, REDUCE_ITERATE, COLLECT_SELECT_ITERATE)
- **21 inlined builtin opcodes** (length, type, keys, sort, add, etc.)
- **Compound instructions** (DOT_FIELD2 for `.a.b`, BUILD_OBJECT with pre-computed layouts)
- **Whole-program shape detection** — IDENTITY, FIELD_ACCESS, FIELD_ACCESS2, PIPE_FIELD_ARITH, BUILTIN bypass the VM loop entirely
- **Constant folding** and **peephole optimization** at compile time
- **Pre-allocated growable stacks** with pre-ensured capacity for hot loops

### Value System

All JqValue types implement `Serializable` for Hibernate second-level cache support. Singletons (`JqNull.NULL`, `JqBoolean.TRUE/FALSE`, cached `JqNumber` instances) preserve identity across serialization via `readResolve()`.

| Type | Implementation | Key optimization |
|------|---------------|-----------------|
| `JqNull` | Singleton | Zero allocation |
| `JqBoolean` | TRUE/FALSE constants | Zero allocation |
| `JqNumber` | `long` fast-path with cache [-128, 1023], `double` for decimals, `BigDecimal` fallback. `of(Number)` accepts any Number subtype. | Direct digit accumulation avoids `new BigDecimal()` for 99% of numbers |
| `JqString` | Deferred: holds `(source, start, end)` reference, materializes lazily. Serialization proxy materializes before writing. | Zero-copy serialization via `sb.append(source, start, end)` for untouched strings |
| `JqArray` | `List<JqValue>` via raw `JqValue[]` | `ofTrusted()` avoids defensive copying |
| `JqObject` | Parallel `String[]` keys + `JqValue[]` values, `Builder` for zero-intermediate construction. Serialization proxy converts map-backed to array-backed. | Linear scan for ≤32 keys, hash index for larger objects. Interned field names enable reference equality. Pre-computed `"key":` JSON form eliminates escapeJson scanning. Copy-on-write `with()`/`without()`/`merge()`/`deepMerge()`. `forEach(BiConsumer)` for zero-allocation iteration. |

### Parser Optimizations

- **byte[]-based parser** (`JqValues.parse(byte[])`) — parses UTF-8 bytes directly, no intermediate String
- **SWAR scanning** — finds `"` and `\` in 8 bytes per iteration using Netty-style bit manipulation
- **Deferred string values** — string values hold source references, materialized only when accessed
- **Field name interning** — open-addressing hash table (1024 slots, 4-probe linear probing) with fused SWAR+hash computation. Quad-based cache verification (1-3 int comparisons for keys <=12 bytes) with hash fast-reject. Cache hits return the same String instance without `substring()`. Pre-computed `"key":` JSON form eliminates escape scanning during serialization.
- **Direct digit accumulation** — integers and decimals parsed to `long`/`double` without `BigDecimal` or `substring()` for numbers with ≤15 significant digits
- **Thread-local buffer reuse** — both char-based (StringBuilder) and byte-based (BytOutput) serialization buffers grow once and are reused across calls
- **Direct byte serialization** (`JqValues.serializeToBytes(byte[])`) — serializes directly to UTF-8 bytes without intermediate String. Deferred-bytes strings copy raw source bytes (zero encoding). Interned field names use pre-computed byte forms. Numbers serialize directly to ASCII digits.

## Building

```bash
# Requires Java 21+
mvn clean install

# Run tests only
mvn test

# Build CLI
mvn package -pl jjq-cli

# Build native binary (requires GraalVM 21+)
mvn package -pl jjq-core,jjq-cli -Pnative -DskipTests

# Run benchmarks
mvn package -pl jjq-core,jjq-jackson,jjq-fastjson2,jjq-benchmark -DskipTests
java --enable-preview -jar jjq-benchmark/target/jjq-benchmark-0.1.4-SNAPSHOT.jar

# Run specific benchmark class
./scripts/run-benchmarks.sh JsonParseComparisonBenchmark

# Run with allocation profiling
java --enable-preview -jar jjq-benchmark/target/jjq-benchmark-0.1.4-SNAPSHOT.jar \
  JsonProductionBenchmark -prof gc -rf json -rff results.json
```

## Supported jq Features

- Identity (`.`), field access (`.foo`, `.a.b.c`), indexing (`.[0]`, `.[2:5]`)
- Pipes (`|`), comma (`,`), parentheses
- Array/object construction (`[...]`, `{...}`, computed keys)
- String interpolation (`"Hello \(.name)"`)
- Arithmetic (`+`, `-`, `*`, `/`, `%`), comparison, logical operators
- Recursive object merge (`*` operator)
- Alternative operator (`//`)
- Optional operator (`.foo?`, `.[]?`)
- `if-then-elif-else-end`
- `try-catch`
- Variable binding (`. as $x | ...`)
- Destructuring bind (`. as [$a, $b] | ...`, `. as {name: $n} | ...`)
- `reduce`, `foreach` (with destructuring pattern support)
- Function definitions (`def f(x): ...;`) with proper closure scoping
- `label-break`
- Assignment operators (`|=`, `+=`, `-=`, `*=`, `/=`, `%=`, `//=`)
- Path expressions (`path()`, `getpath`, `setpath`, `delpaths`, `del`)
- Recursive descent (`..`)
- Format strings (`@base64`, `@uri`, `@csv`, `@tsv`, `@html`, `@json`)
- All standard builtins (179 functions)

## Known Limitations

jjq passes 491 of 508 upstream jq tests (96.7%). The remaining differences:

### Module system (`import` / `include` / `modulemeta`)

jjq does not implement jq's module system. The `import`, `include`, and `modulemeta` keywords are not supported (12 skipped tests).

### Big integer precision

jq uses arbitrary-precision integers internally. jjq uses `long` with `BigDecimal` fallback, which can produce slightly different results for integers beyond 2^53 (4 skipped tests). Normal-range arithmetic works correctly.

### Minor error message differences

`fromjson` parse errors report a different column number than jq for certain invalid JSON (1 skipped test).

## Documentation

- [User Guide](docs/guide.md) — CLI usage, Java API, integration patterns
- [Performance Guide](docs/performance.md) — benchmarking methodology, profiling, optimization history
- [Profiling Guide](docs/profiling-guide.md) — JMH profiler recipes, async-profiler flame graphs
- [jjq-jakarta README](jjq-jakarta/README.md) — Hibernate and JAX-RS integration

## License

This project is licensed under the [Apache License 2.0](LICENSE).

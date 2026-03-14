# jjq

High-performance pure Java [jq](https://jqlang.github.io/jq/) implementation backed by [fastjson2](https://github.com/alibaba/fastjson2).

jjq provides a complete jq filter engine with zero native dependencies, making it portable across all JVM platforms. It uses a bytecode-compiled VM for optimal performance.

## Features

- **Full jq syntax** — pipes, field access, iteration, array/object construction, string interpolation, reduce, foreach, try-catch, label-break, destructuring bind, function definitions, and more
- **179 builtin functions** — comprehensive coverage of jq's standard library including math, string, array, object, path, date/time, and format operations
- **Bytecode VM** — up to 18x faster than jackson-jq with constant folding, peephole optimizations, and fast-path shape detection
- **fastjson2 integration** — lazy zero-copy conversion, byte buffer processing, and JSON stream support
- **Thread-safe** — compiled programs are immutable and can be shared across threads
- **Java 21+** — leverages sealed classes, records, and pattern matching

## Modules

| Module | Description |
|--------|-------------|
| `jjq-core` | Lexer, parser, AST, evaluator, bytecode VM, builtins (zero external dependencies) |
| `jjq-jackson` | Jackson databind adapter — `JsonNode` ↔ `JqValue` conversion |
| `jjq-fastjson2` | fastjson2 adapter with lazy conversion and streaming APIs |
| `jjq-cli` | Command-line interface (zero dependencies, GraalVM native-image ready) |
| `jjq-test-suite` | 466 conformance tests + 508 upstream jq tests (95.1% passing) |
| `jjq-benchmark` | JMH benchmarks comparing jjq VM and jackson-jq |

## Quick Start

### Maven

```xml
<dependency>
    <groupId>io.hyperfoil.tools</groupId>
    <artifactId>jjq-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>

<!-- For Jackson integration (Quarkus, Spring, etc.) -->
<dependency>
    <groupId>io.hyperfoil.tools</groupId>
    <artifactId>jjq-jackson</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>

<!-- For fastjson2 integration -->
<dependency>
    <groupId>io.hyperfoil.tools</groupId>
    <artifactId>jjq-fastjson2</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### Java API

**Core API (zero dependencies):**

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

List<JqValue> results = program.apply(input);
results.forEach(r -> System.out.println(r.toJsonString()));
// {"name":"Alice","email":"alice@example.com"}
// {"name":"Bob","email":"bob@example.com"}
```

**Jackson integration (for Quarkus / REST APIs):**

```java
import io.hyperfoil.tools.jjq.jackson.JacksonJqEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

ObjectMapper mapper = new ObjectMapper();
JacksonJqEngine engine = new JacksonJqEngine(mapper);

// Pre-compile filter (thread-safe, reuse across requests)
JqProgram program = engine.compile(".users[] | {name, email}");

// Apply to Jackson JsonNode — input and output are both JsonNode
JsonNode input = mapper.readTree(requestBody);
List<JsonNode> results = engine.apply(program, input);

// Or get the first result directly
JsonNode first = engine.applyFirst(program, input);
```

**With variables:**

```java
import io.hyperfoil.tools.jjq.evaluator.Environment;
import io.hyperfoil.tools.jjq.value.JqString;

JqProgram program = JqProgram.compile(".[] | select(.name == $target)");
Environment env = new Environment();
env.setVariable("target", JqString.of("Alice"));

List<JqValue> results = program.apply(input, env);
```

**Processing multiple inputs (JSONL-style):**

```java
// Parse a JSONL / NDJSON string into multiple values
List<JqValue> inputs = JqValues.parseAll("""
    {"name":"Alice","age":30}
    {"name":"Bob","age":25}
    {"name":"Charlie","age":35}
    """);

// Process all inputs through one filter — reuses a single VM for efficiency
JqProgram program = JqProgram.compile(".name");
List<JqValue> names = program.applyAll(inputs);
// "Alice", "Bob", "Charlie"

// Or stream results
program.stream(inputs).forEach(name -> System.out.println(name.toJsonString()));
```

**FastjsonEngine (high-level API with fastjson2):**

```java
import io.hyperfoil.tools.jjq.fastjson2.FastjsonEngine;

FastjsonEngine engine = new FastjsonEngine();

// String in, results out
List<JqValue> results = engine.apply(".name", "{\"name\": \"Alice\"}");

// Compile and reuse
JqProgram program = engine.compile("[.[] | . * 2]");

// Byte buffer mode
byte[] output = engine.applyToBytes(program, jsonBytes);

// JSON stream processing (multiple JSON values)
Stream<JqValue> stream = engine.applyToJsonStream(program, inputStream);

// Lazy parsing (converts nested values on demand)
JqValue lazy = FastjsonEngine.fromJsonLazy(largeJsonString);
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
| `-C, --color-output` | Force colored output |
| `-M, --monochrome-output` | Disable colored output |
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

# With variables
echo '{"items":[1,2,3]}' | jjq --arg name items '.[$name]'
# [1,2,3]

# Read filter from file
jjq -f filter.jq data.json

# Process JSONL / NDJSON (one JSON value per line)
printf '{"name":"Alice"}\n{"name":"Bob"}\n' | jjq '.name'
# "Alice"
# "Bob"

# Slurp JSONL into array
printf '1\n2\n3\n' | jjq -s 'add'
# 6
```

## Architecture

```
jq expression string
        |
   [Lexer]           Hand-written, character-by-character
        |
   Token stream
        |
   [Parser]           Pratt parser (top-down operator precedence)
        |
   AST (JqExpr)       ~35 sealed record types with source locations
        |
   +-----------+-----------+
   |                       |
   [Compiler]
   AST -> Bytecode
        |
   [VM]
   Stack-based with
   FORK/BACKTRACK
   for generators
        |
   Output
```

### Bytecode VM

The VM compiles jq expressions to 72 opcodes and executes them on a stack machine. Key design features:

- **FORK/BACKTRACK** for jq's generator semantics (multiple outputs per expression)
- **21 inlined builtin opcodes** (length, type, keys, sort, etc.) avoiding interpreter overhead
- **DOT_FIELD2** compound instruction for `.a.b` chained field access
- **Fused iteration opcodes** (COLLECT_ITERATE, REDUCE_ITERATE) that bypass backtracking for common patterns
- **Parallel array dispatch** — bytecode stored as parallel `int[]` arrays for zero-overhead opcode access
- **Program shape detection** — identity, field access, and pipe-arith patterns bypass the VM entirely
- **Constant folding** evaluates literal expressions (`2 + 3` -> `5`) at compile time
- **Peephole optimization** removes no-op instruction sequences
- **Pre-allocated growable stacks** for minimal allocation during execution

### Value System

| Type | Implementation |
|------|---------------|
| `JqNull` | Singleton |
| `JqBoolean` | `boolean` with TRUE/FALSE constants |
| `JqNumber` | `long` fast-path, `BigDecimal` fallback, NaN/Infinity support |
| `JqString` | `String` |
| `JqArray` | `List<JqValue>` |
| `JqObject` | `LinkedHashMap<String, JqValue>` (preserves insertion order) |

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

# The native binary is at:
./jjq-cli/target/jjq '.name' <<< '{"name":"Alice"}'

# Run benchmarks
mvn package -pl jjq-benchmark -DskipTests
java -jar jjq-benchmark/target/jjq-benchmark-0.1.0-SNAPSHOT.jar
```

## Performance

jjq VM vs [jackson-jq](https://github.com/eiiches/jackson-jq) throughput (ops/μs, higher is better):

| Benchmark | jackson-jq | jjq VM | Ratio |
|-----------|-----------|--------|-------|
| identity (`.`) | 231.0 | 467.0 | **2.0x** |
| fieldAccess (`.foo`) | 72.3 | 131.5 | **1.8x** |
| pipeArith (`.a \| . + 1`) | 30.9 | 91.6 | **3.0x** |
| iterateMap (`[.[] \| . * 2]`, 10 elem) | 5.0 | 13.3 | **2.7x** |
| iterateMap (100 elem) | 0.54 | 2.87 | **5.3x** |
| complexFilter | 0.56 | 1.88 | **3.4x** |
| reduce (`reduce .[] as $x (0; . + $x)`) | 2.55 | 41.8 | **16.4x** |

Measured with JMH on Temurin JDK 21.0.6, 2 forks × 5 iterations.

## Supported jq Features

- Identity (`.`), field access (`.foo`, `.a.b.c`), indexing (`.[0]`, `.[2:5]`)
- Pipes (`|`), comma (`,`), parentheses
- Array/object construction (`[...]`, `{...}`, computed keys)
- String interpolation (`"Hello \(.name)"`)
- Arithmetic (`+`, `-`, `*`, `/`, `%`), comparison, logical operators
- Recursive object merge (`* operator`)
- Alternative operator (`//`)
- Optional operator (`.foo?`, `.[]?`)
- `if-then-elif-else-end`
- `try-catch`
- Variable binding (`. as $x | ...`)
- Destructuring bind (`. as [$a, $b] | ...`, `. as {name: $n} | ...`)
- `reduce`, `foreach` (with destructuring pattern support)
- Function definitions (`def f(x): ...;`) with proper closure scoping
- Function arguments as path expressions (`def inc(x): x |= .+1;`)
- `label-break`
- Assignment operators (`|=`, `+=`, `-=`, `*=`, `/=`, `%=`, `//=`)
- Path expressions (`path()`, `getpath`, `setpath`, `delpaths`, `del`)
- Complex path expressions (`path(.foo[0,1])`, `path(..)`, `del(.[] | select(...))`)
- Recursive descent (`..`)
- Format strings (`@base64`, `@uri`, `@csv`, `@tsv`, `@html`, `@json`)
- All standard builtins (179 functions)

## Documentation

See the [User Guide](docs/guide.md) for comprehensive documentation covering:

- CLI usage with examples
- Java library API with code samples
- Enterprise integration patterns (REST APIs, message queues, batch processing, metrics)
- Performance best practices
- jq language reference

## License

This project is licensed under the [Apache License 2.0](LICENSE).

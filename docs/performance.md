# jjq Performance Guide

## Overview

jjq's performance comes from several layers of optimization applied to both
JSON parsing and jq expression execution:

1. **Parser optimizations** — byte[]-based parsing, deferred strings, direct
   digit accumulation, SWAR scanning, field name interning
2. **VM optimizations** — bytecode compilation, fused iteration opcodes,
   whole-program shape detection, pre-allocated stacks
3. **Data structure optimizations** — parallel array JqObject, hash index for
   large objects, copy-on-write mutation
4. **Serialization optimizations** — thread-local StringBuilder, zero-copy
   deferred string serialization, pre-computed JSON key forms, streaming output

## Benchmarking

### Running benchmarks

```bash
# Build benchmark jar
mvn package -pl jjq-core,jjq-jackson,jjq-fastjson2,jjq-benchmark -DskipTests

# Run all library comparison benchmarks
./scripts/run-benchmarks.sh "Json.*Benchmark"

# Run production query benchmarks (14MB file)
./scripts/run-benchmarks.sh JjqProductionQueryBenchmark

# Run with allocation profiling
java --enable-preview -jar jjq-benchmark/target/jjq-benchmark-*.jar \
  JsonParseComparisonBenchmark -prof gc -rf json -rff results.json
```

### Benchmark methodology

Following the min-of-N approach validated by Netflix (perf_research) and
the simdjson paper:

- **3+ forks** — isolates JIT compilation profiles between benchmarks.
  1-fork results can diverge 20%+ from 3-fork results.
- **5 warmup + 5 measurement iterations** — minimum for stable JIT compilation
- **Fixed heap** (`-Xmx2g -Xms2g`) — prevents GC variance from heap sizing
- **`--enable-preview`** — required for sealed interfaces on JDK 21
- **Min-of-N for timing** — transient interference increases values,
  so the minimum is closest to interference-free performance

### Three-layer measurement

Based on the Parquet SIMD article's methodology:

| Layer | Tool | What it reveals |
|-------|------|-----------------|
| Throughput + allocation | `-prof gc` | ops/us and bytes/op |
| CPU flame graphs | `-prof async:event=cpu` | which methods consume CPU |
| Allocation flame graphs | `-prof async:event=alloc` | where objects are allocated |
| Hardware counters | `-prof perfnorm` | instructions/op, cache misses, branch misses |

See [Profiling Guide](profiling-guide.md) for detailed recipes.

## Current Performance Numbers

### JSON Parsing: jjq vs Jackson vs fastjson2

Measured on JDK 25, Ryzen 7 7800X3D, 3 forks x 5 warmup x 5 measurement:

| Input | Size | Jackson String | jjq String | jjq byte[] | fastjson2 |
|-------|------|---------------|------------|------------|-----------|
| flat | 1KB | 0.317 | 0.540 (170%) | 0.555 (175%) | 0.543 |
| strings | 1KB | 0.429 | 0.675 (157%) | 0.912 (213%) | 0.594 |
| numbers | 1KB | 0.226 | 0.390 (173%) | 0.453 (201%) | 0.398 |
| nested | 1KB | 0.198 | 0.405 (205%) | 0.476 (241%) | 0.456 |
| **14MB prod** | **14MB** | **0.000033** | **0.000069** (209%) | **0.000090** (273%) | 0.000058 |

jjq's byte[]-based parser is **1.9x faster than Jackson** on the 14MB
production file with comparable allocation (40.7 MB vs 38.4 MB).

### Serialization

| Input | Size | Jackson | jjq | jjq/Jackson |
|-------|------|---------|-----|-------------|
| strings | 1KB | 0.706 | 0.974 | 138% |
| nested | 1KB | 0.396 | 0.441 | 112% |
| **14MB prod** | **14MB** | **0.000056** | **0.000056** | **100%** |
| 14MB alloc | | 52.2 MB | 45.1 MB | **87%** (less) |

### Production Query Execution (14MB, 351K nodes)

| Query | Expression | Time | Alloc |
|-------|-----------|------|-------|
| Top-level field | `.user` | 5.3 ns | 0 B |
| Deep field chain | `.autobench_workload.data[0].results` | 63 ns | 0 B |
| Keys (127-key obj) | `.data[0].pcp_time_series[0] \| keys` | 69 ns | 0 B |
| Length | `.data[0].pcp_time_series \| length` | 64 ns | 0 B |
| Object construction | `{user, uuid, run_id, start_time, end_time}` | 95 ns | 168 B |
| Iterate + extract | `[.stressng_workload.data[] \| .sample_uuid]` | 151 ns | 80 B |
| Extract metric (502x) | `[.pcp_time_series[] \| .["mem.util.used"]]` | 13 us | 2.0 KB |
| Round-trip small | `.user` parse+serialize | 15 ns | 56 B |
| Round-trip identity | `.` serialize 14MB | 13.4 ms | 45.1 MB |

Six benchmarks achieve **zero allocation** — results come directly from
the pre-parsed document.

## Optimization Details

### Parser: Deferred String Values

String values parsed from JSON hold a `(source, start, end)` reference to
the original input instead of calling `substring()`. The Java String is
materialized lazily on first `stringValue()` access. For serialization,
`appendTo(StringBuilder)` writes the original JSON bytes directly via
`sb.append(source, start, end)` — zero String construction, zero escape
scanning.

**Impact:** -44% parse allocation on string-heavy data. +131% string
serialization throughput.

### Parser: Field Name Interning

An open-addressing hash table (2048 slots) deduplicates JSON object key
strings. The hash is computed incrementally during character scanning
(same algorithm as `String.hashCode()`). On cache hit, the cached String
instance is returned without calling `substring()` — zero allocation.

Interned keys enable **reference equality** in `JqObject.get()`:
`String.equals()` short-circuits on `this == other` (one pointer comparison
instead of character-by-character comparison).

Pre-computed JSON key forms (`"\"key\":"`) eliminate `escapeJson` scanning
during serialization. One `sb.append()` call replaces three appends plus
a per-character escape check.

**Impact:** +49% round-trip serialization. +21% on 127-key object queries.

### Parser: Direct Digit Accumulation

Integer and decimal numbers are parsed directly into `long` / `double`
accumulators without `substring()` or `new BigDecimal()`. Only numbers with
>15 significant digits fall back to BigDecimal.

**Impact:** 7.5x faster on number-heavy data. -64% parse allocation.

### Parser: byte[]-Based Parsing with SWAR

`JqValues.parse(byte[])` parses UTF-8 bytes directly without constructing
an intermediate `String`. String scanning uses SWAR (SIMD Within A Register)
techniques from Netty's `SWARUtil` pattern — finding `"` and `\` in 8 bytes
per iteration.

**Impact:** +15-37% faster than the String parser. 1.9x faster than Jackson.

### VM: Whole-Program Shape Detection

Common jq expression shapes are detected at compile time and bypass the
VM loop entirely:

| Shape | Pattern | Bypasses |
|-------|---------|----------|
| IDENTITY | `.` | VM loop, stack operations |
| FIELD_ACCESS | `.fieldname` | VM loop, stack operations |
| FIELD_ACCESS2 | `.a.b` | VM loop, stack operations |
| PIPE_FIELD_ARITH | `.a \| . + 1` | VM loop (partially) |

54% of h5m production jq expressions are single field access.

### VM: Fused Iteration Opcodes

Common iteration patterns compile to single fused opcodes that bypass
the FORK/BACKTRACK generator mechanism:

| Pattern | Fused opcode | Speedup |
|---------|-------------|---------|
| `[.[] \| expr]` | COLLECT_ITERATE | 3.5x vs jackson-jq |
| `[.[] \| select(cond) \| expr]` | COLLECT_SELECT_ITERATE | 7.9x |
| `reduce .[] as $x (init; body)` | REDUCE_ITERATE | 15.8x |

### Data Structure: Parallel Array JqObject

`JqObject` stores fields as parallel `String[] keys` + `JqValue[] values`
arrays instead of `LinkedHashMap`. Benefits:

- **Cache-friendly** — sequential array access vs pointer chasing
- **No Entry objects** — ~3x less memory per object
- **Linear scan for small objects** (≤32 keys) — faster than HashMap for
  typical 3-10 field JSON objects
- **Hash index for large objects** (>32 keys) — lazily built `HashMap<String, Integer>`
  for O(1) lookup. Built eagerly at parse time for pre-parsed documents.

### Serialization: Thread-Local StringBuilder Reuse

`JqValues.serialize()` uses a thread-local `StringBuilder` that grows once
and is reused across calls. The `appendTo(StringBuilder)` method on each
value type writes directly into the shared buffer — no intermediate
StringBuilder allocation for nested structures.

**Impact:** -86% serialization allocation (316 MB -> 45.1 MB on 14MB file).

## Best Practices

### Compile once, apply many times

```java
// Good: compile once, reuse across threads
static final JqProgram PROGRAM = JqProgram.compile(".users[] | .name");

// Bad: recompiles on every call
String name = JqProgram.compile(".name").apply(input).toJsonString();
```

### Prefer byte[] input

```java
// Faster: parse from byte[] (avoids intermediate String)
JqValue data = JqValues.parse(fileBytes);

// Slower: parse from String (requires String construction from bytes)
JqValue data = JqValues.parse(new String(fileBytes, UTF_8));
```

### Use Builder for object construction

```java
// Good: direct array construction, no LinkedHashMap
JqObject obj = JqObject.builder()
    .put("name", "Alice")
    .put("age", 30)
    .build();

// Less efficient: goes through LinkedHashMap
var map = new LinkedHashMap<String, JqValue>();
map.put("name", JqString.of("Alice"));
map.put("age", JqNumber.of(30));
JqObject obj = JqObject.ofTrusted(map);
```

### Use streaming serialization for large outputs

```java
// Good: writes directly to OutputStream (no intermediate String)
JqValues.serializeTo(value, outputStream);

// Less efficient: creates intermediate String + byte[]
outputStream.write(value.toJsonString().getBytes(UTF_8));
```

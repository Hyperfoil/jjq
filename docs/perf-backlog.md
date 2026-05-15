# jjq Performance Backlog

This document tracks prioritized optimization candidates for jjq with explicit
measurement and decision criteria.

## Priority List

1. ~~Adaptive lazy-vs-eager conversion threshold in Jackson adapter~~ — Done
2. ~~Reduce `LazyObjectMap.keySet()` allocation~~ — Done
3. ~~Remove lambda-based map operations in hot lazy paths~~ — Done
4. ~~Expand bytecode shape fusion only from real frequency data~~ — Done
5. ~~Explore additional conservative subtree passthrough in serialization~~ — Done
6. ~~Optimize built-in JSON parser and serialization~~ — Done
7. ~~Cache VirtualMachine per thread in JqProgram~~ — Done
8. Deep field access chain shape (3+ levels)
9. Profile real h5m benchmark runs with async-profiler

---

## Completed Items

### 1) Adaptive Lazy-vs-Eager Threshold — Done

Implemented configurable threshold (`jjq.jackson.lazy.eagerObjectThreshold`,
default 16). Objects with field count <= threshold are converted eagerly;
larger objects use `LazyObjectMap` wrappers. Benchmarked with JMH across
object sizes 5/20/100 and access patterns identity/singleField/multiField.

### 2) `LazyObjectMap.keySet()` Allocation Reduction — Done

`keySet()` now caches an immutable `Collections.unmodifiableSet()` on first
call and returns the same instance on subsequent calls. The `ObjectNode`
backing the lazy map is treated as immutable, so the cached set never goes
stale.

Biggest impact: `JqValue.compareTo()` calls `keySet()` twice per comparison,
O(n log n) times during sort operations. Previously each call allocated a new
`LinkedHashSet`; now zero allocations after the first.

### 3) Replace Lambda-Based Map Operations in Hot Paths — Done

Replaced `computeIfAbsent` with capturing lambdas in `ensureFullyConverted()`
in both Jackson and fastjson2 `LazyObjectMap` implementations. Now uses
explicit `containsKey` + `put` branches, eliminating per-iteration lambda
allocation.

### 4) Expand Shape Fusion from Real Workload Data — Done

Generalized `tryEmitCollectIterate`, `tryEmitCollectSelectIterate`, and
`tryEmitFusedReduce` in the compiler to accept any single-output iteration
source, not just bare `.[]` (`IdentityExpr`). The compiler now walks left
through left-associative pipe chains to find the `IterateExpr` and collects
all right-hand parts as the body.

This covers 30% of real h5m production jq expressions (7 of 23) which use the
`[.results[].load.avThroughput]` pattern. These now compile to
`COLLECT_ITERATE` (7 instructions) instead of `FORK/BACKTRACK` (15
instructions), eliminating backtracking overhead entirely.

### 5) Additional Conservative Subtree Passthrough — Done

Implemented identity passthrough in `JacksonJqEngine`: when a jq filter
returns the same `JqValue` instance as the input (reference equality), the
original `JsonNode` is returned directly without reconstruction. This
complements the existing `originalNodeIfLazy` passthrough for lazy sub-trees.

### 6) Optimize Built-in JSON Parser and Serialization — Done

Parser improvements:
- Replaced `int[]` position tracking with mutable `JsonReader` field (avoids
  array indirection on every character)
- Fast-path string parsing: `substring()` for strings without escape
  characters, avoiding `StringBuilder` allocation entirely
- Inline whitespace checks (`c == ' ' || c == '\n'` etc.) instead of
  `Character.isWhitespace()` method call
- Direct integer parsing into `long` accumulator, avoiding
  `substring` + `Long.parseLong` for most numbers

Serialization improvements:
- `escapeJson` scans for clean segments and appends in bulk via
  `sb.append(s, start, end)` instead of char-by-char
- `JqString.toJsonString()` skips `StringBuilder` for strings without
  characters that need escaping
- `JqArray.toJsonString()` pre-sizes its `StringBuilder`

Result: ~10-14% improvement on 67KB inputs in native-image CLI benchmarks.

### 7) Cache VirtualMachine per Thread in JqProgram — Done

`JqProgram` now holds a `ThreadLocal<VirtualMachine>` that is lazily created
once per thread and reused across all subsequent `apply`/`applyAll` calls.
The VM resets all state on each `execute()` call, so reuse is safe.

Eliminates 67+ object allocations per invocation (64 `BacktrackPoint` objects,
stack arrays, `Evaluator`) which were the dominant overhead for simple
expressions that complete in single-digit nanoseconds. For the h5m use case
(cached `JqProgram`, repeated calls on the same thread), the
`JqProgram.apply()` path is now nearly as fast as using a pre-constructed
`VirtualMachine` directly.

### Real Workload Data from h5m

h5m (Horreum rewrite) uses jjq for its DAG-based JSON transformation pipeline.
Profiling a benchmark of 100 uploads with 24 JQ nodes (qvss test) and a
legacy import of rhivos-perf-comprehensive (5 uploads, 23 JQ nodes) shows
three dominant expression shapes:

#### Shape 1: Single field access — 54% of all executions

```
.field
.nested.field.path
```

Examples from h5m (sorted by frequency):
```
.avThroughput           # 1100x — extract scalar from dataset object
.runtime                # 1100x — extract scalar from dataset object
.env.BUILD_ID           # 100x  — nested field access (2 levels)
.config.QUARKUS_VERSION # 100x  — nested field access (2 levels)
.results."quarkus3-jvm".load.avThroughput  # 100x — 4 levels with quoted key
```

Bytecode shape: `LOAD_INPUT → DOT_FIELD(name)` (or `DOT_FIELD2` for 2
levels). Already handled by `FIELD_ACCESS` and `FIELD_ACCESS2` whole-program
shapes which bypass the VM interpreter loop entirely.

#### Shape 2: Array construction with nested iteration — 33% of executions

```
[.results[].path.to.field]
```

Examples from h5m:
```
[.results[].rss.avStartupRss]          # extract nested field from each result
[.results[].load.avThroughput]         # same pattern, different path
[.results[].build.avBuildTime]         # same pattern, different path
[.results[].load.maxThroughputDensity] # same pattern, different path
[.results | to_entries[] | .key]       # variation: object → entries → keys
```

Now compiled to `COLLECT_ITERATE` fused opcode (item 4). The body also
benefits from `DOT_FIELD2` fusion, so `.load.avThroughput` becomes a single
instruction within the iterate body.

#### Shape 3: Object construction with variable binding — 4% of executions

```
. as $top | .results | keys[] as $key | { field: .[$key].path, ... }
```

This is the most complex per-call expression. It runs via the general VM
interpreter with tree-walker fallback for variable binding and conditionals.
Less amenable to shape fusion due to its dynamic nature, but the absolute
execution count (100x per benchmark) means it contributes little to overall
runtime.

---

## Future Items

### 8) Deep Field Access Chain Shape (3+ levels)

#### Goal

Add a whole-program shape for field access chains deeper than 2 levels,
avoiding the general VM interpreter loop for expressions like
`.results."quarkus3-jvm".load.avThroughput` (4 levels).

#### Current State

The VM currently has three whole-program fast-path shapes:
- `FIELD_ACCESS`: `.field` (1 level, 4 instructions)
- `FIELD_ACCESS2`: `.field.field` (2 levels, 4 instructions)
- `PIPE_FIELD_ARITH`: `.field | . + CONST` (field + arithmetic)

Expressions with 3+ field levels fall through to `GENERAL` and run through the
full VM interpreter loop. The compiler already emits `DOT_FIELD2` for
consecutive 2-level pairs, so `.a.b.c.d` compiles to
`LOAD_INPUT, DOT_FIELD2 a.b, SET_INPUT_PEEK, NOP, DOT_FIELD2 c.d, OUTPUT, HALT`
(7 instructions). This runs through the interpreter, which is fast but has
per-instruction dispatch overhead.

#### Candidate

Add `FIELD_ACCESS_N` shape detection that matches any sequence of `DOT_FIELD`
and `DOT_FIELD2` instructions followed by `OUTPUT, HALT`. The fast path would
chain field lookups directly in a loop, avoiding all stack manipulation.

#### Criteria

- Only proceed if profiling shows the VM interpreter dispatch overhead is
  measurable relative to the field lookup cost.
- h5m currently has ~100 executions of 4-level paths per benchmark run. At
  ~7ns per execution via the interpreter, total time is ~0.7μs — unlikely to
  be a bottleneck. May become relevant if h5m adds more complex path
  expressions or processes higher volumes.

### 9) Profile Real h5m Benchmark Runs with async-profiler

#### Goal

Identify actual CPU and allocation hotspots in a representative h5m pipeline
benchmark rather than relying on synthetic JMH microbenchmarks.

#### Candidate

Run the h5m qvss upload benchmark (100 uploads, 24 JQ nodes) under
async-profiler with both CPU and allocation events:

```bash
# CPU flamegraph
java -agentpath:libasyncProfiler.so=start,event=cpu,file=cpu.html ...

# Allocation flamegraph
java -agentpath:libasyncProfiler.so=start,event=alloc,file=alloc.html ...
```

Focus areas:
- Confirm that jjq evaluation is no longer a significant fraction of total
  pipeline time (expect database I/O and Jackson serialization to dominate)
- Identify any remaining allocation hotspots in the jjq ↔ Jackson conversion
  path
- Check whether `ThreadLocal` VM caching is effective under Quarkus's thread
  model (virtual threads, event loop threads)

#### Criteria

- This is an investigative task, not a go/no-go optimization. Run when h5m's
  benchmark infrastructure is stable enough for reproducible profiling.
- Any jjq-specific finding that shows >= 5% of CPU or allocation time should
  be promoted to a new backlog item with specific measurement criteria.

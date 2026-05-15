# jjq Performance Backlog

This document tracks prioritized optimization candidates for jjq with explicit
measurement and decision criteria.

## Priority List

1. Adaptive lazy-vs-eager conversion threshold in Jackson adapter
2. Reduce `LazyObjectMap.keySet()` allocation
3. Remove lambda-based map operations in hot lazy paths
4. Expand bytecode shape fusion only from real frequency data
5. Explore additional conservative subtree passthrough in serialization

## 1) Adaptive Lazy-vs-Eager Threshold (Current)

### Goal

Avoid lazy-wrapper overhead for small JSON objects where eager conversion is
often faster and cheaper overall.

### Scope

- Module: `jjq-jackson`
- Entry point: `JacksonConverter.fromJsonNodeLazy(...)`
- Initial target: object conversion policy

### Proposed Behavior

- For objects with field count <= threshold: convert eagerly.
- For objects with field count > threshold: keep lazy `LazyObjectMap` wrapper.

### Initial Default

- `8` fields (configurable for experiments)

### Measurements

Use both synthetic and end-to-end measurements:

1. JMH-style microbench matrix
   - object sizes: `5, 10, 20, 50, 100`
   - field access ratio: `10%, 50%, 90%`
   - filters: pass-through `.`, single field `.a`, multi-field `{a,b}`
2. End-to-end pipeline benchmarks (e.g. h5m representative flow)
   - allocation profile (`async-profiler --alloc --total`)
   - throughput and wall-clock latency

### Go/No-Go Criteria

- Go when:
  - >= 8% speedup or >= 10% allocation reduction in at least two common
    scenarios, and
  - no regressions > 3% in other representative scenarios.
- Stop/adjust when:
  - results are flat/noisy, or
  - regressions dominate expected usage patterns.

## 2) `LazyObjectMap.keySet()` Allocation Reduction

### Goal

Avoid per-call key set allocation in key-heavy operations.

### Candidate

- Return a cached immutable key view, or
- Implement a lightweight custom key set view over `fieldNames()` iterator.

### Criteria

- Keep insertion order and semantics unchanged.
- Proceed only with measurable allocation reduction in key-heavy benchmarks.

## 3) Replace Lambda-Based Map Operations in Hot Paths

### Goal

Reduce overhead from `computeIfAbsent`/capturing lambdas in tight conversion
loops.

### Candidate

- Prefer explicit `get` + `put` branches in hot code paths.

### Criteria

- Keep code readable.
- Keep only if profiling shows a measurable hotspot improvement.

## 4) Expand Shape Fusion from Real Workload Data

### Goal

Only add compiler/VM specialization where expression frequency justifies
complexity.

### Candidate

- Gather expression-shape frequency from real workload traces.
- Implement top 1-2 missing high-frequency shapes.

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

Bytecode shape: `LOAD_INPUT → FIELD_ACCESS(name)` (repeated for nested).
This is the simplest and most frequent pattern. A fused instruction like
`FIELD_ACCESS_CHAIN(name1, name2, ...)` could skip intermediate JqObject
allocation for multi-level access.

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

Bytecode shape: `LOAD_INPUT → FIELD_ACCESS → ITERATE → FIELD_ACCESS(n) →
COLLECT_ARRAY`. This is the second most common pattern in h5m, used to
extract parallel arrays from nested objects (e.g., all throughput values
across runtimes). Each execution allocates intermediate JqObject/JqArray
wrappers for the iteration that are discarded after collection.

A fused instruction for `[.field[].nested.path]` could avoid intermediate
wrapper allocation by iterating the source ArrayNode/ObjectNode directly
and extracting the nested field into the result array.

#### Shape 3: Object construction with variable binding — 4% of executions

```
. as $top | .results | keys[] as $key | { field: .[$key].path, ... }
```

Example from h5m (the "improved jq nodes" dataset generator):
```jq
. as $top |
.results | keys[] as $key |
{
  runtime: $key | split("-")[0],
  buildType: $key | split("-")[1],
  rssStartup: .[$key].rss.avStartupRss,
  avThroughput: .[$key].load.avThroughput,
  buildId: $top.env.BUILD_ID,
  version: (if $key | startswith("spring")
            then $top.config.SPRING_BOOT_VERSION
            else $top.config.QUARKUS_VERSION end)
}
```

This is the most complex and expensive per-call expression. It runs 100x
(once per upload) but produces multiple output values per input (one per
runtime key). Less amenable to shape fusion due to variable binding and
conditionals, but the `keys[] as $key | { field: .[$key].path }` pattern
is common enough in Horreum-style data transforms to warrant investigation.

### Criteria

- Target >= 10% runtime improvement on representative workloads.

## 5) Additional Conservative Subtree Passthrough

### Goal

Avoid unnecessary reconstruction when subtrees are demonstrably unchanged.

### Candidate

- Extend identity-style passthrough with strict correctness guards.

### Criteria

- No semantic changes.
- Measurable reduction in `ObjectNode`/`LinkedHashMap$Entry` allocations.

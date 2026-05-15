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

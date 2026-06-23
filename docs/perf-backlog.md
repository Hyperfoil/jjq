# jjq Performance Backlog

This document tracks optimization work, completed items, and remaining
candidates.

## Completed Optimizations

### Phase 1: Foundation (Issues #4, #5, #9)

| # | Optimization | Impact |
|---|-------------|--------|
| #4 | Baseline performance characterization infrastructure | 9 benchmark classes, profiling recipes, JOL analysis |
| #5 | Value type allocation reduction (sorted key cache, BigDecimal cache, iterator equals, parser pre-sizing) | +26% on small_singleField |
| #9 | Thread-local StringBuilder reuse for serialization + `appendTo()` primitive | +95% serialize speed, -86% allocation on 14MB |

### Phase 2: Parser Optimizations (Issues #14, #13, #11)

| # | Optimization | Impact |
|---|-------------|--------|
| #14 | Direct digit accumulation for decimals, defer BigDecimal, fix `of(double)` | 7.5x faster on number-heavy data |
| #13 | Deferred string values with zero-copy `appendTo()` for no-escape strings | +131% string serialization, -44% parse allocation |
| #11 | byte[]-based parser + SWAR string scanning + ASCII fast path investigation | 1.9x faster than Jackson on 14MB production file |

### Phase 3: Data Structure Optimizations (Issues #12, #19)

| # | Optimization | Impact |
|---|-------------|--------|
| #12 | Parallel arrays replacing LinkedHashMap in JqObject + hash index for >32 keys | +45% prog_fieldAccess, 85% of Jackson access speed |
| #19 | Eager hash at parse time, cached sortedKeysAsArray, Builder for BUILD_OBJECT | 33x faster on `keys`, +31% objectConstruct |

### Phase 4: Profiling-Driven Optimizations (Issues #20, #22, #10)

| # | Optimization | Impact |
|---|-------------|--------|
| #20 | Copy-then-mutate refactor to with/without/merge/deepMerge | Eliminated all LinkedHashMap intermediates from mutation paths |
| #22 | Compile IndexExpr with literal index, putUnchecked for unique layouts, push pre-check, type-specialized appendTo, eager hash index | +50% deepField, +47% objectConstruct, eliminated evaluator fallback |
| #10 | Field name interning with incremental hash + pre-computed JSON key serialization | +49% round-trip serialization, +21% extractMetric, eliminated escapeJson for keys |

### Infrastructure (Issues #16, #17, #18, #21)

| # | Optimization | Impact |
|---|-------------|--------|
| #16 | Copy-on-write mutation API: Builder, with/without/merge/deepMerge, ArrayBuilder | Prerequisite for h5m#150 |
| #17 | jjq-jakarta module: Hibernate FormatMapper + JAX-RS providers | Prerequisite for h5m#150 |
| #18 | Production-scale jq query benchmarks (15 benchmarks on 14MB, async-profiler) | Validated all optimizations at scale |
| #21 | Streaming serialization to OutputStream/Writer | Eliminates 2 of 3 copies for large serialization |

## Open Items

### High Priority

| # | Item | Status |
|---|------|--------|
| #23 | Compile more expression types to bytecode (TryCatchExpr, OptionalExpr, SliceExpr) | Open — Phase 1 targets h5m's `try/catch` patterns |
| #22 | Remaining CPU hotspots (5 identified from flame graphs) | Partially addressed |

### Medium Priority

| # | Item | Status |
|---|------|--------|
| #15 | vm_pipeAndArith -14% regression investigation | Open — likely JIT artifact from JqString size increase |
| #6 | Tape-based document representation | Open — parallel arrays + deferred strings captured most benefits |
| #7 | Branchless parsing techniques (simdjson-inspired) | Open — partially covered by #11 SWAR |

### Low Priority / Investigation

| # | Item | Status |
|---|------|--------|
| #8 | FFM + Java 25 feature exploration | Open — Compact Object Headers worth benchmarking |

## Performance Journey Summary

### Parsing (vs Jackson, 14MB production file)

| Stage | Speed vs Jackson | Parse allocation |
|-------|-----------------|-----------------|
| Original | 130% (String) | 62.8 MB |
| After #14 (numbers) | 173% | 40.7 MB |
| After #13 (deferred strings) | 213% | 40.7 MB |
| After #11 (byte[] parser) | 191% (byte[] vs Jackson byte[]) | 40.7 MB |
| After #10 (interning) | ~200% (estimated) | ~38 MB |

### Serialization (14MB production file)

| Stage | Allocation | vs Jackson |
|-------|-----------|------------|
| Original | 316 MB/op | 40% of Jackson speed |
| After #9 (StringBuilder reuse) | 45.1 MB/op | 78% of Jackson |
| After #13 (deferred strings) | 45.1 MB/op | 100% of Jackson |
| After #10 (pre-computed JSON keys) | 45.1 MB/op | ~112% (estimated) |

### Production Query Execution (14MB, post all optimizations)

| Query | Time | Allocation |
|-------|------|------------|
| `.user` (top-level field) | 5.3 ns | 0 B |
| `.data[0].results` (4-level deep) | 63 ns | 0 B |
| `keys` (127-key object) | 69 ns | 0 B |
| `{user, uuid, ...}` (object construct) | 95 ns | 168 B |
| `[.data[] \| .sample_uuid]` (iterate + extract) | 151 ns | 80 B |
| `.` identity round-trip (14MB serialize) | 13.4 ms | 45.1 MB |

## Research References

- simdjson paper (Langdale & Lemire, VLDB 2019) — tape format, branchless algorithms
- fastjson2 source analysis — Unsafe string access, thread-local buffers, ASM codegen
- Jackson 2.x/3.x analysis — deferred parsing, name canonicalization, RecyclerPool
- Netty SWARUtil — production Java SWAR patterns
- Cameron's Parabix (CASCON 2008) — parallel bit stream theory
- Lemire's SWAR digit parsing — 8-digit conversion in 6 operations
- Parquet SIMD article (Del Monte, 2026) — baseline matters, SuperWord can regress

# Jackson Lazy Threshold Tuning

This document describes how to tune the Jackson small-object eager conversion
threshold introduced in `LazyJacksonConverter`.

## What is being tuned

Property:

- `jjq.jackson.lazy.eagerObjectThreshold`

Meaning:

- Objects with field count `<= threshold` are converted eagerly.
- Objects with field count `> threshold` stay lazily wrapped.

Default:

- `16`

## Build benchmark jar

```bash
mvn -pl jjq-core,jjq-jackson,jjq-benchmark -am package -DskipTests
```

## Run throughput benchmark per threshold

Example thresholds:

- `0` (always lazy)
- `8`
- `12`
- `16`

Run command template:

```bash
java --enable-preview \
  -Djjq.jackson.lazy.eagerObjectThreshold=<THRESHOLD> \
  -jar jjq-benchmark/target/jjq-benchmark-0.1.3-SNAPSHOT.jar \
  JacksonThresholdBenchmark.small_.* \
  -wi 3 -i 5 -f 1
```

## Run allocation profile per threshold (async-profiler)

Using local async-profiler from `../async-profiler/build`:

```bash
java --enable-preview \
  -Djjq.jackson.lazy.eagerObjectThreshold=<THRESHOLD> \
  -jar jjq-benchmark/target/jjq-benchmark-0.1.3-SNAPSHOT.jar \
  JacksonThresholdBenchmark.small_.* \
  -wi 2 -i 3 -f 1 \
  -prof "async:libPath=../async-profiler/build/lib/libasyncProfiler.so;event=alloc;output=text"
```

## Isolated throughput results (VM reuse)

`JacksonThresholdBenchmark` reuses precompiled `VirtualMachine` instances to
remove per-invocation VM construction overhead from `JqProgram.apply(...)`.

### Small object (5 fields), ops/us

| Threshold | `small_identity_roundtrip` | `small_multiField` | `small_singleField` |
|---|---:|---:|---:|
| 0 | 4.727 | 2.413 | 9.980 |
| 8 | 4.247 | 2.204 | 10.317 |
| 12 | 4.924 | 2.322 | 9.702 |
| 16 | 4.897 | 2.293 | 10.022 |

### Medium object (20 fields), ops/us

| Threshold | `medium_identity_roundtrip` | `medium_multiField` | `medium_singleField` |
|---|---:|---:|---:|
| 0 | 87.830 | 2.570 | 21.937 |
| 8 | 88.325 | 2.442 | 25.680 |
| 12 | 87.196 | 2.478 | 23.508 |
| 16 | 88.905 | 2.435 | 24.006 |

### Large object (100 fields), ops/us

| Threshold | `large_identity_roundtrip` | `large_multiField` | `large_singleField` |
|---|---:|---:|---:|
| 0 | 87.440 | 2.428 | 22.844 |
| 8 | 87.747 | 2.447 | 23.353 |
| 12 | 87.922 | 2.541 | 23.818 |
| 16 | 88.221 | 2.467 | 24.029 |

No threshold is best across all workloads.

## Isolated allocation profile summary

Method: `-prof async` with `event=alloc`, `-wi 1 -i 2`, benchmark pattern
`JacksonThresholdBenchmark.*`, with Java 25 and native access enabled.

- Dominant allocators are stable across thresholds: `LinkedHashMap`,
  `LinkedHashMap$Entry`, `LazyObjectMap`, and `JqObject`.
- `identity_roundtrip` (medium/large) allocates mostly lazy wrapper setup:
  `LinkedHashMap` ~57%, `LazyObjectMap` ~28%, `JqObject` ~14% for all thresholds.
- `multiField` is dominated by entry/map churn during object construction and
  serialization (`LinkedHashMap$Entry` ~45%, `LinkedHashMap` ~18%).
- `singleField` keeps the same hotspot shape across thresholds; no threshold
  introduces a new dominant allocation source.

## Current recommendation

- Data still does not support a single unambiguous winner.
- By aggregate geometric mean across all 9 isolated throughput scenarios,
  `16` is highest, `12` is a close second, `0` and `8` are lower.
- `8` is strongest for `medium_singleField`, but weaker on several
  identity/multi-field scenarios.
- Decision: switch default threshold to `16` as the best overall balanced
  choice from the current benchmark set.

## Record sheet

| Threshold | Throughput summary | Allocation hotspots summary | Notes |
|---|---|---|---|
| 0 |  |  |  |
| 8 |  |  |  |
| 12 |  |  |  |
| 16 | Selected default | Selected default | Best aggregate in isolated sweep |

## Decision guidance

- Favor thresholds that improve both:
  - pass-through/read-heavy filters (e.g. `.`, `.name`), and
  - transformed-object filters.
- Avoid thresholds that regress common scenarios by more than 3%.

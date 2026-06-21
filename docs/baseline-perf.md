# jjq Performance Baseline

Recorded: 2026-06-21

## Environment

| Property | Value |
|----------|-------|
| JDK | OpenJDK 25.0.2 LTS (Temurin) |
| CPU | AMD Ryzen 7 7800X3D 8-Core (Zen 4, 96MB L3 cache) |
| OS | Linux x86_64 (Fedora) |
| jjq version | 0.1.4-SNAPSHOT |
| JMH | 1.37 |
| Settings | 3 warmup, 5 measurement, 1 fork |

All scores in **ops/us** (higher is better). Error is 99.9% CI.

## VM Execution (JjqBenchmark)

Core bytecode VM throughput on pre-parsed values.

### Shape fast paths (bypass VM interpreter entirely)

| Benchmark | ops/us | Error | Alloc (B/op) |
|-----------|-------:|------:|-------------:|
| `vm_identity` (`.`) | 450.4 | 10.1 | 24 |
| `vm_fieldAccess` (`.name`) | 168.1 | 2.7 | 24 |
| `vm_chainedField` (`.a.b`) | 89.4 | 1.3 | -- |
| `vm_pipeAndArith` (`.a \| . + 1`) | 111.8 | 2.2 | -- |

### Fused iteration opcodes

| Benchmark | ops/us | Error | Alloc (B/op) |
|-----------|-------:|------:|-------------:|
| `vm_iterateMap` (`[.[] \| . * 2]`, 10 elem) | 22.9 | 0.6 | -- |
| `vm_iterateMap_medium` (100 elem) | 4.7 | 0.1 | -- |
| `vm_collectIterateField` (`[.results[].load.avThroughput]`) | 6.9 | 0.1 | -- |
| `vm_complexFilter` (`[.[] \| select(. > 5) \| . * 2]`) | 2.1 | 0.0 | -- |
| `vm_reduce` (`reduce .[] as $x (0; . + $x)`) | 41.2 | 0.3 | -- |

### Inlined builtins

| Benchmark | ops/us | Error |
|-----------|-------:|------:|
| `vm_builtinLength` | 64.3 | 0.9 |
| `vm_builtinSort` (10 elem) | 20.8 | 0.3 |
| `vm_builtinAdd` (10 elem) | 21.2 | 0.3 |

### JqProgram.apply() path (includes VM caching via ThreadLocal)

| Benchmark | ops/us | Error | Alloc (B/op) |
|-----------|-------:|------:|-------------:|
| `prog_fieldAccess` (`.name`) | 134.6 | 0.8 | ~0 |
| `prog_reduce` (`reduce .[] as $x (0; . + $x)`) | 36.0 | 0.4 | ~0 |
| `prog_iterateMap` (`[.[] \| . * 2]`) | 20.6 | 0.4 | 120 |
| `prog_collectIterateField` (`[.results[].load.avThroughput]`) | 6.3 | 0.6 | 104 |

### Parse + compile

| Benchmark | ops/us | Error |
|-----------|-------:|------:|
| `parse_simple` (`.name`) | 19.9 | 0.1 |
| `parse_complex` (`[.[] \| select(. > 5) \| . * 2 + 1]`) | 2.6 | 0.0 |

## JSON Parser (JjqParserBenchmark)

`JqValues.parse()` throughput on varied input profiles.

### By profile (10KB input)

| Profile | ops/us | ~us/parse | ~MB/s |
|---------|-------:|----------:|------:|
| `strings` (user records) | 0.049 | 20.4 | 490 |
| `numbers` (time series) | 0.004 | 250 | 40 |
| `nested` (h5m-style) | 0.008 | 125 | 80 |
| `flat` (mixed scalars) | 0.004 | 250 | 40 |

### By size (strings profile)

| Size | ops/us | ~us/parse | ~MB/s |
|------|-------:|----------:|------:|
| 1KB | 0.470 | 2.1 | 476 |
| 10KB | 0.049 | 20.4 | 490 |
| 100KB | 0.005 | 200 | 500 |
| 1MB | 0.001 | 1000 | 1000 |

Parser throughput scales linearly with input size (~500 MB/s for string-heavy
input). Number-heavy and flat inputs are slower (~40 MB/s at 10KB) due to
`BigDecimal` allocation for decimal numbers.

## Round-Trip (JjqParserBenchmark)

Full pipeline: `JqValues.parse(json)` -> `program.apply(input)` -> `result.toJsonString()`.
All on ~10KB input.

| Benchmark | ops/us | ~us/op |
|-----------|-------:|-------:|
| `roundTrip_identity` (`.`) | 0.021 | 47.6 |
| `roundTrip_fieldAccess` (`[.[] \| .name]`) | 0.041 | 24.4 |
| `roundTrip_objectConstruct` (`[.[] \| {name, dept}]`) | 0.027 | 37.0 |
| `roundTrip_collectIterate` (`[.[] \| .cpu]`) | 0.004 | 250 |

## Jackson Integration (JjqParserBenchmark + JacksonThresholdBenchmark)

### JacksonJqEngine round-trip (JsonNode -> JqValue -> VM -> JsonNode)

| Benchmark | ops/us | Notes |
|-----------|-------:|-------|
| `jackson_roundTrip_identity` (`.` on 10KB nested) | 37.0 | Lazy passthrough |
| `jackson_roundTrip_fieldAccess` (`.[0].config.QUARKUS_VERSION`) | 0.9 | Single scalar extraction |
| `jackson_roundTrip_collectIterate` (`[.[0].results[].load.avThroughput]`) | -- | Not in this run |

### Lazy-vs-eager threshold (JacksonThresholdBenchmark)

| Object size | Identity | Single field | Multi field |
|-------------|--------:|-----------:|----------:|
| Small (5 fields, eager) | 9.6 | 17.2 | 4.6 |
| Medium (20 fields, lazy) | 147.8 | 41.4 | 4.8 |
| Large (100 fields, lazy) | 145.4 | 42.0 | 4.8 |

Large and medium objects with lazy conversion are **15x faster** for identity
round-trip than small objects (which are eagerly converted and must be
reconstructed on output).

## jjq vs jackson-jq (JjqVsJacksonJqBenchmark)

| Benchmark | jackson-jq | jjq VM | Ratio |
|-----------|----------:|-------:|------:|
| identity (`.`) | 251.9 | 453.3 | **1.8x** |
| fieldAccess (`.foo`) | 72.7 | 141.5 | **1.9x** |
| pipeArith (`.a \| . + 1`) | 30.9 | 93.6 | **3.0x** |
| iterateMap (`[.[] \| . * 2]`, 10) | 5.1 | 23.8 | **4.7x** |
| iterateMap (100 elem) | 0.6 | 4.7 | **8.2x** |
| complexFilter | 0.6 | 2.1 | **3.5x** |
| reduce | 2.8 | 41.2 | **14.5x** |
| parse simple | 0.2 | 19.8 | **92x** |
| parse complex | 0.2 | 2.6 | **12x** |

### Using executeOne() (zero-allocation single result)

| Benchmark | execute() | executeOne() | Speedup |
|-----------|----------:|-------------:|--------:|
| identity | 453.3 | 1838.1 | 4.1x |
| fieldAccess | 141.5 | 151.4 | 1.1x |
| pipeArith | 93.6 | 116.4 | 1.2x |

`executeOne()` avoids `List.of()` wrapper allocation. The identity case
benefits most because the `List.of()` overhead is significant relative to
the near-zero evaluation cost.

## Key Allocation Metrics

| Benchmark | Alloc (B/op) | Notes |
|-----------|-------------:|-------|
| `prog_fieldAccess` | ~0 | Shape fast path, no allocation |
| `prog_reduce` | ~0 | Fused reduce, scalar result |
| `vm_identity` | 24 | `List.of()` wrapper |
| `vm_fieldAccess` | 24 | `List.of()` wrapper |
| `prog_collectIterateField` | 104 | Result array + `List.of()` |
| `prog_iterateMap` | 120 | Result array + element wrappers |

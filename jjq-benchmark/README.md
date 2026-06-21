# jjq Benchmark Module

JMH benchmarks for measuring jjq performance across the execution pipeline:
parser, bytecode VM, Jackson integration, and end-to-end round trips.

## Benchmark Classes

| Class | Benchmarks | What it measures |
|-------|-----------|------------------|
| `JjqBenchmark` | 18 | Core VM execution (shapes, builtins, fused iteration) and `JqProgram.apply()` path |
| `JjqParserBenchmark` | 23 | JSON parser throughput, full round-trips, and Jackson integration |
| `JjqAllocBenchmark` | 15 | Allocation-heavy workloads (object construction, iteration, try-catch) |
| `JacksonThresholdBenchmark` | 9 | Jackson lazy-vs-eager threshold at different object sizes |
| `JjqVsJacksonJqBenchmark` | 18 | Head-to-head comparison with jackson-jq library |

## Building

```bash
mvn package -pl jjq-core,jjq-jackson,jjq-benchmark -DskipTests
```

## Running

### All benchmarks

```bash
java -jar target/jjq-benchmark-*.jar
```

### Specific class

```bash
java -jar target/jjq-benchmark-*.jar JjqBenchmark
```

### Specific benchmark (regex)

```bash
java -jar target/jjq-benchmark-*.jar "vm_fieldAccess$"
```

### Using the runner script

```bash
# Baseline-quality run (5 warmup, 10 measurement, 3 forks, JSON output)
./scripts/run-benchmarks.sh

# Quick sanity check (2 warmup, 3 measurement, 1 fork)
./scripts/run-benchmarks.sh --quick JjqBenchmark

# Specific benchmarks
./scripts/run-benchmarks.sh "parse_.*1kb"
```

## Profiling

See [docs/profiling-guide.md](../docs/profiling-guide.md) for detailed recipes
covering allocation profiling, CPU flame graphs, and hardware counters.

Quick examples:

```bash
# Allocation rate per operation
java -jar target/jjq-benchmark-*.jar JjqParserBenchmark -prof gc

# CPU flame graph (requires async-profiler)
java -jar target/jjq-benchmark-*.jar JjqBenchmark \
  -prof "async:output=flamegraph;event=cpu"

# Hardware counters (Linux)
java -jar target/jjq-benchmark-*.jar JjqBenchmark -prof perfnorm
```

## Benchmark Data

Parser benchmarks load JSON input from resource files in
`src/main/resources/benchmark-data/`. Four input profiles at four sizes:

| Profile | Description | Sizes |
|---------|-------------|-------|
| `strings-*.json` | Array of user objects with string-heavy fields | 1KB, 10KB, 100KB, 1MB |
| `numbers-*.json` | Array of measurement objects with numeric fields | 1KB, 10KB, 100KB, 1MB |
| `nested-*.json` | h5m-style nested objects with runtime results | 1KB, 10KB, 100KB, 1MB |
| `flat-*.json` | Flat array of mixed scalars (ints, floats, strings, bools, nulls) | 1KB, 10KB, 100KB, 1MB |

## Key Benchmarks for the h5m Use Case

The h5m project uses jjq through the Jackson adapter. The most relevant
benchmarks for that path are:

- `prog_fieldAccess` -- `.name` via `JqProgram.apply()` (54% of h5m expressions)
- `prog_collectIterateField` -- `[.results[].load.avThroughput]` (33% of h5m expressions)
- `jackson_roundTrip_*` -- full `JsonNode -> JqValue -> VM -> JsonNode` round trip
- `JacksonThresholdBenchmark.*` -- lazy-vs-eager conversion at different object sizes

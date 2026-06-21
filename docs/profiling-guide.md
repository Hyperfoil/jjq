# jjq Profiling Guide

This guide covers how to profile jjq benchmarks for throughput, allocation rate,
CPU hotspots, and hardware counters.

## Prerequisites

- JDK 21+ with JMH benchmark jar built:
  ```bash
  mvn package -pl jjq-core,jjq-jackson,jjq-benchmark -DskipTests
  ```
- async-profiler (optional, for flame graphs): https://github.com/async-profiler/async-profiler
- Linux `perf` tool (optional, for hardware counters)

## Running Benchmarks

### Basic throughput

```bash
java -jar jjq-benchmark/target/jjq-benchmark-*.jar JjqBenchmark
```

### Filter by benchmark name (regex)

```bash
# Run only parser benchmarks
java -jar jjq-benchmark/target/jjq-benchmark-*.jar JjqParserBenchmark

# Run a specific benchmark
java -jar jjq-benchmark/target/jjq-benchmark-*.jar "JjqBenchmark.vm_fieldAccess$"

# Run all 1kb parser benchmarks
java -jar jjq-benchmark/target/jjq-benchmark-*.jar "parse_.*1kb"
```

### Export results as JSON (for comparison tooling)

```bash
java -jar jjq-benchmark/target/jjq-benchmark-*.jar JjqBenchmark -rf json -rff results.json
```

## Profiler Recipes

### Allocation Rate (`-prof gc`)

Shows bytes allocated per operation and GC count. Built into JMH, no extra
dependencies.

```bash
java -jar jjq-benchmark/target/jjq-benchmark-*.jar JjqParserBenchmark -prof gc
```

Output includes:
- `gc.alloc.rate.norm` -- bytes allocated per operation (normalized)
- `gc.count` -- number of GC pauses during measurement
- `gc.time` -- total GC pause time in milliseconds

### Allocation Flame Graphs (`-prof async:event=alloc`)

Shows which methods allocate the most. Requires async-profiler.

```bash
java -jar jjq-benchmark/target/jjq-benchmark-*.jar JjqParserBenchmark \
  -prof "async:libPath=/path/to/libasyncProfiler.so;output=flamegraph;event=alloc"
```

This produces an HTML flame graph per benchmark in the working directory.

If you have `asprof` on your PATH:

```bash
java -jar jjq-benchmark/target/jjq-benchmark-*.jar JjqParserBenchmark \
  -prof "async:output=flamegraph;event=alloc"
```

### CPU Flame Graphs (`-prof async:event=cpu`)

Shows which methods consume the most CPU time.

```bash
java -jar jjq-benchmark/target/jjq-benchmark-*.jar JjqBenchmark \
  -prof "async:output=flamegraph;event=cpu"
```

### Hardware Counters (`-prof perfnorm`, Linux only)

Shows instructions per operation, branch misses, cache misses, etc. Requires
Linux `perf` tool and appropriate permissions (`/proc/sys/kernel/perf_event_paranoid`).

```bash
java -jar jjq-benchmark/target/jjq-benchmark-*.jar JjqBenchmark -prof perfnorm
```

Output includes:
- `instructions` -- CPU instructions per operation
- `branches` -- branch instructions per operation
- `branch-misses` -- branch mispredictions per operation
- `L1-dcache-load-misses` -- L1 data cache misses per operation
- `cycles` -- CPU cycles per operation

### JFR CPU-Time Profiling (JDK 25+, `-prof jfr`)

JEP 509 adds CPU-time profiling to JFR. Available as an alternative to
async-profiler on JDK 25+.

```bash
java -jar jjq-benchmark/target/jjq-benchmark-*.jar JjqBenchmark -prof jfr
```

## Min-of-N Methodology

For trustworthy baseline measurements, use multiple forks and take the minimum.
Transient interference (GC, OS scheduling, CPU frequency scaling) increases
measured values, so the minimum is closest to the interference-free measurement.

### Recommended settings for baselines

```bash
java -jar jjq-benchmark/target/jjq-benchmark-*.jar JjqBenchmark \
  -wi 5 -i 10 -f 3 -rf json -rff baseline.json
```

This runs 5 warmup iterations, 10 measurement iterations, across 3 forks.
The minimum fork result approximates the best achievable throughput.

Use the `scripts/run-benchmarks.sh` script to automate this:

```bash
./scripts/run-benchmarks.sh            # run all benchmarks
./scripts/run-benchmarks.sh JjqBenchmark  # run specific class
```

## Compact Object Headers (JDK 25)

JEP 519 reduces object header size from 12-16 bytes to 8 bytes. To measure
the impact on jjq (which creates many small wrapper objects):

```bash
# Without compact headers (default)
java -jar jjq-benchmark/target/jjq-benchmark-*.jar JjqBenchmark \
  -rf json -rff baseline-default.json

# With compact headers
java -XX:+UseCompactObjectHeaders \
  -jar jjq-benchmark/target/jjq-benchmark-*.jar JjqBenchmark \
  -rf json -rff baseline-compact.json
```

Compare the two JSON result files to see the impact.

## GraalVM Native Image

For CLI-focused benchmarks, compare JVM vs native-image throughput:

```bash
# Build native binary
JAVA_HOME=~/.sdkman/candidates/java/25.0.1-graalce \
  mvn package -pl jjq-core,jjq-cli -Pnative -DskipTests

# Compare using hyperfine
hyperfine --warmup 5 -N \
  "jq '.name' input.json" \
  "./jjq-cli/target/jjq '.name' input.json"
```

Note: native-image benchmarking cannot use JMH (no JIT, no warmup). Use
`hyperfine` or shell-level timing instead.

## Interpreting Results

### Throughput (ops/us)

Higher is better. For the h5m use case, focus on:
- `prog_fieldAccess` -- most common expression shape (54%)
- `prog_collectIterateField` -- second most common (33%)
- `jackson_roundTrip_*` -- the actual h5m integration path

### Allocation rate (bytes/op)

Lower is better. High allocation rates cause GC pressure and reduce throughput.
Key indicators:
- `gc.alloc.rate.norm > 1000` bytes/op -- worth investigating
- Any benchmark showing GC pauses -- indicates allocation pressure

### Hardware counters

- **IPC** (instructions per cycle) > 2.0 is good; < 1.0 suggests memory stalls
- **Branch-misses** > 1% of total branches suggests unpredictable control flow
- **L1-dcache-load-misses** indicates data not fitting in cache

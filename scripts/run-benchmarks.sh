#!/usr/bin/env bash
#
# Run jjq JMH benchmarks with baseline-quality settings.
# Results are saved as JSON for reproducible comparison.
#
# Usage:
#   ./scripts/run-benchmarks.sh                     # all benchmarks
#   ./scripts/run-benchmarks.sh JjqBenchmark        # specific class
#   ./scripts/run-benchmarks.sh "parse_.*1kb"       # regex filter
#   ./scripts/run-benchmarks.sh --quick JjqBenchmark # fast run (fewer iterations)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
BENCHMARK_JAR="$PROJECT_DIR/jjq-benchmark/target/jjq-benchmark-0.1.4-SNAPSHOT.jar"
RESULTS_DIR="$PROJECT_DIR/benchmark-results"

# Default settings (use annotation defaults: 5 warmup, 5 measurement, 3 forks)
WARMUP=""
ITERATIONS=""
FORKS=""
FILTER=""
QUICK=false

# Parse arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        --quick)
            QUICK=true
            WARMUP=2
            ITERATIONS=3
            FORKS=1
            shift
            ;;
        *)
            FILTER="$1"
            shift
            ;;
    esac
done

# Build if jar doesn't exist
if [[ ! -f "$BENCHMARK_JAR" ]]; then
    echo "Building benchmark jar..."
    cd "$PROJECT_DIR"
    mvn package -pl jjq-core,jjq-jackson,jjq-benchmark -q -DskipTests
fi

# Create results directory
mkdir -p "$RESULTS_DIR"

# Generate output filename
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
JDK_VERSION=$(java -version 2>&1 | head -1 | sed 's/.*"\(.*\)".*/\1/')
RESULT_FILE="$RESULTS_DIR/benchmark-${TIMESTAMP}-jdk${JDK_VERSION}.json"

echo "JMH Benchmark Runner"
echo "===================="
echo "JDK:        $JDK_VERSION"
echo "Warmup:     ${WARMUP:-annotation default (5)}"
echo "Measure:    ${ITERATIONS:-annotation default (5)}"
echo "Forks:      ${FORKS:-annotation default (3)}"
echo "Filter:     ${FILTER:-all}"
echo "Output:     $RESULT_FILE"
echo ""

# Build JMH command
JMH_CMD=(java -jar "$BENCHMARK_JAR"
    -rf json
    -rff "$RESULT_FILE"
)

# Only override annotation defaults if explicitly set (--quick mode)
if [[ -n "$WARMUP" ]]; then JMH_CMD+=(-wi "$WARMUP"); fi
if [[ -n "$ITERATIONS" ]]; then JMH_CMD+=(-i "$ITERATIONS"); fi
if [[ -n "$FORKS" ]]; then JMH_CMD+=(-f "$FORKS"); fi

if [[ -n "$FILTER" ]]; then
    JMH_CMD+=("$FILTER")
fi

# Run
echo "Starting benchmarks..."
echo ""
"${JMH_CMD[@]}"

echo ""
echo "Results saved to: $RESULT_FILE"
echo ""

# Print summary (min throughput per benchmark from JSON)
if command -v python3 &>/dev/null; then
    python3 -c "
import json, sys
with open('$RESULT_FILE') as f:
    results = json.load(f)
print('Summary (throughput, ops/us):')
print(f'{\"Benchmark\":<55} {\"Score\":>10} {\"Error\":>10}')
print('-' * 77)
for r in sorted(results, key=lambda x: x['benchmark']):
    name = r['benchmark'].split('.')[-1]
    score = r['primaryMetric']['score']
    error = r['primaryMetric']['scoreError']
    print(f'{name:<55} {score:>10.3f} {error:>10.3f}')
" 2>/dev/null || true
fi

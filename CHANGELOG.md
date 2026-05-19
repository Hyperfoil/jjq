# Changelog

## [Unreleased] - 0.1.3

### Performance
- Cache `VirtualMachine` per thread in `JqProgram` — eliminates 67+ object
  allocations per `apply()`/`applyAll()` call via `ThreadLocal` reuse
- Extend fused iteration to support `[.field[].a.b]` patterns — covers 30%
  of real h5m production expressions that previously used FORK/BACKTRACK
- Cache `LazyObjectMap.keySet()` — returns cached immutable set on repeated
  calls, biggest impact on `JqValue.compareTo()` during sort operations
- Replace `computeIfAbsent` capturing lambdas with explicit `get`+`put` in
  `ensureFullyConverted()` for both Jackson and fastjson2 adapters
- Add identity passthrough in `JacksonJqEngine` — returns original `JsonNode`
  directly when the jq filter passes input through unchanged
- Optimize built-in JSON parser: mutable reader state instead of `int[]`
  indirection, fast-path string parsing via `substring()` for no-escape
  strings, direct integer parsing into `long` accumulator
- Optimize JSON serialization: bulk segment appending in `escapeJson`,
  skip `StringBuilder` for strings without escaping, pre-sized array buffers
- Adaptive lazy-vs-eager object threshold (default 16 fields) in Jackson adapter

### Internal
- Added JMH benchmarks for `JqProgram.apply()` path and h5m production
  patterns (`vm_collectIterateField`, `prog_*` benchmarks)
- Fixed `JjqAllocBenchmark.chainedPipe` crash (was iterating wrong field)

## [0.1.2] - 2026-05-14

## [Unreleased] - 0.1.1

### Compatibility
- Fixed 8 upstream jq test failures, improving compatibility from 95.5% to 96.7% (491/508 tests passing, 17 skipped)

### Performance
- Reduced allocations in collect-iterate by using raw `JqValue[]` arrays
- Simplified API and reduced allocations in value serialization

### Internal
- Reduced code duplication across Lexer, VM, Evaluator, and BuiltinRegistry

## [0.1.0] - 2026-03-19

Initial release.

- Pure Java jq engine with zero dependencies (jjq-core)
- Bytecode-compiled VM with optimized dispatch
- 95.5% upstream jq test compatibility (485/508 tests)
- 466 conformance tests
- fastjson2 integration module (jjq-fastjson2)
- Jackson databind integration module (jjq-jackson)
- CLI with GraalVM native-image support
- JSONL/NDJSON and multi-input API
- Apache License 2.0

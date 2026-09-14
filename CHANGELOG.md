# Changelog

## [0.1.12] - 2026-09-14

62 commits since 0.1.11. Headline: generated data binding is 11-15x faster
than Jackson 3 on deserialization with 16x less allocation, and direct
serialization paths (`appendJson`/`appendJsonBytes`) bypass the `JqValue`
tree entirely.

### Data binding (jjq-mapper, jjq-mapper-processor)

- Generated `appendJson` writes fields straight to the output buffer:
  3-field record 177 -> 92 ns/op (-48%), 360 -> 120 B/op (-67%)
- Generated `appendJsonBytes` writes UTF-8 straight to a byte buffer:
  3-field record 218 -> 179 ns/op (-18%), 424 -> 184 B/op (-57%)
- Nested records recurse into generated emitters instead of building inner
  trees: nested String 145 -> 85 ns/op (-41%), nested bytes 192 -> 173 ns/op
- Pre-cached `asSpreader` constructor handle eliminates ~200 B/call of JDK
  `MethodHandle` machinery: reflection deserialization 93 -> 37 ns/op (-60%)
- Positional-first field matching and indexed `JqObject` access
  (`keyAt`/`valueAt`): reflection deserialization down another 14-21%
- `putUnchecked` builder path, `get()`-first mapping cache, interned field
  names, hoisted `resolveKind` in generated list conversion
- New modules: `jjq-yaml` (parse/emit/query YAML via SnakeYAML),
  `jjq-mapper-jackson` and `jjq-mapper-jsonb` annotation bridges,
  `AnnotationBridge` SPI
- New annotations: `@JqInclude`, `@JqNaming`, `@JqConverter` + `ValueConverter`
- POJO support (`@JqMapped` classes with no-arg constructor + accessors)
- Correctness: `char`/`Character` support everywhere, `Optional` scalar
  types, null-safe generated code for all boxed/reference fields
- New public core APIs: `JqObject.keyAt`/`valueAt`/`putUnchecked` overloads,
  `BytOutput` (public), `JqValues` buffer management and `appendJson*` helpers

### Parser

- Char-path parser gained schema cache and per-depth scratch buffers
  (allocation parity with the byte path on large documents)
- `parse(String)` always delegates to the byte path: 14MB production
  document 46.6 -> 20.2 ms/op (-57%)
- Multiplicative hash mixing in `JqObject` hash index

### SQL/JSON path converter (jjq-jsonpath, h5m#322, jjq#80, jjq#81)

- Backslash escapes in keys (`$.result.cpu\-masters` -> `.["cpu-masters"]`)
- Filter field fixes (hyphen, string escaping, `@.*`), filter semantics
  (single-operand `!`, type-directed strict input), slice/union fixes
  (off-by-one, `last` handling, non-integer arms), literal `replace`,
  `exists()` extensions, `appendJson`-aware `like_regex` flag handling
- Conformance 123 -> 127 passing of 558 (zero regressions by skip-set diff)

### Build and docs

- Horreum-style release automation: `release.yml` workflow publishing to
  Maven Central via `maven-release-plugin` (validated with dry run)
- Full README overhaul: fixed contradictions, documented all new APIs,
  refreshed every benchmark table, new `jjq-fastjson2` README
- Javadoc warnings eliminated on the public API surface
- `JqMappedMessageBodyReader`/`Writer` for Jakarta REST use the direct paths

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

# Changelog

## [Unreleased] - 0.2.4

- Second-sighting key admission in the YAML parser: plain mapping keys
  reach the shared intern table only on repeat sighting (lookup-only
  byte probe plus a fixed lossy set, zero per-key allocation), so
  unique keys never allocate intern slots or pollute the table
  (maps-1mb parse -10%, repeat schemas unchanged, measured A/B 3 forks
  interleaved)
- Fused field collection (`COLLECT_FIELD`): `[.arr[] | .field]` and
  `[.arr[] | .["field"]]` resolve the positional index once and reuse
  it for all elements sharing the same keys array, instead of a full
  hash lookup per element (production 14MB `extractMetric` -84%,
  `iterateExtract` -62%, measured A/B 3 forks interleaved)
- Memoized output-cleanliness in `JqString`: repeated serialization
  skips the escape rescan for proven-clean strings (strings-1mb
  `toJsonString` -24%, flat -7%; object stays 32 bytes)
- `floor`/`ceil`/`round` preserve non-finite (and huge finite) doubles
  instead of saturating through a `long` cast (#142)

## [0.2.3] - 2026-10-11

- Non-finite coherence (follow-up to the incus-spawn report): `abs`
  and `divide` route through exact double arithmetic, Jackson/fastjson2
  conversions preserve via `Double`, and `decimalValue()` throws instead
  of silently yielding zero (audited: every other engine caller already
  special-cased); engine semantics otherwise unchanged
- Non-finite numbers mismatch for integral and decimal targets instead
  of silently binding 0 (jq never holds such values); doubles pass
  through exact, `null` serialization unchanged
- YAML float fidelity (#138-#140, from the emitter round-trip audit):
  exact decimals via BigDecimal (22-digit precision survives, dotless
  exponents stay float, trailing forms stable), lone surrogates escape
  instead of corrupting to replacement characters on reparse
- Empty values in sequence items no longer swallow following siblings:
  the nested-block lookup uses the key column, not the outer indent (#134)
- Scalar↔scalar coercion, jq-leaning on conflict (#135, #133): valid
  numeric strings bind (`"42"`->42, was 0), garbage and booleans throw
  for numeric targets (jq `tonumber` rejects them), fractions and
  out-of-range magnitudes throw for integral targets (no silent
  truncation/wrapping), number->char by code point, multi-char->char
  throws, `BigDecimal` parses strings; unified across reflection and
  generated paths (empty string throws, documented)
- Parity policy recorded (mapper Rosetta docs): jq for language behavior,
  Jackson for binding, jq wins on conflict; NaN/Infinity serialize as
  `null` (no jq analog; Jackson literals unreadable here, #136)
- Root-level binding mismatches (empty path) report the first content
  line instead of no position (#130 comment)
- Malformed UTF-8 is rejected with line info on byte entry points (#131):
  new `Utf8.validate` primitive, `parseStrict(byte[])` throws, YAML
  stream/file loading throws naming file and line; lenient `parse`
  keeps replacement behavior
- Release profile strips legacy `.woff` fonts from javadoc jars (keep
  `.woff2`): ~4.2MB -> ~1.8MB per module against the Central size limit
- `parseStrict` rejects duplicate object keys with key and position (#132);
  lenient `parse` keeps both entries (documented)

## [0.2.2] - 2026-10-11

- Mapper type errors, follow-ups to #84-#86 (incus-spawn adoption, #128):
  strict boolean coercion unified across paths (`"true"`/`"false"` bind,
  other strings throw; numbers follow the zero rule), generated
  record/POJO mappings reject scalar input like the reflection path,
  structured `pathSegments()` plus user-facing `displayPath()`, and
  source lines on YAML binding failures (`... at line 3`, best-effort).

- Strict JSON parsing in `parseStrict` (opt-in, #127): rejects trailing
  commas, missing values, truncated input, non-canonical numbers, unknown
  literals (`NaN`/`Infinity`) and invalid string escapes, with line/column
  positions; `parse` keeps its lenient contract byte-identical (verified by
  A/B: no hot-path regression). New `parseStrict(byte[])` overload.
- YAML binding keeps plain-scalar source text for `String` targets
  (`0022` stays `"0022"`, #126); the tree stays typed.
- Release workflow heals profile-gated module version drift itself
  (release pushes never trigger CI, so the ci.yml guard is blind to them).

## [0.2.1] - 2026-10-10

Follow-up fixes since 0.2: Jackson parity for FLOW arrays, two missing
YAML escapes, and a construction quadratic for wide objects.

### Correctness

- FLOW arrays render elements at the array's depth, not depth+1
  (Jackson `INDENT_OUTPUT` parity, #124)
- YAML double-quoted `\L`/`\P` escapes (U+2028/U+2029, #125)

### Performance

- `JqObject.Builder` duplicate check goes hash-indexed past 32 keys:
  50K-key builds 1.8s -> 9ms; wide-doc YAML parse 2.5s -> 16ms

### Build and docs

- Width-coverage benchmarks: `JqObjectBuildBenchmark` (100-50K widths),
  `wide-*.json` fixtures in the parse comparison
- Profile-gated modules (benchmark, examples) re-aligned to the root
  version, plus a post-release alignment check in `release.yml`
  (release pushes never trigger CI, so the ci.yml guard is blind to them)
- Release profile strips legacy `.woff` fonts from javadoc jars (keep
  `.woff2`): ~4.2MB -> ~1.8MB per module, ~28MB saved per release against
  the Central publishing size limit

## [0.2] - 2026-10-10

65 commits since 0.1.12. Headline: `jjq-yaml` goes zero-dependency with a
native byte parser (roughly 2-3x faster than SnakeYAML, ~4x less
allocation, 92% suite conformance), the mapper/processor backlog is
cleared with native replacements for the remaining Jackson annotations,
and a deserialization perf round cuts shaped-data binding up to ~7x.

### YAML (jjq-yaml): native parser, zero dependencies

- Hand-rolled byte parser (block/flow/scalars/anchors/multi-doc, #92):
  roughly 2-3x faster than SnakeYAML with ~4x less allocation
- YAMLTestSuite conformance 48% -> 92% (371/402, #93)
- SnakeYAML dropped to test scope; native block-style emitter (#94)
- Allocation follow-ups: byte spans + deferred scalars (#96), scan/strip
  reductions (#98), shared map-key arrays + deferred seq/doc values
  behind a JSON-clean gate (~43% lower B/op, #99)
- Explicit `?` block mapping keys (#97)
- New `YamlMapper` facade: checked-exception YAML <-> POJO databind (#117)

### Data binding backlog (jjq-mapper, jjq-mapper-processor, #100-#122)

- `@JqConverter` mirror resolution + POJO converter codegen (#100);
  `TypeToken` for generic fields incl. typed Map values (#101); `SET`
  binding mirroring `LIST` (#102); bridge renames + quoted extraction
  programs (#103); private-field fallback (#104); bridge method-form
  any-getter/setter (#105); `Object` fields via `toJavaObject` (#106);
  inherited any-methods (#107); guard fixes (#108, #109); converter
  constructibility validation (#110)
- Native Jackson replacements: `@JqName` on fields and enum constants
  (#111, #112), `@JqAccess` bind direction (#113), `@JqVisibility`
  getter control (#114), `@JqEnum` lenient parsing (#121),
  `@JqAdapter` static-method converters (#120)
- 0.2 scope: boxed NON_DEFAULT parity, HashSet fidelity, interface
  any-methods, sorted/enum sets; guard-read hoisting (#115); shared
  converter instances (#116)
- Jackson Rosetta mapping table + javadoc cross-references

### Deserialization performance

- Generated pre-parsed binding now ~28x Jackson 3 (simple record 8 vs
  228 ns, nested 12 vs 342 ns); end-to-end `byte[]` binding up to 2x
  (list 474 vs 940 ns). JMH, 3 forks, JDK 25.0.4 Temurin.
- Single-lookup `tryGet` POJO deserialization in codegen (~2x)
- Positional fast paths on shaped data: records 4-8x, POJOs ~7x
- Single-entry last-hit mapping cache: same-type 10-field -16%,
  20-field -5%, skewed 3:1 mix -5% (strict alternation +5%, pathological)
- Lazy builtin instantiation: 195 -> 0 hidden classes on the
  builtin-free path, no throughput regression
- Identity-first key lookups with canonicalization

### New public APIs

- `JqValue` opt accessors `pathText`/`pathBoolean`/`pathLong`/`pathInt` (#118)
- `JqValue.asDisplayText`/`size()` defaults (#119)
- `PrettyPrintOptions` for `toPrettyJsonString`: Jackson `INDENT_OUTPUT`
  spacing preset plus terminal-safe `STRICT` escaping (#123)

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

# Changelog

## [Unreleased] - 0.2.4

- `jjq-quarkus` registers bridge modules automatically: when
  `jjq-mapper-jackson` or `jjq-mapper-jsonb` are application dependencies,
  the deployment module resolves them from the application model and the
  recorder instantiates them directly (no ServiceLoader), so generated
  and reflective mappings honor the same annotations. Order is fixed
  (Jackson, then JSON-B) to match the annotation processor; native
  annotations keep precedence. New conversions still belong in the
  `jjq-mapper-*` modules.
- HTTP boundary defaults to strict parsing with 400s for bad input:
  both JAX-RS readers use `parseStrict` (malformed JSON, invalid UTF-8,
  duplicate keys, trailing content rejected) and map parse/bind failures
  to a container-free `JqBadRequestException` (truncated diagnostics,
  rendered as 400 by a scoped mapper); JSON `null` binds to null.
  New `quarkus.jjq.fail-on-unknown-properties` build-time flag (default
  false), applied once to the CDI mapper. Providers are registered as
  explicit CDI beans so the serving instances carry the shared mapper.
- New `jjq-quarkus` extension (issue #65, phases 1+2): Jandex discovery of
  `@JqMapped` types with generated mappings pre-registered on a CDI
  `@Singleton JqMapper`; RESTEasy Reactive providers picked up through
  standard `@Provider` scanning plus explicit serialiser entries;
  RESTEasy Classic providers registered explicitly; Hibernate
  `FormatMapper` wired when Jackson is absent. Tested against Quarkus
  3.40.1 (REST round-trip, mapper singleton sharing, Jackson-free JSON
  column persistence).
- Strict UTF-8 validation splits into a tiny scalar path and a bulk ASCII
  word-skip helper, each under the 325-byte hot-inline budget (strict 14MB
  parse +4%, strings/nested 1MB +5-8%, edge document −5%; tiny documents
  flat with inlining verified on both levels).
- Duplicate scan stays linear to 32 keys with a presized promoted set
  (−7% strict 14MB allocation, byte-exact across forks; latency flat on
  small, 1MB, and production guards).
- Digit loop no longer tracks overflow per digit (18 digits cannot overflow
  a long; the count gates reject longer runs): lenient u64 parse −12%,
  fresh end-to-end number paths −4 to −6%, strict edge document −6%, no
  regressions on small-int, conversion, or 1MB controls.
- Strict duplicate detection scans small objects linearly, promoting to a
  `HashSet` past 8 keys (single-key strict parse −32% with lower
  allocation; `KEEP_LAST` order and escaped-key equality unchanged,
  wide-object and 1MB controls flat). Objects that grew into scratch but
  finished small now copy out (a `KEEP_LAST` duplicate could otherwise leave
  reusable scratch storage in the finished object). Huge exponents no longer
  wrap the accumulator (`1e4294967296` matches `Double.parseDouble` like
  `1e999`, with long arithmetic in the magnitude gate). New end-to-end
  benchmark rows cover fresh parse→convert, parse→serialize, and
  numeric-add paths, pinning first-conversion cost at ~49 ns vs ~14 ns
  steady-state.
- Deferred beyond-long integers: the parser retains the normalized digit
  literal and converts only on first numeric access (strict u64 parse
  −41% overall, rendering −82%, conversions at parity in steady state;
  observably identical to the eager form, pinned by differential tests
  covering rendering, ordering, arithmetic, and Java conversion quirks)
- `toJavaObject` sizes the output map for the known entry count instead of
  taking the default 16-slot table (single-key conversions −4 to −5% with
  lower allocation; insertion order unchanged)
- Parser workspace tables go lazy: the per-parse scratch and schema-cache
  arrays allocate on first use instead of in the reader constructor
  (small-document parse −7 to −17% with lower allocation; sharing logic
  unchanged, large-document controls flat)
- Beyond-long integer literals take a specialized path: validated ASCII
  digits accumulate in base-1e18 `long` limbs instead of paying
  `BigDecimal`'s per-character dispatch (strict u64 parse −25% with a
  small allocation increase; small-integer and large-document controls
  unchanged)
- Large-number parsing and Java conversion check the signed-long range
  before narrowing instead of throwing and catching overflow exceptions;
  the parser's uncommon fallback uses a bounded per-thread character
  buffer instead of allocating a number String. Added focused small-number
  benchmarks and boundary tests for exactness, signed zero, and float provenance
- Duplicate-key policy for strict parsing: `ParseOptions.DuplicatePolicy`
  with `REJECT` (current) and `KEEP_LAST` (later value wins at the
  first-seen key position, serde/LinkedHashMap semantics) — lenient parsing
  keeps entries as-is, error text unchanged
- Float-literal shape on numbers: `JqNumber.isFloatLexed()` records whether
  the literal was float-spelled (`.`/`e`, or negative zero which binds as
  float) at parse time — metadata only, so rendering, equality, hashing,
  and `toJavaObject` are unchanged and adapters branch on it for int-vs-float
  typing; programmatic values carry no provenance
- Strict-mode lone surrogates: `\uD800`-style escapes fail fast unless
  paired (`\uD83D\uDE00` binds), in values and keys, with the usual
  positions — plus a documented contract that every malformed document
  surfaces exclusively as `IllegalArgumentException` (pinned by a
  fixed-seed fuzz test), so adapters catch-umbrella instead of matching
  messages; also fixed truncated arrays crashing with
  `ArrayIndexOutOfBoundsException` instead of failing (strict) or reading
  partial (lenient)
- Signed-zero and beyond-long number fidelity: `-0.0` parses to a distinct
  double-backed value (renders `-0`, binds as float) instead of folding to
  long `0`; integers beyond long stay exact end to end (`toJavaObject`
  yields `BigInteger` instead of wrapping); equality, sort, unique, and
  group_by stay numeric (`-0.0 == 0`); math builtins, `abs`,
  `floor`/`ceil`/`round`, `tonumber`, negation, and `min`/`max` match jq
  1.8.1 signed-zero behavior verbatim (verified against the binary)
- Configurable strict-parse options: `ParseOptions` with `maxDepth`
  (default 10000), BOM `STRIP`/`REJECT`, and raw-NUL `ALLOW`/`REJECT`
  policies on new `parseStrict(doc, options)` overloads — defaults match
  `parseStrict` exactly; escaped `\u0000` stays legal under REJECT
- Identity scope reuse: the per-operation scope object is kept per thread
  instead of reallocated (zero allocation on mapper entries without
  identity; no behavior change)
- java.time Phase C: `Year` (ISO strings plus whole numbers), `YearMonth`,
  `MonthDay`, `OffsetTime`, `ZoneId`, and `ZoneOffset` (ISO strings) —
  canonical ISO text on serialization (unlike Jackson's timestamp/array
  defaults, every form re-binds), fail-fast mismatches, nulls stay null,
  on reflection and generated paths (`Month`/`DayOfWeek` already work as
  plain enums; custom patterns stay limited to the original six types)
- Jackson/JSON-B annotations on accessors: `@JsonProperty` (rename,
  required, access), `@JsonAlias`, `@JsonInclude`, and `@JsonFormat`
  (pattern and shape) are now read from getters/setters when the field
  itself is unannotated (field placement wins); same for `@JsonbProperty`,
  `@JsonbTransient`, and `@JsonbNillable` through the JSON-B bridge.
  Also fixed POJO alias binding when only the alias key is present
  (the absent-key skip consulted the primary name alone)
- Number/boolean wire shapes: native `@JqFormat(shape)` plus Jackson
  `@JsonFormat(shape)` through the bridge — `STRING` quotes numbers and
  booleans on serialization (deserialization already binds quoted and bare
  forms, so shape is ser-side; quoted `BigDecimal` keeps its scale,
  integral doubles render `"3.0"`), on reflection and generated paths;
  `NUMBER` is the default form (no-op, other Jackson shapes ignored);
  explicit numeric forms fail fast at mapping creation (booleans stay
  booleans, no timestamp/enum-ordinal output, no shapes on `Optional`);
  native patterns stay date/time-only while bridged number/boolean
  patterns are moot and ignored (Jackson ignores them)
- Object identity: native `@JqId` plus Jackson `@JsonIdentityInfo`
  (property generators) through the bridge — repeated full objects dedup
  to one instance, scalar ids resolve to bound instances, repeats serialize
  as bare ids; records do dedup + backward refs (forward refs fail fast),
  POJOs register after the no-arg constructor so true cycles resolve;
  per-call scopes, fail-fast on missing/non-scalar/unknown ids, collections
  share the path; identity POJOs bind via reflection, generated records
  share the semantics
- Custom date/time patterns: native `@JqFormat` plus Jackson
  `@JsonFormat` through the bridge — pattern/timezone/locale per field
  on the six date/time types, root locale and UTC by default; patterns
  are strings-only, invalid patterns and non-temporal targets fail fast
  at mapping creation, on reflection and generated paths
- Naming, aliases, order: all 8 `JqNaming` strategies (existing
  separators normalize), native `@JqAlias` (first present wins, primary
  serializes, strict/any-setter aware) and `@JqOrder` (explicit wires
  plus alphabetic tail, serialization only) with Jackson
  `@JsonNaming`/`@JsonAlias`/`@JsonPropertyOrder` through the bridge
- Required properties: native `@JqRequired` plus Jackson
  `@JsonProperty(required)` through the bridge — absent keys fail naming
  the first missing one, on reflection and generated paths (records,
  POJOs, builders, creator params by wire); explicit nulls still bind,
  programs cannot prove presence (fail fast)
- JSON-B creators: `@JsonbCreator` with `@JsonbProperty` params through
  the bridge (delegating and properties, mirroring the Jackson subset)
- Builder binding: native `@JqBuilder` plus Jackson
  `@JsonDeserialize(builder)`/`@JsonPOJOBuilder` through the bridge
  (read reflectively, no new dependency) — Lombok style (empty prefix
  + factory) and `with`-prefix style, chained or void binders, builder
  defaults surviving missing keys; gaps fail fast at mapping creation
- Deserialization creators: native `@JqCreator` plus Jackson
  `@JsonCreator` through the bridge (`DELEGATING`/`PROPERTIES`/default
  modes, `@JsonProperty` params) — single unnamed parameters bind
  scalars, named parameters bind objects with validation running inside;
  records, POJOs (creator removes the no-arg requirement, setters bind
  the rest), reflection and generated paths alike
- Opt-in `failOnUnknownProperties` on the mapper builder (Jackson
  parity, default stays lenient): unknown keys fail as
  `Unrecognized field "z" for Point` pathed at the key, on reflection
  and generated paths; any-setters still consume unknowns, ignored
  properties never fail, polymorphic dispatch strips the consumed
  discriminator before delegating
- `java.time` in the mapper: `Instant`, `LocalDate`/`Time`/`DateTime`,
  `OffsetDateTime`/`ZonedDateTime`, `Duration`, `Period` — ISO-8601
  strings canonical both ways, epoch seconds accepted on deser for
  instant-like types (fractions carry nanos, at UTC) and `Duration`;
  unparseable text fails as shape mismatch, never leaked
  `DateTimeParseException`
- Arrays and wider collections in the mapper: `T[]` (reference and
  primitive) bind natively with exact element mapping, `LinkedList`,
  `Collection`/`Iterable`, `Queue`/`Deque`, `SortedMap`/`TreeMap` and
  custom impls (via no-arg constructor) bind by interface; top-level
  arrays serialize without a mapping
- JDK value types in the mapper: `BigInteger` (exact numbers of any
  magnitude, fractions/non-finite mismatch), `UUID`/`URI`/`URL`
  (strict string forms; relative refs bind `URI` but mismatch `URL`),
  on all paths — reflection, generated code (all 9 sites), `Optional`
  inners, `toJson`/`toJsonBytes` parity, null ternaries throughout
- Sealed polymorphism: `@JqType` on a sealed type dispatches deser on a
  discriminator property (default `"type"`, fail-fast on missing/unknown)
  and injects it on ser (values default to simple class names, `@JqName`
  on the subtype overrides); Jackson `@JsonTypeInfo` (`PROPERTY`+`NAME`)
  with `@JsonSubTypes`/`@JsonTypeName` works through the bridge with
  native winning; `List<Shape>` binds heterogeneously via `TypeToken`
- POJO field inheritance: `forClass` walks the superclass chain
  (subclass fields shadow inherited ones), closing the gap where
  subclass mappings silently dropped inherited fields
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

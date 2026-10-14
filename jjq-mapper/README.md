# jjq-mapper

Data binding between `JqValue` and Java records or POJOs using compiled jq queries.
Each field maps to a jq expression that executes in ~3ns for simple field access.

## Dependencies

```xml
<dependency>
    <groupId>io.hyperfoil.tools</groupId>
    <artifactId>jjq-mapper</artifactId>
    <version>${jjq.version}</version>
</dependency>
```

Depends on `jjq-core` only. No Jackson, no reflection libraries, no external dependencies.

For compile-time optimized mappings (11-15x faster than Jackson 3 on deserialization,
~2x faster on serialization), add the
[jjq-mapper-processor](../jjq-mapper-processor/README.md) as an annotation processor.

## Quick Start

```java
import io.hyperfoil.tools.jjq.mapper.JqMapper;

// Records
record User(String name, int age, boolean active) {}

JqMapper mapper = JqMapper.create();
User user = mapper.fromJson("{\"name\":\"Alice\",\"age\":30,\"active\":true}", User.class);
String json = mapper.toJson(new User("Bob", 25, false));

// POJOs (no-arg constructor + getters/setters)
class Config {
    private String host;
    private int port;
    public Config() {}
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
}

Config config = mapper.fromJson("{\"host\":\"localhost\",\"port\":8080}", Config.class);
```

## API

### Deserialization

```java
JqMapper mapper = JqMapper.create();

// From JSON string
User user = mapper.fromJson(jsonString, User.class);

// From JSON byte[] (uses jjq's optimized byte parser)
User user = mapper.fromJson(jsonBytes, User.class);

// From pre-parsed JqValue (fastest — no parse overhead)
JqValue data = JqValues.parse(jsonBytes);
User user = mapper.fromJqValue(data, User.class);

// With generic types (e.g., List<Item>)
java.lang.reflect.Type listType = ...;
List<Item> items = mapper.fromJson(json, listType);
List<Item> items2 = mapper.fromJqValue(data, listType);
byte[] jsonBytes2 = ...;
List<Item> items3 = mapper.fromJson(jsonBytes2, listType);
```

### Serialization

```java
String json = mapper.toJson(user);          // To JSON string
byte[] bytes = mapper.toJsonBytes(user);    // To JSON byte[]
JqValue value = mapper.toJqValue(user);     // To JqValue tree
```

`toJson` and `toJsonBytes` use direct-to-JSON serialization when a generated
mapping is available: field values are written straight to the output buffer
(`Mapping.appendJson` / `Mapping.appendJsonBytes`), bypassing the intermediate
`JqValue` tree entirely. Reflection-based mappings fall back to building the
tree first. `toJqValue` always builds the tree (needed for queries and merging).

For streaming or reuse of buffers:

```java
StringBuilder sb = new StringBuilder();
mapper.appendJson(user, sb);                // Append JSON, no intermediate tree

BytOutput out = JqValues.acquireByteBuffer();
mapper.appendJsonBytes(user, out);          // Append UTF-8 bytes, no tree
byte[] bytes = JqValues.releaseByteBuffer(out);
```

## Annotations

### `@JqField` — custom jq expression

```java
record PerfResult(
    String user,                                                // .user
    @JqField(".config.timeout") int timeout,                    // nested field
    @JqField("[.metrics[] | .value]") List<Double> values       // complex filter
) {}
```

### `@JqName` — explicit wire name

```java
@JqMapped
record SpawnConfig(@JqName("host-paths") List<String> hostPaths) {}
// Deserializes from {"host-paths":[...]} and serializes back under the same key
```

Unlike `@JqField` (which replaces extraction with a jq program), a renamed
field keeps direct key lookup. Explicit names win over the naming strategy
and bridge renames; combining `@JqName` with `@JqField` on one element is an
error. On enum constants it sets the wire form both directions; see
`@JqEnum` for lenient parsing. On a type declaration it sets the
polymorphic discriminator value inside a `@JqType` family (default: the
simple class name).

### `@JqType` — sealed polymorphism

```java
@JqType
sealed interface Shape permits Circle, Rectangle {}
record Circle(double radius) implements Shape {}
// {"type":"Circle","radius":1.5} binds Circle; ser injects "type" back
```

The discriminator property defaults to `"type"` (`@JqType(discriminator = "kind")`
renames it). Deser fails fast on missing, non-string, or unknown values;
direct binding to a subtype stays lenient. Jackson interop: `@JsonTypeInfo`
(`PROPERTY` + `NAME`, with `@JsonSubTypes`/`@JsonTypeName`) works through the
bridge; native `@JqType` wins when both are present.

### `@JqCreator` — custom construction

```java
record UserId(String value) {
    @JqCreator
    public static UserId parse(String value) {   // single unnamed param: binds scalars
        if (value.isEmpty()) throw new IllegalArgumentException("empty");
        return new UserId(value);
    }
}
record User(String name, int age) {
    @JqCreator
    public static User create(@JqName("user_name") String name,   // named params: binds objects
                              @JqName("age") int age) {
        return new User(name, age);
    }
}
```

A single unnamed parameter delegates scalar documents; named parameters
(`@JqName`, else real names) bind object documents. The creator takes over
construction (objects mismatch a delegating creator and vice versa);
failures wrap with the creator kind. POJOs work too (property creators
construct, setters bind the rest; a creator removes the no-arg requirement).
Jackson `@JsonCreator` (`DELEGATING`/`PROPERTIES`/default modes,
`@JsonProperty` params) works through the bridge; native wins.

### `@JqBuilder` — builder binding

```java
@JqBuilder(value = User.Builder.class, factory = "builder", prefix = "")
class User {
    final String name;
    public String getName() { return name; }
    public static Builder builder() { return new Builder(); }
    public static class Builder {
        Builder name(String name) { ...; return this; }  // or void
        User build() { ... }
    }
}
```

Each property converts as usual, then invokes `prefix + Name(...)`
(empty prefix is Lombok style); missing keys keep builder defaults.
Gaps (missing binder/factory/build) fail fast at mapping creation.
Jackson `@JsonDeserialize(builder=...)` + `@JsonPOJOBuilder` works
through the bridge without new dependencies; native wins.

### `@JqRequired` — fail on absent keys

```java
record Point(@JqRequired int x, int y) {}
// {"y":2} fails: Missing required field "x" for Point at $.x
```

Explicit nulls still bind (only absence fails); ignored properties never
fail; `@JqField` programs cannot prove presence and fail fast in
combination. Applies to creator parameters by wire as well. Jackson
`@JsonProperty(required = true)` works through the bridge.

### `@JqEnum` — lenient enum parsing

```java
@JqEnum(normalize = {TRIM, LOWERCASE, SEPARATOR_FOLD}, onUnknown = NULL)
public enum ClaudeAccountType {
    @JqName("api-key") API_KEY, ...
}
// "  API_KEY  " binds; typos bind null instead of failing
```

Input is stripped, ASCII-lowercased, and `_`→`-` folded before matching
`@JqName`/bridge wire names (exact constant names still bind). Without the
annotation — or with `onUnknown = FAIL` — unknown values fail strictly.
A present annotation takes over deserialization from bridge creators;
serialization is unaffected.

### `@JqIgnore` — exclude fields

```java
record User(
    String name,
    @JqIgnore String internalId  // null during deser, omitted during ser
) {}
```

### `@JqInclude` — control null/empty inclusion in serialization

```java
@JqInclude(JqInclude.Include.NON_NULL)
record ApiResponse(String data, String error, String debug) {}
// ApiResponse("hello", null, null) → {"data":"hello"}
```

Strategies: `ALWAYS` (default), `NON_NULL`, `NON_EMPTY`, `NON_DEFAULT`.
Class-level sets the default; field-level overrides.

### `@JqNaming` — naming strategy

```java
@JqNaming(JqNaming.Strategy.SNAKE_CASE)
record PcpMetric(String metricName, double metricValue) {}
// Deserializes from: {"metric_name":"cpu", "metric_value":0.5}
```

Strategies: `IDENTITY` (default), `SNAKE_CASE`.

### `@JqConverter` — custom type conversion
```java
public class InstantConverter implements ValueConverter<Instant> {
    public Instant fromJqValue(JqValue v) { return Instant.parse(v.stringValue()); }
    public JqValue toJqValue(Instant v) { return JqString.of(v.toString()); }
}

record Event(String name, @JqConverter(InstantConverter.class) Instant timestamp) {}
```

### `@JqAdapter` — static-method delegation

```java
@JqMapped
record SpawnConfig(@JqAdapter(from = "fromWire", to = "wireName") ClaudeAccountType type) {}
```

Names static policy methods on the field's declared type instead of a
converter class: `from` is `static T method(JqValue)` or `static T
method(String)`; `to` is instance `JqValue/String method()` or `static
JqValue/String method(T)`. Nulls short-circuit on both directions;
combining with `@JqConverter` is an error. No Jackson equivalent —
this replaces hand-written converters.

### `@JqAccess` — bind direction

```java
@JqMapped
record SpawnConfig(
    @JqAccess(JqAccess.Access.WRITE_ONLY) @JqName("host-path") String hostPath,
    @JqName("host-paths") List<String> hostPaths
) {}
// "host-path" still reads (legacy files migrate) but is never written back
```

`WRITE_ONLY` binds on read (absent keys tolerated) and skips serialization;
`READ_ONLY` is the mirror. `@JqIgnore` stays bidirectional and wins over both.

### `@JqVisibility` — bind fields, ignore getters

```java
@JqMapped
@JqVisibility(getters = JqVisibility.Visibility.NONE,
              isGetters = JqVisibility.Visibility.NONE)
public class ClaudeConfig {
    private String apiKey;  // read directly; getApiKey() never bound
    public ClaudeConfig() {}
}
```

A native `NONE` suppresses unconditionally; under `ANY` (the default) a bridge
`NONE` still suppresses. There is no field axis — fields are always bound.
Records ignore it (canonical accessors, no `getX()` binding).
## POJO Support

POJOs with a no-arg constructor and getters/setters are supported alongside records.
Property discovery priority: public fields → getters/setters → `setAccessible` fallback.
Boolean getters (`isActive()`) are detected automatically. Fields are discovered
along the superclass chain (subclass first — a subclass field shadows an inherited
one of the same name); static/synthetic/transient members are skipped at every level.

```java
@JqMapped
@JqNaming(JqNaming.Strategy.SNAKE_CASE)
class DetectionResult {
    private double ratio;
    private boolean active;
    public DetectionResult() {}
    // getters and setters...
}
```

## `@JqMapped` — opt into compile-time mapping

Annotating a record or POJO with `@JqMapped` marks it for the annotation processor,
which generates a `_JqMapping` class with direct constructor/accessor calls and
direct-to-JSON serialization. Without the annotation (or without the processor on
the classpath), `JqMapper` falls back to reflection-based mapping, which is slower
but requires no build configuration.

```java
@JqMapped
record User(String name, int age) {}
// Processor generates User_JqMapping with fromJqValue, toJqValue,
// appendJson, appendJsonBytes, and type() — all reflection-free.
```

## Builder API (GraalVM Native Image)

For native-image builds where `Class.forName()` is not available:

```java
JqMapper mapper = JqMapper.builder()
    .register(new User_JqMapping())
    .register(new Config_JqMapping())
    .build();

// Or with the generated registry:
JqMapper.Builder builder = JqMapper.builder();
JqMappingRegistry.registerAll(builder);
JqMapper mapper = builder.build();
```

Bridges can also be registered explicitly instead of via `ServiceLoader` discovery:

```java
JqMapper mapper = JqMapper.builder()
    .bridge(new JacksonAnnotationBridge())
    .build();
```

Strictness is opt-in per mapper (default stays lenient):

```java
JqMapper strict = JqMapper.builder().failOnUnknownProperties(true).build();
// Unknown JSON keys now fail with `Unrecognized field "z" for Point` at `$.z`;
// any-setters still consume unknowns, ignored properties never fail.
```

Note: `JqMapper.create()` discovers bridges via `ServiceLoader` automatically;
`builder()` does not — register bridges explicitly when using the builder.

## Jackson / JSON-B Annotation Bridges

Existing Jackson or JSON-B annotations work transparently with optional bridge modules:

```xml
<!-- Jackson bridge -->
<dependency>
    <groupId>io.hyperfoil.tools</groupId>
    <artifactId>jjq-mapper-jackson</artifactId>
</dependency>

<!-- JSON-B bridge -->
<dependency>
    <groupId>io.hyperfoil.tools</groupId>
    <artifactId>jjq-mapper-jsonb</artifactId>
</dependency>
```

```java
// Existing Jackson-annotated DTO — works with jjq-mapper automatically
record User(@JsonProperty("full_name") String name, @JsonIgnore String secret) {}

JqMapper mapper = JqMapper.create(); // auto-discovers bridge via ServiceLoader
User user = mapper.fromJqValue(json, User.class);
```

Priority: jjq annotations (`@JqField`, `@JqIgnore`, `@JqInclude`, `@JqName`, `@JqAccess`, `@JqVisibility`, `@JqNaming`, `@JqConverter`, `@JqAdapter`, `@JqType`)
over bridge annotations over defaults. Bridges are consulted in registration order;
the first non-null answer wins.
See [jjq-mapper-jackson](../jjq-mapper-jackson/README.md) and [jjq-mapper-jsonb](../jjq-mapper-jsonb/README.md).

### Coming from Jackson?

Native equivalents for the common Jackson annotations (same names would collide
with `com.fasterxml.jackson.annotation` in mixed files, and subtly differ in
semantics — hence the `Jq` prefix, the same reason Jakarta chose `Jsonb*`):

| Jackson | jjq native | Notes |
|---|---|---|
| `@JsonProperty("x")` | `@JqName("x")` | Key rename with direct lookup |
| `@JsonProperty(access = …)` | `@JqAccess(…)` | Same `READ_ONLY`/`WRITE_ONLY` names |
| `@JsonProperty(required = true)` | `@JqRequired` | Absent keys fail; explicit nulls bind |
| `@JsonIgnore` | `@JqIgnore` | jjq's is always bidirectional |
| `@JsonInclude` | `@JqInclude` | Same strategy names |
| `@JsonAutoDetect` | `@JqVisibility` | Getter axes only; fields always bind |
| `@JsonAnyGetter` / `@JsonAnySetter` | `@JqAnyGetter` / `@JqAnySetter` | Same method-form contract |
| `@JsonValue` / `@JsonCreator` on enums | `@JqName` on constants (+ `@JqEnum` for leniency) | Strict exact mapping; leniency via `@JqEnum`, custom policy via `@JqConverter` |
| `@JsonTypeInfo` + `@JsonSubTypes` (`PROPERTY`+`NAME`) | `@JqType` (sealed) | Discriminator property + simple-name values (overridable via `@JqName` on the subtype); native wins over bridge; full `JsonTypeInfo` matrix out of scope |
| `@JsonNaming` | `@JqNaming` | Strategy-based, fewer strategies |
| `TypeReference<T>` | `TypeToken<T>` | Anonymous-subclass capture, same idiom |

### Parity policy: jq wins on conflict

Two references guide behavior, split by layer:

- **jq** for language behavior — parsing, serialization text, and value
  conversions (`tonumber`/`tostring` semantics, truthiness). Verified
  against jq 1.8.1, not from memory.
- **Jackson databind** for Java binding — POJO mapping has no jq analog,
  and migrators compare against a Jackson baseline.

When they conflict, jq wins. Example: Jackson coerces booleans to
numbers (0/1), but jq's `tonumber` rejects them — so binding a boolean
to a numeric field throws `ShapeMismatchException`. The boundary: null
handling follows Java/Jackson (explicit nulls widen to defaults), since
jq's error-on-null has no Java expression for primitives.

### Deliberate divergences from Jackson

Behavior that differs by design, not by accident. Everything below is
pinned by tests:

| Area | Jackson | jjq | Why |
|---|---|---|---|
| Boolean → number | 0/1 | throws | jq `tonumber` rejects booleans; jq wins |
| `""` → number | 0 (primitives) | throws | fail-fast consistency (`"maybe"` throws too) |
| Fraction → integral | truncates | throws | no silent loss (reporter preference) |
| `NaN`/`Infinity` serialization | unquoted literals | `null` | no jq analog; the literals are unreadable here (#136) |
| Unknown keys | fail by default | ignored (opt-in `failOnUnknownProperties` on the builder) | forward compatibility by default; any-setters still consume unknowns under strict |
| Duplicate JSON keys (lenient) | last wins | both kept | documented on `parse`; `parseStrict` rejects |
| Trailing commas etc. (lenient) | fail | tolerated | documented on `parse`; `parseStrict` rejects |

### Custom `AnnotationBridge` implementations

Implement `AnnotationBridge` to read a third framework's annotations. All methods
return `null` (or `false` for `isIgnored`) to delegate:

```java
public interface AnnotationBridge {
    String resolveFieldName(AnnotatedElement element);  // JSON key override
    boolean isIgnored(AnnotatedElement element);        // exclude field
    JqInclude.Include resolveInclusion(AnnotatedElement element);  // null = no opinion
    JqNaming.Strategy resolveNaming(Class<?> type);     // class-level, null = no opinion
}
```

Implementations must be stateless and thread-safe, with a public no-arg constructor
for `ServiceLoader` discovery.

## Supported Types

| Java type | JSON type | Notes |
|---|---|---|
| `String` | string | |
| `int` / `Integer` | number | |
| `long` / `Long` | number | |
| `double` / `Double` | number | |
| `float` / `Float` | number | |
| `boolean` / `Boolean` | boolean | |
| `short` / `Short` | number | |
| `byte` / `Byte` | number | |
| `char` / `Character` | string | Single-character strings; `'\0'` for missing |
| `BigDecimal` | number | Preserves precision |
| `BigInteger` | number | Exact, any magnitude; fractions/non-finite mismatch |
| `UUID` / `URI` / `URL` | string | Strict parse; relative refs bind `URI` but mismatch `URL` |
| `Instant` | string / number | ISO-8601 canonical; epoch seconds accepted on deser (fractions carry nanos) |
| `LocalDate` / `LocalTime` / `LocalDateTime` | string | ISO-8601; numbers mismatch |
| `OffsetDateTime` / `ZonedDateTime` | string / number | ISO-8601 canonical; epoch seconds bind at UTC |
| `Duration` | string / number | ISO-8601 canonical (`PT1H30M`); numbers bind as seconds |
| `Period` | string | ISO-8601 (`P1Y2M3D`) |
| `List<T>` | array | Recursive element mapping (`LinkedList`, `Collection`, `Queue`/`Deque` bind by interface too) |
| `Map<String, V>` | object | Recursive value mapping (`TreeMap`/`SortedMap` order naturally) |
| `T[]` (incl. primitives) | array | Exact element mapping; null arrays stay null |
| `Optional<T>` | value / null | `Optional.empty()` for null/missing |
| `Enum<E>` | string | Via `Enum.valueOf()` / `.name()` |
| `JqValue` | any | Passthrough — no conversion |
| Nested record/POJO | object | Recursive field mapping |

### Shape mismatches

Structural mismatches fail fast with a `$`-rooted path (Jackson `MismatchedInputException` parity):

```java
mapper.fromJqValue(JqValues.parse("{\"packages\":\"git\"}"), Config.class);
// JqMapperException: Cannot bind String "git" to List<String> at '$.packages'
```

Non-array → `List`, non-object → `Map`/record/POJO, and wrong collection element types all throw at bind time instead of silently coercing. The reverse direction throws too: array/object input to any scalar (`String`, numbers, `boolean`, `char`, `BigDecimal`, enums) fails, while scalar-to-scalar coercion stays lenient (`42` → `"42"`). `null`/absent values stay lenient (`null`, empty collections, field initializers). Custom `@JqConverter`s bypass this checking entirely, which is also how genuinely polymorphic shapes (string-or-map, list-or-object) should be modeled.

Shape failures throw `ShapeMismatchException` (a `JqMapperException`), which exposes `expected()`/`actual()` as `JqValue.Type` plus the `$`-rooted `path()` — no message parsing needed:

```java
} catch (ShapeMismatchException e) {
    // e.expected() == ARRAY, e.actual() == STRING, e.path() == "$.packages"
}
```

### Unknown keys: `@JqAnySetter` / `@JqAnyGetter`

Unknown keys are captured instead of dropped (Jackson `@JsonAnySetter`/`@JsonAnyGetter` parity) — required for forward compatibility and lossless round-trips:

```java
class Config {
    private String name;
    private final Map<String, JqValue> extras = new LinkedHashMap<>();
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    @JqAnySetter
    public void setExtra(String key, JqValue value) { extras.put(key, value); }

    @JqAnyGetter
    public Map<String, JqValue> getExtras() { return extras; }
}
```

The setter takes `(String, JqValue)` (exact, lossless) or `(String, Object)` (converted); the getter returns `Map<String, ?>` (converted on emit, `null` as JSON null) or `JqObject` (used directly). Known and `@JqIgnore`d keys never reach the setter. Generated mappings support the native annotations; the Jackson bridge honors `@JsonAnySetter`/`@JsonAnyGetter` on the reflection path (field-form stays unsupported).

The any-getter method itself is never emitted as a bean property (Jackson parity) — only the flattened entries appear.

### Jackson annotation support (`jjq-mapper-jackson` bridge)

| Jackson | jjq behavior |
|---|---|
| `@JsonProperty("name")` | Field rename (records + POJOs, reflection path) |
| `@JsonIgnore`, `@JsonIgnoreProperties` | Exclusion both directions |
| `@JsonInclude(...)` | ALWAYS/NON_NULL/NON_EMPTY/NON_DEFAULT mapping |
| `@JsonProperty(access = WRITE_ONLY)` | Bound on deser, skipped on ser (records + POJOs, reflection + generated) |
| `@JsonProperty(access = READ_ONLY)` | Skipped on deser (default kept), emitted on ser |
| `@JsonAnySetter` / `@JsonAnyGetter` (method form) | Unknown-key capture / extras emission (reflection path) |
| `@JsonValue` | Enum wire form on ser |
| `@JsonCreator` (delegating + properties) | Factory/ctor deser; named params via `@JsonProperty` (records + POJOs, reflection + generated) |
| `@JsonProperty(required = true)` | Absent keys fail (records + POJOs + creator params, reflection + generated) |
| `@JsonAutoDetect(getterVisibility/isGetterVisibility = NONE)` | Suppress `getX`/`isX` binding; fields read directly (records immune: canonical accessors only) |
| `@JsonIgnore` on a getter / setter method | Ser-only / deser-only suppression (field-level stays both directions) |

Directional access and `@JsonValue`/`@JsonCreator` work identically through generated mappings (the processor reads Jackson annotations stringly-typed — no Jackson dependency on the processor).

Generated-mapping limitations (private fields need a readable accessor — generated code emits no reflection): a private field with no bound getter (including `@JsonAutoDetect`-suppressed ones) is dropped from generated *serialization*, matching the pre-existing accessor-less behavior. The reflection path reads such fields via `setAccessible` instead. `@JsonIgnore` on accessor methods is honored on the reflection path only.

### `TypeConverter` — extension point for custom handling

`TypeConverter` is the public conversion engine behind field mapping. The target
type is resolved once per field to a `Kind` enum (`STRING`, `INT`, `LONG`,
`DOUBLE`, `FLOAT`, `BOOLEAN`, `SHORT`, `BYTE`, `CHAR`, `BIG_DECIMAL`, `OPTIONAL`,
`LIST`, `MAP`, `ENUM`, `RECORD`, `JQ_VALUE`, `DEFAULT`), then converted via a
single `switch` — no if/else chains, no lambda allocations on the hot path:

```java
TypeConverter.Kind kind = TypeConverter.resolveKind(String.class, String.class);
Object value = TypeConverter.convert(jqValue, kind, String.class, String.class, mapper);
JqValue json = TypeConverter.toJqValue(javaValue, mapper);
```

## Performance

### Pre-parsed path (JqValue → Record/POJO)

| Scenario | jjq generated | jjq reflection | Jackson 3 |
|---|---|---|---|
| Simple record (5 fields) | **20 ns** | 39 ns | 227 ns |
| Simple POJO (5 fields) | **20 ns** | — | 227 ns |
| Nested record | **25 ns** | 51 ns | 364 ns |

Generated mappings are **11-15x faster** than Jackson 3 on pre-parsed data.
Even reflection-based mapping is 5-7x faster than Jackson 3.

### End-to-end (byte[] → Record)

| Scenario | jjq | Jackson 3 |
|---|---|---|
| Simple | **158 ns** | 362 ns |
| Nested | **202 ns** | 475 ns |
| List | **826 ns** | 976 ns |

### Serialization (Record → JSON)

| Scenario | jjq generated | Jackson 3 |
|---|---|---|
| Simple to JSON string | **92 ns** | — |
| Simple to JSON bytes | **179 ns** | 189 ns |
| Nested to JSON bytes | **192 ns** | 164 ns |

Generated `toJson` writes field values directly to the output buffer
(`appendJson`), bypassing the `JqValue` tree. `toJsonBytes` uses the
parallel `appendJsonBytes` path into a thread-local byte buffer.

## Thread Safety

`JqMapper` is thread-safe. Class metadata is cached per class in a `ConcurrentHashMap`
and computed once on first use. Create the mapper once and reuse.

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

## POJO Support

POJOs with a no-arg constructor and getters/setters are supported alongside records.
Property discovery priority: public fields → getters/setters → `setAccessible` fallback.
Boolean getters (`isActive()`) are detected automatically.

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

Priority: jjq annotations (`@JqField`, `@JqIgnore`, `@JqInclude`, `@JqNaming`, `@JqConverter`)
over bridge annotations over defaults. Bridges are consulted in registration order;
the first non-null answer wins.
See [jjq-mapper-jackson](../jjq-mapper-jackson/README.md) and [jjq-mapper-jsonb](../jjq-mapper-jsonb/README.md).

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
| `List<T>` | array | Recursive element mapping |
| `Map<String, V>` | object | Recursive value mapping |
| `Optional<T>` | value / null | `Optional.empty()` for null/missing |
| `Enum<E>` | string | Via `Enum.valueOf()` / `.name()` |
| `JqValue` | any | Passthrough — no conversion |
| Nested record/POJO | object | Recursive field mapping |

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

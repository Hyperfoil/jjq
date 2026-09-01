# jjq-mapper

Zero-dependency data binding between `JqValue` and Java records using compiled jq queries.
Each record component maps to a jq expression that executes in ~3ns for simple field access.

## Dependencies

```xml
<dependency>
    <groupId>io.hyperfoil.tools</groupId>
    <artifactId>jjq-mapper</artifactId>
    <version>${jjq.version}</version>
</dependency>
```

Depends on `jjq-core` only. No Jackson, no reflection libraries, no external dependencies.

For compile-time optimized mappings (5-6x faster), add the
[jjq-mapper-processor](../jjq-mapper-processor/README.md) as an annotation processor.

## Quick Start

```java
import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;

record User(String name, int age, boolean active) {}

JqMapper mapper = JqMapper.create();

// Deserialize: JSON -> record
User user = mapper.fromJson("{\"name\":\"Alice\",\"age\":30,\"active\":true}", User.class);

// Serialize: record -> JSON
String json = mapper.toJson(new User("Bob", 25, false));
// {"name":"Bob","age":25,"active":false}
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
```

### Serialization

```java
// To JSON string
String json = mapper.toJson(user);

// To JSON byte[] (uses jjq's direct byte serializer)
byte[] bytes = mapper.toJsonBytes(user);

// To JqValue (for further jq processing)
JqValue value = mapper.toJqValue(user);
```

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
| `BigDecimal` | number | Preserves precision |
| `List<T>` | array | Recursive element mapping |
| `Map<String, V>` | object | Recursive value mapping |
| `Optional<T>` | value / null | `Optional.empty()` for null/missing |
| `Enum<E>` | string | Via `Enum.valueOf()` / `.name()` |
| `JqValue` | any | Passthrough — no conversion |
| Nested record | object | Recursive field mapping |

## Custom jq Expressions

By default, a record component named `myField` maps to the jq expression `.myField`.
Use `@JqField` to override with any jq expression:

```java
import io.hyperfoil.tools.jjq.mapper.JqField;

record PerfResult(
    String user,                                                    // .user
    @JqField(".config.timeout") int timeout,                        // .config.timeout
    @JqField(".data[0].name") String firstName,                     // .data[0].name
    @JqField("[.metrics[] | .value]") List<Double> values,          // complex jq filter
    @JqField(".rhivos_config | {build, model}") JqValue config      // object construction
) {}

PerfResult r = mapper.fromJqValue(uploadData, PerfResult.class);
```

The jq expressions are compiled once via `JqProgram.compile()` and cached. Simple
field paths (`.name`, `.config.timeout`) use jjq's fast-path shape detection and
execute in ~3ns with zero allocation.

## Ignoring Fields

Use `@JqIgnore` to exclude fields from serialization and give them default values
during deserialization:

```java
import io.hyperfoil.tools.jjq.mapper.JqIgnore;

record User(
    String name,
    @JqIgnore String internalId  // null during deserialization, omitted during serialization
) {}
```

## Nested Records

Records containing other records are mapped recursively:

```java
record Address(String city, String zip) {}
record Person(String name, Address address) {}

Person p = mapper.fromJson("""
    {"name":"Alice","address":{"city":"NYC","zip":"10001"}}
    """, Person.class);
// p.address().city() == "NYC"
```

## Lists of Records

```java
record Item(String name, double price) {}
record Order(String id, List<Item> items) {}

Order order = mapper.fromJson("""
    {"id":"order-1","items":[
        {"name":"Widget","price":9.99},
        {"name":"Gadget","price":24.99}
    ]}""", Order.class);
```

## Round-Trip

```java
User original = new User("Alice", 30, true);
JqValue json = mapper.toJqValue(original);
User restored = mapper.fromJqValue(json, User.class);
assert original.equals(restored);
```

## Missing and Null Fields

Missing JSON fields receive Java default values:
- `null` for reference types (String, records, etc.)
- `0` for numeric primitives
- `false` for boolean
- `Optional.empty()` for Optional fields

```java
record Config(String host, int port, Optional<String> logFile) {}

Config c = mapper.fromJson("{\"host\":\"localhost\"}", Config.class);
// c.port() == 0, c.logFile() == Optional.empty()
```

## Thread Safety

`JqMapper` is thread-safe. Class metadata (field mappings, compiled jq programs,
MethodHandles) is cached per class in a `ConcurrentHashMap` and computed once on
first use. The mapper instance should be created once and reused.

## Performance

### Pre-parsed path (JqValue already exists)

This is the primary use case for applications, JSON is parsed once at upload,
stored as `JqValue`, and mapped to records for processing.

| Scenario | jjq-mapper | Jackson `treeToValue` |
|---|---|---|
| Simple record (5 fields) | **128 ns, 360 B** | 127 ns, 416 B |
| Nested record | **215 ns, 624 B** | 199 ns, 560 B |

With the [annotation processor](../jjq-mapper-processor/README.md):

| Scenario | Generated mapper | Jackson `treeToValue` |
|---|---|---|
| Simple record (5 fields) | **22 ns, 56 B** | 127 ns, 416 B |
| Nested record | **32 ns, 80 B** | 199 ns, 560 B |

### From JSON string/bytes

| Scenario | jjq-mapper | Jackson |
|---|---|---|
| Simple from bytes | 322 ns | 259 ns |
| Simple from string | 390 ns | 303 ns |

Jackson is faster for the from-string/bytes path because it does single-pass
token-to-record binding, while jjq-mapper parses to a JqValue tree first.

### Serialization

| Scenario | jjq-mapper | Jackson |
|---|---|---|
| Simple → String | **218 ns, 384 B** | 238 ns, 680 B |
| Nested → String | 257 ns, 496 B | 238 ns, 624 B |

jjq-mapper allocates 30-44% less for serialization due to `JqObject.builder()`
with pre-sized arrays and thread-local StringBuilder reuse.

# jjq-mapper-processor

Compile-time annotation processor for [jjq-mapper](../jjq-mapper/README.md) that generates
optimized mapping classes for `@JqMapped` records. Eliminates runtime reflection, MethodHandle
dispatch, and type conversion cascades — achieving **5-6x faster deserialization** than both
reflection-based jjq-mapper and Jackson.

## Setup

### Maven

Add the processor as a `provided` dependency (compile-time only, not included in runtime):

```xml
<dependencies>
    <!-- Runtime dependency -->
    <dependency>
        <groupId>io.hyperfoil.tools</groupId>
        <artifactId>jjq-mapper</artifactId>
        <version>${jjq.version}</version>
    </dependency>
    <!-- Compile-time annotation processor -->
    <dependency>
        <groupId>io.hyperfoil.tools</groupId>
        <artifactId>jjq-mapper-processor</artifactId>
        <version>${jjq.version}</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

If your project uses explicit annotation processor paths in `maven-compiler-plugin`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>io.hyperfoil.tools</groupId>
                <artifactId>jjq-mapper-processor</artifactId>
                <version>${jjq.version}</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

### Gradle

```groovy
implementation "io.hyperfoil.tools:jjq-mapper:${jjqVersion}"
annotationProcessor "io.hyperfoil.tools:jjq-mapper-processor:${jjqVersion}"
```

## Usage

Annotate your records with `@JqMapped`:

```java
import io.hyperfoil.tools.jjq.mapper.JqMapped;
import io.hyperfoil.tools.jjq.mapper.JqField;

@JqMapped
record User(String name, int age, boolean active) {}

@JqMapped
record PerfResult(
    String user,
    @JqField(".config.timeout") int timeout,
    @JqField(".data[0].name") String firstName
) {}
```

The processor generates a `User_JqMapping` and `PerfResult_JqMapping` class in the same
package. These are automatically discovered by `JqMapper` — no configuration needed:

```java
JqMapper mapper = JqMapper.create();

// Uses the generated mapping automatically
User user = mapper.fromJson("{\"name\":\"Alice\",\"age\":30,\"active\":true}", User.class);
String json = mapper.toJson(user);
```

## What Gets Generated

For `@JqMapped record User(String name, int age, boolean active) {}`, the processor generates:

```java
public final class User_JqMapping extends GeneratedMapping<User> {
    private static final JqProgram P_NAME = JqProgram.compile(".name");
    private static final JqProgram P_AGE = JqProgram.compile(".age");
    private static final JqProgram P_ACTIVE = JqProgram.compile(".active");

    @Override
    public User fromJqValue(JqValue input, JqMapper mapper) {
        return new User(
            P_NAME.apply(input).asString(null),
            (int) P_AGE.apply(input).asLong(0),
            P_ACTIVE.apply(input).asBoolean(false)
        );
    }

    @Override
    public JqValue toJqValue(User instance, JqMapper mapper) {
        return JqObject.builder(3)
            .put("name", instance.name())
            .put("age", (long) instance.age())
            .put("active", instance.active())
            .build();
    }

    @Override
    public Class<User> type() { return User.class; }
}
```

Key properties:
- **Static `JqProgram` fields** — compiled once in the class initializer, shared across threads
- **Direct constructor call** — `new User(a, b, c)` instead of `MethodHandle.invokeWithArguments(Object[])`
- **Direct accessor calls** — `instance.name()` instead of `MethodHandle.invoke(instance)`
- **Inlined type conversions** — `.asString(null)` instead of `TypeConverter.toJava()` dispatch chain
- **No intermediate objects** — no `Object[]`, no `FieldMapping`, no `Optional` from `tryGet()`

## Compile-Time Validation

The processor validates `@JqField` expressions at compile time by calling
`JqProgram.compile()`. Invalid jq expressions produce a compile error:

```
error: Invalid jq expression in @JqField(".invalid["):
  ParseException: Unexpected end of input at line 1, column 10
```

## Nested Records

Nested `@JqMapped` records get their own generated mapping class. The generated code
delegates to `JqMapper` for nested types, which discovers the nested mapping automatically:

```java
@JqMapped
record Address(String city, String zip) {}

@JqMapped
record Person(String name, Address address) {}
```

Generates `Address_JqMapping` and `Person_JqMapping`. The `Person_JqMapping.fromJqValue()`
calls `mapper.fromJqValue(extracted, Address.class)` for the nested address, which uses
`Address_JqMapping`.

Records without `@JqMapped` fall back to reflection-based mapping — both generated and
reflection mappings work together seamlessly.

## Fallback Behavior

If the processor is not on the classpath (e.g., in a module that doesn't include it),
`@JqMapped` has no effect at compile time. `JqMapper` falls back to the reflection-based
`ClassMapping` path. Records work with `JqMapper` regardless of whether they are annotated.

This means:
- Adding `@JqMapped` is safe — it never breaks existing code
- The processor is optional — remove it and everything still works, just slower
- You can mix generated and reflection mappings in the same application

## GraalVM Native Image

Generated mappings are native-image friendly by default:
- No `Class.getRecordComponents()` reflection needed
- No `MethodHandle` lookup at runtime
- `JqProgram` static fields are initialized at build time
- No service loader configuration needed

## Performance

Benchmarked against reflection-based jjq-mapper and Jackson `treeToValue` on pre-parsed
JqValue/JsonNode (JMH, 2 forks, 5 iterations):

### Deserialization (pre-parsed tree to record)

| Implementation | Simple (5 fields) | Nested (record + sub) |
|---|---|---|
| **Generated** | **22 ns, 56 B** | **32 ns, 80 B** |
| Reflection | 128 ns, 360 B | 215 ns, 624 B |
| Jackson | 127 ns, 416 B | 199 ns, 560 B |

The generated mapper is **5.8x faster** than Jackson with **7.4x less allocation** for
simple records, and **6.2x faster** with **7x less allocation** for nested records.

### Serialization (record to JSON string)

| Implementation | Simple (5 fields) | Nested (record + sub) |
|---|---|---|
| **Generated** | **181 ns, 376 B** | **216 ns, 528 B** |
| Reflection | 216 ns, 400 B | 256 ns, 528 B |

### Where the Speed Comes From

For a 5-field record, the **22 ns** breaks down to ~4.4 ns per field — very close to
the irreducible minimum of 3 ns for `JqObject.get()`. The reflection-based path adds
~100 ns of overhead from:
- `MethodHandle.invokeWithArguments(Object[])` for constructor (~20 ns)
- `Object[]` allocation for constructor args (~15 ns)
- `TypeConverter` enum switch dispatch per field (~25 ns total)
- `FieldMapping` / `ClassMapping` indirection (~10 ns)

The generated code eliminates all of these, leaving only the jq field extraction
and the record construction.

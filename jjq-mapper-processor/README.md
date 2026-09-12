# jjq-mapper-processor

Compile-time annotation processor for [jjq-mapper](../jjq-mapper/README.md) that generates
optimized mapping classes for `@JqMapped` records and POJOs. Eliminates runtime reflection,
MethodHandle dispatch, and type conversion cascades — achieving **11-15x faster deserialization**
than Jackson 3.

Generates:
- `_JqMapping` classes with direct constructor/setter calls, inlined type conversions,
  and direct-to-JSON/byes serialization (`appendJson`/`appendJsonBytes`)
- `JqMappingRegistry` per package for bulk registration (`JqMapper.builder()`)
- Supports `@JqField`, `@JqIgnore`, `@JqInclude`, `@JqNaming`, `@JqConverter` annotations

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
            .putUnchecked("name", instance.name())
            .putUnchecked("age", (long) instance.age())
            .putUnchecked("active", instance.active())
            .build();
    }

    @Override
    public void appendJson(User instance, StringBuilder _sb, JqMapper mapper) {
        _sb.append('{');
        _sb.append("\"name\":");
        io.hyperfoil.tools.jjq.value.JqValues.appendJsonString(_sb, instance.name());
        _sb.append(',');
        _sb.append("\"age\":");
        _sb.append(instance.age());
        _sb.append(',');
        _sb.append("\"active\":");
        _sb.append(instance.active());
        _sb.append('}');
    }

    @Override
    public void appendJsonBytes(User instance, io.hyperfoil.tools.jjq.value.BytOutput _out, JqMapper mapper) {
        _out.writeByte('{');
        io.hyperfoil.tools.jjq.value.JqValues.appendJsonString(_out, "name");
        _out.writeByte(':');
        io.hyperfoil.tools.jjq.value.JqValues.appendJsonString(_out, instance.name());
        _out.writeByte(',');
        io.hyperfoil.tools.jjq.value.JqValues.appendJsonString(_out, "age");
        _out.writeByte(':');
        _out.writeLong(instance.age());
        _out.writeByte(',');
        io.hyperfoil.tools.jjq.value.JqValues.appendJsonString(_out, "active");
        _out.writeByte(':');
        if (instance.active()) _out.writeTrue(); else _out.writeFalse();
        _out.writeByte('}');
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
- **Direct-to-JSON serialization** — `appendJson` writes fields straight to the output
  buffer, bypassing the `JqObject`/`JqString` tree entirely
- **Direct-to-bytes serialization** — `appendJsonBytes` writes UTF-8 bytes straight to
  a `BytOutput` buffer, bypassing both the tree and the `StringBuilder`
- **Null-safe by construction** — boxed and reference-type fields emit
  `acc == null ? JqNull.NULL : ...` ternaries, so generated output is byte-identical
  to reflection-based mapping for every input including nulls
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

Benchmarked against reflection-based jjq-mapper and Jackson 3 on pre-parsed
JqValue/JsonNode (JMH, 2-3 forks, 5 iterations, JDK 25.0.2 Temurin, as of 2026-09-12):

### Deserialization (pre-parsed tree to record)

| Implementation | Simple (5 fields) | Nested (record + sub) |
|---|---|---|
| **Generated** | **20 ns, 40 B** | **25 ns, 48 B** |
| Reflection | 39 ns, 72 B | 51 ns, 112 B |
| Jackson 3 | 227 ns, 656 B | 364 ns, 600 B |

The generated mapper is **11x faster** than Jackson 3 with **16x less allocation** for
simple records, and **15x faster** with **12x less allocation** for nested records.
Even reflection-based mapping is 5-7x faster than Jackson 3.

### Serialization (record to JSON string)

| Implementation | Simple (5 fields) | Nested (record + sub) |
|---|---|---|
| **Generated** | **92 ns, 120 B** | **150 ns, 344 B** |
| Reflection | 224 ns, 328 B | 264 ns, 440 B |

### Serialization (record to JSON bytes)

| Implementation | Simple (5 fields) | Nested (record + sub) |
|---|---|---|
| **Generated** | **179 ns, 184 B** | **192 ns, 320 B** |
| Reflection | 242 ns, 392 B | 257 ns, 416 B |
| Jackson 3 | 189 ns, 656 B | 164 ns, 600 B |

### Where the Speed Comes From

For a 5-field record, the **20 ns** breaks down to ~4 ns per field — very close to
the irreducible minimum of 3 ns for `JqObject.get()`. The reflection-based path adds
~20 ns of overhead from:
- Pre-cached `asSpreader` constructor handle invocation
- `Object[]` allocation for constructor args (partially escape-analyzed)
- `TypeConverter` enum switch dispatch per field
- `FieldMapping` / `ClassMapping` indirection

The generated code eliminates all of these, leaving only the jq field extraction
and the record construction. On the serialization side, `appendJson`/`appendJsonBytes`
skip the intermediate `JqValue` tree (no `Builder`, `JqObject`, or `JqString` allocation).

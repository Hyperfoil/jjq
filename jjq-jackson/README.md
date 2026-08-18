# jjq-jackson

Jackson databind integration for jjq — provides `JsonNode` <-> `JqValue` conversion, a high-level
`JacksonJqEngine`, and a Jackson `Module` for native `JqValue` serialization in POJOs.

## Dependencies

```xml
<dependency>
    <groupId>io.hyperfoil.tools</groupId>
    <artifactId>jjq-jackson</artifactId>
    <version>${jjq.version}</version>
</dependency>
```

Depends on `jjq-core` and `jackson-databind` (version managed by parent POM).

## Jackson Module

Register `JqValueModule` on any `ObjectMapper` to natively serialize and deserialize
`JqValue` fields in POJOs — no manual conversion needed:

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyperfoil.tools.jjq.jackson.JqValueModule;
import io.hyperfoil.tools.jjq.value.*;

ObjectMapper mapper = new ObjectMapper();
mapper.registerModule(new JqValueModule());
```

Once registered, any class with `JqValue` fields works automatically:

```java
public record MyEntity(String name, JqValue metadata) {}

// Serialize
var metadata = JqObject.of(Map.of("key", JqString.of("value")));
String json = mapper.writeValueAsString(new MyEntity("test", metadata));
// {"name":"test","metadata":{"key":"value"}}

// Deserialize
MyEntity restored = mapper.readValue(json, MyEntity.class);
```

### Standalone serialization

`JqValue` instances can be serialized and deserialized directly:

```java
// Serialize any JqValue type
String json = mapper.writeValueAsString(JqArray.of(List.of(JqNumber.of(1), JqString.of("two"))));
// [1,"two"]

// Deserialize to JqValue
JqValue value = mapper.readValue("{\"name\":\"Alice\",\"age\":30}", JqValue.class);

// Deserialize to specific subtypes
JqObject obj = mapper.readValue("{\"x\":1}", JqObject.class);
JqArray arr = mapper.readValue("[1,2,3]", JqArray.class);
```

### How it works

- **Serializer** (`JqValueSerializer`) streams directly to Jackson's `JsonGenerator` without
  intermediate String allocation. Uses int/long/double/BigDecimal generator methods for exact
  numeric representation. NaN and Infinity serialize as JSON `null` (matching jq behavior).

- **Deserializer** (`JqValueDeserializer`) reads directly from Jackson's `JsonParser` without
  building an intermediate `JsonNode` tree. Preserves `long` for integers and `BigDecimal` for
  exact decimals. JSON `null` deserializes to `JqNull.NULL` (not Java `null`).

- **Module** (`JqValueModule`) registers handlers for the sealed `JqValue` interface and all
  six concrete subtypes, so fields declared with specific types (e.g., `JqObject metadata`)
  are also handled.

## JacksonJqEngine

High-level API for applying jq filters to Jackson `JsonNode` trees:

```java
import io.hyperfoil.tools.jjq.jackson.JacksonJqEngine;
import io.hyperfoil.tools.jjq.JqProgram;

JacksonJqEngine engine = new JacksonJqEngine(mapper);

// One-shot: parse, compile, execute, convert back
List<JsonNode> results = engine.apply(".users[] | .name", jsonNode);

// Pre-compiled for repeated use (recommended)
JqProgram program = engine.compile(".users[] | {name, email}");
List<JsonNode> r1 = engine.apply(program, request1);
List<JsonNode> r2 = engine.apply(program, request2);

// First result only
JsonNode first = engine.applyFirst(program, input);

// With variables
Environment env = new Environment();
env.setVariable("target", JqString.of("Alice"));
List<JsonNode> filtered = engine.apply(program, input, env);
```

The engine internally uses lazy conversion (`JacksonConverter.fromJsonNodeLazy`) so only
accessed fields are converted. Identity passthrough is optimized — if the filter returns the
input unchanged, the original `JsonNode` is returned without reconstruction.

## JsonNode <-> JqValue Conversion

For direct conversion without the engine:

```java
import io.hyperfoil.tools.jjq.jackson.JacksonConverter;

// Jackson -> jjq (eager — converts entire tree)
JqValue jqValue = JacksonConverter.fromJsonNode(jsonNode);

// Jackson -> jjq (lazy — nested elements converted on access)
JqValue lazy = JacksonConverter.fromJsonNodeLazy(jsonNode);

// jjq -> Jackson
JsonNode node = JacksonConverter.toJsonNode(jqValue);
JsonNode node = JacksonConverter.toJsonNode(jqValue, customMapper);
```

### Lazy vs eager conversion

- **`fromJsonNodeLazy`** (default in `JacksonJqEngine`) — wraps Jackson `ObjectNode`/`ArrayNode`
  lazily. Nested elements are only converted when the filter accesses them. Best for large documents
  where the filter touches a subset of fields.

- **`fromJsonNode`** — converts the entire tree eagerly. Best for small documents or when the filter
  accesses most fields.

The lazy threshold is configurable: objects with <= 16 fields (default) are converted eagerly.
Set via system property: `-Djjq.jackson.lazy.eagerObjectThreshold=8`.

## Spring Boot Integration

With `JqValueModule` registered, Spring Boot's `ObjectMapper` natively handles `JqValue`:

```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {
    @Bean
    public JqValueModule jqValueModule() {
        return new JqValueModule();
    }
}
```

Spring Boot auto-discovers `Module` beans and registers them on the default `ObjectMapper`.
After this, `@RequestBody JqValue` and `@ResponseBody JqValue` work in Spring MVC controllers,
and any `@RestController` returning POJOs with `JqValue` fields serializes correctly.

## Quarkus Integration

In Quarkus with RESTEasy Jackson, register the module via a CDI producer:

```java
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;

@Singleton
public class JqValueMapperCustomizer implements ObjectMapperCustomizer {
    @Override
    public void customize(ObjectMapper mapper) {
        mapper.registerModule(new JqValueModule());
    }
}
```

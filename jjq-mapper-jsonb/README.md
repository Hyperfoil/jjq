# jjq-mapper-jsonb

Bridges Jakarta JSON-B annotations into [jjq-mapper](../jjq-mapper), allowing existing JSON-B-annotated DTOs to work with jjq's data binding without duplicate annotations.

## Quick Start

Add the dependency alongside `jjq-mapper`:

```xml
<dependency>
    <groupId>io.hyperfoil.tools</groupId>
    <artifactId>jjq-mapper-jsonb</artifactId>
    <version>${jjq.version}</version>
</dependency>
```

The bridge is discovered automatically via `ServiceLoader` — no code changes needed:

```java
JqMapper mapper = JqMapper.create(); // auto-discovers the JSON-B bridge

record User(@JsonbProperty("full_name") String name, @JsonbTransient String secret, int age) {}

User user = mapper.fromJqValue(json, User.class);
// Reads from "full_name", ignores "secret"
```

Or register explicitly via the builder:

```java
JqMapper mapper = JqMapper.builder()
    .bridge(new JsonbAnnotationBridge())
    .build();
```

## Supported Annotations

| JSON-B Annotation | Effect |
|---|---|
| `@JsonbProperty("name")` | Field name override (equivalent to `@JqField(".name")`) |
| `@JsonbTransient` | Exclude field (equivalent to `@JqIgnore`) |
| `@JsonbNillable` | Include null fields (maps to `@JqInclude(ALWAYS)`) |

## Priority

When multiple annotation systems are present on the same field:

1. **jjq annotations** (`@JqField`, `@JqIgnore`, `@JqInclude`) — always win
2. **JSON-B annotations** — used as fallback when jjq annotations are absent
3. **Default** — Java field name, include all

## Quarkus Integration

Quarkus uses JSON-B (via Yasson) as its default JSON serialization framework. With this bridge module, DTOs annotated for Quarkus REST endpoints automatically work with jjq-mapper:

```java
// Works with both Quarkus REST (JSON-B) and jjq-mapper
record DetectionResult(
    @JsonbProperty("ratio") double ratio,
    @JsonbProperty("previous") double previous,
    @JsonbTransient String internalNote
) {}

// Quarkus CDI producer
@ApplicationScoped
public class JqMapperProducer {
    @Produces @Singleton
    JqMapper mapper() {
        return JqMapper.create(); // auto-discovers JSON-B bridge
    }
}
```

## Dependencies

This module depends only on `jakarta.json.bind-api` (the annotation API, not a JSON-B implementation). Compatible with Jakarta JSON-B 3.0+ as bundled by Quarkus 3.x.

## Works With

- Java records and POJOs (via `@JqMapped` or reflection fallback)
- `JqMapper.create()` (ServiceLoader) and `JqMapper.builder()` (explicit)
- All jjq-mapper features: `@JqNaming`, `@JqInclude`, `@JqConverter`

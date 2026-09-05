# jjq-mapper-jackson

Bridges Jackson 2 annotations into [jjq-mapper](../jjq-mapper), allowing existing Jackson-annotated DTOs to work with jjq's data binding without duplicate annotations.

## Quick Start

Add the dependency alongside `jjq-mapper`:

```xml
<dependency>
    <groupId>io.hyperfoil.tools</groupId>
    <artifactId>jjq-mapper-jackson</artifactId>
    <version>${jjq.version}</version>
</dependency>
```

The bridge is discovered automatically via `ServiceLoader` — no code changes needed:

```java
JqMapper mapper = JqMapper.create(); // auto-discovers the Jackson bridge

record User(@JsonProperty("full_name") String name, @JsonIgnore String secret, int age) {}

User user = mapper.fromJqValue(json, User.class);
// Reads from "full_name", ignores "secret"
```

Or register explicitly via the builder:

```java
JqMapper mapper = JqMapper.builder()
    .bridge(new JacksonAnnotationBridge())
    .build();
```

## Supported Annotations

| Jackson Annotation | Effect |
|---|---|
| `@JsonProperty("name")` | Field name override (equivalent to `@JqField(".name")`) |
| `@JsonIgnore` | Exclude field (equivalent to `@JqIgnore`) |
| `@JsonIgnoreProperties({"a", "b"})` | Class-level field exclusion |
| `@JsonInclude(Include.NON_NULL)` | Null/empty inclusion control (equivalent to `@JqInclude`) |

## Priority

When multiple annotation systems are present on the same field:

1. **jjq annotations** (`@JqField`, `@JqIgnore`, `@JqInclude`) — always win
2. **Jackson annotations** — used as fallback when jjq annotations are absent
3. **Default** — Java field name, include all

```java
record Mixed(
    @JqField(".custom") String name,           // @JqField wins — reads from ".custom"
    @JsonProperty("score") double rating,      // Jackson bridge — reads from "score"
    String plain                               // default — reads from "plain"
) {}
```

## Dependencies

This module depends only on `jackson-annotations` (not `jackson-databind`), keeping the dependency footprint minimal. Compatible with Jackson 2.x as bundled by Quarkus 3.x.

## Works With

- Java records and POJOs (via `@JqMapped` or reflection fallback)
- `JqMapper.create()` (ServiceLoader) and `JqMapper.builder()` (explicit)
- All jjq-mapper features: `@JqNaming`, `@JqInclude`, `@JqConverter`

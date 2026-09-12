# jjq-yaml

YAML parsing, querying, emission, and POJO mapping via jjq's [JqValue](../jjq-core) tree.

## Quick Start

```xml
<dependency>
    <groupId>io.hyperfoil.tools</groupId>
    <artifactId>jjq-yaml</artifactId>
    <version>${jjq.version}</version>
</dependency>
```

```java
import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.yaml.JqYaml;

JqValue config = JqYaml.parse(Files.readString(Path.of("config.yaml")));

// Query with jq expressions
String version = JqProgram.compile(".java.version").apply(config).stringValue();
long optCount = JqProgram.compile(".java.opts | length").apply(config).longValue();
```

## Multi-Document YAML

Kubernetes manifests and other multi-document YAML streams (`---` separated) are supported:

```java
// As a List — iterate documents individually
List<JqValue> docs = JqYaml.parseAll(manifest);
for (JqValue doc : docs) {
    System.out.println(doc.getField("kind").stringValue());
}

// As a JqArray — query across all documents with jq
JqArray resources = JqYaml.parseAllAsArray(manifest);
List<JqValue> names = JqProgram.compile(".[].metadata.name").applyAll((JqValue) resources);
```

## Type Mapping

| YAML | JqValue |
|------|---------|
| Mapping | `JqObject` |
| Sequence | `JqArray` |
| String | `JqString` |
| Integer (decimal, hex `0xFF`, octal `077`, binary `0b1010`) | `JqNumber` |
| Float (including `.inf`, `-.inf`, `.nan`) | `JqNumber` |
| Boolean (`true`/`false`, `yes`/`no`, `on`/`off`) | `JqBoolean` |
| Null (`null`, `~`) | `JqNull` |

## Features

- **YAML anchors and aliases** — resolved transparently by SnakeYAML
- **Merge keys (`<<`)** — flattened into the parent mapping
- **Flow and block styles** — both supported (`{a: 1}` and `a: 1`)
- **Round-trip to JSON** — parse YAML, then `toJsonString()` for JSON output

## Emitting YAML

```java
JqValue config = JqYaml.parse("name: Alice\nage: 30\n");
JqValue fromStream = JqYaml.parse(inputStream);
JqValue fromReader = JqYaml.parse(reader);

String yaml = JqYaml.toYaml(config);

try (var out = Files.newOutputStream(path)) {
    JqYaml.toYaml(config, out);
}
JqYaml.toYaml(config, writer);
```

Emission converts the tree to plain Java values and dumps block-style YAML via SnakeYAML.

## YAML → Java Object Mapping

With `jjq-mapper` on the classpath (optional dependency), parse YAML directly into records or POJOs:

```java
import io.hyperfoil.tools.jjq.mapper.JqMapper;

record ServerConfig(String host, int port) {}

JqMapper mapper = JqMapper.create();
ServerConfig config = JqYaml.fromYaml(yamlString, mapper, ServerConfig.class);

// From streams, and with generic types (e.g., List<ServerConfig>)
ServerConfig fromStream = JqYaml.fromYaml(inputStream, mapper, ServerConfig.class);
java.lang.reflect.Type listType = ...;
List<ServerConfig> all = JqYaml.fromYaml(yamlString, mapper, listType);
```

Works with `@JqField`, `@JqIgnore`, `@JqInclude`, `@JqNaming`, `@JqConverter`, and Jackson/JSON-B annotation bridges. See [jjq-mapper](../jjq-mapper/README.md).

## Jackson Migration

`JqValue` provides Jackson-compatible navigation aliases for mechanical migration:

```java
// Jackson:  node.path("server").path("host").asText("")
// jjq:     value.path("server").path("host").asText("")

// Jackson:  if (node.path("config").isMissingNode()) ...
// jjq:     if (value.path("config").isMissingNode()) ...

// Jackson:  YAML.writeValue(file, config)
// jjq:     JqYaml.toYaml(mapper.toJqValue(config), outputStream)

// Jackson:  YAML.readValue(yaml, Config.class)
// jjq:     JqYaml.fromYaml(yaml, mapper, Config.class)
```

## Example: Querying Application Config

```yaml
# application.yaml
database:
  primary:
    host: db.example.com
    port: 5432
  replicas:
    - host: replica1.example.com
    - host: replica2.example.com
```

```java
JqValue config = JqYaml.parse(yamlString);

// Primary host
String host = JqProgram.compile(".database.primary.host")
    .apply(config).stringValue();
// -> "db.example.com"

// All replica hosts
List<JqValue> replicas = JqProgram.compile(".database.replicas[].host")
    .applyAll(config);
// -> ["replica1.example.com", "replica2.example.com"]

// Convert to JSON
String json = config.toJsonString();
```

## Dependencies

Uses [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml/) for YAML parsing (see `${snakeyaml.version}` in the root `pom.xml`). SnakeYAML is already bundled by Quarkus and Spring Boot — adding this module typically introduces no new transitive dependencies.

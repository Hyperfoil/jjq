# jjq-jakarta

Jakarta EE integration for jjq — provides Hibernate persistence types, JPA `AttributeConverter`,
JAX-RS providers, and Jakarta JSON-B serializers that enable `JqValue` as a first-class JSON type
across the Jakarta EE stack.

## Dependencies

```xml
<dependency>
    <groupId>io.hyperfoil.tools</groupId>
    <artifactId>jjq-jakarta</artifactId>
    <version>${jjq.version}</version>
</dependency>
```

The module depends on `hibernate-core`, `jakarta.ws.rs-api`, `jakarta.json.bind-api`, and
`jakarta.json-api` with `provided` scope — your application's runtime (Quarkus, Spring, WildFly)
supplies the actual versions. Only include the features you use; unused Jakarta APIs are not
required at runtime.

## Hibernate Persistence

Two approaches for persisting `JqValue` fields:

### Option 1: BYTEA with @JqValueColumn (recommended)

Zero-copy persistence using direct byte serialization and JDBC binary streaming. Deferred
string values from byte parsing are copied as raw bytes during serialization — no String
construction, no UTF-8 re-encoding, no intermediate array copies.

The `@JqValueColumn` composite annotation combines the three Hibernate annotations
(`@JdbcType`, `@JavaType`, `@Mutability`) into one:

```java
import io.hyperfoil.tools.jjq.jakarta.JqValueColumn;
import io.hyperfoil.tools.jjq.value.JqValue;

@Entity
public class MyEntity {

    @JqValueColumn
    @Column(columnDefinition = "BYTEA")
    public JqValue data;
}
```

`@Column(columnDefinition = "BYTEA")` is still needed separately because column definitions
are database-specific (BYTEA for PostgreSQL, BLOB for SQLite/MySQL, etc.).

If you prefer explicit annotations, the equivalent expanded form is:

```java
@Column(columnDefinition = "BYTEA")
@org.hibernate.annotations.JdbcType(JqValueJdbcType.class)
@org.hibernate.annotations.JavaType(JqValueJavaType.class)
@Mutability(Immutability.class)
public JqValue data;
```

**Write path (zero copy):**
1. `JqValues.serializeToByteOutput(value)` — serializes directly into a pre-sized `byte[]` buffer (no `Arrays.copyOf`)
2. `PreparedStatement.setBinaryStream(index, stream, length)` — wraps the buffer in `ByteArrayInputStream` (no copy). PostgreSQL's JDBC driver stores the stream reference and reads lazily during execute.

**Read path:**
- `ResultSet.getBytes()` → `JqValues.parse(byte[])` — SWAR-optimized byte parser with field name interning

**Benefits:**
- **2LC compatible:** `JqValue` implements `Serializable` with proper singleton preservation
- **No FormatMapper registration needed** — `@JqValueColumn` is self-contained
- **No JSONB overhead** — avoids PostgreSQL's JSONB parse/validate on write and JSONB-to-text serialize on read
- **Immutability-safe** — `JqValue` is immutable, so Hibernate skips `deepCopy()` for snapshots and
  second-level cache. Dirty checking uses `.equals()`, so reassigning the field to a different
  `JqValue` instance is correctly detected

**Migration from JSONB:** If switching an existing JSONB column to BYTEA:
```sql
ALTER TABLE my_entity ALTER COLUMN data TYPE BYTEA USING data::bytea;
```

### Option 2: JSONB with FormatMapper

Uses String-based I/O via Hibernate's `FormatMapper` SPI. Suitable when you need
PostgreSQL JSONB operators or GIN indexes on the column.

```java
@Entity
public class MyEntity {

    @Column(columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    @Mutability(Immutability.class)
    public JqValue data;
}
```

The FormatMapper requires registration with your framework:

**Quarkus:**
```java
@ApplicationScoped
@PersistenceUnitExtension
@JsonFormat
public class MyJsonFormatMapper extends JqValueFormatMapper {}
```

**Spring Boot:**
```java
@Configuration
public class HibernateConfig {
    @Bean
    public JqValueFormatMapper jqValueFormatMapper() {
        return new JqValueFormatMapper();
    }
}
```

**Plain Hibernate:**
```properties
hibernate.type.json_format_mapper=io.hyperfoil.tools.jjq.jakarta.JqValueFormatMapper
```

## JPA AttributeConverter (portable)

For portable JPA persistence that works with any JPA provider (Hibernate, EclipseLink,
OpenJPA), use the `JqValueConverter`:

```java
import io.hyperfoil.tools.jjq.jakarta.JqValueConverter;

@Entity
public class MyEntity {

    @Convert(converter = JqValueConverter.class)
    @Column(columnDefinition = "TEXT")
    private JqValue metadata;
}
```

Each field requires an explicit `@Convert` annotation. Auto-apply is intentionally
disabled to avoid conflicting with the Hibernate-specific `@JdbcType`/`@JavaType`
BYTEA mapping on other `JqValue` fields in the same entity.

- **Write:** `JqValue.toJsonString()` -> VARCHAR/TEXT column
- **Read:** `JqValues.parse(String)` -> `JqValue`
- `null` database values map to Java `null`

For better performance on PostgreSQL, prefer the Hibernate-specific `JqValueJdbcType` (BYTEA)
or `JqValueFormatMapper` (JSONB) described above.

## JAX-RS Providers

The module includes `@Provider`-annotated `MessageBodyReader` and `MessageBodyWriter`
implementations that handle `JqValue` serialization for REST endpoints.

These are auto-discovered by JAX-RS implementations (Quarkus RESTEasy, Jersey, etc.)
via the `@Provider` annotation. No additional configuration is needed.

### REST endpoint example

```java
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqObject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

@Path("/api/data")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DataResource {

    @POST
    public Response create(JqValue data) {
        // data is parsed from the JSON request body by JqValueMessageBodyReader
        // using JqValues.parse(byte[]) — zero intermediate String allocation
        service.store(data);
        return Response.ok().build();
    }

    @GET
    @Path("{id}")
    public JqValue get(@PathParam("id") long id) {
        // JqValue is serialized to JSON by JqValueMessageBodyWriter
        // using JqValue.toJsonString()
        return service.load(id);
    }
}
```

### Query/path parameter conversion

The module includes a `ParamConverterProvider` that enables `JqValue` as a type for
`@QueryParam`, `@PathParam`, `@HeaderParam`, and `@FormParam` parameters:

```java
@GET
@Path("/search")
public Response search(@QueryParam("filter") JqValue filter) {
    // filter is parsed from the JSON query parameter string
    // e.g., /search?filter={"status":"active","limit":10}
    String status = filter.getField("status").asString("all");
    return Response.ok(service.search(status)).build();
}
```

The `JqValueParamConverterProvider` is annotated with `@Provider` and auto-discovered.
It parses JSON strings from request parameters via `JqValues.parse()` and serializes
back via `JqValue.toJsonString()`.

### Building JSON responses

Use the `JqObject.builder()` and `JqArray.arrayBuilder()` APIs to construct JSON values:

```java
import io.hyperfoil.tools.jjq.value.*;

JqObject response = JqObject.builder()
    .put("status", "ok")
    .put("count", 42)
    .put("items", JqArray.arrayBuilder()
        .add(JqObject.builder().put("id", 1).put("name", "first").build())
        .add(JqObject.builder().put("id", 2).put("name", "second").build())
        .build())
    .build();
```

## Jakarta JSON-B Support

For pure Jakarta EE environments that use JSON-B instead of Jackson (Open Liberty, Payara,
etc.), the module provides `JsonbSerializer` and `JsonbDeserializer` implementations:

```java
import io.hyperfoil.tools.jjq.jakarta.JqValueJsonbSerializer;
import io.hyperfoil.tools.jjq.jakarta.JqValueJsonbDeserializer;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;

JsonbConfig config = new JsonbConfig()
    .withSerializers(new JqValueJsonbSerializer())
    .withDeserializers(new JqValueJsonbDeserializer());
Jsonb jsonb = JsonbBuilder.create(config);

// Serialize
String json = jsonb.toJson(myJqValue, JqValue.class);

// Deserialize
JqValue value = jsonb.fromJson(jsonString, JqValue.class);
```

### Per-field annotation

You can also register the serializers on individual entity fields:

```java
import jakarta.json.bind.annotation.JsonbTypeSerializer;
import jakarta.json.bind.annotation.JsonbTypeDeserializer;

public class MyEntity {
    public String name;

    @JsonbTypeSerializer(JqValueJsonbSerializer.class)
    @JsonbTypeDeserializer(JqValueJsonbDeserializer.class)
    public JqValue metadata;
}
```

### POJOs with JqValue fields

Once registered, JqValue fields in POJOs are automatically handled:

```java
public class Config {
    public String name;
    public JqValue settings;  // serialized as nested JSON
}

Config config = new Config();
config.name = "production";
config.settings = JqValues.parse("{\"timeout\":30,\"retries\":3}");

String json = jsonb.toJson(config);
// {"name":"production","settings":{"timeout":30,"retries":3}}

Config restored = jsonb.fromJson(json, Config.class);
```

## Performance

The module leverages jjq's optimized parser and serializer:

- **Parsing:** `JqValueMessageBodyReader` uses `JqValues.parse(byte[])` for zero-intermediate-String
  parsing directly from the HTTP input stream. jjq's byte parser is 1.3-2.4x faster than Jackson on
  10KB inputs with 26% less allocation.

- **Serialization:** `JqValueMessageBodyWriter` uses `JqValues.serializeTo(value, outputStream)` which
  uses the direct byte serialization path — no intermediate String or StringBuilder.

- **BYTEA persistence (JdbcType):** `JqValueJdbcType` uses `serializeToByteOutput()` with
  `setBinaryStream()` for writes and `parse(byte[])` for reads. The write path achieves
  **zero array copies** — the pre-sized buffer is wrapped in `ByteArrayInputStream` and
  streamed directly to the JDBC driver. PostgreSQL's `setBytes()` makes a defensive
  `System.arraycopy`; `setBinaryStream(is, len)` stores the stream reference and reads
  lazily during execute. For pass-through workloads (parse JSON → store → retrieve → serialize),
  deferred string values flow as raw bytes from input to database without ever constructing
  a Java String.

- **JSONB persistence (FormatMapper):** `JqValueFormatMapper` eliminates Jackson's `ObjectMapper`
  from the Hibernate serialization path.

- **Immutability:** Combined with `@Mutability(Immutability.class)` on the entity field,
  both approaches avoid `deepCopy` overhead for second-level cache and dirty checking.

# jjq-jakarta

Jakarta EE integration for jjq — provides Hibernate persistence types and JAX-RS providers
that enable `JqValue` as a first-class JSON type in persistence and REST layers.

## Dependencies

```xml
<dependency>
    <groupId>io.hyperfoil.tools</groupId>
    <artifactId>jjq-jakarta</artifactId>
    <version>${jjq.version}</version>
</dependency>
```

The module depends on `hibernate-core` and `jakarta.ws.rs-api` with `provided` scope —
your application's runtime (Quarkus, Spring, WildFly) supplies the actual versions.

## Hibernate Persistence

Two approaches for persisting `JqValue` fields:

### Option 1: BYTEA with JdbcType (recommended)

Uses direct `byte[]` JDBC I/O — no intermediate String allocation. Deferred string values
from byte parsing are copied as raw bytes during serialization, making the
`parse(byte[])` -> `serializeToBytes()` round-trip optimal.

```java
import io.hyperfoil.tools.jjq.jakarta.JqValueJdbcType;
import io.hyperfoil.tools.jjq.jakarta.JqValueJavaType;
import io.hyperfoil.tools.jjq.value.JqValue;
import org.hibernate.annotations.Mutability;
import org.hibernate.type.descriptor.java.Immutability;

@Entity
public class MyEntity {

    @Column(columnDefinition = "BYTEA")
    @org.hibernate.annotations.JdbcType(JqValueJdbcType.class)
    @org.hibernate.annotations.JavaType(JqValueJavaType.class)
    @Mutability(Immutability.class)
    public JqValue data;
}
```

- **Write:** `JqValues.serializeToBytes(value)` -> `PreparedStatement.setBytes()`
- **Read:** `ResultSet.getBytes()` -> `JqValues.parse(byte[])`
- **2LC compatible:** `JqValue` implements `Serializable` with proper singleton preservation
- **No FormatMapper registration needed** — the `@JdbcType` and `@JavaType` annotations are self-contained

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

## Performance

The module leverages jjq's optimized parser and serializer:

- **Parsing:** `JqValueMessageBodyReader` uses `JqValues.parse(byte[])` for zero-intermediate-String
  parsing directly from the HTTP input stream. jjq's byte parser is 1.3-2.4x faster than Jackson on
  10KB inputs with 26% less allocation.

- **Serialization:** `JqValueMessageBodyWriter` uses `JqValues.serializeTo(value, outputStream)` which
  uses the direct byte serialization path — no intermediate String or StringBuilder.

- **BYTEA persistence (JdbcType):** `JqValueJdbcType` uses `serializeToBytes()` for writes and
  `parse(byte[])` for reads. Deferred string values from byte parsing are copied as raw bytes
  during serialization — zero String construction, zero UTF-8 re-encoding. This is the fastest
  persistence path for pass-through workloads (parse JSON upload, store, retrieve, serialize).

- **JSONB persistence (FormatMapper):** `JqValueFormatMapper` eliminates Jackson's `ObjectMapper`
  from the Hibernate serialization path.

- **Immutability:** Combined with `@Mutability(Immutability.class)` on the entity field,
  both approaches avoid `deepCopy` overhead for second-level cache and dirty checking.

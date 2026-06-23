# jjq-jakarta

Jakarta EE integration for jjq — provides a Hibernate `FormatMapper` and JAX-RS providers
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

## Hibernate FormatMapper

`JqValueFormatMapper` implements Hibernate's `FormatMapper` SPI to serialize and
deserialize `JqValue` instances for `@JdbcTypeCode(SqlTypes.JSON)` columns. It uses
jjq's built-in parser (`JqValues.parse()`) and serializer (`JqValue.toJsonString()`),
eliminating Jackson from the persistence path.

The class has no framework-specific annotations. Register it with your framework:

### Quarkus

Create a subclass with Quarkus CDI annotations:

```java
import io.hyperfoil.tools.jjq.jakarta.JqValueFormatMapper;
import io.quarkus.hibernate.orm.JsonFormat;
import io.quarkus.hibernate.orm.PersistenceUnitExtension;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@PersistenceUnitExtension
@JsonFormat
public class MyJsonFormatMapper extends JqValueFormatMapper {}
```

Quarkus discovers this bean automatically and registers it as the Hibernate JSON format mapper.

### Spring Boot

Register as a Spring bean:

```java
import io.hyperfoil.tools.jjq.jakarta.JqValueFormatMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HibernateConfig {
    @Bean
    public JqValueFormatMapper jqValueFormatMapper() {
        return new JqValueFormatMapper();
    }
}
```

### Plain Hibernate

Set the format mapper via Hibernate properties:

```properties
hibernate.type.json_format_mapper=io.hyperfoil.tools.jjq.jakarta.JqValueFormatMapper
```

### Entity mapping

Once the `FormatMapper` is registered, use `JqValue` as the field type for JSON columns:

```java
import io.hyperfoil.tools.jjq.value.JqValue;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
public class MyEntity {

    @Column(columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    public JqValue data;
}
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
  parsing directly from the HTTP input stream. jjq's byte parser is 1.9x faster than Jackson on
  production-scale data.

- **Serialization:** `JqValueMessageBodyWriter` uses `JqValue.toJsonString()` which benefits from
  thread-local StringBuilder reuse and deferred string zero-copy optimizations.

- **Persistence:** `JqValueFormatMapper` eliminates Jackson's `ObjectMapper` from the Hibernate
  serialization path. Combined with `@Mutability(Immutability.class)` on the entity field,
  this avoids the `FormatMapperBasedJavaType.deepCopy` overhead that can dominate persistence CPU.

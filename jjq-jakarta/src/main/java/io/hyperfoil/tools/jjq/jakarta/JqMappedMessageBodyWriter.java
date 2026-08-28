package io.hyperfoil.tools.jjq.jakarta;

import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.mapper.JqMapped;
import io.hyperfoil.tools.jjq.value.JqValues;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

/**
 * JAX-RS {@link MessageBodyWriter} that serializes {@link JqMapped @JqMapped}
 * records directly to JSON response bodies using jjq's mapper and serializer —
 * bypassing Jackson entirely.
 *
 * <p>Only activates for types annotated with {@code @JqMapped}. Other types
 * fall through to Jackson or other registered writers.</p>
 *
 * <p>Serializes via {@link JqMapper#toJqValue(Object)} then
 * {@link JqValues#serializeTo}, streaming directly to the response
 * output stream with no intermediate String allocation.</p>
 *
 * <p>Usage — just annotate your record and return it from a REST endpoint:</p>
 * <pre>{@code
 * @JqMapped
 * record UserResponse(String name, int age, String email) {}
 *
 * @GET
 * @Path("/{id}")
 * public UserResponse getUser(@PathParam("id") long id) {
 *     // response is serialized via jjq, not Jackson
 *     return new UserResponse("Alice", 30, "alice@example.com");
 * }
 * }</pre>
 *
 * <p>Annotated with {@code @Provider} for automatic discovery by JAX-RS
 * implementations (Quarkus RESTEasy, Jersey, etc.).</p>
 *
 * @see JqMappedMessageBodyReader
 * @see JqMapped
 */
@Provider
@Produces(MediaType.APPLICATION_JSON)
public class JqMappedMessageBodyWriter implements MessageBodyWriter<Object> {

    private final JqMapper mapper;

    /** Creates a new writer with a default JqMapper. */
    public JqMappedMessageBodyWriter() {
        this(JqMapper.create());
    }

    /** Creates a new writer with the given JqMapper (for pre-registered generated mappings). */
    public JqMappedMessageBodyWriter(JqMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType,
                                Annotation[] annotations, MediaType mediaType) {
        return type.isAnnotationPresent(JqMapped.class);
    }

    @Override
    public void writeTo(Object value, Class<?> type, Type genericType,
                         Annotation[] annotations, MediaType mediaType,
                         MultivaluedMap<String, Object> httpHeaders,
                         OutputStream entityStream) throws IOException, WebApplicationException {
        if (value == null) {
            return;
        }
        JqValues.serializeTo(mapper.toJqValue(value), entityStream);
    }
}

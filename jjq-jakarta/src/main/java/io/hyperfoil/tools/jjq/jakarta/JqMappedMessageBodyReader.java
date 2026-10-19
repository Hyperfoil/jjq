package io.hyperfoil.tools.jjq.jakarta;

import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.mapper.JqMapped;
import io.hyperfoil.tools.jjq.mapper.JqType;
import io.hyperfoil.tools.jjq.mapper.JqKeys;
import io.hyperfoil.tools.jjq.mapper.JqMapperException;
import io.hyperfoil.tools.jjq.value.JqNull;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

import jakarta.inject.Inject;

/**
 * JAX-RS {@link MessageBodyReader} that deserializes JSON request bodies directly
 * into {@link JqMapped @JqMapped} records using jjq's mapper — bypassing Jackson entirely.
 *
 * <p>Only activates for types annotated with {@code @JqMapped}. Other types
 * fall through to Jackson or other registered readers.</p>
 *
 * <p>Uses jjq's byte[]-based parser for zero-intermediate-String parsing,
 * then maps to the target record via {@link JqMapper}. Request bodies are
 * parsed strictly: malformed JSON, invalid UTF-8, duplicate keys, and
 * trailing content are rejected with {@code 400 Bad Request}, as are
 * values that cannot bind to the target type. A JSON {@code null} body
 * binds to a {@code null} reference, mirroring Jackson. If a generated
 * {@code _JqMapping} class exists (from {@code jjq-mapper-processor}),
 * the mapping executes in ~20 ns per 5-field record. Otherwise, the
 * reflection-based mapper is used (~37 ns).</p>
 *
 * <p>Usage — just annotate your record and use it as a REST parameter:</p>
 * <pre>{@code
 * @JqMapped
 * record CreateUserRequest(String name, int age, String email) {}
 *
 * @POST
 * public Response createUser(CreateUserRequest request) {
 *     // request is deserialized via jjq, not Jackson
 *     service.create(request.name(), request.age(), request.email());
 *     return Response.ok().build();
 * }
 * }</pre>
 *
 * <p>Annotated with {@code @Provider} for automatic discovery by JAX-RS
 * implementations (Quarkus RESTEasy, Jersey, etc.).</p>
 *
 * @see JqMappedMessageBodyWriter
 * @see JqMapped
 */
@Provider
@Consumes(MediaType.APPLICATION_JSON)
public class JqMappedMessageBodyReader implements MessageBodyReader<Object> {

    private final JqMapper mapper;

    /** Creates a new reader with a default JqMapper. */
    public JqMappedMessageBodyReader() {
        this(JqMapper.create());
    }

    /**
     * Creates a new reader with the given JqMapper (for pre-registered generated mappings).
     * CDI runtimes (including the {@code jjq-quarkus} extension) inject the
     * shared mapper through this constructor.
     *
     * @param mapper the JqMapper to use for deserialization
     */
    @Inject
    public JqMappedMessageBodyReader(JqMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean isReadable(Class<?> type, Type genericType,
                               Annotation[] annotations, MediaType mediaType) {
        return type.isAnnotationPresent(JqMapped.class)
                || type.isAnnotationPresent(JqType.class)
                || type.isAnnotationPresent(JqKeys.class);
    }

    @Override
    public Object readFrom(Class<Object> type, Type genericType,
                            Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, String> httpHeaders,
                            InputStream entityStream) throws IOException, WebApplicationException {
        byte[] bytes = entityStream.readAllBytes();
        if (bytes.length == 0) {
            return null;
        }
        final JqValue value;
        try {
            value = JqValues.parseStrict(bytes);
        } catch (IllegalArgumentException e) {
            throw JqBadRequestException.of("Invalid JSON request body", e);
        }
        if (value instanceof JqNull) {
            return null;
        }
        try {
            return mapper.fromJqValue(value, type);
        } catch (JqMapperException e) {
            throw JqBadRequestException.of("Cannot bind JSON request body", e);
        }
    }
}

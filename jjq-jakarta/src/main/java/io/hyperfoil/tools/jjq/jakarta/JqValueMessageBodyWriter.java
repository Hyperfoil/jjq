package io.hyperfoil.tools.jjq.jakarta;

import io.hyperfoil.tools.jjq.value.JqValue;
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
import java.nio.charset.StandardCharsets;

/**
 * JAX-RS {@link MessageBodyWriter} that serializes {@link JqValue} instances
 * to JSON response bodies using jjq's built-in serializer.
 *
 * <p>Uses {@link JqValue#toJsonString()} for serialization, which benefits from
 * the thread-local StringBuilder reuse and deferred string zero-copy optimizations.</p>
 *
 * <p>Annotated with {@code @Provider} for automatic discovery by JAX-RS
 * implementations (Quarkus RESTEasy, Jersey, etc.).</p>
 */
@Provider
@Produces(MediaType.APPLICATION_JSON)
public class JqValueMessageBodyWriter implements MessageBodyWriter<JqValue> {

    @Override
    public boolean isWriteable(Class<?> type, Type genericType,
                                Annotation[] annotations, MediaType mediaType) {
        return JqValue.class.isAssignableFrom(type);
    }

    @Override
    public void writeTo(JqValue value, Class<?> type, Type genericType,
                         Annotation[] annotations, MediaType mediaType,
                         MultivaluedMap<String, Object> httpHeaders,
                         OutputStream entityStream) throws IOException, WebApplicationException {
        if (value == null) {
            return;
        }
        entityStream.write(value.toJsonString().getBytes(StandardCharsets.UTF_8));
    }
}

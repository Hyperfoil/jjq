package io.hyperfoil.tools.jjq.jakarta;

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

/**
 * JAX-RS {@link MessageBodyReader} that parses JSON request bodies directly
 * into {@link JqValue} instances using jjq's byte[]-based parser.
 *
 * <p>Uses {@link JqValues#parse(byte[])} for zero-intermediate-String parsing
 * directly from the HTTP input stream.</p>
 *
 * <p>Annotated with {@code @Provider} for automatic discovery by JAX-RS
 * implementations (Quarkus RESTEasy, Jersey, etc.).</p>
 */
@Provider
@Consumes(MediaType.APPLICATION_JSON)
public class JqValueMessageBodyReader implements MessageBodyReader<JqValue> {

    /** Creates a new JqValueMessageBodyReader. */
    public JqValueMessageBodyReader() {}

    @Override
    public boolean isReadable(Class<?> type, Type genericType,
                               Annotation[] annotations, MediaType mediaType) {
        return JqValue.class.isAssignableFrom(type);
    }

    @Override
    public JqValue readFrom(Class<JqValue> type, Type genericType,
                             Annotation[] annotations, MediaType mediaType,
                             MultivaluedMap<String, String> httpHeaders,
                             InputStream entityStream) throws IOException, WebApplicationException {
        byte[] bytes = entityStream.readAllBytes();
        if (bytes.length == 0) {
            return null;
        }
        return JqValues.parse(bytes);
    }
}

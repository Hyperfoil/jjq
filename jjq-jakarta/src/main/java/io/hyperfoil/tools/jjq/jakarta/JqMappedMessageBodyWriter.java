package io.hyperfoil.tools.jjq.jakarta;

import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.mapper.JqMapped;
import io.hyperfoil.tools.jjq.mapper.JqType;
import io.hyperfoil.tools.jjq.mapper.JqKeys;
import io.hyperfoil.tools.jjq.mapper.TypeConverter;
import io.hyperfoil.tools.jjq.value.BytOutput;
import io.hyperfoil.tools.jjq.value.JqValue;
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

import jakarta.inject.Inject;

/**
 * JAX-RS {@link MessageBodyWriter} that serializes {@link JqMapped @JqMapped}
 * records directly to JSON response bodies using jjq's mapper and serializer —
 * bypassing Jackson entirely.
 *
 * <p>Only activates for types annotated with {@code @JqMapped} (including
 * {@code @JqType}/{@code @JqKeys} families) and for homogeneous
 * collections, maps, and arrays of such types. Other types
 * fall through to Jackson or other registered writers.</p>
 *
 * <p>Serializes via {@link JqMapper#appendJsonBytes(Object, io.hyperfoil.tools.jjq.value.BytOutput)}
 * into a thread-local byte buffer, streaming directly to the response output
 * stream with no intermediate String allocation and no intermediate JqValue
 * tree for generated mappings.</p>
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

    /**
     * Creates a new writer with the given JqMapper (for pre-registered generated mappings).
     * CDI runtimes (including the {@code jjq-quarkus} extension) inject the
     * shared mapper through this constructor.
     *
     * @param mapper the JqMapper to use for serialization
     */
    @Inject
    public JqMappedMessageBodyWriter(JqMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType,
                                Annotation[] annotations, MediaType mediaType) {
        return CollectionSupport.isDirectlyMappable(type)
                || CollectionSupport.bindsResponseThroughMapper(genericType);
    }

    @Override
    public void writeTo(Object value, Class<?> type, Type genericType,
                         Annotation[] annotations, MediaType mediaType,
                         MultivaluedMap<String, Object> httpHeaders,
                         OutputStream entityStream) throws IOException, WebApplicationException {
        if (value == null) {
            return;
        }
        BytOutput out = JqValues.acquireByteBuffer();
        if (CollectionSupport.isDirectlyMappable(type)) {
            mapper.appendJsonBytes(value, out);
        } else if (value instanceof java.util.Collection<?> collection) {
            writeCollection(collection, out);
        } else if (value instanceof java.util.Map<?, ?> map) {
            writeMap(map, out);
        } else if (value instanceof Object[] array) {
            writeArray(array, out);
        } else {
            // Anything else (primitive arrays and containers the mapper
            // declines) serializes through the tree exactly as before.
            TypeConverter.toJqValue(value, mapper).appendToBytes(out);
        }
        JqValues.writeByteBuffer(out, entityStream);
    }

    /**
     * Write collection elements one by one, dispatching each on its runtime
     * type without building the intermediate element tree — the same
     * per-element behavior as the tree path (which converts each element
     * through its own binding), minus the {@code JqArray}/{@code JqObject}
     * allocation. Empty collections render {@code []}.
     */
    private void writeCollection(java.util.Collection<?> collection, BytOutput out) {
        out.writeByte('[');
        boolean first = true;
        for (Object element : collection) {
            if (!first) out.writeByte(',');
            first = false;
            writeElement(element, out);
        }
        out.writeByte(']');
    }

    /** Write array elements exactly like collection elements. */
    private void writeArray(Object[] array, BytOutput out) {
        out.writeByte('[');
        for (int i = 0; i < array.length; i++) {
            if (i > 0) out.writeByte(',');
            writeElement(array[i], out);
        }
        out.writeByte(']');
    }

    /**
     * Write map entries with stringified keys in iteration order, matching
     * the tree path's {@code String.valueOf} keys.
     */
    private void writeMap(java.util.Map<?, ?> map, BytOutput out) {
        out.writeByte('{');
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) out.writeByte(',');
            first = false;
            JqValues.appendJsonString(out, String.valueOf(entry.getKey()));
            out.writeByte(':');
            writeElement(entry.getValue(), out);
        }
        out.writeByte('}');
    }

    /**
     * Dispatch one collection element or map value on its runtime type,
     * mirroring {@code TypeConverter.toJqValue} endpoint by endpoint:
     * {@code JqValue} passes through, enums render by wire name through the
     * tree, everything else binds through the mapper (null-safe, generated
     * mappings write directly, reflection falls back to the tree with
     * identical output).
     */
    private void writeElement(Object element, BytOutput out) {
        if (element instanceof JqValue jv) {
            JqValues.appendJqValue(out, jv);
        } else if (element != null && element.getClass().isEnum()) {
            TypeConverter.toJqValue(element, mapper).appendToBytes(out);
        } else {
            mapper.appendJsonBytes(element, out);
        }
    }
}

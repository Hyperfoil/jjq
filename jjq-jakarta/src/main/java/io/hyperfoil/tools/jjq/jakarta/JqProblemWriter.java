package io.hyperfoil.tools.jjq.jakarta;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

import jakarta.inject.Inject;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;

import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.mapper.TypeConverter;
import io.hyperfoil.tools.jjq.value.BytOutput;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import io.quarkiverse.httpproblem.HttpProblem;
import io.quarkiverse.httpproblem.InstanceUtils;

/**
 * Renders {@link HttpProblem} entities as {@code application/problem+json}
 * without a JSON engine (issue #176): the problem converts to a
 * {@link JqObject} and serializes straight to bytes, mirroring the
 * extension's {@code JsonbProblemSerializer} field for field.
 *
 * <p>Field order and null-skipping match the engine exactly: {@code type}
 * (absent when null, ASCII form), always {@code status}, {@code title},
 * {@code detail} and {@code instance} (absent when null, path form), then
 * custom parameters in map order. Parameter values convert through the
 * shared mapper exactly like any other Java value.</p>
 *
 * <p>Registered automatically by the {@code jjq-quarkus} extension only
 * when {@code quarkus-http-problem} is present and no JSON engine is on
 * the classpath; other runtimes pick it up through standard
 * {@code @Provider} scanning. The {@code HttpProblem} dependency is
 * provided scope: applications without it never load this class.</p>
 */
@Provider
@Produces("application/problem+json")
public class JqProblemWriter implements MessageBodyWriter<HttpProblem> {

    /** Wire type for RFC 9457 problem details (no JAX-RS constant exists). */
    static final String PROBLEM_JSON = "application/problem+json";

    private final JqMapper mapper;

    /** Creates a writer with a default JqMapper. */
    public JqProblemWriter() {
        this(JqMapper.create());
    }

    /**
     * Creates a writer with the given JqMapper (for the shared CDI mapper).
     *
     * @param mapper the JqMapper for custom parameter conversion
     */
    @Inject
    public JqProblemWriter(JqMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType,
                               Annotation[] annotations, MediaType mediaType) {
        return HttpProblem.class.isAssignableFrom(type);
    }

    @Override
    public void writeTo(HttpProblem problem, Class<?> type, Type genericType,
                        Annotation[] annotations, MediaType mediaType,
                        MultivaluedMap<String, Object> httpHeaders,
                        OutputStream entityStream) throws IOException, WebApplicationException {
        if (problem == null) {
            return;
        }
        BytOutput out = JqValues.acquireByteBuffer();
        toJqValue(problem).appendToBytes(out);
        JqValues.writeByteBuffer(out, entityStream);
    }

    /** Engine-identical conversion: order, null-skipping, and URI forms. */
    JqValue toJqValue(HttpProblem problem) {
        var builder = JqObject.builder(6);
        if (problem.getType() != null) {
            builder.put("type", JqString.of(problem.getType().toASCIIString()));
        }
        builder.put("status", intValue(problem.getStatusCode()));
        if (problem.getTitle() != null) {
            builder.put("title", JqString.of(problem.getTitle()));
        }
        if (problem.getDetail() != null) {
            builder.put("detail", JqString.of(problem.getDetail()));
        }
        if (problem.getInstance() != null) {
            builder.put("instance", JqString.of(InstanceUtils.instanceToPath(problem.getInstance())));
        }
        for (var entry : problem.getParameters().entrySet()) {
            builder.put(entry.getKey(), TypeConverter.toJqValue(entry.getValue(), mapper));
        }
        return builder.build();
    }

    private static JqValue intValue(int statusCode) {
        return io.hyperfoil.tools.jjq.value.JqNumber.of((long) statusCode);
    }
}

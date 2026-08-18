package io.hyperfoil.tools.jjq.jakarta;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.Provider;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

/**
 * JAX-RS {@link ParamConverterProvider} that converts JSON strings in query
 * parameters, path parameters, headers, and form parameters to {@link JqValue}.
 *
 * <p>Enables using {@code JqValue} directly in JAX-RS resource method parameters:</p>
 * <pre>{@code
 * @GET
 * @Path("/query")
 * public Response query(@QueryParam("filter") JqValue filter) {
 *     // filter is parsed from the JSON query parameter
 * }
 *
 * @GET
 * @Path("/data/{json}")
 * public Response data(@PathParam("json") JqValue data) {
 *     // data is parsed from the JSON path parameter
 * }
 * }</pre>
 *
 * <p>Annotated with {@code @Provider} for automatic discovery by JAX-RS
 * implementations (Quarkus RESTEasy, Jersey, etc.).</p>
 */
@Provider
public class JqValueParamConverterProvider implements ParamConverterProvider {

    /** Creates a new JqValueParamConverterProvider. */
    public JqValueParamConverterProvider() {}

    @SuppressWarnings("unchecked")
    @Override
    public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType,
                                               Annotation[] annotations) {
        if (JqValue.class.isAssignableFrom(rawType)) {
            return (ParamConverter<T>) new JqValueParamConverter();
        }
        return null;
    }

    private static final class JqValueParamConverter implements ParamConverter<JqValue> {

        @Override
        public JqValue fromString(String value) {
            if (value == null || value.isEmpty()) {
                return null;
            }
            return JqValues.parse(value);
        }

        @Override
        public String toString(JqValue value) {
            if (value == null) {
                return null;
            }
            return value.toJsonString();
        }
    }
}

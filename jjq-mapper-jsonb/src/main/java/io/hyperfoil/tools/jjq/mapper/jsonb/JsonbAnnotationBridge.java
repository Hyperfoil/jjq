package io.hyperfoil.tools.jjq.mapper.jsonb;

import io.hyperfoil.tools.jjq.mapper.JqInclude;
import io.hyperfoil.tools.jjq.mapper.JqNaming;
import io.hyperfoil.tools.jjq.mapper.spi.AnnotationBridge;
import jakarta.json.bind.annotation.JsonbNillable;
import jakarta.json.bind.annotation.JsonbProperty;
import jakarta.json.bind.annotation.JsonbTransient;

import java.lang.reflect.AnnotatedElement;

/**
 * Bridges Jakarta JSON-B annotations into jjq-mapper's annotation system.
 *
 * <p>Supports:</p>
 * <ul>
 *   <li>{@code @JsonbProperty("name")} → field name override</li>
 *   <li>{@code @JsonbTransient} → field exclusion</li>
 *   <li>{@code @JsonbNillable} → include null fields (maps to ALWAYS inclusion)</li>
 * </ul>
 *
 * <p>Register via ServiceLoader (automatic when on classpath) or explicitly:</p>
 * <pre>{@code
 * JqMapper mapper = JqMapper.builder()
 *     .bridge(new JsonbAnnotationBridge())
 *     .build();
 * }</pre>
 *
 * @see AnnotationBridge
 */
public class JsonbAnnotationBridge implements AnnotationBridge {

    @Override
    public String resolveFieldName(AnnotatedElement element) {
        AnnotatedElement effective = resolveToField(element);
        JsonbProperty prop = effective.getAnnotation(JsonbProperty.class);
        if (prop != null && !prop.value().isEmpty()) {
            return prop.value();
        }
        return null;
    }

    @Override
    public boolean isIgnored(AnnotatedElement element) {
        AnnotatedElement effective = resolveToField(element);
        return effective.isAnnotationPresent(JsonbTransient.class);
    }

    @Override
    public JqInclude.Include resolveInclusion(AnnotatedElement element) {
        AnnotatedElement effective = (element instanceof Class<?>) ? element : resolveToField(element);
        if (effective.isAnnotationPresent(JsonbNillable.class)) {
            JsonbNillable nillable = effective.getAnnotation(JsonbNillable.class);
            return nillable.value() ? JqInclude.Include.ALWAYS : JqInclude.Include.NON_NULL;
        }
        return null;
    }

    /**
     * For RecordComponents, JSON-B annotations land on the underlying field.
     */
    private static AnnotatedElement resolveToField(AnnotatedElement element) {
        if (element instanceof java.lang.reflect.RecordComponent rc) {
            try {
                return rc.getDeclaringRecord().getDeclaredField(rc.getName());
            } catch (NoSuchFieldException e) {
                return element;
            }
        }
        return element;
    }

    @Override
    public JqNaming.Strategy resolveNaming(Class<?> type) {
        // JSON-B doesn't have a naming strategy annotation
        return null;
    }
}

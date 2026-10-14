package io.hyperfoil.tools.jjq.mapper.jsonb;

import io.hyperfoil.tools.jjq.mapper.JqInclude;
import io.hyperfoil.tools.jjq.mapper.JqNaming;
import io.hyperfoil.tools.jjq.mapper.spi.AnnotationBridge;
import jakarta.json.bind.annotation.JsonbCreator;
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

    /** Creates a new JsonbAnnotationBridge. */
    public JsonbAnnotationBridge() {}

    @Override
    public String resolveFieldName(AnnotatedElement element) {
        // Field first, then conventional accessors (JSON-B reads annotations
        // from fields and methods alike — field wins).
        for (AnnotatedElement candidate : selfThenAccessors(element)) {
            JsonbProperty prop = candidate.getAnnotation(JsonbProperty.class);
            if (prop != null && !prop.value().isEmpty()) {
                return prop.value();
            }
        }
        return null;
    }

    @Override
    public boolean isIgnored(AnnotatedElement element) {
        for (AnnotatedElement candidate : selfThenAccessors(element)) {
            if (candidate.isAnnotationPresent(JsonbTransient.class)) return true;
        }
        return false;
    }

    @Override
    public JqInclude.Include resolveInclusion(AnnotatedElement element) {
        if (element instanceof Class<?>) {
            if (element.isAnnotationPresent(JsonbNillable.class)) {
                JsonbNillable nillable = element.getAnnotation(JsonbNillable.class);
                return nillable.value() ? JqInclude.Include.ALWAYS : JqInclude.Include.NON_NULL;
            }
            return null;
        }
        for (AnnotatedElement candidate : selfThenAccessors(element)) {
            if (candidate.isAnnotationPresent(JsonbNillable.class)) {
                JsonbNillable nillable = candidate.getAnnotation(JsonbNillable.class);
                return nillable.value() ? JqInclude.Include.ALWAYS : JqInclude.Include.NON_NULL;
            }
        }
        return null;
    }

    /**
     * Annotation-bearing elements for a property: the field itself, then
     * conventional accessors (getter before setter).
     */
    private static java.util.List<AnnotatedElement> selfThenAccessors(AnnotatedElement element) {
        var all = new java.util.ArrayList<AnnotatedElement>(4);
        all.add(resolveToField(element));
        Class<?> declaring = getDeclaringClass(element);
        String fieldName = getFieldName(element);
        if (declaring != null && fieldName != null && !fieldName.isEmpty()) {
            String capitalized = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
            if (!declaring.isRecord()) {
                try {
                    all.add(declaring.getMethod("get" + capitalized));
                } catch (NoSuchMethodException ignored) {
                    // No getter — fall through to the setter scan.
                }
            } else {
                try {
                    all.add(declaring.getMethod(fieldName));
                } catch (NoSuchMethodException ignored) {
                    // No explicit accessor — field annotations rule alone.
                }
                return all;
            }
            for (java.lang.reflect.Method m : declaring.getMethods()) {
                boolean getter = (m.getName().equals("get" + capitalized)
                        || m.getName().equals("is" + capitalized)) && m.getParameterCount() == 0;
                boolean setter = m.getName().equals("set" + capitalized) && m.getParameterCount() == 1;
                if ((getter || setter) && !all.contains(m)) {
                    all.add(m);
                    if (setter) break;
                }
            }
        }
        return all;
    }

    private static Class<?> getDeclaringClass(AnnotatedElement element) {
        if (element instanceof java.lang.reflect.RecordComponent rc) return rc.getDeclaringRecord();
        if (element instanceof java.lang.reflect.Field f) return f.getDeclaringClass();
        return null;
    }

    private static String getFieldName(AnnotatedElement element) {
        if (element instanceof java.lang.reflect.RecordComponent rc) return rc.getName();
        if (element instanceof java.lang.reflect.Field f) return f.getName();
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
    public java.lang.reflect.Executable resolveJsonCreator(Class<?> type) {
        for (java.lang.reflect.Method m : type.getMethods()) {
            if (m.isAnnotationPresent(JsonbCreator.class)
                    && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                return m;
            }
        }
        for (java.lang.reflect.Constructor<?> c : type.getDeclaredConstructors()) {
            if (c.isAnnotationPresent(JsonbCreator.class)) {
                return c;
            }
        }
        return null;
    }

    @Override
    public JqNaming.Strategy resolveNaming(Class<?> type) {
        // JSON-B doesn't have a naming strategy annotation
        return null;
    }
}

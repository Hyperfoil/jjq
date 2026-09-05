package io.hyperfoil.tools.jjq.mapper.jackson;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.hyperfoil.tools.jjq.mapper.JqInclude;
import io.hyperfoil.tools.jjq.mapper.JqNaming;
import io.hyperfoil.tools.jjq.mapper.spi.AnnotationBridge;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Set;

/**
 * Bridges Jackson 2 annotations into jjq-mapper's annotation system.
 *
 * <p>Supports:</p>
 * <ul>
 *   <li>{@code @JsonProperty("name")} → field name override</li>
 *   <li>{@code @JsonIgnore} → field exclusion</li>
 *   <li>{@code @JsonIgnoreProperties({"a","b"})} → class-level field exclusion</li>
 *   <li>{@code @JsonInclude(NON_NULL)} → inclusion strategy</li>
 * </ul>
 *
 * <p>Register via ServiceLoader (automatic when on classpath) or explicitly:</p>
 * <pre>{@code
 * JqMapper mapper = JqMapper.builder()
 *     .bridge(new JacksonAnnotationBridge())
 *     .build();
 * }</pre>
 *
 * @see AnnotationBridge
 */
public class JacksonAnnotationBridge implements AnnotationBridge {

    /** Cached set of ignored property names from @JsonIgnoreProperties at class level. */
    private volatile Set<String> ignoredProperties;
    private volatile Class<?> ignoredPropertiesClass;

    @Override
    public String resolveFieldName(AnnotatedElement element) {
        // For RecordComponents, Jackson annotations land on the field, not the component
        AnnotatedElement effective = resolveToField(element);
        JsonProperty prop = effective.getAnnotation(JsonProperty.class);
        if (prop != null && !prop.value().isEmpty() && !JsonProperty.USE_DEFAULT_NAME.equals(prop.value())) {
            return prop.value();
        }
        return null;
    }

    @Override
    public boolean isIgnored(AnnotatedElement element) {
        AnnotatedElement effective = resolveToField(element);

        // Check @JsonIgnore on the field itself
        JsonIgnore ignore = effective.getAnnotation(JsonIgnore.class);
        if (ignore != null && ignore.value()) return true;

        // Check @JsonIgnoreProperties at the declaring class level
        String fieldName = getFieldName(element);
        if (fieldName != null) {
            Class<?> declaringClass = getDeclaringClass(element);
            if (declaringClass != null) {
                Set<String> ignored = getIgnoredProperties(declaringClass);
                if (ignored.contains(fieldName)) return true;
            }
        }

        return false;
    }

    @Override
    public JqInclude.Include resolveInclusion(AnnotatedElement element) {
        AnnotatedElement effective = (element instanceof Class<?>) ? element : resolveToField(element);
        JsonInclude include = effective.getAnnotation(JsonInclude.class);
        if (include == null) return null;
        return switch (include.value()) {
            case ALWAYS -> JqInclude.Include.ALWAYS;
            case NON_NULL -> JqInclude.Include.NON_NULL;
            case NON_EMPTY -> JqInclude.Include.NON_EMPTY;
            case NON_DEFAULT -> JqInclude.Include.NON_DEFAULT;
            default -> null; // NON_ABSENT, CUSTOM, USE_DEFAULTS — not mapped
        };
    }

    @Override
    public JqNaming.Strategy resolveNaming(Class<?> type) {
        // Jackson's @JsonNaming uses a PropertyNamingStrategy class, not an enum.
        // Mapping it would require importing jackson-databind. For now, return null.
        return null;
    }

    private Set<String> getIgnoredProperties(Class<?> type) {
        // Simple cache — thread-safe via volatile
        if (type == ignoredPropertiesClass && ignoredProperties != null) {
            return ignoredProperties;
        }
        JsonIgnoreProperties props = type.getAnnotation(JsonIgnoreProperties.class);
        Set<String> result = (props != null && props.value().length > 0)
                ? Set.of(props.value()) : Set.of();
        ignoredProperties = result;
        ignoredPropertiesClass = type;
        return result;
    }

    /**
     * For RecordComponents, Jackson annotations (which target FIELD, not RECORD_COMPONENT)
     * land on the underlying field. Resolve the element to the field for annotation reading.
     */
    private static AnnotatedElement resolveToField(AnnotatedElement element) {
        if (element instanceof RecordComponent rc) {
            try {
                return rc.getDeclaringRecord().getDeclaredField(rc.getName());
            } catch (NoSuchFieldException e) {
                return element; // fallback
            }
        }
        return element;
    }

    private static String getFieldName(AnnotatedElement element) {
        if (element instanceof RecordComponent rc) return rc.getName();
        if (element instanceof Field f) return f.getName();
        return null;
    }

    private static Class<?> getDeclaringClass(AnnotatedElement element) {
        if (element instanceof RecordComponent rc) return rc.getDeclaringRecord();
        if (element instanceof Field f) return f.getDeclaringClass();
        return null;
    }
}

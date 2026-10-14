package io.hyperfoil.tools.jjq.mapper.jackson;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import io.hyperfoil.tools.jjq.mapper.JqInclude;
import io.hyperfoil.tools.jjq.mapper.JqNaming;
import io.hyperfoil.tools.jjq.mapper.JqOrder;
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
 *   <li>{@code @JsonAnySetter} (method form) → unknown-key capture</li>
 *   <li>{@code @JsonAnyGetter} (method form) → extra-field emission</li>
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

    /** Creates a new JacksonAnnotationBridge. */
    public JacksonAnnotationBridge() {}

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
    public java.util.List<String> resolveAliases(AnnotatedElement element) {
        AnnotatedElement effective = resolveToField(element);
        JsonAlias alias = effective.getAnnotation(JsonAlias.class);
        if (alias == null || alias.value().length == 0) return java.util.List.of();
        return java.util.List.of(alias.value());
    }

    @Override
    public JqOrder.Spec resolveOrder(Class<?> type) {
        // @JsonPropertyOrder lives in databind: match by simple name and
        // read members reflectively (covers Jackson 2 and 3).
        for (java.lang.annotation.Annotation ann : type.getAnnotations()) {
            if (!ann.annotationType().getSimpleName().equals("JsonPropertyOrder")) continue;
            java.util.List<String> explicit = new java.util.ArrayList<>();
            boolean alphabetic = false;
            try {
                Object value = ann.annotationType().getMethod("value").invoke(ann);
                if (value instanceof String[] names) explicit.addAll(java.util.List.of(names));
                Object alpha = ann.annotationType().getMethod("alphabetic").invoke(ann);
                if (alpha instanceof Boolean b) alphabetic = b;
            } catch (NoSuchMethodException e) {
                continue;
            } catch (ReflectiveOperationException e) {
                throw new io.hyperfoil.tools.jjq.mapper.JqMapperException(
                        "Cannot read @JsonPropertyOrder on " + type.getName(), e);
            }
            return new JqOrder.Spec(explicit, alphabetic);
        }
        return null;
    }

    @Override
    public boolean isRequired(AnnotatedElement element) {
        AnnotatedElement effective = resolveToField(element);
        JsonProperty prop = effective.getAnnotation(JsonProperty.class);
        return prop != null && prop.required();
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
        // Jackson's @JsonNaming lives in databind (naming strategy classes
        // too), which this module deliberately does not depend on — match by
        // simple name and map the well-known strategies (covers Jackson 2+3).
        // Anything custom (or absent) returns null: naming stays identity.
        for (java.lang.annotation.Annotation ann : type.getAnnotations()) {
            if (!ann.annotationType().getSimpleName().equals("JsonNaming")) continue;
            String strategyClass;
            try {
                Object value = ann.annotationType().getMethod("value").invoke(ann);
                if (!(value instanceof Class<?> c)) return null;
                strategyClass = c.getSimpleName();
            } catch (ReflectiveOperationException e) {
                return null;
            }
            return switch (strategyClass) {
                case "SnakeCaseStrategy" -> JqNaming.Strategy.SNAKE_CASE;
                case "UpperSnakeCaseStrategy" -> JqNaming.Strategy.UPPER_SNAKE_CASE;
                case "LowerCamelCaseStrategy" -> JqNaming.Strategy.LOWER_CAMEL_CASE;
                case "UpperCamelCaseStrategy" -> JqNaming.Strategy.UPPER_CAMEL_CASE;
                case "LowerCaseStrategy" -> JqNaming.Strategy.LOWER_CASE;
                case "KebabCaseStrategy" -> JqNaming.Strategy.KEBAB_CASE;
                case "LowerDotCaseStrategy" -> JqNaming.Strategy.LOWER_DOT_CASE;
                default -> null;
            };
        }
        return null;
    }

    @Override
    public boolean skipOnSerialize(AnnotatedElement element) {
        if (resolveAccess(element) == JsonProperty.Access.WRITE_ONLY) return true;
        // @JsonIgnore on the getter suppresses serialization only (Jackson split semantics)
        return hasAccessorIgnore(element, true);
    }

    @Override
    public boolean skipOnDeserialize(AnnotatedElement element) {
        if (resolveAccess(element) == JsonProperty.Access.READ_ONLY) return true;
        // @JsonIgnore on the setter suppresses deserialization only (Jackson split semantics)
        return hasAccessorIgnore(element, false);
    }

    @Override
    public boolean includeGetters(Class<?> type) {
        JsonAutoDetect autoDetect = type.getAnnotation(JsonAutoDetect.class);
        return autoDetect == null
                || autoDetect.getterVisibility() != JsonAutoDetect.Visibility.NONE;
    }

    @Override
    public boolean includeIsGetters(Class<?> type) {
        JsonAutoDetect autoDetect = type.getAnnotation(JsonAutoDetect.class);
        return autoDetect == null
                || autoDetect.isGetterVisibility() != JsonAutoDetect.Visibility.NONE;
    }

    /**
     * Check a conventional accessor (getX/isX for reads, setX for writes) for
     * an explicit {@code @JsonIgnore}. Field-level handling is unchanged.
     */
    private static boolean hasAccessorIgnore(AnnotatedElement element, boolean getter) {
        Class<?> declaring = getDeclaringClass(element);
        String fieldName = getFieldName(element);
        if (declaring == null || fieldName == null) return false;
        String capitalized = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        for (java.lang.reflect.Method m : declaring.getMethods()) {
            boolean match;
            if (getter) {
                match = (m.getName().equals("get" + capitalized) || m.getName().equals("is" + capitalized))
                        && m.getParameterCount() == 0;
            } else {
                match = m.getName().equals("set" + capitalized) && m.getParameterCount() == 1;
            }
            if (match) {
                JsonIgnore ignore = m.getAnnotation(JsonIgnore.class);
                if (ignore != null) return ignore.value();
            }
        }
        return false;
    }

    /** Read Jackson's @JsonProperty(access) for a field (RECORD_COMPONENT resolves to its field). */
    private static JsonProperty.Access resolveAccess(AnnotatedElement element) {
        AnnotatedElement effective = resolveToField(element);
        JsonProperty prop = effective.getAnnotation(JsonProperty.class);
        if (prop == null) return JsonProperty.Access.AUTO;
        return prop.access();
    }

    @Override
    public io.hyperfoil.tools.jjq.mapper.PolyConfig resolvePolymorphism(Class<?> type) {
        // Supported subset only: As.PROPERTY with Id.NAME. Anything else
        // (WRAPPER_OBJECT, CLASS ids, missing subtypes) returns null and
        // keeps today's behavior.
        com.fasterxml.jackson.annotation.JsonTypeInfo info =
                type.getAnnotation(com.fasterxml.jackson.annotation.JsonTypeInfo.class);
        if (info == null) return null;
        if (info.use() != com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME) return null;
        if (info.include() != com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY) return null;
        String property = info.property();
        if (property == null || property.isEmpty()) property = "type";
        com.fasterxml.jackson.annotation.JsonSubTypes subs =
                type.getAnnotation(com.fasterxml.jackson.annotation.JsonSubTypes.class);
        if (subs == null || subs.value().length == 0) return null;
        var wireToSub = new java.util.LinkedHashMap<String, Class<?>>();
        for (var sub : subs.value()) {
            String wire = sub.name();
            if (wire == null || wire.isEmpty()) {
                com.fasterxml.jackson.annotation.JsonTypeName typeName =
                        sub.value().getAnnotation(com.fasterxml.jackson.annotation.JsonTypeName.class);
                wire = (typeName != null && !typeName.value().isEmpty())
                        ? typeName.value() : sub.value().getSimpleName();
            }
            if (wireToSub.putIfAbsent(wire, sub.value()) != null) {
                throw new io.hyperfoil.tools.jjq.mapper.JqMapperException("@JsonSubTypes on "
                        + type.getName() + ": duplicate type name '" + wire + "'");
            }
        }
        return new io.hyperfoil.tools.jjq.mapper.PolyConfig(property, wireToSub);
    }

    @Override
    public io.hyperfoil.tools.jjq.mapper.spi.AnnotationBridge.BuilderDecl resolveBuilder(Class<?> type) {
        // @JsonDeserialize(builder=...) selects the builder class; the
        // @JsonPOJOBuilder on that class names the terminal method and the
        // property prefix (Jackson defaults: build/with). Both live in
        // jackson-databind, which this module deliberately does not depend
        // on — read reflectively so the bridge stays annotations-only.
        Class<?> builderClass = reflectJsonDeserializeBuilder(type);
        if (builderClass == null) return null;
        String build = "build";
        String prefix = "with";
        for (java.lang.annotation.Annotation ann : builderClass.getAnnotations()) {
            if (ann.annotationType().getSimpleName().equals("JsonPOJOBuilder")) {
                String buildName = invokeStringMember(ann, "buildMethodName");
                if (buildName != null && !buildName.isEmpty() && !"<none>".equals(buildName)) {
                    build = buildName;
                }
                String withPrefix = invokeStringMember(ann, "withPrefix");
                if (withPrefix != null && !withPrefix.isEmpty() && !"<none>".equals(withPrefix)) {
                    prefix = withPrefix;
                }
            }
        }
        return new io.hyperfoil.tools.jjq.mapper.spi.AnnotationBridge.BuilderDecl(
                builderClass, "", prefix, build);
    }

    /** Read {@code @JsonDeserialize(builder)} reflectively (databind optional). */
    private static Class<?> reflectJsonDeserializeBuilder(Class<?> type) {
        // Simple-name match covers Jackson 2 (com.fasterxml) and 3 (tools.jackson).
        for (java.lang.annotation.Annotation ann : type.getAnnotations()) {
            if (!ann.annotationType().getSimpleName().equals("JsonDeserialize")) continue;
            java.lang.reflect.Method builderMethod;
            try {
                builderMethod = ann.annotationType().getMethod("builder");
            } catch (NoSuchMethodException e) {
                continue;
            }
            try {
                Object value = builderMethod.invoke(ann);
                if (value instanceof Class<?> c && c != Void.class) return c;
                return null;
            } catch (ReflectiveOperationException e) {
                throw new io.hyperfoil.tools.jjq.mapper.JqMapperException(
                        "Cannot read @JsonDeserialize(builder) on " + type.getName(), e);
            }
        }
        return null;
    }

    /** Invoke a no-arg String member on a reflectively-read annotation. */
    private static String invokeStringMember(java.lang.annotation.Annotation ann, String member) {
        try {
            Object value = ann.annotationType().getMethod(member).invoke(ann);
            return value instanceof String s ? s : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    @Override
    public java.lang.reflect.Method resolveAnySetter(Class<?> type) {
        for (java.lang.reflect.Method m : type.getMethods()) {
            if (m.isAnnotationPresent(JsonAnySetter.class)
                    && m.getParameterCount() == 2
                    && m.getParameterTypes()[0] == String.class) {
                return m;
            }
        }
        return null;
    }

    @Override
    public java.lang.reflect.Method resolveAnyGetter(Class<?> type) {
        for (java.lang.reflect.Method m : type.getMethods()) {
            if (m.isAnnotationPresent(JsonAnyGetter.class) && m.getParameterCount() == 0) {
                return m;
            }
        }
        return null;
    }

    @Override
    public java.lang.reflect.Method resolveJsonValueAccessor(Class<?> type) {
        for (java.lang.reflect.Method m : type.getMethods()) {
            JsonValue ann = m.getAnnotation(JsonValue.class);
            if (ann != null && ann.value() && m.getParameterCount() == 0
                    && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                return m;
            }
        }
        return null;
    }

    @Override
    public java.lang.reflect.Executable resolveJsonCreator(Class<?> type) {
        // Jackson DEFAULT parity: single-arg (delegating) or multi-arg
        // (properties); explicit PROPERTIES the same; explicit DELEGATING
        // needs exactly one argument. Property-based creators bind via
        // @JsonProperty/@JqName parameter names (CreatorSpec).
        for (java.lang.reflect.Method m : type.getMethods()) {
            JsonCreator ann = m.getAnnotation(JsonCreator.class);
            if (ann == null || !java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            if (ann.mode() == JsonCreator.Mode.PROPERTIES && m.getParameterCount() >= 1) return m;
            if (ann.mode() == JsonCreator.Mode.DELEGATING && m.getParameterCount() == 1) return m;
            if (ann.mode() == JsonCreator.Mode.DEFAULT && m.getParameterCount() >= 1) return m;
        }
        for (java.lang.reflect.Constructor<?> c : type.getDeclaredConstructors()) {
            JsonCreator ann = c.getAnnotation(JsonCreator.class);
            if (ann == null) continue;
            if (ann.mode() == JsonCreator.Mode.PROPERTIES && c.getParameterCount() >= 1) return c;
            if (ann.mode() == JsonCreator.Mode.DELEGATING && c.getParameterCount() == 1) return c;
            if (ann.mode() == JsonCreator.Mode.DEFAULT && c.getParameterCount() >= 1) return c;
        }
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

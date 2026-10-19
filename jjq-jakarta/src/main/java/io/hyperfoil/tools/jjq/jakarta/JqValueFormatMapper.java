package io.hyperfoil.tools.jjq.jakarta;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.format.FormatMapper;

/**
 * Hibernate {@link FormatMapper} that uses jjq's built-in parser and serializer
 * for JSON column serialization/deserialization.
 *
 * <p>Replaces Jackson-based FormatMappers, eliminating Jackson from the persistence
 * path. Uses {@link JqValues#parse(String)} for reading and
 * {@link JqValue#toJsonString()} for writing.</p>
 *
 * <p>This class has no framework-specific annotations. Register it with your framework:</p>
 * <ul>
 *   <li><b>Quarkus:</b> Subclass with {@code @ApplicationScoped @PersistenceUnitExtension @JsonFormat}</li>
 *   <li><b>Spring:</b> Register as a {@code @Bean}</li>
 *   <li><b>Plain Hibernate:</b> Set via {@code hibernate.type.json_format_mapper} property</li>
 * </ul>
 *
 * <p><b>Quarkus example:</b></p>
 * <pre>{@code
 * @ApplicationScoped
 * @PersistenceUnitExtension
 * @JsonFormat
 * public class MyJsonFormatMapper extends JqValueFormatMapper {}
 * }</pre>
 */
public class JqValueFormatMapper implements FormatMapper {

    /** Creates a new JqValueFormatMapper. */
    public JqValueFormatMapper() {}

    @Override
    public <T> T fromString(CharSequence charSequence, JavaType<T> javaType,
                             WrapperOptions wrapperOptions) {
        if (charSequence == null) {
            return null;
        }
        Class<T> target = javaType.getJavaTypeClass();
        // A JSON column read as String yields its raw JSON text (native
        // queries select jsonb aggregates as String and re-parse them).
        if (target == String.class) {
            return target.cast(charSequence.toString());
        }
        JqValue parsed = JqValues.parse(charSequence.toString());
        if (target.isInstance(parsed)) {
            return target.cast(parsed);
        }
        // Best effort for other targets: plain Java values (String, Long,
        // Double, Boolean, List, Map) with a checked cast.
        Object plain = parsed.toJavaObject();
        if (target.isInstance(plain)) {
            return target.cast(plain);
        }
        throw new org.hibernate.HibernateException(
                "JqValueFormatMapper cannot bind JSON to " + target.getName());
    }

    @Override
    public <T> String toString(T value, JavaType<T> javaType,
                                WrapperOptions wrapperOptions) {
        if (value == null) {
            return null;
        }
        // Mirror fromString: a String column carries raw JSON text, JqValue
        // serializes canonically; anything else cannot be written.
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof JqValue json) {
            return json.toJsonString();
        }
        throw new org.hibernate.HibernateException(
                "JqValueFormatMapper cannot write " + value.getClass().getName() + " as JSON");
    }
}

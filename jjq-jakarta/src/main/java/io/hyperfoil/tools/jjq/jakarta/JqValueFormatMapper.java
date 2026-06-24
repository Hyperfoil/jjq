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
        return javaType.getJavaTypeClass().cast(JqValues.parse(charSequence.toString()));
    }

    @Override
    public <T> String toString(T value, JavaType<T> javaType,
                                WrapperOptions wrapperOptions) {
        if (value == null) {
            return null;
        }
        return ((JqValue) value).toJsonString();
    }
}

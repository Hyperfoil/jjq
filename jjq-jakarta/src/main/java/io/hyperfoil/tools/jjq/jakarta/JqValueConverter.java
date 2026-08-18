package io.hyperfoil.tools.jjq.jakarta;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA {@link AttributeConverter} that converts {@link JqValue} to and from
 * JSON strings for database column storage.
 *
 * <p>This provides a portable JPA alternative to the Hibernate-specific
 * {@link JqValueJdbcType}/{@link JqValueJavaType} pair. It works with any
 * JPA provider (Hibernate, EclipseLink, OpenJPA, etc.) and stores JSON as
 * a VARCHAR/TEXT column.</p>
 *
 * <p>Usage on entity fields:</p>
 * <pre>{@code
 * @Entity
 * public class MyEntity {
 *     @Convert(converter = JqValueConverter.class)
 *     @Column(columnDefinition = "TEXT")
 *     private JqValue metadata;
 * }
 * }</pre>
 *
 * <p>If your database supports JSONB columns and you want binary storage
 * with zero-copy I/O, use the Hibernate-specific {@link JqValueJdbcType}
 * and {@link JqValueJavaType} annotations instead.</p>
 *
 * <p>The {@code @Converter(autoApply = true)} annotation makes this converter
 * apply automatically to all {@code JqValue} fields without requiring
 * {@code @Convert} on each field. To disable auto-apply, subclass and
 * override the annotation.</p>
 *
 * @see JqValueJdbcType
 * @see JqValueJavaType
 */
@Converter(autoApply = true)
public class JqValueConverter implements AttributeConverter<JqValue, String> {

    /** Creates a new JqValueConverter. */
    public JqValueConverter() {}

    @Override
    public String convertToDatabaseColumn(JqValue value) {
        if (value == null) {
            return null;
        }
        return value.toJsonString();
    }

    @Override
    public JqValue convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return null;
        }
        return JqValues.parse(dbData);
    }
}

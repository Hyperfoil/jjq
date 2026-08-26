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
 * <p>Usage on entity fields — requires explicit {@code @Convert} annotation:</p>
 * <pre>{@code
 * @Entity
 * public class MyEntity {
 *     @Convert(converter = JqValueConverter.class)
 *     @Column(columnDefinition = "TEXT")
 *     private JqValue metadata;
 * }
 * }</pre>
 *
 * <p>Auto-apply is disabled ({@code autoApply = false}) to avoid conflicting
 * with the Hibernate-specific {@link JqValueJdbcType}/{@link JqValueJavaType}
 * binary mapping. If auto-apply were enabled, this String-based converter would
 * intercept all {@code JqValue} fields — including those explicitly annotated
 * for BYTEA storage — causing a {@code ClassCastException}.</p>
 *
 * <p>If you want auto-apply behavior, subclass this converter and override
 * the annotation:</p>
 * <pre>{@code
 * @Converter(autoApply = true)
 * public class AutoJqValueConverter extends JqValueConverter {}
 * }</pre>
 *
 * @see JqValueJdbcType
 * @see JqValueJavaType
 * @see JqValueColumn
 */
@Converter(autoApply = false)
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

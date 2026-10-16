package io.hyperfoil.tools.jjq.quarkus.test;

import org.hibernate.annotations.JavaType;
import org.hibernate.type.SqlTypes;

import io.hyperfoil.tools.jjq.jakarta.JqValueJavaType;
import io.hyperfoil.tools.jjq.value.JqValue;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import org.hibernate.annotations.JdbcTypeCode;

/**
 * Hibernate fixture: a {@link JqValue} field mapped as a JSON column.
 * Conversion goes through the extension-provided {@code FormatMapper} —
 * no Jackson, no user-written mapper subclass.
 */
@Entity
public class Note {

    @Id
    @GeneratedValue
    public Long id;

    @JdbcTypeCode(SqlTypes.JSON)
    @JavaType(JqValueJavaType.class)
    public JqValue payload;
}

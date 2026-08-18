package io.hyperfoil.tools.jjq.jakarta;

import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JavaType;
import org.hibernate.annotations.Mutability;
import org.hibernate.type.descriptor.java.Immutability;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Composite annotation for declaring a {@link io.hyperfoil.tools.jjq.value.JqValue}
 * column with Hibernate BYTEA persistence.
 *
 * <p>Combines the four annotations normally required on a JqValue entity field:</p>
 * <ul>
 *   <li>{@code @JdbcType(JqValueJdbcType.class)} — binary JDBC I/O</li>
 *   <li>{@code @JavaType(JqValueJavaType.class)} — type descriptor with wrap/unwrap</li>
 *   <li>{@code @Mutability(Immutability.class)} — JqValue is immutable, skip deep-copy</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * @Entity
 * public class MyEntity {
 *     @JqValueColumn
 *     @Column(columnDefinition = "BYTEA")
 *     public JqValue data;
 * }
 * }</pre>
 *
 * <p>Note: {@code @Column(columnDefinition = "BYTEA")} is still needed separately
 * because column definitions are database-specific and cannot be defaulted safely.</p>
 *
 * @see JqValueJdbcType
 * @see JqValueJavaType
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@JdbcType(JqValueJdbcType.class)
@JavaType(JqValueJavaType.class)
@Mutability(Immutability.class)
public @interface JqValueColumn {
}

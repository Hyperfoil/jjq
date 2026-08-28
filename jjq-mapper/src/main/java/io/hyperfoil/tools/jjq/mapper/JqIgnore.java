package io.hyperfoil.tools.jjq.mapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exclude a record component from serialization.
 *
 * <p>During deserialization, ignored fields receive their Java default value
 * ({@code null} for reference types, {@code 0} for numeric primitives,
 * {@code false} for booleans).</p>
 *
 * <pre>{@code
 * record User(
 *     String name,
 *     @JqIgnore String internalId
 * ) {}
 * }</pre>
 *
 * @see JqField
 * @see JqMapper
 */
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface JqIgnore {}

package io.hyperfoil.tools.jjq.mapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies a custom {@link ValueConverter} for a field.
 *
 * <p>When present, the converter is used instead of the built-in
 * {@link TypeConverter} for both deserialization ({@code fromJqValue})
 * and serialization ({@code toJqValue}) of the annotated field.</p>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @JqMapped
 * record Event(
 *     String name,
 *     @JqConverter(InstantConverter.class) Instant timestamp
 * ) {}
 * }</pre>
 *
 * <p>The converter class must have a public no-arg constructor. A single
 * instance is created per field and reused across all mapping calls.</p>
 *
 * @see ValueConverter
 * @see JqMapped
 */
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface JqConverter {

    /**
     * The converter class to use for this field.
     */
    Class<? extends ValueConverter<?>> value();
}

package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.value.JqValue;

/**
 * Custom bidirectional converter between {@link JqValue} and a Java type.
 *
 * <p>Implement this interface and reference it via {@link JqConverter} to
 * provide custom serialization/deserialization for a field type that
 * {@link TypeConverter} does not handle natively.</p>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * public class InstantConverter implements ValueConverter<Instant> {
 *     @Override
 *     public Instant fromJqValue(JqValue value) {
 *         return value.isNull() ? null : Instant.parse(value.stringValue());
 *     }
 *
 *     @Override
 *     public JqValue toJqValue(Instant value) {
 *         return value == null ? JqNull.NULL : JqString.of(value.toString());
 *     }
 * }
 * }</pre>
 *
 * <p>Converter classes must have a public no-arg constructor. Instances are
 * created once per field and cached for the lifetime of the {@link ClassMapping}.</p>
 *
 * @param <T> the Java type this converter handles
 * @see JqConverter
 */
public interface ValueConverter<T> {

    /**
     * Convert a JqValue to the target Java type.
     *
     * @param value the JqValue to convert (may be {@link io.hyperfoil.tools.jjq.value.JqNull})
     * @return the converted Java value, or null
     */
    T fromJqValue(JqValue value);

    /**
     * Convert a Java value to a JqValue.
     *
     * @param value the Java value to convert (may be null)
     * @return the JqValue representation
     */
    JqValue toJqValue(T value);
}

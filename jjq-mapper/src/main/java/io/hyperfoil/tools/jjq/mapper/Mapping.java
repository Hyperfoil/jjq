package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.value.BytOutput;
import io.hyperfoil.tools.jjq.value.JqValue;

/**
 * Common interface for class mapping strategies. Implemented by both
 * {@link ClassMapping} (reflection-based, runtime) and {@link GeneratedMapping}
 * (compile-time generated, zero-reflection).
 *
 * <p>{@link JqMapper} caches one {@code Mapping} per class. Generated mappings
 * take priority over reflection-based ones when available.</p>
 *
 * @param <T> the Java type being mapped
 */
sealed interface Mapping<T> permits ClassMapping, GeneratedMapping {

    /**
     * Deserialize a JqValue into an instance of type T.
     *
     * @param value  the input JqValue (typically a JqObject)
     * @param mapper the parent mapper (for recursive nested record mapping)
     * @return a new instance of T with fields populated from the JqValue
     */
    T fromJqValue(JqValue value, JqMapper mapper);

    /**
     * Serialize an instance of type T to a JqValue.
     *
     * @param instance the Java object to serialize
     * @param mapper   the parent mapper (for recursive nested record mapping)
     * @return a JqObject with fields populated from the instance
     */
    JqValue toJqValue(T instance, JqMapper mapper);

    /**
     * Serialize an instance of type T directly to JSON in a StringBuilder,
     * bypassing intermediate JqValue tree construction.
     *
     * <p>The default implementation falls back to {@code toJqValue().appendTo()}.
     * Generated mappings override this with direct field-to-JSON writing,
     * eliminating JqObject/JqString/Builder allocation.</p>
     *
     * @param instance the Java object to serialize
     * @param sb       the target StringBuilder
     * @param mapper   the parent mapper (for recursive nested record mapping)
     */
    default void appendJson(T instance, StringBuilder sb, JqMapper mapper) {
        toJqValue(instance, mapper).appendTo(sb);
    }

    /**
     * Serialize an instance of type T directly to JSON bytes in a BytOutput,
     * bypassing intermediate JqValue tree construction.
     *
     * <p>The default implementation falls back to {@code toJqValue().appendToBytes()}.
     * Generated mappings override this with direct field-to-bytes writing,
     * eliminating JqObject/JqString/Builder allocation.</p>
     *
     * @param instance the Java object to serialize
     * @param out      the target byte buffer
     * @param mapper   the parent mapper (for recursive nested record mapping)
     */
    default void appendJsonBytes(T instance, BytOutput out, JqMapper mapper) {
        toJqValue(instance, mapper).appendToBytes(out);
    }
}

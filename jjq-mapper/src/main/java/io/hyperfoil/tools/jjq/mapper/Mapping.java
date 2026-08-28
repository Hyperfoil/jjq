package io.hyperfoil.tools.jjq.mapper;

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
}

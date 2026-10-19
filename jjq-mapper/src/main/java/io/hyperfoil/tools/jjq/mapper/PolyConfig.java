package io.hyperfoil.tools.jjq.mapper;

import java.util.Map;

/**
 * Polymorphism configuration for one family: the discriminator property
 * plus the wire-value to subtype map. Produced natively from
 * {@link JqType} (subtypes from {@code getPermittedSubclasses()}) or by an
 * {@link io.hyperfoil.tools.jjq.mapper.spi.AnnotationBridge} (e.g. Jackson
 * {@code @JsonTypeInfo} with {@code PROPERTY} inclusion and {@code NAME} ids).
 *
 * @param property the JSON property carrying the discriminator value
 * @param subtypes wire value to subtype class (declaration order)
 * @param existingProperty whether the discriminator is a real mapped field
 *        (no injection, no strict-mode stripping, collision allowed)
 */
public record PolyConfig(String property, Map<String, Class<?>> subtypes, boolean existingProperty) {
    /**
     * Validate and defensively copy.
     *
     * @param property the discriminator property (must be non-blank)
     * @param subtypes wire value to subtype (must be non-empty, no nulls)
     * @param existingProperty whether the discriminator is an existing field
     */
    public PolyConfig {
        if (property == null || property.isEmpty()) {
            throw new JqMapperException("Polymorphism needs a non-empty discriminator property");
        }
        if (subtypes == null || subtypes.isEmpty()) {
            throw new JqMapperException("Polymorphism needs at least one subtype");
        }
        for (var entry : subtypes.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isEmpty() || entry.getValue() == null) {
                throw new JqMapperException("Polymorphism needs non-empty wire values and subtype classes");
            }
        }
        subtypes = Map.copyOf(subtypes);
    }

    /**
     * Injected-metadata configuration (discriminator is not a mapped field).
     *
     * @param property the discriminator property (must be non-blank)
     * @param subtypes wire value to subtype (must be non-empty, no nulls)
     */
    public PolyConfig(String property, Map<String, Class<?>> subtypes) {
        this(property, subtypes, false);
    }
}

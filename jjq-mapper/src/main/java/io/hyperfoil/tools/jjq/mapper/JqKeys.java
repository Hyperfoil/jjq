package io.hyperfoil.tools.jjq.mapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a sealed type as a polymorphic family dispatched by key presence
 * (no discriminator property on the wire).
 *
 * <p>Deserialization tries the rules in declaration order; the first whose
 * key is present — including an explicit JSON null — selects the subtype.
 * When no rule matches, the default subtype binds (when declared); without
 * a default, the input fails fast. Serialization re-dispatches on the
 * runtime type with no discriminator injected.</p>
 *
 * <pre>{@code
 * @JqKeys(value = {
 *     @JqKey(value = "windowLen", type = EDivisiveConfig.class),
 *     @JqKey(value = "windowSize", type = StdDevAnomalyConfig.class),
 *     @JqKey(value = "threshold", type = RelativeDifferenceConfig.class)
 * }, defaultSubtype = FixedThresholdConfig.class)
 * sealed interface NodeConfiguration
 *     permits FixedThresholdConfig, RelativeDifferenceConfig,
 *             StdDevAnomalyConfig, EDivisiveConfig {}
 * }</pre>
 *
 * <p>Requires a sealed type; on anything else mapping creation fails fast.
 * A type carrying both {@code @JqKeys} and {@code @JqType} fails fast.
 * Every rule type and the default must be permitted subtypes.</p>
 *
 * @see JqKey
 * @see JqType
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface JqKeys {
    /**
     * Ordered dispatch rules, first present key wins (must be non-empty).
     *
     * @return the key-to-subtype rules in priority order
     */
    JqKey[] value();

    /**
     * Fallback subtype when no rule key is present. {@code Void.class}
     * means no default: unmatched inputs fail fast.
     *
     * @return the default subtype, or {@code Void.class} for none
     */
    Class<?> defaultSubtype() default Void.class;
}

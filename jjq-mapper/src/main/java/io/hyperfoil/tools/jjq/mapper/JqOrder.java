package io.hyperfoil.tools.jjq.mapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

/**
 * Controls serialized field order for a type. Deserialization is unaffected
 * (lookups are name-based).
 *
 * <p>Listed wire names serialize first, in listed order; unlisted fields
 * follow in declaration order (or wire-name order with
 * {@code alphabetic = true}). Unknown names in the list fail fast at
 * mapping creation; duplicates collapse silently.</p>
 *
 * <pre>{@code
 * @JqMapped
 * @JqOrder({"id", "name"})
 * record User(String name, String id, int age) {}
 * // Serializes as {"id":...,"name":...,"age":...}
 * }</pre>
 *
 * <p>Jackson equivalent: {@code @JsonPropertyOrder} (resolved through
 * bridges when native is absent).</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface JqOrder {
    /**
     * Wire names serialized first, in this order.
     *
     * @return explicitly ordered wire names
     */
    String[] value() default {};

    /**
     * Sort the remaining fields by wire name.
     *
     * @return true for alphabetical tail order
     */
    boolean alphabetic() default false;

    /**
     * Explicit order plus tail rule, in one value.
     *
     * @param explicit wire names first, in order
     * @param alphabetic sort the remainder by wire name
     */
    record Spec(List<String> explicit, boolean alphabetic) {
        /**
         * Validate and defensively copy.
         *
         * @param explicit explicitly ordered wire names
         * @param alphabetic sort the remainder by wire name
         */
        public Spec {
            explicit = List.copyOf(explicit);
        }
    }
}

package io.hyperfoil.tools.jjq.mapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a sealed type as a polymorphic family dispatched by a JSON
 * discriminator property (no Jackson {@code @JsonTypeInfo} needed).
 *
 * <p>Deserialization reads the discriminator property and binds to the
 * permitted subtype registered under that wire value; serialization
 * injects the discriminator from the runtime type. Missing or unknown
 * discriminator values fail fast.</p>
 *
 * <pre>{@code
 * @JqType
 * sealed interface Shape permits Circle, Rectangle {}
 * record Circle(double radius) implements Shape {}
 * // {"type":"Circle","radius":1.5} binds Circle
 * }</pre>
 *
 * <p>Discriminator values default to the subtype simple class name and can
 * be overridden with {@link JqName} on the subtype declaration. The property
 * defaults to {@code "type"} and can be renamed here.</p>
 *
 * <p>When the discriminator is a genuine field of every variant (the
 * discriminator echoes domain state rather than existing only for the
 * mapper), set {@code existingProperty = true} and list the wire values
 * explicitly. Reads dispatch on the field value, writes emit the field
 * value with no injection, and a permitted subtype mapping the field no
 * longer fails fast. Explicit mappings allow many-to-one dispatch (several
 * wire values binding one subtype); serialization stays unambiguous because
 * the field value drives it. Declaring {@code mappings} without
 * {@code existingProperty} fails fast. Missing, null, non-string, unknown,
 * or unmapped discriminator values fail fast naming the family, property,
 * and offending value.</p>
 *
 * <pre>{@code
 * @JqType(discriminator = "method", existingProperty = true, mappings = {
 *     @JqSubtype(value = "EMAIL", type = EmailConfig.class),
 *     @JqSubtype(value = "SLACK", type = TokenSecret.class),
 *     @JqSubtype(value = "GITHUB_ISSUE", type = TokenSecret.class)
 * })
 * sealed interface Channel permits EmailConfig, TokenSecret {}
 * }</pre>
 *
 * <p>Requires a sealed type ({@code getPermittedSubclasses()} defines the
 * family); on anything else mapping creation fails fast. Bridge-provided
 * polymorphism (e.g. Jackson {@code @JsonTypeInfo}) applies only when no
 * {@code @JqType} is present — native wins.</p>
 *
 * @see JqName
 * @see JqSubtype
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface JqType {
    /**
     * The JSON property carrying the discriminator value.
     *
     * @return the discriminator property name
     */
    String discriminator() default "type";

    /**
     * Whether the discriminator is a real mapped field of the subtypes
     * (no injection, no stripping, collision allowed).
     *
     * @return true when the discriminator is an existing property
     */
    boolean existingProperty() default false;

    /**
     * Explicit wire-value to subtype mappings (declaration order).
     * Empty means derived from permits via {@link JqName}/simple name.
     * Non-empty requires {@code existingProperty = true} and every type
     * must be a permitted subtype.
     *
     * @return the explicit mappings, or empty for derived
     */
    JqSubtype[] mappings() default {};
}

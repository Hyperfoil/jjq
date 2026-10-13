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
 * defaults to {@code "type"} and can be renamed here. A permitted subtype
 * that maps a field to the discriminator property fails fast at mapping
 * creation (reflection path).</p>
 *
 * <p>Requires a sealed type ({@code getPermittedSubclasses()} defines the
 * family); on anything else mapping creation fails fast. Bridge-provided
 * polymorphism (e.g. Jackson {@code @JsonTypeInfo}) applies only when no
 * {@code @JqType} is present — native wins.</p>
 *
 * @see JqName
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
}

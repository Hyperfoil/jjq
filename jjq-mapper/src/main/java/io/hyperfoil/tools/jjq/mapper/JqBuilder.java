package io.hyperfoil.tools.jjq.mapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a type through an explicit builder instead of a no-arg constructor
 * plus setters: each property converts as usual, then invokes the matching
 * builder method, and the terminal method builds the instance.
 *
 * <pre>{@code
 * @JqBuilder(value = User.Builder.class, factory = "builder", prefix = "")
 * class User {
 *     final String name;
 *     // ...
 *     public static Builder builder() { return new Builder(); }
 *     public static class Builder {
 *         Builder name(String name) { ...; return this; }
 *         User build() { ... }
 *     }
 * }
 * }</pre>
 *
 * <p>Rules:</p>
 * <ul>
 *   <li>The builder comes from {@code factory()} (a static no-arg method on
 *       the value type, e.g. Lombok's {@code builder()}) or, when empty,
 *       from the builder class no-arg constructor.</li>
 *   <li>Each deserializable property needs a single-argument builder method
 *       named {@code prefix + CapitalizedName} (empty prefix gives Lombok
 *       style: {@code name(...)}); gaps fail fast at mapping creation.
 *       The terminal {@code build()} method takes no arguments.</li>
 *   <li>Builder methods may chain (return the builder) or return void
 *       (mutating in place); both shapes are supported.</li>
 *   <li>A type declares at most one construction strategy: a creator
 *       ({@link JqCreator}) and a builder together fail fast.</li>
 * </ul>
 *
 * <p>Builders are deserialization-only; serialization keeps using getters
 * and fields. Strict unknown-key checks, absent-key leniency, and
 * any-setters behave exactly like the setter path.</p>
 *
 * <p>Jackson equivalent: {@code @JsonDeserialize(builder = X.class)} with
 * {@code @JsonPOJOBuilder(buildMethodName, withPrefix)}, resolved through
 * bridges when native is absent.</p>
 *
 * @see JqCreator
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface JqBuilder {
    /**
     * The builder class.
     *
     * @return the builder class
     */
    Class<?> value();

    /**
     * Static no-arg factory on the value type producing a builder
     * (e.g. {@code "builder"}). Empty means the builder no-arg constructor.
     *
     * @return the factory method name, or empty for direct construction
     */
    String factory() default "";

    /**
     * Builder method prefix ({@code "with"} matches {@code withName(...)}).
     * Empty matches Lombok style ({@code name(...)}).
     *
     * @return the method prefix
     */
    String prefix() default "with";

    /**
     * Terminal no-arg build method on the builder.
     *
     * @return the build method name
     */
    String build() default "build";
}

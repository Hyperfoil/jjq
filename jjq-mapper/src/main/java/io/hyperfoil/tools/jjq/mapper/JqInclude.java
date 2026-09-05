package io.hyperfoil.tools.jjq.mapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Controls which fields are included in the serialized JSON output.
 *
 * <p>When applied at the class level, sets the default inclusion strategy for all fields.
 * When applied at the field level, overrides the class-level setting for that field.</p>
 *
 * <p>This annotation only affects serialization ({@code toJqValue}). Deserialization
 * ({@code fromJqValue}) always attempts to read all non-ignored fields.</p>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @JqMapped
 * @JqInclude(JqInclude.Include.NON_NULL)
 * record ApiResponse(String data, String error, String debugInfo) {}
 *
 * // ApiResponse("hello", null, null) serializes to: {"data":"hello"}
 * // without @JqInclude, it would be: {"data":"hello","error":null,"debugInfo":null}
 * }</pre>
 *
 * <h2>Field-level override</h2>
 * <pre>{@code
 * @JqMapped
 * @JqInclude(JqInclude.Include.NON_NULL)
 * record ApiResponse(
 *     String data,
 *     String error,
 *     @JqInclude(JqInclude.Include.ALWAYS) String status  // always included, even if null
 * ) {}
 * }</pre>
 *
 * @see JqMapped
 * @see JqIgnore
 */
@Target({ElementType.TYPE, ElementType.RECORD_COMPONENT, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface JqInclude {

    /**
     * The inclusion strategy to apply.
     */
    Include value() default Include.ALWAYS;

    /**
     * Inclusion strategies for serialization.
     */
    enum Include {
        /**
         * Include all fields regardless of value (default behavior).
         */
        ALWAYS,

        /**
         * Exclude fields with {@code null} values.
         */
        NON_NULL,

        /**
         * Exclude fields with {@code null} values, empty strings ({@code ""}),
         * and empty collections.
         */
        NON_EMPTY,

        /**
         * Exclude fields with Java default values: {@code null} for reference types,
         * {@code 0} for numeric primitives, {@code false} for booleans, {@code '\0'} for char.
         */
        NON_DEFAULT
    }
}

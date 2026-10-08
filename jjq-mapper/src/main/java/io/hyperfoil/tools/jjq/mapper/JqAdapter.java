package io.hyperfoil.tools.jjq.mapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Delegates a field's conversion to static policy methods (or an instance
 * no-arg method for serialization) on the field's declared type — a
 * whole converter without the converter class.
 *
 * <p>Java annotations cannot hold method references, so a 40-line
 * {@code ValueConverter} whose only policy is "delegate both directions"
 * collapses to the two methods, referenced by name:</p>
 *
 * <pre>{@code
 * @JqMapped
 * record SpawnConfig(@JqAdapter(from = "fromWire", to = "wireName") ClaudeAccountType type) {
 *     public enum ClaudeAccountType {
 *         API_KEY, OAUTH;
 *
 *         public static ClaudeAccountType fromWire(String wire) { ... }
 *         public String wireName() { ... }
 *     }
 * }
 * }</pre>
 *
 * <h2>Method shapes</h2>
 * <ul>
 *   <li>{@code from}: {@code static T method(JqValue)} (preferred) or
 *       {@code static T method(String)}. The {@code String} form receives
 *       scalar text ({@code stringValue()} for strings, {@code asText()}
 *       coercion for other scalars); containers fail fast with a shape
 *       mismatch.</li>
 *   <li>{@code to}: instance {@code JqValue/String method()} (preferred) or
 *       {@code static JqValue/String method(T)}. {@code String} results become
 *       {@code JqString.of(...)}.</li>
 * </ul>
 * Overloads across shapes resolve deterministically ({@code JqValue} before
 * {@code String}, instance before static).
 *
 * <h2>Null contract</h2>
 * <p>Null/{@code JqNull} input yields null without calling {@code from}; a
 * null field yields {@code JqNull} without calling {@code to}. (For
 * primitive-typed fields null inputs fail like converter-backed primitives
 * do — adapters are meant for reference types.)</p>
 *
 * <h2>Boundaries</h2>
 * <ul>
 *   <li>Methods resolve against the field's declared type, which must be a
 *       concrete class, record, or enum declared in an accessible package
 *       (public members, or non-private in the mapping's package). Primitive
 *       fields, interfaces, and JDK types (e.g. {@code String}, which cannot
 *       carry policy methods) cannot use this annotation — use
 *       {@code @JqConverter} for those.</li>
 *   <li>Combining {@code @JqAdapter} with {@code @JqConverter} on one element
 *       is an error. {@code @JqField} (extract) and {@code @JqName} (rename)
 *       compose naturally: program extracts, adapter converts.</li>
 * </ul>
 *
 * <p>Malformed adapters (missing methods, wrong arity/static-ness, bad
 * returns) fail fast with named errors at mapping creation / compile time.
 * There is no Jackson equivalent: this replaces hand-written converters.</p>
 *
 * @see JqConverter
 * @see JqMapped
 */
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface JqAdapter {
    /**
     * Name of the static factory method on the field's declared type.
     *
     * @return the {@code from} method name (must be non-empty)
     */
    String from();

    /**
     * Name of the serialization method on the field's declared type.
     *
     * @return the {@code to} method name (must be non-empty)
     */
    String to();
}

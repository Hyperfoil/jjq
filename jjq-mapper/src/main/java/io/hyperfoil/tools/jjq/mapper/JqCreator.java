package io.hyperfoil.tools.jjq.mapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a static factory method or constructor as the deserialization
 * creator for its type, replacing the canonical record constructor or the
 * POJO no-arg-plus-setters path.
 *
 * <p>Mode is inferred from arity (Jackson {@code DEFAULT} parity):</p>
 * <ul>
 *   <li><b>One parameter (delegating):</b> scalar documents convert to the
 *       parameter type and invoke the creator; object documents are shape
 *       mismatches. Enables scalar-backed value types like
 *       {@code record UserId(String value)} bound from {@code "abc"}.</li>
 *   <li><b>Several parameters (properties):</b> object documents bind each
 *       parameter by name and invoke the creator. Names resolve from
 *       {@link JqName} on the parameter, else the real parameter name
 *       ({@code -parameters}); anonymous parameters fail fast at mapping
 *       creation. Missing parameters bind Java defaults (same leniency as
 *       canonical construction).</li>
 * </ul>
 *
 * <p>Creators are deserialization-only; serialization keeps using accessors
 * and getters. Creator parameters support renames only — {@code @JqField}
 * programs, {@code @JqConverter}, and {@code @JqAdapter} on parameters fail
 * fast. At most one creator per type; zero-parameter or non-static methods
 * fail fast.</p>
 *
 * <p>Jackson equivalent: {@code @JsonCreator} (delegating and
 * {@code PROPERTIES} modes, resolved through bridges when native is absent).</p>
 *
 * @see JqName
 */
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
@Retention(RetentionPolicy.RUNTIME)
public @interface JqCreator {
}

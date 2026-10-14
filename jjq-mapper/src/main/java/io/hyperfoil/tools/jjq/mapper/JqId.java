package io.hyperfoil.tools.jjq.mapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds object graphs by a string or numeric id property: repeated full
 * objects deduplicate to one instance, and scalar id values resolve to
 * already-bound instances (graph edges). First occurrence binds fully
 * (including its id property, like any field); repeats render as the bare
 * id on serialization.
 *
 * <pre>{@code
 * @JqId
 * record Node(String id, int value) {}
 * // {"id":"n1","v":1} binds; a later "n1" resolves to the same instance
 * }</pre>
 *
 * <p>Semantics by type:</p>
 * <ul>
 *   <li><b>Records (immutable):</b> dedup plus backward references. Forward
 *       references fail fast (a record cannot be registered before its
 *       canonical constructor returns) — true deserialization cycles need
 *       mutable POJOs.</li>
 *   <li><b>POJOs:</b> the instance registers right after the no-arg
 *       constructor, before field binding, so true cycles resolve.</li>
 * </ul>
 *
 * <p>Scopes are per top-level mapper call (never shared across calls or
 * threads); unresolvable references, missing ids, and non-scalar ids fail
 * fast. Identity is per concrete type: a sealed base's marker is ignored
 * (subtypes opt in individually), which also composes with {@link JqType}
 * dispatch. Direct mapping calls outside the {@code JqMapper} API behave
 * as first occurrences.</p>
 *
 * <p>Jackson equivalent: {@code @JsonIdentityInfo} with
 * {@code PropertyGenerator} (resolved through bridges when native is
 * absent; other generators are out of scope).</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface JqId {
    /**
     * The id property name.
     *
     * @return the property carrying the id (empty is meaningless — fails fast)
     */
    String value() default "id";
}

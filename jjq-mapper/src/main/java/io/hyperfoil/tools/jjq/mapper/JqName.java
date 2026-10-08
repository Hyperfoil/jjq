package io.hyperfoil.tools.jjq.mapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gives a field an explicit JSON wire name.
 *
 * <p>By default, a field named {@code hostPaths} maps to the JSON key
 * {@code "hostPaths"} (subject to the active {@link JqNaming} strategy).
 * This annotation renames the key without changing lookup semantics:</p>
 *
 * <pre>{@code
 * @JqMapped
 * record SpawnConfig(@JqName("host-paths") List<String> hostPaths) {}
 * }</pre>
 *
 * <p>Unlike {@link JqField} — which replaces extraction with an arbitrary jq
 * program (program path, write-always) — a renamed field keeps direct key
 * lookup: the field-access fast path, absent-key skipping, and the naming
 * strategy for every other field are all preserved. Combining
 * {@code @JqName} and {@code @JqField} on one element is an error (fail-fast
 * at mapping creation / compile time).</p>
 *
 * <p>Precedence: an explicit name wins over the naming strategy and over
 * bridge renames (e.g. Jackson {@code @JsonProperty}). An empty value means
 * "no rename", mirroring the bridges.</p>
 *
 * <p>Also names enum constants: a {@code @JqName("api-key")} on a constant is
 * used as its wire form in both directions (strict — unknown wire values
 * still fail), winning over bridge constant renames. Lenient parsing
 * (normalization, unknown-to-null) stays a hand-written {@code @JqConverter}.</p>
 *
 * @see JqField
 * @see JqNaming
 * @see JqMapped
 */
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface JqName {
    /**
     * The JSON key for this field.
     *
     * @return the wire name (empty means no rename)
     */
    String value();
}

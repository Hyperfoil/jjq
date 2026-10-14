package io.hyperfoil.tools.jjq.mapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Alternative accepted JSON keys for a property on deserialization.
 * The primary name ({@link JqName} or the naming strategy) is used on
 * serialization; every alias also binds on deserialization, first present
 * wins (an explicitly present {@code null} counts as present).
 *
 * <pre>{@code
 * record Config(@JqAlias({"host_name", "hostname"}) String hostName) {}
 * // Binds {"hostName":...}, {"host_name":...}, and {"hostname":...};
 * // always serializes as {"hostName":...} (subject to @JqNaming).
 * }</pre>
 *
 * <p>Aliases participate in strict mode and any-setter forwarding as known
 * keys (they bind, so they are never "unknown"). Combining an alias with a
 * {@code @JqField} program fails fast (programs do not read keys). Creator
 * parameters and builder bindings do not accept aliases — rename those
 * instead.</p>
 *
 * <p>Jackson equivalent: {@code @JsonAlias} (resolved through bridges when
 * native is absent).</p>
 *
 * @see JqName
 */
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface JqAlias {
    /**
     * Alternative JSON keys accepted on deserialization.
     *
     * @return the alias names (empty means none)
     */
    String[] value();
}

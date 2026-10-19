package io.hyperfoil.tools.jjq.mapper;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One key-presence dispatch rule inside a {@link JqKeys} family.
 *
 * <p>Rules are tried in declaration order; the first whose key is present
 * (including an explicit JSON null) wins. Presence is the only criterion:
 * the value is bound normally by the selected subtype.</p>
 *
 * @see JqKeys
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface JqKey {
    /**
     * The JSON key whose presence selects the subtype.
     *
     * @return the key name (must be non-blank)
     */
    String value();

    /**
     * The subtype bound when the key is present (must be permitted).
     *
     * @return the subtype class
     */
    Class<?> type();
}

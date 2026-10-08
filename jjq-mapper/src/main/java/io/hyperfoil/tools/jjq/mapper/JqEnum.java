package io.hyperfoil.tools.jjq.mapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Lenient wire-name parsing for an enum type, combining with {@code @JqName}
 * wire names (issue #121).
 *
 * <p>Without this annotation enum binding is strict: the wire value must
 * exactly equal a {@code @JqName} name, bridge-provided name, or constant
 * name. With it, the input is normalized before lookup and unknown values
 * are handled per {@code onUnknown}:</p>
 *
 * <pre>{@code
 * @JqEnum(normalize = {JqEnum.Normalize.TRIM, JqEnum.Normalize.LOWERCASE,
 *                      JqEnum.Normalize.SEPARATOR_FOLD},
 *         onUnknown = JqEnum.OnUnknown.NULL)
 * public enum ClaudeAccountType {
 *     @JqName("api-key") API_KEY,
 *     @JqName("oauth") OAUTH
 * }
 * }</pre>
 *
 * <p>Lookup order: normalized input against normalized {@code @JqName}/bridge
 * wire names, then exact constant-name fallback. Serialization is unaffected
 * (accessor, else wire map, else {@code name()}). A present annotation takes
 * over deserialization policy: a bridge creator ({@code @JsonCreator}) is
 * ignored for that enum. Two constants normalizing to one key fail fast.</p>
 *
 * <p>Only consulted on enums; placed elsewhere it is silently ignored.</p>
 *
 * @see JqName
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface JqEnum {
    /**
     * Input normalizations, applied in declaration spirit (strip, then case,
     * then separators — order between independent folds is irrelevant).
     *
     * @return the normalizations (empty means exact matching)
     */
    Normalize[] normalize() default {};

    /**
     * What to do when the normalized input matches nothing.
     *
     * @return {@code NULL} for lenient nulls, {@code FAIL} for today's strict error
     */
    OnUnknown onUnknown() default OnUnknown.FAIL;

    /**
     * Input folds. All ASCII-only and locale-independent.
     */
    enum Normalize {
        /**
         * Strip leading/trailing whitespace.
         */
        TRIM,

        /**
         * Fold ASCII {@code A-Z} to lowercase (other characters untouched).
         */
        LOWERCASE,

        /**
         * Fold {@code _} separators to {@code -}.
         */
        SEPARATOR_FOLD
    }

    /**
     * Unknown-value policy.
     */
    enum OnUnknown {
        /**
         * Bind unknown wire values as null (records get null, POJOs write
         * null, collections keep a null element).
         */
        NULL,

        /**
         * Fail on unknown wire values (today's strict behavior).
         */
        FAIL
    }
}

package io.hyperfoil.tools.jjq.mapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Explicit wire-value to subtype mapping inside a {@link JqType} family.
 *
 * <p>Used when the discriminator values are domain values rather than class
 * names, and when several wire values bind the same subtype (many-to-one).
 * Entries preserve declaration order; duplicate wire values fail fast at
 * mapping creation.</p>
 *
 * <pre>{@code
 * @JqType(discriminator = "method", existingProperty = true, mappings = {
 *     @JqSubtype(value = "EMAIL", type = EmailConfig.class),
 *     @JqSubtype(value = "SLACK", type = TokenSecret.class),
 *     @JqSubtype(value = "GITHUB_ISSUE", type = TokenSecret.class)
 * })
 * sealed interface Channel permits EmailConfig, TokenSecret {}
 * }</pre>
 *
 * <p>Explicit mappings require {@code existingProperty = true}: the
 * discriminator is a real field whose value drives serialization, so the
 * many-to-one reverse lookup stays unambiguous. Declaring mappings without
 * {@code existingProperty} fails fast.</p>
 *
 * @see JqType
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface JqSubtype {
    /**
     * The wire value selecting the subtype.
     *
     * @return the discriminator value (must be non-blank)
     */
    String value();

    /**
     * The subtype bound for the wire value (must be a permitted subtype).
     *
     * @return the subtype class
     */
    Class<?> type();
}

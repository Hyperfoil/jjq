package io.hyperfoil.tools.jjq.mapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a property as required: documents missing the key fail binding
 * instead of receiving Java defaults or field initializers.
 *
 * <p>Explicit nulls still bind (null handling is unchanged — only absence
 * fails). Ignored properties never fail. Program lookups
 * ({@code @JqField}) cannot prove presence and fail fast at mapping
 * creation when combined with this marker.</p>
 *
 * <p>Applies to record components, POJO fields, and creator parameters
 * alike, on reflection and generated paths.</p>
 *
 * <p>Jackson equivalent: {@code @JsonProperty(required = true)} (resolved
 * through bridges when native is absent).</p>
 *
 * @see JqName
 * @see JqField
 */
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface JqRequired {
}

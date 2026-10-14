package io.hyperfoil.tools.jjq.mapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Custom string format for a date/time property, replacing the ISO-8601
 * default in both directions.
 *
 * <pre>{@code
 * record Event(@JqFormat(pattern = "dd/MM/yyyy") java.time.LocalDate day) {}
 * // Binds "14/10/2026" and serializes back identically.
 * }</pre>
 *
 * <p>Rules:</p>
 * <ul>
 *   <li>Applies to {@code Instant}, {@code LocalDate}, {@code LocalTime},
 *       {@code LocalDateTime}, {@code OffsetDateTime}, and
 *       {@code ZonedDateTime}; any other type fails fast at mapping
 *       creation (durations stay ISO, numbers keep their own rules).</li>
 *   <li>Formatted binding is strings-only: numbers, booleans, and
 *       containers mismatch (even for instant-like types, which accept
 *       epoch seconds in their default form).</li>
 *   <li>Patterns without zone information format and parse instant-like
 *       types in {@code timezone} (default UTC); patterns carrying zone
 *       information use their own. Locale defaults to root (deterministic
 *       everywhere — this differs from Jackson's system-default locale).</li>
 *   <li>An empty pattern is a no-op marker (ISO behavior, timezone and
 *       locale silently moot).</li>
 * </ul>
 *
 * <p>Jackson equivalent: {@code @JsonFormat(pattern, timezone, locale)}
 * (resolved through bridges when native is absent; non-string shapes are
 * out of scope and fail fast).</p>
 */
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface JqFormat {
    /**
     * Date/time pattern ({@code DateTimeFormatter} syntax). Empty means ISO.
     *
     * @return the pattern, or empty for the default form
     */
    String pattern() default "";

    /**
     * Zone for instant-like types when the pattern carries none
     * (e.g. {@code "Europe/Oslo"}). Empty means UTC.
     *
     * @return the zone id, or empty for UTC
     */
    String timezone() default "";

    /**
     * BCP 47 language tag for month/day names (e.g. {@code "fr"}).
     * Empty means root (deterministic output everywhere).
     *
     * @return the locale tag, or empty for root
     */
    String locale() default "";

    /**
     * Normalized format configuration: pattern plus resolved locale and
     * zone (never empty — defaults applied).
     *
     * @param pattern  the pattern text
     * @param timezone the effective zone id
     * @param locale   the effective locale tag
     */
    record Spec(String pattern, String timezone, String locale) {
    }
}

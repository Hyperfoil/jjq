package io.hyperfoil.tools.jjq.mapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Custom string format for a date/time property, replacing the ISO-8601
 * default in both directions — plus wire-shape control for numbers
 * and booleans.
 *
 * <pre>{@code
 * record Event(@JqFormat(pattern = "dd/MM/yyyy") java.time.LocalDate day) {}
 * // Binds "14/10/2026" and serializes back identically.
 * record Ids(@JqFormat(shape = JqFormat.Shape.STRING) long id) {}
 * // Serializes {"id":"42"} (quoted); binds quoted and bare numbers alike.
 * }</pre>
 *
 * <p>Rules:</p>
 * <ul>
 *   <li>Patterns apply to {@code Instant}, {@code LocalDate},
 *       {@code LocalTime}, {@code LocalDateTime}, {@code OffsetDateTime},
 *       and {@code ZonedDateTime}; any other type fails fast at mapping
 *       creation (durations stay ISO, numbers keep their own rules —
 *       patterns are never {@code DecimalFormat}).</li>
 *   <li>Shape {@code STRING} quotes numbers and booleans on serialization
 *       (deserialization already accepts quoted and bare forms, so shape is
 *       serialization-side only). On strings, characters, enums, and
 *       date/time types without a pattern it is a no-op (they already
 *       render as strings).</li>
 *   <li>Shape {@code NUMBER} is the default wire form: a no-op on numbers,
 *       strings, and characters. On booleans, date/time types, and enums
 *       it fails fast (numeric wire forms are out of scope — booleans
 *       stay booleans).</li>
 *   <li>Formatted binding is strings-only: numbers, booleans, and
 *       containers mismatch (even for instant-like types, which accept
 *       epoch seconds in their default form).</li>
 *   <li>Patterns without zone information format and parse instant-like
 *       types in {@code timezone} (default UTC); patterns carrying zone
 *       information use their own. Locale defaults to root (deterministic
 *       everywhere — this differs from Jackson's system-default locale).</li>
 *   <li>An empty pattern is a no-op marker (ISO behavior, timezone and
 *       locale silently moot).</li>
 *   <li>Shapes on {@code Optional} fail fast (bind the inner type
 *       directly).</li>
 * </ul>
 *
 * <p>Jackson equivalent: {@code @JsonFormat(pattern, timezone, locale,
 * shape)} (resolved through bridges when native is absent; non-string,
 * non-number shapes are no-ops, and number/boolean patterns are moot —
 * Jackson ignores {@code DecimalFormat} patterns on serialization).</p>
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
     * Wire shape for the value. {@code STRING} quotes numbers and booleans
     * on serialization; {@code NUMBER} is the default form (no-op except
     * on booleans, date/time types, and enums, which fail fast).
     * Jackson-only shapes ({@code ARRAY}, {@code OBJECT}, ...) arrive as
     * {@code ANY} through bridges (Jackson ignores them on numbers).
     *
     * @return the wire shape, or {@code ANY} for the default form
     */
    Shape shape() default Shape.ANY;

    /**
     * Wire shapes mirroring {@code JsonFormat.Shape} (minimal subset: only
     * {@code STRING} changes the wire form; {@code NUMBER} is the default
     * and fails fast where a numeric form would apply).
     */
    enum Shape {
        /** Default wire form (numbers unquoted, booleans bare). */
        ANY,
        /** Quote numbers and booleans on serialization. */
        STRING,
        /**
         * Numeric wire form: a no-op on numbers, strings, and characters;
         * fails fast on booleans, date/time types, and enums.
         */
        NUMBER
    }

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

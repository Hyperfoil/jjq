package io.hyperfoil.tools.jjq.mapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method to receive otherwise-unmapped ("unknown") keys during
 * deserialization — the native equivalent of Jackson's {@code @JsonAnySetter}.
 *
 * <p>The method must take exactly two parameters, the first a {@code String}
 * key. The second is either a {@link io.hyperfoil.tools.jjq.value.JqValue}
 * (preferred: exact values, no conversion) or an {@code Object} (converted
 * via Java maps/lists/scalars):</p>
 * <pre>{@code
 * private final Map<String, JqValue> extras = new LinkedHashMap<>();
 *
 * @JqAnySetter
 * public void setExtra(String key, JqValue value) { extras.put(key, value); }
 * }</pre>
 *
 * <p>Only keys that match no mapped (non-ignored) field are forwarded;
 * known and {@code @JqIgnore}d keys never reach the setter. Pair with
 * {@link JqAnyGetter} for lossless round-trips.</p>
 *
 * @see JqAnyGetter
 * @see JqMapper
 *
 * <p>Jackson equivalent: {@code @JsonAnySetter} (same method-form contract).</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface JqAnySetter {}

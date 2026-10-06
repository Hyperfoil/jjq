package io.hyperfoil.tools.jjq.mapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a no-arg method whose returned entries are serialized as regular
 * object fields — the native equivalent of Jackson's {@code @JsonAnyGetter}.
 *
 * <p>The method must return either a {@code Map<String, ?>} (values converted
 * to JSON, {@code null} as JSON null) or a
 * {@link io.hyperfoil.tools.jjq.value.JqObject} (entries used directly):</p>
 * <pre>{@code
 * private final Map<String, JqValue> extras = new LinkedHashMap<>();
 *
 * @JqAnyGetter
 * public Map<String, JqValue> getExtras() { return extras; }
 * }</pre>
 *
 * <p>Entries are emitted after mapped fields. Pair with {@link JqAnySetter}
 * for lossless round-trips.</p>
 *
 * @see JqAnySetter
 * @see JqMapper
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface JqAnyGetter {}

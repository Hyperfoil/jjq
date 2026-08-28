package io.hyperfoil.tools.jjq.mapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation that triggers compile-time code generation for a record type.
 *
 * <p>When the {@code jjq-mapper-processor} annotation processor is on the classpath,
 * records annotated with {@code @JqMapped} get a generated {@code ClassName_JqMapping}
 * class that provides optimized serialization and deserialization — no reflection,
 * no MethodHandle dispatch, direct constructor calls and accessor calls.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * @JqMapped
 * record User(String name, int age, @JqField(".address.city") String city) {}
 * }</pre>
 *
 * <p>The generated mapping is automatically discovered by {@link JqMapper} via naming
 * convention ({@code User_JqMapping}). If the processor is not present, {@code JqMapper}
 * falls back to reflection-based mapping — the annotation has no effect at runtime
 * beyond being a marker.</p>
 *
 * <p><b>Without the processor:</b> This annotation is purely a marker. Records work
 * with {@code JqMapper} regardless of whether they are annotated, using the
 * reflection-based path.</p>
 *
 * <p><b>With the processor:</b> The generated mapper eliminates:</p>
 * <ul>
 *   <li>{@code Class.getRecordComponents()} reflection</li>
 *   <li>{@code MethodHandle.invokeWithArguments(Object[])} for construction</li>
 *   <li>{@code Object[]} allocation for constructor args</li>
 *   <li>{@code TypeConverter} dispatch chain per field</li>
 * </ul>
 *
 * @see JqField
 * @see JqIgnore
 * @see JqMapper
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface JqMapped {}

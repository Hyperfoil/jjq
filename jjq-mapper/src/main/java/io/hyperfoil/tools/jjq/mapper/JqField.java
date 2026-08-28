package io.hyperfoil.tools.jjq.mapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Override the jq expression used to extract a field value during deserialization.
 *
 * <p>By default, a record component named {@code myField} maps to the jq expression
 * {@code .myField}. This annotation overrides that with an arbitrary jq expression:</p>
 *
 * <pre>{@code
 * record PerfResult(
 *     @JqField(".autobench_workload.data[0].results") JqValue results,
 *     @JqField(".rhivos_config | {build, model, kernel}") JqValue config,
 *     String user  // defaults to .user
 * ) {}
 * }</pre>
 *
 * <p>The jq expression is compiled once via {@link io.hyperfoil.tools.jjq.JqProgram#compile(String)}
 * and cached. Simple field paths (e.g., {@code .name}) use the fast-path shape
 * detection and execute in ~3ns with zero allocation.</p>
 *
 * <p>During serialization, fields with {@code @JqField} use the component name
 * as the JSON key (not the jq expression).</p>
 *
 * @see JqIgnore
 * @see JqMapper
 */
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface JqField {
    /** The jq expression to extract this field's value. */
    String value();
}

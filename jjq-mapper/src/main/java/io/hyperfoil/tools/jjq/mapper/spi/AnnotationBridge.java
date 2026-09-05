package io.hyperfoil.tools.jjq.mapper.spi;

import io.hyperfoil.tools.jjq.mapper.JqInclude;
import io.hyperfoil.tools.jjq.mapper.JqNaming;

import java.lang.reflect.AnnotatedElement;

/**
 * Service provider interface for bridging external annotation frameworks
 * (Jackson, JSON-B, etc.) into jjq-mapper's annotation system.
 *
 * <p>Implementations read framework-specific annotations from fields and classes,
 * translating them into jjq-mapper concepts. Multiple bridges can be registered;
 * they are consulted in registration order after jjq-native annotations.</p>
 *
 * <h2>Priority order</h2>
 * <ol>
 *   <li>jjq-native annotations ({@code @JqField}, {@code @JqIgnore}, etc.) — always win</li>
 *   <li>Bridge annotations — consulted in registration order</li>
 *   <li>Defaults — Java field name, no ignoring, ALWAYS inclusion</li>
 * </ol>
 *
 * <h2>Registration</h2>
 * <p>Bridges can be registered explicitly via the builder:</p>
 * <pre>{@code
 * JqMapper mapper = JqMapper.builder()
 *     .bridge(new JacksonAnnotationBridge())
 *     .build();
 * }</pre>
 *
 * <p>Or discovered automatically via {@link java.util.ServiceLoader} when using
 * {@link io.hyperfoil.tools.jjq.mapper.JqMapper#create()}.</p>
 *
 * <h2>Implementation requirements</h2>
 * <ul>
 *   <li>Must have a public no-arg constructor (for ServiceLoader discovery)</li>
 *   <li>Must be thread-safe (called during class introspection, which is cached)</li>
 *   <li>Should return {@code null} from resolution methods when the bridge's
 *       annotations are not present — this allows the next bridge or default to take over</li>
 * </ul>
 *
 * @see io.hyperfoil.tools.jjq.mapper.JqMapper.Builder#bridge(AnnotationBridge)
 */
public interface AnnotationBridge {

    /**
     * Resolve a field name override from this bridge's annotations.
     *
     * @param element the field or record component to inspect
     * @return the overridden JSON field name, or {@code null} if no annotation is present
     */
    String resolveFieldName(AnnotatedElement element);

    /**
     * Check whether this element should be ignored (excluded from mapping).
     *
     * @param element the field or record component to inspect
     * @return {@code true} if the element should be ignored, {@code false} if not,
     *         or the bridge's annotation is not present
     */
    boolean isIgnored(AnnotatedElement element);

    /**
     * Resolve an inclusion strategy override from this bridge's annotations.
     *
     * @param element the field, record component, or class to inspect
     * @return the inclusion strategy, or {@code null} if no annotation is present
     */
    JqInclude.Include resolveInclusion(AnnotatedElement element);

    /**
     * Resolve a naming strategy override from this bridge's annotations.
     *
     * @param type the class to inspect
     * @return the naming strategy, or {@code null} if no annotation is present
     */
    JqNaming.Strategy resolveNaming(Class<?> type);
}

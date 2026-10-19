package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.value.JqValue;

/**
 * Compiled enum binding: direct calls for an enum's wire conversion, with
 * no reflective member lookup or invocation on any path.
 *
 * <p>Implementations are generated at build time (annotation processor or
 * Quarkus augmentation) from the same annotation rules as the reflective
 * path, so behavior matches exactly: {@link #read} mirrors the
 * {@code ENUM} conversion branch and {@link #write} mirrors enum
 * serialization, including null handling, wire-name fallbacks, and
 * failure attribution. Anything the generator cannot express falls back
 * to reflective resolution instead of receiving a binding.</p>
 *
 * <p>Contracts (mirroring the reflective path):</p>
 * <ul>
 *   <li>The mapper short-circuits Java {@code null} and JSON null before
 *       consulting bindings, so implementations assume non-null input.</li>
 *   <li>{@code write} never receives {@code null} (callers map it to JSON
 *       null first).</li>
 *   <li>Unbindable input throws {@link JqMapperException}, never returns
 *       garbage or silently substitutes another constant.</li>
 * </ul>
 *
 * @param <E> the bound enum type
 */
public interface EnumBinding<E extends Enum<E>> {

    /**
     * @return the bound enum class
     */
    Class<E> type();

    /**
     * Bind a JSON value to an enum constant.
     *
     * @param value  the input (never Java {@code null} or JSON null via the mapper)
     * @param mapper the mapper for nested conversions
     * @return the bound constant
     * @throws JqMapperException if the value cannot bind
     */
    E read(JqValue value, JqMapper mapper);

    /**
     * Render an enum constant to its wire form.
     *
     * @param value  the constant (never {@code null})
     * @param mapper the mapper for nested conversions
     * @return the JSON wire value
     * @throws JqMapperException if the value cannot render
     */
    JqValue write(E value, JqMapper mapper);
}

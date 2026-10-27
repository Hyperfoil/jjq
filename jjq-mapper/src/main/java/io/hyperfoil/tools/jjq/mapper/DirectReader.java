package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.value.JqValues;

/**
 * Build-time generated direct reader from JSON bytes to a Java type,
 * bypassing the {@code JqValue} tree (issue #170).
 *
 * <p>Implementations are emitted per eligible type by the Quarkus
 * augmentation step (and, in the future, the annotation processor) from
 * one {@link BindingSchema}: they drive a
 * {@link JqValues.JqCursor strict cursor} over the input, decode canonical
 * wire values into typed locals, and invoke the target constructor
 * directly. Anything outside the canonical fast path — unknown or escaped
 * field names, nested values, accepted coercions, duplicates, malformed
 * separators — throws {@link DirectReadFallbackException}, and the caller
 * re-runs the existing strict parse-plus-map path over the complete
 * original input. Fallback therefore reproduces today's exact behavior;
 * only a fully validated document constructs a value.</p>
 *
 * <p>Instances are stateless and thread-safe: all mutable cursor state,
 * locals and duplicate tracking live in {@link #read} locals, so one
 * reader is shared across endpoints and requests.</p>
 *
 * @param <T> the bound type
 */
public interface DirectReader<T> {

    /**
     * The bound type.
     *
     * @return the target class
     */
    Class<T> type();

    /**
     * Read one JSON document straight into the target type.
     *
     * @param json   the request bytes (buffered by the caller; not retained)
     * @param mapper the application mapper (for future nested delegation;
     *               the MVP flat backend does not consult it)
     * @return the constructed value (never a fallback signal)
     * @throws DirectReadFallbackException to run the existing path instead;
     *         user constructors, creators, converters and setters never run
     *         before this is thrown or a value is returned
     * @throws IllegalArgumentException on malformed input that the
     *         cursor detects itself, identical to the existing path
     */
    T read(byte[] json, JqMapper mapper);
}

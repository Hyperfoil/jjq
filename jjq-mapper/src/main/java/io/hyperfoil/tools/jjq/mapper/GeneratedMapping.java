package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.value.JqValue;

/**
 * Base class for compile-time generated mapping classes.
 *
 * <p>The {@code jjq-mapper-processor} annotation processor generates a subclass
 * of this class for each record annotated with {@link JqMapped}. The generated
 * class is named {@code ClassName_JqMapping} and is automatically discovered
 * by {@link JqMapper} via naming convention.</p>
 *
 * <p>Generated mappings provide:</p>
 * <ul>
 *   <li>Direct constructor calls (no {@code MethodHandle.invokeWithArguments})</li>
 *   <li>Direct accessor calls (no {@code MethodHandle.invoke})</li>
 *   <li>Inlined type conversions (no {@code TypeConverter} dispatch)</li>
 *   <li>Direct-to-JSON serialization ({@code appendJson}) bypassing the JqValue tree</li>
 *   <li>Direct-to-bytes serialization ({@code appendJsonBytes}) bypassing the JqValue tree</li>
 *   <li>Static {@code JqProgram} fields for {@code @JqField} expressions</li>
 *   <li>No reflection at runtime — GraalVM native-image friendly</li>
 * </ul>
 *
 * <p>Example generated class:</p>
 * <pre>{@code
 * public final class User_JqMapping extends GeneratedMapping<User> {
 *     private static final JqProgram P_NAME = JqProgram.compile(".name");
 *     private static final JqProgram P_AGE = JqProgram.compile(".age");
 *
 *     @Override
 *     public User fromJqValue(JqValue input, JqMapper mapper) {
 *         return new User(
 *             P_NAME.apply(input).asString(null),
 *             (int) P_AGE.apply(input).asLong(0)
 *         );
 *     }
 *
 *     @Override
 *     public JqValue toJqValue(User instance, JqMapper mapper) {
 *         return JqObject.builder(2)
 *             .put("name", instance.name())
 *             .put("age", (long) instance.age())
 *             .build();
 *     }
 *
 *     @Override
 *     public Class<User> type() { return User.class; }
 * }
 * }</pre>
 *
 * @param <T> the record type being mapped
 * @see JqMapped
 * @see JqMapper
 */
public abstract non-sealed class GeneratedMapping<T> implements Mapping<T> {

    /** Creates a new GeneratedMapping. */
    protected GeneratedMapping() {}

    /**
     * Return the record class this mapping handles.
     *
     * @return the mapped record class
     */
    public abstract Class<T> type();

    /**
     * Require a scalar (non-container) value for scalar conversion. Returns the
     * value unchanged for nulls and scalars (downstream {@code asX} accessors
     * keep their lenient defaults); throws a shape-mismatch error for arrays
     * and objects.
     *
     * @param value    the extracted value (never null from {@code JqProgram.apply})
     * @param expected human-readable target description for the error, e.g. {@code String}
     * @return the value unchanged
     * @throws JqMapperException if the value is an array or object
     */
    protected static JqValue requireScalar(JqValue value, JqValue.Type expectedType, String expected) {
        if (value != null && value.isContainer()) throw TypeConverter.mismatch(expectedType, expected, value);
        return value;
    }

    /**
     * Lenient string coercion mirroring {@code TypeConverter} {@code STRING} semantics:
     * nulls stay null, strings unwrap, other scalars render as JSON text,
     * arrays and objects fail fast.
     *
     * @param value the extracted value (never null from {@code JqProgram.apply})
     * @return the coerced string, or null for null inputs
     * @throws JqMapperException if the value is an array or object
     */
    protected static String asStringChecked(JqValue value) {
        if (value == null || value instanceof io.hyperfoil.tools.jjq.value.JqNull) return null;
        if (value instanceof io.hyperfoil.tools.jjq.value.JqString s) return s.stringValue();
        if (value.isContainer()) throw TypeConverter.mismatch(JqValue.Type.STRING, "String", value);
        return value.toJsonString();
    }

    /**
     * Require an array value for list conversion. Returns {@code null} for
     * null inputs (callers map those to empty lists); throws a shape-mismatch
     * error for any other non-array shape.
     *
     * @param value  the value to check (may be null)
     * @param target human-readable target description for the error, e.g. {@code List<Tool>}
     * @return the value as a {@code JqArray}, or {@code null} for null inputs
     * @throws JqMapperException if the value is a non-array shape
     */
    protected static io.hyperfoil.tools.jjq.value.JqArray requireArray(JqValue value, String target) {
        if (value == null || value instanceof io.hyperfoil.tools.jjq.value.JqNull) return null;
        if (value instanceof io.hyperfoil.tools.jjq.value.JqArray arr) return arr;
        throw TypeConverter.mismatch(JqValue.Type.ARRAY, "array for " + target, value);
    }
}

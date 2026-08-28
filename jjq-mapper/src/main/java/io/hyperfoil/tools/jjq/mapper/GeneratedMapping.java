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

    /** Return the record class this mapping handles. */
    public abstract Class<T> type();
}

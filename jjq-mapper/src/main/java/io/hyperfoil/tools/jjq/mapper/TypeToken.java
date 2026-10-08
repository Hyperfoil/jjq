package io.hyperfoil.tools.jjq.mapper;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Objects;

/**
 * Captures a generic type at runtime via an anonymous subclass, in the style
 * of Guava's {@code TypeToken} and Jackson's {@code TypeReference}.
 *
 * <p>Java erases generic parameters, so a {@code Class<List<Item>>} literal
 * cannot exist. Subclassing this class anonymously preserves the type argument
 * in the class's generic superclass signature, where reflection can read it:</p>
 *
 * <pre>{@code
 * Type listOfItems = new TypeToken<List<Item>>(){}.getType();
 * List<Item> items = mapper.fromJqValue(value, listOfItems);
 * }</pre>
 *
 * <p>Generated {@code _JqMapping} classes emit this pattern for generic fields
 * (a parameterized {@code .class} literal is illegal Java).</p>
 *
 * @param <T> the captured type
 */
public abstract class TypeToken<T> {

    private final Type type;

    /**
     * Captures the type argument from the anonymous subclass's generic
     * superclass signature.
     *
     * @throws JqMapperException if the subclass carries no type argument
     *         (i.e. this class was subclassed without a concrete parameter)
     */
    protected TypeToken() {
        Type superclass = getClass().getGenericSuperclass();
        if (superclass instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length == 1) {
                this.type = args[0];
                return;
            }
        }
        throw new JqMapperException("TypeToken must be subclassed with a concrete type argument: "
                + "new TypeToken<List<Item>>(){}.getType()");
    }

    /**
     * The captured type.
     *
     * @return the generic type argument of the anonymous subclass
     */
    public Type getType() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TypeToken<?> other && Objects.equals(type, other.type);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(type);
    }

    @Override
    public String toString() {
        return "TypeToken<" + type.getTypeName() + ">";
    }
}

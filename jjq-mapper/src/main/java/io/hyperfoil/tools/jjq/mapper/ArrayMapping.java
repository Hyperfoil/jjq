package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.value.BytOutput;
import io.hyperfoil.tools.jjq.value.JqValue;

/**
 * Mapping for Java array classes (reference or primitive component).
 * Arrays have no no-arg constructor, so neither {@link ClassMapping} nor
 * generated mappings apply — both directions delegate to
 * {@link TypeConverter}, which binds structurally. Direct-to-JSON fast
 * paths fall back to the tree (arrays are cold shapes; one correct output).
 *
 * @param <T> the array type
 */
final class ArrayMapping<T> implements Mapping<T> {

    private final Class<T> type;

    ArrayMapping(Class<T> type) {
        this.type = type;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T fromJqValue(JqValue value, JqMapper mapper) {
        return (T) TypeConverter.convert(value, TypeConverter.Kind.ARRAY, type, type, mapper);
    }

    @Override
    public JqValue toJqValue(T instance, JqMapper mapper) {
        return TypeConverter.toJqValue(instance, mapper);
    }

    @Override
    public void appendJson(T instance, StringBuilder sb, JqMapper mapper) {
        toJqValue(instance, mapper).appendTo(sb);
    }

    @Override
    public void appendJsonBytes(T instance, BytOutput out, JqMapper mapper) {
        toJqValue(instance, mapper).appendToBytes(out);
    }
}

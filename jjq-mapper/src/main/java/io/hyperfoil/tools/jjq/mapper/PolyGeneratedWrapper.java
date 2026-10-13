package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.value.BytOutput;
import io.hyperfoil.tools.jjq.value.JqValue;

/**
 * Wraps a {@link GeneratedMapping} for a polymorphic family member so
 * serialization injects the discriminator. Deserialization needs no
 * interception (the base {@link PolyMapping} delegates to the raw
 * mapping); the direct-to-JSON fast paths fall back to the injected
 * tree, keeping one correct output shape.
 *
 * @param <T> the member type
 */
final class PolyGeneratedWrapper<T> extends GeneratedMapping<T> {

    private final GeneratedMapping<T> delegate;
    private final PolySerInfo info;

    PolyGeneratedWrapper(GeneratedMapping<T> delegate, PolySerInfo info) {
        this.delegate = delegate;
        this.info = info;
    }

    @Override
    public Class<T> type() {
        return delegate.type();
    }

    @Override
    public T fromJqValue(JqValue value, JqMapper mapper) {
        return delegate.fromJqValue(value, mapper);
    }

    @Override
    public JqValue toJqValue(T instance, JqMapper mapper) {
        return info.inject(delegate.toJqValue(instance, mapper));
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

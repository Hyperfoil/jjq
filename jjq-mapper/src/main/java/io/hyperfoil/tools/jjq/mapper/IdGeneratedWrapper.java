package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.value.JqValue;

/**
 * Wraps a {@link GeneratedMapping} for an identity-bound type so
 * serialization and deserialization share the reflection-path identity
 * semantics (dedup, reference resolution, id emission) with zero codegen
 * changes. The direct-to-JSON fast paths inherit the tree fallback, which
 * routes through the identity-aware {@code toJqValue} below.
 *
 * @param <T> the member type
 */
final class IdGeneratedWrapper<T> extends GeneratedMapping<T> {

    private final GeneratedMapping<T> delegate;
    private final IdInfo idInfo;

    IdGeneratedWrapper(GeneratedMapping<T> delegate, IdInfo idInfo) {
        this.delegate = delegate;
        this.idInfo = idInfo;
    }

    @Override
    public Class<T> type() {
        return delegate.type();
    }

    @Override
    @SuppressWarnings("unchecked")
    public T fromJqValue(JqValue value, JqMapper mapper) {
        Object known = IdBindings.lookupDeser(value, idInfo, type().getSimpleName());
        if (known != null) return (T) known;
        T bound = delegate.fromJqValue(value, mapper);
        IdBindings.registerDeser(value, idInfo, bound);
        return bound;
    }

    @Override
    public JqValue toJqValue(T instance, JqMapper mapper) {
        JqValue ref = IdBindings.lookupSer(instance, idInfo);
        if (ref != null) return ref;
        return IdBindings.registerSer(instance, idInfo,
                delegate.toJqValue(instance, mapper), type().getSimpleName());
    }

    @Override
    public IdInfo idInfo() {
        return idInfo;
    }
}

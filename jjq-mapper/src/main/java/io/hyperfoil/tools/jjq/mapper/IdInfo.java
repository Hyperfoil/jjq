package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.mapper.spi.AnnotationBridge;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.List;

/**
 * Identity configuration for one concrete type: which property keys it.
 * Resolved once per mapping from native {@link JqId} (empty value fails
 * fast) or the first bridge identity declaration; absent means standalone
 * binding.
 *
 * @param property the id property name
 */
record IdInfo(String property) {

    /**
     * Resolve identity for a concrete type, or null when unconfigured.
     *
     * @param type    the class being mapped
     * @param bridges the mapper bridges
     * @return the configuration, or null for standalone binding
     */
    static IdInfo resolve(Class<?> type, List<AnnotationBridge> bridges) {
        JqId spec = type.getAnnotation(JqId.class);
        if (spec != null) {
            if (spec.value() == null || spec.value().isEmpty()) {
                throw new JqMapperException("@JqId on " + type.getName()
                        + " needs a property name");
            }
            return new IdInfo(spec.value());
        }
        for (AnnotationBridge bridge : bridges) {
            String property = bridge.resolveIdentity(type);
            if (property != null && !property.isEmpty()) {
                return new IdInfo(property);
            }
        }
        return null;
    }

    /**
     * Look up a bound instance by id value. Null or inactive scopes
     * (direct mapping calls outside the API) and null values always miss —
     * and never create the maps.
     *
     * @param idValue the scalar id
     * @param scope   the ambient scope (may be null)
     * @return the registered instance, or null
     */
    Object lookupDeser(JqValue idValue, IdentityScope scope) {
        if (scope == null || !scope.isActive() || idValue == null) return null;
        if (scope.byId().isEmpty()) return null;
        return scope.byId().get(idValue.toJsonString());
    }

    /**
     * Register a bound instance under its id value. Null or inactive scopes
     * are a no-op (standalone semantics for direct mapping calls — nothing
     * may leak across them).
     */
    void registerDeser(JqValue idValue, Object instance, IdentityScope scope) {
        if (scope == null || !scope.isActive() || idValue == null || instance == null) return;
        scope.byId().put(idValue.toJsonString(), instance);
    }

    /**
     * Look up a rendered id for an instance. Null or inactive scopes always
     * miss.
     *
     * @return the stored id value, or null for first occurrences
     */
    JqValue lookupSer(Object instance, IdentityScope scope) {
        if (scope == null || !scope.isActive() || instance == null) return null;
        if (scope.byInstance().isEmpty()) return null;
        return scope.byInstance().get(instance);
    }

    /** Register a first occurrence. Null or inactive scopes are a no-op. */
    void registerSer(Object instance, JqValue idValue, IdentityScope scope) {
        if (scope == null || !scope.isActive() || instance == null || idValue == null) return;
        scope.byInstance().put(instance, idValue);
    }
}

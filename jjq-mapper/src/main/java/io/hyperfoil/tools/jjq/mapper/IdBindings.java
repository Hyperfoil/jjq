package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.value.JqBoolean;
import io.hyperfoil.tools.jjq.value.JqNull;
import io.hyperfoil.tools.jjq.value.JqNumber;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;

/**
 * Shared identity entry points used by reflection mappings and the
 * generated-mapping wrapper alike (single implementation — the callers
 * differ only in how they bind and build). All methods tolerate a null
 * scope (direct mapping calls behave as first occurrences).
 */
final class IdBindings {

    private IdBindings() {}

    /** True for scalar id values (strings, numbers, booleans — never null). */
    static boolean isScalarId(JqValue value) {
        return value instanceof JqString || value instanceof JqNumber || value instanceof JqBoolean;
    }

    /**
     * Deserialization lookup: full objects resolve their id property
     * (missing or non-scalar ids fail fast); scalars resolve as references;
     * anything else falls through (shape gates report it). Returns the
     * registered instance, or null on a first occurrence.
     */
    static Object lookupDeser(JqValue value, IdInfo info, String simpleName) {
        if (value instanceof JqObject obj) {
            JqValue idValue = obj.get(info.property());
            if (!isScalarId(idValue)) {
                throw TypeConverter.mismatch(JqValue.Type.STRING,
                        "id property '" + info.property() + "' for " + simpleName, idValue);
            }
            return info.lookupDeser(idValue, IdentityScope.current());
        }
        if (isScalarId(value)) {
            Object known = info.lookupDeser(value, IdentityScope.current());
            if (known != null) return known;
            throw TypeConverter.mismatch(JqValue.Type.STRING,
                    "known id of " + simpleName, value);
        }
        return null;
    }

    /**
     * Deserialization registration after binding (no-op for non-objects,
     * which never reach here — shape gates throw first).
     */
    static void registerDeser(JqValue value, IdInfo info, Object instance) {
        if (!(value instanceof JqObject obj)) return;
        JqValue idValue = obj.get(info.property());
        if (isScalarId(idValue)) {
            info.registerDeser(idValue, instance, IdentityScope.current());
        }
    }

    /**
     * Serialization lookup: repeats render as the stored id value.
     * Returns null for first occurrences.
     */
    static JqValue lookupSer(Object instance, IdInfo info) {
        return info.lookupSer(instance, IdentityScope.current());
    }

    /**
     * Serialization registration after building: the tree must carry a
     * scalar id (missing or non-scalar ids fail — the property has to be
     * a bound field).
     */
    static JqValue registerSer(Object instance, IdInfo info, JqValue tree, String simpleName) {
        if (!(tree instanceof JqObject obj)) {
            throw new JqMapperException("Cannot emit " + simpleName + " without id property '"
                    + info.property() + "': not an object");
        }
        JqValue idValue = obj.get(info.property());
        if (!isScalarId(idValue)) {
            throw new JqMapperException("Cannot emit " + simpleName + " without id property '"
                    + info.property() + "'");
        }
        info.registerSer(instance, idValue, IdentityScope.current());
        return tree;
    }
}

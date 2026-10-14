package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Per-operation object-identity scope for {@code @JqId} binding. Established
 * by every public {@code JqMapper} entry point, reused by nested binds, and
 * cleared when the outermost call returns — scopes never leak across calls
 * or threads.
 *
 * <p>Always entered (the outer call cannot know whether nested types need
 * identity — mappings resolve lazily mid-bind, so a fast-path check would
 * isolate siblings into separate scopes). The maps stay lazy, so operations
 * without identity pay one small scope allocation plus ThreadLocal churn,
 * nothing more.</p>
 */
final class IdentityScope {

    private static final ThreadLocal<IdentityScope> CURRENT = new ThreadLocal<>();

    private Map<String, Object> byId;
    private Map<Object, JqValue> byInstance;
    private int depth;

    private IdentityScope() {}

    /** The ambient scope, or null outside an operation. */
    static IdentityScope current() {
        return CURRENT.get();
    }

    /**
     * Enter a scope: reuse the ambient one when nested, else create and own.
     * Pair every call with {@link #close()} in a finally block.
     *
     * @return the ambient or new scope
     */
    static IdentityScope enter() {
        IdentityScope scope = CURRENT.get();
        if (scope == null) {
            scope = new IdentityScope();
            CURRENT.set(scope);
        }
        scope.depth++;
        return scope;
    }

    /**
     * Leave a scope: the owner clears the maps and detaches (nested calls
     * only decrement). Safe to call without a matching enter on a fresh
     * thread (no-op).
     */
    void close() {
        if (--depth == 0) {
            byId = null;
            byInstance = null;
            CURRENT.remove();
        }
    }

    /** Id to instance map, created on first identity bind. */
    Map<String, Object> byId() {
        if (byId == null) byId = new HashMap<>();
        return byId;
    }

    /** Instance to rendered-id map, created on first identity bind. */
    Map<Object, JqValue> byInstance() {
        if (byInstance == null) byInstance = new IdentityHashMap<>();
        return byInstance;
    }
}

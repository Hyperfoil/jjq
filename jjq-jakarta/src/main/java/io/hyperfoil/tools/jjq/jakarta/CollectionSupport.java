package io.hyperfoil.tools.jjq.jakarta;

import io.hyperfoil.tools.jjq.mapper.JqKeys;
import io.hyperfoil.tools.jjq.mapper.JqMapped;
import io.hyperfoil.tools.jjq.mapper.JqType;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.Collection;
import java.util.Map;

/**
 * Generic collection support shared by the {@code @JqMapped} JAX-RS
 * providers (issue #152): a homogeneous {@code Collection} (covers
 * {@code List}/{@code Set}), {@code Map}, or array whose element/value
 * type is directly mappable binds element-wise through the shared
 * {@link io.hyperfoil.tools.jjq.mapper.JqMapper}. Anything else —
 * raw collections, nested generics, scalar or non-string-keyed maps —
 * keeps declining so the container default still serves it.
 */
final class CollectionSupport {

    private CollectionSupport() {}

    /** Raw types bound directly: records, POJOs, and polymorphic families. */
    static boolean isDirectlyMappable(Class<?> type) {
        return type.isAnnotationPresent(JqMapped.class)
                || type.isAnnotationPresent(JqType.class)
                || type.isAnnotationPresent(JqKeys.class);
    }

    /**
     * Whether {@code genericType} is a homogeneous collection of directly
     * mappable elements: one annotated (or {@code JqValue}) type argument
     * for collections and arrays, {@code String} keys plus one such value
     * type for maps. Null-safe: a missing generic type never matches.
     *
     * @param genericType the declared generic type (may be null)
     * @return true when the providers should bind through the mapper
     */
    static boolean bindsThroughMapper(Type genericType) {
        if (genericType instanceof ParameterizedType parameterized) {
            if (!(parameterized.getRawType() instanceof Class<?> raw)) return false;
            Type[] args = parameterized.getActualTypeArguments();
            if (Collection.class.isAssignableFrom(raw)) {
                return args.length == 1 && isMappableElement(args[0]);
            }
            if (Map.class.isAssignableFrom(raw)) {
                return args.length == 2 && isStringKey(args[0]) && isMappableElement(args[1]);
            }
            return false;
        }
        if (genericType instanceof Class<?> clazz && clazz.isArray()) {
            return isMappableElement(clazz.getComponentType());
        }
        return false;
    }

    /**
     * Writer-side variant of {@link #bindsThroughMapper}: RESTEasy passes the
     * DECLARED endpoint return type, so a reactive wrapper around a mappable
     * collection ({@code Uni<List<Hero>>}) must unwrap to the bound
     * collection before matching. Only single-value async shapes qualify:
     * Mutiny {@code Uni} by name (no Mutiny dependency here) and
     * {@code CompletionStage} (covers {@code CompletableFuture}); streaming
     * shapes and {@code Response}-wrapped values keep declining exactly as
     * before. The framework always resolves the wrapper before writing, so
     * per-element dispatch is unchanged. The reader keeps the strict
     * variant: request entities are never async-typed, and claiming one
     * would bind the wrong Java type downstream.
     *
     * @param genericType the declared generic type (may be null)
     * @return true when the writer should bind through the mapper
     */
    static boolean bindsResponseThroughMapper(Type genericType) {
        Type current = genericType;
        while (current instanceof ParameterizedType parameterized
                && isAsyncWrapper(rawClassOf(parameterized))) {
            Type[] args = parameterized.getActualTypeArguments();
            if (args.length != 1) return false;
            current = args[0];
        }
        return bindsThroughMapper(current);
    }

    /** Raw class of a parameterized type, or null for exotic raw types. */
    private static Class<?> rawClassOf(ParameterizedType parameterized) {
        return parameterized.getRawType() instanceof Class<?> raw ? raw : null;
    }

    /** Single-value async wrappers the framework resolves before writing. */
    private static boolean isAsyncWrapper(Class<?> raw) {
        if (raw == null) return false;
        if (java.util.concurrent.CompletionStage.class.isAssignableFrom(raw)) return true;
        return raw.getName().equals("io.smallrye.mutiny.Uni");
    }

    /** An element type the mapper binds: annotated, or a {@code JqValue} passthrough. */
    private static boolean isMappableElement(Type type) {
        Class<?> element = upperBound(type);
        if (element == null) return false;
        return element.isAnnotationPresent(JqMapped.class)
                || element.isAnnotationPresent(JqType.class)
                || element.isAnnotationPresent(JqKeys.class)
                || JqValue.class.isAssignableFrom(element);
    }

    /** Map keys stay strings on the wire (Jackson parity). */
    private static boolean isStringKey(Type type) {
        return upperBound(type) == String.class;
    }

    /** A plain class, or a wildcard's upper bound; anything else declines. */
    private static Class<?> upperBound(Type type) {
        if (type instanceof Class<?> clazz) return clazz;
        if (type instanceof WildcardType wildcard && wildcard.getUpperBounds().length > 0
                && wildcard.getUpperBounds()[0] instanceof Class<?> bound) {
            return bound;
        }
        return null;
    }
}

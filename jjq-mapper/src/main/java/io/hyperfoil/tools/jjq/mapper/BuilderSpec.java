package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.mapper.spi.AnnotationBridge;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;

/**
 * Builder-based construction for one type: instantiate a builder, invoke
 * one single-argument method per bound property (chained or void), then the
 * terminal build method. Resolved from native {@link JqBuilder} or the
 * Jackson {@code @JsonDeserialize(builder)}/{@code @JsonPOJOBuilder} pair
 * (native wins).
 *
 * @param newBuilder  zero-arg handle producing a builder instance
 *                    (factory call or constructor)
 * @param binders     one handle per bound field, in {@code fields} order
 *                    (null entries bind nothing — absent by construction)
 * @param voidBinder  parallel flags: true when the binder returns void
 *                    (mutating in place) instead of chaining
 * @param build       zero-arg terminal handle producing the instance
 * @param label       the annotation kind for error messages
 *                    ({@code @JqBuilder} or {@code @JsonDeserialize})
 */
record BuilderSpec(MethodHandle newBuilder, MethodHandle[] binders, boolean[] voidBinder,
                   MethodHandle build, String label) {

    /**
     * Resolve the builder for a value type and its fields. Returns null when
     * neither native nor bridge configuration is present. Fails fast on gaps:
     * missing factory/constructor, missing build method, or a deserializable
     * field without a matching builder method.
     *
     * @param type    the value class
     * @param fields  the bound property fields (binders align by index)
     * @param bridges the mapper bridges
     * @return the spec, or null when unconfigured
     */
    static BuilderSpec resolve(Class<?> type, FieldMapping[] fields,
                               List<AnnotationBridge> bridges) {
        AnnotationBridge.BuilderDecl decl = nativeDecl(type);
        String label = "@JqBuilder";
        if (decl == null) {
            for (AnnotationBridge bridge : bridges) {
                decl = bridge.resolveBuilder(type);
                if (decl != null) {
                    label = "@JsonDeserialize";
                    break;
                }
            }
        }
        if (decl == null) return null;
        try {
            MethodHandles.Lookup typeLookup =
                    MethodHandles.privateLookupIn(type, MethodHandles.lookup());
            MethodHandles.Lookup builderLookup =
                    MethodHandles.privateLookupIn(decl.builderClass(), MethodHandles.lookup());
            // Builder instance source: factory on the value type, else
            // the builder no-arg constructor.
            MethodHandle newBuilder;
            if (!decl.factory().isEmpty()) {
                Method factory;
                try {
                    factory = type.getMethod(decl.factory());
                } catch (NoSuchMethodException e) {
                    throw new JqMapperException(label + " on " + type.getName()
                            + ": no static factory '" + decl.factory() + "' on "
                            + type.getSimpleName());
                }
                if (!Modifier.isStatic(factory.getModifiers()) || factory.getParameterCount() != 0) {
                    throw new JqMapperException(label + " on " + type.getName()
                            + ": factory '" + decl.factory() + "' must be a static no-arg method");
                }
                if (!decl.builderClass().isAssignableFrom(factory.getReturnType())) {
                    throw new JqMapperException(label + " on " + type.getName()
                            + ": factory '" + decl.factory() + "' must return "
                            + decl.builderClass().getSimpleName());
                }
                newBuilder = typeLookup.unreflect(factory);
            } else {
                try {
                    newBuilder = builderLookup.findConstructor(decl.builderClass(),
                            java.lang.invoke.MethodType.methodType(void.class));
                } catch (NoSuchMethodException e) {
                    throw new JqMapperException(label + " on " + type.getName()
                            + ": builder " + decl.builderClass().getSimpleName()
                            + " needs a no-arg constructor (or name a factory)");
                }
            }
            // Terminal build method on the builder class.
            Method buildMethod = findBuild(type, decl);
            MethodHandle build = builderLookup.unreflect(buildMethod);
            // One binder per field, aligned by index (null = no binder).
            MethodHandle[] binders = new MethodHandle[fields.length];
            boolean[] voidBinder = new boolean[fields.length];
            for (int i = 0; i < fields.length; i++) {
                FieldMapping field = fields[i];
                if (field.isIgnored() || field.skipDeserialize()) continue;
                String candidate = decl.prefix().isEmpty() ? field.name()
                        : decl.prefix() + Character.toUpperCase(field.name().charAt(0))
                        + field.name().substring(1);
                Method binder = findBinder(decl.builderClass(), candidate, field);
                if (binder == null) {
                    throw new JqMapperException(label + " on " + type.getName()
                            + ": no builder method '" + candidate + "("
                            + field.type().getSimpleName() + ")' on "
                            + decl.builderClass().getSimpleName() + " for field '"
                            + field.name() + "'");
                }
                try {
                    binders[i] = builderLookup.unreflect(binder);
                } catch (IllegalAccessException e) {
                    throw new JqMapperException("Cannot access builder method '"
                            + candidate + "' on " + decl.builderClass().getName(), e);
                }
                voidBinder[i] = binder.getReturnType() == void.class;
            }
            return new BuilderSpec(newBuilder, binders, voidBinder, build, label);
        } catch (IllegalAccessException e) {
            throw new JqMapperException("Cannot create builder lookup for " + type.getName(), e);
        }
    }

    /** Native declaration, or null when absent. */
    private static AnnotationBridge.BuilderDecl nativeDecl(Class<?> type) {
        JqBuilder spec = type.getAnnotation(JqBuilder.class);
        if (spec == null) return null;
        return new AnnotationBridge.BuilderDecl(spec.value(), spec.factory(), spec.prefix(), spec.build());
    }

    /** Terminal build method: no-arg, non-static, returning the value type. First match wins. */
    private static Method findBuild(Class<?> type, AnnotationBridge.BuilderDecl decl) {
        for (Method m : decl.builderClass().getMethods()) {
            if (m.getName().equals(decl.build()) && m.getParameterCount() == 0
                    && !Modifier.isStatic(m.getModifiers())
                    && type.isAssignableFrom(m.getReturnType())) {
                return m;
            }
        }
        throw new JqMapperException("Builder " + decl.builderClass().getSimpleName()
                + " has no terminal method '" + decl.build() + "()' returning "
                + type.getSimpleName());
    }

    /**
     * Binder for one field: single argument accepting the field type (or its
     * box), non-static. First match wins; overloads beyond that are out of
     * scope (documented).
     */
    private static Method findBinder(Class<?> builderClass, String name, FieldMapping field) {
        for (Method m : builderClass.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != 1
                    || Modifier.isStatic(m.getModifiers())) continue;
            if (boxed(m.getParameterTypes()[0]).isAssignableFrom(boxed(field.type()))) return m;
        }
        return null;
    }

    /** Box a primitive for assignability checks. */
    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == boolean.class) return Boolean.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }
}

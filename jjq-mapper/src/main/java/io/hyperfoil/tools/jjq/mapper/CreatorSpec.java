package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.mapper.spi.AnnotationBridge;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * A deserialization creator for one type: a {@code @JqCreator} static
 * factory/constructor or a bridge {@code @JsonCreator} equivalent.
 * The creator takes over construction from the canonical record
 * constructor or the POJO no-arg-plus-setters path.
 *
 * <p>Mode is inferred from arity and naming (Jackson {@code DEFAULT}
 * parity): a single parameter without an explicit name is
 * <b>delegating</b> (scalar documents convert to the parameter type);
 * anything else is <b>properties</b> (object documents bind each
 * parameter by name). Creator parameters bind by direct lookup only —
 * {@code @JqField} programs and {@code @JqAdapter} on parameters fail
 * fast; {@code @JqConverter} is honored.</p>
 *
 * @param spreader   the creator as a fixed-arity spreader over
 *                   {@code Object[]} (pre-cached, no per-call allocation)
 * @param params     the creator parameters as field mappings
 *                   (constructor positions, direct lookups)
 * @param delegating true for single unnamed-parameter creators
 * @param label      the annotation kind for error messages
 *                   ({@code @JqCreator} or {@code @JsonCreator})
 */
record CreatorSpec(MethodHandle spreader, FieldMapping[] params, boolean delegating, String label,
                   java.util.Set<String> required) {

    /**
     * Resolve the creator for a type: native {@code @JqCreator} first,
     * then bridges in order. Native wins; an invalid native creator fails
     * fast instead of falling back.
     *
     * @param type    the mapped class
     * @param bridges the mapper bridges
     * @return the creator, or null when absent
     */
    static CreatorSpec resolve(Class<?> type, List<AnnotationBridge> bridges) {
        List<Executable> nativeCandidates = new ArrayList<>();
        for (Method m : type.getDeclaredMethods()) {
            if (m.isAnnotationPresent(JqCreator.class)) nativeCandidates.add(m);
        }
        for (Constructor<?> c : type.getDeclaredConstructors()) {
            if (c.isAnnotationPresent(JqCreator.class)) nativeCandidates.add(c);
        }
        if (!nativeCandidates.isEmpty()) {
            if (nativeCandidates.size() > 1) {
                throw new JqMapperException("@JqCreator on " + type.getName()
                        + ": at most one creator per type");
            }
            return fromExecutable(type, nativeCandidates.get(0), "@JqCreator", true, bridges);
        }
        for (AnnotationBridge bridge : bridges) {
            Executable e = bridge.resolveJsonCreator(type);
            if (e != null) return fromExecutable(type, e, "@JsonCreator", false, bridges);
        }
        return null;
    }

    /**
     * Validate an executable and build its spec. Native executables must be
     * static factories or constructors with at least one parameter;
     * bridge executables follow the same shape (the bridge pre-filters modes).
     */
    private static CreatorSpec fromExecutable(Class<?> type, Executable e, String label,
                                              boolean nativeAnn, List<AnnotationBridge> bridges) {
        if (e instanceof Method m) {
            if (!Modifier.isStatic(m.getModifiers())) {
                throw new JqMapperException(label + " on " + type.getName()
                        + ": method creators must be static: " + m.getName());
            }
            if (m.getReturnType() == void.class || !type.isAssignableFrom(m.getReturnType())) {
                throw new JqMapperException(label + " on " + type.getName()
                        + ": factory '" + m.getName() + "' must return " + type.getSimpleName());
            }
        }
        if (e.getParameterCount() == 0) {
            throw new JqMapperException(label + " on " + type.getName()
                    + ": creators need at least one parameter");
        }
        Parameter[] parameters = e.getParameters();
        // Delegating creators (single unnamed parameter) bind the whole
        // document: no property name is ever resolved for the argument.
        boolean delegating = parameters.length == 1
                && explicitName(parameters[0], bridges) == null;
        FieldMapping[] params = new FieldMapping[parameters.length];
        java.util.Set<String> required = new java.util.LinkedHashSet<>();
        for (int i = 0; i < parameters.length; i++) {
            params[i] = creatorParam(type, label, e, parameters[i], i, bridges, delegating);
            if (!delegating && TypeConverter.isRequired(parameters[i], bridges)) {
                required.add(params[i].jsonName());
            }
        }
        MethodHandle spreader;
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());
            spreader = (e instanceof Constructor<?> c
                    ? lookup.unreflectConstructor(c)
                    : lookup.unreflect((Method) e)).asSpreader(Object[].class, parameters.length);
        } catch (IllegalAccessException ex) {
            throw new JqMapperException("Cannot access " + label + " on " + type.getName(), ex);
        }
        return new CreatorSpec(spreader, params, delegating, label,
                java.util.Collections.unmodifiableSet(required));
    }

    /**
     * Build the field mapping for one creator parameter: renames from
     * {@code @JqName}, then bridges, then the real parameter name;
     * anonymous parameters fail fast. Converters are honored; lookup
     * programs and adapters on parameters fail fast.
     */
    private static FieldMapping creatorParam(Class<?> type, String label, Executable executable,
                                             Parameter parameter, int index,
                                             List<AnnotationBridge> bridges, boolean unnamedOk) {
        if (parameter.isAnnotationPresent(JqField.class)) {
            throw new JqMapperException(label + " on " + type.getName()
                    + ": @JqField programs are not supported on creator parameters"
                    + " (rename with @JqName instead)");
        }
        if (parameter.isAnnotationPresent(JqAdapter.class)) {
            throw new JqMapperException(label + " on " + type.getName()
                    + ": @JqAdapter is not supported on creator parameters"
                    + " (use @JqConverter instead)");
        }
        String name = explicitName(parameter, bridges);
        if (name == null) {
            if (parameter.isNamePresent()) {
                name = parameter.getName();
            } else if (unnamedOk) {
                // Delegating argument binds the whole document: the name is
                // inert (never looked up, never prepended).
                name = "value";
            } else {
                throw new JqMapperException(label + " on " + type.getName()
                        + ": parameter " + index + " needs an explicit name (@JqName)"
                        + " — recompile with -parameters or rename it");
            }
        }
        Class<?> paramType = parameter.getType();
        Type genericType = parameter.getParameterizedType();
        ValueConverter<?> converter = null;
        JqConverter converterAnn = parameter.getAnnotation(JqConverter.class);
        if (converterAnn != null) {
            try {
                converter = converterAnn.value().getDeclaredConstructor().newInstance();
            } catch (Exception ex) {
                throw new JqMapperException("Failed to instantiate converter: "
                        + converterAnn.value().getName(), ex);
            }
        }
        String jsonName = name.intern();
        return new FieldMapping(name, jsonName, jsonName, null, paramType, genericType,
                null, null, index, false, JqInclude.Include.ALWAYS, converter, false, false);
    }

    /**
     * Explicit wire name for a creator parameter: native {@code @JqName}
     * first, then bridges (e.g. Jackson {@code @JsonProperty}). Null when
     * neither provides one (callers fall back to the real parameter name).
     */
    private static String explicitName(Parameter parameter, List<AnnotationBridge> bridges) {
        JqName jqName = parameter.getAnnotation(JqName.class);
        if (jqName != null && !jqName.value().isEmpty()) return jqName.value();
        for (AnnotationBridge bridge : bridges) {
            String wire = bridge.resolveFieldName(parameter);
            if (wire != null && !wire.isEmpty()) return wire;
        }
        return null;
    }
}

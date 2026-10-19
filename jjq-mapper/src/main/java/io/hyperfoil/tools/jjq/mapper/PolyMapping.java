package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.mapper.spi.AnnotationBridge;
import io.hyperfoil.tools.jjq.value.JqNull;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dispatching mapping for a polymorphic family ({@link JqType} base or
 * bridge-declared equivalent). Deserialization reads the discriminator
 * property and delegates to the matching subtype mapping; serialization
 * re-dispatches on the runtime type (which carries its own injection,
 * unless the discriminator is an existing property). Missing, non-string,
 * or unknown discriminator values fail fast naming the family, property,
 * and offending value.
 *
 * @param <T> the base type
 */
public final class PolyMapping<T> implements Mapping<T> {

    private final Class<T> base;
    private final String discriminator;
    private final Map<String, Class<?>> subtypes;
    private final boolean existingProperty;

    PolyMapping(Class<T> base, PolyConfig config) {
        this.base = base;
        this.discriminator = config.property();
        this.subtypes = config.subtypes();
        this.existingProperty = config.existingProperty();
    }

    @Override
    @SuppressWarnings("unchecked")
    public T fromJqValue(JqValue value, JqMapper mapper) {
        if (value == null || value instanceof JqNull) return null;
        if (!(value instanceof JqObject obj)) {
            throw TypeConverter.mismatch(JqValue.Type.OBJECT, "object for " + base.getSimpleName(), value);
        }
        JqValue disc = obj.get(discriminator);
        if (!(disc instanceof JqString s)) {
            throw TypeConverter.mismatch(JqValue.Type.STRING,
                    "type discriminator '" + discriminator + "' for " + base.getSimpleName(), disc);
        }
        Class<?> sub = subtypes.get(s.stringValue());
        if (sub == null) {
            throw TypeConverter.mismatch(JqValue.Type.STRING,
                    "one of " + new ArrayList<>(subtypes.keySet()) + " for discriminator '"
                            + discriminator + "' of " + base.getSimpleName(), disc);
        }
        if (mapper.isFailOnUnknownProperties() && !existingProperty) {
            // The injected discriminator is consumed here: without stripping,
            // the subtype mapping would flag it as unrecognized. Rebuilt only
            // in strict mode; lenient keeps the zero-copy delegation. An
            // existing property stays: it is a real field the subtype binds.
            var stripped = JqObject.builder(obj.size());
            obj.forEach((k, v) -> {
                if (!k.equals(discriminator)) stripped.putUnchecked(k, v);
            });
            value = stripped.build();
        }
        return (T) mapper.fromJqValue(value, sub);
    }

    @Override
    public JqValue toJqValue(T instance, JqMapper mapper) {
        // Re-dispatch on the runtime type: the subtype mapping owns injection.
        return mapper.toJqValue(instance);
    }

    /**
     * Resolve the family configuration for a base type: native
     * {@link JqType} first, then bridges in order. Native wins.
     * Public for build-time tooling (e.g. the {@code jjq-quarkus}
     * extension pre-resolves families during augmentation so native
     * images never query sealed permits at runtime).
     *
     * @param base    the base class being mapped
     * @param bridges the mapper bridges
     * @return the configuration, or null when not polymorphic
     */
    public static PolyConfig resolvePolyConfig(Class<?> base, List<AnnotationBridge> bridges) {
        PolyConfig nativeConfig = nativePolyConfig(base);
        if (nativeConfig != null) return nativeConfig;
        for (AnnotationBridge bridge : bridges) {
            PolyConfig config = bridge.resolvePolymorphism(base);
            if (config != null) return config;
        }
        return null;
    }

    /**
     * Native configuration from {@link JqType}. Returns null when absent;
     * fails fast when present on a non-sealed type, on duplicate wire values,
     * on explicit mappings without {@code existingProperty}, or when an
     * explicit type is not permitted.
     */
    private static PolyConfig nativePolyConfig(Class<?> base) {
        JqType spec = base.getAnnotation(JqType.class);
        if (spec == null) return null;
        Class<?>[] permitted = base.getPermittedSubclasses();
        if (permitted == null || permitted.length == 0) {
            throw new JqMapperException("@JqType on " + base.getName()
                    + " needs a sealed type (no permitted subclasses found)");
        }
        JqSubtype[] explicit = spec.mappings();
        if (explicit != null && explicit.length > 0) {
            return explicitPolyConfig(base, spec, explicit, permitted);
        }
        var subtypes = new LinkedHashMap<String, Class<?>>();
        for (Class<?> sub : permitted) {
            String wire = polyWireValue(sub);
            if (subtypes.putIfAbsent(wire, sub) != null) {
                throw new JqMapperException("@JqType on " + base.getName()
                        + ": duplicate discriminator value '" + wire + "'");
            }
        }
        return new PolyConfig(spec.discriminator(), subtypes, spec.existingProperty());
    }

    /**
     * Configuration from explicit {@code mappings}: every wire must be
     * non-blank and unique, every type a permitted subtype. Explicit
     * mappings require {@code existingProperty} (field-driven
     * serialization keeps many-to-one unambiguous).
     */
    private static PolyConfig explicitPolyConfig(Class<?> base, JqType spec,
            JqSubtype[] explicit, Class<?>[] permitted) {
        if (!spec.existingProperty()) {
            throw new JqMapperException("@JqType on " + base.getName()
                    + ": explicit mappings need existingProperty = true"
                    + " (the discriminator is a real field, serialization is field-driven)");
        }
        var allowed = new java.util.HashSet<Class<?>>(List.of(permitted));
        var subtypes = new LinkedHashMap<String, Class<?>>();
        for (JqSubtype mapping : explicit) {
            if (mapping == null || mapping.value() == null || mapping.value().isEmpty()
                    || mapping.type() == null) {
                throw new JqMapperException("@JqType on " + base.getName()
                        + " needs non-empty wire values and subtype classes");
            }
            if (!allowed.contains(mapping.type())) {
                throw new JqMapperException("@JqType on " + base.getName()
                        + ": " + mapping.type().getName() + " is not a permitted subtype");
            }
            if (subtypes.putIfAbsent(mapping.value(), mapping.type()) != null) {
                throw new JqMapperException("@JqType on " + base.getName()
                        + ": duplicate discriminator value '" + mapping.value() + "'");
            }
        }
        return new PolyConfig(spec.discriminator(), subtypes, true);
    }

    /**
     * Serialization membership for a concrete type: the nearest polymorphic
     * ancestor's discriminator plus this type's wire value under it, or null
     * when no ancestor declares polymorphism. Pure function of the class —
     * order-independent, computed once per mapping.
     *
     * <p>Nested sealed families resolve to the nearest ancestor; deeper
     * families sharing a leaf are a documented limitation.</p>
     */
    static PolySerInfo resolvePolyMember(Class<?> type, List<AnnotationBridge> bridges) {
        // Superclass chain, nearest first (covers POJO inheritance).
        List<Class<?>> chain = new ArrayList<>();
        for (Class<?> c = type.getSuperclass(); c != null && c != Object.class; c = c.getSuperclass()) {
            chain.add(c);
        }
        for (Class<?> c : chain) {
            PolySerInfo info = memberOf(type, c, bridges);
            if (info != null) return info;
        }
        // Interfaces of the type and every superclass, breadth-first.
        Set<Class<?>> seen = new LinkedHashSet<>();
        List<Class<?>> queue = new ArrayList<>(List.of(type.getInterfaces()));
        for (Class<?> c : chain) queue.addAll(List.of(c.getInterfaces()));
        while (!queue.isEmpty()) {
            Class<?> iface = queue.remove(0);
            if (!seen.add(iface)) continue;
            PolySerInfo info = memberOf(type, iface, bridges);
            if (info != null) return info;
            queue.addAll(List.of(iface.getInterfaces()));
        }
        return null;
    }

    /**
     * Membership of {@code type} under one ancestor: native {@link JqType}
     * (sealed enforces permits, so presence implies membership) else the
     * first bridge configuration listing it. Existing-property families
     * carry no injection (the field value drives serialization), so they
     * resolve to null like standalone types.
     */
    private static PolySerInfo memberOf(Class<?> type, Class<?> ancestor, List<AnnotationBridge> bridges) {
        JqType spec = ancestor.getAnnotation(JqType.class);
        if (spec != null) {
            if (ancestor.getPermittedSubclasses() == null) {
                throw new JqMapperException("@JqType on " + ancestor.getName()
                        + " needs a sealed type (no permitted subclasses found)");
            }
            if (spec.existingProperty() || (spec.mappings() != null && spec.mappings().length > 0)) {
                return null;
            }
            return new PolySerInfo(spec.discriminator(), polyWireValue(type));
        }
        for (AnnotationBridge bridge : bridges) {
            PolyConfig config = bridge.resolvePolymorphism(ancestor);
            if (config == null) continue;
            boolean listed = false;
            String wire = null;
            for (var entry : config.subtypes().entrySet()) {
                if (entry.getValue() == type) {
                    listed = true;
                    wire = entry.getKey();
                    break;
                }
            }
            if (!listed) continue;
            if (config.existingProperty()) {
                // Field-driven serialization: no injection.
                return null;
            }
            return new PolySerInfo(config.property(), wire);
        }
        return null;
    }

    /**
     * Discriminator value for a subtype: {@link JqName} on the declaration
     * wins, else the simple class name (case-sensitive, Jackson parity).
     */
    static String polyWireValue(Class<?> sub) {
        JqName rename = sub.getAnnotation(JqName.class);
        if (rename != null && !rename.value().isEmpty()) return rename.value();
        return sub.getSimpleName();
    }
}

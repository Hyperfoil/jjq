package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.value.JqNull;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Dispatching mapping for a key-presence family ({@link JqKeys} base).
 * Deserialization tries the rules in declaration order; the first whose key
 * is present — including an explicit JSON null — selects the subtype. When
 * no rule matches, the default subtype binds; without a default the input
 * fails fast naming the family and the expected keys. Serialization
 * re-dispatches on the runtime type with no discriminator injected.
 *
 * @param <T> the base type
 */
public final class KeyPolyMapping<T> implements Mapping<T> {

    private final Class<T> base;
    private final List<KeyPolyConfig.KeyRule> rules;
    private final Class<?> defaultSubtype;

    KeyPolyMapping(Class<T> base, KeyPolyConfig config) {
        this.base = base;
        this.rules = config.rules();
        this.defaultSubtype = config.defaultSubtype();
    }

    @Override
    @SuppressWarnings("unchecked")
    public T fromJqValue(JqValue value, JqMapper mapper) {
        if (value == null || value instanceof JqNull) return null;
        if (!(value instanceof JqObject obj)) {
            throw TypeConverter.mismatch(JqValue.Type.OBJECT, "object for " + base.getSimpleName(), value);
        }
        for (var rule : rules) {
            if (obj.has(rule.key())) {
                return (T) mapper.fromJqValue(value, rule.subtype());
            }
        }
        if (defaultSubtype != null) {
            return (T) mapper.fromJqValue(value, defaultSubtype);
        }
        var keys = new ArrayList<String>();
        for (var rule : rules) keys.add(rule.key());
        throw TypeConverter.mismatch(JqValue.Type.OBJECT,
                "one of keys " + keys + " for " + base.getSimpleName(), value);
    }

    @Override
    public JqValue toJqValue(T instance, JqMapper mapper) {
        // Re-dispatch on the runtime type: no discriminator to inject.
        return mapper.toJqValue(instance);
    }

    /**
     * Native configuration from {@link JqKeys}. Returns null when absent;
     * fails fast when present on a non-sealed type, on empty rules, on
     * duplicate keys, or when a rule or default type is not permitted.
     * A type carrying both {@code @JqKeys} and {@code @JqType} fails fast.
     * Public for build-time tooling (see {@link PolyMapping}).
     */
    public static KeyPolyConfig resolveKeyConfig(Class<?> base) {
        JqKeys spec = base.getAnnotation(JqKeys.class);
        if (spec == null) return null;
        if (base.getAnnotation(JqType.class) != null) {
            throw new JqMapperException("@" + JqKeys.class.getSimpleName() + " on " + base.getName()
                    + " cannot be combined with @" + JqType.class.getSimpleName());
        }
        Class<?>[] permitted = base.getPermittedSubclasses();
        if (permitted == null || permitted.length == 0) {
            throw new JqMapperException("@" + JqKeys.class.getSimpleName() + " on " + base.getName()
                    + " needs a sealed type (no permitted subclasses found)");
        }
        var allowed = new java.util.HashSet<Class<?>>(List.of(permitted));
        JqKey[] declared = spec.value();
        if (declared == null || declared.length == 0) {
            throw new JqMapperException("@" + JqKeys.class.getSimpleName() + " on " + base.getName()
                    + " needs at least one rule");
        }
        var rules = new ArrayList<KeyPolyConfig.KeyRule>();
        for (JqKey rule : declared) {
            if (rule == null || rule.value() == null || rule.value().isEmpty() || rule.type() == null) {
                throw new JqMapperException("@" + JqKeys.class.getSimpleName() + " on " + base.getName()
                        + " needs non-empty keys and subtype classes");
            }
            if (!allowed.contains(rule.type())) {
                throw new JqMapperException("@" + JqKeys.class.getSimpleName() + " on " + base.getName()
                        + ": " + rule.type().getName() + " is not a permitted subtype");
            }
            rules.add(new KeyPolyConfig.KeyRule(rule.value(), rule.type()));
        }
        Class<?> def = spec.defaultSubtype();
        Class<?> defaultSubtype = (def == null || def == Void.class) ? null : def;
        if (defaultSubtype != null && !allowed.contains(defaultSubtype)) {
            throw new JqMapperException("@" + JqKeys.class.getSimpleName() + " on " + base.getName()
                    + ": default " + defaultSubtype.getName() + " is not a permitted subtype");
        }
        return new KeyPolyConfig(rules, defaultSubtype);
    }
}

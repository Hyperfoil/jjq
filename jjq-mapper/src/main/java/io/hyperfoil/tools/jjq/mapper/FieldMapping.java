package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.value.*;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Type;

/**
 * Mapping metadata for a single record component / class field.
 * Holds the extraction strategy, the pre-resolved conversion kind,
 * and the MethodHandle for reading field values (serialization).
 *
 * <p>Instances are created once per field during class introspection and
 * cached in the parent {@link ClassMapping}.</p>
 *
 * <p>Two performance optimizations over naive dispatch:</p>
 * <ul>
 *   <li><b>Direct field access</b>: for simple {@code .fieldName} expressions (no
 *       {@code @JqField}), extracts via {@code JqObject.get(name)} directly instead
 *       of going through {@code JqProgram.apply()}. Avoids program shape detection
 *       and ThreadLocal VM lookup.</li>
 *   <li><b>Pre-resolved conversion kind</b>: the target type is resolved to an enum
 *       constant at introspection time. Conversion uses a single switch on the enum
 *       instead of a long if/else chain — no lambda classes, no anonymous inner classes,
 *       faster JIT warmup.</li>
 * </ul>
 */
final class FieldMapping {

    private final String name;            // Java field/component name
    private final String jsonName;        // JSON key for serialization (may differ from name due to @JqNaming)
    private final String directFieldName; // non-null for direct JqObject.get() extraction (no @JqField)
    private final JqProgram program;      // non-null for @JqField expressions
    private final Class<?> type;          // target Java type
    private final Type genericType;       // generic type (for List<T>, Map<String, V>, etc.)
    private final MethodHandle getter;    // reads the field value from an instance (for serialization)
    private final MethodHandle setter;    // writes the field value on an instance (POJO deserialization), null for records
    private final int constructorIndex;   // index in the canonical constructor parameter list (-1 for POJOs)
    private final boolean ignored;        // true if @JqIgnore is present
    private final JqInclude.Include inclusion; // serialization inclusion strategy
    private final TypeConverter.Kind conversionKind; // pre-resolved conversion strategy

    /** Constructor for record components (positional constructor, no setter). */
    FieldMapping(String name, String directFieldName, JqProgram program,
                 Class<?> type, Type genericType,
                 MethodHandle getter, int constructorIndex, boolean ignored) {
        this(name, name, directFieldName, program, type, genericType, getter, null, constructorIndex, ignored, JqInclude.Include.ALWAYS);
    }

    /** Full constructor with all options. */
    FieldMapping(String name, String jsonName, String directFieldName, JqProgram program,
                 Class<?> type, Type genericType,
                 MethodHandle getter, MethodHandle setter, int constructorIndex,
                 boolean ignored, JqInclude.Include inclusion) {
        this.name = name;
        this.jsonName = jsonName;
        this.directFieldName = directFieldName;
        this.program = program;
        this.type = type;
        this.genericType = genericType;
        this.getter = getter;
        this.setter = setter;
        this.constructorIndex = constructorIndex;
        this.ignored = ignored;
        this.inclusion = inclusion;
        this.conversionKind = ignored ? TypeConverter.Kind.DEFAULT
                                     : TypeConverter.resolveKind(type, genericType);
    }

    /** Extract the field value from a JqValue. */
    JqValue extract(JqValue input) {
        if (ignored) return null;
        if (directFieldName != null) {
            // Direct field access — bypass JqProgram entirely
            if (input instanceof JqObject obj) return obj.get(directFieldName);
            return JqNull.NULL;
        }
        return program.apply(input);
    }

    /** Convert the extracted JqValue to the target Java type. */
    Object convert(JqValue extracted, JqMapper mapper) {
        return TypeConverter.convert(extracted, conversionKind, type, genericType, mapper);
    }

    /** Read the field value from a Java object instance (for serialization). */
    Object readValue(Object instance) {
        try {
            return getter.invoke(instance);
        } catch (Throwable e) {
            throw new JqMapperException("Failed to read field '" + name + "' from " + instance.getClass().getName(), e);
        }
    }

    /** Write a field value on a POJO instance (via setter or direct field access). */
    void writeValue(Object instance, Object value) {
        if (setter == null) throw new JqMapperException("No setter for field '" + name + "'");
        try {
            setter.invoke(instance, value);
        } catch (Throwable e) {
            throw new JqMapperException("Failed to write field '" + name + "' on " + instance.getClass().getName(), e);
        }
    }

    /**
     * Check whether a serialized value should be included based on the inclusion strategy.
     * @param value the Java value read from the field (may be null)
     * @return true if the value should be included in the serialized output
     */
    boolean shouldInclude(Object value) {
        return switch (inclusion) {
            case ALWAYS -> true;
            case NON_NULL -> value != null;
            case NON_EMPTY -> {
                if (value == null) yield false;
                if (value instanceof String s && s.isEmpty()) yield false;
                if (value instanceof java.util.Collection<?> c && c.isEmpty()) yield false;
                if (value instanceof java.util.Map<?, ?> m && m.isEmpty()) yield false;
                yield true;
            }
            case NON_DEFAULT -> {
                if (value == null) yield false;
                if (value instanceof Number n && n.doubleValue() == 0.0) yield false;
                if (value instanceof Boolean b && !b) yield false;
                if (value instanceof Character c && c == '\0') yield false;
                if (value instanceof String s && s.isEmpty()) yield false;
                yield true;
            }
        };
    }

    boolean hasSetter() { return setter != null; }
    JqInclude.Include inclusion() { return inclusion; }
    String name() { return name; }
    /** The JSON key name for serialization (may differ from name() due to @JqNaming). */
    String jsonName() { return jsonName; }
    Class<?> type() { return type; }
    Type genericType() { return genericType; }
    int constructorIndex() { return constructorIndex; }
    boolean isIgnored() { return ignored; }
    /** True if this field uses a JqProgram (from @JqField), false if direct field name. */
    boolean usesProgram() { return program != null; }
}

package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Type;

/**
 * Mapping metadata for a single record component / class field.
 * Holds the compiled jq program for extraction, the MethodHandle for
 * reading the field value (serialization), and type information for conversion.
 *
 * <p>Instances are created once per field during class introspection and
 * cached in the parent {@link ClassMapping}.</p>
 */
final class FieldMapping {

    private final String name;            // Java field/component name (also the JSON key for serialization)
    private final JqProgram program;      // compiled jq expression for deserialization
    private final Class<?> type;          // target Java type (e.g., String.class, int.class)
    private final Type genericType;       // generic type (e.g., List<Item>, Map<String, Integer>)
    private final MethodHandle getter;    // reads the field value from an instance (for serialization)
    private final int constructorIndex;   // index in the canonical constructor parameter list
    private final boolean ignored;        // true if @JqIgnore is present

    FieldMapping(String name, JqProgram program, Class<?> type, Type genericType,
                 MethodHandle getter, int constructorIndex, boolean ignored) {
        this.name = name;
        this.program = program;
        this.type = type;
        this.genericType = genericType;
        this.getter = getter;
        this.constructorIndex = constructorIndex;
        this.ignored = ignored;
    }

    /** Extract the field value from a JqValue using the compiled jq program. */
    JqValue extract(JqValue input) {
        if (ignored) return null;
        return program.apply(input);
    }

    /** Convert the extracted JqValue to the target Java type. */
    Object convert(JqValue extracted, JqMapper mapper) {
        if (ignored) return TypeConverter.defaultValue(type);
        return TypeConverter.toJava(extracted, type, genericType, mapper);
    }

    /** Read the field value from a Java object instance (for serialization). */
    Object readValue(Object instance) {
        try {
            return getter.invoke(instance);
        } catch (Throwable e) {
            throw new JqMapperException("Failed to read field '" + name + "' from " + instance.getClass().getName(), e);
        }
    }

    String name() { return name; }
    Class<?> type() { return type; }
    Type genericType() { return genericType; }
    int constructorIndex() { return constructorIndex; }
    boolean isIgnored() { return ignored; }
}

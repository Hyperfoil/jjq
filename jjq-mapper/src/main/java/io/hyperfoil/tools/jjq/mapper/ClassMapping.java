package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.value.*;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;

/**
 * Cached mapping metadata for a single Java class (record).
 * Created once per class on first use and cached in {@link JqMapper}.
 *
 * <p>For records, introspects {@link RecordComponent}s to discover field names,
 * types, and annotations. Compiles a {@link JqProgram} per field for extraction
 * and caches {@link MethodHandle}s for the accessor methods and canonical
 * constructor.</p>
 */
final class ClassMapping<T> {

    private final Class<T> type;
    private final FieldMapping[] fields;
    private final MethodHandle constructor; // canonical constructor MethodHandle

    private ClassMapping(Class<T> type, FieldMapping[] fields, MethodHandle constructor) {
        this.type = type;
        this.fields = fields;
        this.constructor = constructor;
    }

    /**
     * Create a ClassMapping by introspecting a record class.
     *
     * @throws JqMapperException if the class is not a record or introspection fails
     */
    @SuppressWarnings("unchecked")
    static <T> ClassMapping<T> forRecord(Class<T> type) {
        if (!type.isRecord()) {
            throw new JqMapperException("Only record types are supported: " + type.getName());
        }

        RecordComponent[] components = type.getRecordComponents();
        if (components == null) {
            throw new JqMapperException("Cannot introspect record components of " + type.getName()
                    + " (GraalVM native-image requires reflection registration)");
        }

        FieldMapping[] fields = new FieldMapping[components.length];
        Class<?>[] ctorParamTypes = new Class<?>[components.length];
        MethodHandles.Lookup lookup = MethodHandles.lookup();

        for (int i = 0; i < components.length; i++) {
            RecordComponent rc = components[i];
            String name = rc.getName();
            Class<?> fieldType = rc.getType();
            Type genericType = rc.getGenericType();
            ctorParamTypes[i] = fieldType;

            // Check for @JqIgnore
            boolean ignored = rc.isAnnotationPresent(JqIgnore.class);

            // Determine extraction strategy:
            // - @JqField: compile the jq expression and use JqProgram.apply()
            // - Default: use JqObject.get(name) directly (faster — no JqProgram overhead)
            String directFieldName;
            JqProgram program;
            JqField jqFieldAnnotation = rc.getAnnotation(JqField.class);
            if (!ignored && jqFieldAnnotation != null) {
                directFieldName = null;
                program = JqProgram.compile(jqFieldAnnotation.value());
            } else {
                directFieldName = name;
                program = null;
            }

            // Create MethodHandle for the accessor method (e.g., record.name())
            MethodHandle getter;
            try {
                getter = lookup.unreflect(rc.getAccessor());
            } catch (IllegalAccessException e) {
                throw new JqMapperException("Cannot access record component accessor: " + name, e);
            }

            fields[i] = new FieldMapping(name, directFieldName, program, fieldType, genericType, getter, i, ignored);
        }

        // Find and cache the canonical constructor
        MethodHandle ctor;
        try {
            Constructor<T> javaConstructor = type.getDeclaredConstructor(ctorParamTypes);
            javaConstructor.setAccessible(true);
            ctor = lookup.unreflectConstructor(javaConstructor);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new JqMapperException("Cannot find canonical constructor for record " + type.getName(), e);
        }

        return new ClassMapping<>(type, fields, ctor);
    }

    /**
     * Deserialize a JqValue into an instance of this class.
     */
    @SuppressWarnings("unchecked")
    T fromJqValue(JqValue value, JqMapper mapper) {
        Object[] args = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) {
            FieldMapping field = fields[i];
            JqValue extracted = field.extract(value);
            args[field.constructorIndex()] = field.convert(extracted, mapper);
        }
        try {
            return (T) constructor.invokeWithArguments(args);
        } catch (Throwable e) {
            throw new JqMapperException("Failed to construct " + type.getName(), e);
        }
    }

    /**
     * Serialize a Java object instance to a JqValue.
     */
    JqValue toJqValue(Object instance, JqMapper mapper) {
        var builder = JqObject.builder(fields.length);
        for (FieldMapping field : fields) {
            if (field.isIgnored()) continue;
            Object value = field.readValue(instance);
            builder.put(field.name(), TypeConverter.toJqValue(value, mapper));
        }
        return builder.build();
    }

    Class<T> type() { return type; }
    FieldMapping[] fields() { return fields; }
}

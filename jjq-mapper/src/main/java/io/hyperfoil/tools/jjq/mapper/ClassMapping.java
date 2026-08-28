package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.value.*;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * Cached mapping metadata for a single Java class (record).
 * Created once per class on first use and cached in {@link JqMapper}.
 *
 * <p>For records, introspects {@link RecordComponent}s to discover field names,
 * types, and annotations. Compiles a {@link JqProgram} per field for extraction
 * and caches {@link MethodHandle}s for the accessor methods and canonical
 * constructor.</p>
 *
 * <p>Fast path: when the input JqObject has the same number of fields as the
 * record and all field names match (no @JqField overrides, no @JqIgnore),
 * uses a single-pass {@code forEach} iteration over the object's parallel
 * arrays instead of N separate {@code get()} lookups. This eliminates per-field
 * linear scan or hash lookup overhead.</p>
 */
final class ClassMapping<T> implements Mapping<T> {

    private final Class<T> type;
    private final FieldMapping[] fields;
    private final MethodHandle constructor; // canonical constructor MethodHandle
    // Fast-path: name→index map for single-pass forEach extraction.
    // Non-null only when all fields use direct field names (no @JqField, no @JqIgnore).
    private final Map<String, Integer> nameToIndex;
    private final boolean useForEachFastPath;

    private ClassMapping(Class<T> type, FieldMapping[] fields, MethodHandle constructor,
                         Map<String, Integer> nameToIndex, boolean useForEachFastPath) {
        this.type = type;
        this.fields = fields;
        this.constructor = constructor;
        this.nameToIndex = nameToIndex;
        this.useForEachFastPath = useForEachFastPath;
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
        MethodHandles.Lookup lookup;
        try {
            lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());
        } catch (IllegalAccessException e) {
            throw new JqMapperException("Cannot create private lookup for " + type.getName(), e);
        }

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

        // Build fast-path name→index map if all fields use direct field names
        // (no @JqField overrides, no @JqIgnore)
        boolean canUseFastPath = true;
        Map<String, Integer> nameMap = new HashMap<>(fields.length);
        for (int i = 0; i < fields.length; i++) {
            if (fields[i].isIgnored() || fields[i].usesProgram()) {
                canUseFastPath = false;
                break;
            }
            nameMap.put(fields[i].name(), i);
        }

        return new ClassMapping<>(type, fields, ctor,
                canUseFastPath ? nameMap : null, canUseFastPath);
    }

    /**
     * Deserialize a JqValue into an instance of this class.
     *
     * <p>Fast path: when the input is a JqObject with matching field count and
     * all fields use direct names (no @JqField/@JqIgnore), uses a single-pass
     * forEach iteration. This avoids N separate get() lookups — the object's
     * internal parallel arrays are iterated once, and each field is matched
     * by name to the constructor parameter index.</p>
     */
    @SuppressWarnings("unchecked")
    @Override
    public T fromJqValue(JqValue value, JqMapper mapper) {
        // Fast path: single-pass iteration over the object's entries.
        // Instead of N separate get() lookups, iterate all entries once and
        // match each key to a constructor parameter index via the nameToIndex map.
        if (useForEachFastPath && value instanceof JqObject obj && obj.size() == fields.length) {
            Object[] args = forEachExtract(obj, mapper);
            if (args != null) {
                try {
                    return (T) constructor.invokeWithArguments(args);
                } catch (Throwable e) {
                    throw new JqMapperException("Failed to construct " + type.getName(), e);
                }
            }
            // Fall through to per-field extraction if names didn't match
        }

        // Standard path: per-field extraction via get() or JqProgram
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
    @Override
    public JqValue toJqValue(T instance, JqMapper mapper) {
        var builder = JqObject.builder(fields.length);
        for (FieldMapping field : fields) {
            if (field.isIgnored()) continue;
            Object value = field.readValue(instance);
            builder.put(field.name(), TypeConverter.toJqValue(value, mapper));
        }
        return builder.build();
    }

    /**
     * Single-pass extraction using entrySet iteration. Returns the populated
     * args array, or null if any key didn't match a field name.
     */
    private Object[] forEachExtract(JqObject obj, JqMapper mapper) {
        Object[] args = new Object[fields.length];
        for (var entry : obj.objectValue().entrySet()) {
            Integer idx = nameToIndex.get(entry.getKey());
            if (idx == null) return null; // unknown key — fall back to standard path
            args[idx] = fields[idx].convert(entry.getValue(), mapper);
        }
        return args;
    }

    Class<T> type() { return type; }
    FieldMapping[] fields() { return fields; }
}

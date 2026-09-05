package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.value.*;

import io.hyperfoil.tools.jjq.mapper.spi.AnnotationBridge;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    static <T> ClassMapping<T> forRecord(Class<T> type, List<AnnotationBridge> bridges) {
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

        // Resolve class-level annotations: jjq-native first, then bridges
        JqInclude.Include classInclusion = resolveClassInclusion(type);
        JqNaming.Strategy namingStrategy = resolveNamingStrategy(type);
        // Bridges can override class-level settings
        for (AnnotationBridge bridge : bridges) {
            if (classInclusion == JqInclude.Include.ALWAYS) {
                JqInclude.Include bridgeInclusion = bridge.resolveInclusion(type);
                if (bridgeInclusion != null) classInclusion = bridgeInclusion;
            }
            if (namingStrategy == JqNaming.Strategy.IDENTITY) {
                JqNaming.Strategy bridgeNaming = bridge.resolveNaming(type);
                if (bridgeNaming != null) namingStrategy = bridgeNaming;
            }
        }

        for (int i = 0; i < components.length; i++) {
            RecordComponent rc = components[i];
            String name = rc.getName();
            Class<?> fieldType = rc.getType();
            Type genericType = rc.getGenericType();
            ctorParamTypes[i] = fieldType;

            // Check for @JqIgnore, then bridge isIgnored
            boolean ignored = rc.isAnnotationPresent(JqIgnore.class);
            if (!ignored) {
                for (AnnotationBridge bridge : bridges) {
                    if (bridge.isIgnored(rc)) { ignored = true; break; }
                }
            }

            // Apply naming strategy to get the JSON name
            String jsonName = namingStrategy.transform(name);

            // Determine extraction strategy:
            // 1. @JqField: compile the jq expression
            // 2. Bridge resolveFieldName: use as JSON name
            // 3. Default: use naming-transformed name
            String directFieldName;
            JqProgram program;
            JqField jqFieldAnnotation = rc.getAnnotation(JqField.class);
            if (!ignored && jqFieldAnnotation != null) {
                directFieldName = null;
                program = JqProgram.compile(jqFieldAnnotation.value());
                jsonName = name; // @JqField overrides naming strategy
            } else if (!ignored) {
                // Check bridges for field name override
                String bridgeName = resolveBridgeFieldName(rc, bridges);
                if (bridgeName != null) {
                    jsonName = bridgeName;
                }
                directFieldName = jsonName;
                program = null;
            } else {
                directFieldName = jsonName;
                program = null;
            }

            // Resolve field-level @JqInclude, then bridge inclusion
            JqInclude fieldInclude = rc.getAnnotation(JqInclude.class);
            JqInclude.Include inclusion = fieldInclude != null ? fieldInclude.value() : classInclusion;
            if (fieldInclude == null) {
                for (AnnotationBridge bridge : bridges) {
                    JqInclude.Include bridgeInclusion = bridge.resolveInclusion(rc);
                    if (bridgeInclusion != null) { inclusion = bridgeInclusion; break; }
                }
            }

            // Create MethodHandle for the accessor method (e.g., record.name())
            MethodHandle getter;
            try {
                getter = lookup.unreflect(rc.getAccessor());
            } catch (IllegalAccessException e) {
                throw new JqMapperException("Cannot access record component accessor: " + name, e);
            }

            // Resolve @JqConverter
            ValueConverter<?> converter = resolveConverter(rc.getAnnotation(JqConverter.class));

            fields[i] = new FieldMapping(name, jsonName, directFieldName, program, fieldType, genericType,
                    getter, null, i, ignored, inclusion, converter);
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
            nameMap.put(fields[i].jsonName(), i);
        }

        return new ClassMapping<>(type, fields, ctor,
                canUseFastPath ? nameMap : null, canUseFastPath);
    }

    /**
     * Create a ClassMapping by introspecting a POJO class (non-record).
     * Uses no-arg constructor + setter methods (or direct public field access) for deserialization,
     * and getter methods (or direct public field access) for serialization.
     *
     * <p>Property discovery priority for each field:</p>
     * <ol>
     *   <li>Public field — direct read/write, no getter/setter needed</li>
     *   <li>Getter/setter methods — {@code getFieldName()}/{@code setFieldName(Type)},
     *       with {@code isFieldName()} for booleans</li>
     *   <li>{@code setAccessible(true)} — last resort for private fields without accessors</li>
     * </ol>
     *
     * <p>Only declared fields are discovered (no superclass inheritance in v1).</p>
     *
     * @throws JqMapperException if the class has no no-arg constructor or introspection fails
     */
    @SuppressWarnings("unchecked")
    static <T> ClassMapping<T> forClass(Class<T> type, List<AnnotationBridge> bridges) {
        MethodHandles.Lookup lookup;
        try {
            lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());
        } catch (IllegalAccessException e) {
            throw new JqMapperException("Cannot create private lookup for " + type.getName(), e);
        }

        // Find no-arg constructor
        MethodHandle ctor;
        try {
            Constructor<T> noArgCtor = type.getDeclaredConstructor();
            noArgCtor.setAccessible(true);
            ctor = lookup.unreflectConstructor(noArgCtor);
        } catch (NoSuchMethodException e) {
            throw new JqMapperException("POJO " + type.getName() + " requires a no-arg constructor", e);
        } catch (IllegalAccessException e) {
            throw new JqMapperException("Cannot access no-arg constructor of " + type.getName(), e);
        }

        // Resolve class-level annotations: jjq-native first, then bridges
        JqInclude.Include classInclusion = resolveClassInclusion(type);
        JqNaming.Strategy namingStrategy = resolveNamingStrategy(type);
        for (AnnotationBridge bridge : bridges) {
            if (classInclusion == JqInclude.Include.ALWAYS) {
                JqInclude.Include bridgeInclusion = bridge.resolveInclusion(type);
                if (bridgeInclusion != null) classInclusion = bridgeInclusion;
            }
            if (namingStrategy == JqNaming.Strategy.IDENTITY) {
                JqNaming.Strategy bridgeNaming = bridge.resolveNaming(type);
                if (bridgeNaming != null) namingStrategy = bridgeNaming;
            }
        }

        // Discover fields (declared only, skip static/synthetic/transient)
        var fieldMappings = new ArrayList<FieldMapping>();
        for (Field field : type.getDeclaredFields()) {
            int mods = field.getModifiers();
            if (Modifier.isStatic(mods) || field.isSynthetic() || Modifier.isTransient(mods)) continue;

            String name = field.getName();
            Class<?> fieldType = field.getType();
            Type genericType = field.getGenericType();

            // Apply naming strategy
            String jsonName = namingStrategy.transform(name);

            // Check annotations: jjq-native first, then bridges
            boolean ignored = field.isAnnotationPresent(JqIgnore.class);
            if (!ignored) {
                for (AnnotationBridge bridge : bridges) {
                    if (bridge.isIgnored(field)) { ignored = true; break; }
                }
            }
            String directFieldName;
            JqProgram program;
            JqField jqFieldAnnotation = field.getAnnotation(JqField.class);
            if (!ignored && jqFieldAnnotation != null) {
                directFieldName = null;
                program = JqProgram.compile(jqFieldAnnotation.value());
                jsonName = name; // @JqField overrides naming strategy
            } else if (!ignored) {
                // Check bridges for field name override
                String bridgeName = resolveBridgeFieldName(field, bridges);
                if (bridgeName != null) {
                    jsonName = bridgeName;
                }
                directFieldName = jsonName;
                program = null;
            } else {
                directFieldName = jsonName;
                program = null;
            }

            // Resolve getter and setter using priority: public field → getter/setter → setAccessible
            MethodHandle getter = null;
            MethodHandle setter = null;
            boolean isPublic = Modifier.isPublic(mods);

            if (isPublic) {
                // Public field — direct access
                try {
                    getter = lookup.unreflectGetter(field);
                    if (!Modifier.isFinal(mods)) {
                        setter = lookup.unreflectSetter(field);
                    }
                } catch (IllegalAccessException e) {
                    throw new JqMapperException("Cannot access public field " + name, e);
                }
            }

            // Try getter/setter methods
            if (getter == null) {
                getter = findGetter(type, name, fieldType, lookup);
            }
            if (setter == null && !Modifier.isFinal(mods)) {
                setter = findSetter(type, name, fieldType, lookup);
            }

            // Last resort: setAccessible on the field itself
            if (getter == null) {
                try {
                    field.setAccessible(true);
                    getter = lookup.unreflectGetter(field);
                } catch (Exception e) {
                    throw new JqMapperException("Cannot access field '" + name + "' on " + type.getName()
                            + ". Add a public getter, make the field public, or use --add-opens.", e);
                }
            }
            if (setter == null && !Modifier.isFinal(mods)) {
                try {
                    field.setAccessible(true);
                    setter = lookup.unreflectSetter(field);
                } catch (Exception e) {
                    // Setter is optional — field may be read-only for serialization
                }
            }

            // Resolve field-level @JqInclude, then bridge inclusion
            JqInclude fieldInclude = field.getAnnotation(JqInclude.class);
            JqInclude.Include inclusion = fieldInclude != null ? fieldInclude.value() : classInclusion;
            if (fieldInclude == null) {
                for (AnnotationBridge bridge : bridges) {
                    JqInclude.Include bridgeInclusion = bridge.resolveInclusion(field);
                    if (bridgeInclusion != null) { inclusion = bridgeInclusion; break; }
                }
            }

            // Resolve @JqConverter
            ValueConverter<?> converter = resolveConverter(field.getAnnotation(JqConverter.class));

            fieldMappings.add(new FieldMapping(name, jsonName, directFieldName, program,
                    fieldType, genericType, getter, setter, -1, ignored, inclusion, converter));
        }

        FieldMapping[] fields = fieldMappings.toArray(new FieldMapping[0]);

        // Build fast-path name→index map (same logic as records)
        boolean canUseFastPath = true;
        Map<String, Integer> nameMap = new HashMap<>(fields.length);
        for (int i = 0; i < fields.length; i++) {
            if (fields[i].isIgnored() || fields[i].usesProgram()) {
                canUseFastPath = false;
                break;
            }
            nameMap.put(fields[i].jsonName(), i);
        }

        return new ClassMapping<>(type, fields, ctor,
                canUseFastPath ? nameMap : null, canUseFastPath);
    }

    /** Find a getter method for a field: getFieldName() or isFieldName() for booleans. */
    private static MethodHandle findGetter(Class<?> type, String fieldName, Class<?> fieldType,
                                            MethodHandles.Lookup lookup) {
        String capitalized = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        String[] candidates = (fieldType == boolean.class || fieldType == Boolean.class)
                ? new String[]{"get" + capitalized, "is" + capitalized}
                : new String[]{"get" + capitalized};
        for (String methodName : candidates) {
            try {
                Method m = type.getMethod(methodName);
                return lookup.unreflect(m);
            } catch (NoSuchMethodException | IllegalAccessException ignored) {
                // try next candidate
            }
        }
        return null;
    }

    /** Find a setter method: setFieldName(Type). */
    private static MethodHandle findSetter(Class<?> type, String fieldName, Class<?> fieldType,
                                            MethodHandles.Lookup lookup) {
        String methodName = "set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        try {
            Method m = type.getMethod(methodName, fieldType);
            return lookup.unreflect(m);
        } catch (NoSuchMethodException | IllegalAccessException ignored) {
            return null;
        }
    }

    /**
     * Deserialize a JqValue into an instance of this class.
     *
     * <p>For records: uses positional canonical constructor with optional
     * forEach fast path when all fields are direct field names.</p>
     *
     * <p>For POJOs: creates instance via no-arg constructor, then sets
     * each field via setter method or direct field access.</p>
     */
    @SuppressWarnings("unchecked")
    @Override
    public T fromJqValue(JqValue value, JqMapper mapper) {
        // POJO path: no-arg constructor + setters
        if (fields.length > 0 && fields[0].constructorIndex() < 0) {
            return fromJqValuePojo(value, mapper);
        }

        // Record path: positional constructor
        // Fast path: single-pass iteration over the object's entries.
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

        // Standard record path: per-field extraction via get() or JqProgram
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

    /** POJO deserialization: no-arg constructor + setter calls. */
    @SuppressWarnings("unchecked")
    private T fromJqValuePojo(JqValue value, JqMapper mapper) {
        T instance;
        try {
            instance = (T) constructor.invoke();
        } catch (Throwable e) {
            throw new JqMapperException("Failed to construct " + type.getName(), e);
        }
        for (FieldMapping field : fields) {
            if (field.isIgnored() || !field.hasSetter()) continue;
            JqValue extracted = field.extract(value);
            Object converted = field.convert(extracted, mapper);
            field.writeValue(instance, converted);
        }
        return instance;
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
            if (!field.shouldInclude(value)) continue;
            builder.put(field.jsonName(), field.toJqValue(value, mapper));
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

    /** Consult bridges for a field name override. Returns null if no bridge provides one. */
    private static String resolveBridgeFieldName(AnnotatedElement element, List<AnnotationBridge> bridges) {
        for (AnnotationBridge bridge : bridges) {
            String name = bridge.resolveFieldName(element);
            if (name != null) return name;
        }
        return null;
    }

    /** Resolve class-level @JqInclude, defaulting to ALWAYS. */
    private static JqInclude.Include resolveClassInclusion(Class<?> type) {
        JqInclude classInclude = type.getAnnotation(JqInclude.class);
        return classInclude != null ? classInclude.value() : JqInclude.Include.ALWAYS;
    }

    /** Resolve class-level @JqNaming, defaulting to IDENTITY. */
    private static JqNaming.Strategy resolveNamingStrategy(Class<?> type) {
        JqNaming naming = type.getAnnotation(JqNaming.class);
        return naming != null ? naming.value() : JqNaming.Strategy.IDENTITY;
    }

    /** Instantiate a ValueConverter from a @JqConverter annotation. Returns null if no annotation. */
    private static ValueConverter<?> resolveConverter(JqConverter annotation) {
        if (annotation == null) return null;
        try {
            return annotation.value().getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new JqMapperException("Failed to instantiate converter: " + annotation.value().getName(), e);
        }
    }

    Class<T> type() { return type; }
    FieldMapping[] fields() { return fields; }
}

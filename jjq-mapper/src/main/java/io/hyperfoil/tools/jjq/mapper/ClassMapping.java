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
    // Pre-cached spreader: accepts Object[] and spreads to positional args.
    // Avoids per-call asSpreader/MethodType allocation in invokeWithArguments.
    private final MethodHandle spreadConstructor;
    // Fast-path: name→index map for single-pass forEach extraction.
    // Non-null only when all fields use direct field names (no @JqField, no @JqIgnore).
    private final Map<String, Integer> nameToIndex;
    private final boolean useForEachFastPath;
    // Unknown-key capture / extra-field emission (null when no any-setter/getter present).
    // Gated checks keep the zero-cost profile for classes without them.
    private final AnyHandlers anyHandlers;

    /**
     * Cached any-setter/any-getter handles plus the known-field names used to
     * recognize unknown keys. Null when the class declares neither.
     */
    private record AnyHandlers(MethodHandle setter, boolean setterTakesJqValue,
                               MethodHandle getter, boolean getterReturnsMap,
                               java.util.Set<String> knownNames) {}

    private ClassMapping(Class<T> type, FieldMapping[] fields, MethodHandle constructor,
                         MethodHandle spreadConstructor,
                         Map<String, Integer> nameToIndex, boolean useForEachFastPath,
                         AnyHandlers anyHandlers) {
        this.type = type;
        this.fields = fields;
        this.constructor = constructor;
        this.spreadConstructor = spreadConstructor;
        this.nameToIndex = nameToIndex;
        this.useForEachFastPath = useForEachFastPath;
        this.anyHandlers = anyHandlers;
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

        // Any-setter/getter methods first: any-getter-backed properties are
        // suppressed entirely (issue #88.1, Jackson parity)
        Method[] anyMethods = resolveAnyMethods(type, bridges);
        Method anyGetter = anyMethods[1];

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
            // Any-getter-backed components are suppressed entirely (issue #88.1)
            if (!ignored && isAnyGetterBacked(name, rc.getAccessor(), anyGetter)) ignored = true;

            // Apply naming strategy to get the JSON name
            String jsonName = namingStrategy.transform(name);

            // Determine extraction strategy:
            // 1. @JqField: compile the jq expression (@JqName conflicts: fail fast)
            // 2. @JqName: explicit wire name with direct lookup
            // 3. Bridge resolveFieldName: use as JSON name
            // 4. Default: use naming-transformed name
            String directFieldName;
            JqProgram program;
            JqField jqFieldAnnotation = rc.getAnnotation(JqField.class);
            JqName jqNameAnnotation = rc.getAnnotation(JqName.class);
            if (!ignored && jqFieldAnnotation != null && hasWireName(jqNameAnnotation)) {
                throw new JqMapperException("@JqName and @JqField conflict on '" + name
                        + "' of " + type.getName() + ": rename the key or query it, not both");
            }
            if (!ignored && jqFieldAnnotation != null) {
                directFieldName = null;
                program = JqProgram.compile(jqFieldAnnotation.value());
                jsonName = name; // @JqField overrides naming strategy
            } else if (!ignored && hasWireName(jqNameAnnotation)) {
                // Explicit rename wins over naming strategy and bridges
                jsonName = jqNameAnnotation.value();
                directFieldName = jsonName;
                program = null;
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

            // Resolve @JqAdapter (backs the same custom-converter slot) or @JqConverter
            ValueConverter<?> converter = resolveAdapter(rc, fieldType, type, name);
            if (converter == null) converter = resolveConverter(rc.getAnnotation(JqConverter.class));

            // Directional exclusion: native @JqAccess first, then bridges
            // (e.g. Jackson WRITE_ONLY/READ_ONLY). Native wins (issue #113).
            JqAccess jqAccess = rc.getAnnotation(JqAccess.class);
            boolean skipSer = jqAccess != null && jqAccess.value() == JqAccess.Access.WRITE_ONLY;
            boolean skipDeser = jqAccess != null && jqAccess.value() == JqAccess.Access.READ_ONLY;
            for (AnnotationBridge bridge : bridges) {
                if (!skipSer && bridge.skipOnSerialize(rc)) skipSer = true;
                if (!skipDeser && bridge.skipOnDeserialize(rc)) skipDeser = true;
            }

            fields[i] = new FieldMapping(name, jsonName, directFieldName, program, fieldType, genericType,
                    getter, null, i, ignored, inclusion, converter, skipSer, skipDeser);
        }

        // Find and cache the canonical constructor + pre-cached spreader
        MethodHandle ctor;
        MethodHandle spread;
        try {
            Constructor<T> javaConstructor = type.getDeclaredConstructor(ctorParamTypes);
            javaConstructor.setAccessible(true);
            ctor = lookup.unreflectConstructor(javaConstructor);
            spread = ctor.asSpreader(Object[].class, components.length);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new JqMapperException("Cannot find canonical constructor for record " + type.getName(), e);
        }

        // Build fast-path name→index map if all fields use direct field names
        // (no @JqField overrides, no @JqIgnore)
        boolean canUseFastPath = true;
        Map<String, Integer> nameMap = new HashMap<>(fields.length);
        for (int i = 0; i < fields.length; i++) {
            if (fields[i].isIgnored() || fields[i].usesProgram() || fields[i].skipDeserialize()) {
                canUseFastPath = false;
                break;
            }
            nameMap.put(fields[i].jsonName(), i);
        }

        return new ClassMapping<>(type, fields, ctor, spread,
                canUseFastPath ? nameMap : null, canUseFastPath,
                resolveAnyHandlers(type, fields, anyMethods[0], anyMethods[1], lookup));
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

        // Any-setter/getter methods first: any-getter-backed properties are
        // suppressed entirely (issue #88.1, Jackson parity)
        Method[] anyMethods = resolveAnyMethods(type, bridges);
        Method anyGetter = anyMethods[1];

        // Getter visibility per class: native @JqVisibility NONE suppresses
        // unconditionally; bridges still apply under native ANY (issue #114)
        JqVisibility visibility = type.getAnnotation(JqVisibility.class);
        boolean useGetters = visibility == null
                || visibility.getters() == JqVisibility.Visibility.ANY;
        boolean useIsGetters = visibility == null
                || visibility.isGetters() == JqVisibility.Visibility.ANY;
        for (AnnotationBridge bridge : bridges) {
            if (useGetters && !bridge.includeGetters(type)) useGetters = false;
            if (useIsGetters && !bridge.includeIsGetters(type)) useIsGetters = false;
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
            // Any-getter-backed properties are suppressed entirely (issue #88.1).
            // Public fields use direct access (no getter method): the implied
            // property-name rule covers them; method getters match by identity.
            if (!ignored && anyGetter != null) {
                Method gm = Modifier.isPublic(mods) ? null : findGetterMethod(type, name, fieldType);
                if (isAnyGetterBacked(name, gm, anyGetter)) ignored = true;
            }
            String directFieldName;
            JqProgram program;
            JqField jqFieldAnnotation = field.getAnnotation(JqField.class);
            JqName jqNameAnnotation = field.getAnnotation(JqName.class);
            if (!ignored && jqFieldAnnotation != null && hasWireName(jqNameAnnotation)) {
                throw new JqMapperException("@JqName and @JqField conflict on '" + name
                        + "' of " + type.getName() + ": rename the key or query it, not both");
            }
            if (!ignored && jqFieldAnnotation != null) {
                directFieldName = null;
                program = JqProgram.compile(jqFieldAnnotation.value());
                jsonName = name; // @JqField overrides naming strategy
            } else if (!ignored && hasWireName(jqNameAnnotation)) {
                // Explicit rename wins over naming strategy and bridges
                jsonName = jqNameAnnotation.value();
                directFieldName = jsonName;
                program = null;
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
                getter = findGetter(type, name, fieldType, lookup, useGetters, useIsGetters);
            }
            if (setter == null && !Modifier.isFinal(mods)) {
                setter = findSetter(type, name, fieldType, lookup);
            }

            // Last resort: setAccessible on the field itself, unless Jackson
            // parity applies (issue #95.2): a non-public field with neither a
            // getter nor a setter method is not a property unless explicitly
            // named (@JqField or a bridge rename such as @JsonProperty, which
            // opts the field in). Setter-backed fields stay bound (explicit
            // field-visibility configs rely on them).
            if (!isPublic && getter == null && setter == null && jqFieldAnnotation == null
                    && resolveBridgeFieldName(field, bridges) == null) {
                continue;
            }
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

            // Resolve @JqAdapter (backs the same custom-converter slot) or @JqConverter
            ValueConverter<?> converter = resolveAdapter(field, fieldType, type, name);
            if (converter == null) converter = resolveConverter(field.getAnnotation(JqConverter.class));

            // Directional exclusion: native @JqAccess first, then bridges.
            // Native wins (issue #113).
            JqAccess fieldAccess = field.getAnnotation(JqAccess.class);
            boolean skipSer = fieldAccess != null
                    && fieldAccess.value() == JqAccess.Access.WRITE_ONLY;
            boolean skipDeser = fieldAccess != null
                    && fieldAccess.value() == JqAccess.Access.READ_ONLY;
            for (AnnotationBridge bridge : bridges) {
                if (!skipSer && bridge.skipOnSerialize(field)) skipSer = true;
                if (!skipDeser && bridge.skipOnDeserialize(field)) skipDeser = true;
            }

            fieldMappings.add(new FieldMapping(name, jsonName, directFieldName, program,
                    fieldType, genericType, getter, setter, -1, ignored, inclusion, converter, skipSer, skipDeser));
        }

        FieldMapping[] fields = fieldMappings.toArray(new FieldMapping[0]);

        // Build fast-path name→index map (same logic as records)
        boolean canUseFastPath = true;
        Map<String, Integer> nameMap = new HashMap<>(fields.length);
        for (int i = 0; i < fields.length; i++) {
            if (fields[i].isIgnored() || fields[i].usesProgram() || fields[i].skipDeserialize()) {
                canUseFastPath = false;
                break;
            }
            nameMap.put(fields[i].jsonName(), i);
        }

        return new ClassMapping<>(type, fields, ctor, null,
                canUseFastPath ? nameMap : null, canUseFastPath,
                resolveAnyHandlers(type, fields, anyMethods[0], anyMethods[1], lookup));
    }

    /** Find a getter method for a field: getFieldName() or isFieldName() for booleans. Returns the Method (unreflect at site). */
    private static Method findGetterMethod(Class<?> type, String fieldName, Class<?> fieldType) {
        String capitalized = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        String[] candidates = (fieldType == boolean.class || fieldType == Boolean.class)
                ? new String[]{"get" + capitalized, "is" + capitalized}
                : new String[]{"get" + capitalized};
        for (String methodName : candidates) {
            try {
                return type.getMethod(methodName);
            } catch (NoSuchMethodException ignored) {
                // try next candidate
            }
        }
        return null;
    }

    /** Find a getter method for a field: getFieldName() or isFieldName() for booleans. */
    private static MethodHandle findGetter(Class<?> type, String fieldName, Class<?> fieldType,
                                            MethodHandles.Lookup lookup,
                                            boolean useGet, boolean useIs) {
        String capitalized = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        java.util.List<String> candidates = new java.util.ArrayList<>(2);
        if (useGet) candidates.add("get" + capitalized);
        if (useIs && (fieldType == boolean.class || fieldType == Boolean.class)) {
            candidates.add("is" + capitalized);
        }
        for (String methodName : candidates) {
            try {
                return lookup.unreflect(type.getMethod(methodName));
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
        // POJO path: no-arg constructor + setters. Empty non-record mappings
        // (all fields invisible, issue #95.2) also construct this way.
        if ((fields.length > 0 && fields[0].constructorIndex() < 0)
                || (fields.length == 0 && !type.isRecord())) {
            return fromJqValuePojo(value, mapper);
        }

        // Records and @JqMapped POJOs bind from objects (null stays lenient).
        // Scalar/array documents with only direct field lookups are shape mismatches;
        // @JqField programs may legitimately extract from any shape, so those stay lenient.
        if (value != null && !(value instanceof JqNull) && !(value instanceof JqObject) && allDirectFields()) {
            throw TypeConverter.mismatch(JqValue.Type.OBJECT, "object for " + type.getSimpleName(), value);
        }

        // Record path: positional constructor
        // Fast path: single-pass iteration over the object's entries.
        if (useForEachFastPath && value instanceof JqObject obj && obj.size() == fields.length) {
            Object[] args = forEachExtract(obj, mapper);
            if (args != null) {
                T created;
                try {
                    created = (T) spreadConstructor.invoke(args);
                } catch (Throwable e) {
                    throw new JqMapperException("Failed to construct " + type.getName(), e);
                }
                applyAnySetter(created, obj);
                return created;
            }
            // Fall through to per-field extraction if names didn't match
        }

        // Standard record path: per-field extraction via get() or JqProgram
        Object[] args = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) {
            FieldMapping field = fields[i];
            try {
                JqValue extracted = field.extract(value);
                args[field.constructorIndex()] = field.convert(extracted, mapper);
            } catch (JqMapperException e) {
                throw e.prependPath(field.jsonName());
            }
        }
        T created;
        try {
            created = (T) spreadConstructor.invoke(args);
        } catch (Throwable e) {
            throw new JqMapperException("Failed to construct " + type.getName(), e);
        }
        applyAnySetter(created, value);
        return created;
    }

    /** True when every mapped field uses a direct name lookup (no @JqField programs). */
    private boolean allDirectFields() {
        for (FieldMapping field : fields) {
            if (!field.isIgnored() && field.usesProgram()) return false;
        }
        return true;
    }

    /**
     * Discover any-setter/any-getter methods (native annotations first, then bridges)
     * and unreflect them. Returns null when neither is present.
     */
    /**
     * Discover any-setter/any-getter methods (native annotations first, then bridges).
     * Returns {@code {setter, getter}} with nulls for absent methods.
     */
    private static Method[] resolveAnyMethods(Class<?> type, List<AnnotationBridge> bridges) {
        Method setterMethod = null;
        for (Method m : type.getMethods()) {
            if (m.isAnnotationPresent(JqAnySetter.class)) {
                validateAnySetter(m, type);
                setterMethod = m;
                break;
            }
        }
        if (setterMethod == null) {
            for (AnnotationBridge bridge : bridges) {
                Method m = bridge.resolveAnySetter(type);
                if (m != null) {
                    validateAnySetter(m, type);
                    setterMethod = m;
                    break;
                }
            }
        }
        Method getterMethod = null;
        for (Method m : type.getMethods()) {
            if (m.isAnnotationPresent(JqAnyGetter.class)) {
                validateAnyGetter(m, type);
                getterMethod = m;
                break;
            }
        }
        if (getterMethod == null) {
            for (AnnotationBridge bridge : bridges) {
                Method m = bridge.resolveAnyGetter(type);
                if (m != null) {
                    validateAnyGetter(m, type);
                    getterMethod = m;
                    break;
                }
            }
        }
        return new Method[]{setterMethod, getterMethod};
    }

    /**
     * True when a property is backed by the any-getter method (issue #88.1):
     * the resolved getter is the any-getter itself, or the field name matches
     * the any-getter's implied property (covers direct field access, which has
     * no getter method). Such properties are suppressed entirely (Jackson parity).
     */
    private static boolean isAnyGetterBacked(String fieldName, Method getterMethod, Method anyGetter) {
        if (anyGetter == null) return false;
        if (getterMethod != null && getterMethod.equals(anyGetter)) return true;
        return fieldName.equals(anyGetterPropertyName(anyGetter.getName()));
    }

    /** Implied property name of an any-getter: strip get/is prefix, decapitalize. Null when no prefix. */
    private static String anyGetterPropertyName(String methodName) {
        String stripped = null;
        if (methodName.startsWith("get") && methodName.length() > 3) {
            stripped = methodName.substring(3);
        } else if (methodName.startsWith("is") && methodName.length() > 2) {
            stripped = methodName.substring(2);
        }
        if (stripped == null || stripped.isEmpty()) return null;
        return Character.toLowerCase(stripped.charAt(0)) + stripped.substring(1);
    }

    private static AnyHandlers resolveAnyHandlers(Class<?> type, FieldMapping[] fields,
                                                  Method setterMethod, Method getterMethod,
                                                  MethodHandles.Lookup lookup) {
        if (setterMethod == null && getterMethod == null) return null;
        MethodHandle setter = null;
        boolean setterTakesJqValue = false;
        java.util.Set<String> knownNames = null;
        if (setterMethod != null) {
            try {
                setter = lookup.unreflect(setterMethod);
            } catch (IllegalAccessException e) {
                throw new JqMapperException("Cannot access any-setter " + setterMethod.getName()
                        + " on " + type.getName(), e);
            }
            setterTakesJqValue = setterMethod.getParameterTypes()[1] == JqValue.class;
            // Known names: every direct-mapped key, including ignored ones (ignored
            // keys are skipped entirely, never forwarded — Jackson parity).
            // @JqField program fields are excluded: their jsonName is not a lookup key.
            knownNames = new java.util.HashSet<>();
            for (FieldMapping field : fields) {
                if (!field.usesProgram()) knownNames.add(field.jsonName());
            }
        }
        MethodHandle getter = null;
        boolean getterReturnsMap = false;
        if (getterMethod != null) {
            try {
                getter = lookup.unreflect(getterMethod);
            } catch (IllegalAccessException e) {
                throw new JqMapperException("Cannot access any-getter " + getterMethod.getName()
                        + " on " + type.getName(), e);
            }
            getterReturnsMap = Map.class.isAssignableFrom(getterMethod.getReturnType());
        }
        return new AnyHandlers(setter, setterTakesJqValue, getter, getterReturnsMap, knownNames);
    }

    /** Fail fast on malformed any-setter signatures (checked once at introspection). */
    private static void validateAnySetter(Method m, Class<?> type) {
        Class<?>[] params = m.getParameterTypes();
        if (Modifier.isStatic(m.getModifiers()) || params.length != 2 || params[0] != String.class
                || (params[1] != JqValue.class && params[1] != Object.class)
                || m.getReturnType() != void.class) {
            throw new JqMapperException("Any-setter must be a non-static void (String, JqValue|Object) method: "
                    + type.getName() + "#" + m.getName());
        }
    }

    /** Fail fast on malformed any-getter signatures (checked once at introspection). */
    private static void validateAnyGetter(Method m, Class<?> type) {
        if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) {
            throw new JqMapperException("Any-getter must be a non-static no-arg method: "
                    + type.getName() + "#" + m.getName());
        }
        Class<?> rt = m.getReturnType();
        if (!Map.class.isAssignableFrom(rt) && !JqObject.class.isAssignableFrom(rt)) {
            throw new JqMapperException("Any-getter must return Map<String, ?> or JqObject: "
                    + type.getName() + "#" + m.getName());
        }
    }

    /** Forward unknown input keys to the any-setter. No-op without one (zero-cost gate). */
    @SuppressWarnings("unchecked")
    private void applyAnySetter(T instance, JqValue value) {
        if (anyHandlers == null || anyHandlers.setter() == null || !(value instanceof JqObject obj)) return;
        for (int i = 0; i < obj.size(); i++) {
            String key = obj.keyAt(i);
            if (anyHandlers.knownNames().contains(key)) continue;
            JqValue v = obj.valueAt(i);
            try {
                if (anyHandlers.setterTakesJqValue()) {
                    anyHandlers.setter().invoke(instance, key, v);
                } else {
                    anyHandlers.setter().invoke(instance, key, v.toJavaObject());
                }
            } catch (Throwable e) {
                throw new JqMapperException("Failed to apply any-setter for key '" + key
                        + "' on " + type.getName(), e);
            }
        }
    }

    /** Emit any-getter entries after mapped fields. No-op without one (zero-cost gate). */
    private void emitAnyGetter(JqObject.Builder builder, T instance, JqMapper mapper) {
        if (anyHandlers == null || anyHandlers.getter() == null) return;
        Object extras;
        try {
            extras = anyHandlers.getter().invoke(instance);
        } catch (Throwable e) {
            throw new JqMapperException("Failed to apply any-getter on " + type.getName(), e);
        }
        if (extras instanceof JqObject jo) {
            jo.forEach(builder::put);
        } else if (extras instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                // toJqValue passes JqValue through and maps null to JqNull
                builder.put(String.valueOf(entry.getKey()),
                        TypeConverter.toJqValue(entry.getValue(), mapper));
            }
        }
    }

    /** POJO deserialization: no-arg constructor + setter calls. */
    @SuppressWarnings("unchecked")
    private T fromJqValuePojo(JqValue value, JqMapper mapper) {
        // Scalar/array documents with only direct field lookups are shape mismatches
        // (same rule as the record path above; @JqField programs stay lenient).
        if (value != null && !(value instanceof JqNull) && !(value instanceof JqObject) && allDirectFields()) {
            throw TypeConverter.mismatch(JqValue.Type.OBJECT, "object for " + type.getSimpleName(), value);
        }
        T instance;
        try {
            instance = (T) constructor.invoke();
        } catch (Throwable e) {
            throw new JqMapperException("Failed to construct " + type.getName(), e);
        }
        for (FieldMapping field : fields) {
            if (field.isIgnored() || field.skipDeserialize() || !field.hasSetter()) continue;
            // Absent keys leave field initializers / constructor defaults in place
            // (Jackson-compatible). Only direct field lookups can prove absence;
            // @JqField program expressions keep write-always semantics.
            // Explicit nulls are still written. has() is null-safe (false for non-objects).
            if (value != null && !field.usesProgram() && !value.has(field.jsonName())) continue;
            JqValue extracted = field.extract(value);
            Object converted;
            try {
                converted = field.convert(extracted, mapper);
            } catch (JqMapperException e) {
                throw e.prependPath(field.jsonName());
            }
            field.writeValue(instance, converted);
        }
        applyAnySetter(instance, value);
        return instance;
    }

    /**
     * Serialize a Java object instance to a JqValue.
     */
    @Override
    public JqValue toJqValue(T instance, JqMapper mapper) {
        var builder = JqObject.builder(fields.length);
        for (FieldMapping field : fields) {
            if (field.isIgnored() || field.skipSerialize()) continue;
            Object value = field.readValue(instance);
            if (!field.shouldInclude(value)) continue;
            // putUnchecked: mapper jsonNames are unique per class by construction
            builder.putUnchecked(field.jsonName(), field.toJqValue(value, mapper));
        }
        // Extra catch-all entries after mapped fields (extras win on collision)
        emitAnyGetter(builder, instance, mapper);
        return builder.build();
    }

    /**
     * Single-pass extraction using indexed access over parallel arrays.
     * Returns the populated args array, or null if any key didn't match a field name.
     * Zero allocation beyond the args array itself — no Iterator, no Map.Entry, no AbstractSet.
     *
     * <p>Uses a positional-first heuristic: checks if the key at position i matches
     * the field at position i (common for in-order JSON). If yes, avoids the HashMap
     * lookup entirely. Falls back to HashMap for out-of-order keys.</p>
     */
    private Object[] forEachExtract(JqObject obj, JqMapper mapper) {
        Object[] args = new Object[fields.length];
        int n = obj.size();
        for (int i = 0; i < n; i++) {
            String key = obj.keyAt(i);
            // Positional-first: if key matches the field at position i, use it directly.
            // String identity check first (interned keys), then equals fallback.
            int idx;
            if (key == fields[i].jsonName() || key.equals(fields[i].jsonName())) {
                idx = i;
            } else {
                Integer mapped = nameToIndex.get(key);
                if (mapped == null) return null; // unknown key — fall back to standard path
                idx = mapped;
            }
            args[idx] = convertIndexed(fields[idx], obj.valueAt(i), mapper);
        }
        return args;
    }

    /** Convert one fast-path value, attributing failures to the matched field. */
    private static Object convertIndexed(FieldMapping field, JqValue extracted, JqMapper mapper) {
        try {
            return field.convert(extracted, mapper);
        } catch (JqMapperException e) {
            throw e.prependPath(field.jsonName());
        }
    }

    /** Consult bridges for a field name override. Returns null if no bridge provides one. */
    private static String resolveBridgeFieldName(AnnotatedElement element, List<AnnotationBridge> bridges) {
        for (AnnotationBridge bridge : bridges) {
            String name = bridge.resolveFieldName(element);
            if (name != null) return name;
        }
        return null;
    }

    /** True when a @JqName carries a non-empty wire name (empty means "no rename"). */
    private static boolean hasWireName(JqName annotation) {
        return annotation != null && !annotation.value().isEmpty();
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

    /**
     * Build a {@code ValueConverter} from a {@code @JqAdapter} annotation by
     * resolving static policy methods on the field's declared type (issue
     * #120). Returns null when no annotation is present. Method handles are
     * resolved once here; binding invokes them without reflective lookup.
     *
     * @param element   the annotated record component or field
     * @param fieldType the declared field type (policy methods resolve against it)
     * @param mappedType the mapped record/class (for package-access rules and errors)
     * @param fieldName the field name (for errors)
     */
    private static ValueConverter<?> resolveAdapter(java.lang.reflect.AnnotatedElement element,
            Class<?> fieldType, Class<?> mappedType, String fieldName) {
        JqAdapter adapter = element.getAnnotation(JqAdapter.class);
        if (adapter == null) return null;
        String where = "'" + fieldName + "' of " + mappedType.getName();
        if (element.getAnnotation(JqConverter.class) != null) {
            throw new JqMapperException("@JqAdapter and @JqConverter conflict on " + where
                    + ": delegate statically or convert, not both");
        }
        if (adapter.from().isEmpty() || adapter.to().isEmpty()) {
            throw new JqMapperException("@JqAdapter on " + where
                    + " must name both from and to methods");
        }
        if (fieldType.isPrimitive() || fieldType.isInterface() || fieldType.isAnnotation()) {
            throw new JqMapperException("@JqAdapter on " + where
                    + " needs a reference-typed field of a concrete class, record, or enum"
                    + " (adapter methods resolve against " + fieldType.getName()
                    + "); use @JqConverter for other shapes");
        }
        String ownerPackage = fieldType.getPackageName();
        String mappedPackage = mappedType.getPackageName();
        boolean samePackage = ownerPackage.equals(mappedPackage);

        Method fromJq = null, fromString = null, toInstance = null, toStatic = null;
        for (Method m : fieldType.getDeclaredMethods()) {
            if (m.isSynthetic() || m.isBridge()) continue;
            if (m.getName().equals(adapter.from())
                    && Modifier.isStatic(m.getModifiers())
                    && m.getParameterCount() == 1
                    && m.getReturnType() != void.class) {
                Class<?> param = m.getParameterTypes()[0];
                if (param == JqValue.class && fromJq == null) fromJq = m;
                else if (param == String.class && fromString == null) fromString = m;
            }
            if (m.getName().equals(adapter.to())) {
                if (!Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0
                        && m.getReturnType() != void.class && toInstance == null) {
                    toInstance = m;
                } else if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1
                        && toStatic == null) {
                    toStatic = m;
                }
            }
        }
        if (fromJq == null && fromString == null) {
            throw new JqMapperException("@JqAdapter on " + where + ": no suitable static '"
                    + adapter.from() + "' on " + fieldType.getName()
                    + " (static (JqValue)->T or (String)->T)");
        }
        Method from = fromJq != null ? fromJq : fromString;
        boolean fromTakesString = fromJq == null;
        if (!boxed(fieldType).isAssignableFrom(boxed(from.getReturnType()))) {
            throw new JqMapperException("@JqAdapter on " + where + ": '" + adapter.from()
                    + "' returns " + from.getReturnType().getName()
                    + ", not assignable to " + fieldType.getName());
        }
        Method to = toInstance != null ? toInstance : toStatic;
        if (to == null) {
            throw new JqMapperException("@JqAdapter on " + where + ": no suitable '"
                    + adapter.to() + "' on " + fieldType.getName()
                    + " (instance ()->JqValue/String or static (T)->JqValue/String)");
        }
        boolean toIsStatic = to == toStatic;
        boolean toReturnsString;
        Class<?> toReturn = to.getReturnType();
        if (toReturn == String.class) {
            toReturnsString = true;
        } else if (JqValue.class.isAssignableFrom(toReturn)) {
            toReturnsString = false;
        } else {
            throw new JqMapperException("@JqAdapter on " + where + ": '" + adapter.to()
                    + "' must return JqValue or String, not " + toReturn.getName());
        }
        if (toIsStatic && !boxed(to.getParameterTypes()[0]).isAssignableFrom(boxed(fieldType))) {
            throw new JqMapperException("@JqAdapter on " + where + ": '" + adapter.to()
                    + "' takes " + to.getParameterTypes()[0].getName()
                    + ", not assignable from " + fieldType.getName());
        }
        for (Method m : new Method[]{from, to}) {
            if (!isAccessibleFrom(m.getModifiers(), samePackage)) {
                throw new JqMapperException("@JqAdapter on " + where + ": '" + m.getName()
                        + "' must be public"
                        + (samePackage ? "" : " (or non-private in " + mappedPackage + ")"));
            }
        }
        final MethodHandle fromHandle;
        final MethodHandle toHandle;
        try {
            MethodHandles.Lookup ownerLookup =
                    MethodHandles.privateLookupIn(fieldType, MethodHandles.lookup());
            fromHandle = ownerLookup.unreflect(from).asType(fromTakesString
                    ? java.lang.invoke.MethodType.methodType(Object.class, String.class)
                    : java.lang.invoke.MethodType.methodType(Object.class, JqValue.class));
            toHandle = ownerLookup.unreflect(to).asType(
                    java.lang.invoke.MethodType.methodType(Object.class, Object.class));
        } catch (IllegalAccessException | java.lang.invoke.WrongMethodTypeException e) {
            throw new JqMapperException("@JqAdapter on " + where + ": cannot access adapter methods", e);
        }
        final String fieldDesc = fieldType.getSimpleName();
        return new ValueConverter<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public Object fromJqValue(JqValue value) {
                if (value == null || value instanceof JqNull) return null;
                try {
                    if (fromTakesString) {
                        String text;
                        if (value instanceof JqString s) {
                            text = s.stringValue();
                        } else if (!value.isContainer()) {
                            text = value.asText();
                        } else {
                            throw TypeConverter.mismatch(JqValue.Type.STRING,
                                    "text for " + fieldDesc, value);
                        }
                        return fromHandle.invokeExact((String) text);
                    }
                    return fromHandle.invokeExact((JqValue) value);
                } catch (JqMapperException e) {
                    throw e;
                } catch (Throwable t) {
                    throw new JqMapperException("Failed to invoke adapter from method", t);
                }
            }

            @Override
            @SuppressWarnings("unchecked")
            public JqValue toJqValue(Object value) {
                if (value == null) return JqNull.NULL;
                try {
                    Object out = toHandle.invokeExact((Object) value);
                    if (out == null) return JqNull.NULL;
                    if (toReturnsString) return JqString.of((String) out);
                    return (JqValue) out;
                } catch (JqMapperException e) {
                    throw e;
                } catch (Throwable t) {
                    throw new JqMapperException("Failed to invoke adapter to method", t);
                }
            }
        };
    }

    /** Boxed equivalent (primitives map to wrappers, references pass through). */
    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }

    /** Public, or non-private within the same package. */
    private static boolean isAccessibleFrom(int modifiers, boolean samePackage) {
        if (Modifier.isPublic(modifiers)) return true;
        if (Modifier.isPrivate(modifiers)) return false;
        return samePackage;
    }

    Class<T> type() { return type; }
    FieldMapping[] fields() { return fields; }
}

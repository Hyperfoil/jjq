package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.mapper.spi.AnnotationBridge;
import io.hyperfoil.tools.jjq.value.BytOutput;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zero-dependency data binding between {@link JqValue} and Java records.
 *
 * <p>Maps Java record components to jq queries — each field compiles to a
 * {@link io.hyperfoil.tools.jjq.JqProgram} that executes in ~3ns for simple
 * field access. Thread-safe: class metadata is cached and immutable.</p>
 *
 * <h2>Deserialization (JqValue to record)</h2>
 * <pre>{@code
 * record User(String name, int age) {}
 *
 * JqMapper mapper = JqMapper.create();
 * JqValue json = JqValues.parse("{\"name\":\"Alice\",\"age\":30}");
 * User user = mapper.fromJqValue(json, User.class);
 * // user.name() == "Alice", user.age() == 30
 * }</pre>
 *
 * <h2>Serialization (record to JqValue)</h2>
 * <pre>{@code
 * JqValue json = mapper.toJqValue(new User("Bob", 25));
 * // {"name":"Bob","age":25}
 * }</pre>
 *
 * <h2>Custom jq expressions</h2>
 * <pre>{@code
 * record PerfResult(
 *     @JqField(".autobench_workload.data[0].results") JqValue results,
 *     @JqField("[.pcp_time_series[] | .\"mem.util.used\"]") List<Double> memUsage,
 *     String user  // defaults to .user
 * ) {}
 *
 * PerfResult r = mapper.fromJqValue(uploadData, PerfResult.class);
 * }</pre>
 *
 * <h2>Nested records and collections</h2>
 * <pre>{@code
 * record Order(String id, List<Item> items) {}
 * record Item(String name, double price) {}
 *
 * Order order = mapper.fromJqValue(json, Order.class);
 * }</pre>
 *
 * <h2>Round-trip</h2>
 * <pre>{@code
 * User original = new User("Alice", 30);
 * JqValue json = mapper.toJqValue(original);
 * User restored = mapper.fromJqValue(json, User.class);
 * // original.equals(restored)
 * }</pre>
 *
 * @see JqField
 * @see JqIgnore
 */
public final class JqMapper {

    private final ConcurrentHashMap<Class<?>, LastHit> cache = new ConcurrentHashMap<>();
    // Single-entry memo for the last resolved mapping: repeated binds of one
    // type (record arrays, repeated config loads) skip the map lookup with one
    // volatile read + ref compare. The holder lives in the map, so misses
    // publish without allocating; the immutable holder publishes atomically,
    // so races are benign (last writer wins with an always-valid pair).
    // Single-entry memo for the last resolved mapping: repeated binds of one
    // type (record arrays, repeated config loads) skip the map lookup with one
    // volatile read + ref compare. Immutable holder publishes atomically, so
    // races are benign (last writer wins with an always-valid pair).
    private volatile LastHit lastHit;

    /** Immutable (type, mapping) pair for {@link #lastHit}. */
    private record LastHit(Class<?> type, Mapping<?> mapping) {}
    private final List<AnnotationBridge> bridges;
    private final boolean failOnUnknownProperties;
    // Resolved @JsonValue/@JsonCreator handlers per enum class (empty = none present).
    private final ConcurrentHashMap<Class<?>, Optional<TypeConverter.EnumHandlers>> enumHandlers =
            new ConcurrentHashMap<>();
    // Compiled enum bindings per enum class (empty = reflective resolution).
    private final ConcurrentHashMap<Class<?>, EnumBinding<?>> compiledEnums = new ConcurrentHashMap<>();

    private JqMapper(List<AnnotationBridge> bridges) {
        this(bridges, false);
    }

    private JqMapper(List<AnnotationBridge> bridges, boolean failOnUnknownProperties) {
        this.bridges = bridges;
        this.failOnUnknownProperties = failOnUnknownProperties;
    }

    /**
     * Whether deserialization fails on unrecognized JSON properties
     * (Jackson {@code FAIL_ON_UNKNOWN_PROPERTIES} parity, opt-in).
     * Disabled by default: unknown keys are ignored (forward compatibility)
     * or forwarded to an any-setter, which always wins over this flag.
     *
     * <p>Generated mappings consult this at runtime, so one flag covers
     * both reflection and codegen paths.</p>
     *
     * @return true when unknown properties fail binding
     */
    public boolean isFailOnUnknownProperties() {
        return failOnUnknownProperties;
    }

    /**
     * The registered annotation bridges (jjq-native annotations are always
     * consulted first; bridges apply in registration order).
     *
     * @return the bridges for this mapper
     */
    List<AnnotationBridge> bridges() {
        return bridges;
    }

    /**
     * Cached {@code @JsonValue}/{@code @JsonCreator} handlers for an enum class.
     * Empty when the class declares neither.
     *
     * @param type the enum class
     * @return the resolved handlers, or empty
     */
    Optional<TypeConverter.EnumHandlers> enumHandlers(Class<?> type) {
        return enumHandlers.computeIfAbsent(type, t -> TypeConverter.resolveEnumHandlers(t, bridges));
    }

    /**
     * Compiled enum binding for a class, or {@code null} when none was
     * registered (reflective resolution applies). Consulted before
     * {@link #enumHandlers(Class)} on every enum conversion path, so direct
     * mapper calls, nested fields, and collections all reach it uniformly.
     *
     * @param type the enum class
     * @return the compiled binding, or {@code null}
     */
    @SuppressWarnings("unchecked")
    <E extends Enum<E>> EnumBinding<E> compiledEnumBinding(Class<E> type) {
        return (EnumBinding<E>) compiledEnums.get(type);
    }

    /**
     * Create a new JqMapper with default settings.
     * Uses {@code Class.forName()} to discover generated mappings at runtime.
     * The mapper is thread-safe and should be reused across calls.
     *
     * <p>For GraalVM native-image or Quarkus native builds, use {@link #builder()}
     * to pre-register generated mappings without reflective discovery.</p>
     *
     * @return a new thread-safe JqMapper
     */
    public static JqMapper create() {
        var bridges = new ArrayList<AnnotationBridge>();
        for (AnnotationBridge bridge : ServiceLoader.load(AnnotationBridge.class)) {
            bridges.add(bridge);
        }
        return new JqMapper(bridges);
    }

    /**
     * Create a builder for constructing a JqMapper with pre-registered mappings.
     * Pre-registered mappings are used directly without {@code Class.forName()}
     * discovery, making the mapper compatible with GraalVM native-image and
     * Quarkus native builds.
     *
     * <p>Example:</p>
     * <pre>{@code
     * JqMapper mapper = JqMapper.builder()
     *     .register(new User_JqMapping())
     *     .register(new PerfSummary_JqMapping())
     *     .build();
     * }</pre>
     *
     * <p>With a generated registry:</p>
     * <pre>{@code
     * JqMapper.Builder builder = JqMapper.builder();
     * JqMappingRegistry.registerAll(builder);
     * JqMapper mapper = builder.build();
     * }</pre>
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing a {@link JqMapper} with pre-registered mappings.
     * The builder is not thread-safe — build the mapper once, then share it.
     */
    public static final class Builder {
        private final java.util.List<GeneratedMapping<?>> mappings = new java.util.ArrayList<>();
        private final java.util.List<AnnotationBridge> bridges = new java.util.ArrayList<>();
        private final java.util.List<EnumBinding<?>> enumBindings = new java.util.ArrayList<>();
        private final java.util.Map<Class<?>, PolyConfig> polyFamilies = new java.util.LinkedHashMap<>();
        private final java.util.Map<Class<?>, KeyPolyConfig> keyFamilies = new java.util.LinkedHashMap<>();
        private boolean failOnUnknownProperties;

        private Builder() {}

        /**
         * Register a compile-time generated mapping.
         * Pre-registered mappings take priority over {@code Class.forName()} discovery
         * and reflection-based {@link ClassMapping}.
         *
         * @param mapping the generated mapping to register
         * @return this builder for chaining
         */
        public Builder register(GeneratedMapping<?> mapping) {
            mappings.add(mapping);
            return this;
        }

        /**
         * Register a compile-time enum binding. Compiled bindings take
         * priority over reflective {@code @JsonValue}/{@code @JsonCreator}
         * resolution for the bound type on every conversion path.
         *
         * @param binding the compiled binding to register
         * @return this builder for chaining
         */
        public Builder registerEnum(EnumBinding<?> binding) {
            enumBindings.add(java.util.Objects.requireNonNull(binding, "binding"));
            return this;
        }

        /**
         * Pre-register a discriminator family resolved at build time.
         * The base dispatches through the given configuration without
         * sealed permits reflection, which native images do not retain.
         *
         * @param base the family base type
         * @param config the pre-resolved family configuration
         * @return this builder for chaining
         */
        public Builder registerPoly(Class<?> base, PolyConfig config) {
            polyFamilies.put(
                    java.util.Objects.requireNonNull(base, "base"),
                    java.util.Objects.requireNonNull(config, "config"));
            return this;
        }

        /**
         * Pre-register a key-presence family resolved at build time.
         *
         * @param base the family base type
         * @param config the pre-resolved family configuration
         * @return this builder for chaining
         */
        public Builder registerKeys(Class<?> base, KeyPolyConfig config) {
            keyFamilies.put(
                    java.util.Objects.requireNonNull(base, "base"),
                    java.util.Objects.requireNonNull(config, "config"));
            return this;
        }
        /**
         * Register an annotation bridge for reading external framework annotations
         * (Jackson, JSON-B, etc.) as jjq-mapper configuration.
         *
         * <p>Bridges are consulted in registration order, after jjq-native annotations.
         * Multiple bridges can be registered; the first one that returns a non-null
         * value wins.</p>
         *
         * @param bridge the annotation bridge to register
         * @return this builder for chaining
         * @see AnnotationBridge
         */
        public Builder bridge(AnnotationBridge bridge) {
            bridges.add(bridge);
            return this;
        }

        /**
         * Fail deserialization on unrecognized JSON properties instead of
         * ignoring them (Jackson {@code FAIL_ON_UNKNOWN_PROPERTIES} parity).
         * Disabled by default. Any-setters still consume unknown keys when
         * present; ignored properties never fail.
         *
         * @param fail true to fail on unknown properties
         * @return this builder for chaining
         */
        public Builder failOnUnknownProperties(boolean fail) {
            this.failOnUnknownProperties = fail;
            return this;
        }

        /**
         * Build the mapper with all registered mappings and bridges.
         * Types not pre-registered will still fall back to {@code Class.forName()}
         * discovery and reflection-based mapping.
         *
         * @return a new thread-safe JqMapper
         */
        public JqMapper build() {
            JqMapper mapper = new JqMapper(List.copyOf(bridges), failOnUnknownProperties);
            for (var m : mappings) {
                mapper.cache.put(m.type(),
                        new LastHit(m.type(), wrapId(wrapPoly(m, mapper.bridges), mapper.bridges)));
            }
            for (var b : enumBindings) {
                mapper.compiledEnums.put(b.type(), b);
            }
            for (var family : polyFamilies.entrySet()) {
                mapper.cache.put(family.getKey(),
                        new LastHit(family.getKey(), new PolyMapping<>(family.getKey(), family.getValue())));
            }
            for (var family : keyFamilies.entrySet()) {
                mapper.cache.put(family.getKey(),
                        new LastHit(family.getKey(), new KeyPolyMapping<>(family.getKey(), family.getValue())));
            }
            return mapper;
        }

        /**
         * Wrap a pre-registered generated mapping for polymorphic family
         * membership (same rule as discovery-time wrapping, so both paths
         * agree regardless of registration order).
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private static GeneratedMapping<?> wrapPoly(GeneratedMapping<?> mapping, List<AnnotationBridge> bridges) {
            PolySerInfo serInfo = PolyMapping.resolvePolyMember(mapping.type(), bridges);
            if (serInfo == null) return mapping;
            return new PolyGeneratedWrapper<>((GeneratedMapping<Object>) mapping, serInfo);
        }

        /**
         * Wrap a pre-registered generated mapping for identity binding
         * (same rule as discovery-time wrapping, so both paths agree
         * regardless of registration order).
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private static GeneratedMapping<?> wrapId(GeneratedMapping<?> mapping, List<AnnotationBridge> bridges) {
            IdInfo idInfo = IdInfo.resolve(mapping.type(), bridges);
            if (idInfo == null) return mapping;
            return new IdGeneratedWrapper<>((GeneratedMapping<Object>) mapping, idInfo);
        }
    }

    /**
     * Deserialize a JqValue to a Java record.
     *
     * <p>Each record component is extracted from the input using a compiled jq
     * expression (default: {@code .fieldName}) and converted to the target Java type.
     * Missing fields receive their Java default value ({@code null} for reference types,
     * {@code 0} for numeric primitives, {@code false} for booleans).</p>
     *
     * @param value the JqValue to deserialize (typically a JqObject)
     * @param type  the target record class
     * @param <T>   the target type
     * @return a new instance of the record class with fields populated from the JqValue
     * @throws JqMapperException if mapping fails (type not a record, conversion error, etc.)
     */
    public <T> T fromJqValue(JqValue value, Class<T> type) {
        IdentityScope scope = IdentityScope.enter();
        try {
            return fromJqValueInner(value, type);
        } finally {
            scope.close();
        }
    }

    /**
     * Compile the immutable binding schema for a concrete type.
     *
     * <p>The schema describes wire names, required presence, conversions and
     * construction for the type under this mapper's bridge configuration. It
     * feeds build-time generation of direct byte-to-record readers (issue
     * #170); it never changes what the mapper accepts.</p>
     *
     * @param type the bound type
     * @param <T>  the target type
     * @return the immutable schema
     * @throws JqMapperException for invalid mapping configuration
     */
    public <T> BindingSchema bindingSchemaFor(Class<T> type) {
        return BindingSchema.forType(type, this);
    }

    /**
     * Compile the immutable binding schema for a possibly generic type.
     *
     * @param type the bound type (a {@code Class} or parameterized type)
     * @return the immutable schema
     * @throws JqMapperException for invalid mapping configuration
     */
    public BindingSchema bindingSchemaFor(Type type) {
        return BindingSchema.forType(type, this);
    }

    @SuppressWarnings("unchecked")
    private <T> T fromJqValueInner(JqValue value, Class<T> type) {
        if (type.isEnum()) {
            // Enums have no mapping (no no-arg constructor): convert directly so
            // @JsonCreator creators and name-based binding work from any caller,
            // including generated code (issue #88.4).
            @SuppressWarnings("unchecked")
            T result = (T) TypeConverter.convert(value, TypeConverter.Kind.ENUM, type, type, this);
            return result;
        }
        return getMapping(type).fromJqValue(value, this);
    }

    /**
     * Deserialize a JSON string to a Java record.
     * Convenience method that parses the JSON first, then maps.
     *
     * @param json the JSON string to parse and deserialize
     * @param type the target record class
     * @param <T>  the target type
     * @return a new instance of the record class
     */
    public <T> T fromJson(String json, Class<T> type) {
        return fromJqValue(JqValues.parse(json), type);
    }

    /**
     * Deserialize a JSON byte array to a Java record.
     * Uses the byte[]-based parser for maximum performance.
     *
     * @param json the JSON bytes to parse and deserialize
     * @param type the target record class
     * @param <T>  the target type
     * @return a new instance of the record class
     */
    public <T> T fromJson(byte[] json, Class<T> type) {
        return fromJqValue(JqValues.parse(json), type);
    }

    /**
     * Deserialize a JqValue using a generic type (e.g., {@code List<MyRecord>}).
     *
     * <p>Use this when the target type has generic parameters that a plain
     * {@code Class<T>} cannot express. For simple (non-generic) types, prefer
     * {@link #fromJqValue(JqValue, Class)}.</p>
     *
     * <pre>{@code
     * // Jackson equivalent: mapper.readValue(json, new TypeReference<List<Item>>(){})
     * Type listOfItems = new TypeToken<List<Item>>(){}.getType();
     * List<Item> items = mapper.fromJqValue(value, listOfItems);
     * }</pre>
     *
     * @param value the JqValue to deserialize
     * @param type  the target generic type
     * @param <T>   the target type
     * @return the deserialized value
     */
    @SuppressWarnings("unchecked")
    public <T> T fromJqValue(JqValue value, Type type) {
        IdentityScope scope = IdentityScope.enter();
        try {
            return fromJqValueInner(value, type);
        } finally {
            scope.close();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T fromJqValueInner(JqValue value, Type type) {
        Class<?> rawClass = TypeConverter.rawClass(type);
        return (T) TypeConverter.toJava(value, rawClass, type, this);
    }

    /**
     * Deserialize a JSON string using a generic type.
     *
     * @param json the JSON string to parse and deserialize
     * @param type the target generic type
     * @param <T>  the target type
     * @return the deserialized value
     * @see #fromJqValue(JqValue, Type)
     */
    public <T> T fromJson(String json, Type type) {
        return fromJqValue(JqValues.parse(json), type);
    }

    /**
     * Deserialize a JSON byte array using a generic type.
     *
     * @param json the JSON bytes to parse and deserialize
     * @param type the target generic type
     * @param <T>  the target type
     * @return the deserialized value
     * @see #fromJqValue(JqValue, Type)
     */
    public <T> T fromJson(byte[] json, Type type) {
        return fromJqValue(JqValues.parse(json), type);
    }

    /**
     * Serialize a Java record to a JqValue.
     *
     * <p>Each record component is read via its accessor method and converted
     * to a JqValue. The result is a JqObject with field names matching the
     * record component names.</p>
     *
     * @param value the record instance to serialize
     * @return a JqObject with fields populated from the record
     * @throws JqMapperException if serialization fails
     */
    @SuppressWarnings("unchecked")
    public JqValue toJqValue(Object value) {
        IdentityScope scope = IdentityScope.enter();
        try {
            return toJqValueInner(value);
        } finally {
            scope.close();
        }
    }

    @SuppressWarnings("unchecked")
    private JqValue toJqValueInner(Object value) {
        if (value == null) return io.hyperfoil.tools.jjq.value.JqNull.NULL;
        if (value.getClass().isEnum()) {
            // Enums have no mapping: convert directly so @JsonValue accessors
            // apply from any caller (issue #88.4).
            return TypeConverter.toJqValue(value, this);
        }
        if (value.getClass().isArray()) {
            // Arrays have no mapping (no no-arg constructor): convert directly.
            return TypeConverter.toJqValue(value, this);
        }
        Mapping<Object> mapping = (Mapping<Object>) getMapping(value.getClass());
        return mapping.toJqValue(value, this);
    }

    /**
     * Serialize a Java record to a JSON string.
     * Uses direct-to-JSON serialization when a generated mapping provides
     * {@code appendJson}, bypassing intermediate JqValue tree construction.
     *
     * @param value the record instance to serialize
     * @return the compact JSON string representation
     */
    public String toJson(Object value) {
        if (value == null) return "null";
        StringBuilder sb = JqValues.acquireSerializerBuffer();
        appendJson(value, sb);
        return JqValues.releaseSerializerBuffer(sb);
    }

    /**
     * Serialize a Java record to a UTF-8 JSON byte array.
     * Uses direct-to-bytes serialization when a generated mapping provides
     * {@code appendJsonBytes}, bypassing intermediate JqValue tree construction.
     *
     * @param value the record instance to serialize
     * @return the UTF-8 encoded JSON byte array
     */
    public byte[] toJsonBytes(Object value) {
        if (value == null) return new byte[]{'n', 'u', 'l', 'l'};
        BytOutput out = JqValues.acquireByteBuffer();
        appendJsonBytes(value, out);
        return JqValues.releaseByteBuffer(out);
    }

    /**
     * Append the JSON serialization of a Java object directly to a StringBuilder,
     * bypassing intermediate JqValue tree construction when possible.
     *
     * <p>Generated mappings write field values directly to the StringBuilder.
     * Reflection-based mappings fall back to building a JqValue tree and calling
     * {@code appendTo}.</p>
     *
     * @param value the object to serialize (may be null)
     * @param sb    the target StringBuilder
     */
    @SuppressWarnings("unchecked")
    public void appendJson(Object value, StringBuilder sb) {
        IdentityScope scope = IdentityScope.enter();
        try {
            appendJsonInner(value, sb);
        } finally {
            scope.close();
        }
    }

    @SuppressWarnings("unchecked")
    private void appendJsonInner(Object value, StringBuilder sb) {
        if (value == null) { sb.append("null"); return; }
        // Arrays have no mapping (no no-arg constructor): render through the
        // tree like any other unmapped container.
        if (value.getClass().isArray()) { toJqValue(value).appendTo(sb); return; }
        Mapping<Object> mapping = (Mapping<Object>) getMapping(value.getClass());
        mapping.appendJson(value, sb, this);
    }

    /**
     * Append the JSON serialization of a Java object directly to a byte buffer,
     * bypassing intermediate JqValue tree construction when possible.
     *
     * <p>Generated mappings write field values directly as UTF-8 bytes.
     * Reflection-based mappings fall back to building a JqValue tree and calling
     * {@code appendToBytes}.</p>
     *
     * @param value the object to serialize (may be null)
     * @param out   the target byte buffer
     */
    @SuppressWarnings("unchecked")
    public void appendJsonBytes(Object value, BytOutput out) {
        IdentityScope scope = IdentityScope.enter();
        try {
            appendJsonBytesInner(value, out);
        } finally {
            scope.close();
        }
    }

    @SuppressWarnings("unchecked")
    private void appendJsonBytesInner(Object value, BytOutput out) {
        if (value == null) { out.writeNull(); return; }
        // Arrays have no mapping (no no-arg constructor): render through the
        // tree like any other unmapped container.
        if (value.getClass().isArray()) { toJqValue(value).appendToBytes(out); return; }
        Mapping<Object> mapping = (Mapping<Object>) getMapping(value.getClass());
        mapping.appendJsonBytes(value, out, this);
    }

    @SuppressWarnings("unchecked")
    <T> Mapping<T> getMapping(Class<T> type) {
        LastHit hit = lastHit;
        if (hit != null && hit.type() == type) return (Mapping<T>) hit.mapping();
        LastHit resolved = cache.get(type);
        if (resolved == null) {
            resolved = cache.computeIfAbsent(type, t -> new LastHit(t, createMapping(t)));
        }
        // Always publish: adaptivity (phase changes re-cache in one miss) beats
        // the alternative — fill-once sticks with setup order and can miss
        // forever (measured: worse than baseline). Strict alternation pays one
        // volatile write per op; every repeating shape wins.
        lastHit = resolved;
        return (Mapping<T>) resolved.mapping();
    }

    /**
     * Whether a pre-registered family mapping exists for the type: a peek,
     * never a creation. Nested positions (collection elements, fields)
     * consult this first so native images dispatch through baked tables
     * instead of sealed permits reflection, which they do not retain.
     *
     * @param type the base class to inspect
     * @return true when a registered {@code PolyMapping}/{@code KeyPolyMapping} is cached
     */
    boolean hasFamilyMapping(Class<?> type) {
        LastHit hit = lastHit;
        if (hit != null && hit.type() == type) {
            return hit.mapping() instanceof PolyMapping || hit.mapping() instanceof KeyPolyMapping;
        }
        LastHit resolved = cache.get(type);
        return resolved != null
                && (resolved.mapping() instanceof PolyMapping || resolved.mapping() instanceof KeyPolyMapping);
    }

    private <T> Mapping<T> createMapping(Class<T> type) {
        // Arrays have no no-arg constructor: neither reflection field mapping
        // nor generated mappings apply (privateLookupIn rejects array classes).
        if (type.isArray()) return new ArrayMapping<>(type);
        // Polymorphic families dispatch before anything else: a @JqType (or
        // bridge-declared) base and a @JqKeys base never bind directly.
        // Both annotations on one type fail fast (resolved inside).
        KeyPolyConfig keys = KeyPolyMapping.resolveKeyConfig(type);
        if (keys != null) return new KeyPolyMapping<>(type, keys);
        PolyConfig poly = PolyMapping.resolvePolyConfig(type, bridges);
        if (poly != null) return new PolyMapping<>(type, poly);
        PolySerInfo serInfo = PolyMapping.resolvePolyMember(type, bridges);
        IdInfo idInfo = IdInfo.resolve(type, bridges);
        if (idInfo != null && (type.isInterface()
                || java.lang.reflect.Modifier.isAbstract(type.getModifiers()))) {
            // Identity needs instances: interfaces and abstract types can
            // never bind, with or without an id property.
            throw new JqMapperException("@JqId on " + type.getName()
                    + " needs a concrete type — identity binds instances, not interfaces");
        }
        // Try generated mapping first (from jjq-mapper-processor)
        // Identity POJOs skip codegen: true deserialization cycles need
        // mid-construction registration (before field binding), which only the
        // reflection path does — the wrapper registers after the delegate
        // returns, so generated POJOs support DAGs only. Records are immutable
        // (no true cycles possible), so they keep the generated fast path.
        GeneratedMapping<T> generated = (idInfo != null && !type.isRecord()) ? null : loadGenerated(type);
        if (generated != null) {
            Mapping<T> mapping = serInfo == null ? generated
                    : new PolyGeneratedWrapper<>(generated, serInfo);
            if (idInfo != null && mapping instanceof GeneratedMapping<T> gen) {
                return new IdGeneratedWrapper<>(gen, idInfo);
            }
            return mapping;
        }
        // Fall back to reflection-based mapping
        if (type.isRecord()) return ClassMapping.forRecord(type, bridges, serInfo);
        return ClassMapping.forClass(type, bridges, serInfo);
    }

    /**
     * Try to load a compile-time generated mapping class via naming convention.
     * Returns null if no generated class exists (processor not used or not on classpath).
     */
    @SuppressWarnings("unchecked")
    private <T> GeneratedMapping<T> loadGenerated(Class<T> type) {
        // For nested classes, Class.getName() uses '$' (e.g., "pkg.Outer$Inner")
        // but the generated class uses '_' (e.g., "pkg.Outer_Inner_JqMapping")
        String baseName = type.getName().replace('$', '_');
        String generatedName = baseName + "_JqMapping";
        try {
            Class<?> cls = Class.forName(generatedName, true, type.getClassLoader());
            return (GeneratedMapping<T>) cls.getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            return null; // no generated mapping — use reflection
        } catch (ReflectiveOperationException e) {
            throw new JqMapperException("Failed to instantiate generated mapping for " + type.getName(), e);
        }
    }
}

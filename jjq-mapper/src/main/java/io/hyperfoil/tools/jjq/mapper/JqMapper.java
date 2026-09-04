package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;

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

    private final ConcurrentHashMap<Class<?>, Mapping<?>> cache = new ConcurrentHashMap<>();

    private JqMapper() {}

    /**
     * Create a new JqMapper with default settings.
     * Uses {@code Class.forName()} to discover generated mappings at runtime.
     * The mapper is thread-safe and should be reused across calls.
     *
     * <p>For GraalVM native-image or Quarkus native builds, use {@link #builder()}
     * to pre-register generated mappings without reflective discovery.</p>
     */
    public static JqMapper create() {
        return new JqMapper();
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
         * Build the mapper with all registered mappings.
         * Types not pre-registered will still fall back to {@code Class.forName()}
         * discovery and reflection-based mapping.
         *
         * @return a new thread-safe JqMapper
         */
        public JqMapper build() {
            JqMapper mapper = new JqMapper();
            for (var m : mappings) {
                mapper.cache.put(m.type(), m);
            }
            return mapper;
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
        if (value == null) return io.hyperfoil.tools.jjq.value.JqNull.NULL;
        Mapping<Object> mapping = (Mapping<Object>) getMapping(value.getClass());
        return mapping.toJqValue(value, this);
    }

    /**
     * Serialize a Java record to a JSON string.
     * Convenience method that maps to JqValue, then serializes.
     *
     * @param value the record instance to serialize
     * @return the compact JSON string representation
     */
    public String toJson(Object value) {
        return toJqValue(value).toJsonString();
    }

    /**
     * Serialize a Java record to a UTF-8 JSON byte array.
     * Uses the direct byte serialization path for maximum performance.
     *
     * @param value the record instance to serialize
     * @return the UTF-8 encoded JSON byte array
     */
    public byte[] toJsonBytes(Object value) {
        return JqValues.serializeToBytes(toJqValue(value));
    }

    @SuppressWarnings("unchecked")
    private <T> Mapping<T> getMapping(Class<T> type) {
        return (Mapping<T>) cache.computeIfAbsent(type, this::createMapping);
    }

    private <T> Mapping<T> createMapping(Class<T> type) {
        // Try generated mapping first (from jjq-mapper-processor)
        GeneratedMapping<T> generated = loadGenerated(type);
        if (generated != null) return generated;
        // Fall back to reflection-based mapping
        if (type.isRecord()) return ClassMapping.forRecord(type);
        return ClassMapping.forClass(type);
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

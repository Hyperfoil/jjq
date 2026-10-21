package io.hyperfoil.tools.jjq.mapper;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable build-time description of how one Java type binds JSON.
 *
 * <p>A binding schema is compiled once per resolved Java type plus mapper
 * configuration and drives generated typed readers (issue #170) with their
 * binding checks. It is not a JSON Schema document and must not become a
 * separate per-request validation pass: the generated reader fuses parsing
 * with these checks instead of interpreting a schema tree per request.</p>
 *
 * <p>Metadata mapping (no new binding rules are introduced here):</p>
 * <ul>
 *   <li>Wire names, aliases and required presence come from {@code FieldMapping}
 *       and {@code TypeConverter.checkRequired} ({@code @JqName},
 *       {@code @JqNaming}, {@code @JqAlias}, {@code @JqRequired} plus bridges).</li>
 *   <li>Conversion kinds come from {@code TypeConverter.resolveKind}; the
 *       schema records the kind but never narrows accepted input. A
 *       {@code String} field still accepts numbers and booleans through the
 *       existing lenient coercions; a fast path that cannot represent a
 *       coercion must fall back to the tree-based binding.</li>
 *   <li>Missing-value behavior mirrors {@code TypeConverter.convert}:
 *       required fields fail, primitives bind their JVM default, references
 *       bind null ({@code Optional} binds empty).</li>
 *   <li>Explicit JSON null counts as present for {@code @JqRequired}, matching
 *       {@code JqObject.has} (which reports present keys regardless of value).</li>
 *   <li>Alias precedence is input-order independent: the primary wire name
 *       wins, then aliases in declaration order, mirroring
 *       {@code FieldMapping.extract}.</li>
 *   <li>Unknown-key handling mirrors the mapper: any-setter types forward,
 *       otherwise the mapper-wide {@code failOnUnknownProperties} flag decides.</li>
 * </ul>
 *
 * <p>Invalid mapping configuration (conflicting annotations, unbound
 * required markers, missing constructors) throws {@link JqMapperException}
 * during compilation, exactly as building the mapper would. Unsupported but
 * valid mappings instead compile to a non-eligible schema with explicit
 * fallback reasons.</p>
 *
 * <p>Instances carry no request state and are safe to share across threads and
 * endpoints. Raw {@code Class} identity alone is insufficient for resolved
 * generics or differing bridge policies, so the schema also records the
 * generic type and the bridge class names it was compiled against.</p>
 */
public final class BindingSchema {

    /** How the bound type is constructed. */
    public enum Construction {
        /** Record canonical constructor. */
        RECORD,
        /** POJO no-arg constructor plus setters or field access. */
        POJO,
        /** Property creator binding object documents by parameter name. */
        PROPERTY_CREATOR,
        /** Delegating creator binding whole scalar documents. */
        DELEGATING_CREATOR,
        /** Builder taking over construction. */
        BUILDER,
        /** Polymorphic families, arrays, enums and other non-object bindings. */
        UNSUPPORTED
    }

    /** What happens to unrecognized input keys. */
    public enum UnknownPolicy {
        /** Unknown keys are ignored (default mapper configuration). */
        IGNORE_UNKNOWN,
        /** Unknown keys fail when the mapper enables strict checking. */
        FAIL_ON_UNKNOWN,
        /** Unknown keys are forwarded to the any-setter. */
        FORWARD_TO_ANY_SETTER
    }

    /** What binds when a non-required field is absent from the input. */
    public enum MissingPolicy {
        /** Absence fails binding (required fields). */
        FAIL,
        /** Absence binds null ({@code Optional} binds empty). */
        BIND_NULL,
        /** Absence binds the JVM default (primitive fields). */
        BIND_PRIMITIVE_DEFAULT
    }

    /**
     * Binding descriptor for a single field.
     *
     * @param javaName        the Java component or property name
     * @param wireName        the primary JSON key
     * @param aliases         alternative accepted JSON keys, in priority order
     * @param javaType        the raw Java type
     * @param genericType     the generic type (carries element arguments)
     * @param kind            the pre-resolved conversion kind
     * @param primitive       true for JVM primitive targets (null cannot bind)
     * @param required        true when the key must be present
     * @param missingPolicy   what binds when a non-required field is absent
     * @param usesProgram     true for {@code @JqField} expression extraction
     * @param hasConverter    true for a custom {@code @JqConverter}/{@code @JqAdapter}
     * @param hasFormatter    true for a {@code @JqFormat} temporal formatter
     * @param stringShaped    true when numbers/booleans serialize quoted
     * @param ignored         true when excluded from binding entirely
     * @param skipDeserialize true when excluded from deserialization
     * @param constructorIndex canonical constructor position (-1 for POJOs)
     */
    public record Field(String javaName, String wireName, List<String> aliases,
                        Class<?> javaType, Type genericType, TypeConverter.Kind kind,
                        boolean primitive, boolean required, MissingPolicy missingPolicy,
                        boolean usesProgram, boolean hasConverter, boolean hasFormatter,
                        boolean stringShaped, boolean ignored, boolean skipDeserialize,
                        int constructorIndex) {
        /** Compact constructor defensively copies the alias list. */
        public Field {
            aliases = List.copyOf(aliases);
        }
    }

    private final Class<?> targetType;
    private final Type genericType;
    private final List<String> bridgeClassNames;
    private final List<Field> fields;
    private final Set<String> required;
    private final Set<String> knownKeys;
    private final Construction construction;
    private final UnknownPolicy unknownPolicy;
    private final boolean eligibleForDirectReader;
    private final List<String> ineligibilityReasons;

    private BindingSchema(Class<?> targetType, Type genericType, List<String> bridgeClassNames,
                          List<Field> fields, Set<String> required, Set<String> knownKeys,
                          Construction construction, UnknownPolicy unknownPolicy,
                          List<String> ineligibilityReasons) {
        this.targetType = targetType;
        this.genericType = genericType;
        this.bridgeClassNames = List.copyOf(bridgeClassNames);
        this.fields = List.copyOf(fields);
        this.required = Set.copyOf(required);
        this.knownKeys = Set.copyOf(knownKeys);
        this.construction = construction;
        this.unknownPolicy = unknownPolicy;
        this.eligibleForDirectReader = ineligibilityReasons.isEmpty();
        this.ineligibilityReasons = List.copyOf(ineligibilityReasons);
    }

    /**
     * Compile the binding schema for a concrete type.
     *
     * @param type   the bound type
     * @param mapper the mapper supplying bridges and the unknown-key policy
     * @return the immutable schema
     * @throws JqMapperException for invalid mapping configuration
     */
    public static BindingSchema forType(Class<?> type, JqMapper mapper) {
        return forType((Type) type, mapper);
    }

    /**
     * Compile the binding schema for a possibly generic type.
     *
     * @param type   the bound type (a {@code Class} or parameterized type)
     * @param mapper the mapper supplying bridges and the unknown-key policy
     * @return the immutable schema
     * @throws JqMapperException for invalid mapping configuration
     */
    public static BindingSchema forType(Type type, JqMapper mapper) {
        return compile(type, mapper);
    }

    /** The bound Java type. */
    public Class<?> targetType() { return targetType; }

    /** The generic type (equal to the target when non-generic). */
    public Type genericType() { return genericType; }

    /** Bridge implementation class names this schema was compiled against. */
    public List<String> bridgeClassNames() { return bridgeClassNames; }

    /** Bound fields in binding order. */
    public List<Field> fields() { return fields; }

    /** Required wire names in declaration order. */
    public Set<String> required() { return required; }

    /** Every non-program lookup key (primary names plus aliases). */
    public Set<String> knownKeys() { return knownKeys; }

    /** How the bound type is constructed. */
    public Construction construction() { return construction; }

    /** What happens to unrecognized input keys. */
    public UnknownPolicy unknownPolicy() { return unknownPolicy; }

    /** Whether the MVP direct byte-to-record reader may serve this type. */
    public boolean eligibleForDirectReader() { return eligibleForDirectReader; }

    /** Why the direct reader must fall back (empty when eligible). */
    public List<String> ineligibilityReasons() { return ineligibilityReasons; }

    /**
     * Look up a field by primary wire name, Java name, then alias.
     *
     * @param wireName the name to look up
     * @return the field, or null when no field accepts that name
     */
    public Field field(String wireName) {
        for (Field field : fields) {
            if (field.wireName().equals(wireName) || field.javaName().equals(wireName)) return field;
        }
        for (Field field : fields) {
            if (field.aliases().contains(wireName)) return field;
        }
        return null;
    }

    /** Conversion kinds the MVP direct reader decodes inline. */
    private static boolean isDirectScalar(TypeConverter.Kind kind) {
        return switch (kind) {
            case STRING, INT, LONG, BOOLEAN -> true;
            default -> false;
        };
    }

    private static BindingSchema compile(Type type, JqMapper mapper) {
        Class<?> raw = TypeConverter.rawClass(type);
        List<String> bridges = mapper.bridges().stream()
                .map(bridge -> bridge.getClass().getName()).toList();

        if (raw.isArray()) {
            return unsupported(raw, type, bridges, mapper,
                    "Java array '" + raw.getSimpleName() + "' has no object binding");
        }
        if (raw.isEnum()) {
            return unsupported(raw, type, bridges, mapper,
                    "enum '" + raw.getSimpleName() + "' binds as a scalar, not an object");
        }
        if (io.hyperfoil.tools.jjq.value.JqValue.class.isAssignableFrom(raw)) {
            return unsupported(raw, type, bridges, mapper,
                    "'" + raw.getSimpleName() + "' is a JqValue scalar passthrough");
        }
        if (KeyPolyMapping.resolveKeyConfig(raw) != null
                || PolyMapping.resolvePolyConfig(raw, mapper.bridges()) != null) {
            return unsupported(raw, type, bridges, mapper,
                    "'" + raw.getSimpleName() + "' is a polymorphic family base");
        }

        // Reflective introspection is the semantic source of truth: it runs
        // the same FieldMapping/TypeConverter/creator/builder/id resolution
        // the tree-based binding uses, so generated readers compiled from
        // this schema cannot drift from runtime behavior. Invalid mapping
        // configuration throws here, preserving existing failure behavior.
        PolySerInfo member = PolyMapping.resolvePolyMember(raw, mapper.bridges());
        ClassMapping<?> mapping = raw.isRecord()
                ? ClassMapping.forRecord(raw, mapper.bridges(), member)
                : ClassMapping.forClass(raw, mapper.bridges(), member);

        List<String> reasons = new ArrayList<>();
        if (member != null) {
            reasons.add("polymorphic member of family (discriminator '"
                    + member.discriminator() + "')");
        }
        if (mapping.idInfo() != null) {
            reasons.add("identity binding on property '" + mapping.idInfo().property() + "'");
        }
        if (mapping.hasAnySetter()) {
            reasons.add("any-setter forwards unknown keys");
        }

        Construction construction;
        if (mapping.builder() != null) {
            construction = Construction.BUILDER;
            reasons.add("builder '" + mapping.builder().label() + "' takes over construction");
        } else if (mapping.creator() != null && mapping.creator().delegating()) {
            construction = Construction.DELEGATING_CREATOR;
            reasons.add("delegating creator '" + mapping.creator().label()
                    + "' binds whole scalars");
        } else if (mapping.creator() != null) {
            construction = Construction.PROPERTY_CREATOR;
            reasons.add("property creator '" + mapping.creator().label()
                    + "' binds by parameter name");
        } else {
            construction = raw.isRecord() ? Construction.RECORD : Construction.POJO;
            if (!raw.isRecord()) {
                reasons.add("POJO setter/field construction is not a direct-reader target");
            }
        }

        List<Field> fields = new ArrayList<>();
        Set<String> required = new LinkedHashSet<>();
        Set<String> known = new LinkedHashSet<>();
        for (FieldMapping field : mapping.deserFields()) {
            if (field.isIgnored() || field.skipDeserialize()) continue;
            boolean isRequired = mapping.required().contains(field.jsonName());
            MissingPolicy missing = isRequired ? MissingPolicy.FAIL
                    : field.type().isPrimitive() ? MissingPolicy.BIND_PRIMITIVE_DEFAULT
                    : MissingPolicy.BIND_NULL;
            List<String> aliases = field.aliases() == null ? List.of() : List.of(field.aliases());
            fields.add(new Field(field.name(), field.jsonName(), aliases, field.type(),
                    field.genericType(), field.conversionKind(), field.type().isPrimitive(),
                    isRequired, missing, field.usesProgram(), field.hasCustomConverter(),
                    field.hasFormatter(), field.isStringShaped(), field.isIgnored(),
                    field.skipDeserialize(), field.constructorIndex()));
            if (!field.usesProgram()) {
                known.add(field.jsonName());
                known.addAll(aliases);
            }
            if (isRequired) required.add(field.jsonName());
            if (!field.usesProgram() && !field.hasCustomConverter() && !field.hasFormatter()
                    && !field.isStringShaped() && !isDirectScalar(field.conversionKind())) {
                reasons.add("field '" + field.jsonName() + "' has nested kind "
                        + field.conversionKind() + " (not a direct-reader scalar)");
            }
            if (field.usesProgram()) {
                reasons.add("field '" + field.name()
                        + "' uses @JqField program extraction");
            }
            if (field.hasCustomConverter()) {
                reasons.add("field '" + field.jsonName() + "' uses a custom converter");
            }
            if (field.hasFormatter() || field.isStringShaped()) {
                reasons.add("field '" + field.jsonName() + "' uses @JqFormat shaping");
            }
        }

        UnknownPolicy unknown = mapping.hasAnySetter() ? UnknownPolicy.FORWARD_TO_ANY_SETTER
                : mapper.isFailOnUnknownProperties() ? UnknownPolicy.FAIL_ON_UNKNOWN
                : UnknownPolicy.IGNORE_UNKNOWN;
        return new BindingSchema(raw, type, bridges, fields, required, known,
                construction, unknown, reasons);
    }

    private static BindingSchema unsupported(Class<?> raw, Type type, List<String> bridges,
                                             JqMapper mapper, String reason) {
        UnknownPolicy unknown = mapper.isFailOnUnknownProperties()
                ? UnknownPolicy.FAIL_ON_UNKNOWN : UnknownPolicy.IGNORE_UNKNOWN;
        return new BindingSchema(raw, type, bridges, List.of(), Set.of(), Set.of(),
                Construction.UNSUPPORTED, unknown, List.of(reason));
    }
}

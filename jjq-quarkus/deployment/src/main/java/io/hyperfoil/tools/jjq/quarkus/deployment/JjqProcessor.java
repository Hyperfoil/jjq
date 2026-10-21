package io.hyperfoil.tools.jjq.quarkus.deployment;

import java.lang.Enum.EnumDesc;
import java.lang.constant.ClassDesc;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.logging.Logger;

import io.hyperfoil.tools.jjq.jakarta.JqBadRequestException;
import io.hyperfoil.tools.jjq.jakarta.JqBadRequestExceptionMapper;
import io.hyperfoil.tools.jjq.jakarta.JqMappedMessageBodyReader;
import io.hyperfoil.tools.jjq.jakarta.JqMappedMessageBodyWriter;
import io.hyperfoil.tools.jjq.jakarta.JqValueMessageBodyReader;
import io.hyperfoil.tools.jjq.jakarta.JqValueMessageBodyWriter;
import io.hyperfoil.tools.jjq.jakarta.JqValueParamConverterProvider;
import io.hyperfoil.tools.jjq.mapper.BindingSchema;
import io.hyperfoil.tools.jjq.mapper.DirectReadFallbackException;
import io.hyperfoil.tools.jjq.mapper.DirectReader;
import io.hyperfoil.tools.jjq.mapper.JqMapped;
import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.mapper.EnumBinding;
import io.hyperfoil.tools.jjq.mapper.JqMapperException;
import io.hyperfoil.tools.jjq.mapper.GeneratedMapping;
import io.hyperfoil.tools.jjq.mapper.KeyPolyConfig;
import io.hyperfoil.tools.jjq.mapper.KeyPolyMapping;
import io.hyperfoil.tools.jjq.mapper.PolyConfig;
import io.hyperfoil.tools.jjq.mapper.PolyMapping;
import io.hyperfoil.tools.jjq.jakarta.JqProblemPolicy;
import io.hyperfoil.tools.jjq.mapper.TypeConverter;
import io.hyperfoil.tools.jjq.mapper.spi.AnnotationBridge;
import io.hyperfoil.tools.jjq.quarkus.runtime.JjqFormatMapper;
import io.hyperfoil.tools.jjq.quarkus.runtime.JjqBuildTimeConfig;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.GeneratedBeanBuildItem;
import io.quarkus.arc.deployment.GeneratedBeanGizmo2Adaptor;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.gizmo2.ClassOutput;
import io.quarkus.gizmo2.Const;
import io.quarkus.gizmo2.Expr;
import io.quarkus.gizmo2.Gizmo;
import io.quarkus.gizmo2.LocalVar;
import io.quarkus.gizmo2.creator.BlockCreator;
import io.quarkus.gizmo2.desc.ConstructorDesc;
import io.quarkus.gizmo2.desc.FieldDesc;
import io.quarkus.gizmo2.desc.MethodDesc;
import io.quarkus.resteasy.common.spi.ResteasyJaxrsProviderBuildItem;
import io.quarkus.resteasy.reactive.spi.MessageBodyReaderBuildItem;
import io.quarkus.resteasy.reactive.spi.MessageBodyWriterBuildItem;
import io.quarkus.resteasy.reactive.spi.ExceptionMapperBuildItem;
import io.quarkus.resteasy.reactive.spi.ParamConverterBuildItem;
import io.quarkus.runtime.configuration.ConfigurationException;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.RuntimeType;

/**
 * {@code jjq-quarkus} deployment processor (issue #65).
 *
 * <p>Discovers {@code @JqMapped} types via Jandex and pre-registers their
 * generated mappings on a CDI-managed {@link JqMapper} singleton, which the
 * {@code jjq-jakarta} JAX-RS providers pick up through {@code @Inject}.
 * RESTEasy Reactive ({@code quarkus-rest}) discovers the providers as beans
 * through its standard {@code @Provider} scanning; the extension pins them
 * unremovable and adds explicit serialiser entries (application scanning
 * only covers app classes, not providers from dependency jars). RESTEasy
 * Classic needs fully explicit provider registration, and the Hibernate
 * {@code FormatMapper} is wired for Jackson-free applications (the build
 * fails fast when the Jackson extension is present).</p>
 */
public class JjqProcessor {

    private static final Logger LOG = Logger.getLogger(JjqProcessor.class);

    /** Generated CDI producer for the application mapper (one per application). */
    static final String PRODUCER_CLASS = "io.hyperfoil.tools.jjq.quarkus.generated.JjqMapperProducer";

    private static final DotName JQ_MAPPED = DotName.createSimple(JqMapped.class.getName());
    private static final DotName JQ_TYPE = DotName.createSimple("io.hyperfoil.tools.jjq.mapper.JqType");
    private static final DotName JQ_KEYS = DotName.createSimple("io.hyperfoil.tools.jjq.mapper.JqKeys");
    private static final DotName SINGLETON = DotName.createSimple(Singleton.class.getName());

    private static final String JSON = "application/json";

    /** Wire type for RFC 9457 problem details (no JAX-RS constant exists). */
    private static final String PROBLEM_JSON = "application/problem+json";

    /**
     * Never loaded as a class (the application may not have http-problem):
     * registration below passes this name as a string only.
     */
    private static final String HTTP_PROBLEM_CLASS = "io.quarkiverse.httpproblem.HttpProblem";

    /** REST JSON engines whose presence keeps HttpProblem on the engine path. */
    private static final java.util.Set<String> JSON_ENGINE_ARTIFACTS = java.util.Set.of(
            "quarkus-rest-jackson", "quarkus-resteasy-jackson",
            "quarkus-rest-jsonb", "quarkus-resteasy-jsonb");

    private static final String[] BODY_PROVIDER_CLASSES = {
            JqMappedMessageBodyReader.class.getName(),
            JqMappedMessageBodyWriter.class.getName(),
            JqValueMessageBodyReader.class.getName(),
            JqValueMessageBodyWriter.class.getName(),
    };

    private static final String[] PROVIDER_CLASSES = {
            JqMappedMessageBodyReader.class.getName(),
            JqMappedMessageBodyWriter.class.getName(),
            JqValueMessageBodyReader.class.getName(),
            JqValueMessageBodyWriter.class.getName(),
            JqValueParamConverterProvider.class.getName(),
            JqBadRequestExceptionMapper.class.getName(),
    };

    /**
     * Annotation bridges auto-registered when their modules are application
     * dependencies. Order is load-bearing: bridges are consulted in
     * registration order after native annotations, matching the annotation
     * processor's precedence (Jackson before JSON-B).
     */
    private record BridgeModule(String group, String artifact, String bridgeClass) {}

    private static final List<BridgeModule> BRIDGE_MODULES = List.of(
            new BridgeModule("io.hyperfoil.tools", "jjq-mapper-jackson",
                    "io.hyperfoil.tools.jjq.mapper.jackson.JacksonAnnotationBridge"),
            new BridgeModule("io.hyperfoil.tools", "jjq-mapper-jsonb",
                    "io.hyperfoil.tools.jjq.mapper.jsonb.JsonbAnnotationBridge"));

    /**
     * Whether the http-problem extension renders the application's errors:
     * when {@code quarkus-http-problem} is an application dependency, bind
     * failures render problem details unconditionally for consistency with
     * every other 4xx (issue #153). Pure presence check on the application
     * model, without loading http-problem classes at runtime.
     */
    static boolean httpProblemPresent(Collection<ResolvedDependency> dependencies) {
        return dependencies.stream().anyMatch(d -> d.getGroupId().equals("io.quarkiverse.httpproblem")
                && d.getArtifactId().equals("quarkus-http-problem"));
    }

    /**
     * Whether a JSON engine that renders {@code HttpProblem} is present.
     * Jackson variants cannot reach here (rejected above), so in practice
     * this is the JSON-B REST extensions; all four are listed so the gate
     * stays correct if the rejection ever narrows. Pure presence check on
     * the application model, without loading engine classes.
     */
    static boolean jsonEnginePresent(Collection<ResolvedDependency> dependencies) {
        return dependencies.stream().anyMatch(d -> d.getGroupId().equals("io.quarkus")
                && JSON_ENGINE_ARTIFACTS.contains(d.getArtifactId()));
    }

    /**
     * Whether the engine-free problem writer applies: http-problem present
     * and no JSON engine able to serve it instead (issue #176).
     */
    static boolean problemWriterWanted(Collection<ResolvedDependency> dependencies) {
        return httpProblemPresent(dependencies) && !jsonEnginePresent(dependencies);
    }

    /**
     * Bridge classes for explicitly included bridge modules, in precedence
     * order. Presence in the application model is the opt-in (never
     * transitive-only guessing, never ServiceLoader at runtime).
     */
    static List<String> bridgeClassNames(Collection<ResolvedDependency> dependencies) {
        List<String> names = new ArrayList<>();
        for (BridgeModule bridge : BRIDGE_MODULES) {
            if (dependencies.stream().anyMatch(d -> d.getGroupId().equals(bridge.group())
                    && d.getArtifactId().equals(bridge.artifact()))) {
                names.add(bridge.bridgeClass());
            }
        }
        return names;
    }

    /**
     * Binding class name for an enum, colocated in the enum's package like
     * {@code _JqMapping} classes (same package keeps future member access
     * options open; nested {@code $} becomes {@code _}).
     */
    static String enumBindingClassNameFor(String className) {
        int dot = className.lastIndexOf('.');
        String pkg = dot < 0 ? "" : className.substring(0, dot + 1);
        String binary = dot < 0 ? className : className.substring(dot + 1);
        return pkg + binary.replace('$', '_') + "_EnumBinding";
    }

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem("jjq");
    }

    /**
     * Fail the build when the Jackson extension shares the classpath: both
     * extensions register JSON serializers for the same types, and silent
     * provider-selection order would make behavior nondeterministic.
     * Plain Jackson library jars carry no capability and are unaffected.
     */
    @BuildStep
    void rejectJacksonExtension(Capabilities capabilities, ValidationPhaseBuildItem validationPhase,
            BuildProducer<ValidationPhaseBuildItem.ValidationErrorBuildItem> validationErrors) {
        // quarkus-rest-jackson and quarkus-resteasy-jackson both depend on
        // quarkus-jackson, so the single JACKSON capability covers every variant.
        if (capabilities.isPresent(Capability.JACKSON)) {
            validationErrors.produce(new ValidationPhaseBuildItem.ValidationErrorBuildItem(new ConfigurationException(
                    "jjq-quarkus and quarkus-jackson were both detected on the classpath,"
                            + " but they cannot be used together.\n"
                            + "These two JSON mapping extensions are mutually exclusive: please use only one of them.\n"
                            + "Either remove quarkus-jackson (including quarkus-rest-jackson or quarkus-resteasy-jackson,"
                            + " which depend on it) and keep jjq-quarkus,"
                            + " or remove jjq-quarkus and keep Jackson.")));
        }
    }

    /**
     * Resolve the generated {@code _JqMapping} class name for a {@code @JqMapped}
     * type, mirroring {@code JqMapper.loadGenerated}'s naming convention
     * ({@code $} becomes {@code _}, suffix {@code _JqMapping}).
     *
     * @param className the Jandex dotted class name (nested classes use {@code $})
     * @return the generated mapping class name
     */
    static String mappingClassNameFor(String className) {
        int dot = className.lastIndexOf('.');
        String pkg = dot < 0 ? "" : className.substring(0, dot + 1);
        String binary = dot < 0 ? className : className.substring(dot + 1);
        return pkg + binary.replace('$', '_') + "_JqMapping";
    }

    @BuildStep
    void discoverMappedTypes(CombinedIndexBuildItem combinedIndex,
            BuildProducer<JjqMappedTypeBuildItem> mappedTypes) {
        for (String className : annotatedClassNames(combinedIndex.getIndex(), JQ_MAPPED)) {
            mappedTypes.produce(new JjqMappedTypeBuildItem(className, mappingClassNameFor(className)));
        }
    }

    @BuildStep
    void discoverPolyFamilies(CombinedIndexBuildItem combinedIndex,
            BuildProducer<JjqPolyFamilyBuildItem> families) {
        IndexView index = combinedIndex.getIndex();
        for (DotName name : List.of(JQ_TYPE, JQ_KEYS)) {
            for (String className : annotatedClassNames(index, name)) {
                families.produce(new JjqPolyFamilyBuildItem(className));
            }
        }
    }

    /**
     * Class names carrying an annotation (Jandex dotted form, CLASS targets
     * only — the shape every jjq annotation uses).
     */
    private static List<String> annotatedClassNames(IndexView index, DotName annotation) {
        List<String> names = new ArrayList<>();
        for (AnnotationInstance instance : index.getAnnotations(annotation)) {
            if (instance.target().kind() == AnnotationTarget.Kind.CLASS) {
                names.add(instance.target().asClass().name().toString());
            }
        }
        return names;
    }

    @BuildStep
    void discoverEnumBindings(CombinedIndexBuildItem combinedIndex, CurateOutcomeBuildItem curateOutcome,
            BuildProducer<JjqEnumBindingBuildItem> enumBindings,
            BuildProducer<GeneratedBeanBuildItem> generatedBeans) {
        List<String> selectedBridges = bridgeClassNames(curateOutcome.getApplicationModel().getDependencies());
        if (selectedBridges.isEmpty()) {
            return;
        }
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        List<AnnotationBridge> bridges = new ArrayList<>();
        for (String name : selectedBridges) {
            try {
                Class<?> cls = Class.forName(name, true, cl);
                bridges.add((AnnotationBridge) cls.getDeclaredConstructor().newInstance());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("jjq: cannot instantiate annotation bridge " + name
                        + " — check the bridge module dependency", e);
            }
        }
        ClassOutput output = new GeneratedBeanGizmo2Adaptor(generatedBeans);
        for (var clazz : combinedIndex.getIndex().getKnownClasses()) {
            if (!clazz.isEnum() || clazz.name().toString().startsWith("java.")) {
                continue;
            }
            String enumName = clazz.name().toString();
            Class<?> enumType;
            try {
                enumType = Class.forName(enumName, false, cl);
            } catch (ClassNotFoundException | LinkageError e) {
                continue;
            }
            if (!enumType.isEnum()) {
                continue;
            }
            TypeConverter.EnumHandlers handlers;
            try {
                var resolved = TypeConverter.resolveEnumHandlers(enumType, bridges);
                if (resolved.isEmpty()) {
                    continue;
                }
                handlers = resolved.get();
            } catch (JqMapperException e) {
                // Malformed bridge metadata: keep the reflective timing
                // (failure at first use) instead of failing the build.
                continue;
            }
            try {
                generateEnumBinding(output, enumType, handlers);
            } catch (IllegalArgumentException e) {
                LOG.warnf("jjq: %s, type will use reflection-based enum handling", e.getMessage());
                continue;
            }
            enumBindings.produce(new JjqEnumBindingBuildItem(enumName, enumBindingClassNameFor(enumName)));
        }
    }

    /**
     * Emit a compiled enum binding: direct accessor/creator calls with the
     * same failure attribution as the reflective path. Anything this method
     * cannot express (lenient enums, non-public members, generic creator
     * arguments, self-returning accessors) throws, and the caller keeps the
     * reflective fallback — never silently partial.
     *
     * @param output    where the generated class bytes go
     * @param enumType  the bound enum class (already loaded)
     * @param handlers  the resolved description (creator required)
     */
    static void generateEnumBinding(ClassOutput output, Class<?> enumType, TypeConverter.EnumHandlers handlers) {
        if (handlers.creator() == null) {
            throw new IllegalArgumentException(
                    "jjq: enum binding needs a creator for " + enumType.getName());
        }
        if (handlers.leniency() != null) {
            throw new IllegalArgumentException(
                    "jjq: lenient enums use the reflective path: " + enumType.getName());
        }
        if (!java.lang.reflect.Modifier.isPublic(enumType.getModifiers())) {
            throw new IllegalArgumentException(
                    "jjq: enum must be public for direct binding: " + enumType.getName());
        }
        java.lang.reflect.Method accessor = handlers.valueAccessor();
        if (accessor != null) {
            if (!java.lang.reflect.Modifier.isPublic(accessor.getModifiers())) {
                throw new IllegalArgumentException(
                        "jjq: value accessor must be public for direct binding: " + enumType.getName());
            }
            if (accessor.getReturnType().isPrimitive()) {
                throw new IllegalArgumentException(
                        "jjq: primitive-returning accessors use the reflective path: " + enumType.getName());
            }
        }
        // Enum constructors are implicitly private (explicit public or
        // protected is a compile error), so direct construction from
        // generated code is never valid: constructor creators stay on the
        // reflective path, which already handles accessibility.
        if (handlers.creator() instanceof java.lang.reflect.Constructor<?>) {
            throw new IllegalArgumentException(
                    "jjq: constructor creators use the reflective path: " + enumType.getName());
        }
        java.lang.reflect.Member member = (java.lang.reflect.Member) handlers.creator();
        if (!java.lang.reflect.Modifier.isPublic(member.getModifiers())) {
            throw new IllegalArgumentException(
                    "jjq: creator must be public for direct binding: " + enumType.getName());
        }
        if (!(handlers.creatorArgType() instanceof Class<?> argClass)) {
            throw new IllegalArgumentException(
                    "jjq: generic creator arguments use the reflective path: " + enumType.getName());
        }
        // Resolved once here, baked as a constant: the generated code never
        // re-resolves the kind per conversion.
        TypeConverter.Kind argKind = TypeConverter.resolveKind(argClass, argClass);
        String creatorName = ((java.lang.reflect.Method) handlers.creator()).getName();
        String failureMessage = "Failed to invoke @JsonCreator for " + enumType.getName();
        Gizmo gizmo = Gizmo.create(output).withDebugInfo(false).withParameters(false);
        gizmo.class_(enumBindingClassNameFor(enumType.getName()), cc -> {
            cc.defaultConstructor();
            cc.implements_(EnumBinding.class);
            cc.method("type", mc -> {
                mc.returning(Class.class);
                mc.body(bc -> bc.return_(Const.of(enumType)));
            });
            cc.method("read", mc -> {
                // Erased signature (Enum, not E): the JVM resolves
                // interface dispatch by exact descriptor, so a covariant
                // return here would need a synthetic bridge method.
                mc.returning(Enum.class);
                var value = mc.parameter("value", JqValue.class);
                var mapper = mc.parameter("mapper", JqMapper.class);
                mc.body(bc -> {
                    LocalVar arg = bc.localVar("arg", bc.invokeStatic(
                            MethodDesc.of(TypeConverter.class, "convert", Object.class, JqValue.class,
                                    TypeConverter.Kind.class, Class.class, java.lang.reflect.Type.class,
                                    JqMapper.class),
                            List.of(value, Const.of(argKind),
                                    Const.of(argClass), Const.of(argClass), mapper)));
                    LocalVar typed = bc.localVar("typed", bc.cast(arg, argClass));
                    bc.try_(tc -> {
                        tc.body(b -> b.return_(b.invokeStatic(
                                MethodDesc.of(enumType, creatorName, enumType, argClass),
                                List.of(typed))));
                        tc.catch_(RuntimeException.class, "e", (b2, ex) -> b2.throw_(
                                b2.new_(ClassDesc.of(
                                        "io.hyperfoil.tools.jjq.mapper.JqMapperException"),
                                        List.of(Const.of(failureMessage),
                                                b2.cast(ex, Throwable.class)))));
                    });
                });
            });
            cc.method("write", mc -> {
                mc.returning(JqValue.class);
                var value = mc.parameter("value", Enum.class);
                var mapper = mc.parameter("mapper", JqMapper.class);
                mc.body(bc -> {
                    LocalVar bound = bc.localVar("bound", bc.cast(value, enumType));
                    if (accessor != null) {
                        LocalVar wire = bc.localVar("wire", bc.invokeVirtual(
                                MethodDesc.of(enumType, accessor.getName(), accessor.getReturnType()), bound));
                        bc.if_(bc.isNull(wire), then -> emitWriteFallback(then, bound, handlers));
                        if (enumType.isAssignableFrom(accessor.getReturnType())) {
                            bc.if_(bc.invokeVirtual(
                                    MethodDesc.of(Enum.class, "equals", boolean.class, Object.class),
                                    bound, wire),
                                    then -> emitWriteFallback(then, bound, handlers));
                        }
                        bc.return_(bc.invokeStatic(
                                MethodDesc.of(TypeConverter.class, "toJqValue", JqValue.class, Object.class,
                                        JqMapper.class),
                                List.of(wire, mapper)));
                    } else {
                        emitWriteFallback(bc, bound, handlers);
                    }
                });
            });
        });
    }

    /**
     * Reader class name for a bound type, colocated in the type's package
     * like {@code _JqMapping} classes (same package keeps the canonical
     * constructor accessible; nested {@code $} becomes {@code _}).
     *
     * @param className the Jandex dotted class name (nested classes use {@code $})
     * @return the generated reader class name
     */
    public static String directReaderClassNameFor(String className) {
        int dot = className.lastIndexOf('.');
        String pkg = dot < 0 ? "" : className.substring(0, dot + 1);
        String binary = dot < 0 ? className : className.substring(dot + 1);
        return pkg + binary.replace('$', '_') + "_DirectReader";
    }

    /** Reader generation caps at 64 fields: method bytecode grows linearly
     * per field, and this keeps the hottest method an order of magnitude
     * under the 64KB method limit. Wider records keep the existing path. */
    static final int DIRECT_READER_MAX_FIELDS = 64;

    private static final DotName JAX_POST = DotName.createSimple("jakarta.ws.rs.POST");
    private static final DotName JAX_PUT = DotName.createSimple("jakarta.ws.rs.PUT");
    private static final DotName JAX_PATCH = DotName.createSimple("jakarta.ws.rs.PATCH");

    /**
     * Request body types for direct-reader generation: {@code @JqMapped}
     * types taken directly as parameters of {@code @POST}/{@code @PUT}/
     * {@code @PATCH} resource methods. One reader is generated per type no
     * matter how many endpoints share it; serialization-only models get
     * none. Generic wrappers (lists, maps) stay on the existing path.
     *
     * @param index  the application Jandex index
     * @param mapped discovered {@code @JqMapped} class names (Jandex dotted form)
     * @return request body class names, in discovery order
     */
    static Set<String> requestBodyClassNames(IndexView index, Set<String> mapped) {
        Set<String> found = new LinkedHashSet<>();
        for (DotName method : List.of(JAX_POST, JAX_PUT, JAX_PATCH)) {
            for (AnnotationInstance ann : index.getAnnotations(method)) {
                if (ann.target().kind() != AnnotationTarget.Kind.METHOD) continue;
                for (org.jboss.jandex.MethodParameterInfo param : ann.target().asMethod().parameters()) {
                    if (param.type().kind() != org.jboss.jandex.Type.Kind.CLASS) continue;
                    String name = param.type().asClassType().name().toString();
                    if (mapped.contains(name)) found.add(name);
                }
            }
        }
        return found;
    }

    /**
     * Emit a direct byte-to-record reader for one eligible flat record.
     * Anything this method cannot express throws, and the caller keeps the
     * existing strict parse-plus-map path — never silently partial.
     *
     * <p>Eligibility beyond the schema (which already pins binding rules):
     * the record and its canonical constructor must be invocable from the
     * colocated reader (public or package-visible, never private), the
     * field count must fit the method-size budget, and no field may carry
     * aliases (one wire name per field keeps dispatch a single scan).</p>
     *
     * @param output where the generated class bytes go
     * @param type   the bound record class (already loaded)
     * @param schema the compiled binding schema (must be eligible)
     */
    public static void generateDirectReader(ClassOutput output, Class<?> type, BindingSchema schema) {
        String name = type.getName();
        if (!type.isRecord()) {
            throw new IllegalArgumentException(
                    "jjq: direct readers serve records: " + name);
        }
        if (!schema.eligibleForDirectReader()) {
            throw new IllegalArgumentException("jjq: " + name + " is not direct-reader eligible: "
                    + String.join("; ", schema.ineligibilityReasons()));
        }
        if (Modifier.isPrivate(type.getModifiers())) {
            throw new IllegalArgumentException(
                    "jjq: private records use the reflective path: " + name);
        }
        List<BindingSchema.Field> fields = schema.fields();
        if (fields.size() > DIRECT_READER_MAX_FIELDS) {
            throw new IllegalArgumentException("jjq: " + name + " has " + fields.size()
                    + " fields, readers support up to " + DIRECT_READER_MAX_FIELDS);
        }
        for (BindingSchema.Field field : fields) {
            if (!field.aliases().isEmpty()) {
                throw new IllegalArgumentException("jjq: aliased field '" + field.wireName()
                        + "' of " + name + " uses the reflective path");
            }
            switch (field.kind()) {
                case STRING, INT, LONG, BOOLEAN -> { }
                default -> throw new IllegalArgumentException("jjq: field '" + field.wireName()
                        + "' of " + name + " has kind " + field.kind());
            }
        }
        Class<?>[] ctorTypes = new Class<?>[fields.size()];
        for (BindingSchema.Field field : fields) {
            ctorTypes[field.constructorIndex()] = field.javaType();
        }
        Constructor<?> ctor;
        try {
            ctor = type.getDeclaredConstructor(ctorTypes);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
                    "jjq: no canonical constructor for " + name, e);
        }
        if (Modifier.isPrivate(ctor.getModifiers())) {
            throw new IllegalArgumentException(
                    "jjq: private canonical constructor uses the reflective path: " + name);
        }

        ClassDesc recordDesc = ClassDesc.of(name);
        ClassDesc kindDesc = ClassDesc.of(JqValues.JqCursor.ValueKind.class.getName());
        MethodDesc mStrict = MethodDesc.of(JqValues.class, "strictCursor",
                JqValues.JqCursor.class, byte[].class);
        MethodDesc mBeginDoc = MethodDesc.of(JqValues.JqCursor.class, "beginDocument", boolean.class);
        MethodDesc mBeginObj = MethodDesc.of(JqValues.JqCursor.class, "beginObject", boolean.class);
        MethodDesc mNext = MethodDesc.of(JqValues.JqCursor.class, "nextField", boolean.class);
        MethodDesc mIndex = MethodDesc.of(JqValues.JqCursor.class, "fieldIndex", int.class, byte[][].class);
        MethodDesc mColon = MethodDesc.of(JqValues.JqCursor.class, "expectColon", boolean.class);
        MethodDesc mPeek = MethodDesc.of(JqValues.JqCursor.class, "peekValue",
                JqValues.JqCursor.ValueKind.class);
        MethodDesc mReadString = MethodDesc.of(JqValues.JqCursor.class, "readString", String.class);
        MethodDesc mReadLong = MethodDesc.of(JqValues.JqCursor.class, "readLong", boolean.class);
        MethodDesc mLongValue = MethodDesc.of(JqValues.JqCursor.class, "longValue", long.class);
        MethodDesc mReadInt = MethodDesc.of(JqValues.JqCursor.class, "readInt", boolean.class);
        MethodDesc mIntValue = MethodDesc.of(JqValues.JqCursor.class, "intValue", int.class);
        MethodDesc mReadBool = MethodDesc.of(JqValues.JqCursor.class, "readBoolean", boolean.class);
        MethodDesc mBoolValue = MethodDesc.of(JqValues.JqCursor.class, "booleanValue", boolean.class);
        MethodDesc mReadNull = MethodDesc.of(JqValues.JqCursor.class, "readNull", boolean.class);
        MethodDesc mEnd = MethodDesc.of(JqValues.JqCursor.class, "endInput", boolean.class);
        Const cString = Const.of(EnumDesc.of(kindDesc, "STRING"));
        Const cNumber = Const.of(EnumDesc.of(kindDesc, "NUMBER"));
        Const cTrue = Const.of(EnumDesc.of(kindDesc, "TRUE"));
        Const cFalse = Const.of(EnumDesc.of(kindDesc, "FALSE"));
        Const cNull = Const.of(EnumDesc.of(kindDesc, "NULL"));

        Gizmo gizmo = Gizmo.create(output).withDebugInfo(false).withParameters(false);
        gizmo.class_(directReaderClassNameFor(name), cc -> {
            cc.implements_(DirectReader.class);
            // Dispatch table, built once per reader in the constructor:
            // readers are singletons, so per-request matching allocates
            // nothing beyond the decoded values themselves.
            FieldDesc keysDesc = cc.field("keys", fc -> fc.setType(byte[][].class));
            cc.constructor(mc -> mc.body(bc -> {
                bc.invokeSpecial(ConstructorDesc.of(Object.class), mc.this_());
                List<Expr> keyExprs = new ArrayList<>(fields.size());
                for (BindingSchema.Field field : fields) {
                    keyExprs.add(bc.invokeVirtual(
                            MethodDesc.of(String.class, "getBytes", byte[].class, Charset.class),
                            Const.of(field.wireName()),
                            bc.getStaticField(FieldDesc.of(StandardCharsets.class, "UTF_8"))));
                }
                bc.set(mc.this_().field(keysDesc),
                        bc.newArray(ClassDesc.ofDescriptor("[B"), keyExprs));
                bc.return_();
            }));
            cc.method("type", mc -> {
                mc.returning(Class.class);
                mc.body(bc -> bc.return_(Const.of(type)));
            });
            cc.method("read", mc -> {
                // Erased signature (Object, not T): the JVM resolves
                // interface dispatch by exact descriptor, so a covariant
                // return here would need a synthetic bridge method (same
                // rule as the generated enum bindings).
                mc.returning(Object.class);
                var json = mc.parameter("json", byte[].class);
                // Reserved for nested delegation (future backends consult the
                // mapper); the MVP flat backend bakes everything at build time.
                mc.parameter("mapper", JqMapper.class);
                mc.body(bc -> {
                    LocalVar cursor = bc.localVar("cursor",
                            bc.invokeStatic(mStrict, List.of(json)));
                    ifNotFallback(bc, bc.invokeVirtual(mBeginDoc, cursor));
                    ifNotFallback(bc, bc.invokeVirtual(mBeginObj, cursor));
                    // Typed locals start at their mapping defaults (JVM
                    // defaults): absent fields and explicit nulls both bind
                    // these, exactly like the tree-based mapping.
                    List<LocalVar> values = new ArrayList<>(fields.size());
                    List<LocalVar> seen = new ArrayList<>(fields.size());
                    for (BindingSchema.Field field : fields) {
                        values.add(newValueLocal(bc, field));
                        seen.add(bc.localVar("s_" + field.javaName(), false));
                    }
                    bc.while_(cond -> cond.yield(cond.invokeVirtual(mNext, cursor)), body -> {
                        LocalVar key = body.localVar("k", body.invokeVirtual(mIndex, cursor,
                                body.get(mc.this_().field(keysDesc))));
                        body.if_(body.lt(key, 0), t -> t.throw_(DirectReadFallbackException.class));
                        body.switch_(key, sc -> {
                            for (int i = 0; i < fields.size(); i++) {
                                emitFieldCase(sc, i, fields.get(i), cursor, values.get(i),
                                        seen.get(i), cNull,
                                        mColon, mPeek, cString, cNumber, cTrue, cFalse,
                                        mReadString, mReadLong, mLongValue, mReadInt,
                                        mIntValue, mReadBool, mBoolValue, mReadNull);
                            }
                            sc.default_(b -> b.throw_(DirectReadFallbackException.class));
                        });
                    });
                    ifNotFallback(bc, bc.invokeVirtual(mEnd, cursor));
                    // Required check runs only after the whole input passed
                    // strict validation, preserving syntax-before-binding
                    // failure precedence; declaration order matches
                    // TypeConverter.checkRequired exactly.
                    for (String wire : schema.required()) {
                        LocalVar flag = seen.get(indexOfWire(fields, wire));
                        bc.ifNot(flag, b -> b.throw_(b.invokeVirtual(
                                MethodDesc.of(JqMapperException.class, "prependPath",
                                        JqMapperException.class, String.class),
                                b.new_(JqMapperException.class, List.of(Const.of(
                                        "Missing required field \"" + wire + "\" for "
                                                + type.getSimpleName()))),
                                Const.of(wire))));
                    }
                    // The user constructor runs last: never before complete
                    // validation, never retried on fallback.
                    List<Expr> args = new ArrayList<>(fields.size());
                    List<BindingSchema.Field> byCtor = new ArrayList<>(fields);
                    byCtor.sort(java.util.Comparator.comparingInt(BindingSchema.Field::constructorIndex));
                    for (BindingSchema.Field field : byCtor) {
                        args.add(values.get(fields.indexOf(field)));
                    }
                    bc.return_(bc.new_(
                            ConstructorDesc.of(recordDesc, ctorTypes), args));
                });
            });
        });
    }

    /** Index of a wire name in schema order (required names always resolve). */
    private static int indexOfWire(List<BindingSchema.Field> fields, String wire) {
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).wireName().equals(wire)) return i;
        }
        throw new IllegalStateException("jjq: required wire '" + wire + "' is not a field");
    }

    /** Value local for one field, initialized to its mapping default. */
    private static LocalVar newValueLocal(BlockCreator bc, BindingSchema.Field field) {
        String name = "f_" + field.javaName();
        Class<?> javaType = field.javaType();
        if (javaType == String.class) {
            return bc.localVar(name, String.class, Const.ofNull(String.class));
        }
        if (javaType == int.class) return bc.localVar(name, 0);
        if (javaType == long.class) return bc.localVar(name, 0L);
        if (javaType == boolean.class) return bc.localVar(name, false);
        if (javaType == Integer.class || javaType == Long.class || javaType == Boolean.class) {
            return bc.localVar(name, javaType, Const.ofNull(javaType));
        }
        throw new IllegalArgumentException(
                "jjq: field '" + field.wireName() + "' has unsupported type " + javaType.getName());
    }

    /** Emit one field case: duplicate check, presence, colon, peek, null-or-typed read. */
    private static void emitFieldCase(io.quarkus.gizmo2.creator.SwitchCreator sc, int index,
            BindingSchema.Field field, LocalVar cursor, LocalVar value, LocalVar seen,
            Const cNull, MethodDesc mColon, MethodDesc mPeek,
            Const cString, Const cNumber, Const cTrue, Const cFalse,
            MethodDesc mReadString, MethodDesc mReadLong, MethodDesc mLongValue,
            MethodDesc mReadInt, MethodDesc mIntValue, MethodDesc mReadBool, MethodDesc mBoolValue,
            MethodDesc mReadNull) {
        sc.caseOf(index, b -> {
            b.if_(seen, t -> t.throw_(DirectReadFallbackException.class));
            b.set(seen, Const.of(true));
            ifNotFallback(b, b.invokeVirtual(mColon, cursor));
            // The shape is classified after the colon, when the cursor is on
            // the value (a peek at the colon would always read OTHER).
            LocalVar peek = b.localVar("pk_" + field.javaName(), b.invokeVirtual(mPeek, cursor));
            // Explicit null counts as present and keeps the mapping default.
            b.ifElse(b.eq(peek, cNull),
                    t -> ifNotFallback(t, t.invokeVirtual(mReadNull, cursor)),
                    e -> emitTypedRead(e, field, cursor, value, peek,
                            cString, cNumber, cTrue, cFalse,
                            mReadString, mReadLong, mLongValue, mReadInt, mIntValue,
                            mReadBool, mBoolValue));
        });
    }

    /** Emit the canonical typed read for one field (mismatch means fallback). */
    private static void emitTypedRead(BlockCreator e, BindingSchema.Field field,
            LocalVar cursor, LocalVar value, LocalVar peek,
            Const cString, Const cNumber, Const cTrue, Const cFalse,
            MethodDesc mReadString, MethodDesc mReadLong, MethodDesc mLongValue,
            MethodDesc mReadInt, MethodDesc mIntValue, MethodDesc mReadBool, MethodDesc mBoolValue) {
        Class<?> javaType = field.javaType();
        switch (field.kind()) {
            case STRING -> {
                ifNotFallback(e, e.eq(peek, cString));
                LocalVar v = e.localVar("v_" + field.javaName(),
                        e.invokeVirtual(mReadString, cursor));
                e.ifNull(v, t -> t.throw_(DirectReadFallbackException.class));
                e.set(value, v);
            }
            case INT -> {
                ifNotFallback(e, e.eq(peek, cNumber));
                ifNotFallback(e, e.invokeVirtual(mReadInt, cursor));
                LocalVar holder = e.localVar("h_" + field.javaName(),
                        e.invokeVirtual(mIntValue, cursor));
                e.set(value, javaType.isPrimitive() ? holder : e.box(holder));
            }
            case LONG -> {
                ifNotFallback(e, e.eq(peek, cNumber));
                ifNotFallback(e, e.invokeVirtual(mReadLong, cursor));
                LocalVar holder = e.localVar("h_" + field.javaName(),
                        e.invokeVirtual(mLongValue, cursor));
                e.set(value, javaType.isPrimitive() ? holder : e.box(holder));
            }
            case BOOLEAN -> {
                e.ifElse(e.eq(peek, cTrue),
                        t -> ifNotFallback(t, t.invokeVirtual(mReadBool, cursor)),
                        u -> {
                            ifNotFallback(u, u.eq(peek, cFalse));
                            ifNotFallback(u, u.invokeVirtual(mReadBool, cursor));
                        });
                LocalVar holder = e.localVar("h_" + field.javaName(),
                        e.invokeVirtual(mBoolValue, cursor));
                e.set(value, javaType.isPrimitive() ? holder : e.box(holder));
            }
            default -> throw new IllegalArgumentException(
                    "jjq: field '" + field.wireName() + "' has kind " + field.kind());
        }
    }

    /** Throw the fallback signal unless the condition holds. */
    private static void ifNotFallback(BlockCreator bc, Expr condition) {
        bc.ifNot(condition, b -> b.throw_(DirectReadFallbackException.class));
    }

    /**
     * Emit the serialization fallback both paths share: per-constant wire
     * names first (baked if-chain, no map allocation), else {@code name()}.
     * The caller must invoke this in tail position: every path returns.
     * Mirrors {@code TypeConverter} exactly, including the accessor-absent
     * shape.
     */
    private static void emitWriteFallback(BlockCreator bc, LocalVar bound,
            TypeConverter.EnumHandlers handlers) {
        LocalVar name = bc.localVar("name", bc.invokeVirtual(
                MethodDesc.of(Enum.class, "name", String.class), bound));
        for (var entry : handlers.wireNames().entrySet()) {
            bc.if_(bc.invokeVirtual(
                    MethodDesc.of(String.class, "equals", boolean.class, Object.class),
                    name, Const.of(entry.getKey())),
                    then -> then.return_(bc.invokeStatic(
                            MethodDesc.of(JqString.class, "of", JqString.class, String.class),
                            List.of(Const.of(entry.getValue())))));
        }
        bc.return_(bc.invokeStatic(
                MethodDesc.of(JqString.class, "of", JqString.class, String.class),
                List.of(name)));
    }

    @BuildStep
    void registerForReflection(List<JjqMappedTypeBuildItem> mappedTypes,
            BuildProducer<ReflectiveClassBuildItem> reflective) {
        for (JjqMappedTypeBuildItem type : mappedTypes) {
            reflective.produce(ReflectiveClassBuildItem.builder(type.getClassName())
                    .methods().constructors()
                    .reason("jjq: @JqMapped record components accessed by generated mappings")
                    .build());
            // Weak: the processor may not have run for this type (documented
            // reflection fallback) — a missing class must not fail the build.
            reflective.produce(ReflectiveClassBuildItem.builder(type.getMappingClassName())
                    .weak(true).constructors()
                    .reason("jjq: generated mapping fallback loading (JqMapper.loadGenerated)")
                    .build());
        }
    }

    /**
     * Build the CDI mapper without reflection: mappings and bridges are
     * discovered here, then a producer class is generated whose body
     * constructs every instance with direct constructor calls. Generated
     * mappings missing from the classpath are skipped (the runtime
     * reflection fallback still covers them); bridges must load since
     * their modules are explicit dependencies.
     */
    @BuildStep
    void createMapperBean(JjqBuildTimeConfig config,
            CurateOutcomeBuildItem curateOutcome,
            CombinedIndexBuildItem combinedIndex,
            List<JjqMappedTypeBuildItem> mappedTypes,
            List<JjqEnumBindingBuildItem> enumBindings,
            List<JjqPolyFamilyBuildItem> polyFamilyItems,
            BuildProducer<GeneratedBeanBuildItem> generatedBeans,
            BuildProducer<JjqDirectReaderBuildItem> directReaders) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        List<String> mappingClassNames = new ArrayList<>();
        for (JjqMappedTypeBuildItem type : mappedTypes) {
            try {
                cl.loadClass(type.getMappingClassName());
                mappingClassNames.add(type.getMappingClassName());
            } catch (ClassNotFoundException e) {
                // No generated mapping (processor did not run for this type):
                // leave it to the runtime reflection fallback instead of
                // failing the boot.
                LOG.warnf("jjq: generated mapping %s not found, type will use reflection-based mapping",
                        type.getMappingClassName());
            }
        }
        // Bridges come from explicit module dependencies only.
        List<String> bridgeClassNames = bridgeClassNames(curateOutcome.getApplicationModel().getDependencies());
        List<AnnotationBridge> bridges = new ArrayList<>();
        for (String name : bridgeClassNames) {
            try {
                Class<?> cls = Class.forName(name, true, cl);
                bridges.add((AnnotationBridge) cls.getDeclaredConstructor().newInstance());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("jjq: cannot instantiate annotation bridge " + name
                        + " — check the bridge module dependency", e);
            }
        }
        java.util.Map<Class<?>, PolyConfig> polyFamilies = new java.util.LinkedHashMap<>();
        java.util.Map<Class<?>, KeyPolyConfig> keyFamilies = new java.util.LinkedHashMap<>();
        for (JjqPolyFamilyBuildItem family : polyFamilyItems) {
            Class<?> base;
            try {
                base = Class.forName(family.getClassName(), false, cl);
            } catch (ClassNotFoundException | LinkageError e) {
                LOG.warnf("jjq: polymorphic family %s not found, type will use reflection-based mapping",
                        family.getClassName());
                continue;
            }
            try {
                KeyPolyConfig keys = KeyPolyMapping.resolveKeyConfig(base);
                if (keys != null) {
                    keyFamilies.put(base, keys);
                    continue;
                }
                PolyConfig poly = PolyMapping.resolvePolyConfig(base, bridges);
                if (poly != null) {
                    polyFamilies.put(base, poly);
                }
            } catch (JqMapperException e) {
                throw new IllegalStateException(
                        "jjq: invalid polymorphic family " + base.getName() + ": " + e.getMessage(), e);
            }
        }
        List<String> enumBindingClassNames = enumBindings.stream()
                .map(JjqEnumBindingBuildItem::getBindingClassName)
                .toList();
        List<String> directReaderClassNames = generateDirectReaders(config.directReaders(),
                combinedIndex.getIndex(), mappedTypes, bridges, config.failOnUnknownProperties(),
                new GeneratedBeanGizmo2Adaptor(generatedBeans), directReaders);
        generateMapperProducer(new GeneratedBeanGizmo2Adaptor(generatedBeans),
                mappingClassNames, config.failOnUnknownProperties(), bridgeClassNames, enumBindingClassNames,
                directReaderClassNames,
                polyFamilies, keyFamilies,
                httpProblemPresent(curateOutcome.getApplicationModel().getDependencies()));
    }

    /**
     * Generate one direct reader per eligible request body type (issue #173).
     * Schemas compile against a probe mapper with the application's bridges
     * and strictness, so generation sees exactly the runtime binding rules.
     * Ineligible types and generation guardrails skip with a warning and
     * keep the existing path; invalid mapping configuration also skips here
     * and keeps its established first-use failure instead of failing the
     * build for an endpoint that may never be called.
     *
     * @return generated reader class names, in discovery order
     */
    static List<String> generateDirectReaders(boolean directReadersEnabled, IndexView index,
            List<JjqMappedTypeBuildItem> mappedTypes, List<AnnotationBridge> bridges,
            boolean failOnUnknown, ClassOutput output,
            BuildProducer<JjqDirectReaderBuildItem> produced) {
        List<String> readers = new ArrayList<>();
        if (!directReadersEnabled) return readers;
        Set<String> mapped = new LinkedHashSet<>();
        for (JjqMappedTypeBuildItem type : mappedTypes) mapped.add(type.getClassName());
        Set<String> requests = requestBodyClassNames(index, mapped);
        if (requests.isEmpty()) return readers;
        JqMapper.Builder probe = JqMapper.builder().failOnUnknownProperties(failOnUnknown);
        for (AnnotationBridge bridge : bridges) probe.bridge(bridge);
        JqMapper probeMapper = probe.build();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        for (String className : requests) {
            Class<?> type;
            try {
                type = Class.forName(className, false, cl);
            } catch (ClassNotFoundException | LinkageError e) {
                LOG.warnf("jjq: request type %s not found, keeping the existing path", className);
                continue;
            }
            BindingSchema schema;
            try {
                schema = probeMapper.bindingSchemaFor(type);
            } catch (JqMapperException e) {
                // Invalid mapping configuration: keep the established
                // first-use failure instead of failing the build here.
                LOG.warnf("jjq: no direct reader for %s (%s), keeping the existing path",
                        className, e.getMessage());
                continue;
            }
            if (!schema.eligibleForDirectReader()) continue;
            try {
                generateDirectReader(output, type, schema);
            } catch (IllegalArgumentException e) {
                LOG.warnf("jjq: %s, type will use the existing path", e.getMessage());
                continue;
            }
            String reader = directReaderClassNameFor(className);
            readers.add(reader);
            produced.produce(new JjqDirectReaderBuildItem(className, reader));
        }
        return readers;
    }

    /**
     * Emit the mapper producer: a {@code @Singleton} bean whose
     * {@code @Produces @Singleton} method builds the application mapper
     * with direct {@code new} calls only. Every class referenced here was
     * verified loadable above, so the generated bytecode never touches
     * {@code Class.forName} or reflective construction on any path.
     *
     * @param output                  where the generated class bytes go
     * @param mappingClassNames       generated {@code _JqMapping} classes present on the classpath
     * @param failOnUnknownProperties mapper strictness flag, baked in as a constant
     * @param bridgeClassNames        {@code AnnotationBridge} implementations to construct directly
     * @param enumBindingClassNames   generated {@code _EnumBinding} classes to register directly
     * @param directReaderClassNames  generated {@code _DirectReader} classes to register directly
     * @param polyFamilies            discriminator family bases to dispatch tables (discovery order)
     * @param keyFamilies             key-presence family bases to dispatch tables (discovery order)
     * @param alwaysProblemDetails    whether the http-problem extension owns error rendering
     */
    static void generateMapperProducer(ClassOutput output, List<String> mappingClassNames,
            boolean failOnUnknownProperties, List<String> bridgeClassNames, List<String> enumBindingClassNames,
            List<String> directReaderClassNames,
            java.util.Map<Class<?>, PolyConfig> polyFamilies, java.util.Map<Class<?>, KeyPolyConfig> keyFamilies,
            boolean alwaysProblemDetails) {
        Gizmo gizmo = Gizmo.create(output).withDebugInfo(false).withParameters(false);
        gizmo.class_(PRODUCER_CLASS, cc -> {
            cc.defaultConstructor();
            cc.addAnnotation(Singleton.class);
            cc.method("produceMapper", mc -> {
                mc.returning(JqMapper.class);
                mc.addAnnotation(jakarta.enterprise.inject.Produces.class);
                mc.addAnnotation(Singleton.class);
                mc.addAnnotation(io.quarkus.runtime.Startup.class);
                mc.body(bc -> {
                    LocalVar builder = bc.localVar("builder", bc.invokeStatic(
                            MethodDesc.of(JqMapper.class, "builder", JqMapper.Builder.class)));
                    bc.set(builder, bc.invokeVirtual(
                            MethodDesc.of(JqMapper.Builder.class, "failOnUnknownProperties",
                                    JqMapper.Builder.class, boolean.class),
                            builder, Const.of(failOnUnknownProperties)));
                    for (String bridge : bridgeClassNames) {
                        bc.set(builder, bc.invokeVirtual(
                                MethodDesc.of(JqMapper.Builder.class, "bridge",
                                        JqMapper.Builder.class, AnnotationBridge.class),
                                builder, bc.new_(java.lang.constant.ClassDesc.of(bridge))));
                    }
                    for (String mapping : mappingClassNames) {
                        bc.set(builder, bc.invokeVirtual(
                                MethodDesc.of(JqMapper.Builder.class, "register",
                                        JqMapper.Builder.class, GeneratedMapping.class),
                                builder, bc.new_(java.lang.constant.ClassDesc.of(mapping))));
                    }
                    for (String binding : enumBindingClassNames) {
                        bc.set(builder, bc.invokeVirtual(
                                MethodDesc.of(JqMapper.Builder.class, "registerEnum",
                                        JqMapper.Builder.class, EnumBinding.class),
                                builder, bc.new_(java.lang.constant.ClassDesc.of(binding))));
                    }
                    for (String reader : directReaderClassNames) {
                        bc.set(builder, bc.invokeVirtual(
                                MethodDesc.of(JqMapper.Builder.class, "registerDirectReader",
                                        JqMapper.Builder.class, DirectReader.class),
                                builder, bc.new_(java.lang.constant.ClassDesc.of(reader))));
                    }
                    for (var poly : polyFamilies.entrySet()) {
                        bc.set(builder, bc.invokeVirtual(
                                MethodDesc.of(JqMapper.Builder.class, "registerPoly",
                                        JqMapper.Builder.class, Class.class, PolyConfig.class),
                                builder, Const.of(poly.getKey()), emitPolyConfig(bc, poly.getValue())));
                    }
                    for (var keys : keyFamilies.entrySet()) {
                        bc.set(builder, bc.invokeVirtual(
                                MethodDesc.of(JqMapper.Builder.class, "registerKeys",
                                        JqMapper.Builder.class, Class.class, KeyPolyConfig.class),
                                builder, Const.of(keys.getKey()), emitKeyConfig(bc, keys.getValue())));
                    }
                    bc.return_(bc.invokeVirtual(
                            MethodDesc.of(JqMapper.Builder.class, "build", JqMapper.class), builder));
                });
            });
            cc.method("produceProblemPolicy", mc -> {
                mc.returning(JqProblemPolicy.class);
                mc.addAnnotation(jakarta.enterprise.inject.Produces.class);
                mc.addAnnotation(Singleton.class);
                mc.body(bc -> bc.return_(bc.new_(JqProblemPolicy.class,
                        List.of(Const.of(alwaysProblemDetails)))));
            });
        });
    }

    /**
     * Fixed-arity {@code Map.of}/{@code List.of} call: every argument erases
     * to {@code Object}, so the overload resolves by arity. Larger
     * collections need chunked fallbacks the producer avoids.
     */
    private static Expr emitOfCall(BlockCreator bc, Class<?> owner, String what, List<Expr> args, int max) {
        if (args.size() > max) {
            throw new IllegalStateException("jjq: " + what + " has " + args.size()
                    + " entries, producer codegen supports up to " + max);
        }
        Class<?>[] params = args.stream().map(a -> Object.class).toArray(Class[]::new);
        return bc.invokeStatic(MethodDesc.of(owner, "of", owner, params), args);
    }

    /**
     * Emit a {@code new PolyConfig(property, Map.of(...), existing)} expression.
     * Wire entries ride as string and class literals: direct linkage, no
     * reflective lookup, and the subtype classes stay reachable in native.
     */
    private static Expr emitPolyConfig(BlockCreator bc, PolyConfig config) {
        List<Expr> mapArgs = new ArrayList<>();
        for (var entry : config.subtypes().entrySet()) {
            mapArgs.add(Const.of(entry.getKey()));
            mapArgs.add(Const.of(entry.getValue()));
        }
        Expr map = emitOfCall(bc, java.util.Map.class,
                "family '" + config.property() + "' wire values", mapArgs, 20);
        return bc.new_(PolyConfig.class,
                List.of(Const.of(config.property()), map, Const.of(config.existingProperty())));
    }

    /**
     * Emit a {@code new KeyPolyConfig(List.of(new KeyRule(...)), default)}
     * expression. A missing default emits a typed null.
     */
    private static Expr emitKeyConfig(BlockCreator bc, KeyPolyConfig config) {
        List<Expr> ruleExprs = new ArrayList<>();
        for (var rule : config.rules()) {
            ruleExprs.add(bc.new_(KeyPolyConfig.KeyRule.class,
                    List.of(Const.of(rule.key()), Const.of(rule.subtype()))));
        }
        Expr list = emitOfCall(bc, java.util.List.class, "key-presence family rules", ruleExprs, 10);
        Expr def = config.defaultSubtype() == null
                ? Const.ofNull(Class.class)
                : Const.of(config.defaultSubtype());
        return bc.new_(KeyPolyConfig.class, List.of(list, def));
    }

    @BuildStep
    void registerRestProviders(Capabilities capabilities,
            CurateOutcomeBuildItem curateOutcome,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<MessageBodyReaderBuildItem> additionalReaders,
            BuildProducer<MessageBodyWriterBuildItem> additionalWriters,
            BuildProducer<ParamConverterBuildItem> paramConverters,
            BuildProducer<ExceptionMapperBuildItem> exceptionMappers) {
        if (capabilities.isMissing(Capability.REST)) {
            return;
        }
        // Providers from dependency jars are outside application scanning,
        // so register them as beans explicitly (this is what carries the
        // injected shared mapper to the serving instances); the serialiser
        // entries below need explicit registration for the same reason.
        additionalBeans.produce(AdditionalBeanBuildItem.builder()
                .addBeanClasses(List.of(BODY_PROVIDER_CLASSES))
                .setDefaultScope(SINGLETON)
                .setUnremovable()
                .build());
        additionalReaders.produce(new MessageBodyReaderBuildItem.Builder(
                JqMappedMessageBodyReader.class.getName(), Object.class.getName())
                .setMediaTypeStrings(List.of(JSON))
                .setBuiltin(true)
                .setRuntimeType(RuntimeType.SERVER)
                .build());
        additionalWriters.produce(new MessageBodyWriterBuildItem.Builder(
                JqMappedMessageBodyWriter.class.getName(), Object.class.getName())
                .setMediaTypeStrings(List.of(JSON))
                .setBuiltin(true)
                .setRuntimeType(RuntimeType.SERVER)
                .build());
        additionalReaders.produce(new MessageBodyReaderBuildItem.Builder(
                JqValueMessageBodyReader.class.getName(), JqValue.class.getName())
                .setMediaTypeStrings(List.of(JSON))
                .setBuiltin(true)
                .setRuntimeType(RuntimeType.SERVER)
                .build());
        additionalWriters.produce(new MessageBodyWriterBuildItem.Builder(
                JqValueMessageBodyWriter.class.getName(), JqValue.class.getName())
                .setMediaTypeStrings(List.of(JSON))
                .setBuiltin(true)
                .setRuntimeType(RuntimeType.SERVER)
                .build());
        paramConverters.produce(new ParamConverterBuildItem(
                JqValueParamConverterProvider.class.getName(), Priorities.USER, true));
        // Scoped to JqBadRequestException only: request-driven rejections
        // render as 400 here, everything else keeps container defaults.
        exceptionMappers.produce(new ExceptionMapperBuildItem(
                JqBadRequestExceptionMapper.class.getName(),
                JqBadRequestException.class.getName(), Priorities.USER, true));
        // Engine-free problem rendering (issue #176): only when http-problem
        // is present and no JSON engine can serve it instead. The writer
        // class is named, never loaded, so engine-backed applications are
        // untouched.
        if (problemWriterWanted(curateOutcome.getApplicationModel().getDependencies())) {
            additionalBeans.produce(AdditionalBeanBuildItem.builder()
                    .addBeanClasses(List.of(
                            io.hyperfoil.tools.jjq.jakarta.JqProblemWriter.class.getName()))
                    .setDefaultScope(SINGLETON)
                    .setUnremovable()
                    .build());
            additionalWriters.produce(new MessageBodyWriterBuildItem.Builder(
                    io.hyperfoil.tools.jjq.jakarta.JqProblemWriter.class.getName(), HTTP_PROBLEM_CLASS)
                    .setMediaTypeStrings(List.of(PROBLEM_JSON))
                    .setBuiltin(true)
                    .setRuntimeType(RuntimeType.SERVER)
                    .build());
        }
    }

    @BuildStep
    void registerClassicProviders(Capabilities capabilities,
            CurateOutcomeBuildItem curateOutcome,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ResteasyJaxrsProviderBuildItem> providers) {
        if (capabilities.isMissing(Capability.RESTEASY)) {
            return;
        }
        additionalBeans.produce(AdditionalBeanBuildItem.builder()
                .addBeanClasses(List.of(PROVIDER_CLASSES))
                .setDefaultScope(SINGLETON)
                .setUnremovable()
                .build());
        for (String provider : PROVIDER_CLASSES) {
            providers.produce(new ResteasyJaxrsProviderBuildItem(provider));
        }
        if (problemWriterWanted(curateOutcome.getApplicationModel().getDependencies())) {
            additionalBeans.produce(AdditionalBeanBuildItem.builder()
                    .addBeanClasses(List.of(
                            io.hyperfoil.tools.jjq.jakarta.JqProblemWriter.class.getName()))
                    .setDefaultScope(SINGLETON)
                    .setUnremovable()
                    .build());
            providers.produce(new ResteasyJaxrsProviderBuildItem(
                    io.hyperfoil.tools.jjq.jakarta.JqProblemWriter.class.getName()));
        }
    }

    @BuildStep
    void registerHibernateFormatMapper(Capabilities capabilities,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans) {
        if (capabilities.isMissing(Capability.HIBERNATE_ORM)) {
            return;
        }
        // Jackson coexistence is rejected above, so reaching here means a
        // Jackson-free application where ours is the only FormatMapper.
        additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(JjqFormatMapper.class));
    }
}

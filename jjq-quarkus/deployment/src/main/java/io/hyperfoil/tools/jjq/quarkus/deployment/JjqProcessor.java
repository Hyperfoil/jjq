package io.hyperfoil.tools.jjq.quarkus.deployment;

import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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
import io.hyperfoil.tools.jjq.mapper.JqMapped;
import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.mapper.EnumBinding;
import io.hyperfoil.tools.jjq.mapper.JqMapperException;
import io.hyperfoil.tools.jjq.mapper.GeneratedMapping;
import io.hyperfoil.tools.jjq.mapper.TypeConverter;
import io.hyperfoil.tools.jjq.mapper.spi.AnnotationBridge;
import io.hyperfoil.tools.jjq.quarkus.runtime.JjqFormatMapper;
import io.hyperfoil.tools.jjq.quarkus.runtime.JjqBuildTimeConfig;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;
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
import io.quarkus.gizmo2.Gizmo;
import io.quarkus.gizmo2.LocalVar;
import io.quarkus.gizmo2.creator.BlockCreator;
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
    private static final DotName SINGLETON = DotName.createSimple(Singleton.class.getName());

    private static final String JSON = "application/json";

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
        IndexView index = combinedIndex.getIndex();
        for (AnnotationInstance annotation : index.getAnnotations(JQ_MAPPED)) {
            if (annotation.target().kind() == AnnotationTarget.Kind.CLASS) {
                String className = annotation.target().asClass().name().toString();
                mappedTypes.produce(new JjqMappedTypeBuildItem(className, mappingClassNameFor(className)));
            }
        }
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
            List<JjqMappedTypeBuildItem> mappedTypes,
            List<JjqEnumBindingBuildItem> enumBindings,
            BuildProducer<GeneratedBeanBuildItem> generatedBeans) {
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
        List<String> enumBindingClassNames = enumBindings.stream()
                .map(JjqEnumBindingBuildItem::getBindingClassName)
                .toList();
        generateMapperProducer(new GeneratedBeanGizmo2Adaptor(generatedBeans),
                mappingClassNames, config.failOnUnknownProperties(), bridgeClassNames, enumBindingClassNames);
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
     */
    static void generateMapperProducer(ClassOutput output, List<String> mappingClassNames,
            boolean failOnUnknownProperties, List<String> bridgeClassNames, List<String> enumBindingClassNames) {
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
                    bc.return_(bc.invokeVirtual(
                            MethodDesc.of(JqMapper.Builder.class, "build", JqMapper.class), builder));
                });
            });
        });
    }

    @BuildStep
    void registerRestProviders(Capabilities capabilities,
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
    }

    @BuildStep
    void registerClassicProviders(Capabilities capabilities,
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

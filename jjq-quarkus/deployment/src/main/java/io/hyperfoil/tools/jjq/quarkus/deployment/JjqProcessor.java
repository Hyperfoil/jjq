package io.hyperfoil.tools.jjq.quarkus.deployment;

import java.util.List;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;

import io.hyperfoil.tools.jjq.jakarta.JqMappedMessageBodyReader;
import io.hyperfoil.tools.jjq.jakarta.JqMappedMessageBodyWriter;
import io.hyperfoil.tools.jjq.jakarta.JqValueMessageBodyReader;
import io.hyperfoil.tools.jjq.jakarta.JqValueMessageBodyWriter;
import io.hyperfoil.tools.jjq.jakarta.JqValueParamConverterProvider;
import io.hyperfoil.tools.jjq.mapper.JqMapped;
import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.quarkus.runtime.JjqFormatMapper;
import io.hyperfoil.tools.jjq.quarkus.runtime.JjqRecorder;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.resteasy.common.spi.ResteasyJaxrsProviderBuildItem;
import io.quarkus.resteasy.reactive.spi.MessageBodyReaderBuildItem;
import io.quarkus.resteasy.reactive.spi.MessageBodyWriterBuildItem;
import io.quarkus.resteasy.reactive.spi.ParamConverterBuildItem;
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
 * {@code FormatMapper} is wired when Jackson is absent.</p>
 */
public class JjqProcessor {

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
    };

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem("jjq");
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
                    .reason("jjq: generated mapping instantiated by JjqRecorder")
                    .build());
        }
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void createMapperBean(JjqRecorder recorder,
            List<JjqMappedTypeBuildItem> mappedTypes,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeans) {
        List<String> mappingClassNames = mappedTypes.stream()
                .map(JjqMappedTypeBuildItem::getMappingClassName)
                .toList();
        syntheticBeans.produce(SyntheticBeanBuildItem.configure(JqMapper.class)
                .scope(Singleton.class)
                .unremovable()
                .runtimeValue(recorder.createMapper(mappingClassNames))
                .done());
    }

    @BuildStep
    void registerRestProviders(Capabilities capabilities,
            BuildProducer<UnremovableBeanBuildItem> unremovableBeans,
            BuildProducer<MessageBodyReaderBuildItem> additionalReaders,
            BuildProducer<MessageBodyWriterBuildItem> additionalWriters,
            BuildProducer<ParamConverterBuildItem> paramConverters) {
        if (capabilities.isMissing(Capability.REST)) {
            return;
        }
        // The providers are already CDI beans through RESTEasy's @Provider
        // bean-defining annotation; pin them unremovable (programmatic lookup
        // does not protect from unused-bean removal) and register explicit
        // serialiser entries (application scanning only covers app classes,
        // not providers from dependency jars).
        unremovableBeans.produce(UnremovableBeanBuildItem.beanClassNames(BODY_PROVIDER_CLASSES));
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
        // Jackson's own FormatMapper keeps precedence when both are present;
        // ours fills the gap for Jackson-free applications.
        if (capabilities.isPresent(Capability.JACKSON)) {
            return;
        }
        additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(JjqFormatMapper.class));
    }
}

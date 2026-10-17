package io.hyperfoil.tools.jjq.quarkus.runtime;

import java.util.List;

import org.jboss.logging.Logger;

import io.hyperfoil.tools.jjq.mapper.GeneratedMapping;
import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;

/**
 * Build-time recorder that creates the CDI-managed {@link JqMapper} singleton.
 *
 * <p>All {@code _JqMapping} class names are collected by the deployment module
 * via Jandex and passed in here. Instantiation is direct
 * ({@code Class.forName} + no-arg constructor on classes the build step already
 * registered for reflection) — no {@code ServiceLoader}, no runtime scanning.</p>
 */
@Recorder
public class JjqRecorder {

    private static final Logger LOG = Logger.getLogger(JjqRecorder.class);

    /** Creates a mapper with all discovered generated mappings pre-registered. */
    public RuntimeValue<JqMapper> createMapper(List<String> mappingClassNames, boolean failOnUnknownProperties) {
        JqMapper.Builder builder = JqMapper.builder().failOnUnknownProperties(failOnUnknownProperties);
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        for (String name : mappingClassNames) {
            try {
                Class<?> cls = Class.forName(name, true, cl);
                builder.register((GeneratedMapping<?>) cls.getDeclaredConstructor().newInstance());
            } catch (ClassNotFoundException e) {
                // No generated mapping (processor did not run for this type):
                // leave it to JqMapper's runtime fallback instead of failing the boot.
                LOG.warnf("jjq: generated mapping %s not found, type will use reflection-based mapping", name);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("jjq: cannot instantiate generated mapping " + name, e);
            }
        }
        return new RuntimeValue<>(builder.build());
    }
}

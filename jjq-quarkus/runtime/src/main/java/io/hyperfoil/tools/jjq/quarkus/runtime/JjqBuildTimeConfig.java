package io.hyperfoil.tools.jjq.quarkus.runtime;

import static io.quarkus.runtime.annotations.ConfigPhase.BUILD_AND_RUN_TIME_FIXED;

import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Build-time configuration for the {@code jjq-quarkus} extension.
 *
 * <p>Fixed at build time because the CDI mapper is constructed once during
 * static initialization; a runtime change could not take effect without a
 * rebuild (same constraint as {@code quarkus-jackson} mapper settings).</p>
 */
@ConfigRoot(phase = BUILD_AND_RUN_TIME_FIXED)
@ConfigMapping(prefix = "quarkus.jjq")
public interface JjqBuildTimeConfig {

    /**
     * Fail request binding on unrecognized JSON properties instead of
     * ignoring them (Jackson {@code FAIL_ON_UNKNOWN_PROPERTIES} parity).
     * Disabled by default: unknown keys are ignored for forward
     * compatibility. Applied once when the CDI mapper is created — zero
     * per-request cost.
     */
    @WithDefault("false")
    boolean failOnUnknownProperties();

    /**
     * Generate direct byte-to-record readers for eligible flat
     * {@code @JqMapped} request records (issue #170). Enabled by default:
     * readers decode canonical request bodies without a {@code JqValue}
     * tree (plus the #215 positional-dispatch hint and double/float
     * support), and inputs outside their canonical fast path fall back to
     * the strict parse-plus-map path, so enabling the flag never changes
     * accepted inputs or error behavior. Set {@code false} to generate no
     * reader classes at all and keep request handling exactly on the
     * strict parse-plus-map path.
     */
    @WithDefault("true")
    boolean directReaders();
}

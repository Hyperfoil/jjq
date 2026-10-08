package io.hyperfoil.tools.jjq.mapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Controls the bind direction of a field: deserialize-only or serialize-only.
 *
 * <p>A {@code WRITE_ONLY} field still reads (old documents carrying it keep
 * working; absent keys keep their defaults) but is never written back —
 * the shape for migrating away from a legacy key:</p>
 *
 * <pre>{@code
 * @JqMapped
 * record SpawnConfig(
 *     @JqAccess(JqAccess.Access.WRITE_ONLY) @JqName("host-path") String hostPath,
 *     @JqName("host-paths") List<String> hostPaths
 * ) {}
 * }</pre>
 *
 * <p>A {@code READ_ONLY} field is the mirror: serialized but never bound on
 * deserialization (server-computed values, for example).</p>
 *
 * <p>{@link JqIgnore} stays bidirectional and wins over this annotation when
 * both are present. An explicit value wins over bridge direction control
 * (e.g. Jackson {@code @JsonProperty(access = ...)}).</p>
 *
 * @see JqIgnore
 * @see JqName
 * @see JqMapped
 */
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface JqAccess {
    /**
     * The allowed bind direction.
     *
     * @return {@code WRITE_ONLY} (deserialize-only) or {@code READ_ONLY} (serialize-only)
     */
    Access value();

    /**
     * Bind directions, mirroring Jackson's {@code JsonProperty.Access} names.
     */
    enum Access {
        /**
         * Deserialize-only: bound on read (absent keys tolerated), skipped on write.
         */
        WRITE_ONLY,

        /**
         * Serialize-only: emitted on write, never bound on read.
         */
        READ_ONLY
    }
}

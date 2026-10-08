package io.hyperfoil.tools.jjq.mapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Controls whether {@code getX()}/{@code isX()} getters are bound as property
 * accessors ("bind fields, ignore getters").
 *
 * <p>Some classes hide fields behind smart accessors that must never be used
 * for binding — a getter that resolves live state instead of returning the
 * field, or one that recurses through serialization. Suppressing getter
 * binding falls back to direct field reads, mirroring the reflection path:</p>
 *
 * <pre>{@code
 * @JqMapped
 * @JqVisibility(getters = JqVisibility.Visibility.NONE,
 *               isGetters = JqVisibility.Visibility.NONE)
 * public class ClaudeConfig {
 *     private String apiKey;
 *
 *     public ClaudeConfig() {}
 *
 *     // Resolves the live account — never bound, never serialized directly
 *     public String getApiKey() { return resolveLiveAccount(); }
 * }
 * }</pre>
 *
 * <p>A native {@code NONE} suppresses unconditionally; with {@code ANY} (the
 * default) a bridge {@code NONE} (e.g. Jackson {@code @JsonAutoDetect}) still
 * suppresses. There is no field axis: fields are always bound on both paths.
 * Records ignore this annotation (canonical accessors, no {@code getX()}
 * binding).</p>
 *
 * @see JqMapped
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface JqVisibility {
    /**
     * Whether {@code getX()} getters bind as property readers.
     *
     * @return {@code NONE} to read fields directly instead
     */
    Visibility getters() default Visibility.ANY;

    /**
     * Whether {@code isX()} getters bind as property readers.
     *
     * @return {@code NONE} to read fields directly instead
     */
    Visibility isGetters() default Visibility.ANY;

    /**
     * Getter visibility levels.
     */
    enum Visibility {
        /**
         * Bind getters normally (default behavior).
         */
        ANY,

        /**
         * Never bind getters of this shape; read fields directly.
         */
        NONE
    }
}

package io.hyperfoil.tools.jjq.mapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies a naming strategy for mapping between Java field names and JSON keys.
 *
 * <p>Applied at the class level, this annotation transforms all field names
 * for both deserialization (the jq extraction expression) and serialization
 * (the JSON output key). Fields with explicit {@link JqField} annotations
 * are not affected by the naming strategy.</p>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @JqMapped
 * @JqNaming(JqNaming.Strategy.SNAKE_CASE)
 * record PcpMetric(String metricName, double metricValue, boolean isActive) {}
 *
 * // Deserializes from: {"metric_name":"cpu", "metric_value":0.5, "is_active":true}
 * // Serializes to:     {"metric_name":"cpu", "metric_value":0.5, "is_active":true}
 * }</pre>
 *
 * <h2>Interaction with @JqField</h2>
 * <pre>{@code
 * @JqMapped
 * @JqNaming(JqNaming.Strategy.SNAKE_CASE)
 * record Mixed(
 *     String userName,                            // -> "user_name" (transformed)
 *     @JqField(".custom_key") String customField  // -> "custom_key" (explicit, not transformed)
 * ) {}
 * }</pre>
 *
 * @see JqMapped
 * @see JqField
 *
 * <p>Jackson equivalent: {@code @JsonNaming} (strategy-based, fewer strategies).</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface JqNaming {

    /**
     * The naming strategy to apply.
     *
     * @return the configured naming strategy
     */
    Strategy value();

    /**
     * Naming strategies for field name transformation.
     */
    enum Strategy {
        /**
         * No transformation — Java field names are used as-is (default behavior).
         */
        IDENTITY,

        /**
         * Convert camelCase to snake_case.
         * <ul>
         *   <li>{@code metricName} → {@code metric_name}</li>
         *   <li>{@code isActive} → {@code is_active}</li>
         *   <li>{@code httpURL} → {@code http_url}</li>
         *   <li>{@code already_snake} → {@code already_snake}</li>
         * </ul>
         */
        SNAKE_CASE,

        /**
         * Convert to lowerCamelCase (decapitalize leading acronyms).
         * <ul>
         *   <li>{@code URL} → {@code url}</li>
         *   <li>{@code URLValue} → {@code urlValue}</li>
         *   <li>{@code Name} → {@code name}</li>
         * </ul>
         */
        LOWER_CAMEL_CASE,

        /**
         * Convert to UpperCamelCase.
         * <ul>
         *   <li>{@code name} → {@code Name}</li>
         *   <li>{@code host_paths} → {@code HostPaths}</li>
         * </ul>
         */
        UPPER_CAMEL_CASE,

        /**
         * Convert camelCase to UPPER_SNAKE_CASE.
         * <ul>
         *   <li>{@code metricName} → {@code METRIC_NAME}</li>
         * </ul>
         */
        UPPER_SNAKE_CASE,

        /**
         * Lowercase everything ({@code hostPaths} → {@code hostpaths}).
         */
        LOWER_CASE,

        /**
         * Convert camelCase to kebab-case.
         * <ul>
         *   <li>{@code hostPaths} → {@code host-paths}</li>
         * </ul>
         */
        KEBAB_CASE,

        /**
         * Convert camelCase to lower.dot.case.
         * <ul>
         *   <li>{@code hostPaths} → {@code host.paths}</li>
         * </ul>
         */
        LOWER_DOT_CASE;

        /**
         * Transform a field name according to this strategy.
         *
         * @param name the Java field name
         * @return the transformed name
         */
        public String transform(String name) {
            return switch (this) {
                case IDENTITY -> name;
                case SNAKE_CASE -> toSeparated(name, '_');
                case LOWER_CAMEL_CASE -> toLowerCamel(name);
                case UPPER_CAMEL_CASE -> toUpperCamel(name);
                case UPPER_SNAKE_CASE -> toSeparated(name, '_').toUpperCase(java.util.Locale.ROOT);
                case LOWER_CASE -> name.toLowerCase(java.util.Locale.ROOT);
                case KEBAB_CASE -> toSeparated(name, '-');
                case LOWER_DOT_CASE -> toSeparated(name, '.');
            };
        }

        /**
         * Convert a camelCase string to separator-separated lowercase.
         * Handles consecutive uppercase letters (acronyms) correctly:
         * {@code httpURL} → {@code http_url}, not {@code http_u_r_l}.
         * Existing separators ({@code _-.}) normalize to the target and
         * collapse, so every strategy renders canonically.
         */
        static String toSeparated(String name, char separator) {
            if (name == null || name.isEmpty()) return name;
            var sb = new StringBuilder(name.length() + 4);
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (Character.isUpperCase(c)) {
                    if (sb.length() > 0 && sb.charAt(sb.length() - 1) != separator) {
                        // Insert separator before uppercase, but not between consecutive
                        // uppercase letters (acronyms) unless the next char is lowercase
                        char prev = name.charAt(i - 1);
                        if (!Character.isUpperCase(prev)) {
                            sb.append(separator);
                        } else if (i + 1 < name.length() && Character.isLowerCase(name.charAt(i + 1))) {
                            // End of acronym: "httpURL" → "http_url" (insert before the 'U' of 'Rl')
                            sb.append(separator);
                        }
                    }
                    sb.append(Character.toLowerCase(c));
                } else if (c == '_' || c == '-' || c == '.') {
                    if (sb.length() == 0 || sb.charAt(sb.length() - 1) != separator) {
                        sb.append(separator);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        /**
         * Convert a camelCase string to snake_case.
         * Handles consecutive uppercase letters (acronyms) correctly:
         * {@code httpURL} → {@code http_url}, not {@code http_u_r_l}.
         */
        static String toSnakeCase(String name) {
            return toSeparated(name, '_');
        }

        /**
         * Lowercase a leading uppercase run, preserving the camel hump:
         * {@code URL} → {@code url}, {@code URLValue} → {@code urlValue},
         * {@code Name} → {@code name}. Already-lower names pass through.
         */
        static String toLowerCamel(String name) {
            if (name == null || name.isEmpty() || Character.isLowerCase(name.charAt(0))) return name;
            int run = 0;
            while (run < name.length() && Character.isUpperCase(name.charAt(run))) run++;
            // Keep the last uppercase of a multi-letter run when it starts a
            // lowercase tail (the hump): URLValue → url + Value.
            int lower = (run > 1 && run < name.length()) ? run - 1 : run;
            var sb = new StringBuilder(name.length());
            for (int i = 0; i < lower; i++) sb.append(Character.toLowerCase(name.charAt(i)));
            sb.append(name, lower, name.length());
            return sb.toString();
        }

        /**
         * Split on separators and camel humps, capitalizing each part:
         * {@code host_paths} → {@code HostPaths}, {@code name} → {@code Name}.
         */
        static String toUpperCamel(String name) {
            if (name == null || name.isEmpty()) return name;
            var sb = new StringBuilder(name.length());
            boolean capitalize = true;
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (c == '_' || c == '-' || c == '.' || c == ' ') {
                    capitalize = true;
                } else if (capitalize) {
                    sb.append(Character.toUpperCase(c));
                    capitalize = false;
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
    }
}

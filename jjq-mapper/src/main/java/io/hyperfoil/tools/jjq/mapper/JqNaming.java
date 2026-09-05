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
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface JqNaming {

    /**
     * The naming strategy to apply.
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
        SNAKE_CASE;

        /**
         * Transform a field name according to this strategy.
         *
         * @param name the Java field name
         * @return the transformed name
         */
        public String transform(String name) {
            return switch (this) {
                case IDENTITY -> name;
                case SNAKE_CASE -> toSnakeCase(name);
            };
        }

        /**
         * Convert a camelCase string to snake_case.
         * Handles consecutive uppercase letters (acronyms) correctly:
         * {@code httpURL} → {@code http_url}, not {@code http_u_r_l}.
         */
        static String toSnakeCase(String name) {
            if (name == null || name.isEmpty()) return name;
            var sb = new StringBuilder(name.length() + 4);
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (Character.isUpperCase(c)) {
                    if (i > 0) {
                        // Insert underscore before uppercase, but not between consecutive
                        // uppercase letters (acronyms) unless the next char is lowercase
                        char prev = name.charAt(i - 1);
                        if (!Character.isUpperCase(prev) && prev != '_') {
                            sb.append('_');
                        } else if (i + 1 < name.length() && Character.isLowerCase(name.charAt(i + 1))) {
                            // End of acronym: "httpURL" → "http_url" (insert before the 'U' of 'Rl')
                            sb.append('_');
                        }
                    }
                    sb.append(Character.toLowerCase(c));
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
    }
}

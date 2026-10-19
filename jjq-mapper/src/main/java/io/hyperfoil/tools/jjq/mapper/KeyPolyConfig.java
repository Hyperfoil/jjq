package io.hyperfoil.tools.jjq.mapper;

import java.util.List;

/**
 * Key-presence polymorphism configuration for one family: ordered
 * key-to-subtype rules plus an optional default. Produced natively from
 * {@link JqKeys} (subtypes must be permitted by the sealed base).
 *
 * @param rules ordered dispatch rules, first present key wins
 * @param defaultSubtype fallback subtype when no rule matches, or null
 */
public record KeyPolyConfig(List<KeyRule> rules, Class<?> defaultSubtype) {
    /**
     * One dispatch rule: presence of {@code key} selects {@code subtype}.
     *
     * @param key the JSON key whose presence selects the subtype
     * @param subtype the subtype bound when the key is present
     */
    public record KeyRule(String key, Class<?> subtype) {}

    /**
     * Validate and defensively copy.
     *
     * @param rules ordered rules (must be non-empty, no blank keys or null types)
     * @param defaultSubtype fallback subtype, or null for fail-fast
     */
    public KeyPolyConfig {
        if (rules == null || rules.isEmpty()) {
            throw new JqMapperException("Key-presence polymorphism needs at least one rule");
        }
        var seen = new java.util.HashSet<String>();
        for (var rule : rules) {
            if (rule == null || rule.key() == null || rule.key().isEmpty() || rule.subtype() == null) {
                throw new JqMapperException("Key-presence polymorphism needs non-empty keys and subtype classes");
            }
            if (!seen.add(rule.key())) {
                throw new JqMapperException("Key-presence polymorphism has a duplicate key '" + rule.key() + "'");
            }
        }
        rules = List.copyOf(rules);
    }
}

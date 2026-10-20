package io.hyperfoil.tools.jjq.yaml;

import io.hyperfoil.tools.jjq.value.JqValue;

/**
 * A custom constructor for one explicitly tagged YAML node kind
 * (SnakeYAML {@code Construct} parity, issue #165).
 *
 * <p>The parser decodes the node normally first (scalar, mapping, or
 * sequence to {@link JqValue}), then calls the registered constructor
 * with the normalized tag and the decoded value. The returned value
 * replaces the node, including under anchors.</p>
 *
 * <p>Tags normalize as in {@code YamlParser}: {@code !foo} and
 * {@code foo} both arrive as {@code "foo"}, {@code !!str} as
 * {@code "str"}, and verbatim {@code !<uri>} tags as the URI's last
 * segment. Registering a core tag (e.g. {@code "str"}) overrides its
 * built-in coercion for explicitly tagged nodes; untagged scalars
 * never consult constructors. Mapping keys are exempt: keys always
 * decode to strings with the tag stripped.</p>
 *
 * <p>A {@link RuntimeException} thrown from {@code construct}
 * propagates to the caller as-is.</p>
 */
@FunctionalInterface
public interface YamlConstructor {

    /**
     * Build the node value from its decoded payload.
     *
     * @param tag     the normalized tag (never null)
     * @param decoded the normally-decoded node (never null)
     * @return the value to use for the node
     */
    JqValue construct(String tag, JqValue decoded);
}

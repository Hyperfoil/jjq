package io.hyperfoil.tools.jjq.yaml;

/**
 * Parse options for {@link JqYaml}.
 *
 * <p>Defaults are lenient (matching Jackson's defaults): duplicate mapping
 * keys are last-wins, silently. Use {@link #STRICT} for
 * {@code STRICT_DUPLICATE_DETECTION}-equivalent behavior, which rejects
 * duplicate keys with a {@link JqYamlException} naming the offending key.</p>
 *
 * @param allowDuplicateKeys when false, duplicate mapping keys fail parsing
 * @see JqYaml#parse(String, YamlOptions)
 */
public record YamlOptions(boolean allowDuplicateKeys) {

    /** Lenient defaults: duplicate keys are last-wins. */
    public static final YamlOptions LENIENT = new YamlOptions(true);

    /** Strict mode: duplicate mapping keys fail with {@link JqYamlException}. */
    public static final YamlOptions STRICT = new YamlOptions(false);
}

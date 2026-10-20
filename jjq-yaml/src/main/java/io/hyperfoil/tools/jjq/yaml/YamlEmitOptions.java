package io.hyperfoil.tools.jjq.yaml;

/**
 * Emitter options for {@link JqYaml#toYaml(JqValue, YamlEmitOptions)}.
 *
 * <p>Defaults ({@link #DEFAULT}) reproduce the historical output exactly:
 * 2-space indent, block style everywhere. Existing {@code toYaml} overloads
 * without options delegate with {@link #DEFAULT}, so their output is
 * byte-identical with or without this record in play (issue #164).</p>
 *
 * @param indentWidth   spaces per block level, 1-10 (SnakeYAML range)
 * @param sequenceStyle block ({@code - item}) or flow ({@code [a, b]})
 *                      rendering for sequences
 * @param mappingStyle  block ({@code key: value} lines) or flow
 *                      ({@code {k: v}}) rendering for mappings
 * @see JqYaml#toYaml(JqValue, YamlEmitOptions)
 */
public record YamlEmitOptions(int indentWidth, Style sequenceStyle, Style mappingStyle) {

    /** Block vs flow rendering for one collection kind. */
    public enum Style {
        BLOCK,
        FLOW
    }

    /** Historical output: 2-space indent, block style everywhere. */
    public static final YamlEmitOptions DEFAULT =
            new YamlEmitOptions(2, Style.BLOCK, Style.BLOCK);

    public YamlEmitOptions {
        if (indentWidth < 1 || indentWidth > 10) {
            throw new IllegalArgumentException(
                    "indentWidth must be 1-10, was " + indentWidth);
        }
        if (sequenceStyle == null || mappingStyle == null) {
            throw new NullPointerException("collection styles must not be null");
        }
    }
}

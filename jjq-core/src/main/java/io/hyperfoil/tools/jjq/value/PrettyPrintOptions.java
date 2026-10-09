package io.hyperfoil.tools.jjq.value;

/**
 * Controls {@link JqValues#toPrettyJsonString(JqValue, PrettyPrintOptions)} output style.
 *
 * <p>Three independent knobs: the key separator, the array layout, and the
 * string escaping policy. All defaults reproduce the historical output, so
 * {@link #DEFAULT} is byte-identical to
 * {@link JqValues#toPrettyJsonString(JqValue)}.</p>
 *
 * <pre>{@code
 * // Jackson INDENT_OUTPUT spacing for stable text contracts:
 * JqValues.toPrettyJsonString(value, PrettyPrintOptions.JACKSON);
 * // Jackson spacing plus terminal-safe escaping:
 * JqValues.toPrettyJsonString(value, new PrettyPrintOptions(
 *         PrettyPrintOptions.Separator.SPACED,
 *         PrettyPrintOptions.ArrayStyle.FLOW,
 *         PrettyPrintOptions.Escaping.STRICT));
 * }</pre>
 *
 * @see JqValues#toPrettyJsonString(JqValue, PrettyPrintOptions)
 */
public record PrettyPrintOptions(Separator separator, ArrayStyle arrayStyle, Escaping escaping) {

    /**
     * The separator between an object key and its value.
     */
    public enum Separator {
        /** {@code "key": value} (historical output). */
        COMPACT(": "),
        /** {@code "key" : value} (Jackson {@code INDENT_OUTPUT}). */
        SPACED(" : ");

        private final String text;

        Separator(String text) {
            this.text = text;
        }

        /**
         * @return the literal separator text
         */
        public String text() {
            return text;
        }
    }

    /**
     * Array layout.
     */
    public enum ArrayStyle {
        /** One element per line, indented (historical output). */
        BLOCK,
        /** Inline elements separated by spaces: {@code [ "x", "y" ]} (Jackson default). */
        FLOW
    }

    /**
     * String escaping policy for keys and pretty-printed values.
     * The compact serialization path always uses {@link #STANDARD}.
     */
    public enum Escaping {
        /** Escape {@code "}, {@code \}, and C0 controls (historical output). */
        STANDARD,
        /**
         * Additionally escape as uppercase {@code \\uXXXX} everything
         * {@link Character#isISOControl} (DEL, C1) plus U+2028–U+202E and
         * U+2066–U+2069, so terminal-driving values cannot inject
         * escape/bidi sequences. Short escapes ({@code \n} etc.) are kept.
         */
        STRICT
    }

    /**
     * Historical output: compact separator, block arrays, standard escaping.
     */
    public static final PrettyPrintOptions DEFAULT =
            new PrettyPrintOptions(Separator.COMPACT, ArrayStyle.BLOCK, Escaping.STANDARD);

    /**
     * Jackson {@code INDENT_OUTPUT} spacing: spaced separator, flow arrays
     * (empty containers render as {@code { }} and {@code [ ]}), standard
     * escaping. Pair with {@link Escaping#STRICT} for terminal-safe output.
     */
    public static final PrettyPrintOptions JACKSON =
            new PrettyPrintOptions(Separator.SPACED, ArrayStyle.FLOW, Escaping.STANDARD);

    /**
     * Empty-object rendering: {@code "{ }"} for flow style, {@code "{}"} otherwise.
     *
     * @return the empty-object literal
     */
    public String emptyObject() {
        return arrayStyle == ArrayStyle.FLOW ? "{ }" : "{}";
    }

    /**
     * Empty-array rendering: {@code "[ ]"} for flow style, {@code "[]"} otherwise.
     *
     * @return the empty-array literal
     */
    public String emptyArray() {
        return arrayStyle == ArrayStyle.FLOW ? "[ ]" : "[]";
    }
}

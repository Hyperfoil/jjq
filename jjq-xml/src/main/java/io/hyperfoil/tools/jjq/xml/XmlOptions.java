package io.hyperfoil.tools.jjq.xml;

/**
 * Parse options for {@link JqXml}.
 *
 * @param coerceNumbers when true, text and attribute values shaped like JSON
 *                      numbers become {@code JqNumber} (integral shapes become
 *                      long, or BigDecimal past long range; fractional and
 *                      exponent shapes become double). Anything else —
 *                      notably {@code 001}, hex, or version-like
 *                      {@code 1.2.3} — stays a string. Defaults to strings.
 * @see JqXml#parse(String, XmlOptions)
 */
public record XmlOptions(boolean coerceNumbers) {

    /** Default: every text and attribute value is a string. */
    public static final XmlOptions DEFAULT = new XmlOptions(false);

    /** Opt-in: JSON-number-shaped strings become numbers. */
    public static final XmlOptions COERCE_NUMBERS = new XmlOptions(true);
}

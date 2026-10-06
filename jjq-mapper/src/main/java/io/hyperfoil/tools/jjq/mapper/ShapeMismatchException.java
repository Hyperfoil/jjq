package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.value.JqValue;

/**
 * A {@link JqMapperException} for structural shape mismatches: the document
 * shape does not fit the Java target type (scalar for a collection, array
 * for a scalar, wrong element type, ...).
 *
 * <p>Carries the expected and actual shapes structurally as
 * {@link JqValue.Type}, alongside the inherited failure {@link #path()},
 * so callers can render their own diagnostics without parsing the message:</p>
 * <pre>{@code
 * try {
 *     mapper.fromJqValue(doc, Config.class);
 * } catch (ShapeMismatchException e) {
 *     // e.expected() == ARRAY, e.actual() == STRING, e.path() == "$.packages"
 * }
 * }</pre>
 *
 * <p>The human-readable message keeps the {@code Cannot bind ...} wording;
 * only the shapes are additionally available in structured form.</p>
 */
public class ShapeMismatchException extends JqMapperException {

    private final JqValue.Type expected;
    private final JqValue.Type actual;

    /**
     * Creates a shape-mismatch error.
     *
     * @param expected    the expected JSON shape
     * @param actual      the actual JSON shape ({@code null} maps to {@code NULL})
     * @param description human-readable detail, e.g. {@code Cannot bind String "git" to List<String>}
     */
    public ShapeMismatchException(JqValue.Type expected, JqValue.Type actual, String description) {
        super(description);
        this.expected = expected;
        this.actual = actual == null ? JqValue.Type.NULL : actual;
    }

    /** The expected JSON shape, e.g. {@code ARRAY} for a {@code List} target. */
    public JqValue.Type expected() { return expected; }

    /** The actual JSON shape of the offending value. */
    public JqValue.Type actual() { return actual; }
}

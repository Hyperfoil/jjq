package io.hyperfoil.tools.jjq.schema;

/**
 * A schema compilation failure: malformed schemas, unresolved references,
 * unsupported dialects, or recognized-but-unsupported assertion features.
 * Runtime data mismatches never throw this; they return {@code false} or
 * error values from {@link JqSchema#isValid} / {@link JqSchema#validate}.
 */
public class JqSchemaException extends RuntimeException {

    private final String schemaLocation;

    /**
     * @param message        what is wrong with the schema
     * @param schemaLocation JSON Pointer to the offending schema location
     *                       ({@code ""} for the document root)
     */
    public JqSchemaException(String message, String schemaLocation) {
        super(message);
        this.schemaLocation = schemaLocation;
    }

    /** JSON Pointer to the offending schema location, {@code ""} for the root. */
    public String schemaLocation() {
        return schemaLocation;
    }

    @Override
    public String getMessage() {
        String base = super.getMessage();
        return schemaLocation.isEmpty() ? base : base + " at '" + schemaLocation + "'";
    }
}

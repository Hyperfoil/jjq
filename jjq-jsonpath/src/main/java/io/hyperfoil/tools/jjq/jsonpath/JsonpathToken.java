package io.hyperfoil.tools.jjq.jsonpath;

/**
 * A single token from the PostgreSQL jsonpath lexer.
 *
 * @param type     the token type
 * @param value    the string value (for identifiers, strings, numbers) or null for structural tokens
 * @param position the character offset in the input string
 */
public record JsonpathToken(JsonpathTokenType type, String value, int position) {

    /** Create a structural token without a value. */
    public JsonpathToken(JsonpathTokenType type, int position) {
        this(type, null, position);
    }

    /** Check if this token is a specific type. */
    public boolean is(JsonpathTokenType expected) {
        return type == expected;
    }
}

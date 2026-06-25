package io.hyperfoil.tools.jjq.jsonata;

/**
 * Thrown when a JSONata expression cannot be translated to jq.
 * This includes unsupported JSONata features and syntax errors.
 */
public class JsonataException extends RuntimeException {
    /** Creates a new JsonataException with the given message. */
    public JsonataException(String message) {
        super(message);
    }

    /** Creates a new JsonataException with the given message and cause. */
    public JsonataException(String message, Throwable cause) {
        super(message, cause);
    }
}

package io.hyperfoil.tools.jjq.jsonata;

/**
 * Thrown when a JSONata expression cannot be translated to jq.
 * This includes unsupported JSONata features and syntax errors.
 */
public class JsonataException extends RuntimeException {
    public JsonataException(String message) {
        super(message);
    }

    public JsonataException(String message, Throwable cause) {
        super(message, cause);
    }
}

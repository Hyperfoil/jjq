package io.hyperfoil.tools.jjq.evaluator;

import io.hyperfoil.tools.jjq.ast.SourceLocation;
import io.hyperfoil.tools.jjq.value.JqValue;

public class JqException extends RuntimeException {
    private final SourceLocation location;
    private final JqValue value;

    public JqException(String message) {
        super(message);
        this.location = null;
        this.value = null;
    }

    public JqException(String message, Throwable cause) {
        super(message, cause);
        this.location = null;
        this.value = null;
    }

    public JqException(String message, SourceLocation location) {
        super(formatMessage(message, location));
        this.location = location;
        this.value = null;
    }

    public JqException(String message, SourceLocation location, Throwable cause) {
        super(formatMessage(message, location), cause);
        this.location = location;
        this.value = null;
    }

    public JqException(JqValue value) {
        super(value instanceof io.hyperfoil.tools.jjq.value.JqString s ? s.stringValue() : value.toJsonString());
        this.location = null;
        this.value = value;
    }

    public SourceLocation getLocation() {
        return location;
    }

    /**
     * Returns the original JqValue passed to error(), or null if constructed with a string message.
     */
    public JqValue jqValue() {
        return value;
    }

    private static String formatMessage(String message, SourceLocation location) {
        if (location != null && location != SourceLocation.UNKNOWN) {
            return message + " (at " + location + ")";
        }
        return message;
    }
}

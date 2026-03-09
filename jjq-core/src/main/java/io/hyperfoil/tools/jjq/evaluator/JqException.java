package io.hyperfoil.tools.jjq.evaluator;

import io.hyperfoil.tools.jjq.ast.SourceLocation;

public class JqException extends RuntimeException {
    private final SourceLocation location;

    public JqException(String message) {
        super(message);
        this.location = null;
    }

    public JqException(String message, Throwable cause) {
        super(message, cause);
        this.location = null;
    }

    public JqException(String message, SourceLocation location) {
        super(formatMessage(message, location));
        this.location = location;
    }

    public JqException(String message, SourceLocation location, Throwable cause) {
        super(formatMessage(message, location), cause);
        this.location = location;
    }

    public SourceLocation getLocation() {
        return location;
    }

    private static String formatMessage(String message, SourceLocation location) {
        if (location != null && location != SourceLocation.UNKNOWN) {
            return message + " (at " + location + ")";
        }
        return message;
    }
}

package io.hyperfoil.tools.jjq.mapper;

/**
 * Thrown when the mapper fails to map between JqValue and Java types.
 * Covers introspection failures, type conversion errors, and constructor
 * invocation failures.
 */
public class JqMapperException extends RuntimeException {

    /**
     * Creates a new mapper exception with the given message.
     *
     * @param message the error message
     */
    public JqMapperException(String message) {
        super(message);
    }

    /**
     * Creates a new mapper exception with the given message and cause.
     *
     * @param message the error message
     * @param cause   the underlying cause
     */
    public JqMapperException(String message, Throwable cause) {
        super(message, cause);
    }
}

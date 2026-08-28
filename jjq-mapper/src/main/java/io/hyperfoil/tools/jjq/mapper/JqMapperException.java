package io.hyperfoil.tools.jjq.mapper;

/**
 * Thrown when the mapper fails to map between JqValue and Java types.
 * Covers introspection failures, type conversion errors, and constructor
 * invocation failures.
 */
public class JqMapperException extends RuntimeException {

    public JqMapperException(String message) {
        super(message);
    }

    public JqMapperException(String message, Throwable cause) {
        super(message, cause);
    }
}

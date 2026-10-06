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

    // Path segments prepended while unwinding through nested structures:
    // String for object fields, Integer for collection indexes. Empty at the
    // throw site unless the mismatch is already contextualized. Prepending is
    // allocation-free on success paths (only executed while throwing).

    /** Path segments, outermost first. Elements are String (field) or Integer (index). */
    private final java.util.List<Object> pathSegments = new java.util.ArrayList<>();

    /**
     * Prepend an object field to the failure path. Returns {@code this} for
     * {@code throw e.prependPath(field)} rethrow idiom (no reallocation).
     *
     * @param field the field name at this nesting level
     * @return this exception
     */
    public JqMapperException prependPath(String field) {
        pathSegments.add(0, field);
        return this;
    }

    /**
     * Prepend a collection index to the failure path. Returns {@code this} for
     * {@code throw e.prependPath(index)} rethrow idiom (no reallocation).
     *
     * @param index the collection index at this nesting level
     * @return this exception
     */
    public JqMapperException prependPath(int index) {
        pathSegments.add(0, index);
        return this;
    }

    /**
     * Render the failure path in {@code $.field[0].nested} form.
     *
     * @return the path, or {@code "$"} if no segments were recorded
     */
    public String path() {
        if (pathSegments.isEmpty()) return "$";
        StringBuilder sb = new StringBuilder("$");
        for (Object segment : pathSegments) {
            if (segment instanceof Integer index) {
                sb.append('[').append(index).append(']');
            } else {
                String field = String.valueOf(segment);
                if (field.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    sb.append('.').append(field);
                } else {
                    sb.append("[\"").append(field.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"]");
                }
            }
        }
        return sb.toString();
    }

    @Override
    public String getMessage() {
        String base = super.getMessage();
        if (pathSegments.isEmpty()) return base;
        return base == null ? "at '" + path() + "'" : base + " at '" + path() + "'";
    }
}

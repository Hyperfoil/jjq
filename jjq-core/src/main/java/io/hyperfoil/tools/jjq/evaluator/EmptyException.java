package io.hyperfoil.tools.jjq.evaluator;

/**
 * Thrown when 'empty' is called - signals no output for this branch.
 * Used for generator backtracking semantics.
 */
public class EmptyException extends RuntimeException {
    public static final EmptyException INSTANCE = new EmptyException();

    private EmptyException() {
        super("empty");
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this; // performance: no stack trace needed
    }
}

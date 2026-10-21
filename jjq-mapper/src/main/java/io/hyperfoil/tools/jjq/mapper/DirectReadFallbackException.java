package io.hyperfoil.tools.jjq.mapper;

/**
 * Control-flow signal from a {@link DirectReader} to run the existing
 * strict parse-plus-map path over the complete original input instead of
 * completing the direct read (issue #170).
 *
 * <p>Thrown for every input outside the canonical fast path: unknown or
 * escaped field names, nested values, accepted-but-uncached coercions,
 * duplicates, malformed separators and trailing content. The fallback path
 * then reproduces today's exact behavior — accepted coercions, binding
 * errors and syntax errors alike — so catching this exception and
 * retrying generically can never weaken validation.</p>
 *
 * <p>Carries no stack trace: it is a routine outcome, not a failure.</p>
 */
public final class DirectReadFallbackException extends RuntimeException {

    /** Creates a fallback signal. */
    public DirectReadFallbackException() {
        super(null, null, false, false);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}

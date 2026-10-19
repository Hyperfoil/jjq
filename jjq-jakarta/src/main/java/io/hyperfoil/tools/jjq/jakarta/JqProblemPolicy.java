package io.hyperfoil.tools.jjq.jakarta;

/**
 * Opt-in always-problem policy for {@link JqBadRequestExceptionMapper}.
 *
 * <p>When a policy bean resolving to {@code true} is available (e.g.
 * produced by the {@code jjq-quarkus} extension for applications whose
 * other errors already render problem details), bind failures render
 * {@code application/problem+json} regardless of the request
 * {@code Accept} header. Without a policy the mapper negotiates: an
 * explicit {@code application/problem+json} accept renders the problem
 * shape, everything else keeps the historical {@code text/plain} body.</p>
 *
 * @param alwaysProblemDetails whether to always render problem details
 */
public record JqProblemPolicy(boolean alwaysProblemDetails) {
}

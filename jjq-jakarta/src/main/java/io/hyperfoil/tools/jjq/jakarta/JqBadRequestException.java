package io.hyperfoil.tools.jjq.jakarta;

import jakarta.ws.rs.core.Response;

/**
 * Rejection of an HTTP request body: malformed JSON, invalid UTF-8, policy
 * violations (duplicate keys, trailing content), or data that cannot bind
 * to the target type.
 *
 * <p>Kept free of {@code WebApplicationException}/{@code Response} so it can
 * be constructed and unit-tested without a JAX-RS runtime delegate — only
 * {@link JqBadRequestExceptionMapper} (invoked by the container) builds the
 * actual {@code 400} response. Thrown exclusively for request-driven
 * failures; mapper misconfiguration and application bugs propagate as other
 * types and keep their {@code 500} mapping.</p>
 */
public final class JqBadRequestException extends RuntimeException {

    /** Maximum characters of cause diagnostics echoed in the message. */
    private static final int MAX_ERROR_DETAIL = 512;

    /**
     * Creates a rejection with diagnostics.
     *
     * @param message sanitized detail (positions, never stack traces or full bodies)
     */
    public JqBadRequestException(String message) {
        super(message);
    }

    /**
     * Creates a rejection with diagnostics and a cause (cause is logged
     * server-side only, never rendered).
     *
     * @param message sanitized detail
     * @param cause   the underlying parse/bind failure
     */
    public JqBadRequestException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Build a rejection from a parse/bind failure, truncating the cause
     * diagnostics: positions stay useful, echoed input never grows with
     * the request body.
     *
     * @param prefix human context naming the failed stage
     * @param cause  the underlying parse/bind failure
     * @return the rejection, carrying the cause server-side only
     */
    public static JqBadRequestException of(String prefix, RuntimeException cause) {
        String detail = cause.getMessage();
        if (detail != null && detail.length() > MAX_ERROR_DETAIL) {
            detail = detail.substring(0, MAX_ERROR_DETAIL) + "...[truncated]";
        }
        return new JqBadRequestException(detail == null ? prefix : prefix + ": " + detail, cause);
    }

    /**
     * @return the HTTP status this rejection maps to (400)
     */
    public int status() {
        return Response.Status.BAD_REQUEST.getStatusCode();
    }
}

package io.hyperfoil.tools.jjq.jakarta;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Renders {@link JqBadRequestException} as {@code 400 Bad Request} with the
 * sanitized diagnostics as plain text. Scoped to this single exception type:
 * anything else keeps the container default mapping.
 *
 * <p>Registered automatically by the {@code jjq-quarkus} extension alongside
 * the readers; other runtimes pick it up through standard {@code @Provider}
 * scanning.</p>
 */
@Provider
public class JqBadRequestExceptionMapper implements ExceptionMapper<JqBadRequestException> {

    @Override
    public Response toResponse(JqBadRequestException exception) {
        return Response.status(exception.status())
                .entity(exception.getMessage() == null ? "Invalid JSON request body" : exception.getMessage())
                .type(MediaType.TEXT_PLAIN_TYPE)
                .build();
    }
}

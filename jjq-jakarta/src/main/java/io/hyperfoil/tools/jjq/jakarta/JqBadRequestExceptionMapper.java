package io.hyperfoil.tools.jjq.jakarta;

import io.hyperfoil.tools.jjq.value.JqObject;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Renders {@link JqBadRequestException} as {@code 400 Bad Request}.
 * Scoped to this single exception type: anything else keeps the container
 * default mapping.
 *
 * <p>Two shapes, negotiated per request (issue #153): an explicit
 * {@code application/problem+json} {@code Accept} (or an always-problem
 * {@link JqProblemPolicy}) renders an RFC 9457 problem detail built from
 * the sanitized diagnostics ({@code title} from the status,
 * {@code detail} carrying the message, {@code instance} the request path
 * when known); everything else keeps the historical {@code text/plain}
 * body byte-identical. In particular a bare {@code application/json} or
 * wildcard accept stays plain text.</p>
 *
 * <p>Container caveat: JAX-RS matches the request {@code Accept} against
 * the endpoint's {@code @Produces} before error mappers run, so a lone
 * {@code application/problem+json} accept against a JSON-only endpoint
 * yields the container's {@code 406}, not this mapper. Advertise the
 * endpoint's success media type as well. The policy does not change
 * endpoint matching.</p>
 *
 * <p>Registered automatically by the {@code jjq-quarkus} extension alongside
 * the readers; other runtimes pick it up through standard {@code @Provider}
 * scanning. The {@code @Context}/{@code @Inject} fields stay null outside
 * a container and degrade to the plain-text shape.</p>
 */
@Provider
public class JqBadRequestExceptionMapper implements ExceptionMapper<JqBadRequestException> {

    /** Wire type for RFC 9457 problem details (no JAX-RS constant exists). */
    static final String PROBLEM_JSON = "application/problem+json";

    @Context
    private HttpHeaders headers;

    @Context
    private UriInfo uriInfo;

    @Inject
    private Instance<JqProblemPolicy> policies;

    @Override
    public Response toResponse(JqBadRequestException exception) {
        String detail = exception.getMessage() == null ? "Invalid JSON request body" : exception.getMessage();
        if (rendersProblem()) {
            return Response.status(exception.status())
                    .entity(problemEntity(exception.status(), detail, requestPath()))
                    .type(PROBLEM_JSON)
                    .build();
        }
        return Response.status(exception.status())
                .entity(detail)
                .type(MediaType.TEXT_PLAIN_TYPE)
                .build();
    }

    /** Whether this request renders the problem shape: policy first, then negotiation. */
    boolean rendersProblem() {
        return alwaysProblem() || clientWantsProblem();
    }

    /** An always-problem policy bean wins over negotiation when present. */
    private boolean alwaysProblem() {
        if (policies == null || policies.isUnsatisfied()) return false;
        for (JqProblemPolicy policy : policies) {
            if (policy.alwaysProblemDetails()) return true;
        }
        return false;
    }

    /**
     * Explicit opt-in only: the accept list must name
     * {@code application/problem+json} with a non-zero quality.
     * Wildcards, bare {@code application/json}, and {@code q=0} entries
     * alone keep the plain-text shape. An explicit higher text/plain
     * preference wins; equal qualities prefer the requested problem shape.
     */
    private boolean clientWantsProblem() {
        if (headers == null) return false;
        double problemQuality = 0;
        double textQuality = 0;
        for (MediaType accepted : headers.getAcceptableMediaTypes()) {
            if ("application".equalsIgnoreCase(accepted.getType())
                    && "problem+json".equalsIgnoreCase(accepted.getSubtype())) {
                problemQuality = Math.max(problemQuality, quality(accepted));
            } else if ("text".equalsIgnoreCase(accepted.getType())
                    && "plain".equalsIgnoreCase(accepted.getSubtype())) {
                textQuality = Math.max(textQuality, quality(accepted));
            }
        }
        return problemQuality > 0 && problemQuality >= textQuality;
    }

    private static double quality(MediaType accepted) {
        try {
            double quality = Double.parseDouble(accepted.getParameters().getOrDefault("q", "1"));
            return quality >= 0 && quality <= 1 ? quality : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** RFC 9457 shape; the sanitized detail needs no further truncation. */
    static String problemEntity(int status, String detail, String path) {
        Response.Status statusType = Response.Status.fromStatusCode(status);
        var builder = JqObject.builder(5)
                .put("type", "about:blank")
                .put("title", statusType != null ? statusType.getReasonPhrase() : "Bad Request")
                .put("status", (long) status)
                .put("detail", detail);
        if (path != null) builder.put("instance", path);
        return builder.build().toJsonString();
    }

    /** The request path for {@code instance}, or null outside a container. */
    String requestPath() {
        // Preserve URI escaping and the leading slash, never echo query data.
        return uriInfo == null ? null : uriInfo.getRequestUri().getRawPath();
    }
}

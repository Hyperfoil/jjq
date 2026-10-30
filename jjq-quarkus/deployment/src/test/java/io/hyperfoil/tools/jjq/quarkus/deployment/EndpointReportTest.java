package io.hyperfoil.tools.jjq.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.jboss.jandex.Index;
import org.junit.jupiter.api.Test;

import io.hyperfoil.tools.jjq.mapper.JqMapped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

/**
 * Endpoint report classification (issue #198): each JSON direction of a
 * resource method is marked served, declined, unknown, or skipped with a
 * reason, mirroring the runtime provider rules.
 */
class EndpointReportTest {

    @JqMapped
    record ReportHero(String name, int level) {
    }

    record PlainPojo(String name) {
    }

    @Path("/report")
    static class ReportResource {
        @POST
        @Path("/hero")
        @Consumes("application/json")
        @Produces("application/json")
        public ReportHero create(ReportHero in) {
            return in;
        }

        @POST
        @Path("/raw")
        @Consumes("application/json")
        @Produces("application/json")
        public Map<String, Integer> raw(Map<String, Integer> in) {
            return in;
        }

        @GET
        @Path("/name")
        @Produces("text/plain")
        public String name() {
            return "x";
        }

        @GET
        @Path("/heroes")
        @Produces("application/json")
        public CompletableFuture<List<ReportHero>> list() {
            return null;
        }

        @GET
        @Path("/maybe")
        @Produces("application/json")
        public Response maybe() {
            return null;
        }

        @POST
        @Path("/plain")
        @Consumes("application/json")
        @Produces("application/json")
        public PlainPojo plain(PlainPojo in) {
            return in;
        }
    }

    private static EndpointReport.Entry find(List<EndpointReport.Entry> entries,
            String method, String path, EndpointReport.Direction direction) {
        return entries.stream()
                .filter(e -> e.httpMethod().equals(method) && e.path().equals(path)
                        && e.direction() == direction)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing entry " + method + " " + path + " " + direction + " in " + entries));
    }

    @Test
    void mappedPojoServedBothDirections() throws Exception {
        var entries = EndpointReport.endpointReportEntries(Index.of(ReportResource.class, ReportHero.class, PlainPojo.class));
        var in = find(entries, "POST", "/report/hero", EndpointReport.Direction.IN);
        assertEquals(EndpointReport.Verdict.SERVED, in.verdict());
        assertEquals("directly-mappable", in.reason());
        var out = find(entries, "POST", "/report/hero", EndpointReport.Direction.OUT);
        assertEquals(EndpointReport.Verdict.SERVED, out.verdict());
        assertEquals("directly-mappable", out.reason());
    }

    @Test
    void rawScalarMapDeclinedBothDirections() throws Exception {
        // The h5m shape: Map<String, Integer> is not a homogeneous
        // mappable collection, so the container default serves it.
        var entries = EndpointReport.endpointReportEntries(Index.of(ReportResource.class, ReportHero.class, PlainPojo.class));
        var in = find(entries, "POST", "/report/raw", EndpointReport.Direction.IN);
        assertEquals(EndpointReport.Verdict.DECLINED, in.verdict());
        assertEquals("raw-collection-element", in.reason());
        var out = find(entries, "POST", "/report/raw", EndpointReport.Direction.OUT);
        assertEquals(EndpointReport.Verdict.DECLINED, out.verdict());
    }

    @Test
    void plainStringSkipped() throws Exception {
        var entries = EndpointReport.endpointReportEntries(Index.of(ReportResource.class, ReportHero.class, PlainPojo.class));
        var out = find(entries, "GET", "/report/name", EndpointReport.Direction.OUT);
        assertEquals(EndpointReport.Verdict.SKIPPED, out.verdict());
        assertTrue(entries.stream().noneMatch(e -> e.httpMethod().equals("GET")
                && e.path().equals("/report/name") && e.direction() == EndpointReport.Direction.IN));
    }

    @Test
    void asyncCollectionServedAfterUnwrap() throws Exception {
        var entries = EndpointReport.endpointReportEntries(Index.of(ReportResource.class, ReportHero.class, PlainPojo.class));
        var out = find(entries, "GET", "/report/heroes", EndpointReport.Direction.OUT);
        assertEquals(EndpointReport.Verdict.SERVED, out.verdict());
        assertEquals("collection-through-mapper", out.reason());
    }

    @Test
    void responseWrappedIsUnknown() throws Exception {
        var entries = EndpointReport.endpointReportEntries(Index.of(ReportResource.class, ReportHero.class, PlainPojo.class));
        var out = find(entries, "GET", "/report/maybe", EndpointReport.Direction.OUT);
        assertEquals(EndpointReport.Verdict.UNKNOWN, out.verdict());
        assertEquals("response-wrapped", out.reason());
    }

    @Test
    void unmappedPojoDeclinedBothDirections() throws Exception {
        var entries = EndpointReport.endpointReportEntries(Index.of(ReportResource.class, ReportHero.class, PlainPojo.class));
        var in = find(entries, "POST", "/report/plain", EndpointReport.Direction.IN);
        assertEquals(EndpointReport.Verdict.DECLINED, in.verdict());
        assertEquals("unmapped-pojo", in.reason());
        var out = find(entries, "POST", "/report/plain", EndpointReport.Direction.OUT);
        assertEquals(EndpointReport.Verdict.DECLINED, out.verdict());
    }
}

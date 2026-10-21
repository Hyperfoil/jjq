package io.hyperfoil.tools.jjq.quarkus.test;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** REST fixture with eligible and ineligible request body types. */
@Path("/direct")
public class DirectReaderResource {

    @POST
    @Path("/eligible")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public DirectEligible echo(DirectEligible in) {
        return in;
    }

    @POST
    @Path("/nested")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public DirectNested echoNested(DirectNested in) {
        return in;
    }
}

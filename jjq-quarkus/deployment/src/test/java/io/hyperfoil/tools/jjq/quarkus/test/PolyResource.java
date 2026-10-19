package io.hyperfoil.tools.jjq.quarkus.test;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Family-typed endpoints: the providers must accept bases, not just records. */
@Path("/poly")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PolyResource {

    @POST
    @Path("/channel")
    public PolyChannel channel(PolyChannel channel) {
        return channel;
    }

    @POST
    @Path("/node")
    public KeyNode node(KeyNode node) {
        return node;
    }
}

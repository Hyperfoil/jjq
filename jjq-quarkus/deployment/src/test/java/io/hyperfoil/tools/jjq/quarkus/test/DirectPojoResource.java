package io.hyperfoil.tools.jjq.quarkus.test;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** REST fixture with a flat-POJO request body type (issue #4). */
@Path("/direct-pojo")
public class DirectPojoResource {

    @POST
    @Path("/echo")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public DirectPojo echo(DirectPojo in) {
        return in;
    }
}

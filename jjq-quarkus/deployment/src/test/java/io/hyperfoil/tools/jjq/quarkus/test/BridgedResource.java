package io.hyperfoil.tools.jjq.quarkus.test;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** REST fixture exercising bridge-backed bindings through the CDI mapper. */
@Path("/bridged")
public class BridgedResource {

    @POST
    @Path("/jackson")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public JacksonRenamed jackson(JacksonRenamed value) {
        return value;
    }

    @POST
    @Path("/auth")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public AuthHolder auth(AuthHolder value) {
        return value;
    }

    @POST
    @Path("/jsonb")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public JsonbRenamed jsonb(JsonbRenamed value) {
        return value;
    }
}

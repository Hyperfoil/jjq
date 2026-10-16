package io.hyperfoil.tools.jjq.quarkus.test;

import io.hyperfoil.tools.jjq.mapper.JqMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** REST fixture exercising the extension-registered providers and mapper bean. */
@Path("/greeting")
public class GreetingResource {

    @Inject
    JqMapper mapper;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Greeting echo(Greeting greeting) {
        return greeting;
    }

    @GET
    @Path("/mapper-id")
    @Produces(MediaType.TEXT_PLAIN)
    public int mapperId() {
        return System.identityHashCode(mapper);
    }
}

package io.hyperfoil.tools.jjq.quarkus.test;

import io.hyperfoil.tools.jjq.value.JqValue;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** REST fixture exercising the JqValue providers and rejection mapper. */
@Path("/jvalue")
public class JqValueResource {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public JqValue echo(JqValue value) {
        return value;
    }
}

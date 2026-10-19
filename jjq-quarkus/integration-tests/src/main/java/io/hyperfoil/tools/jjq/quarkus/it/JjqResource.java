package io.hyperfoil.tools.jjq.quarkus.it;

import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.quarkus.it.model.Channel;
import io.hyperfoil.tools.jjq.quarkus.it.model.ChannelSecret;
import io.hyperfoil.tools.jjq.quarkus.it.model.Greeting;
import io.hyperfoil.tools.jjq.quarkus.it.model.JacksonGreeting;
import io.hyperfoil.tools.jjq.quarkus.it.model.NodeConfig;
import io.hyperfoil.tools.jjq.quarkus.it.model.Repository;
import io.hyperfoil.tools.jjq.value.JqValue;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Application endpoints consumed by the JVM and packaged HTTP tests. */
@Path("/jjq")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class JjqResource {

    @Inject
    JqMapper mapper;

    @POST
    @Path("/record")
    public Greeting record(Greeting greeting) {
        return greeting;
    }

    @POST
    @Path("/jackson")
    public JacksonGreeting jackson(JacksonGreeting greeting) {
        return greeting;
    }

    @POST
    @Path("/jsonb")
    public Repository jsonb(Repository repository) {
        return repository;
    }

    @POST
    @Path("/value")
    public JqValue value(JqValue value) {
        return value;
    }

    @POST
    @Path("/channel")
    public Channel channel(Channel channel) {
        return channel;
    }

    @POST
    @Path("/secret")
    public ChannelSecret secret(ChannelSecret secret) {
        return secret;
    }

    @POST
    @Path("/node")
    public NodeConfig node(NodeConfig config) {
        return config;
    }

    @POST
    @Path("/mapper")
    public Greeting mapper(JqValue value) {
        return mapper.fromJqValue(value, Greeting.class);
    }

    @GET
    @Path("/mapper/strict")
    @Produces(MediaType.TEXT_PLAIN)
    public String strict() {
        return Boolean.toString(mapper.isFailOnUnknownProperties());
    }

    @GET
    @Path("/failure")
    @Produces(MediaType.TEXT_PLAIN)
    public String failure() {
        throw new IllegalArgumentException("Deliberate application failure for the HTTP error contract");
    }
}

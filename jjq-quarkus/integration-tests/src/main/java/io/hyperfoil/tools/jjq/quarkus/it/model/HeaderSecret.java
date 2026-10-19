package io.hyperfoil.tools.jjq.quarkus.it.model;

import io.hyperfoil.tools.jjq.mapper.JqMapped;

@JqMapped
public record HeaderSecret(ChannelMethod method, String authHeader) implements ChannelSecret {
}

package io.hyperfoil.tools.jjq.quarkus.it.model;

import io.hyperfoil.tools.jjq.mapper.JqMapped;

/** Email variant forces its method, mirroring h5m's compact constructor. */
@JqMapped
public record EmailChannel(ChannelMethod method, String to) implements Channel {
    public EmailChannel {
        method = ChannelMethod.EMAIL;
    }
}

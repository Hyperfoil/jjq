package io.hyperfoil.tools.jjq.quarkus.it.model;

import io.hyperfoil.tools.jjq.mapper.JqMapped;

@JqMapped
public record WebhookChannel(ChannelMethod method, String url) implements Channel {
}

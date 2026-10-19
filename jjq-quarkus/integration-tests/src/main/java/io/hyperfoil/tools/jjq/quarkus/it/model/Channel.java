package io.hyperfoil.tools.jjq.quarkus.it.model;

import io.hyperfoil.tools.jjq.mapper.JqSubtype;
import io.hyperfoil.tools.jjq.mapper.JqType;

/**
 * Discriminator-as-field family: {@code method} is a real record component
 * of every variant, not injected metadata.
 */
@JqType(discriminator = "method", existingProperty = true, mappings = {
        @JqSubtype(value = "EMAIL", type = EmailChannel.class),
        @JqSubtype(value = "SLACK", type = SlackChannel.class),
        @JqSubtype(value = "WEBHOOK", type = WebhookChannel.class)
})
public sealed interface Channel permits EmailChannel, SlackChannel, WebhookChannel {
}

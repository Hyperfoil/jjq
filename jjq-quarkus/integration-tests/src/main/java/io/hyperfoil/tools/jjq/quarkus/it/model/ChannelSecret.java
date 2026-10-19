package io.hyperfoil.tools.jjq.quarkus.it.model;

import io.hyperfoil.tools.jjq.mapper.JqSubtype;
import io.hyperfoil.tools.jjq.mapper.JqType;

/**
 * Many-to-one discriminator family: SLACK and GITHUB_ISSUE both bind
 * {@link TokenSecret}. Serialization stays unambiguous because the
 * {@code method} field value drives it.
 */
@JqType(discriminator = "method", existingProperty = true, mappings = {
        @JqSubtype(value = "SLACK", type = TokenSecret.class),
        @JqSubtype(value = "GITHUB_ISSUE", type = TokenSecret.class),
        @JqSubtype(value = "WEBHOOK", type = HeaderSecret.class)
})
public sealed interface ChannelSecret permits TokenSecret, HeaderSecret {
}

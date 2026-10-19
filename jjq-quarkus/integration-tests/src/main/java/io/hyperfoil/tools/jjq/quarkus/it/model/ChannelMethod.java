package io.hyperfoil.tools.jjq.quarkus.it.model;

/** Wire discriminator for the channel family (plain name binding). */
public enum ChannelMethod {
    EMAIL,
    SLACK,
    GITHUB_ISSUE,
    WEBHOOK
}

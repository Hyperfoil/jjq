package io.hyperfoil.tools.jjq.quarkus.it.model;

import io.hyperfoil.tools.jjq.mapper.JqMapped;

/** Simple generated record mapping used by the HTTP smoke tests. */
@JqMapped
public record Greeting(String message, int count) {
}

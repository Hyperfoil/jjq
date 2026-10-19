package io.hyperfoil.tools.jjq.quarkus.it.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.hyperfoil.tools.jjq.mapper.JqMapped;

/** Generated property binding combined with a runtime enum conversion. */
@JqMapped
public record JacksonGreeting(@JsonProperty("display-name") String name, AuthType auth) {
}

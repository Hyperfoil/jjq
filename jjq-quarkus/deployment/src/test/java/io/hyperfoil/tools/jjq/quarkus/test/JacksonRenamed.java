package io.hyperfoil.tools.jjq.quarkus.test;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.hyperfoil.tools.jjq.mapper.JqMapped;

/** REST fixture: Jackson-renamed property binds through the CDI mapper. */
@JqMapped
public record JacksonRenamed(@JsonProperty("display-name") String displayName) {}

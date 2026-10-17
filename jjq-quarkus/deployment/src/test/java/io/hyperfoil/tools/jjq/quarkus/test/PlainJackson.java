package io.hyperfoil.tools.jjq.quarkus.test;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Mapper-level fixture, deliberately <em>not</em> {@code @JqMapped}: binds
 * through the reflection path so generated and reflective handling of bridge
 * annotations stay consistent.
 */
public record PlainJackson(@JsonProperty("display-name") String displayName) {}

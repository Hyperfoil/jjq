package io.hyperfoil.tools.jjq.quarkus.test;

import io.hyperfoil.tools.jjq.mapper.JqMapped;

/** REST round-trip fixture: a mapped record served as JSON. */
@JqMapped
public record Greeting(String message, int count) {}

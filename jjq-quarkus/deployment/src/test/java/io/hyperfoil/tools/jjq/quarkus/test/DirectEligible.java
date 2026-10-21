package io.hyperfoil.tools.jjq.quarkus.test;

import io.hyperfoil.tools.jjq.mapper.JqMapped;

/** Flat request fixture: eligible for direct reader generation. */
@JqMapped
public record DirectEligible(String name, int age) {}

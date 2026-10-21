package io.hyperfoil.tools.jjq.quarkus.test;

import io.hyperfoil.tools.jjq.mapper.JqMapped;

/** Nested request fixture: valid mapping but outside the MVP reader scope. */
@JqMapped
public record DirectNested(DirectEligible inner, String note) {}

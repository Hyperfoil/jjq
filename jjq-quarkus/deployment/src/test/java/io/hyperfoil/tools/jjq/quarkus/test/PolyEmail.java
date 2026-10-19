package io.hyperfoil.tools.jjq.quarkus.test;

import io.hyperfoil.tools.jjq.mapper.JqMapped;

@JqMapped
public record PolyEmail(PolyMethod method, String to) implements PolyChannel {
    public PolyEmail {
        method = PolyMethod.EMAIL;
    }
}

package io.hyperfoil.tools.jjq.quarkus.test;

import io.hyperfoil.tools.jjq.mapper.JqMapped;

@JqMapped
public record KeyNodeB(int b) implements KeyNode {
}

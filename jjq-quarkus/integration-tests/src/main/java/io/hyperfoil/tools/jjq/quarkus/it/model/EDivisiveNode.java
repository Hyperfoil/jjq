package io.hyperfoil.tools.jjq.quarkus.it.model;

import io.hyperfoil.tools.jjq.mapper.JqMapped;

@JqMapped
public record EDivisiveNode(int windowLen, double maxPvalue) implements NodeConfig {
}

package io.hyperfoil.tools.jjq.quarkus.it.model;

import io.hyperfoil.tools.jjq.mapper.JqMapped;

@JqMapped
public record StdDevNode(int windowSize, double deviations) implements NodeConfig {
}

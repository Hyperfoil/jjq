package io.hyperfoil.tools.jjq.quarkus.it.model;

import io.hyperfoil.tools.jjq.mapper.JqMapped;
import jakarta.json.bind.annotation.JsonbProperty;

/** JSON-B annotations interpreted by jjq without a JSON-B serialization engine. */
@JqMapped
public record Repository(@JsonbProperty("repo-path") String path) {
}

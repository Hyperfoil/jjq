package io.hyperfoil.tools.jjq.quarkus.test;

import jakarta.json.bind.annotation.JsonbProperty;

import io.hyperfoil.tools.jjq.mapper.JqMapped;

/** REST fixture: JSON-B-renamed property binds through the CDI mapper. */
@JqMapped
public record JsonbRenamed(@JsonbProperty("repo-paths") String repoPaths) {}

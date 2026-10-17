package io.hyperfoil.tools.jjq.quarkus.test;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.json.bind.annotation.JsonbProperty;

/**
 * Mapper-level fixture: conflicting bridge renames resolve deterministically
 * (Jackson first, matching the annotation processor's precedence).
 */
public record BothRenamed(
        @JsonProperty("jackson-name") @JsonbProperty("jsonb-name") String value) {}

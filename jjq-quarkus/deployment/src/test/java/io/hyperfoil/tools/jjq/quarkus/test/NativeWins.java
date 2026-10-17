package io.hyperfoil.tools.jjq.quarkus.test;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.hyperfoil.tools.jjq.mapper.JqMapped;
import io.hyperfoil.tools.jjq.mapper.JqName;

/** Mapper-level fixture: native rename wins over bridge renames. */
@JqMapped
public record NativeWins(@JqName("native-name") @JsonProperty("jackson-name") String value) {}

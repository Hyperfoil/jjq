package io.hyperfoil.tools.jjq.quarkus.test;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import io.hyperfoil.tools.jjq.mapper.JqMapped;

/** REST fixture: enum wire form resolved through bridge-registered runtime helpers. */
@JqMapped
public record AuthHolder(String name, AuthType auth) {

    public enum AuthType {
        API_KEY, OAUTH;

        @JsonValue
        public String toWire() {
            return name().toLowerCase().replace('_', '-');
        }

        @JsonCreator
        public static AuthType fromWire(String wire) {
            for (AuthType candidate : values()) {
                if (candidate.toWire().equals(wire)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown auth type: " + wire);
        }
    }
}

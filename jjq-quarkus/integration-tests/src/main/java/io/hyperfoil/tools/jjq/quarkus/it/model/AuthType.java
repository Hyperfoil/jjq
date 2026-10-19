package io.hyperfoil.tools.jjq.quarkus.it.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Public bridge members eligible for augmentation-generated direct calls. */
public enum AuthType {
    API_KEY, OAUTH;

    @JsonValue
    public String toWire() {
        return switch (this) {
            case API_KEY -> "api-key";
            case OAUTH -> "oauth";
        };
    }

    @JsonCreator
    public static AuthType fromWire(String wire) {
        return switch (wire) {
            case "api-key" -> API_KEY;
            case "oauth" -> OAUTH;
            default -> throw new IllegalArgumentException("Unknown authentication type");
        };
    }
}

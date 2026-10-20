package io.hyperfoil.tools.jjq.schema;

import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;

/** Shared diagnostic object shape for every keyword failure. */
final class Failures {

    private Failures() {}

    /** Escape a token for JSON Pointer locations ({@code ~} first, then {@code /}). */
    static String escapePointer(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    static JqValue failure(String instanceLocation, String schemaLocation,
            String keyword, String message) {
        return JqObject.builder(4)
                .putUnchecked("instanceLocation", JqString.of(instanceLocation))
                .putUnchecked("keywordLocation", JqString.of(schemaLocation + "/" + keyword))
                .putUnchecked("keyword", JqString.of(keyword))
                .putUnchecked("message", JqString.of(message))
                .build();
    }
}

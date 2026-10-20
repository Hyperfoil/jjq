package io.hyperfoil.tools.jjq.schema;

import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;

/** Shared diagnostic object shape for every keyword failure. */
final class Failures {

    private Failures() {}

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

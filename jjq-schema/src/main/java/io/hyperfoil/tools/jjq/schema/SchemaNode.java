package io.hyperfoil.tools.jjq.schema;

import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;
import java.util.List;

/**
 * One compiled schema node: the immutable boolean/reporting rule pair for a
 * single schema location. Both executions share assertion semantics; the
 * boolean fast path only skips diagnostic construction, never checks.
 */
final class SchemaNode {

    private final boolean alwaysFalse;

    private SchemaNode(boolean alwaysFalse) {
        this.alwaysFalse = alwaysFalse;
    }

    static SchemaNode alwaysTrue() {
        return new SchemaNode(false);
    }

    static SchemaNode alwaysFalse() {
        return new SchemaNode(true);
    }

    boolean isValid(JqValue instance) {
        return !alwaysFalse;
    }

    void validateInto(JqValue instance, String instanceLocation, List<JqValue> errors) {
        if (alwaysFalse) {
            errors.add(JqObject.builder(4)
                    .putUnchecked("instanceLocation", JqString.of(instanceLocation))
                    .putUnchecked("keywordLocation", JqString.of(""))
                    .putUnchecked("keyword", JqString.of("false"))
                    .putUnchecked("message", JqString.of("boolean schema 'false' never validates"))
                    .build());
        }
    }
}

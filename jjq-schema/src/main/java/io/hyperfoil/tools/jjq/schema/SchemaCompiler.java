package io.hyperfoil.tools.jjq.schema;

import io.hyperfoil.tools.jjq.value.JqBoolean;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;

/**
 * Compiles a schema tree into an immutable {@link SchemaNode} graph.
 * Keyword shapes are validated here, once, without coercion; per-document
 * work must not reread them.
 */
final class SchemaCompiler {

    SchemaNode compile(JqValue schema) {
        if (schema instanceof JqBoolean b) {
            return b.booleanValue() ? SchemaNode.alwaysTrue() : SchemaNode.alwaysFalse();
        }
        if (schema instanceof JqObject obj) {
            if (obj.size() == 0) return SchemaNode.alwaysTrue();
            for (String key : obj.keys()) {
                throw new JqSchemaException("unsupported keyword '" + key + "'", "#");
            }
            throw new AssertionError("unreachable");
        }
        throw new JqSchemaException(
                "schema must be an object or boolean, was " + schema.type().jqName(), "#");
    }
}

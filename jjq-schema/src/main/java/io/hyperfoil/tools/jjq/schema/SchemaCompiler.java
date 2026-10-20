package io.hyperfoil.tools.jjq.schema;

import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqBoolean;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiles a schema tree into an immutable {@link SchemaNode} graph.
 * Keyword shapes are validated here, once, without coercion; per-document
 * work must not reread them. Retained {@code enum}/{@code const} values are
 * deep-copied so later mutation of a map-backed schema cannot change the
 * validator (scalars are immutable and shared).
 */
final class SchemaCompiler {

    SchemaNode compile(JqValue schema) {
        return compileAt(schema, "");
    }

    private SchemaNode compileAt(JqValue schema, String location) {
        if (schema instanceof JqBoolean b) {
            return b.booleanValue() ? SchemaNode.alwaysTrue() : SchemaNode.alwaysFalse();
        }
        if (schema instanceof JqObject obj) {
            if (obj.size() == 0) return SchemaNode.alwaysTrue();
            if (obj.has("$schema")) {
                checkDialect(obj.get("$schema"), location);
            }
            int typeMask = SchemaNode.TYPE_ALL;
            List<JqValue> enumValues = null;
            JqValue constValue = null;
            boolean hasConst = false;
            for (String key : obj.keys()) {
                switch (key) {
                    case "$schema", "$id" -> {}
                    case "title", "description", "default", "deprecated",
                         "readOnly", "writeOnly", "examples", "$comment" -> {}
                    case "type" -> typeMask = parseType(obj.get(key), location);
                    case "enum" -> enumValues = parseEnum(obj.get(key), location);
                    case "const" -> {
                        constValue = deepCopy(obj.get(key));
                        hasConst = true;
                    }
                    default -> throw new JqSchemaException(
                            "unsupported keyword '" + key + "'", location);
                }
            }
            return SchemaNode.of(typeMask, enumValues, constValue, hasConst, location);
        }
        throw new JqSchemaException(
                "schema must be an object or boolean, was " + schema.type().jqName(), location);
    }

    private void checkDialect(JqValue value, String location) {
        if (!(value instanceof JqString s)) {
            throw new JqSchemaException("$schema must be a string", location);
        }
        switch (s.stringValue()) {
            case "https://json-schema.org/draft/2020-12/schema",
                 "https://json-schema.org/draft/2020-12/schema#",
                 "http://json-schema.org/draft-07/schema",
                 "http://json-schema.org/draft-07/schema#" -> {}
            default -> throw new JqSchemaException(
                    "unsupported dialect '" + s.stringValue() + "'", location);
        }
        // Dialect differences (tuple items, $ref siblings) normalize during
        // compilation of the keywords they affect; nothing to store yet.
    }

    private int parseType(JqValue value, String location) {
        if (value instanceof JqString s) return typeBit(s.stringValue(), location);
        if (value instanceof JqArray arr) {
            if (arr.size() == 0) {
                throw new JqSchemaException("\"type\" array must not be empty", location);
            }
            int mask = 0;
            for (int i = 0; i < arr.size(); i++) {
                if (!(arr.get(i) instanceof JqString s)) {
                    throw new JqSchemaException("\"type\" array must contain only strings", location);
                }
                int bit = typeBit(s.stringValue(), location);
                if ((mask & bit) != 0) {
                    throw new JqSchemaException("duplicate type '" + s.stringValue() + "'", location);
                }
                mask |= bit;
            }
            return mask;
        }
        throw new JqSchemaException("\"type\" must be a string or an array", location);
    }

    private int typeBit(String name, String location) {
        return switch (name) {
            case "null" -> SchemaNode.TYPE_NULL;
            case "boolean" -> SchemaNode.TYPE_BOOLEAN;
            case "object" -> SchemaNode.TYPE_OBJECT;
            case "array" -> SchemaNode.TYPE_ARRAY;
            case "number" -> SchemaNode.TYPE_NUMBER;
            case "string" -> SchemaNode.TYPE_STRING;
            case "integer" -> SchemaNode.TYPE_INTEGER;
            default -> throw new JqSchemaException("unknown type '" + name + "'", location);
        };
    }

    private List<JqValue> parseEnum(JqValue value, String location) {
        if (!(value instanceof JqArray arr)) {
            throw new JqSchemaException("\"enum\" must be an array", location);
        }
        List<JqValue> snapshot = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) snapshot.add(deepCopy(arr.get(i)));
        return List.copyOf(snapshot);
    }

    static JqValue deepCopy(JqValue value) {
        if (value instanceof JqArray arr) {
            List<JqValue> copy = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) copy.add(deepCopy(arr.get(i)));
            return JqArray.ofTrusted(copy);
        }
        if (value instanceof JqObject obj) {
            JqObject.Builder builder = JqObject.builder(obj.size());
            obj.forEach((key, field) -> builder.putUnchecked(key, deepCopy(field)));
            return builder.build();
        }
        return value;
    }
}

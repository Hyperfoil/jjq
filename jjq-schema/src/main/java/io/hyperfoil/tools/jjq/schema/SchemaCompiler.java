package io.hyperfoil.tools.jjq.schema;

import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqBoolean;
import io.hyperfoil.tools.jjq.value.JqNumber;
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

    /** True while compiling a draft-07 document (tuple items, $ref siblings). */
    private boolean draft07;

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
            JqNumber lower = null;
            boolean lowerExclusive = false;
            boolean hasLower = false;
            JqNumber upper = null;
            boolean upperExclusive = false;
            boolean hasUpper = false;
            JqNumber multipleOf = null;
            JqNumber minLength = null;
            JqNumber maxLength = null;
            java.util.regex.Pattern pattern = null;
            String patternSource = null;
            List<String> propNames = new ArrayList<>();
            List<SchemaNode> propNodes = new ArrayList<>();
            List<java.util.regex.Pattern> patterns = new ArrayList<>();
            List<SchemaNode> patternNodes = new ArrayList<>();
            List<String> required = List.of();
            SchemaNode additionalNode = null;
            boolean hasAdditional = false;
            JqNumber minProperties = null;
            JqNumber maxProperties = null;
            JqValue itemsRaw = null;
            JqValue prefixRaw = null;
            JqValue additionalItemsRaw = null;
            boolean hasAdditionalItems = false;
            JqNumber minItems = null;
            JqNumber maxItems = null;
            SchemaNode containsNode = null;
            JqNumber minContains = null;
            JqNumber maxContains = null;
            boolean unique = false;
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
                    case "minimum" -> {
                        lower = requireNumber(obj.get(key), "minimum", location);
                        lowerExclusive = false;
                        hasLower = true;
                    }
                    case "exclusiveMinimum" -> {
                        lower = requireNumber(obj.get(key), "exclusiveMinimum", location);
                        lowerExclusive = true;
                        hasLower = true;
                    }
                    case "maximum" -> {
                        upper = requireNumber(obj.get(key), "maximum", location);
                        upperExclusive = false;
                        hasUpper = true;
                    }
                    case "exclusiveMaximum" -> {
                        upper = requireNumber(obj.get(key), "exclusiveMaximum", location);
                        upperExclusive = true;
                        hasUpper = true;
                    }
                    case "multipleOf" -> multipleOf = parseMultipleOf(obj.get(key), location);
                    case "minLength" -> minLength = parseNonNegativeInt(obj.get(key), "minLength", location);
                    case "maxLength" -> maxLength = parseNonNegativeInt(obj.get(key), "maxLength", location);
                    case "pattern" -> {
                        if (!(obj.get(key) instanceof JqString s)) {
                            throw new JqSchemaException("\"pattern\" must be a string", location);
                        }
                        patternSource = s.stringValue();
                        try {
                            pattern = java.util.regex.Pattern.compile(patternSource);
                        } catch (java.util.regex.PatternSyntaxException e) {
                            throw new JqSchemaException(
                                    "invalid \"pattern\" regex: " + e.getMessage(), location);
                        }
                    }
                    case "properties" -> {
                        if (!(obj.get(key) instanceof JqObject props)) {
                            throw new JqSchemaException("\"properties\" must be an object", location);
                        }
                        for (String name : props.keys()) {
                            propNames.add(name);
                            propNodes.add(compileAt(props.get(name),
                                    location + "/properties/" + Failures.escapePointer(name)));
                        }
                    }
                    case "patternProperties" -> {
                        if (!(obj.get(key) instanceof JqObject props)) {
                            throw new JqSchemaException(
                                    "\"patternProperties\" must be an object", location);
                        }
                        for (String name : props.keys()) {
                            java.util.regex.Pattern compiled;
                            try {
                                compiled = java.util.regex.Pattern.compile(name);
                            } catch (java.util.regex.PatternSyntaxException e) {
                                throw new JqSchemaException(
                                        "invalid \"patternProperties\" regex '" + name + "': "
                                                + e.getMessage(),
                                        location);
                            }
                            patterns.add(compiled);
                            patternNodes.add(compileAt(props.get(name),
                                    location + "/patternProperties/" + Failures.escapePointer(name)));
                        }
                    }
                    case "required" -> required = parseRequired(obj.get(key), location);
                    case "additionalProperties" -> {
                        JqValue additional = obj.get(key);
                        String childLocation = location + "/additionalProperties";
                        if (additional instanceof JqBoolean b) {
                            additionalNode = b.booleanValue() ? null
                                    : SchemaNode.alwaysFalse(childLocation);
                        } else {
                            additionalNode = compileAt(additional, childLocation);
                        }
                        hasAdditional = true;
                    }
                    case "minProperties" ->
                        minProperties = parseNonNegativeInt(obj.get(key), "minProperties", location);
                    case "maxProperties" ->
                        maxProperties = parseNonNegativeInt(obj.get(key), "maxProperties", location);
                    case "items" -> itemsRaw = obj.get(key);
                    case "prefixItems" -> prefixRaw = obj.get(key);
                    case "additionalItems" -> {
                        additionalItemsRaw = obj.get(key);
                        hasAdditionalItems = true;
                    }
                    case "minItems" ->
                        minItems = parseNonNegativeInt(obj.get(key), "minItems", location);
                    case "maxItems" ->
                        maxItems = parseNonNegativeInt(obj.get(key), "maxItems", location);
                    case "contains" ->
                        containsNode = compileAt(obj.get(key), location + "/contains");
                    case "minContains" ->
                        minContains = parseNonNegativeInt(obj.get(key), "minContains", location);
                    case "maxContains" ->
                        maxContains = parseNonNegativeInt(obj.get(key), "maxContains", location);
                    case "uniqueItems" -> {
                        if (!(obj.get(key) instanceof JqBoolean b)) {
                            throw new JqSchemaException("\"uniqueItems\" must be a boolean", location);
                        }
                        unique = b.booleanValue();
                    }
                    default -> throw new JqSchemaException(
                            "unsupported keyword '" + key + "'", location);
                }
            }
            NumberPlan numberPlan = hasLower || hasUpper || multipleOf != null
                    ? new NumberPlan(lower, lowerExclusive, hasLower,
                            upper, upperExclusive, hasUpper, multipleOf)
                    : null;
            StringPlan stringPlan = minLength != null || maxLength != null || pattern != null
                    ? new StringPlan(minLength, maxLength, pattern, patternSource)
                    : null;
            ObjectPlan objectPlan = !propNames.isEmpty() || !patterns.isEmpty() || !required.isEmpty()
                    || hasAdditional || minProperties != null || maxProperties != null
                    ? new ObjectPlan(propNames.toArray(new String[0]),
                            propNodes.toArray(new SchemaNode[0]),
                            patterns.toArray(new java.util.regex.Pattern[0]),
                            patternNodes.toArray(new SchemaNode[0]),
                            required, additionalNode, minProperties, maxProperties)
                    : null;
            ArrayPlan arrayPlan = buildArrayPlan(itemsRaw, prefixRaw, additionalItemsRaw,
                    hasAdditionalItems, minItems, maxItems,
                    containsNode, minContains, maxContains, unique, location);
            return SchemaNode.of(typeMask, enumValues, constValue, hasConst,
                    numberPlan, stringPlan, objectPlan, arrayPlan, location);
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
                 "https://json-schema.org/draft/2020-12/schema#" -> draft07 = false;
            case "http://json-schema.org/draft-07/schema",
                 "http://json-schema.org/draft-07/schema#" -> draft07 = true;
            default -> throw new JqSchemaException(
                    "unsupported dialect '" + s.stringValue() + "'", location);
        }
        // Dialect differences (tuple items, $ref siblings) normalize during
        // compilation of the keywords they affect.
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

    private JqNumber requireNumber(JqValue value, String keyword, String location) {
        if (!(value instanceof JqNumber number)) {
            throw new JqSchemaException("\"" + keyword + "\" must be a number", location);
        }
        return number;
    }

    private JqNumber parseMultipleOf(JqValue value, String location) {
        JqNumber divisor = requireNumber(value, "multipleOf", location);
        if (JsonSemantics.compareNumbers(divisor, JqNumber.of(0)) <= 0) {
            throw new JqSchemaException("\"multipleOf\" must be strictly greater than 0", location);
        }
        return divisor;
    }

    private JqNumber parseNonNegativeInt(JqValue value, String keyword, String location) {
        JqNumber bound = requireNumber(value, keyword, location);
        if (!bound.isIntegral()
                || JsonSemantics.compareNumbers(bound, JqNumber.of(0)) < 0) {
            throw new JqSchemaException(
                    "\"" + keyword + "\" must be a non-negative integer", location);
        }
        return bound;
    }

    private List<String> parseRequired(JqValue value, String location) {
        if (!(value instanceof JqArray arr)) {
            throw new JqSchemaException("\"required\" must be an array", location);
        }
        List<String> names = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            if (!(arr.get(i) instanceof JqString s)) {
                throw new JqSchemaException(
                        "\"required\" array must contain only strings", location);
            }
            names.add(s.stringValue());
        }
        return List.copyOf(names);
    }

    private ArrayPlan buildArrayPlan(JqValue itemsRaw, JqValue prefixRaw,
            JqValue additionalItemsRaw, boolean hasAdditionalItems,
            JqNumber minItems, JqNumber maxItems,
            SchemaNode containsNode, JqNumber minContains, JqNumber maxContains,
            boolean unique, String location) {
        List<SchemaNode> prefix = new ArrayList<>();
        SchemaNode tail = null;
        if (draft07) {
            if (itemsRaw instanceof JqArray tuple) {
                for (int i = 0; i < tuple.size(); i++) {
                    prefix.add(compileAt(tuple.get(i), location + "/items/" + i));
                }
                if (hasAdditionalItems) {
                    if (additionalItemsRaw instanceof JqBoolean b) {
                        tail = b.booleanValue() ? null
                                : SchemaNode.alwaysFalse(location + "/additionalItems");
                    } else {
                        tail = compileAt(additionalItemsRaw, location + "/additionalItems");
                    }
                }
            } else if (itemsRaw != null) {
                tail = compileAt(itemsRaw, location + "/items");
                // Draft-07 ignores additionalItems alongside single-schema items.
            }
            // Bare additionalItems without tuple items has no meaning; ignored.
        } else {
            if (itemsRaw != null) {
                if (itemsRaw instanceof JqArray) {
                    throw new JqSchemaException(
                            "array-form \"items\" is draft-07; use \"prefixItems\"", location);
                }
                tail = compileAt(itemsRaw, location + "/items");
            }
            if (prefixRaw != null) {
                if (!(prefixRaw instanceof JqArray tuple)) {
                    throw new JqSchemaException("\"prefixItems\" must be an array", location);
                }
                for (int i = 0; i < tuple.size(); i++) {
                    prefix.add(compileAt(tuple.get(i), location + "/prefixItems/" + i));
                }
            }
            if (hasAdditionalItems) {
                throw new JqSchemaException(
                        "\"additionalItems\" is draft-07 only; use \"prefixItems\"/\"items\"",
                        location);
            }
        }
        if (prefix.isEmpty() && tail == null && minItems == null && maxItems == null
                && containsNode == null && minContains == null && maxContains == null
                && !unique) {
            return null;
        }
        return new ArrayPlan(prefix.toArray(new SchemaNode[0]), tail,
                minItems, maxItems, containsNode, minContains, maxContains, unique);
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

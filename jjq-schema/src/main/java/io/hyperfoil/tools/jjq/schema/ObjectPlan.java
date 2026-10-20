package io.hyperfoil.tools.jjq.schema;

import io.hyperfoil.tools.jjq.value.JqNumber;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Compiled object rules. Two traversal shapes, chosen at compile time:
 * property-directed when no pattern or additional-property rule can see
 * unknown fields (undeclared values stay untouched, deferred strings never
 * materialize), otherwise a single instance-directed pass. Required names
 * are presence-checked in both shapes; required-only names never count as
 * declared for {@code additionalProperties}.
 */
final class ObjectPlan {

    private final String[] propNames;
    private final SchemaNode[] propNodes;
    private final Pattern[] patterns;
    private final SchemaNode[] patternNodes;
    private final List<String> required;
    private final SchemaNode additional;
    private final JqNumber minProperties;
    private final JqNumber maxProperties;

    ObjectPlan(String[] propNames, SchemaNode[] propNodes,
            Pattern[] patterns, SchemaNode[] patternNodes,
            List<String> required, SchemaNode additional,
            JqNumber minProperties, JqNumber maxProperties) {
        this.propNames = propNames;
        this.propNodes = propNodes;
        this.patterns = patterns;
        this.patternNodes = patternNodes;
        this.required = required;
        this.additional = additional;
        this.minProperties = minProperties;
        this.maxProperties = maxProperties;
    }

    boolean isValid(JqObject instance) {
        if (!checkCounts(instance.size()) || !checkRequired(instance)) return false;
        if (patterns.length == 0 && additional == null) {
            for (int p = 0; p < propNames.length; p++) {
                if (instance.has(propNames[p])
                        && !propNodes[p].isValid(instance.get(propNames[p]))) {
                    return false;
                }
            }
            return true;
        }
        if (instance.isArrayBacked()) {
            for (int i = 0; i < instance.size(); i++) {
                if (!checkField(instance.keyAt(i), instance.valueAt(i))) return false;
            }
            return true;
        }
        for (String key : instance.keys()) {
            if (!checkField(key, instance.get(key))) return false;
        }
        return true;
    }

    void validateInto(JqObject instance, String instanceLocation, String schemaLocation,
            List<JqValue> errors) {
        if (!checkCounts(instance.size())) {
            errors.add(Failures.failure(instanceLocation, schemaLocation,
                    minProperties != null
                            && JsonSemantics.compareNumbers(
                                    JqNumber.of(instance.size()), minProperties) < 0
                            ? "minProperties" : "maxProperties",
                    "object has wrong number of properties"));
        }
        for (String name : required) {
            if (!instance.has(name)) {
                errors.add(Failures.failure(instanceLocation, schemaLocation, "required",
                        "required property '" + name + "' is missing"));
            }
        }
        if (patterns.length == 0 && additional == null) {
            for (int p = 0; p < propNames.length; p++) {
                if (instance.has(propNames[p])) {
                    propNodes[p].validateInto(instance.get(propNames[p]),
                            instanceLocation + "/" + Failures.escapePointer(propNames[p]), errors);
                }
            }
            return;
        }
        if (instance.isArrayBacked()) {
            for (int i = 0; i < instance.size(); i++) {
                reportField(instance.keyAt(i), instance.valueAt(i),
                        instanceLocation, errors);
            }
        } else {
            for (String key : instance.keys()) {
                reportField(key, instance.get(key), instanceLocation, errors);
            }
        }
    }

    private boolean checkCounts(int size) {
        return (minProperties == null
                || JsonSemantics.compareNumbers(JqNumber.of(size), minProperties) >= 0)
                && (maxProperties == null
                || JsonSemantics.compareNumbers(JqNumber.of(size), maxProperties) <= 0);
    }

    private boolean checkRequired(JqObject instance) {
        for (String name : required) {
            if (!instance.has(name)) return false;
        }
        return true;
    }

    private boolean checkField(String key, JqValue value) {
        boolean named = false;
        for (int p = 0; p < propNames.length; p++) {
            if (key == propNames[p] || key.equals(propNames[p])) {
                named = true;
                if (!propNodes[p].isValid(value)) return false;
            }
        }
        boolean patternHit = false;
        for (int p = 0; p < patterns.length; p++) {
            if (patterns[p].matcher(key).find()) {
                patternHit = true;
                if (!patternNodes[p].isValid(value)) return false;
            }
        }
        return named || patternHit || additional == null || additional.isValid(value);
    }

    private void reportField(String key, JqValue value, String instanceLocation,
            List<JqValue> errors) {
        String childLocation = instanceLocation + "/" + Failures.escapePointer(key);
        boolean named = false;
        for (int p = 0; p < propNames.length; p++) {
            if (key == propNames[p] || key.equals(propNames[p])) {
                named = true;
                propNodes[p].validateInto(value, childLocation, errors);
            }
        }
        boolean patternHit = false;
        for (int p = 0; p < patterns.length; p++) {
            if (patterns[p].matcher(key).find()) {
                patternHit = true;
                patternNodes[p].validateInto(value, childLocation, errors);
            }
        }
        if (!named && !patternHit && additional != null) {
            additional.validateInto(value, childLocation, errors);
        }
    }
}

package io.hyperfoil.tools.jjq.schema;

import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqNumber;
import io.hyperfoil.tools.jjq.value.JqValue;
import java.util.List;

/**
 * Compiled array rules: tuple prefix plus tail schema, cardinality,
 * {@code contains} with match counts, and structural uniqueness.
 * Uniqueness stays a linear pairwise check; hash indexing waits for a
 * measured crossover (tiny arrays dominate, per the enum lesson).
 */
final class ArrayPlan {

    private final SchemaNode[] prefix;
    private final SchemaNode tail;
    private final JqNumber minItems;
    private final JqNumber maxItems;
    private final SchemaNode contains;
    private final JqNumber minContains;
    private final JqNumber maxContains;
    private final boolean unique;

    ArrayPlan(SchemaNode[] prefix, SchemaNode tail,
            JqNumber minItems, JqNumber maxItems,
            SchemaNode contains, JqNumber minContains, JqNumber maxContains,
            boolean unique) {
        this.prefix = prefix;
        this.tail = tail;
        this.minItems = minItems;
        this.maxItems = maxItems;
        this.contains = contains;
        this.minContains = minContains;
        this.maxContains = maxContains;
        this.unique = unique;
    }

    boolean isValid(JqArray instance) {
        int size = instance.size();
        if (minItems != null
                && JsonSemantics.compareNumbers(JqNumber.of(size), minItems) < 0) {
            return false;
        }
        if (maxItems != null
                && JsonSemantics.compareNumbers(JqNumber.of(size), maxItems) > 0) {
            return false;
        }
        for (int i = 0; i < size; i++) {
            SchemaNode rule = i < prefix.length ? prefix[i] : tail;
            if (rule != null && !rule.isValid(instance.get(i))) return false;
        }
        if (contains != null && !checkContains(instance)) return false;
        return !unique || checkUnique(instance);
    }

    void validateInto(JqArray instance, String instanceLocation, String schemaLocation,
            List<JqValue> errors) {
        int size = instance.size();
        if (minItems != null
                && JsonSemantics.compareNumbers(JqNumber.of(size), minItems) < 0) {
            errors.add(Failures.failure(instanceLocation, schemaLocation, "minItems",
                    "array is shorter than minItems"));
        }
        if (maxItems != null
                && JsonSemantics.compareNumbers(JqNumber.of(size), maxItems) > 0) {
            errors.add(Failures.failure(instanceLocation, schemaLocation, "maxItems",
                    "array is longer than maxItems"));
        }
        for (int i = 0; i < size; i++) {
            SchemaNode rule = i < prefix.length ? prefix[i] : tail;
            if (rule != null) {
                rule.validateInto(instance.get(i), instanceLocation + "/" + i, errors);
            }
        }
        if (contains != null) {
            int matches = countContains(instance);
            JqNumber floor = minContains != null ? minContains : JqNumber.of(1);
            if (JsonSemantics.compareNumbers(JqNumber.of(matches), floor) < 0) {
                errors.add(Failures.failure(instanceLocation, schemaLocation, "contains",
                        "array contains too few matching elements"));
            }
            if (maxContains != null
                    && JsonSemantics.compareNumbers(JqNumber.of(matches), maxContains) > 0) {
                errors.add(Failures.failure(instanceLocation, schemaLocation, "maxContains",
                        "array contains too many matching elements"));
            }
        }
        if (unique) {
            outer:
            for (int i = 0; i < size; i++) {
                for (int j = i + 1; j < size; j++) {
                    if (JsonSemantics.schemaEquals(instance.get(i), instance.get(j))) {
                        errors.add(Failures.failure(instanceLocation + "/" + j, schemaLocation,
                                "uniqueItems", "array items are not unique"));
                        break outer;
                    }
                }
            }
        }
    }

    private boolean checkContains(JqArray instance) {
        int matches = countContains(instance);
        JqNumber floor = minContains != null ? minContains : JqNumber.of(1);
        if (JsonSemantics.compareNumbers(JqNumber.of(matches), floor) < 0) return false;
        return maxContains == null
                || JsonSemantics.compareNumbers(JqNumber.of(matches), maxContains) <= 0;
    }

    private int countContains(JqArray instance) {
        int matches = 0;
        for (int i = 0; i < instance.size(); i++) {
            if (contains.isValid(instance.get(i))) matches++;
        }
        return matches;
    }

    private static boolean checkUnique(JqArray instance) {
        int size = instance.size();
        for (int i = 0; i < size; i++) {
            for (int j = i + 1; j < size; j++) {
                if (JsonSemantics.schemaEquals(instance.get(i), instance.get(j))) return false;
            }
        }
        return true;
    }
}

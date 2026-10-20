package io.hyperfoil.tools.jjq.schema;

import io.hyperfoil.tools.jjq.value.JqNumber;
import io.hyperfoil.tools.jjq.value.JqValue;

/**
 * Compiled numeric rules: inclusive/exclusive bounds plus
 * {@code multipleOf}. Bounds compare exactly (long fast path, decimal
 * otherwise); {@code multipleOf} divides exactly the same way, with
 * non-finite values decided by IEEE remainder (never valid for NaN,
 * valid for infinite only against an infinite divisor — outside the
 * JSON input contract, but deterministic).
 */
final class NumberPlan {

    private final JqNumber lower;
    private final boolean lowerExclusive;
    private final boolean hasLower;
    private final JqNumber upper;
    private final boolean upperExclusive;
    private final boolean hasUpper;
    private final JqNumber multipleOf;

    NumberPlan(JqNumber lower, boolean lowerExclusive, boolean hasLower,
            JqNumber upper, boolean upperExclusive, boolean hasUpper,
            JqNumber multipleOf) {
        this.lower = lower;
        this.lowerExclusive = lowerExclusive;
        this.hasLower = hasLower;
        this.upper = upper;
        this.upperExclusive = upperExclusive;
        this.hasUpper = hasUpper;
        this.multipleOf = multipleOf;
    }

    boolean isValid(JqNumber instance) {
        if (hasLower) {
            int cmp = JsonSemantics.compareNumbers(instance, lower);
            if (cmp < 0 || (lowerExclusive && cmp == 0)) return false;
        }
        if (hasUpper) {
            int cmp = JsonSemantics.compareNumbers(instance, upper);
            if (cmp > 0 || (upperExclusive && cmp == 0)) return false;
        }
        return multipleOf == null || isMultiple(instance, multipleOf);
    }

    void validateInto(JqNumber instance, String instanceLocation, String schemaLocation,
            java.util.List<JqValue> errors) {
        if (hasLower) {
            int cmp = JsonSemantics.compareNumbers(instance, lower);
            if (cmp < 0 || (lowerExclusive && cmp == 0)) {
                errors.add(Failures.failure(instanceLocation, schemaLocation,
                        lowerExclusive ? "exclusiveMinimum" : "minimum",
                        "number is below the " + (lowerExclusive ? "exclusive " : "") + "minimum"));
            }
        }
        if (hasUpper) {
            int cmp = JsonSemantics.compareNumbers(instance, upper);
            if (cmp > 0 || (upperExclusive && cmp == 0)) {
                errors.add(Failures.failure(instanceLocation, schemaLocation,
                        upperExclusive ? "exclusiveMaximum" : "maximum",
                        "number is above the " + (upperExclusive ? "exclusive " : "") + "maximum"));
            }
        }
        if (multipleOf != null && !isMultiple(instance, multipleOf)) {
            errors.add(Failures.failure(instanceLocation, schemaLocation, "multipleOf",
                    "number is not a multiple of " + multipleOf.toJsonString()));
        }
    }

    private static boolean isMultiple(JqNumber instance, JqNumber divisor) {
        if (instance.isLongBacked() && divisor.isLongBacked()) {
            return instance.longValue() % divisor.longValue() == 0;
        }
        if (instance.isNaN() || instance.isInfinite()
                || divisor.isNaN() || divisor.isInfinite()) {
            return instance.doubleValue() % divisor.doubleValue() == 0;
        }
        return instance.decimalValue().remainder(divisor.decimalValue()).signum() == 0;
    }
}

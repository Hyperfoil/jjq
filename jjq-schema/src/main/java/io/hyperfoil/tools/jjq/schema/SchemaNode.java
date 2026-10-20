package io.hyperfoil.tools.jjq.schema;

import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqBoolean;
import io.hyperfoil.tools.jjq.value.JqNull;
import io.hyperfoil.tools.jjq.value.JqNumber;
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

    static final int TYPE_NULL = 1;
    static final int TYPE_BOOLEAN = 2;
    static final int TYPE_OBJECT = 4;
    static final int TYPE_ARRAY = 8;
    static final int TYPE_NUMBER = 16;
    static final int TYPE_STRING = 32;
    static final int TYPE_INTEGER = 64;
    static final int TYPE_ALL = 127;

    private final boolean alwaysFalse;
    private final int typeMask;
    private final List<JqValue> enumValues;
    private final JqValue constValue;
    private final boolean hasConst;
    private final NumberPlan numberPlan;
    private final StringPlan stringPlan;
    private final ObjectPlan objectPlan;
    private final ArrayPlan arrayPlan;
    /** Referenced schema (single-element slot: indirection only for recursion). */
    private final SchemaNode[] refTarget;
    private final String schemaLocation;

    private SchemaNode(boolean alwaysFalse, int typeMask, List<JqValue> enumValues,
            JqValue constValue, boolean hasConst,
            NumberPlan numberPlan, StringPlan stringPlan,
            ObjectPlan objectPlan, ArrayPlan arrayPlan,
            SchemaNode[] refTarget, String schemaLocation) {
        this.alwaysFalse = alwaysFalse;
        this.typeMask = typeMask;
        this.enumValues = enumValues;
        this.constValue = constValue;
        this.hasConst = hasConst;
        this.numberPlan = numberPlan;
        this.stringPlan = stringPlan;
        this.objectPlan = objectPlan;
        this.arrayPlan = arrayPlan;
        this.refTarget = refTarget;
        this.schemaLocation = schemaLocation;
    }

    static SchemaNode alwaysTrue() {
        return new SchemaNode(false, TYPE_ALL, null, null, false,
                null, null, null, null, null, "");
    }

    static SchemaNode alwaysFalse() {
        return alwaysFalse("");
    }

    static SchemaNode alwaysFalse(String schemaLocation) {
        return new SchemaNode(true, TYPE_ALL, null, null, false,
                null, null, null, null, null, schemaLocation);
    }

    static SchemaNode of(int typeMask, List<JqValue> enumValues,
            JqValue constValue, boolean hasConst,
            NumberPlan numberPlan, StringPlan stringPlan,
            ObjectPlan objectPlan, ArrayPlan arrayPlan,
            SchemaNode[] refSlot, String schemaLocation) {
        return new SchemaNode(false, typeMask, enumValues, constValue, hasConst,
                numberPlan, stringPlan, objectPlan, arrayPlan, refSlot, schemaLocation);
    }

    static SchemaNode ref(SchemaNode[] slot, String schemaLocation) {
        return new SchemaNode(false, TYPE_ALL, null, null, false,
                null, null, null, null, slot, schemaLocation);
    }

    boolean isValid(JqValue instance) {
        if (alwaysFalse) return false;
        if (!matchesType(instance)) return false;
        if (enumValues != null && !matchesEnum(instance)) return false;
        if (hasConst && !JsonSemantics.schemaEquals(instance, constValue)) return false;
        if (instance instanceof JqNumber number && numberPlan != null
                && !numberPlan.isValid(number)) {
            return false;
        }
        if (instance instanceof JqString string && stringPlan != null
                && !stringPlan.isValid(string)) {
            return false;
        }
        if (instance instanceof JqObject object && objectPlan != null
                && !objectPlan.isValid(object)) {
            return false;
        }
        if (instance instanceof JqArray array && arrayPlan != null
                && !arrayPlan.isValid(array)) {
            return false;
        }
        return refTarget == null || refTarget[0].isValid(instance);
    }

    void validateInto(JqValue instance, String instanceLocation, List<JqValue> errors) {
        if (alwaysFalse) {
            errors.add(Failures.failure(instanceLocation, "", "false",
                    "boolean schema 'false' never validates"));
            return;
        }
        // Every applicable keyword reports independently (no early return):
        // an instance failing `type` still gets its `enum`/`const` verdicts.
        if (!matchesType(instance)) {
            errors.add(Failures.failure(instanceLocation, schemaLocation, "type",
                    "expected " + expectedNames() + " but was " + instance.type().jqName()));
        }
        if (enumValues != null && !matchesEnum(instance)) {
            errors.add(Failures.failure(instanceLocation, schemaLocation, "enum",
                    "value is not one of the allowed enum values"));
        }
        if (hasConst && !JsonSemantics.schemaEquals(instance, constValue)) {
            errors.add(Failures.failure(instanceLocation, schemaLocation, "const",
                    "value does not equal const"));
        }
        if (instance instanceof JqNumber number && numberPlan != null) {
            numberPlan.validateInto(number, instanceLocation, schemaLocation, errors);
        }
        if (instance instanceof JqString string && stringPlan != null) {
            stringPlan.validateInto(string, instanceLocation, schemaLocation, errors);
        }
        if (instance instanceof JqObject object && objectPlan != null) {
            objectPlan.validateInto(object, instanceLocation, schemaLocation, errors);
        }
        if (instance instanceof JqArray array && arrayPlan != null) {
            arrayPlan.validateInto(array, instanceLocation, schemaLocation, errors);
        }
        if (refTarget != null) {
            refTarget[0].validateInto(instance, instanceLocation, errors);
        }
    }

    private boolean matchesType(JqValue instance) {
        if (typeMask == TYPE_ALL) return true;
        if (instance instanceof JqNull) return (typeMask & TYPE_NULL) != 0;
        if (instance instanceof JqBoolean) return (typeMask & TYPE_BOOLEAN) != 0;
        if (instance instanceof JqObject) return (typeMask & TYPE_OBJECT) != 0;
        if (instance instanceof JqArray) return (typeMask & TYPE_ARRAY) != 0;
        if (instance instanceof JqString) return (typeMask & TYPE_STRING) != 0;
        if (instance instanceof JqNumber number) {
            if ((typeMask & TYPE_NUMBER) != 0) return true;
            return ((typeMask & TYPE_INTEGER) != 0) && number.isIntegral();
        }
        return false;
    }

    private boolean matchesEnum(JqValue instance) {
        for (JqValue candidate : enumValues) {
            if (JsonSemantics.schemaEquals(instance, candidate)) return true;
        }
        return false;
    }

    private String expectedNames() {
        StringBuilder names = new StringBuilder();
        appendName(names, TYPE_NULL, "null");
        appendName(names, TYPE_BOOLEAN, "boolean");
        appendName(names, TYPE_OBJECT, "object");
        appendName(names, TYPE_ARRAY, "array");
        appendName(names, TYPE_NUMBER, "number");
        appendName(names, TYPE_STRING, "string");
        appendName(names, TYPE_INTEGER, "integer");
        return names.toString();
    }

    private void appendName(StringBuilder names, int bit, String name) {
        if ((typeMask & bit) == 0) return;
        if (!names.isEmpty()) names.append(", ");
        names.append(name);
    }
}

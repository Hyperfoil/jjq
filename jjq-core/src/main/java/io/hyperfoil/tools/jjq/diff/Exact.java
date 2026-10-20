package io.hyperfoil.tools.jjq.diff;

import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqBoolean;
import io.hyperfoil.tools.jjq.value.JqNull;
import io.hyperfoil.tools.jjq.value.JqNumber;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;

/**
 * Exact structural equality for diff and patch work (issue #168).
 *
 * <p>Not the jq ordering: {@code compareTo} collapses distinct large
 * integers through doubles, so numbers compare mathematically here
 * (long fast path, {@code BigDecimal} otherwise). Kept local to the
 * diff package; core equality semantics stay untouched.</p>
 */
final class Exact {

    private Exact() {}

    static boolean equal(JqValue a, JqValue b) {
        if (a instanceof JqNull || b instanceof JqNull) {
            return a instanceof JqNull && b instanceof JqNull;
        }
        if (a instanceof JqBoolean x && b instanceof JqBoolean y) {
            return x.booleanValue() == y.booleanValue();
        }
        if (a instanceof JqNumber x && b instanceof JqNumber y) {
            return numbersEqual(x, y);
        }
        if (a instanceof JqString x && b instanceof JqString y) {
            return x.stringValue().equals(y.stringValue());
        }
        if (a instanceof JqArray x && b instanceof JqArray y) {
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) {
                if (!equal(x.get(i), y.get(i))) return false;
            }
            return true;
        }
        if (a instanceof JqObject x && b instanceof JqObject y) {
            if (x.size() != y.size()) return false;
            for (String key : x.keys()) {
                if (!y.has(key) || !equal(x.get(key), y.get(key))) return false;
            }
            return true;
        }
        return false;
    }

    static boolean numbersEqual(JqNumber x, JqNumber y) {
        if (x.isLongBacked() && y.isLongBacked()) {
            return x.longValue() == y.longValue();
        }
        if (x.isNaN() || x.isInfinite() || y.isNaN() || y.isInfinite()) {
            return Double.compare(x.doubleValue(), y.doubleValue()) == 0;
        }
        return x.decimalValue().compareTo(y.decimalValue()) == 0;
    }
}

package io.hyperfoil.tools.jjq.schema;

import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqBoolean;
import io.hyperfoil.tools.jjq.value.JqNull;
import io.hyperfoil.tools.jjq.value.JqNumber;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;

/**
 * JSON Schema equality and hashing over {@link JqValue} (issue #167).
 *
 * <p>Deliberately not the jq ordering: {@code compareTo} compares
 * non-long numerics through doubles (u64 and u64+1 collapse), and
 * {@code hashCode} is representation-dependent (double-backed 1.5 and
 * decimal 1.5 are equal but hash differently), which breaks
 * {@code HashSet}-based matching in both directions. These helpers are
 * exact and representation-independent; core stays untouched.</p>
 */
final class JsonSemantics {

    private JsonSemantics() {}

    /** JSON Schema structural equality: numbers compare mathematically. */
    static boolean schemaEquals(JqValue a, JqValue b) {
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
                if (!schemaEquals(x.get(i), y.get(i))) return false;
            }
            return true;
        }
        if (a instanceof JqObject x && b instanceof JqObject y) {
            if (x.size() != y.size()) return false;
            for (String key : x.keys()) {
                if (!y.has(key) || !schemaEquals(x.get(key), y.get(key))) return false;
            }
            return true;
        }
        return false;
    }

    /** Exact numeric equality: long fast path, decimal otherwise. */
    static boolean numbersEqual(JqNumber x, JqNumber y) {
        return compareNumbers(x, y) == 0;
    }

    /**
     * Exact numeric ordering: long fast path, doubles only for
     * non-finite values, decimal comparison otherwise.
     */
    static int compareNumbers(JqNumber x, JqNumber y) {
        if (x.isLongBacked() && y.isLongBacked()) {
            return Long.compare(x.longValue(), y.longValue());
        }
        if (x.isNaN() || x.isInfinite() || y.isNaN() || y.isInfinite()) {
            return Double.compare(x.doubleValue(), y.doubleValue());
        }
        return x.decimalValue().compareTo(y.decimalValue());
    }

    /**
     * Hash consistent with {@link #schemaEquals}: equal values hash alike
     * across representations. Integral and fractional numbers all hash via
     * the stripped decimal form; object field order is irrelevant.
     */
    static int schemaHash(JqValue v) {
        if (v instanceof JqNull) return 0;
        if (v instanceof JqBoolean b) return Boolean.hashCode(b.booleanValue());
        if (v instanceof JqNumber n) {
            if (n.isNaN() || n.isInfinite()) return Double.hashCode(n.doubleValue());
            return n.decimalValue().stripTrailingZeros().hashCode();
        }
        if (v instanceof JqString s) return s.stringValue().hashCode();
        if (v instanceof JqArray a) {
            int hash = 1;
            for (int i = 0; i < a.size(); i++) hash = 31 * hash + schemaHash(a.get(i));
            return hash;
        }
        if (v instanceof JqObject o) {
            int hash = 0;
            for (String key : o.keys()) hash += key.hashCode() ^ schemaHash(o.get(key));
            return hash;
        }
        throw new AssertionError("unknown JqValue: " + v);
    }
}

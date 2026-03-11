package io.hyperfoil.tools.jjq.value;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public sealed interface JqValue extends Comparable<JqValue>
        permits JqNull, JqBoolean, JqNumber, JqString, JqArray, JqObject {

    enum Type {
        NULL, BOOLEAN, NUMBER, STRING, ARRAY, OBJECT;

        public String jqName() {
            return name().toLowerCase();
        }
    }

    Type type();

    default boolean isTruthy() {
        return this != JqNull.NULL && this != JqBoolean.FALSE;
    }

    default boolean isNull() { return this instanceof JqNull; }
    default boolean isBoolean() { return this instanceof JqBoolean; }
    default boolean isNumber() { return this instanceof JqNumber; }
    default boolean isString() { return this instanceof JqString; }
    default boolean isArray() { return this instanceof JqArray; }
    default boolean isObject() { return this instanceof JqObject; }

    default boolean booleanValue() { throw new JqTypeError("Cannot get boolean from " + type()); }
    default long longValue() { throw new JqTypeError("Cannot get number from " + type()); }
    default BigDecimal decimalValue() { throw new JqTypeError("Cannot get number from " + type()); }
    default double doubleValue() { throw new JqTypeError("Cannot get number from " + type()); }
    default String stringValue() { throw new JqTypeError("Cannot get string from " + type()); }
    default List<JqValue> arrayValue() { throw new JqTypeError("Cannot get array from " + type()); }
    default Map<String, JqValue> objectValue() { throw new JqTypeError("Cannot get object from " + type()); }

    // Safe accessors with defaults — avoid instanceof checks and JqTypeError for optional access

    /**
     * Return the string value, or the default if this is not a string.
     */
    default String asString(String defaultValue) {
        return isString() ? stringValue() : defaultValue;
    }

    /**
     * Return the long value, or the default if this is not a number.
     */
    default long asLong(long defaultValue) {
        return isNumber() ? longValue() : defaultValue;
    }

    /**
     * Return the double value, or the default if this is not a number.
     */
    default double asDouble(double defaultValue) {
        return isNumber() ? doubleValue() : defaultValue;
    }

    /**
     * Return the boolean value, or the default if this is not a boolean.
     */
    default boolean asBoolean(boolean defaultValue) {
        return isBoolean() ? booleanValue() : defaultValue;
    }

    /**
     * Return the array elements, or an empty list if this is not an array.
     */
    default List<JqValue> asList() {
        return isArray() ? arrayValue() : List.of();
    }

    /**
     * Return the object entries, or an empty map if this is not an object.
     */
    default Map<String, JqValue> asMap() {
        return isObject() ? objectValue() : Map.of();
    }

    default int length() {
        return switch (this) {
            case JqNull ignored -> 0;
            case JqBoolean ignored -> throw new JqTypeError("boolean has no length");
            case JqNumber ignored -> throw new JqTypeError("number has no length");
            case JqString s -> s.stringValue().length();
            case JqArray a -> a.arrayValue().size();
            case JqObject o -> o.objectValue().size();
        };
    }

    default JqValue add(JqValue other) {
        return switch (this) {
            case JqNull ignored -> other;
            case JqNumber n when other instanceof JqNumber m -> {
                if (n.isIntegral() && m.isIntegral()) {
                    yield JqNumber.of(Math.addExact(n.longValue(), m.longValue()));
                }
                yield JqNumber.of(n.decimalValue().add(m.decimalValue()));
            }
            case JqNumber n -> JqNumber.of(n.decimalValue().add(other.decimalValue()));
            case JqString s -> JqString.of(s.stringValue() + other.stringValue());
            case JqArray a -> {
                var list = new java.util.ArrayList<>(a.arrayValue());
                list.addAll(other.arrayValue());
                yield JqArray.of(list);
            }
            case JqObject o -> {
                var map = new java.util.LinkedHashMap<>(o.objectValue());
                map.putAll(other.objectValue());
                yield JqObject.ofTrusted(map);
            }
            default -> throw new JqTypeError("Cannot add " + type() + " and " + other.type());
        };
    }

    default JqValue subtract(JqValue other) {
        // null - null = 0 in jq
        if (this instanceof JqNull && other instanceof JqNull) return JqNumber.of(0);
        return switch (this) {
            case JqNumber n when other instanceof JqNumber m -> {
                if (n.isIntegral() && m.isIntegral()) {
                    yield JqNumber.of(Math.subtractExact(n.longValue(), m.longValue()));
                }
                yield JqNumber.of(n.decimalValue().subtract(m.decimalValue()));
            }
            case JqNumber n -> JqNumber.of(n.decimalValue().subtract(other.decimalValue()));
            case JqArray a -> {
                var toRemove = other.arrayValue();
                var list = new java.util.ArrayList<>(a.arrayValue());
                list.removeAll(toRemove);
                yield JqArray.of(list);
            }
            default -> throw new JqTypeError("Cannot subtract " + other.type() + " from " + type());
        };
    }

    default JqValue multiply(JqValue other) {
        if (this instanceof JqNull || other instanceof JqNull) {
            return JqNull.NULL;
        }
        if (this instanceof JqNumber n && other instanceof JqNumber m) {
            if (n.isIntegral() && m.isIntegral()) {
                return JqNumber.of(Math.multiplyExact(n.longValue(), m.longValue()));
            }
            return JqNumber.of(n.decimalValue().multiply(m.decimalValue()));
        }
        if (this instanceof JqObject o1 && other instanceof JqObject o2) {
            var map = new java.util.LinkedHashMap<>(o1.objectValue());
            for (var entry : o2.objectValue().entrySet()) {
                JqValue existing = map.get(entry.getKey());
                if (existing instanceof JqObject && entry.getValue() instanceof JqObject) {
                    map.put(entry.getKey(), existing.multiply(entry.getValue()));
                } else {
                    map.put(entry.getKey(), entry.getValue());
                }
            }
            return JqObject.ofTrusted(map);
        }
        if (this instanceof JqString s && other instanceof JqNumber n) {
            // "ab" * 3 = "ababab"
            int count = (int) n.longValue();
            if (count <= 0) return JqNull.NULL;
            long resultLen = (long) s.stringValue().length() * count;
            if (resultLen > 100_000_000) {
                throw new JqTypeError("Repeat string result too long");
            }
            return JqString.of(s.stringValue().repeat(count));
        }
        if (this instanceof JqString s && other instanceof JqObject) {
            throw new JqTypeError("Cannot multiply string and object");
        }
        throw new JqTypeError("Cannot multiply " + type() + " and " + other.type());
    }

    default JqValue divide(JqValue other) {
        if (this instanceof JqNumber n && other instanceof JqNumber m) {
            if (m.isIntegral() && m.longValue() == 0) {
                throw new JqTypeError("number (" + n.toJsonString() + ") and number (0) cannot be divided because the divisor is zero");
            }
            return JqNumber.of(n.decimalValue().divide(m.decimalValue(), java.math.MathContext.DECIMAL128));
        }
        if (this instanceof JqString s && other instanceof JqString sep) {
            var parts = s.stringValue().split(java.util.regex.Pattern.quote(sep.stringValue()), -1);
            var list = new java.util.ArrayList<JqValue>(parts.length);
            for (String p : parts) list.add(JqString.of(p));
            return JqArray.of(list);
        }
        throw new JqTypeError("Cannot divide " + type() + " by " + other.type());
    }

    default JqValue modulo(JqValue other) {
        if (this instanceof JqNumber n && other instanceof JqNumber m) {
            if (n.isIntegral() && m.isIntegral()) {
                return JqNumber.of(n.longValue() % m.longValue());
            }
            return JqNumber.of(n.decimalValue().remainder(m.decimalValue()));
        }
        throw new JqTypeError("Cannot modulo " + type() + " by " + other.type());
    }

    default JqValue negate() {
        if (this instanceof JqNumber n) {
            if (n.isIntegral()) return JqNumber.of(-n.longValue());
            return JqNumber.of(n.decimalValue().negate());
        }
        throw new JqTypeError("Cannot negate " + type());
    }

    @Override
    default int compareTo(JqValue other) {
        if (this.type() != other.type()) {
            return this.type().ordinal() - other.type().ordinal();
        }
        return switch (this) {
            case JqNull ignored -> 0;
            case JqBoolean b -> Boolean.compare(b.booleanValue(), other.booleanValue());
            case JqNumber n -> {
                if (n.isIntegral() && other instanceof JqNumber m && m.isIntegral()) {
                    yield Long.compare(n.longValue(), m.longValue());
                }
                yield n.decimalValue().compareTo(other.decimalValue());
            }
            case JqString s -> s.stringValue().compareTo(other.stringValue());
            case JqArray a -> {
                var otherArr = other.arrayValue();
                var thisArr = a.arrayValue();
                for (int i = 0; i < Math.min(thisArr.size(), otherArr.size()); i++) {
                    int cmp = thisArr.get(i).compareTo(otherArr.get(i));
                    if (cmp != 0) yield cmp;
                }
                yield Integer.compare(thisArr.size(), otherArr.size());
            }
            case JqObject ignored -> 0; // jq: objects compare by length then keys
        };
    }

    String toJsonString();
}

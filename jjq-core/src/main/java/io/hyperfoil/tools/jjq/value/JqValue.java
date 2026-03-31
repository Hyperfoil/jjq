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
        if (other instanceof JqNull) return this;
        return switch (this) {
            case JqNull ignored -> other;
            case JqNumber n when other instanceof JqNumber m -> {
                if (n.isIntegral() && m.isIntegral()) {
                    yield JqNumber.of(Math.addExact(n.longValue(), m.longValue()));
                }
                if (n.isNaN() || n.isInfinite() || m.isNaN() || m.isInfinite()) {
                    yield JqNumber.of(n.doubleValue() + m.doubleValue());
                }
                yield JqNumber.of(n.decimalValue().add(m.decimalValue()));
            }
            case JqNumber ignored -> throw new JqTypeError(type().jqName() + " (" + truncateForError(toJsonString()) + ") and "
                    + other.type().jqName() + " (" + truncateForError(other.toJsonString()) + ") cannot be added");
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
            default -> throw new JqTypeError(type().jqName() + " (" + truncateForError(toJsonString()) + ") and "
                    + other.type().jqName() + " (" + truncateForError(other.toJsonString()) + ") cannot be added");
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
                if (n.isNaN() || n.isInfinite() || m.isNaN() || m.isInfinite()) {
                    yield JqNumber.of(n.doubleValue() - m.doubleValue());
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
            default -> throw new JqTypeError(type().jqName() + " (" + truncateForError(toJsonString()) + ") and "
                    + other.type().jqName() + " (" + truncateForError(other.toJsonString()) + ") cannot be subtracted");
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
            if (n.isNaN() || n.isInfinite() || m.isNaN() || m.isInfinite()) {
                return JqNumber.of(n.doubleValue() * m.doubleValue());
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
            return repeatString(s, n);
        }
        if (this instanceof JqNumber n && other instanceof JqString s) {
            return repeatString(s, n);
        }
        if (this instanceof JqString s && other instanceof JqObject) {
            throw new JqTypeError("Cannot multiply string and object");
        }
        throw new JqTypeError("Cannot multiply " + type() + " and " + other.type());
    }

    private static JqValue repeatString(JqString s, JqNumber n) {
        double d = n.doubleValue();
        if (Double.isNaN(d)) return JqNull.NULL;
        int count = (int) Math.floor(d);
        if (count < 0) return JqNull.NULL;
        if (count == 0) return JqString.of("");
        long resultLen = (long) s.stringValue().length() * count;
        if (resultLen > 100_000_000) {
            throw new JqTypeError("Repeat string result too long");
        }
        return JqString.of(s.stringValue().repeat(count));
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
            double a = n.doubleValue();
            double b = m.doubleValue();
            // Handle nan/infinite with IEEE 754 semantics
            if (Double.isNaN(a) || Double.isNaN(b)) {
                return JqNumber.of(Double.NaN);
            }
            if (b == 0.0) {
                throw new JqTypeError("number (" + n.toJsonString() + ") and number (" + m.toJsonString()
                        + ") cannot be divided (remainder) because the divisor is zero");
            }
            if (Double.isInfinite(a) && Double.isInfinite(b)) {
                // jq converts to long long: inf→LLONG_MAX, -inf→LLONG_MIN, then does integer %
                long la = a > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
                long lb = b > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
                return JqNumber.of(la % lb);
            }
            if (Double.isInfinite(a)) {
                // inf % finite = 0 in jq (converts inf to LLONG_MAX/MIN, % finite ≈ 0)
                return JqNumber.of(0);
            }
            if (Double.isInfinite(b)) {
                // finite % inf = the finite value in jq
                return JqNumber.of(a % b);
            }
            if (n.isIntegral() && m.isIntegral()) {
                return JqNumber.of(n.longValue() % m.longValue());
            }
            return JqNumber.of(a % b);
        }
        throw new JqTypeError("Cannot modulo " + type() + " by " + other.type());
    }

    default JqValue negate() {
        if (this instanceof JqNumber n) {
            if (n.isNaN() || n.isInfinite()) return JqNumber.of(-n.doubleValue());
            return JqNumber.of(n.decimalValue().negate());
        }
        throw new JqTypeError(type().jqName() + " (" + truncateForError(toJsonString()) + ") cannot be negated");
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
                JqNumber m = (JqNumber) other;
                if (n.isNaN() || n.isInfinite() || m.isNaN() || m.isInfinite()) {
                    yield Double.compare(n.doubleValue(), m.doubleValue());
                }
                // Fast path: both backed by long
                if (n.isLongBacked() && m.isLongBacked()) {
                    yield Long.compare(n.longValue(), m.longValue());
                }
                yield n.decimalValue().compareTo(m.decimalValue());
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
            case JqObject obj -> {
                var thisMap = obj.objectValue();
                var otherMap = ((JqObject) other).objectValue();
                // Compare by number of keys first
                int sizeCmp = Integer.compare(thisMap.size(), otherMap.size());
                if (sizeCmp != 0) yield sizeCmp;
                // Then by sorted keys
                var thisKeys = thisMap.keySet().stream().sorted().toList();
                var otherKeys = otherMap.keySet().stream().sorted().toList();
                for (int i = 0; i < thisKeys.size(); i++) {
                    int keyCmp = thisKeys.get(i).compareTo(otherKeys.get(i));
                    if (keyCmp != 0) yield keyCmp;
                }
                // Then by values in sorted key order
                for (int i = 0; i < thisKeys.size(); i++) {
                    int valCmp = thisMap.get(thisKeys.get(i)).compareTo(otherMap.get(otherKeys.get(i)));
                    if (valCmp != 0) yield valCmp;
                }
                yield 0;
            }
        };
    }

    String toJsonString();

    /** Truncate value string for error messages (jq uses 25 chars + "...") */
    private static String truncateForError(String jsonStr) {
        // jq truncation: if the full JSON representation exceeds 30 UTF-8 bytes,
        // truncate content to fit in 24 UTF-8 bytes and append "..." (or "...\"" for strings).
        byte[] fullBytes = jsonStr.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (fullBytes.length < 30) return jsonStr;

        if (jsonStr.startsWith("\"") && jsonStr.endsWith("\"")) {
            // String value — truncate inner content to fit in 24 UTF-8 bytes
            String inner = jsonStr.substring(1, jsonStr.length() - 1);
            // Build truncated content codepoint by codepoint
            var sb = new StringBuilder();
            int byteCount = 0;
            for (int i = 0; i < inner.length(); ) {
                int cp = inner.codePointAt(i);
                int cpBytes = Character.toString(cp).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if (byteCount + cpBytes > 24) break;
                sb.appendCodePoint(cp);
                byteCount += cpBytes;
                i += Character.charCount(cp);
            }
            return "\"" + sb + "...\"";
        }
        // Non-string: truncate to fit in 26 UTF-8 bytes (26 + "..." = 29 total)
        var sb = new StringBuilder();
        int byteCount = 0;
        for (int i = 0; i < jsonStr.length(); ) {
            int cp = jsonStr.codePointAt(i);
            int cpBytes = Character.toString(cp).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (byteCount + cpBytes > 26) break;
            sb.appendCodePoint(cp);
            byteCount += cpBytes;
            i += Character.charCount(cp);
        }
        return sb + "...";
    }
}

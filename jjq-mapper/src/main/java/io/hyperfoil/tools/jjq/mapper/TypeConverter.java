package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.value.*;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.*;

/**
 * Converts between JqValue and Java types. Uses a pre-resolved {@link Kind}
 * enum to avoid if/else dispatch chains and lambda allocations on the hot path.
 *
 * <p>The conversion kind is resolved once per field at class introspection time
 * via {@link #resolveKind(Class, Type)}. The actual conversion is done via a
 * single {@code switch} in {@link #convert(JqValue, Kind, Class, Type, JqMapper)}.</p>
 */
final class TypeConverter {

    /** Pre-resolved conversion strategy — one enum constant per target type category. */
    enum Kind {
        STRING, INT, LONG, DOUBLE, FLOAT, BOOLEAN, SHORT, BYTE, CHAR,
        BIG_DECIMAL, OPTIONAL, LIST, MAP, ENUM, RECORD, JQ_VALUE, DEFAULT
    }

    private TypeConverter() {}

    /**
     * Resolve the conversion kind for a target type at introspection time.
     * This replaces the if/else chain in the old toJava() with a single
     * enum constant that can be dispatched via switch.
     */
    static Kind resolveKind(Class<?> targetType, Type genericType) {
        if (targetType == Optional.class) return Kind.OPTIONAL;
        if (JqValue.class.isAssignableFrom(targetType)) return Kind.JQ_VALUE;
        if (targetType == String.class) return Kind.STRING;
        if (targetType == int.class || targetType == Integer.class) return Kind.INT;
        if (targetType == long.class || targetType == Long.class) return Kind.LONG;
        if (targetType == double.class || targetType == Double.class) return Kind.DOUBLE;
        if (targetType == boolean.class || targetType == Boolean.class) return Kind.BOOLEAN;
        if (targetType == float.class || targetType == Float.class) return Kind.FLOAT;
        if (targetType == short.class || targetType == Short.class) return Kind.SHORT;
        if (targetType == byte.class || targetType == Byte.class) return Kind.BYTE;
        if (targetType == char.class || targetType == Character.class) return Kind.CHAR;
        if (targetType == BigDecimal.class) return Kind.BIG_DECIMAL;
        if (targetType == List.class || targetType == ArrayList.class) return Kind.LIST;
        if (targetType == Map.class || targetType == LinkedHashMap.class || targetType == HashMap.class) return Kind.MAP;
        if (targetType.isEnum()) return Kind.ENUM;
        if (targetType.isRecord()) return Kind.RECORD;
        return Kind.DEFAULT;
    }

    /**
     * Convert a JqValue to the target Java type using the pre-resolved kind.
     * Single switch dispatch — no if/else chains, no lambda classes.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object convert(JqValue value, Kind kind, Class<?> targetType, Type genericType, JqMapper mapper) {
        return switch (kind) {
            case OPTIONAL -> {
                if (value == null || value instanceof JqNull) yield Optional.empty();
                Type elementType = extractTypeArgument(genericType, 0);
                Class<?> elementClass = rawClass(elementType);
                Kind innerKind = resolveKind(elementClass, elementType);
                yield Optional.ofNullable(convert(value, innerKind, elementClass, elementType, mapper));
            }
            case JQ_VALUE -> (value == null || value instanceof JqNull) ? defaultValue(targetType) : value;
            case STRING -> {
                if (value == null || value instanceof JqNull) yield null;
                yield value instanceof JqString s ? s.stringValue() : value.toJsonString();
            }
            case INT -> value instanceof JqNumber n ? n.intValue() : 0;
            case LONG -> value instanceof JqNumber n ? n.longValue() : 0L;
            case DOUBLE -> value instanceof JqNumber n ? n.doubleValue() : 0.0;
            case FLOAT -> value instanceof JqNumber n ? (float) n.doubleValue() : 0.0f;
            case BOOLEAN -> value instanceof JqBoolean b ? b.booleanValue() : (value != null && value.isTruthy());
            case SHORT -> value instanceof JqNumber n ? (short) n.intValue() : (short) 0;
            case BYTE -> value instanceof JqNumber n ? (byte) n.intValue() : (byte) 0;
            case CHAR -> {
                if (value instanceof JqString s && !s.stringValue().isEmpty()) yield s.stringValue().charAt(0);
                yield '\0';
            }
            case BIG_DECIMAL -> {
                if (value == null || value instanceof JqNull) yield null;
                yield value instanceof JqNumber n ? n.decimalValue() : BigDecimal.ZERO;
            }
            case LIST -> {
                if (value == null || value instanceof JqNull || !(value instanceof JqArray arr)) yield List.of();
                Type elementType = extractTypeArgument(genericType, 0);
                Class<?> elementClass = rawClass(elementType);
                Kind innerKind = resolveKind(elementClass, elementType);
                var list = new ArrayList<>(arr.size());
                for (JqValue elem : arr) {
                    list.add(convert(elem, innerKind, elementClass, elementType, mapper));
                }
                yield list;
            }
            case MAP -> {
                if (value == null || value instanceof JqNull || !(value instanceof JqObject obj)) yield Map.of();
                Type valueType = extractTypeArgument(genericType, 1);
                Class<?> valueClass = rawClass(valueType);
                Kind innerKind = resolveKind(valueClass, valueType);
                var map = new LinkedHashMap<String, Object>();
                obj.forEach((k, v) -> map.put(k, convert(v, innerKind, valueClass, valueType, mapper)));
                yield map;
            }
            case ENUM -> {
                if (value == null || value instanceof JqNull) yield null;
                if (value instanceof JqString s) yield Enum.valueOf((Class<? extends Enum>) targetType, s.stringValue());
                yield null;
            }
            case RECORD -> {
                if (value == null || value instanceof JqNull) yield null;
                yield mapper.fromJqValue(value, targetType);
            }
            case DEFAULT -> toJava(value, targetType, genericType, mapper);
        };
    }

    /**
     * Convert a JqValue to the target Java type (full dispatch — fallback path).
     */
    @SuppressWarnings("unchecked")
    static Object toJava(JqValue value, Class<?> targetType, Type genericType, JqMapper mapper) {
        Kind kind = resolveKind(targetType, genericType);
        if (kind != Kind.DEFAULT) {
            return convert(value, kind, targetType, genericType, mapper);
        }
        // True fallback — unknown type
        if (value == null || value instanceof JqNull) return defaultValue(targetType);
        return value.toJavaObject();
    }

    /**
     * Convert a Java value to a JqValue (serialization direction).
     */
    static JqValue toJqValue(Object value, JqMapper mapper) {
        if (value == null) return JqNull.NULL;
        if (value instanceof JqValue jv) return jv;
        if (value instanceof String s) return JqString.of(s);
        if (value instanceof Number n) return JqNumber.of(n);
        if (value instanceof Boolean b) return JqBoolean.of(b);
        if (value instanceof Optional<?> opt) {
            return opt.map(v -> toJqValue(v, mapper)).orElse(JqNull.NULL);
        }
        if (value instanceof List<?> list) {
            JqValue[] elements = new JqValue[list.size()];
            for (int i = 0; i < list.size(); i++) {
                elements[i] = toJqValue(list.get(i), mapper);
            }
            return JqArray.of(elements);
        }
        if (value instanceof Map<?, ?> map) {
            var builder = JqObject.builder(map.size());
            for (var entry : map.entrySet()) {
                builder.put(String.valueOf(entry.getKey()), toJqValue(entry.getValue(), mapper));
            }
            return builder.build();
        }
        if (value.getClass().isEnum()) {
            return JqString.of(((Enum<?>) value).name());
        }
        if (value.getClass().isRecord()) {
            return mapper.toJqValue(value);
        }
        return JqValues.fromJavaObject(value);
    }

    /** Return the Java default value for a type. */
    static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        if (type == boolean.class) return false;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return '\0';
        return null;
    }

    /** Extract the i-th type argument from a ParameterizedType. */
    private static Type extractTypeArgument(Type genericType, int index) {
        if (genericType instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length > index) return args[index];
        }
        return Object.class;
    }

    /** Get the raw Class from a Type. */
    static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> c) return c;
        if (type instanceof ParameterizedType pt) return (Class<?>) pt.getRawType();
        return Object.class;
    }
}

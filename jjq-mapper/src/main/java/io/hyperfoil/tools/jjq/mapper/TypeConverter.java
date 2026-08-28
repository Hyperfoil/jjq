package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.value.*;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.*;

/**
 * Converts between JqValue and Java types. Handles primitives, wrappers,
 * String, BigDecimal, collections (List, Map), Optional, enums, JqValue
 * passthrough, and recursive record/POJO mapping via the parent JqMapper.
 */
final class TypeConverter {

    private TypeConverter() {}

    /**
     * Convert a JqValue to the target Java type.
     *
     * @param value      the JqValue to convert (may be JqNull)
     * @param targetType the target Java class
     * @param genericType the generic type (for List/Map/Optional parameterization)
     * @param mapper     the parent mapper (for recursive record mapping)
     * @return the converted Java value, or null/default for JqNull
     */
    @SuppressWarnings("unchecked")
    static Object toJava(JqValue value, Class<?> targetType, Type genericType, JqMapper mapper) {
        // Optional<T> — must be checked before the null handling because
        // JqNull should map to Optional.empty(), not null.
        if (targetType == Optional.class) {
            if (value == null || value instanceof JqNull) {
                return Optional.empty();
            }
            Type elementType = extractTypeArgument(genericType, 0);
            Class<?> elementClass = rawClass(elementType);
            Object converted = toJava(value, elementClass, elementType, mapper);
            return Optional.ofNullable(converted);
        }

        // Null handling
        if (value == null || value instanceof JqNull) {
            return defaultValue(targetType);
        }

        // JqValue passthrough — field declared as JqValue or a subtype
        if (JqValue.class.isAssignableFrom(targetType)) {
            return value;
        }

        // String
        if (targetType == String.class) {
            if (value instanceof JqString s) return s.stringValue();
            // Coerce non-strings to their JSON representation
            return value.toJsonString();
        }

        // Primitives and wrappers
        if (targetType == int.class || targetType == Integer.class) {
            return value instanceof JqNumber n ? n.intValue() : 0;
        }
        if (targetType == long.class || targetType == Long.class) {
            return value instanceof JqNumber n ? n.longValue() : 0L;
        }
        if (targetType == double.class || targetType == Double.class) {
            return value instanceof JqNumber n ? n.doubleValue() : 0.0;
        }
        if (targetType == float.class || targetType == Float.class) {
            return value instanceof JqNumber n ? (float) n.doubleValue() : 0.0f;
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return value instanceof JqBoolean b ? b.booleanValue() : value.isTruthy();
        }
        if (targetType == short.class || targetType == Short.class) {
            return value instanceof JqNumber n ? (short) n.intValue() : (short) 0;
        }
        if (targetType == byte.class || targetType == Byte.class) {
            return value instanceof JqNumber n ? (byte) n.intValue() : (byte) 0;
        }
        if (targetType == char.class || targetType == Character.class) {
            if (value instanceof JqString s && !s.stringValue().isEmpty()) {
                return s.stringValue().charAt(0);
            }
            return '\0';
        }

        // BigDecimal
        if (targetType == BigDecimal.class) {
            return value instanceof JqNumber n ? n.decimalValue() : BigDecimal.ZERO;
        }

        // List<T>
        if (targetType == List.class || targetType == ArrayList.class) {
            if (!(value instanceof JqArray arr)) return List.of();
            Type elementType = extractTypeArgument(genericType, 0);
            Class<?> elementClass = rawClass(elementType);
            var list = new ArrayList<>(arr.size());
            for (JqValue elem : arr) {
                list.add(toJava(elem, elementClass, elementType, mapper));
            }
            return list;
        }

        // Map<String, V>
        if (targetType == Map.class || targetType == LinkedHashMap.class || targetType == HashMap.class) {
            if (!(value instanceof JqObject obj)) return Map.of();
            Type valueType = extractTypeArgument(genericType, 1);
            Class<?> valueClass = rawClass(valueType);
            var map = new LinkedHashMap<String, Object>();
            obj.forEach((k, v) -> map.put(k, toJava(v, valueClass, valueType, mapper)));
            return map;
        }

        // Enum
        if (targetType.isEnum()) {
            if (value instanceof JqString s) {
                @SuppressWarnings("rawtypes")
                Class<? extends Enum> enumType = (Class<? extends Enum>) targetType;
                return Enum.valueOf(enumType, s.stringValue());
            }
            return null;
        }

        // Record — recursive mapping
        if (targetType.isRecord() && value instanceof JqObject) {
            return mapper.fromJqValue(value, targetType);
        }

        // Fallback — try toJavaObject() and hope the types align
        return value.toJavaObject();
    }

    /**
     * Convert a Java value to a JqValue.
     *
     * @param value  the Java value (may be null)
     * @param mapper the parent mapper (for recursive record serialization)
     * @return the JqValue representation
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
        // Fallback
        return JqValues.fromJavaObject(value);
    }

    /** Return the Java default value for a type (null for reference, 0 for int, etc.). */
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

    /** Get the raw Class<?> from a Type. */
    static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> c) return c;
        if (type instanceof ParameterizedType pt) return (Class<?>) pt.getRawType();
        return Object.class;
    }
}

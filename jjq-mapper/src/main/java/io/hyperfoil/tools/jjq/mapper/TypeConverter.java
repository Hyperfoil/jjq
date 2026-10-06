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
public final class TypeConverter {

    /** Pre-resolved conversion strategy -- one enum constant per target type category. */
    public enum Kind {
        /** Target is {@code String}. */
        STRING,
        /** Target is {@code int} or {@code Integer}. */
        INT,
        /** Target is {@code long} or {@code Long}. */
        LONG,
        /** Target is {@code double} or {@code Double}. */
        DOUBLE,
        /** Target is {@code float} or {@code Float}. */
        FLOAT,
        /** Target is {@code boolean} or {@code Boolean}. */
        BOOLEAN,
        /** Target is {@code short} or {@code Short}. */
        SHORT,
        /** Target is {@code byte} or {@code Byte}. */
        BYTE,
        /** Target is {@code char} or {@code Character}. */
        CHAR,
        /** Target is {@link java.math.BigDecimal}. */
        BIG_DECIMAL,
        /** Target is {@link java.util.Optional}. */
        OPTIONAL,
        /** Target is {@link java.util.List}. */
        LIST,
        /** Target is {@link java.util.Map}. */
        MAP,
        /** Target is an {@code enum} type. */
        ENUM,
        /** Target is a record or {@code @JqMapped} POJO. */
        RECORD,
        /** Target is a {@link io.hyperfoil.tools.jjq.value.JqValue} subtype. */
        JQ_VALUE,
        /** Fallback for unrecognized types. */
        DEFAULT
    }

    private TypeConverter() {}

    /**
     * Resolve the conversion kind for a target type at introspection time.
     * This replaces the if/else chain in the old toJava() with a single
     * enum constant that can be dispatched via switch.
     *
     * @param targetType  the raw target class
     * @param genericType the generic type (may carry type arguments)
     * @return the resolved conversion kind
     */
    public static Kind resolveKind(Class<?> targetType, Type genericType) {
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
        if (targetType.isAnnotationPresent(JqMapped.class)) return Kind.RECORD; // POJOs with @JqMapped use the mapper
        return Kind.DEFAULT;
    }

    /**
     * Convert a JqValue to the target Java type using the pre-resolved kind.
     * Single switch dispatch -- no if/else chains, no lambda classes.
     *
     * @param value       the JqValue to convert (may be null)
     * @param kind        the pre-resolved conversion kind
     * @param targetType  the raw target class
     * @param genericType the generic type (may carry type arguments)
     * @param mapper      the mapper for nested record conversions
     * @return the converted Java value
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object convert(JqValue value, Kind kind, Class<?> targetType, Type genericType, JqMapper mapper) {
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
                if (value.isContainer()) throw mismatch("String", value);
                yield value instanceof JqString s ? s.stringValue() : value.toJsonString();
            }
            case INT -> {
                if (value != null && value.isContainer()) throw mismatch("int", value);
                yield value instanceof JqNumber n ? n.intValue() : 0;
            }
            case LONG -> {
                if (value != null && value.isContainer()) throw mismatch("long", value);
                yield value instanceof JqNumber n ? n.longValue() : 0L;
            }
            case DOUBLE -> {
                if (value != null && value.isContainer()) throw mismatch("double", value);
                yield value instanceof JqNumber n ? n.doubleValue() : 0.0;
            }
            case FLOAT -> {
                if (value != null && value.isContainer()) throw mismatch("float", value);
                yield value instanceof JqNumber n ? (float) n.doubleValue() : 0.0f;
            }
            case BOOLEAN -> {
                if (value != null && value.isContainer()) throw mismatch("boolean", value);
                yield value instanceof JqBoolean b ? b.booleanValue() : (value != null && value.isTruthy());
            }
            case SHORT -> {
                if (value != null && value.isContainer()) throw mismatch("short", value);
                yield value instanceof JqNumber n ? (short) n.intValue() : (short) 0;
            }
            case BYTE -> {
                if (value != null && value.isContainer()) throw mismatch("byte", value);
                yield value instanceof JqNumber n ? (byte) n.intValue() : (byte) 0;
            }
            case CHAR -> {
                if (value != null && value.isContainer()) throw mismatch("char", value);
                if (value instanceof JqString s && !s.stringValue().isEmpty()) yield s.stringValue().charAt(0);
                yield '\0';
            }
            case BIG_DECIMAL -> {
                if (value == null || value instanceof JqNull) yield null;
                if (value.isContainer()) throw mismatch("BigDecimal", value);
                yield value instanceof JqNumber n ? n.decimalValue() : BigDecimal.ZERO;
            }
            case LIST -> {
                if (value == null || value instanceof JqNull) yield List.of();
                if (!(value instanceof JqArray arr)) {
                    throw mismatch("array for " + listTarget(targetType, genericType), value);
                }
                Type elementType = extractTypeArgument(genericType, 0);
                Class<?> elementClass = rawClass(elementType);
                Kind innerKind = resolveKind(elementClass, elementType);
                var list = new ArrayList<>(arr.size());
                for (int i = 0; i < arr.size(); i++) {
                    try {
                        list.add(convert(arr.get(i), innerKind, elementClass, elementType, mapper));
                    } catch (JqMapperException e) {
                        throw e.prependPath(i);
                    }
                }
                yield list;
            }
            case MAP -> {
                if (value == null || value instanceof JqNull) yield Map.of();
                if (!(value instanceof JqObject obj)) {
                    throw mismatch("object for " + mapTarget(targetType, genericType), value);
                }
                Type valueType = extractTypeArgument(genericType, 1);
                Class<?> valueClass = rawClass(valueType);
                Kind innerKind = resolveKind(valueClass, valueType);
                int n = obj.size();
                var map = new LinkedHashMap<String, Object>(n * 4 / 3 + 1);
                for (int i = 0; i < n; i++) {
                    try {
                        map.put(obj.keyAt(i), convert(obj.valueAt(i), innerKind, valueClass, valueType, mapper));
                    } catch (JqMapperException e) {
                        throw e.prependPath(obj.keyAt(i));
                    }
                }
                yield map;
            }
            case ENUM -> {
                if (value == null || value instanceof JqNull) yield null;
                if (value instanceof JqString s) yield Enum.valueOf((Class<? extends Enum>) targetType, s.stringValue());
                if (value.isContainer()) throw mismatch(targetType.getSimpleName(), value);
                yield null;
            }
            case RECORD -> {
                if (value == null || value instanceof JqNull) yield null;
                if (!(value instanceof JqObject)) {
                    throw mismatch("object for " + targetType.getSimpleName(), value);
                }
                yield mapper.fromJqValue(value, targetType);
            }
            case DEFAULT -> toJava(value, targetType, genericType, mapper);
        };
    }

    /**
     * Convert a JqValue to the target Java type (full dispatch -- fallback path).
     *
     * @param value       the JqValue to convert (may be null)
     * @param targetType  the raw target class
     * @param genericType the generic type (may carry type arguments)
     * @param mapper      the mapper for nested record conversions
     * @return the converted Java value
     */
    @SuppressWarnings("unchecked")
    public static Object toJava(JqValue value, Class<?> targetType, Type genericType, JqMapper mapper) {
        Kind kind = resolveKind(targetType, genericType);
        if (kind != Kind.DEFAULT) {
            return convert(value, kind, targetType, genericType, mapper);
        }
        // True fallback — unknown type
        if (value == null || value instanceof JqNull) return defaultValue(targetType);
        if (targetType == Object.class) return value.toJavaObject();
        if (value instanceof JqObject) {
            // Structural bind for plain POJOs (Jackson parity). Still fails loudly
            // for unsuitable classes (no no-arg constructor, JDK internals, ...).
            if (isPojoLike(targetType)) return mapper.fromJqValue(value, targetType);
            return value.toJavaObject();
        }
        if (isPojoLike(targetType)) {
            throw mismatch("object for " + targetType.getSimpleName(), value);
        }
        return value.toJavaObject();
    }

    /**
     * True for concrete POJO-like classes: not an interface, array, enum, or
     * abstract class, and not a type with dedicated conversion semantics
     * (Object, collections, maps, scalars, JqValue, Optional).
     */
    private static boolean isPojoLike(Class<?> targetType) {
        if (targetType.isInterface() || targetType.isArray() || targetType.isEnum()
                || targetType.isPrimitive() || targetType == Object.class) return false;
        if (java.lang.reflect.Modifier.isAbstract(targetType.getModifiers())) return false;
        if (JqValue.class.isAssignableFrom(targetType)) return false;
        if (java.util.Collection.class.isAssignableFrom(targetType)
                || java.util.Map.class.isAssignableFrom(targetType)) return false;
        if (targetType == java.util.Optional.class) return false;
        if (Number.class.isAssignableFrom(targetType) || targetType == Boolean.class
                || targetType == Character.class || targetType == String.class) return false;
        return true;
    }

    /**
     * Build a shape-mismatch error: what was found vs what was expected.
     * The failure path is prepended while unwinding through nested structures.
     */
    static JqMapperException mismatch(String expected, JqValue actual) {
        return new JqMapperException("Cannot bind " + describe(actual) + " to " + expected);
    }

    /** Short human-readable description of a JqValue (strings truncated). */
    private static String describe(JqValue value) {
        if (value == null || value instanceof JqNull) return "null";
        if (value instanceof JqString s) {
            String text = s.stringValue();
            if (text.length() > 40) text = text.substring(0, 37) + "...";
            return "String \"" + text + "\"";
        }
        if (value instanceof JqNumber) return "number";
        if (value instanceof JqBoolean) return "boolean";
        if (value instanceof JqArray) return "array";
        if (value instanceof JqObject) return "object";
        return value.getClass().getSimpleName();
    }

    /** Target description for List mismatches, e.g. {@code List<ToolRef>}. */
    private static String listTarget(Class<?> targetType, Type genericType) {
        Type elementType = extractTypeArgument(genericType, 0);
        return "List<" + rawClass(elementType).getSimpleName() + ">";
    }

    /** Target description for Map mismatches, e.g. {@code Map<String, Integer>}. */
    private static String mapTarget(Class<?> targetType, Type genericType) {
        Type valueType = extractTypeArgument(genericType, 1);
        return "Map<String, " + rawClass(valueType).getSimpleName() + ">";
    }

    /**
     * Convert a Java value to a JqValue (serialization direction).
     * Public so that generated mapping classes can call this for complex types
     * (List, Map, Optional, Enum, nested records).
     *
     * @param value  the Java value to convert (may be null)
     * @param mapper the mapper for nested record conversions
     * @return the corresponding JqValue
     */
    public static JqValue toJqValue(Object value, JqMapper mapper) {
        if (value == null) return JqNull.NULL;
        if (value instanceof JqValue jv) return jv;
        if (value instanceof String s) return JqString.of(s);
        if (value instanceof Number n) return JqNumber.of(n);
        if (value instanceof Boolean b) return JqBoolean.of(b);
        if (value instanceof Character c) return JqString.of(String.valueOf(c));
        if (value instanceof Optional<?> opt) {
            return opt.map(v -> toJqValue(v, mapper)).orElse(JqNull.NULL);
        }
        if (value instanceof List<?> list) {
            JqValue[] elements = new JqValue[list.size()];
            for (int i = 0; i < list.size(); i++) {
                elements[i] = toJqValue(list.get(i), mapper);
            }
            return JqArray.ofTrusted(elements);
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
        if (value.getClass().isAnnotationPresent(JqMapped.class)) {
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

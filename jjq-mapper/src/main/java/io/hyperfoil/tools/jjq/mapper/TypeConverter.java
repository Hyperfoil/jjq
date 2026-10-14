package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.value.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
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
        /** Target is {@link java.math.BigInteger}. */
        BIG_INTEGER,
        /** Target is {@link java.util.UUID} (string wire form). */
        UUID,
        /** Target is {@link java.net.URI} (string wire form). */
        URI,
        /** Target is {@link java.net.URL} (string wire form, absolute only). */
        URL,
        /** Target is {@link java.util.Optional}. */
        OPTIONAL,
        /** Target is {@link java.util.List}. */
        LIST,
        /** Target is {@link java.util.Set} (incl. sorted and enum sets). */
        SET,
        /** Target is {@link java.util.Map}. */
        MAP,
        /** Target is a Java array (reference or primitive component). */
        ARRAY,
        /** Target is {@link java.time.Instant} (ISO string, or epoch seconds). */
        INSTANT,
        /** Target is {@link java.time.LocalDate} (ISO string). */
        LOCAL_DATE,
        /** Target is {@link java.time.LocalTime} (ISO string). */
        LOCAL_TIME,
        /** Target is {@link java.time.LocalDateTime} (ISO string). */
        LOCAL_DATE_TIME,
        /** Target is {@link java.time.OffsetDateTime} (ISO string, or epoch seconds as UTC). */
        OFFSET_DATE_TIME,
        /** Target is {@link java.time.ZonedDateTime} (ISO string, or epoch seconds as UTC). */
        ZONED_DATE_TIME,
        /** Target is {@link java.time.Duration} (ISO string, or seconds). */
        DURATION,
        /** Target is {@link java.time.Period} (ISO string). */
        PERIOD,
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
        if (targetType.isArray()) return Kind.ARRAY;
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
        if (targetType == java.math.BigInteger.class) return Kind.BIG_INTEGER;
        if (targetType == java.time.Instant.class) return Kind.INSTANT;
        if (targetType == java.time.LocalDate.class) return Kind.LOCAL_DATE;
        if (targetType == java.time.LocalTime.class) return Kind.LOCAL_TIME;
        if (targetType == java.time.LocalDateTime.class) return Kind.LOCAL_DATE_TIME;
        if (targetType == java.time.OffsetDateTime.class) return Kind.OFFSET_DATE_TIME;
        if (targetType == java.time.ZonedDateTime.class) return Kind.ZONED_DATE_TIME;
        if (targetType == java.time.Duration.class) return Kind.DURATION;
        if (targetType == java.time.Period.class) return Kind.PERIOD;
        if (targetType == java.util.UUID.class) return Kind.UUID;
        if (targetType == java.net.URI.class) return Kind.URI;
        if (targetType == java.net.URL.class) return Kind.URL;
        if (targetType == List.class || targetType == ArrayList.class
                || targetType == java.util.Collection.class || targetType == Iterable.class
                || List.class.isAssignableFrom(targetType)
                || java.util.Queue.class.isAssignableFrom(targetType)) return Kind.LIST;
        if (targetType == Set.class || targetType == HashSet.class
                || targetType == LinkedHashSet.class || targetType == SortedSet.class
                || targetType == TreeSet.class || targetType == EnumSet.class
                || targetType == java.util.NavigableSet.class
                || Set.class.isAssignableFrom(targetType)) return Kind.SET;
        if (targetType == Map.class || targetType == LinkedHashMap.class || targetType == HashMap.class
                || targetType == java.util.TreeMap.class || targetType == java.util.SortedMap.class
                || targetType == java.util.NavigableMap.class
                || Map.class.isAssignableFrom(targetType)) return Kind.MAP;
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
                if (value.isContainer()) throw mismatch(JqValue.Type.STRING, "String", value);
                String source = value.sourceText();
                if (source != null) yield source;
                yield value instanceof JqString s ? s.stringValue() : value.toJsonString();
            }
            case INT -> {
                yield coerceInt(value);
            }
            case LONG -> {
                yield coerceLong(value, "long");
            }
            case DOUBLE -> {
                yield coerceDouble(value, "double");
            }
            case FLOAT -> {
                yield (float) coerceDouble(value, "float");
            }
            case BOOLEAN -> {
                yield coerceBoolean(value);
            }
            case SHORT -> {
                yield coerceShort(value);
            }
            case BYTE -> {
                yield coerceByte(value);
            }
            case CHAR -> {
                yield coerceChar(value);
            }
            case BIG_DECIMAL -> {
                yield coerceBigDecimal(value);
            }
            case BIG_INTEGER -> {
                yield coerceBigInteger(value);
            }
            case UUID -> {
                yield coerceUuid(value);
            }
            case URI -> {
                yield coerceUri(value);
            }
            case URL -> {
                yield coerceUrl(value);
            }
            case INSTANT -> {
                yield coerceInstant(value);
            }
            case LOCAL_DATE -> {
                yield coerceLocalDate(value);
            }
            case LOCAL_TIME -> {
                yield coerceLocalTime(value);
            }
            case LOCAL_DATE_TIME -> {
                yield coerceLocalDateTime(value);
            }
            case OFFSET_DATE_TIME -> {
                yield coerceOffsetDateTime(value);
            }
            case ZONED_DATE_TIME -> {
                yield coerceZonedDateTime(value);
            }
            case DURATION -> {
                yield coerceDuration(value);
            }
            case PERIOD -> {
                yield coercePeriod(value);
            }
            case LIST -> {
                if (value == null || value instanceof JqNull) yield newListFor(targetType, 0);
                if (!(value instanceof JqArray arr)) {
                    throw mismatch(JqValue.Type.ARRAY, "array for " + listTarget(targetType, genericType), value);
                }
                Type elementType = extractTypeArgument(genericType, 0);
                Class<?> elementClass = rawClass(elementType);
                Kind innerKind = resolveKind(elementClass, elementType);
                java.util.Collection<Object> out = newListFor(targetType, arr.size());
                for (int i = 0; i < arr.size(); i++) {
                    try {
                        out.add(convert(arr.get(i), innerKind, elementClass, elementType, mapper));
                    } catch (JqMapperException e) {
                        throw e.prependPath(i);
                    }
                }
                yield out;
            }
            case SET -> {
                Type elementType = extractTypeArgument(genericType, 0);
                Class<?> elementClass = rawClass(elementType);
                if (value == null || value instanceof JqNull) yield emptySetFor(targetType, elementClass);
                if (!(value instanceof JqArray arr)) {
                    throw mismatch(JqValue.Type.ARRAY, "array for " + setTarget(targetType, genericType), value);
                }
                Kind innerKind = resolveKind(elementClass, elementType);
                java.util.Set<Object> set = newSetFor(targetType, elementClass, arr.size());
                for (int i = 0; i < arr.size(); i++) {
                    try {
                        set.add(convert(arr.get(i), innerKind, elementClass, elementType, mapper));
                    } catch (JqMapperException e) {
                        throw e.prependPath(i);
                    }
                }
                yield set;
            }
            case MAP -> {
                if (value == null || value instanceof JqNull) yield newMapFor(targetType, 0);
                if (!(value instanceof JqObject obj)) {
                    throw mismatch(JqValue.Type.OBJECT, "object for " + mapTarget(targetType, genericType), value);
                }
                Type valueType = extractTypeArgument(genericType, 1);
                Class<?> valueClass = rawClass(valueType);
                Kind innerKind = resolveKind(valueClass, valueType);
                int n = obj.size();
                Map<String, Object> map = newMapFor(targetType, n);
                for (int i = 0; i < n; i++) {
                    try {
                        map.put(obj.keyAt(i), convert(obj.valueAt(i), innerKind, valueClass, valueType, mapper));
                    } catch (JqMapperException e) {
                        throw e.prependPath(obj.keyAt(i));
                    }
                }
                yield map;
            }
            case ARRAY -> {
                // Null arrays stay null (Jackson default); missing keys bind
                // null through the record/POJO leniency around this call.
                if (value == null || value instanceof JqNull) yield null;
                if (!(value instanceof JqArray arr)) {
                    throw mismatch(JqValue.Type.ARRAY, "array for " + targetType.getSimpleName(), value);
                }
                Class<?> component = targetType.getComponentType();
                Kind innerKind = resolveKind(component, component);
                int n = arr.size();
                Object result = java.lang.reflect.Array.newInstance(component, n);
                for (int i = 0; i < n; i++) {
                    try {
                        java.lang.reflect.Array.set(result, i,
                                convert(arr.get(i), innerKind, component, component, mapper));
                    } catch (JqMapperException e) {
                        throw e.prependPath(i);
                    }
                }
                yield result;
            }
            case ENUM -> {
                if (value == null || value instanceof JqNull) yield null;
                java.util.Optional<EnumHandlers> handlers = mapper.enumHandlers(targetType);
                if (handlers.isPresent() && handlers.get().creator() != null) {
                    yield invokeCreator(handlers.get(), value, targetType, mapper);
                }
                if (value instanceof JqString s) {
                    EnumLeniency leniency =
                            handlers.isPresent() ? handlers.get().leniency() : null;
                    if (leniency != null) {
                        String constant = leniency.normalizedWire().get(
                                normalizeWire(s.stringValue(), leniency.normalizations()));
                        if (constant != null) {
                            yield Enum.valueOf((Class<? extends Enum>) targetType, constant);
                        }
                        if (leniency.onUnknown() == JqEnum.OnUnknown.NULL) {
                            // Exact constant-name fallback before giving up
                            try {
                                yield Enum.valueOf((Class<? extends Enum>) targetType, s.stringValue());
                            } catch (IllegalArgumentException e) {
                                yield null;
                            }
                        }
                    }
                    // Wire names first (issue #95.1), then constant names.
                    if (handlers.isPresent()) {
                        for (var entry : handlers.get().wireNames().entrySet()) {
                            if (entry.getValue().equals(s.stringValue())) {
                                yield Enum.valueOf((Class<? extends Enum>) targetType, entry.getKey());
                            }
                        }
                    }
                    yield Enum.valueOf((Class<? extends Enum>) targetType, s.stringValue());
                }
                if (value.isContainer()) throw mismatch(JqValue.Type.STRING, targetType.getSimpleName(), value);
                yield null;
            }
            case RECORD -> {
                if (value == null || value instanceof JqNull) yield null;
                if (!(value instanceof JqObject)) {
                    throw mismatch(JqValue.Type.OBJECT, "object for " + targetType.getSimpleName(), value);
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
        // Polymorphic abstract/interface targets dispatch on the discriminator
        // (heterogeneous lists via TypeToken); anything else keeps leniency.
        if (targetType.isInterface() || java.lang.reflect.Modifier.isAbstract(targetType.getModifiers())) {
            if (PolyMapping.resolvePolyConfig(targetType, mapper.bridges()) != null) {
                return mapper.fromJqValue(value, targetType);
            }
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
            throw mismatch(JqValue.Type.OBJECT, "object for " + targetType.getSimpleName(), value);
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
    static ShapeMismatchException mismatch(JqValue.Type expectedType, String expected, JqValue actual) {
        return new ShapeMismatchException(expectedType,
                actual == null ? JqValue.Type.NULL : actual.type(),
                "Cannot bind " + describe(actual) + " to " + expected);
    }

    /**
     * Jackson-compatible boolean coercion for scalar values: booleans pass
     * through, numbers follow the zero rule (0 is false), {@code "true"}/
     * {@code "false"} bind case-insensitively, anything else is a shape
     * mismatch; nulls stay false. Shared by {@link #convert} and the
     * generated-code helper so both paths agree.
     *
     * @param value the value (may be null)
     * @return the boolean value
     * @throws ShapeMismatchException for containers and non-boolean strings
     */
    static boolean coerceBoolean(JqValue value) {
        if (value == null || value instanceof JqNull) return false;
        if (value.isContainer()) throw mismatch(JqValue.Type.BOOLEAN, "boolean", value);
        if (value instanceof JqBoolean b) return b.booleanValue();
        if (value instanceof JqNumber n) return n.doubleValue() != 0;
        if (value instanceof JqString s) {
            String text = s.stringValue().trim();
            if (text.equalsIgnoreCase("true")) return true;
            if (text.equalsIgnoreCase("false")) return false;
        }
        throw mismatch(JqValue.Type.BOOLEAN, "boolean", value);
    }

    /**
     * Jackson-compatible integral coercion: numbers narrow, trimmed decimal
     * strings parse, anything else (including booleans — jq's
     * {@code tonumber} rejects them — and the empty string) is a shape
     * mismatch; nulls stay zero. Shared by {@link #convert} and the
     * generated-code helper so both paths agree.
     *
     * @param value  the value (may be null)
     * @param target human-readable target description for errors
     * @return the long value
     * @throws ShapeMismatchException for containers, booleans, fractions,
     *         out-of-range values, and non-numeric strings
     */
    static long coerceLong(JqValue value, String target) {
        if (value == null || value instanceof JqNull) return 0L;
        if (value.isContainer()) throw mismatch(JqValue.Type.NUMBER, target, value);
        if (value instanceof JqNumber n) {
            // Non-finite values have no integral form (jq never holds them);
            // silently yielding 0 would be a wrong value, so they mismatch.
            if (n.isNaN() || n.isInfinite()) throw mismatch(JqValue.Type.NUMBER, target, value);
            try {
                return n.decimalValue().longValueExact();
            } catch (ArithmeticException e) {
                throw mismatch(JqValue.Type.NUMBER, target, value);
            }
        }
        if (value instanceof JqString s) {
            try {
                return Long.parseLong(s.stringValue().trim());
            } catch (NumberFormatException e) {
                throw mismatch(JqValue.Type.NUMBER, target, value);
            }
        }
        throw mismatch(JqValue.Type.NUMBER, target, value);
    }

    /**
     * Range-checked narrowing for {@code int} targets: exact integral values
     * that fit, anything else (fractions, out-of-range magnitudes) is a
     * shape mismatch. Shared by {@link #convert} and the generated-code
     * helper so both paths agree.
     *
     * @param value the value (may be null; nulls stay zero)
     * @return the int value
     * @throws ShapeMismatchException on lossy conversions
     */
    static int coerceInt(JqValue value) {
        long v = coerceLong(value, "int");
        if ((int) v != v) throw mismatch(JqValue.Type.NUMBER, "int", value);
        return (int) v;
    }

    /**
     * Range-checked narrowing for {@code short} targets.
     *
     * @param value the value (may be null; nulls stay zero)
     * @return the short value
     * @throws ShapeMismatchException on lossy conversions
     */
    static short coerceShort(JqValue value) {
        long v = coerceLong(value, "short");
        if ((short) v != v) throw mismatch(JqValue.Type.NUMBER, "short", value);
        return (short) v;
    }

    /**
     * Range-checked narrowing for {@code byte} targets.
     *
     * @param value the value (may be null; nulls stay zero)
     * @return the byte value
     * @throws ShapeMismatchException on lossy conversions
     */
    static byte coerceByte(JqValue value) {
        long v = coerceLong(value, "byte");
        if ((byte) v != v) throw mismatch(JqValue.Type.NUMBER, "byte", value);
        return (byte) v;
    }

    /**
     * Jackson-compatible floating-point coercion: numbers widen, trimmed
     * numeric strings (including exponents) parse, anything else (including
     * booleans — jq's {@code tonumber} rejects them) is a shape mismatch;
     * nulls stay zero. Shared by {@link #convert} and the generated-code
     * helper so both paths agree.
     *
     * @param value  the value (may be null)
     * @param target human-readable target description for errors
     * @return the double value
     * @throws ShapeMismatchException for containers, booleans, and non-numeric strings
     */
    static double coerceDouble(JqValue value, String target) {
        if (value == null || value instanceof JqNull) return 0.0;
        if (value.isContainer()) throw mismatch(JqValue.Type.NUMBER, target, value);
        if (value instanceof JqNumber n) return n.doubleValue();
        if (value instanceof JqString s) {
            try {
                return Double.parseDouble(s.stringValue().trim());
            } catch (NumberFormatException e) {
                throw mismatch(JqValue.Type.NUMBER, target, value);
            }
        }
        throw mismatch(JqValue.Type.NUMBER, target, value);
    }

    /**
     * Jackson-compatible char coercion: single-character strings unwrap,
     * numbers map by code point, anything else (including multi-character
     * strings) is a shape mismatch; nulls and empty strings stay
     * {@code '\0'}. Shared by {@link #convert} and the generated-code
     * helper so both paths agree.
     *
     * @param value the value (may be null)
     * @return the char value
     * @throws ShapeMismatchException for containers, multi-character strings,
     *         and booleans
     */
    static char coerceChar(JqValue value) {
        if (value == null || value instanceof JqNull) return '\0';
        if (value.isContainer()) throw mismatch(JqValue.Type.STRING, "char", value);
        if (value instanceof JqString s) {
            if (s.stringValue().length() == 1) return s.stringValue().charAt(0);
            if (s.stringValue().isEmpty()) return '\0';
            throw mismatch(JqValue.Type.STRING, "char", value);
        }
        if (value instanceof JqNumber n) return (char) n.intValue();
        throw mismatch(JqValue.Type.STRING, "char", value);
    }

    /**
     * Jackson-compatible {@code BigDecimal} coercion: numbers convert
     * exactly, trimmed decimal strings parse, anything else (including
     * booleans — jq's {@code tonumber} rejects them) is a shape mismatch;
     * nulls stay null. Shared by {@link #convert} and the generated-code
     * helper so both paths agree.
     *
     * @param value the value (may be null)
     * @return the decimal value, or null for null inputs
     * @throws ShapeMismatchException for containers, booleans, and non-numeric strings
     */
    static BigDecimal coerceBigDecimal(JqValue value) {
        if (value == null || value instanceof JqNull) return null;
        if (value.isContainer()) throw mismatch(JqValue.Type.NUMBER, "BigDecimal", value);
        if (value instanceof JqNumber n) {
            if (n.isNaN() || n.isInfinite()) {
                throw mismatch(JqValue.Type.NUMBER, "BigDecimal", value);
            }
            return n.decimalValue();
        }
        if (value instanceof JqString s) {
            try {
                return new BigDecimal(s.stringValue().trim());
            } catch (NumberFormatException e) {
                throw mismatch(JqValue.Type.NUMBER, "BigDecimal", value);
            }
        }
        throw mismatch(JqValue.Type.NUMBER, "BigDecimal", value);
    }

    /**
     * Exact {@code BigInteger} coercion: numbers convert without loss
     * (integral values of any magnitude, including {@code 3.0}), trimmed
     * integer strings parse, anything else (fractions, non-finite values,
     * booleans — jq's {@code tonumber} rejects them) is a shape mismatch;
     * nulls stay null. Shared by {@link #convert} and the generated-code
     * helper so both paths agree.
     *
     * @param value the value (may be null)
     * @return the integer value, or null for null inputs
     * @throws ShapeMismatchException for containers, booleans, fractions,
     *         non-finite values, and non-numeric strings
     */
    static java.math.BigInteger coerceBigInteger(JqValue value) {
        if (value == null || value instanceof JqNull) return null;
        if (value.isContainer()) throw mismatch(JqValue.Type.NUMBER, "BigInteger", value);
        if (value instanceof JqNumber n) {
            if (n.isNaN() || n.isInfinite()) {
                throw mismatch(JqValue.Type.NUMBER, "BigInteger", value);
            }
            try {
                return n.decimalValue().toBigIntegerExact();
            } catch (ArithmeticException e) {
                throw mismatch(JqValue.Type.NUMBER, "BigInteger", value);
            }
        }
        if (value instanceof JqString s) {
            try {
                return new java.math.BigInteger(s.stringValue().trim());
            } catch (NumberFormatException e) {
                throw mismatch(JqValue.Type.NUMBER, "BigInteger", value);
            }
        }
        throw mismatch(JqValue.Type.NUMBER, "BigInteger", value);
    }

    /**
     * {@code UUID} coercion from its canonical string form: strings parse
     * (trimmed), anything else (numbers, booleans, containers) is a shape
     * mismatch; nulls stay null. Shared by {@link #convert} and the
     * generated-code helper so both paths agree.
     *
     * @param value the value (may be null)
     * @return the UUID, or null for null inputs
     * @throws ShapeMismatchException for non-string scalars and malformed text
     */
    static java.util.UUID coerceUuid(JqValue value) {
        if (value == null || value instanceof JqNull) return null;
        if (value.isContainer()) throw mismatch(JqValue.Type.STRING, "UUID", value);
        if (value instanceof JqString s) {
            try {
                return java.util.UUID.fromString(s.stringValue().trim());
            } catch (IllegalArgumentException e) {
                throw mismatch(JqValue.Type.STRING, "UUID", value);
            }
        }
        throw mismatch(JqValue.Type.STRING, "UUID", value);
    }

    /**
     * {@code URI} coercion from its string form (relative references bind,
     * like Jackson): strings parse (trimmed), anything else is a shape
     * mismatch; nulls stay null. Shared by {@link #convert} and the
     * generated-code helper so both paths agree.
     *
     * @param value the value (may be null)
     * @return the URI, or null for null inputs
     * @throws ShapeMismatchException for non-string scalars and malformed text
     */
    static java.net.URI coerceUri(JqValue value) {
        if (value == null || value instanceof JqNull) return null;
        if (value.isContainer()) throw mismatch(JqValue.Type.STRING, "URI", value);
        if (value instanceof JqString s) {
            try {
                return new java.net.URI(s.stringValue().trim());
            } catch (java.net.URISyntaxException e) {
                throw mismatch(JqValue.Type.STRING, "URI", value);
            }
        }
        throw mismatch(JqValue.Type.STRING, "URI", value);
    }

    /**
     * {@code URL} coercion from its string form (absolute URLs only —
     * relative references are shape mismatches, like Jackson): strings
     * parse (trimmed), anything else is a shape mismatch; nulls stay null.
     * Shared by {@link #convert} and the generated-code helper so both
     * paths agree.
     *
     * @param value the value (may be null)
     * @return the URL, or null for null inputs
     * @throws ShapeMismatchException for non-string scalars and malformed text
     */
    static java.net.URL coerceUrl(JqValue value) {
        if (value == null || value instanceof JqNull) return null;
        if (value.isContainer()) throw mismatch(JqValue.Type.STRING, "URL", value);
        if (value instanceof JqString s) {
            try {
                return new java.net.URI(s.stringValue().trim()).toURL();
            } catch (java.net.URISyntaxException | java.net.MalformedURLException
                    | IllegalArgumentException e) {
                throw mismatch(JqValue.Type.STRING, "URL", value);
            }
        }
        throw mismatch(JqValue.Type.STRING, "URL", value);
    }

    /**
     * {@code Instant} coercion: ISO-8601 strings parse, JSON numbers bind as
     * epoch seconds (fractions carry nanos); anything else (booleans,
     * non-finite numbers, containers, unparseable text) is a shape mismatch;
     * nulls stay null. Shared by {@link #convert} and the generated-code
     * helper so both paths agree.
     *
     * @param value the value (may be null)
     * @return the instant, or null for null inputs
     * @throws ShapeMismatchException for unparseable or unrepresentable values
     */
    static java.time.Instant coerceInstant(JqValue value) {
        if (value == null || value instanceof JqNull) return null;
        if (value.isContainer()) throw mismatch(JqValue.Type.STRING, "Instant", value);
        if (value instanceof JqString s) {
            try {
                return java.time.Instant.parse(s.stringValue().trim());
            } catch (java.time.format.DateTimeParseException e) {
                throw mismatch(JqValue.Type.STRING, "Instant", value);
            }
        }
        if (value instanceof JqNumber n) {
            if (n.isNaN() || n.isInfinite()) throw mismatch(JqValue.Type.NUMBER, "Instant", value);
            try {
                return epochToInstant(n.decimalValue());
            } catch (ArithmeticException e) {
                throw mismatch(JqValue.Type.NUMBER, "Instant", value);
            }
        }
        throw mismatch(JqValue.Type.STRING, "Instant", value);
    }

    /**
     * Split epoch seconds (integral or fractional) into an {@code Instant}.
     * Sub-nanosecond fractions are unrepresentable and fail via
     * {@code intValueExact}; magnitudes beyond {@code long} fail via
     * {@code longValueExact} — both surface as shape mismatches at callers.
     */
    private static java.time.Instant epochToInstant(BigDecimal decimal) {
        long seconds = decimal.setScale(0, java.math.RoundingMode.DOWN).longValueExact();
        int nanos = decimal.subtract(BigDecimal.valueOf(seconds)).movePointRight(9).intValueExact();
        return java.time.Instant.ofEpochSecond(seconds, nanos);
    }

    /**
     * {@code LocalDate} coercion from ISO strings ({@code 2026-10-14});
     * numbers are meaningless for calendar types and mismatch; nulls stay
     * null. Shared by {@link #convert} and the generated-code helper.
     */
    static java.time.LocalDate coerceLocalDate(JqValue value) {
        if (value == null || value instanceof JqNull) return null;
        if (value.isContainer()) throw mismatch(JqValue.Type.STRING, "LocalDate", value);
        if (value instanceof JqString s) {
            try {
                return java.time.LocalDate.parse(s.stringValue().trim());
            } catch (java.time.format.DateTimeParseException e) {
                throw mismatch(JqValue.Type.STRING, "LocalDate", value);
            }
        }
        throw mismatch(JqValue.Type.STRING, "LocalDate", value);
    }

    /**
     * {@code LocalTime} coercion from ISO strings; nulls stay null.
     * Shared by {@link #convert} and the generated-code helper.
     */
    static java.time.LocalTime coerceLocalTime(JqValue value) {
        if (value == null || value instanceof JqNull) return null;
        if (value.isContainer()) throw mismatch(JqValue.Type.STRING, "LocalTime", value);
        if (value instanceof JqString s) {
            try {
                return java.time.LocalTime.parse(s.stringValue().trim());
            } catch (java.time.format.DateTimeParseException e) {
                throw mismatch(JqValue.Type.STRING, "LocalTime", value);
            }
        }
        throw mismatch(JqValue.Type.STRING, "LocalTime", value);
    }

    /**
     * {@code LocalDateTime} coercion from ISO strings; nulls stay null.
     * Shared by {@link #convert} and the generated-code helper.
     */
    static java.time.LocalDateTime coerceLocalDateTime(JqValue value) {
        if (value == null || value instanceof JqNull) return null;
        if (value.isContainer()) throw mismatch(JqValue.Type.STRING, "LocalDateTime", value);
        if (value instanceof JqString s) {
            try {
                return java.time.LocalDateTime.parse(s.stringValue().trim());
            } catch (java.time.format.DateTimeParseException e) {
                throw mismatch(JqValue.Type.STRING, "LocalDateTime", value);
            }
        }
        throw mismatch(JqValue.Type.STRING, "LocalDateTime", value);
    }

    /**
     * {@code OffsetDateTime} coercion: ISO strings parse, numbers bind as
     * epoch seconds at UTC; nulls stay null. Shared by {@link #convert} and
     * the generated-code helper.
     */
    static java.time.OffsetDateTime coerceOffsetDateTime(JqValue value) {
        if (value == null || value instanceof JqNull) return null;
        if (value.isContainer()) throw mismatch(JqValue.Type.STRING, "OffsetDateTime", value);
        if (value instanceof JqString s) {
            try {
                return java.time.OffsetDateTime.parse(s.stringValue().trim());
            } catch (java.time.format.DateTimeParseException e) {
                throw mismatch(JqValue.Type.STRING, "OffsetDateTime", value);
            }
        }
        if (value instanceof JqNumber n) {
            if (n.isNaN() || n.isInfinite()) throw mismatch(JqValue.Type.NUMBER, "OffsetDateTime", value);
            try {
                return epochToInstant(n.decimalValue()).atOffset(java.time.ZoneOffset.UTC);
            } catch (ArithmeticException e) {
                throw mismatch(JqValue.Type.NUMBER, "OffsetDateTime", value);
            }
        }
        throw mismatch(JqValue.Type.STRING, "OffsetDateTime", value);
    }

    /**
     * {@code ZonedDateTime} coercion: ISO strings parse, numbers bind as
     * epoch seconds at UTC; nulls stay null. Shared by {@link #convert} and
     * the generated-code helper.
     */
    static java.time.ZonedDateTime coerceZonedDateTime(JqValue value) {
        if (value == null || value instanceof JqNull) return null;
        if (value.isContainer()) throw mismatch(JqValue.Type.STRING, "ZonedDateTime", value);
        if (value instanceof JqString s) {
            try {
                return java.time.ZonedDateTime.parse(s.stringValue().trim());
            } catch (java.time.format.DateTimeParseException e) {
                throw mismatch(JqValue.Type.STRING, "ZonedDateTime", value);
            }
        }
        if (value instanceof JqNumber n) {
            if (n.isNaN() || n.isInfinite()) throw mismatch(JqValue.Type.NUMBER, "ZonedDateTime", value);
            try {
                return epochToInstant(n.decimalValue()).atZone(java.time.ZoneOffset.UTC);
            } catch (ArithmeticException e) {
                throw mismatch(JqValue.Type.NUMBER, "ZonedDateTime", value);
            }
        }
        throw mismatch(JqValue.Type.STRING, "ZonedDateTime", value);
    }

    /**
     * {@code Duration} coercion: ISO strings parse ({@code PT1H30M}),
     * numbers bind as seconds (fractions carry nanos); nulls stay null.
     * Shared by {@link #convert} and the generated-code helper.
     */
    static java.time.Duration coerceDuration(JqValue value) {
        if (value == null || value instanceof JqNull) return null;
        if (value.isContainer()) throw mismatch(JqValue.Type.STRING, "Duration", value);
        if (value instanceof JqString s) {
            try {
                return java.time.Duration.parse(s.stringValue().trim());
            } catch (java.time.format.DateTimeParseException e) {
                throw mismatch(JqValue.Type.STRING, "Duration", value);
            }
        }
        if (value instanceof JqNumber n) {
            if (n.isNaN() || n.isInfinite()) throw mismatch(JqValue.Type.NUMBER, "Duration", value);
            try {
                BigDecimal decimal = n.decimalValue();
                long seconds = decimal.setScale(0, java.math.RoundingMode.DOWN).longValueExact();
                int nanos = decimal.subtract(BigDecimal.valueOf(seconds)).movePointRight(9).intValueExact();
                return java.time.Duration.ofSeconds(seconds, nanos);
            } catch (ArithmeticException e) {
                throw mismatch(JqValue.Type.NUMBER, "Duration", value);
            }
        }
        throw mismatch(JqValue.Type.STRING, "Duration", value);
    }

    /**
     * {@code Period} coercion from ISO strings ({@code P1Y2M3D}); numbers
     * are meaningless for calendar periods and mismatch; nulls stay null.
     * Shared by {@link #convert} and the generated-code helper.
     */
    static java.time.Period coercePeriod(JqValue value) {
        if (value == null || value instanceof JqNull) return null;
        if (value.isContainer()) throw mismatch(JqValue.Type.STRING, "Period", value);
        if (value instanceof JqString s) {
            try {
                return java.time.Period.parse(s.stringValue().trim());
            } catch (java.time.format.DateTimeParseException e) {
                throw mismatch(JqValue.Type.STRING, "Period", value);
            }
        }
        throw mismatch(JqValue.Type.STRING, "Period", value);
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

    /** Target description for Set mismatches, e.g. {@code Set<String>}. */
    private static String setTarget(Class<?> targetType, Type genericType) {
        Type elementType = extractTypeArgument(genericType, 0);
        return "Set<" + rawClass(elementType).getSimpleName() + ">";
    }

    /** Empty set matching the declared implementation (null inputs). */
    private static Object emptySetFor(Class<?> targetType, Class<?> elementClass) {
        if (targetType == EnumSet.class) return newSetFor(targetType, elementClass, 0);
        if (targetType == TreeSet.class || targetType == SortedSet.class
                || targetType == java.util.NavigableSet.class) return new TreeSet<>();
        if (!targetType.isInterface() && !java.lang.reflect.Modifier.isAbstract(targetType.getModifiers())
                && targetType != Set.class && targetType != HashSet.class && targetType != LinkedHashSet.class) {
            Object custom = newNoArg(targetType);
            if (custom instanceof java.util.Set<?>) return custom;
        }
        return Set.of();
    }

    /**
     * Mutable set matching the declared implementation: {@code HashSet} stays
     * one, sorted sets order naturally, {@code EnumSet} uses its compact
     * representation (and rejects non-enum element types fail-fast).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static java.util.Set<Object> newSetFor(Class<?> targetType, Class<?> elementClass, int size) {
        if (targetType == EnumSet.class) {
            if (!elementClass.isEnum()) {
                throw new JqMapperException("EnumSet requires an enum element type, not "
                        + elementClass.getName());
            }
            return (java.util.Set<Object>) (java.util.Set<?>) EnumSet.noneOf((Class) elementClass);
        }
        if (targetType == TreeSet.class || targetType == SortedSet.class
                || targetType == java.util.NavigableSet.class) return new TreeSet<>();
        if (targetType == HashSet.class) return new HashSet<>(size * 4 / 3 + 1);
        if (!targetType.isInterface() && !java.lang.reflect.Modifier.isAbstract(targetType.getModifiers())
                && targetType != Set.class && targetType != LinkedHashSet.class) {
            // Custom Set implementation: bind through its no-arg constructor.
            Object custom = newNoArg(targetType);
            if (custom instanceof java.util.Set<?> s) {
                @SuppressWarnings("unchecked")
                java.util.Set<Object> typed = (java.util.Set<Object>) s;
                return typed;
            }
        }
        return new LinkedHashSet<>(size * 4 / 3 + 1);
    }

    /**
     * Mutable list matching the declared type: {@code LinkedList} stays one,
     * queues/deques use {@code ArrayDeque}, everything else an
     * {@code ArrayList} (covers {@code List}, {@code Collection},
     * {@code Iterable}, and custom {@code List} implementations by
     * interface — the constructor adapts, same leniency as before).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static java.util.Collection<Object> newListFor(Class<?> targetType, int size) {
        if (targetType == java.util.LinkedList.class) return new java.util.LinkedList<>();
        if (targetType == java.util.Queue.class || targetType == java.util.Deque.class
                || targetType == java.util.ArrayDeque.class
                || (java.util.Queue.class.isAssignableFrom(targetType)
                    && !List.class.isAssignableFrom(targetType))) {
            return new java.util.ArrayDeque<>(Math.max(size, 1));
        }
        if (!targetType.isInterface() && !java.lang.reflect.Modifier.isAbstract(targetType.getModifiers())
                && targetType != List.class && targetType != ArrayList.class
                && targetType != java.util.Collection.class && targetType != Iterable.class) {
            // Custom List implementation (Vector, Stack, CopyOnWriteArrayList, ...):
            // bind through its no-arg constructor, ArrayList as fallback.
            Object custom = newNoArg(targetType);
            if (custom instanceof java.util.Collection<?> c) return (java.util.Collection<Object>) c;
        }
        return new ArrayList<>(size);
    }

    /**
     * Mutable map matching the declared type: sorted maps order naturally,
     * everything else a {@code LinkedHashMap} (insertion order, matching
     * JSON document order).
     */
    private static Map<String, Object> newMapFor(Class<?> targetType, int size) {
        if (targetType == java.util.TreeMap.class || targetType == java.util.SortedMap.class
                || targetType == java.util.NavigableMap.class) {
            return new java.util.TreeMap<>();
        }
        if (!targetType.isInterface() && !java.lang.reflect.Modifier.isAbstract(targetType.getModifiers())
                && targetType != Map.class && targetType != LinkedHashMap.class && targetType != HashMap.class) {
            // Custom Map implementation (ConcurrentHashMap, ...): bind through
            // its no-arg constructor, LinkedHashMap as fallback.
            Object custom = newNoArg(targetType);
            if (custom instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) m;
                return typed;
            }
        }
        return new LinkedHashMap<>(size * 4 / 3 + 1);
    }

    /**
     * Best-effort no-arg instantiation for custom collection/map
     * implementations. Returns null when unavailable (callers fall back to
     * the interface default).
     */
    private static Object newNoArg(Class<?> targetType) {
        try {
            var ctor = targetType.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException | SecurityException e) {
            return null;
        }
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
        // BigInteger before Number: the generic Number path narrows via
        // longValue() and would silently truncate huge magnitudes.
        if (value instanceof java.math.BigInteger bi) return JqNumber.of(new BigDecimal(bi));
        if (value instanceof Number n) return JqNumber.of(n);
        if (value instanceof Boolean b) return JqBoolean.of(b);
        if (value instanceof Character c) return JqString.of(String.valueOf(c));
        if (value instanceof java.util.UUID u) return JqString.of(u.toString());
        if (value instanceof java.time.Instant i) return JqString.of(i.toString());
        if (value instanceof java.time.LocalDate d) return JqString.of(d.toString());
        if (value instanceof java.time.LocalTime t) return JqString.of(t.toString());
        if (value instanceof java.time.LocalDateTime dt) return JqString.of(dt.toString());
        if (value instanceof java.time.OffsetDateTime odt) return JqString.of(odt.toString());
        if (value instanceof java.time.ZonedDateTime zdt) return JqString.of(zdt.toString());
        if (value instanceof java.time.Duration dur) return JqString.of(dur.toString());
        if (value instanceof java.time.Period p) return JqString.of(p.toString());
        if (value instanceof java.net.URL url) return JqString.of(url.toString());
        if (value instanceof java.net.URI uri) return JqString.of(uri.toString());
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
        if (value instanceof Set<?> set) {
            JqValue[] elements = new JqValue[set.size()];
            int i = 0;
            for (Object element : set) {
                elements[i++] = toJqValue(element, mapper);
            }
            return JqArray.ofTrusted(elements);
        }
        if (value.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(value);
            JqValue[] elements = new JqValue[len];
            for (int i = 0; i < len; i++) {
                elements[i] = toJqValue(java.lang.reflect.Array.get(value, i), mapper);
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
            JqValue wire = enumWireValue(value, mapper);
            if (wire != null) return wire;
            String wireName = enumWireName((Enum<?>) value, mapper);
            if (wireName != null) return JqString.of(wireName);
            return JqString.of(((Enum<?>) value).name());
        }
        if (value.getClass().isRecord()) {
            return mapper.toJqValue(value);
        }
        if (value.getClass().isAnnotationPresent(JqMapped.class)) {
            return mapper.toJqValue(value);
        }
        if (isPojoLike(value.getClass()) && !isJdkClass(value.getClass())) {
            // Plain POJOs bind structurally, mirroring the deserialization
            // direction (issue #88.3). JDK types keep the lenient fallback below.
            return mapper.toJqValue(value);
        }
        return JqValues.fromJavaObject(value);
    }

    /**
     * True for JDK platform classes, which keep lenient conversion instead of
     * structural binding (their internals are not meaningful bean properties).
     */
    private static boolean isJdkClass(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("sun.");
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

    /**
     * Cached {@code @JsonValue}/{@code @JsonCreator}-equivalent handlers for an enum class.
     * Null members mean absent. Resolved once per (mapper, class) via
     * {@link JqMapper#enumHandlers(Class)}; discovery failures throw at first use.
     *
     * @param valueAccessor zero-arg instance method whose result is the wire form (may be null)
     * @param creator       single-arg static factory or constructor (may be null)
     * @param creatorArgClass raw creator parameter class (null when no creator)
     * @param creatorArgType  generic creator parameter type (null when no creator)
     * @param wireNames     constant name to wire name from per-constant renames
     *                      (e.g. Jackson {@code @JsonProperty}; empty when none)
     */
    record EnumHandlers(Method valueAccessor, Executable creator,
                        Class<?> creatorArgClass, Type creatorArgType,
                        java.util.Map<String, String> wireNames, EnumLeniency leniency) {}

    /**
     * Native {@code @JqEnum} leniency policy (null when the annotation is
     * absent — strict behavior). The normalized map goes from normalized wire
     * name to constant name, built once at handler resolution.
     */
    record EnumLeniency(java.util.Set<JqEnum.Normalize> normalizations,
                        JqEnum.OnUnknown onUnknown,
                        java.util.Map<String, String> normalizedWire) {}

    /**
     * Resolve enum handlers from the mapper's bridges (native annotations are
     * always consulted first — currently no native equivalent exists, so this
     * is bridge-driven). Malformed signatures fail fast.
     *
     * @return the handlers, or empty when the class declares neither
     */
    static java.util.Optional<EnumHandlers> resolveEnumHandlers(
            Class<?> type, List<io.hyperfoil.tools.jjq.mapper.spi.AnnotationBridge> bridges) {
        EnumLeniency leniency = resolveEnumLeniency(type, bridges);
        Method accessor = null;
        Executable creator = null;
        for (io.hyperfoil.tools.jjq.mapper.spi.AnnotationBridge bridge : bridges) {
            if (accessor == null) {
                Method m = bridge.resolveJsonValueAccessor(type);
                if (m != null) {
                    validateValueAccessor(m, type);
                    accessor = m;
                }
            }
            // A present @JqEnum takes over deserialization: bridge creators
            // are ignored for that enum (serialization accessors still apply).
            if (creator == null && leniency == null) {
                Executable e = bridge.resolveJsonCreator(type);
                if (e != null) {
                    validateCreator(e, type);
                    creator = e;
                }
            }
            if (accessor != null && (creator != null || leniency != null)) break;
        }
        if (accessor == null && creator == null && leniency == null) {
            java.util.Map<String, String> wireOnly =
                    resolveEnumWireNames(type, bridges);
            if (wireOnly.isEmpty()) return java.util.Optional.empty();
            return java.util.Optional.of(new EnumHandlers(null, null, null, null, wireOnly, null));
        }
        Class<?> argClass = null;
        Type argType = null;
        if (creator != null) {
            argType = creator.getGenericParameterTypes()[0];
            argClass = rawClass(argType);
        }
        return java.util.Optional.of(new EnumHandlers(accessor, creator, argClass, argType,
                resolveEnumWireNames(type, bridges), leniency));
    }

    /**
     * Native {@code @JqEnum} leniency for an enum type, or null when absent
     * (strict behavior preserved). Wire names come from the same
     * {@code @JqName}-first resolution as the strict path.
     */
    private static EnumLeniency resolveEnumLeniency(
            Class<?> type, List<io.hyperfoil.tools.jjq.mapper.spi.AnnotationBridge> bridges) {
        JqEnum spec = type.getAnnotation(JqEnum.class);
        if (spec == null) return null;
        java.util.Set<JqEnum.Normalize> normalizations = java.util.EnumSet.noneOf(JqEnum.Normalize.class);
        normalizations.addAll(java.util.List.of(spec.normalize()));
        java.util.Map<String, String> byWire = new java.util.LinkedHashMap<>();
        for (var entry : resolveEnumWireNames(type, bridges).entrySet()) {
            String key = normalizeWire(entry.getValue(), normalizations);
            String prev = byWire.putIfAbsent(key, entry.getKey());
            if (prev != null) {
                throw new JqMapperException("@JqEnum on " + type.getName() + ": '"
                        + prev + "' and '" + entry.getKey()
                        + "' normalize to the same wire name '" + key + "'");
            }
        }
        return new EnumLeniency(normalizations, spec.onUnknown(), byWire);
    }

    /**
     * Fold a wire value per the normalization flags (all ASCII-only and
     * locale-independent; no-ops avoid allocation when nothing changes).
     */
    static String normalizeWire(String wire, java.util.Set<JqEnum.Normalize> normalizations) {
        String result = wire;
        if (normalizations.contains(JqEnum.Normalize.TRIM)) result = result.strip();
        if (normalizations.contains(JqEnum.Normalize.LOWERCASE)) {
            StringBuilder folded = null;
            for (int i = 0; i < result.length(); i++) {
                char c = result.charAt(i);
                if (c >= 'A' && c <= 'Z') {
                    if (folded == null) {
                        folded = new StringBuilder(result.length());
                        folded.append(result, 0, i);
                    }
                    folded.append((char) (c + ('a' - 'A')));
                } else if (folded != null) {
                    folded.append(c);
                }
            }
            if (folded != null) result = folded.toString();
        }
        if (normalizations.contains(JqEnum.Normalize.SEPARATOR_FOLD) && result.indexOf('_') >= 0) {
            result = result.replace('_', '-');
        }
        return result;
    }

    /**
     * Per-constant wire names from rename annotations on the enum constants
     * (issue #95.1: Jackson {@code @JsonProperty("set-if-unset")} on constants,
     * resolved through bridges like any other field rename; issue #112: native
     * {@code @JqName} wins over bridges, like any other field rename).
     */
    private static java.util.Map<String, String> resolveEnumWireNames(
            Class<?> type, List<io.hyperfoil.tools.jjq.mapper.spi.AnnotationBridge> bridges) {
        java.util.Map<String, String> wireNames = new java.util.LinkedHashMap<>();
        for (Object constant : type.getEnumConstants()) {
            String name = ((Enum<?>) constant).name();
            java.lang.reflect.Field field;
            try {
                field = type.getField(name);
            } catch (NoSuchFieldException e) {
                continue;
            }
            JqName jqName = field.getAnnotation(JqName.class);
            if (jqName != null && !jqName.value().isEmpty()) {
                wireNames.put(name, jqName.value());
                continue;
            }
            for (io.hyperfoil.tools.jjq.mapper.spi.AnnotationBridge bridge : bridges) {
                String wire = bridge.resolveFieldName(field);
                if (wire != null && !wire.isEmpty()) {
                    wireNames.put(name, wire);
                    break;
                }
            }
        }
        return wireNames;
    }

    /** Fail fast on malformed value-accessor signatures (checked once per class). */
    private static void validateValueAccessor(Method m, Class<?> type) {
        if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) {
            throw new JqMapperException("@JsonValue accessor must be a non-static no-arg method: "
                    + type.getName() + "#" + m.getName());
        }
        try {
            m.setAccessible(true);
        } catch (SecurityException se) {
            throw new JqMapperException("Cannot access @JsonValue accessor on " + type.getName(), se);
        }
    }

    /** Fail fast on malformed creator signatures (checked once per class). */
    private static void validateCreator(Executable e, Class<?> type) {
        if (e.getParameterCount() != 1
                || (e instanceof Method m && !java.lang.reflect.Modifier.isStatic(m.getModifiers()))) {
            throw new JqMapperException("@JsonCreator must be a single-arg static factory or constructor: "
                    + type.getName() + "#" + e.getName());
        }
        try {
            e.setAccessible(true);
        } catch (SecurityException se) {
            throw new JqMapperException("Cannot access @JsonCreator on " + type.getName(), se);
        }
    }

    /** Invoke a creator factory with a converted scalar argument. Failures are fail-fast. */
    private static Object invokeCreator(EnumHandlers handlers, JqValue value,
                                        Class<?> targetType, JqMapper mapper) {
        Kind argKind = resolveKind(handlers.creatorArgClass(), handlers.creatorArgType());
        Object arg = convert(value, argKind, handlers.creatorArgClass(), handlers.creatorArgType(), mapper);
        try {
            if (handlers.creator() instanceof Constructor<?> c) return c.newInstance(arg);
            return ((Method) handlers.creator()).invoke(null, arg);
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            throw new JqMapperException("Failed to invoke @JsonCreator for " + targetType.getName(), e);
        }
    }

    /**
     * Per-constant wire name for an enum value, or null when it serializes
     * via {@code name()} (issue #95.1). {@code @JsonValue} accessors take
     * precedence (checked by the caller first).
     */
    private static String enumWireName(Enum<?> enumValue, JqMapper mapper) {
        return mapper.enumHandlers(enumValue.getClass())
                .map(h -> h.wireNames().get(enumValue.name()))
                .orElse(null);
    }

    /**
     * Compute an enum's wire form via its value accessor, converted to JSON.
     * Returns null when no accessor is present (caller falls back to {@code name()}).
     * A self-returning accessor (returns the enum itself) also falls back, avoiding recursion.
     */
    private static JqValue enumWireValue(Object enumValue, JqMapper mapper) {
        java.util.Optional<EnumHandlers> handlers = mapper.enumHandlers(enumValue.getClass());
        if (handlers.isEmpty() || handlers.get().valueAccessor() == null) return null;
        final Object result;
        try {
            result = handlers.get().valueAccessor().invoke(enumValue);
        } catch (ReflectiveOperationException e) {
            throw new JqMapperException("Failed to invoke @JsonValue accessor on "
                    + enumValue.getClass().getName(), e);
        }
        if (result == null || result == enumValue) return null;
        return toJqValue(result, mapper);
    }
}

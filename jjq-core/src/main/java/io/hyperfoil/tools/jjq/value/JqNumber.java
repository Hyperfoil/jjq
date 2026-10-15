package io.hyperfoil.tools.jjq.value;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;

public final class JqNumber implements JqValue {
    private static final long serialVersionUID = 1L;

    private static final int CACHE_LOW = -128;
    private static final int CACHE_HIGH = 1023;
    private static final JqNumber[] CACHE = new JqNumber[CACHE_HIGH - CACHE_LOW + 1];
    static {
        for (int i = 0; i < CACHE.length; i++) {
            CACHE[i] = new JqNumber(i + CACHE_LOW, null, 0, true, null, false);
        }
    }

    private final long longVal;
    private final BigDecimal decimalVal;
    private final double rawDouble; // used for NaN/Infinity
    private final boolean isLong;
    private transient BigDecimal cachedDecimal; // lazy cache for long-backed numbers
    // Original source text when it differs from the typed rendering (e.g. YAML
    // `0022` resolving to 18). Null for canonical values. Used only for String
    // binding; ignored by serialization, equality, and queries.
    private final String sourceText;
    // True when the literal was spelled as a float (`.`/`e`/`E`, or negative
    // zero which binds as float). Metadata only: ignored by serialization,
    // equality, hashCode, and toJavaObject — adapters branch on it for
    // int-vs-float typing.
    private final boolean floatLexed;

    private JqNumber(long longVal, BigDecimal decimalVal, double rawDouble, boolean isLong,
                     String sourceText, boolean floatLexed) {
        this.longVal = longVal;
        this.decimalVal = decimalVal;
        this.rawDouble = rawDouble;
        this.isLong = isLong;
        this.sourceText = sourceText;
        this.floatLexed = floatLexed;
    }

    /**
     * Copy this number preserving its exact representation, attaching source text.
     *
     * @param sourceText the original source text (may be null to clear)
     * @return a number equal to this one, carrying {@code sourceText}
     */
    public JqNumber withSourceText(String sourceText) {
        if (sourceText == this.sourceText) return this;
        return new JqNumber(longVal, decimalVal, rawDouble, isLong, sourceText, floatLexed);
    }

    @Override
    public String sourceText() { return sourceText; }

    public static JqNumber of(long value) {
        if (value >= CACHE_LOW && value <= CACHE_HIGH) {
            return CACHE[(int) value - CACHE_LOW];
        }
        return new JqNumber(value, null, 0, true, null, false);
    }

    public static JqNumber of(double value) {
        return ofDouble(value, false);
    }

    /**
     * Create a JqNumber from a double with float-literal provenance (the
     * parser passes whether the literal was spelled as a float).
     * Programmatic doubles carry no provenance ({@code false}).
     *
     * @param value      the double value
     * @param floatLexed true when float-spelled in source text
     */
    public static JqNumber of(double value, boolean floatLexed) {
        return ofDouble(value, floatLexed);
    }

    private static JqNumber ofDouble(double value, boolean floatLexed) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return new JqNumber(0, null, value, false, null, false);
        }
        if (value == 0.0 && Double.doubleToRawLongBits(value) == Long.MIN_VALUE) {
            // Negative zero never folds to long: serde-style consumers
            // distinguish -0.0 (float) from 0 (int), and jq renders "-0".
            return new JqNumber(0, null, value, false, null, floatLexed);
        }
        if (value == Math.floor(value) && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE) {
            long folded = (long) value;
            if (!floatLexed) return of(folded);
            return new JqNumber(folded, null, 0, true, null, true);
        }
        // Store double directly, defer BigDecimal construction until needed
        return new JqNumber(0, null, value, false, null, floatLexed);
    }

    /**
     * True when the literal was spelled as a float (contains {@code .} or
     * exponent) or denotes negative zero (which binds as float) — as
     * opposed to computed values, which carry no provenance. Metadata only:
     * ignored by serialization, equality, hashCode, and {@code toJavaObject}.
     * serde-style adapters branch on it for int-vs-float typing:
     * <pre>{@code
     * Object typed = n.isFloatLexed() ? (Object) n.doubleValue() : n.toJavaObject();
     * }</pre>
     */
    public boolean isFloatLexed() {
        return floatLexed;
    }

    /**
     * True for double-backed negative zero — the only value distinguishing
     * -0.0 from +0.0 in the model (JSON input and IEEE arithmetic preserve
     * it; program literals, math builtins, and negation normalize to +0).
     */
    public boolean isNegativeZero() {
        return !isLong && decimalVal == null && Double.doubleToRawLongBits(rawDouble) == Long.MIN_VALUE;
    }

    /**
     * Wrap a rounding result: jq keeps -0.0 for negative inputs rounding to
     * zero ({@code round(-0.4)} is -0.0). {@link Math#round(double)} returns
     * long, which has no negative zero.
     */
    public static JqNumber ofRounded(double value) {
        long rounded = Math.round(value);
        if (rounded == 0 && (value < 0 || Double.doubleToRawLongBits(value) == Long.MIN_VALUE)) {
            return of(-0.0);
        }
        return of(rounded);
    }

    /**
     * Exact Java form of an integral value: {@link Long} when it fits,
     * {@link BigInteger} beyond long range (where
     * {@link #longValue()} would silently wrap). Call only when
     * {@link #isIntegral()} holds (fractional values have no exact form).
     */
    public Object integralValueExact() {
        if (isLong) return longVal;
        BigDecimal value = decimalValue();
        if (value.signum() == 0 || fitsInLong(value, value.scale())) {
            return value.longValueExact();
        }
        return value.toBigIntegerExact();
    }

    /**
     * Create a JqNumber from any {@link Number} subtype.
     * {@link Integer}, {@link Long}, {@link Short}, and {@link Byte} are treated as integral (long-backed).
     * {@link Float} and {@link Double} are treated as floating-point.
     * {@link BigDecimal} preserves its exact value; {@link BigInteger}
     * stays exact through BigDecimal (never demoted through {@code double}).
     * Other Number types are converted via {@link Number#doubleValue()}.
     */
    public static JqNumber of(Number value) {
        if (value instanceof Long l) return of(l.longValue());
        if (value instanceof Integer i) return of((long) i.intValue());
        if (value instanceof Double d) return of(d.doubleValue());
        if (value instanceof Float f) return of((double) f.floatValue());
        if (value instanceof BigDecimal bd) return of(bd);
        if (value instanceof BigInteger bi) return of(new BigDecimal(bi));
        if (value instanceof Short s) return of((long) s.shortValue());
        if (value instanceof Byte b) return of((long) b.byteValue());
        // Fallback for AtomicInteger, AtomicLong, etc.
        double d = value.doubleValue();
        if (d == Math.floor(d) && !Double.isInfinite(d) && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE) {
            return of(value.longValue());
        }
        return of(d);
    }

    private static final BigDecimal LONG_MIN_BD = BigDecimal.valueOf(Long.MIN_VALUE);
    private static final BigDecimal LONG_MAX_BD = BigDecimal.valueOf(Long.MAX_VALUE);

    /**
     * Create a JqNumber from a BigDecimal. If the value represents a whole number
     * that fits in a long, returns a long-backed instance (with cache hit for [-128, 1023]).
     * Otherwise stores the BigDecimal directly.
     *
     * <p>Uses scale/precision checks to avoid {@code longValueExact()} throwing
     * {@link ArithmeticException} on non-integer values. This eliminates
     * {@code Throwable.fillInStackTrace()} overhead that was measurable in
     * high-throughput JSON parsing (issue #59).</p>
     */
    public static JqNumber of(BigDecimal value) {
        return ofDecimal(value, false);
    }

    /**
     * Create a JqNumber from a BigDecimal with float-literal provenance (the
     * parser passes whether the literal was spelled as a float).
     *
     * @param value      the decimal value
     * @param floatLexed true when float-spelled in source text
     */
    public static JqNumber of(BigDecimal value, boolean floatLexed) {
        return ofDecimal(value, floatLexed);
    }

    private static JqNumber ofDecimal(BigDecimal value, boolean floatLexed) {
        int scale = value.scale();
        if (scale <= 0) {
            // No fractional part — check if it fits in a long
            if (fitsInLong(value, scale)) {
                long folded = value.longValueExact(); // guaranteed no exception
                if (!floatLexed) return of(folded);
                return new JqNumber(folded, null, 0, true, null, true);
            }
            // Too large for long — keep as BigDecimal
            return new JqNumber(0, value, value.doubleValue(), false, null, floatLexed);
        }
        // Positive scale — has fractional digits. Strip trailing zeros first
        // to handle cases like 3.00 which should be treated as integer 3.
        BigDecimal stripped = value.stripTrailingZeros();
        int strippedScale = stripped.scale();
        if (strippedScale <= 0 && fitsInLong(stripped, strippedScale)) {
            long folded = stripped.longValueExact(); // guaranteed no exception
            if (!floatLexed) return of(folded);
            return new JqNumber(folded, null, 0, true, null, true);
        }
        // Genuinely non-integer — store as BigDecimal
        return new JqNumber(0, value, value.doubleValue(), false, null, floatLexed);
    }

    /**
     * Check if a BigDecimal with scale <= 0 fits in a long without calling
     * longValueExact() (which would throw ArithmeticException if it doesn't fit).
     *
     * <p>The number of integer digits is {@code precision - scale} (scale is <= 0,
     * so {@code -scale} adds the trailing zeros). A long holds at most 19 decimal
     * digits, but only some 19-digit values fit (Long.MAX_VALUE = 9223372036854775807).
     * For 1-18 digits we know it fits; for 19 digits we range-check against
     * Long.MIN_VALUE/MAX_VALUE; for 20+ digits it never fits.</p>
     */
    private static boolean fitsInLong(BigDecimal value, int scale) {
        int integerDigits = value.precision() + (-scale);
        if (integerDigits <= 18) return true;
        if (integerDigits >= 20) return false;
        // Exactly 19 digits — some fit, some don't. Range-check.
        return value.compareTo(LONG_MIN_BD) >= 0 && value.compareTo(LONG_MAX_BD) <= 0;
    }

    /** Preserve cached instance identity across serialization. */
    private Object readResolve() {
        if (isLong && !floatLexed && longVal >= CACHE_LOW && longVal <= CACHE_HIGH) {
            return CACHE[(int) longVal - CACHE_LOW];
        }
        return this;
    }

    public boolean isNaN() {
        return !isLong && decimalVal == null && Double.isNaN(rawDouble);
    }

    public boolean isInfinite() {
        return !isLong && decimalVal == null && Double.isInfinite(rawDouble);
    }

    public boolean isIntegral() {
        if (isLong) return true;
        if (isSpecial()) return false; // NaN/Infinity
        if (decimalVal == null) {
            // double-backed: check if it's a whole number
            return rawDouble == Math.floor(rawDouble) && !Double.isInfinite(rawDouble);
        }
        return decimalVal.stripTrailingZeros().scale() <= 0;
    }

    /** Returns true if this number is directly backed by a long (not BigDecimal or special). */
    public boolean isLongBacked() {
        return isLong;
    }

    /** True for NaN/Infinity values that have no BigDecimal representation. */
    private boolean isSpecial() {
        return !isLong && decimalVal == null && (Double.isNaN(rawDouble) || Double.isInfinite(rawDouble));
    }

    @Override
    public Type type() { return Type.NUMBER; }

    @Override
    public long longValue() {
        if (isLong) return longVal;
        if (decimalVal != null) return decimalVal.longValue();
        return (long) rawDouble;
    }

    @Override
    public double doubleValue() {
        if (isLong) return (double) longVal;
        if (decimalVal != null) return decimalVal.doubleValue();
        return rawDouble;
    }

    @Override
    public BigDecimal decimalValue() {
        if (isSpecial()) {
            // NaN/Infinity have no BigDecimal form; the old ZERO fallback was
            // a silent wrong value (engine paths route around this; direct
            // callers get a loud error instead).
            throw new JqTypeError("Cannot represent " + toJsonString() + " as BigDecimal");
        }
        if (isLong) {
            BigDecimal cached = cachedDecimal;
            if (cached == null) {
                cached = BigDecimal.valueOf(longVal);
                cachedDecimal = cached;
            }
            return cached;
        }
        if (decimalVal != null) return decimalVal;
        // Double-backed (specials already rejected above): lazy construct.
        BigDecimal cached = cachedDecimal;
        if (cached == null) {
            cached = BigDecimal.valueOf(rawDouble);
            cachedDecimal = cached;
        }
        return cached;
    }

    @Override
    public String toJsonString() {
        if (isLong) return Long.toString(longVal);
        if (isSpecial()) {
            return "null"; // JSON: NaN and Infinity -> null
        }
        if (decimalVal == null) {
            return formatDouble(rawDouble);
        }
        BigDecimal stripped = decimalVal.stripTrailingZeros();
        if (stripped.scale() <= 0) {
            if (stripped.scale() < -20) {
                return stripped.toString();
            }
            return stripped.toBigInteger().toString();
        }
        if (stripped.scale() > 20) {
            return stripped.toString();
        }
        return stripped.toPlainString();
    }

    /** Format a double for JSON output, matching jq's plain notation for common values. */
    private static String formatDouble(double d) {
        if (Double.doubleToRawLongBits(d) == Long.MIN_VALUE) return "-0";
        long asLong = (long) d;
        if ((double) asLong == d) {
            return Long.toString(asLong); // 3.0 -> "3"
        }
        if ((d > 1e-3 && d < 1e15) || (d < -1e-3 && d > -1e15)) {
            return Double.toString(d); // common range: plain notation
        }
        // Extreme range: use BigDecimal for consistent plain notation
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    @Override
    public void appendTo(StringBuilder sb) {
        if (isLong) {
            sb.append(longVal);
            return;
        }
        if (isSpecial()) {
            sb.append("null");
            return;
        }
        if (decimalVal == null) {
            appendDouble(sb, rawDouble);
            return;
        }
        BigDecimal stripped = decimalVal.stripTrailingZeros();
        if (stripped.scale() <= 0) {
            if (stripped.scale() < -20) {
                sb.append(stripped.toString());
            } else {
                sb.append(stripped.toBigInteger().toString());
            }
        } else if (stripped.scale() > 20) {
            sb.append(stripped.toString());
        } else {
            sb.append(stripped.toPlainString());
        }
    }

    @Override
    public void appendToBytes(BytOutput out) {
        if (isLong) {
            out.writeLong(longVal);
            return;
        }
        if (isSpecial()) {
            out.writeNull();
            return;
        }
        if (decimalVal == null) {
            appendDoubleBytes(out, rawDouble);
            return;
        }
        BigDecimal stripped = decimalVal.stripTrailingZeros();
        if (stripped.scale() <= 0) {
            if (stripped.scale() < -20) {
                out.writeAsciiString(stripped.toString());
            } else {
                out.writeAsciiString(stripped.toBigInteger().toString());
            }
        } else if (stripped.scale() > 20) {
            out.writeAsciiString(stripped.toString());
        } else {
            out.writeAsciiString(stripped.toPlainString());
        }
    }

    private static void appendDoubleBytes(BytOutput out, double d) {
        out.writeDouble(d);
    }

    /** Append a double to StringBuilder, matching jq's plain notation for common values. */
    public static void appendDouble(StringBuilder sb, double d) {
        if (Double.doubleToRawLongBits(d) == Long.MIN_VALUE) {
            sb.append("-0");
            return;
        }
        long asLong = (long) d;
        if ((double) asLong == d) {
            sb.append(asLong); // 3.0 -> "3"
        } else if ((d > 1e-3 && d < 1e15) || (d < -1e-3 && d > -1e15)) {
            // StringBuilder.append(double) formats directly into the buffer
            // without intermediate String allocation (unlike Double.toString())
            sb.append(d);
        } else {
            sb.append(BigDecimal.valueOf(d).stripTrailingZeros().toPlainString());
        }
    }

    @Override
    public String toString() { return toJsonString(); }

    @Override
    public boolean equals(Object o) {
        if (o instanceof JqNumber n) {
            if (this.isLong && n.isLong) return this.longVal == n.longVal;
            // Signed zero equals any zero (jq numeric equality: -0.0 == +0).
            // Field reads first — virtual calls only when -0.0 is involved.
            if (isNegativeZero() || n.isNegativeZero()) {
                return this.doubleValue() == 0.0 && n.doubleValue() == 0.0;
            }
            if (this.isSpecial() || n.isSpecial()) {
                return Double.compare(this.doubleValue(), n.doubleValue()) == 0;
            }
            // Fast path: both double-backed, compare doubles directly
            if (!this.isLong && this.decimalVal == null && !n.isLong && n.decimalVal == null) {
                return Double.compare(this.rawDouble, n.rawDouble) == 0;
            }
            // Fast path: long vs double-backed
            if (this.isLong && !n.isLong && n.decimalVal == null) {
                return Double.compare((double) this.longVal, n.rawDouble) == 0;
            }
            if (!this.isLong && this.decimalVal == null && n.isLong) {
                return Double.compare(this.rawDouble, (double) n.longVal) == 0;
            }
            return this.decimalValue().compareTo(n.decimalValue()) == 0;
        }
        return false;
    }

    @Override
    public int hashCode() {
        if (isLong) return Long.hashCode(longVal);
        if (isSpecial()) return Double.hashCode(rawDouble);
        // Zeroes hash alike (matches the equals collapse above).
        if (decimalVal == null && rawDouble == 0.0) return 0;
        if (decimalVal == null) return Double.hashCode(rawDouble);
        return decimalVal.stripTrailingZeros().hashCode();
    }
}

package io.hyperfoil.tools.jjq.value;

import java.math.BigDecimal;
import java.math.MathContext;

public final class JqNumber implements JqValue {
    private static final int CACHE_LOW = -128;
    private static final int CACHE_HIGH = 1023;
    private static final JqNumber[] CACHE = new JqNumber[CACHE_HIGH - CACHE_LOW + 1];
    static {
        for (int i = 0; i < CACHE.length; i++) {
            CACHE[i] = new JqNumber(i + CACHE_LOW, null, 0, true);
        }
    }

    private final long longVal;
    private final BigDecimal decimalVal;
    private final double rawDouble; // used for NaN/Infinity
    private final boolean isLong;
    private BigDecimal cachedDecimal; // lazy cache for long-backed numbers

    private JqNumber(long longVal, BigDecimal decimalVal, double rawDouble, boolean isLong) {
        this.longVal = longVal;
        this.decimalVal = decimalVal;
        this.rawDouble = rawDouble;
        this.isLong = isLong;
    }

    public static JqNumber of(long value) {
        if (value >= CACHE_LOW && value <= CACHE_HIGH) {
            return CACHE[(int) value - CACHE_LOW];
        }
        return new JqNumber(value, null, 0, true);
    }

    public static JqNumber of(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return new JqNumber(0, null, value, false);
        }
        if (value == Math.floor(value) && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE) {
            return of((long) value);
        }
        // Store double directly, defer BigDecimal construction until needed
        return new JqNumber(0, null, value, false);
    }

    public static JqNumber of(BigDecimal value) {
        try {
            long l = value.longValueExact();
            return of(l);
        } catch (ArithmeticException e) {
            return new JqNumber(0, value, value.doubleValue(), false);
        }
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
        if (isLong) {
            BigDecimal cached = cachedDecimal;
            if (cached == null) {
                cached = BigDecimal.valueOf(longVal);
                cachedDecimal = cached;
            }
            return cached;
        }
        if (decimalVal != null) return decimalVal;
        if (!isSpecial()) {
            // double-backed, not NaN/Infinity -- lazy construct
            BigDecimal cached = cachedDecimal;
            if (cached == null) {
                cached = BigDecimal.valueOf(rawDouble);
                cachedDecimal = cached;
            }
            return cached;
        }
        // NaN/Infinity can't be BigDecimal - return 0 as fallback
        return BigDecimal.ZERO;
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

    /** Append a double to StringBuilder, matching jq's plain notation for common values. */
    private static void appendDouble(StringBuilder sb, double d) {
        long asLong = (long) d;
        if ((double) asLong == d) {
            sb.append(asLong); // 3.0 -> "3"
        } else if ((d > 1e-3 && d < 1e15) || (d < -1e-3 && d > -1e15)) {
            sb.append(d); // common range: plain notation
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
        if (decimalVal == null) return Double.hashCode(rawDouble);
        return decimalVal.stripTrailingZeros().hashCode();
    }
}

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
        return new JqNumber(0, BigDecimal.valueOf(value), value, false);
    }

    public static JqNumber of(BigDecimal value) {
        try {
            long l = value.longValueExact();
            return of(l);
        } catch (ArithmeticException e) {
            return new JqNumber(0, value, value.doubleValue(), false);
        }
    }

    public boolean isIntegral() {
        if (isLong) return true;
        if (decimalVal == null) return false; // NaN/Infinity
        return decimalVal.stripTrailingZeros().scale() <= 0;
    }

    private boolean isSpecial() {
        return !isLong && decimalVal == null;
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
        if (isLong) return BigDecimal.valueOf(longVal);
        if (decimalVal != null) return decimalVal;
        // NaN/Infinity can't be BigDecimal - return 0 as fallback
        return BigDecimal.ZERO;
    }

    @Override
    public String toJsonString() {
        if (isLong) return Long.toString(longVal);
        if (isSpecial()) {
            // In JSON, NaN and Infinity are represented as null
            return "null";
        }
        BigDecimal stripped = decimalVal.stripTrailingZeros();
        if (stripped.scale() <= 0) {
            return stripped.toBigInteger().toString();
        }
        return stripped.toPlainString();
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
            return this.decimalValue().compareTo(n.decimalValue()) == 0;
        }
        return false;
    }

    @Override
    public int hashCode() {
        if (isLong) return Long.hashCode(longVal);
        if (isSpecial()) return Double.hashCode(rawDouble);
        return decimalVal.stripTrailingZeros().hashCode();
    }
}

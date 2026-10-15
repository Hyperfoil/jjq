package io.hyperfoil.tools.jjq.value;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class NumberParsingBoundaryTest {

    @Test
    void largeIntegerBoundariesStayExact() {
        BigInteger min = BigInteger.valueOf(Long.MIN_VALUE);
        BigInteger max = BigInteger.valueOf(Long.MAX_VALUE);
        for (int delta = -1000; delta <= 1000; delta++) {
            assertInteger(min.add(BigInteger.valueOf(delta)));
            assertInteger(max.add(BigInteger.valueOf(delta)));
        }
        assertInteger(new BigInteger("18446744073709551615"));
        assertInteger(new BigInteger("18446744073709551616"));
    }

    @Test
    void leadingZerosKeepLenientValuesAndStrictRejection() {
        for (String literal : new String[]{"9223372036854775807", "-9223372036854775808",
                "9223372036854775808", "-9223372036854775809", "18446744073709551615", "7"}) {
            BigInteger expected = new BigInteger(literal);
            String padded = literal.startsWith("-")
                    ? "-" + "0".repeat(32) + literal.substring(1)
                    : "0".repeat(32) + literal;
            byte[] bytes = padded.getBytes(StandardCharsets.UTF_8);
            assertIntegerValue(expected, JqValues.parse(padded));
            assertIntegerValue(expected, JqValues.parse(bytes));
            assertThrows(IllegalArgumentException.class, () -> JqValues.parseStrict(padded));
            assertThrows(IllegalArgumentException.class, () -> JqValues.parseStrict(bytes));
        }
    }

    @Test
    void randomLargeIntegersMatchBigDecimalReference() {
        Random random = new Random(149);
        for (int i = 0; i < 1000; i++) {
            BigInteger expected = new BigInteger(64 + random.nextInt(256), random);
            assertInteger(random.nextBoolean() ? expected.negate() : expected);
        }
    }

    @Test
    void fallbackBufferReuseDoesNotMixLiterals() {
        assertInteger(new BigInteger("9".repeat(5000)));
        assertInteger(new BigInteger("18446744073709551615"));
        assertInteger(BigInteger.valueOf(Long.MIN_VALUE));
        assertInteger(new BigInteger("123456789012345678901234567890123456789012345678901234567890123456789"));
        assertInteger(BigInteger.valueOf(Long.MAX_VALUE));
    }

    @Test
    void decimalFallbackKeepsPrecisionAndFloatProvenance() {
        for (String literal : new String[]{"1e20", "9223372036854775807.125",
                "18446744073709551615e-2", "1.234567890123456789e20", "1e-340"}) {
            BigDecimal expected = new BigDecimal(literal);
            JqNumber fromString = assertInstanceOf(JqNumber.class, JqValues.parseStrict(literal));
            JqNumber fromBytes = assertInstanceOf(JqNumber.class,
                    JqValues.parseStrict(literal.getBytes(StandardCharsets.UTF_8)));
            assertEquals(0, expected.compareTo(fromString.decimalValue()), literal);
            assertEquals(0, expected.compareTo(fromBytes.decimalValue()), literal);
            assertTrue(fromString.isFloatLexed(), literal);
            assertTrue(fromBytes.isFloatLexed(), literal);
        }
        assertEquals("100000000000000000000", JqValues.parseStrict("1e20").toJsonString());
        assertEquals(Long.valueOf(100), JqValues.parseStrict("100.0").toJavaObject());
    }

    @Test
    void negativeIntegerZeroKeepsSignAtAnyWidth() {
        for (int width : new int[]{1, 18, 19, 20, 64, 5000}) {
            String literal = "-" + "0".repeat(width);
            JqNumber fromString = assertInstanceOf(JqNumber.class, JqValues.parse(literal));
            JqNumber fromBytes = assertInstanceOf(JqNumber.class,
                    JqValues.parse(literal.getBytes(StandardCharsets.UTF_8)));
            assertTrue(fromString.isNegativeZero(), "width " + width);
            assertTrue(fromBytes.isNegativeZero(), "width " + width);
            assertTrue(fromString.isFloatLexed(), "width " + width);
            assertTrue(fromBytes.isFloatLexed(), "width " + width);
            assertEquals(Double.valueOf(-0.0), fromString.toJavaObject());
            assertEquals("-0", fromBytes.toJsonString());
        }
    }

    @Test
    void integralJavaConversionKeepsTypesAcrossDecimalScales() {
        for (String literal : new String[]{"18446744073709551615.000", "-9223372036854775809.0",
                "1e20", "1000000000000000000000000000000000000000"}) {
            BigInteger expected = new BigDecimal(literal).toBigIntegerExact();
            assertEquals(expected, JqValues.parseStrict(literal).toJavaObject(), literal);
        }
        assertEquals(Long.valueOf(0), JqValues.parseStrict("0e40").toJavaObject());
        assertEquals(Long.valueOf(0), JqValues.parseStrict("0.000000000000000000000000").toJavaObject());
        assertEquals(Long.valueOf(Long.MAX_VALUE), JqValues.parseStrict("9223372036854775807.000").toJavaObject());
        assertEquals(Long.valueOf(Long.MIN_VALUE), JqValues.parseStrict("-9223372036854775808.000").toJavaObject());
    }

    private static void assertInteger(BigInteger expected) {
        String text = expected.toString();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        assertIntegerValue(expected, JqValues.parseStrict(text));
        assertIntegerValue(expected, JqValues.parseStrict(bytes));
        assertIntegerValue(expected, JqValues.parse(text));
        assertIntegerValue(expected, JqValues.parse(bytes));
    }

    private static void assertIntegerValue(BigInteger expected, JqValue parsed) {
        JqNumber number = assertInstanceOf(JqNumber.class, parsed);
        assertEquals(0, new BigDecimal(expected).compareTo(number.decimalValue()), expected.toString());
        assertFalse(number.isFloatLexed(), expected.toString());
        Object javaValue = number.toJavaObject();
        if (expected.bitLength() < Long.SIZE) {
            assertEquals(Long.valueOf(expected.longValueExact()), javaValue, expected.toString());
        } else {
            assertEquals(expected, javaValue, expected.toString());
        }
    }
}

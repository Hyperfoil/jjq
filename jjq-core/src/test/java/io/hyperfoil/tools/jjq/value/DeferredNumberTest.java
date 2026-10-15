package io.hyperfoil.tools.jjq.value;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deferred beyond-long integers must be observably identical to their eager
 * {@code JqNumber.of(new BigDecimal(...))} counterparts: same rendering,
 * equality, hashing, ordering, arithmetic, and Java conversion. The tests are
 * differential on purpose — they pin equivalence with the eager form, not
 * absolute arithmetic semantics (which are a separate concern).
 */
class DeferredNumberTest {

    private static final String[] HUGE = {
        "9223372036854775807",
        "-9223372036854775808",
        "9223372036854775808",
        "-9223372036854775809",
        "18446744073709551615",
        "18446744073709551616",
        "9999999999999999999",
        "100000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
        "-123456789012345678901234567890123456789012345678901234567890123456789",
        "9".repeat(5000),
        "1" + "0".repeat(4999),
        "18446744073709551615000",
    };

    /** Trailing zeros beyond 20 render in scientific notation, like the eager form. */
    private static boolean rendersPlain(String literal) {
        String digits = literal.startsWith("-") ? literal.substring(1) : literal;
        int zeros = 0;
        while (zeros < digits.length() && digits.charAt(digits.length() - 1 - zeros) == '0') zeros++;
        return zeros <= 20;
    }

    private static JqNumber eager(String literal) {
        return JqNumber.of(new BigDecimal(literal));
    }

    private static JqNumber parsed(String literal) {
        return assertInstanceOf(JqNumber.class, JqValues.parseStrict(literal));
    }

    private static JqNumber parsedBytes(String literal) {
        return assertInstanceOf(JqNumber.class,
                JqValues.parseStrict(literal.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertSameAsEager(String literal) {
        JqNumber expected = eager(literal);
        for (JqNumber actual : new JqNumber[]{parsed(literal), parsedBytes(literal)}) {
            assertEquals(expected, actual, literal);
            assertEquals(expected.hashCode(), actual.hashCode(), literal);
            assertEquals(expected.toJsonString(), actual.toJsonString(), literal);
            assertEquals(expected.decimalValue(), actual.decimalValue(), literal);
            assertEquals(expected.doubleValue(), actual.doubleValue(), literal);
            assertEquals(expected.longValue(), actual.longValue(), literal);
            assertEquals(expected.toJavaObject(), actual.toJavaObject(), literal);
            assertEquals(expected.isIntegral(), actual.isIntegral(), literal);
            assertEquals(expected.isNegativeZero(), actual.isNegativeZero(), literal);
            assertEquals(expected.isLongBacked(), actual.isLongBacked(), literal);
            assertEquals(0, expected.compareTo(actual), literal);
            assertEquals(0, actual.compareTo(expected), literal);
            // Rendering matches the normalized literal exactly, except for
            // long trailing-zero runs which render scientifically like eager.
            if (rendersPlain(literal)) {
                assertEquals(literal, actual.toJsonString(), literal);
            }
            StringBuilder sb = new StringBuilder();
            actual.appendTo(sb);
            assertEquals(expected.toJsonString(), sb.toString(), literal);
        }
    }

    @Test
    void valuesMatchEagerForm() {
        for (String literal : HUGE) {
            assertSameAsEager(literal);
        }
    }

    @Test
    void orderingMatchesEagerForm() {
        String[] values = {
            "-" + "9".repeat(5000),
            "-18446744073709551616",
            "-9223372036854775809",
            "-9223372036854775808",
            "-7",
            "0",
            "7",
            "9223372036854775807",
            "9223372036854775808",
            "18446744073709551615",
            "18446744073709551616",
            "9".repeat(5000),
        };
        // Pairwise ordering agrees with the eager form (including its
        // double-comparison collapse of values beyond 2^53).
        for (String left : values) {
            for (String right : values) {
                int expected = Integer.signum(eager(left).compareTo(eager(right)));
                assertEquals(expected, Integer.signum(parsed(left).compareTo(parsed(right))),
                        left + " vs " + right);
            }
        }
        // Sorting agrees element-wise with sorting the eager forms.
        List<JqValue> eagerList = new ArrayList<>();
        List<JqValue> parsedList = new ArrayList<>();
        for (int i = values.length - 1; i >= 0; i--) {
            eagerList.add(eager(values[i]));
            parsedList.add(parsed(values[i]));
        }
        Collections.shuffle(eagerList, new java.util.Random(149));
        Collections.shuffle(parsedList, new java.util.Random(149));
        Collections.sort(eagerList, JqValue::compareTo);
        Collections.sort(parsedList, JqValue::compareTo);
        for (int i = 0; i < values.length; i++) {
            assertEquals(eagerList.get(i).toJsonString(), parsedList.get(i).toJsonString(), "index " + i);
        }
    }

    @Test
    void arithmeticMatchesEagerForm() {
        String[] operands = {"18446744073709551615", "18446744073709551616", "7", "-3",
            "9999999999999999999", "100000000000000000000"};
        for (String left : operands) {
            for (String right : operands) {
                JqNumber eagerLeft = eager(left);
                JqNumber eagerRight = eager(right);
                JqNumber parsedLeft = parsed(left);
                JqNumber parsedRight = parsed(right);
                assertOutcome(() -> eagerLeft.add(eagerRight).toJsonString(),
                        () -> parsedLeft.add(parsedRight).toJsonString(), left + "+" + right);
                assertOutcome(() -> eagerLeft.subtract(eagerRight).toJsonString(),
                        () -> parsedLeft.subtract(parsedRight).toJsonString(), left + "-" + right);
                assertOutcome(() -> eagerLeft.multiply(eagerRight).toJsonString(),
                        () -> parsedLeft.multiply(parsedRight).toJsonString(), left + "*" + right);
                assertOutcome(() -> eagerLeft.divide(eagerRight).toJsonString(),
                        () -> parsedLeft.divide(parsedRight).toJsonString(), left + "/" + right);
                assertOutcome(() -> eagerLeft.modulo(eagerRight).toJsonString(),
                        () -> parsedLeft.modulo(parsedRight).toJsonString(), left + "%" + right);
                assertEquals(eagerLeft.negate().toJsonString(),
                        parsedLeft.negate().toJsonString(), "-(" + left + ")");
            }
        }
    }

    /**
     * Compares outcomes differentially: equal results, or the same exception
     * type (e.g. the truncated-long zero-divisor check fires identically).
     */
    private static void assertOutcome(java.util.function.Supplier<String> eagerOp,
                                      java.util.function.Supplier<String> parsedOp, String label) {
        String expected;
        try {
            expected = eagerOp.get();
        } catch (RuntimeException e) {
            assertThrows(e.getClass(), parsedOp::get, label);
            return;
        }
        assertEquals(expected, parsedOp.get(), label);
    }

    @Test
    void mixedArithmeticWithSmallOperands() {
        JqNumber huge = parsed("18446744073709551615");
        JqNumber one = assertInstanceOf(JqNumber.class, JqValues.parseStrict("1"));
        // Differential against eager: whatever the eager integral fast path
        // computes from truncated longs must match exactly.
        assertEquals(eager("18446744073709551615").add(eager("1")).toJsonString(),
                huge.add(one).toJsonString());
        assertEquals(eager("18446744073709551615").multiply(eager("2")).toJsonString(),
                huge.multiply(assertInstanceOf(JqNumber.class, JqValues.parseStrict("2"))).toJsonString());
    }

    @Test
    void sourceTextCarriesDeferredValue() {
        JqNumber withText = parsed("18446744073709551615").withSourceText("u64");
        assertEquals("u64", withText.sourceText());
        assertEquals("18446744073709551615", withText.toJsonString());
        assertEquals(eager("18446744073709551615"), withText);
        assertEquals(eager("18446744073709551615").hashCode(), withText.hashCode());
    }

    @Test
    void byteSerializationRoundTrips() {
        for (String literal : new String[]{"18446744073709551615", "9".repeat(500),
                "-123456789012345678901234567890"}) {
            JqNumber number = parsed(literal);
            BytOutput out = new BytOutput(64);
            number.appendToBytes(out);
            assertEquals(literal, new String(out.toByteArray(), StandardCharsets.UTF_8), literal);
        }
    }
}

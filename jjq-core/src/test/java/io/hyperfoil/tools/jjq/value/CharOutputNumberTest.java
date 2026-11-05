package io.hyperfoil.tools.jjq.value;

import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.Random;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class CharOutputNumberTest {
    @Test
    void finiteNumbersKeepExistingRenderingAcrossBitPatterns() {
        double[] edges = {-0.0, 0.0, 3.0, 98.5, -2.5, Double.MIN_VALUE, -Double.MIN_VALUE,
                Double.MAX_VALUE, -Double.MAX_VALUE, 1e-3, Math.nextDown(1e-3), Math.nextUp(1e-3),
                -1e-3, Math.nextUp(-1e-3), Math.nextDown(-1e-3), 1e15, Math.nextDown(1e15),
                Math.nextUp(1e15), -1e15, Math.nextUp(-1e15), Math.nextDown(-1e15),
                (double) Long.MIN_VALUE, (double) Long.MAX_VALUE};
        try (CharOutput out = new CharOutput(32)) {
            for (double value : edges) assertFinite(out, value, 31);
            Random random = new Random(20705);
            for (int i = 0; i < 5000; i++) {
                double value = Double.longBitsToDouble(random.nextLong());
                if (Double.isFinite(value)) assertFinite(out, value, i & 31);
            }
        }
    }

    @Test
    void nonFiniteInputsKeepExistingErrorsAndOutput() {
        try (CharOutput out = new CharOutput(32)) {
            for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                StringBuilder expected = new StringBuilder("prefix");
                var failure = assertThrows(RuntimeException.class, () -> JqNumber.appendDouble(expected, value));
                out.append("prefix");
                var actual = assertThrows(failure.getClass(), () -> out.appendDouble(value));
                assertEquals(failure.getMessage(), actual.getMessage());
                assertEquals(expected.toString(), out.toString());
                out.close();
            }
        }
    }

    @Test
    void repeatedNumbersKeepSegmentEdgesAndOwnedResults() throws Exception {
        try (CharOutput out = new CharOutput(32)) {
            StringBuilder expected = new StringBuilder("x".repeat(31));
            out.append(expected.toString());
            for (double value : new double[]{1.25, -7.125, 12345.5, Math.nextUp(1e-3),
                    Math.nextDown(1e15), -0.0, Double.MIN_VALUE, 2.75}) {
                expected.append('|');
                JqNumber.appendDouble(expected, value);
                out.append('|');
                out.appendDouble(value);
            }
            String retained = out.toString();
            assertEquals(expected.toString(), retained);
            StringWriter writer = new StringWriter();
            out.writeTo(writer);
            assertEquals(retained, writer.toString());
            out.close();
            out.appendDouble(8.125);
            assertEquals("8.125", out.toString());
            assertEquals(expected.toString(), retained);
        }
    }

    @Test
    void nestedAndThreadedNumericOutputsRemainIndependent() throws Exception {
        try (CharOutput outer = CharOutput.acquire()) {
            outer.appendDouble(98.5);
            try (CharOutput inner = CharOutput.acquire()) {
                inner.appendDouble(0.125);
                assertEquals("0.125", inner.toString());
            }
            outer.append('|');
            outer.appendDouble(2.25);
            assertEquals("98.5|2.25", outer.toString());
        }
        try (var pool = Executors.newFixedThreadPool(2)) {
            var left = pool.submit(() -> renderSequence(1.25));
            var right = pool.submit(() -> renderSequence(-2.75));
            assertEquals("1.25|1.25|1.25", left.get());
            assertEquals("-2.75|-2.75|-2.75", right.get());
        }
    }

    private static void assertFinite(CharOutput out, double value, int padding) {
        String prefix = "x".repeat(padding);
        StringBuilder expected = new StringBuilder(prefix);
        JqNumber.appendDouble(expected, value);
        out.append(prefix);
        out.appendDouble(value);
        assertEquals(expected.toString(), out.toString(), () -> "bits " + Long.toHexString(Double.doubleToRawLongBits(value)));
        out.close();
    }

    private static String renderSequence(double value) {
        try (CharOutput out = CharOutput.acquire()) {
            out.appendDouble(value);
            out.append('|');
            out.appendDouble(value);
            out.append('|');
            out.appendDouble(value);
            return out.toString();
        }
    }
}

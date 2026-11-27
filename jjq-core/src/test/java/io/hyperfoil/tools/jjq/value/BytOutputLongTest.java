package io.hyperfoil.tools.jjq.value;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Boundary pin for {@link BytOutput#writeLong(long)}: every digit-count
 * edge of the count-then-emit form (powers of ten and their neighbors),
 * the sign edges, the int/long boundary where a narrowed remainder
 * computation would overflow, and a randomized differential against
 * {@link Long#toString(long)} in buffers small enough to force growth
 * mid-write.
 */
class BytOutputLongTest {

    @Test
    void digitCountEdgesMatchLongToString() {
        long[] edges = {0, 1, 9, 10, 11, 99, 100, 101, 999, 1000, 1001,
                Long.MAX_VALUE, Long.MIN_VALUE, -1, -10, -100, -1000,
                Integer.MIN_VALUE, Integer.MAX_VALUE, -(long) Integer.MAX_VALUE};
        for (long p = 10; p <= 1_000_000_000_000_000_000L; p *= 10) {
            edges = append(edges, p - 1, p, p + 1, -p, -(p - 1), -(p + 1));
        }
        // int/long boundary: an int cast in paired emission overflows
        // past ~2^31, and the digit-pair remainder past ~10^11.
        edges = append(edges, (1L << 31) - 1, 1L << 31, (1L << 31) + 1,
                (1L << 32) - 1, 1L << 32, (1L << 32) + 1,
                99_999_999_999L, 100_000_000_000L, 100_000_000_001L,
                999_999_999_999L, 1_000_000_000_000L, 1_000_000_000_001L);
        for (long v : edges) {
            assertWrites(v, 2); // forces growth from the first write
            assertWrites(v, 24); // fits without growth
        }
    }

    @Test
    void writesAppendInSequenceAndForceGrowth() {
        BytOutput out = new BytOutput(3);
        StringBuilder expected = new StringBuilder();
        long[] sequence = {0, 7, 42, -42, 1_000_000, Long.MAX_VALUE,
                Long.MIN_VALUE, -Long.MAX_VALUE, 123456789012345L,
                -123456789012345L, 987654321098765432L};
        for (long v : sequence) {
            out.writeByte('|');
            expected.append('|');
            out.writeLong(v);
            expected.append(v);
        }
        assertEquals(expected.toString(), ascii(out));
    }

    @Test
    void randomLongsMatchLongToString() {
        Random random = new Random(20716);
        BytOutput out = new BytOutput(4);
        for (int i = 0; i < 10_000; i++) {
            long v = random.nextLong();
            out.reset();
            out.writeLong(v);
            assertEquals(Long.toString(v), ascii(out), () -> "value " + v);
            // Negation exercises the sign branch on the same magnitude.
            out.reset();
            out.writeLong(-v);
            assertEquals(Long.toString(-v), ascii(out), () -> "value " + -v);
        }
    }

    private static void assertWrites(long v, int capacity) {
        BytOutput out = new BytOutput(capacity);
        out.writeLong(v);
        assertEquals(Long.toString(v), ascii(out),
                () -> "capacity " + capacity + " value " + v);
    }

    private static String ascii(BytOutput out) {
        return new String(out.toByteArray(), StandardCharsets.US_ASCII);
    }

    private static long[] append(long[] base, long... additions) {
        long[] out = Arrays.copyOf(base, base.length + additions.length);
        System.arraycopy(additions, 0, out, base.length, additions.length);
        return out;
    }
}

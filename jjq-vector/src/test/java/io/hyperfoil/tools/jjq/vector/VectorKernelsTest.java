package io.hyperfoil.tools.jjq.vector;

import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage-0 dispatch pins (issue #187): both arms agree on a fixed corpus
 * plus randomized inputs, and the flag forces the fallback path.
 * (Both arms share one loop for now, so this guards the dispatch shape
 * and the contract edges; stage 1 adds speed plus benchmarks.)
 */
class VectorKernelsTest {

    @AfterEach
    void clearFlag() {
        System.clearProperty(VectorKernels.ENABLE_PROPERTY);
    }

    private static void withFlag(String value, Runnable body) {
        String prev = System.getProperty(VectorKernels.ENABLE_PROPERTY);
        try {
            if (value == null) System.clearProperty(VectorKernels.ENABLE_PROPERTY);
            else System.setProperty(VectorKernels.ENABLE_PROPERTY, value);
            body.run();
        } finally {
            if (prev == null) System.clearProperty(VectorKernels.ENABLE_PROPERTY);
            else System.setProperty(VectorKernels.ENABLE_PROPERTY, prev);
        }
    }

    /** Independent oracle: different loop shape, same contract. */
    private static int oracle(byte[] v, int off, int len) {
        int i = off;
        int end = off + len;
        while (i < end) {
            byte b = v[i];
            if (b < 0x20 || b == '"' || b == '\\') return i - off;
            i++;
        }
        return len;
    }

    @Test
    void flagForcesFallback() {
        withFlag("false", () -> assertFalse(VectorKernels.isSupported()));
        withFlag("true", () -> assertEquals(VectorSupport.preferredBits() >= 128,
                VectorKernels.isSupported()));
    }

    @Test
    void contractEdges() {
        byte[][] cases = {
                {},
                {'a', 'b', 'c'},
                {'"', 'a'},
                {'a', '\\'},
                {'a', '\n'},
                {0x00},
                {0x1F},
                {0x20},
                {0x7F},
                {(byte) 0x80},
                {(byte) 0xFF},
                {'"', '\\', '\n', 0x01},
        };
        for (boolean forced : new boolean[]{false, true}) {
            withFlag(forced ? "false" : null, () -> {
                for (byte[] c : cases) {
                    assertEquals(oracle(c, 0, c.length),
                            VectorKernels.scanFirstEscape(c, 0, c.length));
                }
            });
        }
    }

    @Test
    void offsetsAndEmptyRanges() {
        byte[] v = {'x', '"', 'y'};
        for (boolean forced : new boolean[]{false, true}) {
            withFlag(forced ? "false" : null, () -> {
                assertEquals(1, VectorKernels.scanFirstEscape(v, 0, 3));
                assertEquals(0, VectorKernels.scanFirstEscape(v, 1, 1));
                assertEquals(1, VectorKernels.scanFirstEscape(v, 2, 1));
                assertEquals(0, VectorKernels.scanFirstEscape(v, 3, 0));
                assertEquals(0, VectorKernels.scanFirstEscape(v, 0, 0));
            });
        }
    }

    @Test
    void randomizedAgreement() {
        var rnd = new Random(187);
        for (int round = 0; round < 500; round++) {
            byte[] v = new byte[rnd.nextInt(300)];
            rnd.nextBytes(v);
            // Sprinkle escapes so both outcomes occur.
            if (v.length > 0 && rnd.nextBoolean()) v[rnd.nextInt(v.length)] = (byte) '"';
            int off = v.length == 0 ? 0 : rnd.nextInt(v.length);
            int len = v.length - off;
            int expected = oracle(v, off, len);
            withFlag("false", () ->
                    assertEquals(expected, VectorKernels.scanFirstEscape(v, off, len)));
            withFlag(null, () ->
                    assertEquals(expected, VectorKernels.scanFirstEscape(v, off, len)));
        }
    }

    @Test
    void rejectsBadRanges() {
        byte[] v = {'a'};
        assertThrows(IndexOutOfBoundsException.class,
                () -> VectorKernels.scanFirstEscape(v, 0, 2));
        assertThrows(IndexOutOfBoundsException.class,
                () -> VectorKernels.scanFirstEscape(v, -1, 1));
    }
}

package io.hyperfoil.tools.jjq.vector;

import io.hyperfoil.tools.jjq.value.BytOutput;
import io.hyperfoil.tools.jjq.value.JsonEscapeCopyKernel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Differential pin for the vector escape-copy kernel (#1B): every shape
 * must agree byte-for-byte with a scalar oracle mirroring the byte-level
 * scanner in {@code BytOutput.escapeWindowBytes}, plus an end-to-end run
 * through {@link BytOutput} itself with the kernel discovered via
 * ServiceLoader (jjq-vector's test classpath carries the registration).
 */
class VectorEscapeCopyKernelTest {

    private static final byte POISON = (byte) 0xAB;

    private final VectorEscapeCopyKernel kernel = new VectorEscapeCopyKernel();

    /** Scalar oracle mirroring BytOutput's byte-level scanner exactly. */
    private static int oracleCopy(byte[] a, int start, int end) {
        for (int i = start; i < end; i++) {
            if (a[i] == '?') return -1; // encode unverifiable: same rule
        }
        int i = start;
        for (; i < end; i++) {
            int b = a[i] & 0xFF;
            if (b < 0x20 || b == '"' || b == '\\') break;
        }
        return i - start;
    }

    private void assertKernelMatchesOracle(byte[] a, int start, int end) {
        int expCount = oracleCopy(a, start, end);
        if (expCount < 0) {
            byte[] actual = new byte[end - start + 8];
            Arrays.fill(actual, POISON);
            assertEquals(-1, kernel.copyCleanUtf8(a, start, end, actual, 3, false),
                    () -> "expected unverifiable ('?' present), len=" + (end - start));
            // Whatever was written before the 0x3F must be a verbatim
            // prefix of the input — the caller discards it by not
            // advancing, so garbage past a correct prefix is a bug.
            for (int k = 0; k < actual.length; k++) {
                if (actual[k] != POISON) {
                    assertEquals(a[start + k - 3], actual[k],
                            "written byte is not the verbatim input prefix");
                }
            }
            return;
        }
        byte[] expected = new byte[end - start + 8];
        byte[] actual = new byte[end - start + 8];
        Arrays.fill(expected, POISON);
        Arrays.fill(actual, POISON);
        int expIdx = 0;
        for (int i = start; i < start + expCount; i++) {
            expected[3 + expIdx++] = a[i];
        }
        int actCount = kernel.copyCleanUtf8(a, start, end, actual, 3, false);
        assertEquals(expCount, actCount, () -> "count mismatch, len=" + (end - start));
        assertArrayEquals(expected, actual, () -> "bytes mismatch (or over-write past count), len=" + (end - start));
    }

    // ------------------------------------------------------------------
    // Route pinning
    // ------------------------------------------------------------------

    @Test
    void serviceLoaderDiscoversKernel() {
        var it = ServiceLoader.load(JsonEscapeCopyKernel.class).iterator();
        assertTrue(it.hasNext(), "jjq-vector service registration missing");
        assertTrue(it.next() instanceof VectorEscapeCopyKernel);
    }

    @Test
    void bytOutputDiscoversTheKernel() throws Exception {
        // BytOutput loads the kernel once at class-init; the test classpath
        // carries jjq-vector's registration, so the dispatch must find it.
        Field field = BytOutput.class.getDeclaredField("ESCAPE_KERNEL");
        field.setAccessible(true);
        Object loaded = field.get(null);
        assertTrue(loaded instanceof VectorEscapeCopyKernel,
                "BytOutput.ESCAPE_KERNEL should resolve to the vector kernel on this classpath");
    }

    @Test
    void kernelIsEnabledOnVectorHardware() {
        assumeTrue(VectorKernels.isSupported());
        assertTrue(kernel.isEnabled());
    }

    // ------------------------------------------------------------------
    // Differential: break positions and lane edges
    // ------------------------------------------------------------------

    @Test
    void slowByteAtEveryPosition() {
        // Every slow byte class planted at every index of a 160-byte
        // clean window (ASCII plus multi-byte UTF-8): crosses 16/32/64-
        // lane boundaries at every residue.
        byte[] slowBytes = {'"', '\\', '\n', '\r', '\t', '\b', '\f', 0x01, 0x1F, 0x00};
        for (byte slow : slowBytes) {
            for (int at = 0; at < 160; at++) {
                byte[] a = cleanBytes(160);
                a[at] = slow;
                assertKernelMatchesOracle(a, 0, 160);
            }
        }
    }

    @Test
    void questionMarkAtEveryPositionReturnsUnverifiable() {
        // A '?' anywhere — before or after the first slow byte, in the
        // break vector, or in the tail — must report -1 untrusted:
        // getBytes(UTF_8) cannot be told apart from a replaced lone
        // surrogate.
        for (int at = 0; at < 160; at++) {
            byte[] a = cleanBytes(160);
            a[at] = '?';
            assertKernelMatchesOracle(a, 0, 160);
        }
        for (int at = 0; at < 160; at++) {
            byte[] a = cleanBytes(160);
            a[at] = '"';
            a[(at + 80) % 160] = '?'; // question mark past the break
            assertKernelMatchesOracle(a, 0, 160);
        }
        for (int at = 155; at < 160; at++) { // inside the masked tail
            byte[] a = cleanBytes(160);
            a[at] = '?';
            assertKernelMatchesOracle(a, 0, 160);
        }
    }

    @Test
    void trustedModeTreatsQuestionMarksAsClean() {
        // After the caller verifies the encode lossless, '?' is a
        // genuine question mark: the kernel copies through it.
        for (int at = 0; at < 160; at += 7) {
            byte[] a = cleanBytes(160);
            a[at] = '?';
            byte[] expected = new byte[168];
            byte[] actual = new byte[168];
            java.util.Arrays.fill(expected, POISON);
            java.util.Arrays.fill(actual, POISON);
            System.arraycopy(a, 0, expected, 3, 160);
            int copied = kernel.copyCleanUtf8(a, 0, 160, actual, 3, true);
            assertEquals(160, copied, "trusted clean run copies everything");
            assertArrayEquals(expected, actual);
        }
    }

    @Test
    void cleanRunsOfEveryLength() {
        for (int len = 0; len <= 200; len++) {
            assertKernelMatchesOracle(cleanBytes(len), 0, len);
        }
        for (int len = 0; len <= 200; len++) {
            assertKernelMatchesOracle(cleanBytes(len + 10), 5, len + 5);
        }
    }

    @Test
    void everyByteClassifiesLikeTheOracle() {
        // All 256 byte values at a position inside the second vector:
        // a mis-classified lane (e.g. unsigned-vs-signed confusion on
        // the 0x20 boundary or multi-byte leads) shows up here.
        for (int b = 0; b <= 0xFF; b++) {
            byte[] a = cleanBytes(96);
            a[33] = (byte) b;
            assertKernelMatchesOracle(a, 0, 96);
        }
    }

    @Test
    void randomFuzz() {
        Random rnd = new Random(20718);
        for (int i = 0; i < 10_000; i++) {
            int len = 1 + rnd.nextInt(300);
            byte[] a = new byte[len];
            for (int j = 0; j < len; j++) {
                int pick = rnd.nextInt(100);
                if (pick < 65) a[j] = (byte) (' ' + rnd.nextInt(95)); // ASCII
                else if (pick < 72) a[j] = (byte) rnd.nextInt(0x20);   // controls
                else if (pick < 80) a[j] = (byte) (0x80 + rnd.nextInt(0x80)); // multi-byte payload
                else if (pick < 85) a[j] = '"';
                else if (pick < 88) a[j] = '\\';
                else if (pick < 91) a[j] = '?';                        // unverifiable
                else a[j] = (byte) (0xC0 + rnd.nextInt(0x40));         // lead bytes
            }
            assertKernelMatchesOracle(a, 0, len);
        }
    }

    // ------------------------------------------------------------------
    // End-to-end through BytOutput with the kernel on the classpath
    // ------------------------------------------------------------------

    @Test
    void escapeJsonToBytesMatchesOracleEndToEnd() throws Exception {
        bytOutputDiscoversTheKernel();
        String[] values = {
                "x".repeat(64), // at the dispatch cutoff
                "x".repeat(65),
                "x".repeat(2048),
                "x".repeat(8192), // exactly one scratch window
                "x".repeat(8193), // first multi-window value
                "x".repeat(20000), // several windows
                "x".repeat(100) + "\"" + "y".repeat(100),
                "x".repeat(100) + "\n" + "y".repeat(100),
                "x".repeat(8300) + "\"" + "y".repeat(200), // break past the first window
                "é".repeat(200), // non-ASCII from the start
                "x".repeat(200) + "\u4E2D\u6587" + "y".repeat(200),
                "x".repeat(100) + "\uD83D\uDE00" + "y".repeat(100), // surrogate pair
                "x".repeat(100) + "\uD83D" + "y".repeat(100), // lone surrogate: malformed, char route
                "\uD83D\uDE00".repeat(100), // all supplementary
                "x".repeat(100) + "\uDE00" + "y".repeat(100), // lone low surrogate
                "",
                "short",
                "quote\"in\"the\"middle of a longer string value for the kernel path",
        };
        for (String s : values) {
            BytOutput out = new BytOutput(64);
            out.escapeJsonToBytes(s);
            assertArrayEquals(oracleEscape(s.toCharArray(), 0, s.length()),
                    out.toByteArray(), () -> "end-to-end mismatch, len=" + s.length());
        }
    }

    /**
     * Hand-rolled JSON string-body oracle over UTF-16 code units —
     * the same shape as the exhaustive test in jjq-core: escapes for
     * the slow set, CESU-8 3-byte form for lone surrogates, 4-byte
     * form for pairs.
     */
    private static byte[] oracleEscape(char[] a, int start, int end) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(end - start + 8);
        for (int i = start; i < end; i++) {
            char c = a[i];
            switch (c) {
                case '"' -> { out.write('\\'); out.write('"'); }
                case '\\' -> { out.write('\\'); out.write('\\'); }
                case '\b' -> { out.write('\\'); out.write('b'); }
                case '\f' -> { out.write('\\'); out.write('f'); }
                case '\n' -> { out.write('\\'); out.write('n'); }
                case '\r' -> { out.write('\\'); out.write('r'); }
                case '\t' -> { out.write('\\'); out.write('t'); }
                default -> {
                    if (c < 0x20) {
                        out.write('\\');
                        out.write('u');
                        out.write('0');
                        out.write('0');
                        out.write(Character.forDigit((c >> 4) & 0xF, 16));
                        out.write(Character.forDigit(c & 0xF, 16));
                    } else if (Character.isHighSurrogate(c) && i + 1 < end
                            && Character.isLowSurrogate(a[i + 1])) {
                        int cp = Character.toCodePoint(c, a[++i]);
                        out.write(0xF0 | (cp >> 18));
                        out.write(0x80 | ((cp >> 12) & 0x3F));
                        out.write(0x80 | ((cp >> 6) & 0x3F));
                        out.write(0x80 | (cp & 0x3F));
                    } else if (c < 0x80) {
                        out.write(c);
                    } else if (c < 0x800) {
                        out.write(0xC0 | (c >> 6));
                        out.write(0x80 | (c & 0x3F));
                    } else {
                        out.write(0xE0 | (c >> 12));
                        out.write(0x80 | ((c >> 6) & 0x3F));
                        out.write(0x80 | (c & 0x3F));
                    }
                }
            }
        }
        return out.toByteArray();
    }

    /** Clean UTF-8 bytes: printable ASCII and whole multi-byte sequences. */
    private static byte[] cleanBytes(int len) {
        byte[] a = new byte[len];
        Random rnd = new Random(20719);
        String pool = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .,-_é中";
        byte[] encoded = pool.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < len; i++) {
            a[i] = encoded[rnd.nextInt(encoded.length)];
        }
        return a;
    }
}

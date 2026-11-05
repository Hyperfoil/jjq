package io.hyperfoil.tools.jjq.value;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class JsonByteStringUnescapeTest {
    @Test
    void everyUtf8ScalarSurvivesEscapedByteMaterialization() {
        primeWorkspace();
        String padding = "x".repeat(300);
        for (int first = 0x80; first <= Character.MAX_CODE_POINT; first += 128) {
            StringBuilder text = new StringBuilder(padding);
            for (int code = first; code < first + 128 && code <= Character.MAX_CODE_POINT; code++) {
                if (code < 0xD800 || code > 0xDFFF) text.appendCodePoint(code);
            }
            String raw = text + "\\n";
            String expected = text + "\n";
            byte[] json = ("\"" + raw + "\"").getBytes(StandardCharsets.UTF_8);
            assertEquals(expected, JqValues.parseStrict(json).stringValue(), "scalar batch " + first);
            byte[] range = ("prefix" + raw + "suffix").getBytes(StandardCharsets.UTF_8);
            assertEquals(expected, JqString.deferredBytes(range, 6, range.length - 6, true).stringValue(),
                    "source range " + first);
        }
    }

    @Test
    void ordinaryEscapesAndRetainedResultsSurviveLaterDecoding() {
        primeWorkspace();
        String prefix = "a".repeat(300) + "\u00E9\u4E2D\uD83D\uDE00";
        String raw = prefix + "\\\"\\\\\\/\\b\\f\\n\\r\\t";
        String expected = prefix + "\"\\/\b\f\n\r\t";
        JqValue first = JqValues.parseStrict(("\"" + raw + "\"").getBytes(StandardCharsets.UTF_8));
        String retained = first.stringValue();
        assertEquals(expected, retained);
        for (int i = 0; i < 8; i++) {
            byte[] other = ("z".repeat(700) + "\\t" + i).getBytes(StandardCharsets.UTF_8);
            assertEquals("z".repeat(700) + "\t" + i,
                    JqString.deferredBytes(other, 0, other.length, true).stringValue());
        }
        assertEquals(expected, retained);
        assertSame(retained, first.stringValue(), "materialization remains memoized");
    }

    @Test
    void malformedUtf8KeepsJdkReplacementAndStrictRejection() {
        primeWorkspace();
        int[][] invalid = {{0x80}, {0xC0, 0xAF}, {0xC1, 0xBF}, {0xC3}, {0xC3, 0x28},
                {0xE0, 0x80, 0xAF}, {0xED, 0xA0, 0x80}, {0xE2, 0x82}, {0xE2, 0x28, 0xA1},
                {0xF0, 0x80, 0x80, 0x80}, {0xF0, 0x9F, 0x98}, {0xF4, 0x90, 0x80, 0x80},
                {0xF5, 0x80, 0x80, 0x80}, {0xFF}};
        for (int[] sequence : invalid) {
            byte[] raw = new byte[302 + sequence.length];
            Arrays.fill(raw, 0, 300, (byte) 'x');
            for (int i = 0; i < sequence.length; i++) raw[300 + i] = (byte) sequence[i];
            raw[raw.length - 2] = '\\';
            raw[raw.length - 1] = 'n';
            String expected = "x".repeat(300)
                    + new String(raw, 300, sequence.length, StandardCharsets.UTF_8) + "\n";
            assertEquals(expected, JqString.deferredBytes(raw, 0, raw.length, true).stringValue());
            byte[] json = new byte[raw.length + 2];
            json[0] = json[json.length - 1] = '"';
            System.arraycopy(raw, 0, json, 1, raw.length);
            assertThrows(IllegalArgumentException.class, () -> JqValues.parseStrict(json));
        }
        primeWorkspace(); // a failed attempt must not poison later materialization
    }

    @Test
    void unicodeAndLenientEscapesKeepBothSurrogatePolicies() {
        primeWorkspace();
        String prefix = "\u00E9\u4E2D".repeat(150);
        String[] raw = {"\\u0041", "\\uD83D\\uDE00", "\\uD83D\\u0041", "\\uDC00x", "\\uD83Dx", "\\q", "\\"};
        String[] kept = {"A", "\uD83D\uDE00", "\uD83DA", "\uDC00x", "\uD83Dx", "q", "\\"};
        String[] replaced = {"A", "\uD83D\uDE00", "\uFFFDA", "\uFFFDx", "\uFFFDx", "q", "\\"};
        for (int i = 0; i < raw.length; i++) {
            byte[] bytes = (prefix + "\\t" + raw[i]).getBytes(StandardCharsets.UTF_8);
            assertEquals(prefix + "\t" + kept[i],
                    JqString.deferredBytes(bytes, 0, bytes.length, true, false).stringValue());
            assertEquals(prefix + "\t" + replaced[i],
                    JqString.deferredBytes(bytes, 0, bytes.length, true, true).stringValue());
        }
        for (String malformed : new String[]{"\\uZZZZ", "\\u12x4"}) {
            byte[] bytes = (prefix + malformed).getBytes(StandardCharsets.UTF_8);
            JqString value = JqString.deferredBytes(bytes, 0, bytes.length, true);
            assertThrows(NumberFormatException.class, value::stringValue);
            assertThrows(NumberFormatException.class, value::stringValue, "failure remains lazy on a later access");
        }
    }

    @Test
    void byteAndUtf16BoundsKeepValuesAndOwnership() {
        primeWorkspace();
        for (int size : new int[]{2, 64, 65, 256, 257, 1025, 8191, 8192, 8193}) {
            String prefix = "x".repeat(size - 2);
            byte[] bytes = (prefix + "\\t").getBytes(StandardCharsets.UTF_8);
            String retained = JqString.deferredBytes(bytes, 0, bytes.length, true).stringValue();
            primeWorkspace();
            assertEquals(prefix + "\t", retained, "raw units " + size);
        }
        String prefix = "\u4E2D".repeat(3000);
        byte[] bytes = (prefix + "\\n").getBytes(StandardCharsets.UTF_8);
        assertEquals(prefix + "\n", JqString.deferredBytes(bytes, 0, bytes.length, true).stringValue());
    }

    @Test
    void invalidFactoryRangesKeepConstructorErrors() {
        primeWorkspace();
        byte[] bytes = new byte[500];
        for (int[] range : new int[][]{{-1, 300}, {0, 501}, {200, 100},
                {Integer.MAX_VALUE, -1}, {Integer.MIN_VALUE, Integer.MAX_VALUE}}) {
            int start = range[0], end = range[1];
            var expected = assertThrows(IndexOutOfBoundsException.class,
                    () -> new String(bytes, start, end - start, StandardCharsets.UTF_8));
            var actual = assertThrows(IndexOutOfBoundsException.class,
                    () -> JqString.deferredBytes(bytes, start, end, true).stringValue());
            assertEquals(expected.getClass(), actual.getClass());
            assertEquals(expected.getMessage(), actual.getMessage());
        }
    }

    @Test
    void independentThreadsKeepTheirRetainedResults() throws Exception {
        try (var pool = Executors.newFixedThreadPool(4)) {
            Future<?>[] futures = new Future<?>[4];
            for (int worker = 0; worker < futures.length; worker++) {
                int id = worker;
                futures[worker] = pool.submit(() -> {
                    primeWorkspace();
                    String[] retained = new String[128];
                    String prefix = "\u00E9\u4E2D\uD83D\uDE00".repeat(80) + id;
                    for (int i = 0; i < retained.length; i++) {
                        byte[] bytes = (prefix + "\\n" + i).getBytes(StandardCharsets.UTF_8);
                        retained[i] = JqString.deferredBytes(bytes, 0, bytes.length, true).stringValue();
                    }
                    for (int i = 0; i < retained.length; i++) assertEquals(prefix + "\n" + i, retained[i]);
                });
            }
            for (Future<?> future : futures) future.get();
        }
    }

    private static void primeWorkspace() {
        byte[] raw = ("p".repeat(1024) + "\\n").getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 2; i++) {
            assertEquals("p".repeat(1024) + "\n", JqString.deferredBytes(raw, 0, raw.length, true).stringValue());
        }
    }
}

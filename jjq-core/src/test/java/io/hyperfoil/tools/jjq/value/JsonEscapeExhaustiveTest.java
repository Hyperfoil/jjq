package io.hyperfoil.tools.jjq.value;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive escape pin (paper "Escaping JSON strings, one lane at a
 * time", correctness surface): every one of the 65,536 chars through
 * {@link BytOutput#escapeJsonToBytes}, checked against an independent
 * hand-rolled oracle, plus strings straddling the 8K getChars scratch
 * window and a differential against the {@code StringBuilder} path.
 */
class JsonEscapeExhaustiveTest {

    /** Must match the scratch window in {@link BytOutput}. */
    private static final int SCRATCH = 8192;

    /**
     * Independent oracle: hand-rolled JSON string-body encoding. Lone
     * surrogates use the CESU-8 3-byte form, matching
     * {@code writeSegmentChars}.
     */
    private static byte[] oracle(String s) {
        var out = new ByteArrayOutputStream(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
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
                    } else if (Character.isHighSurrogate(c) && i + 1 < s.length()
                            && Character.isLowSurrogate(s.charAt(i + 1))) {
                        int cp = Character.toCodePoint(c, s.charAt(++i));
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

    private static byte[] escapeBytes(String s) {
        var out = new BytOutput(64);
        out.escapeJsonToBytes(s);
        return out.toByteArray();
    }

    @Test
    void everyCharAgreesWithOracle() {
        for (int cp = 0; cp <= 0xFFFF; cp++) {
            final int code = cp;
            String s = String.valueOf((char) code);
            assertArrayEquals(oracle(s), escapeBytes(s),
                    () -> "mismatch at U+" + Integer.toHexString(code));
        }
    }

    @Test
    void needsEscapingAgreesWithOracle() {
        for (int cp = 0; cp <= 0xFFFF; cp++) {
            final int code = cp;
            char c = (char) code;
            boolean expected = c < 0x20 || c == '"' || c == '\\';
            assertEquals(expected, JqString.needsEscaping(String.valueOf(c)),
                    () -> "needsEscaping wrong at U+" + Integer.toHexString(code));
        }
        // Multi-window strings: escape past the 8K scratch edge.
        String pad = "a".repeat(SCRATCH);
        assertTrue(JqString.needsEscaping(pad + "\""));
        assertTrue(JqString.needsEscaping(pad + "\u0000" + pad));
        assertFalse(JqString.needsEscaping(pad + pad));
        // Mid-range: just over the short tier, across chunk edges.
        assertFalse(JqString.needsEscaping("x".repeat(100)));
        assertTrue(JqString.needsEscaping("y".repeat(99) + "\""));
        assertTrue(JqString.needsEscaping("y".repeat(255) + "\u0001" + "z".repeat(300)));
        assertFalse(JqString.needsEscaping("z".repeat(300)));
    }

    @Test
    void loneSurrogatePolicy() {
        // Default (REJECT) unchanged everywhere.
        for (String doc : new String[]{
                "{\"a\":\"\\uD83D\"}",
                "{\"a\":\"\\uDC00\"}",
                "{\"a\":\"\\uD83D\\u0041\"}"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> JqValues.parseStrict(doc));
        }
        // REPLACE decodes as U+FFFD, preserving everything else.
        var opts = ParseOptions.defaults()
                .withLoneSurrogatePolicy(ParseOptions.LoneSurrogatePolicy.REPLACE);
        // High surrogate alone.
        assertEquals("�", ((io.hyperfoil.tools.jjq.value.JqObject)
                JqValues.parseStrict("{\"a\":\"\\uD83D\"}", opts)).get("a").stringValue());
        // Low surrogate alone.
        assertEquals("�", ((io.hyperfoil.tools.jjq.value.JqObject)
                JqValues.parseStrict("{\"a\":\"\\uDC00\"}", opts)).get("a").stringValue());
        // High + non-low: replace the high, keep the follower.
        assertEquals("�A", ((io.hyperfoil.tools.jjq.value.JqObject)
                JqValues.parseStrict("{\"a\":\"\\uD83D\\u0041\"}", opts)).get("a").stringValue());
        // Paired escape is NOT replaced.
        assertEquals("😀", ((io.hyperfoil.tools.jjq.value.JqObject)
                JqValues.parseStrict("{\"a\":\"\\uD83D\\uDE00\"}", opts)).get("a").stringValue());
        // Bytes path (large input exercises the byte parser).
        String big = "{\"a\":\"" + "x".repeat(10000) + "\\uD83D\"}";
        assertEquals("x".repeat(10000) + "�",
                ((io.hyperfoil.tools.jjq.value.JqObject) JqValues.parseStrict(big, opts)).get("a").stringValue());
        // Keys stay strict — a lone surrogate in a key is still an error.
        assertThrows(IllegalArgumentException.class,
                () -> JqValues.parseStrict("{\"\\uD83D\":1}", opts));
    }

    @Test
    void windowBoundaries() {
        String pad = "a".repeat(SCRATCH);
        String[] cases = {
                // Escape exactly at, around, and across the window edge.
                pad.substring(0, SCRATCH - 1) + "\"",
                pad + "\"",
                pad + "a\"",
                pad + "\n" + pad,
                // Surrogate pair straddling the edge (high at 8191).
                pad.substring(0, SCRATCH - 1) + "\uD83D\uDE00" + "x",
                // Lone surrogates at the edge.
                pad.substring(0, SCRATCH - 1) + "\uD83D" + "x",
                pad.substring(0, SCRATCH - 1) + "\uDE00" + "x",
                // Long clean and escape-dense strings over several windows.
                "b".repeat(3 * SCRATCH + 17),
                ("\"\\\n\t" + "é\u20ACTrue").repeat(2000),
                // Mid-range tier/chunk coverage: escapes around 64 and 256.
                "q".repeat(63) + "\"" + "q".repeat(10),
                "q".repeat(64) + "\"" + "q".repeat(10),
                "q".repeat(255) + "\\" + "q".repeat(300),
                "q".repeat(512),
        };
        for (String s : cases) {
            assertArrayEquals(oracle(s), escapeBytes(s),
                    () -> "mismatch at length " + s.length());
        }
    }

    @Test
    void differentialAgainstStringBuilderPath() {
        // Well-formed strings only: lone surrogates have no UTF-8
        // decoding, so the byte path is oracle-checked above instead.
        String[] cases = {
                "",
                "plain ascii",
                "quote \" backslash \\ slash /",
                "controls \u0000\u0001\u001F\b\f\n\r\t",
                "del \u007F latin \u00E9\u00FF bmp \u20AC\u4E2D",
                "pair \uD83D\uDE00 done",
                "mixed a\"\u0007\u00E9\uD83D\uDE00\\z",
        };
        for (String s : cases) {
            var sb = new StringBuilder();
            JqString.escapeJson(s, sb);
            assertEquals(sb.toString(),
                    new String(escapeBytes(s), StandardCharsets.UTF_8),
                    () -> "paths disagree on: " + s);
        }
    }

    /**
     * Char-path escape pin (#207 phase 0): every one of the 65,536 chars
     * through {@link JqString#escapeJson(String, StringBuilder)} against an
     * independent expected builder, including the returned flag. Lone
     * surrogates pass through by design (no UTF-8 decoding on this path),
     * so they are pinned as raw passthrough here, not via the byte oracle.
     */
    @Test
    void escapeJsonCharPathEveryChar() {
        for (int cp = 0; cp <= 0xFFFF; cp++) {
            final char c = (char) cp;
            final int code = cp;
            var sb = new StringBuilder();
            boolean escaped = JqString.escapeJson(String.valueOf(c), sb);
            boolean expectEscape = c < 0x20 || c == '"' || c == '\\';
            assertEquals(expectEscape, escaped,
                    () -> "flag wrong at U+" + Integer.toHexString(code));
            assertEquals(expectedCharBody(c), sb.toString(),
                    () -> "mismatch at U+" + Integer.toHexString(code));
        }
    }

    private static String expectedCharBody(char c) {
        return switch (c) {
            case '"' -> "\\\"";
            case '\\' -> "\\\\";
            case '\b' -> "\\b";
            case '\f' -> "\\f";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            default -> {
                if (c < 0x20) {
                    yield "\\u00"
                            + Character.forDigit((c >> 4) & 0xF, 16)
                            + Character.forDigit(c & 0xF, 16);
                }
                yield String.valueOf(c);
            }
        };
    }

    /**
     * Escape flag and output at string edges (#207 phase 0): the Stage 1
     * experiment splits the scan at the first hit, so first-char, last-char,
     * single-char, and empty inputs pin the boundary behavior up front.
     */
    @Test
    void escapeFlagAtFirstAndLastChar() {
        assertEscape("\"abc", "\\\"abc", true);
        assertEscape("abc\"", "abc\\\"", true);
        assertEscape("\"", "\\\"", true);
        assertEscape("\\", "\\\\", true);
        assertEscape("\u0000", "\\u0000", true);
        assertEscape("\nabc\t", "\\nabc\\t", true);
        assertEscape("", "", false);
        assertEscape("abc", "abc", false);
        assertEscape("a".repeat(512), "a".repeat(512), false);
    }

    private static void assertEscape(String in, String expectBody, boolean expectFlag) {
        var sb = new StringBuilder();
        assertEquals(expectFlag, JqString.escapeJson(in, sb),
                () -> "flag wrong for: " + in);
        assertEquals(expectBody, sb.toString(),
                () -> "body wrong for: " + in);
    }

    /**
     * First-use vs memo-warm output pin (#207 phase 0): repeated
     * serialization of the same {@link JqString} must stay byte-identical,
     * and the cleanliness memo must record the verdict of the first run
     * (1 = clean, 2 = escaping) so later serializes skip the rescan. The
     * memo field is read reflectively to pin the route, not just the
     * result: output equality alone cannot tell a memo hit from a rescan.
     */
    @Test
    void firstUseVsMemoWarm() throws Exception {
        var cleanField = JqString.class.getDeclaredField("cleanMemo");
        cleanField.setAccessible(true);

        JqString clean = JqString.of("plain ascii, no escapes");
        var sb1 = new StringBuilder();
        clean.appendTo(sb1);
        assertEquals("\"plain ascii, no escapes\"", sb1.toString());
        var sb2 = new StringBuilder();
        clean.appendTo(sb2);
        assertEquals(sb1.toString(), sb2.toString());
        assertEquals((byte) 1, cleanField.getByte(clean));

        JqString esc = JqString.of("quote \" and \\ backslash");
        var sb3 = new StringBuilder();
        esc.appendTo(sb3);
        String first = sb3.toString();
        assertTrue(first.contains("\\\""), "escaped quotes: " + first);
        var sb4 = new StringBuilder();
        esc.appendTo(sb4);
        assertEquals(first, sb4.toString());
        assertEquals((byte) 2, cleanField.getByte(esc));
    }

    /**
     * Segment-sized char-path pin (#207 phase 0): strings around the 8 KiB
     * scratch/segment size with escapes at the edges must agree exactly
     * between the char and byte paths. Well-formed strings only, like the
     * existing differential.
     */
    @Test
    void segmentSizedCharPathMatchesBytePath() {
        String pad = "a".repeat(SCRATCH);
        String[] cases = {
                pad.substring(0, SCRATCH - 1) + "\"",
                pad + "\"",
                pad + "a\"",
                "\"" + pad,
                "é".repeat(SCRATCH - 1) + "\"",
                "中".repeat(SCRATCH - 1) + "\"",
                "a".repeat(SCRATCH - 2) + "\uD83D\uDE00" + "x",
                "\"" + "b".repeat(SCRATCH),
                "x".repeat(SCRATCH / 2) + "\n" + "x".repeat(SCRATCH / 2),
                "q".repeat(511) + "\\" + "q".repeat(100),
        };
        for (String s : cases) {
            var sb = new StringBuilder();
            JqString.escapeJson(s, sb);
            assertEquals(sb.toString(),
                    new String(escapeBytes(s), StandardCharsets.UTF_8),
                    () -> "paths disagree at length " + s.length());
        }
    }
}

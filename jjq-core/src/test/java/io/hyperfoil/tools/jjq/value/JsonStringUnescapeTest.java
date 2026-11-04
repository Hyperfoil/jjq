package io.hyperfoil.tools.jjq.value;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class JsonStringUnescapeTest {
    @Test
    void everyEscapedUtf16UnitKeepsItsPolicy() {
        String padding = "x".repeat(80);
        // Admit and size the decoder workspace before the padded corpus.
        for (int i = 0; i < 2; i++) {
            String prime = padding + "\\n";
            assertEquals(padding + "\n", JqString.deferred(prime, 0, prime.length(), true).stringValue());
        }
        for (int code = 0; code <= 0xFFFF; code++) {
            String hex = Integer.toHexString(code);
            String escaped = "\\u" + "0".repeat(4 - hex.length()) + hex;
            String source = "prefix" + escaped + "suffix";
            String literal = String.valueOf((char) code);
            assertEquals(literal, JqString.deferred(source, 6, 12, true).stringValue(), "unit " + code);
            String replaced = Character.isSurrogate((char) code) ? "\uFFFD" : literal;
            assertEquals(replaced, JqString.deferred(source, 6, 12, true, true).stringValue(), "replace " + code);
            String padded = padding + escaped;
            assertEquals(padding + literal, JqString.deferred(padded, 0, padded.length(), true).stringValue(),
                    "workspace unit " + code);
            assertEquals(padding + replaced, JqString.deferred(padded, 0, padded.length(), true, true).stringValue(),
                    "workspace replacement " + code);
        }
    }

    @Test
    void allEscapesRangesAndByteSourcesAgree() {
        String raw = "first\\\"\\\\\\/\\b\\f\\n\\r\\t\\u0041last";
        String expected = "first\"\\/\b\f\n\r\tAlast";
        String source = "prefix" + raw + "suffix";
        assertEquals(expected, JqString.deferred(source, 6, 6 + raw.length(), true).stringValue());
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        assertEquals(expected, JqString.deferredBytes(bytes, 6, 6 + raw.length(), true).stringValue());
        assertEquals("", JqString.deferred("prefixsuffix", 6, 6, true).stringValue());
        // These lenient factory inputs deliberately preserve today's tolerance.
        assertEquals("q", JqString.deferred("\\q", 0, 2, true).stringValue());
        assertEquals("\\", JqString.deferred("\\", 0, 1, true).stringValue());
    }

    @Test
    void pairsAndLoneSurrogatesKeepNeighboringText() {
        String[] raw = {"\\uD83D\\uDE00", "\\uD83D\\u0041", "\\uDC00x", "\\uD83Dx"};
        String[] preserved = {"\uD83D\uDE00", "\uD83DA", "\uDC00x", "\uD83Dx"};
        String[] replaced = {"\uD83D\uDE00", "\uFFFDA", "\uFFFDx", "\uFFFDx"};
        for (int i = 0; i < raw.length; i++) {
            for (String prefix : new String[]{"", "x".repeat(80)}) {
                String input = prefix + raw[i];
                assertEquals(prefix + preserved[i], JqString.deferred(input, 0, input.length(), true).stringValue());
                assertEquals(prefix + replaced[i], JqString.deferred(input, 0, input.length(), true, true).stringValue());
            }
        }
    }

    @Test
    void longAndShortResultsDoNotAliasReusableStorage() {
        for (int length : new int[]{0, 1, 63, 64, 255, 256, 8191, 8192, 8193, 16385}) {
            String prefix = "\u00E9\u4E2D".repeat(length);
            String raw = prefix + "\\n\\uD83D\\uDE00";
            String expected = prefix + "\n\uD83D\uDE00";
            String first = JqString.deferred(raw, 0, raw.length(), true).stringValue();
            assertEquals(expected, first);
            assertEquals("other\t", JqString.deferred("other\\t", 0, 7, true).stringValue());
            assertEquals(expected, first);
        }
    }

    @Test
    void malformedUnicodeStillFailsLazilyAndStrictInputStillRejects() {
        for (String raw : new String[]{"\\uZZZZ", "\\u12x4"}) {
            JqString deferred = JqString.deferred(raw, 0, raw.length(), true);
            assertThrows(NumberFormatException.class, deferred::stringValue);
            assertThrows(IllegalArgumentException.class, () -> JqValues.parseStrict("\"" + raw + "\""));
        }
        assertThrows(IllegalArgumentException.class, () -> JqValues.parseStrict("\"\\uD83D\""));
        assertThrows(IllegalArgumentException.class, () -> JqValues.parseStrict("\"\\uDC00\""));
        var replace = ParseOptions.defaults()
                .withLoneSurrogatePolicy(ParseOptions.LoneSurrogatePolicy.REPLACE);
        assertEquals("\uFFFD", JqValues.parseStrict("\"\\uD83D\"", replace).stringValue());
    }
}

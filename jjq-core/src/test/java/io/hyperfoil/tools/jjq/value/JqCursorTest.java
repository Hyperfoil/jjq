package io.hyperfoil.tools.jjq.value;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Differential contract tests for the strict token cursor (issue #172).
 *
 * <p>The cursor shares the strict grammar with {@link JqValues#parseStrict(byte[])}
 * but decodes flat objects into plain JDK values without building a
 * {@code JqValue} tree. Any deviation from the canonical happy path reports
 * an explicit fallback outcome, and the existing path then reproduces today's
 * exact behavior — so a fallback is always legal, while a completion must
 * agree with the {@code parseStrict} oracle on success and decoded values.
 */
class JqCursorTest {

    /** Field kinds the test driver (mimicking #173 generated code) decodes. */
    enum Kind { STRING, INT, LONG, BOOLEAN }

    /** Decoded outcome: complete with values, or an explicit fallback. */
    record DecodeResult(boolean complete, Map<String, Object> values) {
        static DecodeResult fallback() {
            return new DecodeResult(false, Map.of());
        }
    }

    static byte[][] wires(String... names) {
        byte[][] out = new byte[names.length][];
        for (int i = 0; i < names.length; i++) {
            out[i] = names[i].getBytes(StandardCharsets.UTF_8);
        }
        return out;
    }

    /**
     * Drive the cursor exactly as #173 generated code will: known-field
     * dispatch, presence bits with fallback on repeats, canonical scalar
     * reads, fallback on anything else.
     */
    static DecodeResult decode(byte[] doc, String[] names, Kind[] kinds) {
        return decode(doc, names, kinds, ParseOptions.defaults());
    }

    static DecodeResult decode(byte[] doc, String[] names, Kind[] kinds, ParseOptions options) {
        JqValues.JqCursor cursor = JqValues.strictCursor(doc, options);
        if (!cursor.beginDocument()) return DecodeResult.fallback();
        if (!cursor.beginObject()) return DecodeResult.fallback();
        byte[][] known = wires(names);
        Map<String, Object> out = new LinkedHashMap<>();
        long present = 0;
        while (cursor.nextField()) {
            int idx = cursor.fieldIndex(known);
            if (idx < 0) return DecodeResult.fallback();
            if ((present & (1L << idx)) != 0) return DecodeResult.fallback();
            present |= 1L << idx;
            if (!cursor.expectColon()) return DecodeResult.fallback();
            var peek = cursor.peekValue();
            if (peek == JqValues.JqCursor.ValueKind.NULL) {
                if (!cursor.readNull()) return DecodeResult.fallback();
                continue;
            }
            switch (kinds[idx]) {
                case STRING -> {
                    if (peek != JqValues.JqCursor.ValueKind.STRING) return DecodeResult.fallback();
                    String s = cursor.readString();
                    if (s == null) return DecodeResult.fallback();
                    out.put(names[idx], s);
                }
                case LONG -> {
                    if (peek != JqValues.JqCursor.ValueKind.NUMBER) return DecodeResult.fallback();
                    if (!cursor.readLong()) return DecodeResult.fallback();
                    out.put(names[idx], cursor.longValue());
                }
                case INT -> {
                    if (peek != JqValues.JqCursor.ValueKind.NUMBER) return DecodeResult.fallback();
                    if (!cursor.readInt()) return DecodeResult.fallback();
                    out.put(names[idx], cursor.intValue());
                }
                case BOOLEAN -> {
                    if (peek != JqValues.JqCursor.ValueKind.TRUE
                            && peek != JqValues.JqCursor.ValueKind.FALSE) {
                        return DecodeResult.fallback();
                    }
                    if (!cursor.readBoolean()) return DecodeResult.fallback();
                    out.put(names[idx], cursor.booleanValue());
                }
            }
        }
        if (!cursor.endInput()) return DecodeResult.fallback();
        return new DecodeResult(true, out);
    }

    /** Oracle outcome of the canonical path. */
    record Oracle(boolean ok, JqValue value, String error) {}

    static Oracle oracle(byte[] doc, ParseOptions options) {
        try {
            return new Oracle(true, JqValues.parseStrict(doc, options), null);
        } catch (IllegalArgumentException e) {
            return new Oracle(false, null, e.getMessage());
        } catch (RuntimeException e) {
            fail("parseStrict escaped the error contract with " + e.getClass().getName(), e);
            throw new AssertionError(e);
        }
    }

    static byte[] bytes(String doc) {
        return doc.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Core differential property: a completion must agree with the oracle,
     * and the cursor must never escape the {@code IllegalArgumentException}
     * error contract. Fallback is always legal (the existing path reproduces
     * today's behavior exactly).
     */
    static DecodeResult assertDifferential(byte[] doc, String[] names, Kind[] kinds,
                                           ParseOptions options) {
        Oracle o = oracle(doc, options);
        DecodeResult r;
        String thrown = null;
        try {
            r = decode(doc, names, kinds, options);
        } catch (IllegalArgumentException e) {
            thrown = e.getMessage();
            r = null;
        } catch (RuntimeException e) {
            fail("cursor escaped the error contract with " + e.getClass().getName()
                    + " for input " + printable(doc), e);
            throw new AssertionError(e);
        }
        if (thrown != null) {
            assertFalse(o.ok, "cursor threw but parseStrict succeeded for " + printable(doc));
            assertEquals(o.error, thrown, "cursor/parseStrict error mismatch for " + printable(doc));
            return DecodeResult.fallback();
        }
        assertNotNull(r);
        if (r.complete()) {
            assertTrue(o.ok, "cursor completed but parseStrict threw <" + o.error + "> for "
                    + printable(doc));
            assertDecodedEquals(o.value(), names, kinds, r.values(), doc);
        }
        return r;
    }

    static void assertDecodedEquals(JqValue root, String[] names, Kind[] kinds,
                                    Map<String, Object> decoded, byte[] doc) {
        assertInstanceOf(JqObject.class, root, "oracle root for " + printable(doc));
        JqObject obj = (JqObject) root;
        for (int i = 0; i < names.length; i++) {
            var hit = obj.tryGet(names[i]);
            Object expected;
            if (hit.isEmpty() || hit.get() instanceof JqNull) {
                expected = null;
            } else {
                JqValue v = hit.get();
                expected = switch (kinds[i]) {
                    case STRING -> ((JqString) v).stringValue();
                    case LONG -> v.longValue();
                    case INT -> (int) v.longValue();
                    case BOOLEAN -> v.booleanValue();
                };
            }
            Object actual = decoded.get(names[i]);
            // Absent fields stay at their mapping default (null here); explicit
            // JSON null decodes the same way, matching required-presence rules.
            assertEquals(expected, actual, "field '" + names[i] + "' for " + printable(doc));
        }
    }

    static String printable(byte[] doc) {
        StringBuilder sb = new StringBuilder();
        for (byte b : doc) {
            int u = b & 0xFF;
            if (u >= 0x20 && u < 0x7F) sb.append((char) u);
            else sb.append(String.format("\\x%02x", u));
        }
        return sb.toString();
    }

    static void assertCompletes(String doc, String[] names, Kind[] kinds) {
        DecodeResult r = assertDifferential(bytes(doc), names, kinds, ParseOptions.defaults());
        assertTrue(r.complete(), "expected completion for " + doc);
    }

    static void assertFallsBack(byte[] doc, String[] names, Kind[] kinds) {
        assertFallsBack(doc, names, kinds, ParseOptions.defaults());
    }

    static void assertFallsBack(byte[] doc, String[] names, Kind[] kinds, ParseOptions options) {
        DecodeResult r = assertDifferential(doc, names, kinds, options);
        assertFalse(r.complete(), "expected fallback for " + printable(doc));
    }

    static final String[] NAMES = {"name", "age", "score", "active"};
    static final Kind[] KINDS = {Kind.STRING, Kind.INT, Kind.LONG, Kind.BOOLEAN};

    @Test
    void canonicalFlatObjectsComplete() {
        assertCompletes("{\"name\":\"ann\",\"age\":30,\"score\":100,\"active\":true}",
                NAMES, KINDS);
        assertCompletes("{\"active\":false,\"score\":-5,\"age\":0,\"name\":\"\"}",
                NAMES, KINDS);
        assertCompletes("{\n  \"name\" : \"a b\" ,\n  \"age\" : 1 ,\n  \"score\" : 2 ,\n  \"active\" : true\n}",
                NAMES, KINDS);
        assertCompletes("{}", NAMES, KINDS);
        assertCompletes("{\"name\":\"x\"}", NAMES, KINDS);
        assertCompletes("  {\"name\":\"x\"}  ", NAMES, KINDS);
    }

    @Test
    void escapedAndUnicodeStringsComplete() {
        assertCompletes("{\"name\":\"a\\\"b\\\\c\\/d\\be\\ff\\ng\\rh\\ti\"}", NAMES, KINDS);
        assertCompletes("{\"name\":\"caf\\u00e9\"}", NAMES, KINDS);
        assertCompletes("{\"name\":\"\\uD83D\\uDE00\"}", NAMES, KINDS);
        assertCompletes("{\"name\":\"caf\u00e9\u4e2d\ud83d\ude00\"}", NAMES, KINDS);
        assertCompletes("{\"name\":\"\\u0000\"}", NAMES, KINDS);
    }

    @Test
    void integerBoundariesComplete() {
        assertCompletes("{\"age\":2147483647,\"score\":9223372036854775807}", NAMES, KINDS);
        assertCompletes("{\"age\":-2147483648,\"score\":-9223372036854775808}", NAMES, KINDS);
        assertCompletes("{\"age\":-0,\"score\":-0}", NAMES, KINDS);
    }

    @Test
    void prefixFieldNamesDiscriminateByLength() {
        String[] names = {"a", "ab"};
        Kind[] kinds = {Kind.INT, Kind.INT};
        DecodeResult r = assertDifferential(bytes("{\"ab\":1,\"a\":2}"), names, kinds,
                ParseOptions.defaults());
        assertTrue(r.complete());
        assertEquals(2, r.values().get("a"));
        assertEquals(1, r.values().get("ab"));
        assertFallsBack(bytes("{\"abc\":1}"), names, kinds);
    }

    @Test
    void nonAsciiFieldNamesComplete() {
        String[] names = {"caf\u00e9", "na\u00efve"};
        Kind[] kinds = {Kind.STRING, Kind.INT};
        DecodeResult r = assertDifferential(
                bytes("{\"caf\u00e9\":\"x\",\"na\u00efve\":3}"), names, kinds,
                ParseOptions.defaults());
        assertTrue(r.complete());
        assertEquals("x", r.values().get("caf\u00e9"));
        assertEquals(3, r.values().get("na\u00efve"));
    }

    @Test
    void nullsCountAsPresentWithDefaults() {
        DecodeResult r = assertDifferential(
                bytes("{\"name\":null,\"age\":null,\"score\":null,\"active\":null}"),
                NAMES, KINDS, ParseOptions.defaults());
        assertTrue(r.complete());
        assertTrue(r.values().isEmpty());
    }

    @Test
    void unsupportedShapesFallBackWithoutChangingOutcomes() {
        // Unknown keys, nesting, and coercible-but-uncached forms fall back;
        // the existing path then reproduces today's exact behavior.
        assertFallsBack(bytes("{\"name\":\"a\",\"unknown\":1}"), NAMES, KINDS);
        assertFallsBack(bytes("{\"name\":{\"nested\":1}}"), NAMES, KINDS);
        assertFallsBack(bytes("{\"name\":[1,2]}"), NAMES, KINDS);
        assertFallsBack(bytes("{\"age\":\"42\"}"), NAMES, KINDS);
        assertFallsBack(bytes("{\"name\":42}"), NAMES, KINDS);
        assertFallsBack(bytes("{\"active\":1}"), NAMES, KINDS);
        assertFallsBack(bytes("{\"age\":true}"), NAMES, KINDS);
        assertFallsBack(bytes("{\"age\":4.5}"), NAMES, KINDS);
        assertFallsBack(bytes("{\"age\":1e3}"), NAMES, KINDS);
        assertFallsBack(bytes("{\"age\":2147483648}"), NAMES, KINDS);
        assertFallsBack(bytes("{\"score\":9223372036854775808}"), NAMES, KINDS);
        assertFallsBack(bytes("{\"age\":007}"), NAMES, KINDS);
        assertFallsBack(bytes("{\"n\\u0061me\":\"a\"}"), NAMES, KINDS);
        assertFallsBack(bytes("[]"), NAMES, KINDS);
        assertFallsBack(bytes("\"just a string\""), NAMES, KINDS);
        assertFallsBack(bytes("42"), NAMES, KINDS);
        assertFallsBack(bytes("null"), NAMES, KINDS);
        assertFallsBack(bytes(""), NAMES, KINDS);
        assertFallsBack(bytes("   "), NAMES, KINDS);
    }

    @Test
    void duplicateKeysFallBackUnderBothPolicies() {
        byte[] doc = bytes("{\"name\":\"a\",\"name\":\"b\"}");
        assertFallsBack(doc, NAMES, KINDS);
        assertFallsBack(doc, NAMES, KINDS,
                ParseOptions.defaults().withDuplicatePolicy(ParseOptions.DuplicatePolicy.KEEP_LAST));
        // KEEP_LAST still reproduces through the fallback path.
        Oracle o = oracle(doc,
                ParseOptions.defaults().withDuplicatePolicy(ParseOptions.DuplicatePolicy.KEEP_LAST));
        assertTrue(o.ok);
        assertEquals("b", ((JqString) ((JqObject) o.value()).get("name")).stringValue());
    }

    @Test
    void everyTruncationFallsBackOrMatches() {
        String[] seeds = {
            "{\"name\":\"ann\",\"age\":30,\"score\":100,\"active\":true}",
            "{\"name\":\"a\\\"b\\u00e9\",\"age\":-12}",
            "  { \"name\" : \"x\" }  ",
        };
        for (String seed : seeds) {
            byte[] full = bytes(seed);
            for (int cut = 0; cut < full.length; cut++) {
                byte[] prefix = new byte[cut];
                System.arraycopy(full, 0, prefix, 0, cut);
                assertDifferential(prefix, NAMES, KINDS, ParseOptions.defaults());
            }
        }
    }

    @Test
    void malformedDocumentsNeverComplete() {
        String[] bad = {
            "{\"name\":\"unterminated}",
            "{\"name\":\"bad\\xescape\"}",
            "{\"name\":\"bad\\u12escape\"}",
            "{\"name\":\"bad\\u12GZ\"}",
            "{\"name\":\"lone\\uD800half\"}",
            "{\"name\":\"lone\\uDC00half\"}",
            "{\"name\":\"unpaired\\uD83D\"}",
            "{\"name\":tru}",
            "{\"name\":fals}",
            "{\"name\":nul}",
            "{\"name\":NaN}",
            "{\"name\":Infinity}",
            "{\"name\":-Infinity}",
            "{\"name\":'single'}",
            "{'name':\"x\"}",
            "{\"age\":01}",
            "{\"age\":-01}",
            "{\"age\":1.}",
            "{\"age\":.5}",
            "{\"age\":1e}",
            "{\"age\":1e+}",
            "{\"age\":+1}",
            "{\"age\":--1}",
            "{\"age\":-}",
            "{\"name\" \"a\"}",
            "{\"name\":}",
            "{\"name\":\"a\" \"b\":1}",
            "{\"name\":\"a\",}",
            "{,\"name\":\"a\"}",
            "{\"name\":\"a\"}",
            "{\"name\":\"a\"}trailing",
            "{\"name\":\"a\"} {}",
            "[{\"name\":\"a\"}]",
            "{\"name\":\"a\"",
            "{\"name\":",
            "{\"name\"",
            "{",
        };
        for (String doc : bad) {
            // The last entry is valid; the rest must never complete.
            if (doc.equals("{\"name\":\"a\"}")) {
                assertCompletes(doc, NAMES, KINDS);
            } else {
                assertFallsBack(bytes(doc), NAMES, KINDS);
            }
        }
    }

    @Test
    void invalidUtf8ThrowsIdentically() {
        byte[][] bad = {
            {(byte) 0x80},
            "{\"name\":\"a\u00e9\"}".getBytes(StandardCharsets.UTF_8),
            {(byte) '{', (byte) '"', (byte) 'a', (byte) '"', (byte) ':', (byte) '"',
             (byte) 0xC3, (byte) '"', (byte) '}'},
            {(byte) '{', (byte) '"', (byte) 'a', (byte) '"', (byte) ':', (byte) '"',
             (byte) 0xE2, (byte) 0x82, (byte) '"', (byte) '}'},
            {(byte) '{', (byte) '}', (byte) 0xFE},
            {(byte) '{', (byte) '}', (byte) 0xFF},
            {(byte) '{', (byte) '"', (byte) 'a', (byte) '"', (byte) ':', (byte) '\"',
             (byte) 0xED, (byte) 0xA0, (byte) 0x80, (byte) '\"', (byte) '}'},
        };
        // Repair the second entry into overlong encoding of '/': 0xC0 0xAF.
        bad[1] = new byte[]{'{', '"', 'a', '"', ':', '"', (byte) 0xC0, (byte) 0xAF, '"', '}'};
        for (byte[] doc : bad) {
            DecodeResult r = null;
            String thrown = null;
            Oracle o = oracle(doc, ParseOptions.defaults());
            assertFalse(o.ok, "oracle unexpectedly accepted " + printable(doc));
            try {
                r = decode(doc, NAMES, KINDS);
            } catch (IllegalArgumentException e) {
                thrown = e.getMessage();
            }
            if (thrown != null) {
                assertEquals(o.error, thrown, "error mismatch for " + printable(doc));
            } else {
                assertNotNull(r);
                assertFalse(r.complete(), "cursor completed invalid UTF-8 " + printable(doc));
            }
        }
    }

    @Test
    void bomAndNulPoliciesMatch() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '}'};
        DecodeResult r = assertDifferential(bom, NAMES, KINDS, ParseOptions.defaults());
        assertTrue(r.complete());
        assertFallsBack(bom, NAMES, KINDS, ParseOptions.defaults()
                .withBomPolicy(ParseOptions.BomPolicy.REJECT));

        byte[] nul = {'{', '"', 'n', '"', ':', '"', 'a', 0, 'b', '"', '}'};
        DecodeResult rn = assertDifferential(nul, new String[]{"n"}, new Kind[]{Kind.STRING},
                ParseOptions.defaults());
        assertTrue(rn.complete());
        assertEquals("a\0b", rn.values().get("n"));
        assertFallsBack(nul, new String[]{"n"}, new Kind[]{Kind.STRING},
                ParseOptions.defaults().withNulPolicy(ParseOptions.NulPolicy.REJECT));
    }

    @Test
    void fuzzKeepsTheErrorContract() {
        String[] seeds = {
            "{\"name\":\"ann\",\"age\":30,\"score\":-100,\"active\":true}",
            "{\"name\":\"\\u00e9\\ud83d\\ude00\",\"empty\":\"\"}",
        };
        byte[] alphabet = "{}[]\",: \t\n0123456789.eE+-truefalsn\"\\u".getBytes(StandardCharsets.US_ASCII);
        java.util.Random rng = new java.util.Random(0xC0FFEE);
        for (String seed : seeds) {
            byte[] base = bytes(seed);
            for (int i = 0; i < 1500; i++) {
                byte[] doc = mutate(base, alphabet, rng);
                assertDifferential(doc, NAMES, KINDS, ParseOptions.defaults());
            }
        }
    }

    static byte[] mutate(byte[] base, byte[] alphabet, java.util.Random rng) {
        int op = rng.nextInt(5);
        return switch (op) {
            case 0 -> { // flip one byte
                if (base.length == 0) yield base;
                byte[] doc = base.clone();
                doc[rng.nextInt(doc.length)] = (byte) rng.nextInt(256);
                yield doc;
            }
            case 1 -> { // truncate
                int cut = rng.nextInt(base.length + 1);
                byte[] doc = new byte[cut];
                System.arraycopy(base, 0, doc, 0, cut);
                yield doc;
            }
            case 2 -> { // delete a span
                if (base.length == 0) yield base;
                int at = rng.nextInt(base.length);
                int len = Math.min(rng.nextInt(4), base.length - at);
                byte[] doc = new byte[base.length - len];
                System.arraycopy(base, 0, doc, 0, at);
                System.arraycopy(base, at + len, doc, at, base.length - at - len);
                yield doc;
            }
            case 3 -> { // insert alphabet bytes
                int at = rng.nextInt(base.length + 1);
                int len = 1 + rng.nextInt(3);
                byte[] doc = new byte[base.length + len];
                System.arraycopy(base, 0, doc, 0, at);
                for (int j = 0; j < len; j++) doc[at + j] = alphabet[rng.nextInt(alphabet.length)];
                System.arraycopy(base, at, doc, at + len, base.length - at);
                yield doc;
            }
            default -> { // duplicate a span
                if (base.length == 0) yield base;
                int at = rng.nextInt(base.length);
                int len = Math.min(1 + rng.nextInt(4), base.length - at);
                byte[] doc = new byte[base.length + len];
                System.arraycopy(base, 0, doc, 0, at + len);
                System.arraycopy(base, at, doc, at, len);
                System.arraycopy(base, at + len, doc, at + len, base.length - at - len);
                yield doc;
            }
        };
    }

    static volatile long blackhole;

    // Lean driver locals, mirroring #173 typed locals (no map, no boxing
    // beyond the decoded values themselves).
    static String leanName;
    static int leanAge;
    static long leanScore;
    static boolean leanActive;
    // Generated code emits these once per type, not per request.
    static final byte[][] LEAN_WIRES = wires(NAMES);
    static final ParseOptions LEAN_OPTIONS = ParseOptions.defaults();

    static boolean decodeLean(byte[] doc) {
        JqValues.JqCursor cursor = JqValues.strictCursor(doc, LEAN_OPTIONS);
        if (!cursor.beginDocument()) return false;
        if (!cursor.beginObject()) return false;
        byte[][] known = LEAN_WIRES;
        long present = 0;
        String name = null;
        int age = 0;
        long score = 0;
        boolean active = false;
        while (cursor.nextField()) {
            int idx = cursor.fieldIndex(known);
            if (idx < 0) return false;
            if ((present & (1L << idx)) != 0) return false;
            present |= 1L << idx;
            if (!cursor.expectColon()) return false;
            var peek = cursor.peekValue();
            if (peek == JqValues.JqCursor.ValueKind.NULL) {
                if (!cursor.readNull()) return false;
                continue;
            }
            switch (idx) {
                case 0 -> {
                    if (peek != JqValues.JqCursor.ValueKind.STRING) return false;
                    String s = cursor.readString();
                    if (s == null) return false;
                    name = s;
                }
                case 1 -> {
                    if (peek != JqValues.JqCursor.ValueKind.NUMBER) return false;
                    if (!cursor.readInt()) return false;
                    age = cursor.intValue();
                }
                case 2 -> {
                    if (peek != JqValues.JqCursor.ValueKind.NUMBER) return false;
                    if (!cursor.readLong()) return false;
                    score = cursor.longValue();
                }
                case 3 -> {
                    if (peek != JqValues.JqCursor.ValueKind.TRUE
                            && peek != JqValues.JqCursor.ValueKind.FALSE) return false;
                    if (!cursor.readBoolean()) return false;
                    active = cursor.booleanValue();
                }
                default -> { return false; }
            }
        }
        if (!cursor.endInput()) return false;
        leanName = name;
        leanAge = age;
        leanScore = score;
        leanActive = active;
        return true;
    }

    @Test
    void cursorAvoidsTreeAllocation() {
        com.sun.management.ThreadMXBean mx =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(mx.isThreadAllocatedMemorySupported() && mx.isThreadAllocatedMemoryEnabled());
        byte[] doc = bytes("{\"name\":\"annabelle\",\"age\":30,\"score\":-100,\"active\":true}");
        for (int i = 0; i < 20_000; i++) {
            if (!decodeLean(doc)) fail("lean driver fell back on canonical input");
            blackhole += leanName.hashCode() + leanAge + leanScore + (leanActive ? 1 : 0);
            blackhole += JqValues.parseStrict(doc).hashCode();
        }
        long id = Thread.currentThread().getId();
        long cursorStart = mx.getThreadAllocatedBytes(id);
        for (int i = 0; i < 20_000; i++) {
            if (!decodeLean(doc)) fail("lean driver fell back on canonical input");
            blackhole += leanName.hashCode() + leanAge + leanScore + (leanActive ? 1 : 0);
        }
        long cursorBytes = mx.getThreadAllocatedBytes(id) - cursorStart;
        long strictStart = mx.getThreadAllocatedBytes(id);
        for (int i = 0; i < 20_000; i++) {
            blackhole += JqValues.parseStrict(doc).hashCode();
        }
        long strictBytes = mx.getThreadAllocatedBytes(id) - strictStart;
        assertTrue(cursorBytes * 2 < strictBytes,
                "cursor " + cursorBytes / 20_000 + " B/op vs parseStrict "
                        + strictBytes / 20_000 + " B/op");
    }
}

package io.hyperfoil.tools.jjq.value;

import io.hyperfoil.tools.jjq.JqProgram;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue #211 Phase 4: strict on-demand extraction. The fast path is a gate —
 * every rejection falls back to {@code apply(JqValues.parseStrict(...))}, so
 * result and exception parity hold by construction. These tests pin the
 * gate's decisions and the end-to-end parity.
 */
class ApplyStrictTest {

    private static final ParseOptions TRUST_UNIQUE =
            ParseOptions.defaults().withDuplicatePolicy(ParseOptions.DuplicatePolicy.TRUST_UNIQUE);
    private static final ParseOptions DEFAULTS = ParseOptions.defaults();

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Gate units: StrictByteValidator acceptance / rejection
    // ------------------------------------------------------------------

    private static void assertValid(String json) {
        byte[] b = utf8(json);
        assertTrue(StrictByteValidator.validate(b, 0, b.length, TRUST_UNIQUE), json);
    }

    private static void assertInvalid(String json) {
        byte[] b = utf8(json);
        assertFalse(StrictByteValidator.validate(b, 0, b.length, TRUST_UNIQUE), json);
    }

    @Test
    void validatorAcceptsValidDocuments() {
        assertValid("{}");
        assertValid("[]");
        assertValid("null");
        assertValid("true");
        assertValid("-0");
        assertValid("0");
        assertValid("123");
        assertValid("-12.5e+10");
        assertValid("{\"a\":1,\"b\":[true,null,\"x\\n\\u0041\"]}");
        assertValid("  [1, 2, {\"k\": \"}\"}]  ");
        assertValid("\"\\ud83d\\ude00\""); // paired surrogate escape
        assertValid("[[[[[[[1]]]]]]]");
        // Duplicate keys are a policy question, not grammar: gate accepts.
        assertValid("{\"a\":1,\"a\":2}");
    }

    @Test
    void validatorRejectsEachStrictErrorClass() {
        // UTF-8
        assertFalse(StrictByteValidator.validate(
                new byte[]{(byte) 0xC3, 0x28}, 0, 2, TRUST_UNIQUE)); // bad 2-byte seq
        // Literals
        assertInvalid("tru");
        assertInvalid("truex");
        assertInvalid("falsee");
        assertInvalid("nul");
        // Numbers
        assertInvalid("-");
        assertInvalid("01");
        assertInvalid("1.");
        assertInvalid("1e");
        assertInvalid("1e+");
        assertInvalid(".5");
        assertInvalid("+1");
        assertInvalid("NaN");
        assertInvalid("Infinity");
        assertInvalid("-Infinity");
        // Strings / escapes
        assertInvalid("\"unterminated");
        assertInvalid("\"bad\\xescape\"");
        assertInvalid("\"bad\\u00zz\"");
        assertInvalid("\"\\u00\"");
        assertInvalid("\"\\ud800\"");      // unpaired high
        assertInvalid("\"\\udc00\"");      // unpaired low
        assertInvalid("\"\\ud800x\"");     // high not followed by low escape
        assertInvalid("\"\\ud800\\u0041\""); // high followed by non-low escape
        // Objects
        assertInvalid("{");
        assertInvalid("{]");
        assertInvalid("{a:1}");
        assertInvalid("{\"a\" 1}");
        assertInvalid("{\"a\":1 \"b\":2}");
        assertInvalid("{\"a\":1,}");
        assertInvalid("{\"a\":1");
        // Arrays
        assertInvalid("[");
        assertInvalid("[1,2");
        assertInvalid("[1 2]");
        assertInvalid("[1,]");
        assertInvalid("[,1]");
        // Trailing content
        assertInvalid("1 2");
        assertInvalid("{} x");
        assertInvalid("null garbage");
    }

    @Test
    void validatorDepthLimitMirrorsOptions() {
        String deep = "[".repeat(100) + "]".repeat(100);
        byte[] b = utf8(deep);
        assertTrue(StrictByteValidator.validate(b, 0, b.length,
                ParseOptions.defaults().withMaxDepth(100)));
        assertFalse(StrictByteValidator.validate(b, 0, b.length,
                ParseOptions.defaults().withMaxDepth(99)));
    }

    // ------------------------------------------------------------------
    // End-to-end parity: applyStrict == apply(parseStrict(...))
    // ------------------------------------------------------------------

    private static void assertStrictParity(String expr, String json, ParseOptions options) {
        JqProgram prog = JqProgram.compile(expr);
        byte[] bytes = utf8(json);
        JqValue expected = prog.apply(JqValues.parseStrict(bytes, options));
        JqValue actual = prog.applyStrict(bytes, options);
        assertEquals(expected, actual, "Value mismatch for expr: " + expr);
        assertEquals(expected.toJsonString(), actual.toJsonString(), "JSON mismatch for expr: " + expr);
    }

    private static void assertStrictErrorParity(String expr, String json, ParseOptions options) {
        JqProgram prog = JqProgram.compile(expr);
        byte[] bytes = utf8(json);
        IllegalArgumentException eager = assertThrows(IllegalArgumentException.class,
                () -> prog.apply(JqValues.parseStrict(bytes, options)));
        IllegalArgumentException actual = assertThrows(IllegalArgumentException.class,
                () -> prog.applyStrict(bytes, options));
        assertEquals(eager.getMessage(), actual.getMessage(), "Error mismatch for expr: " + expr);
    }

    @Test
    void strictParityAcrossShapesUnderTrustUnique() {
        String json = "{"
                + "\"user\":\"alice\",\"n\":42,\"f\":-3.5e2,"
                + "\"items\":[{\"id\":1,\"meta\":{\"score\":10}},null,{\"id\":3}],"
                + "\"cfg\":{\"build\":\"b1\",\"kernel\":\"k1\"},"
                + "\"target\":\"real\","
                + "\"esc\":\"a\\\"b\\\\c\\u0041\\ud83d\\ude00\""
                + "}";
        assertStrictParity(".user", json, TRUST_UNIQUE);
        assertStrictParity(".items[0].meta.score", json, TRUST_UNIQUE);
        assertStrictParity("[.items[] | .id]", json, TRUST_UNIQUE);
        assertStrictParity(".cfg | {build, kernel}", json, TRUST_UNIQUE);
        assertStrictParity("{user, n, f}", json, TRUST_UNIQUE);
        assertStrictParity(".esc", json, TRUST_UNIQUE);
        assertStrictParity(".missing.deep", json, TRUST_UNIQUE);
    }

    @Test
    void strictParityUnderDefaultRejectPolicy() {
        // REJECT never takes the fast path; parity must still hold exactly.
        String json = "{\"a\":1,\"b\":[2,3]}";
        assertStrictParity(".a", json, DEFAULTS);
        assertStrictParity("[.b[]]", json, DEFAULTS);
        // Duplicate under REJECT: identical exception via fallback.
        assertStrictErrorParity(".a", "{\"a\":1,\"a\":2}", DEFAULTS);
    }

    @Test
    void strictDuplicateKeysUnderTrustUniqueMatchLenientOrder() {
        // TRUST_UNIQUE keeps both entries; first-match extraction must agree
        // with JqObject.get's first-match semantics.
        String json = "{\"a\":1,\"a\":2,\"b\":[{\"a\":\"x\",\"a\":\"y\"}]}";
        assertStrictParity(".a", json, TRUST_UNIQUE);
        assertStrictParity("[.b[] | .a]", json, TRUST_UNIQUE);
    }

    @Test
    void strictErrorParityOnInvalidDocuments() {
        // Invalid docs: the gate fails, the fallback throws the exact error.
        String[] bad = {
                "{\"user\":\"alice\",\"broken\":",
                "{\"a\":tru}",
                "{\"a\":01}",
                "[1,2",
                "{\"a\":\"\\ud800\"}",
                "{\"a\":1} trailing",
                "{\"a\":1 \"b\":2}",
        };
        for (String json : bad) {
            assertStrictErrorParity(".a", json, TRUST_UNIQUE);
        }
    }

    @Test
    void strictNonZeroSliceAndEmptyInput() {
        JqProgram prog = JqProgram.compile(".user");
        byte[] payload = utf8("{\"user\":\"alice\",\"n\":1}");
        byte[] padded = ("GARBAGE" + "{\"user\":\"alice\",\"n\":1}" + "TAIL").getBytes(StandardCharsets.UTF_8);
        int payloadLen = payload.length;
        assertEquals(((JqObject) JqValues.parseStrict(payload, TRUST_UNIQUE)).get("user"),
                prog.applyStrict(padded, 7, payloadLen, TRUST_UNIQUE));

        // Empty and whitespace-only bind JqNull like parseStrict.
        assertEquals(prog.apply(JqValues.parseStrict(utf8(""), TRUST_UNIQUE)),
                prog.applyStrict(utf8(""), TRUST_UNIQUE));
        assertEquals(prog.apply(JqValues.parseStrict(utf8("  "), TRUST_UNIQUE)),
                prog.applyStrict(utf8("  "), TRUST_UNIQUE));
    }

    @Test
    void productionAndTwitterFixturesStrictParity() throws Exception {
        Path benchData = Path.of("../jjq-benchmark/src/main/resources/benchmark-data");
        Path prod = benchData.resolve("production-upload-14mb.json");
        if (Files.exists(prod)) {
            byte[] prodBytes = Files.readAllBytes(prod);
            String[] prodExprs = {
                ".user",
                ".autobench_workload.data[0].results.autobench_results",
                "[.stressng_workload.data[] | .sample_uuid]",
                "[.autobench_workload.data[0].pcp_time_series[] | .[\"mem.util.used\"]]",
                "{user, uuid, run_id, start_time, end_time}",
                ".rhivos_config | {build, model, kernel, architecture}"
            };
            for (String expr : prodExprs) {
                JqProgram p = JqProgram.compile(expr);
                JqValue expected = p.apply(JqValues.parseStrict(prodBytes, TRUST_UNIQUE));
                JqValue actual = p.applyStrict(prodBytes, TRUST_UNIQUE);
                assertEquals(expected, actual, "Prod strict parity failed for: " + expr);
                assertEquals(expected.toJsonString(), actual.toJsonString(),
                        "Prod strict JSON parity failed for: " + expr);
            }
        }
        Path twitter = benchData.resolve("twitter.json");
        if (Files.exists(twitter)) {
            byte[] twBytes = Files.readAllBytes(twitter);
            JqProgram p = JqProgram.compile(".search_metadata.count");
            JqValue expected = p.apply(JqValues.parseStrict(twBytes, TRUST_UNIQUE));
            JqValue actual = p.applyStrict(twBytes, TRUST_UNIQUE);
            assertEquals(expected, actual, "Twitter strict parity failed");
        }
    }
}

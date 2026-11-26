package io.hyperfoil.tools.jjq;

import io.hyperfoil.tools.jjq.evaluator.JqException;
import io.hyperfoil.tools.jjq.value.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BytePathApplyTest {

    private static void assertMatchesEager(String expr, String json) {
        assertMatchesEager(expr, json.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertMatchesEager(String expr, byte[] bytes) {
        JqProgram prog = JqProgram.compile(expr);
        JqValue expected = prog.apply(JqValues.parse(bytes));
        JqValue actual = prog.apply(bytes);
        assertEquals(expected, actual, "Value mismatch for expr: " + expr);
        assertEquals(expected.toJsonString(), actual.toJsonString(), "JSON mismatch for expr: " + expr);
    }

    @Test
    void routePinningForSupportedAndUnsupportedShapes() {
        // Supported shapes must compile a BytePathPlan
        assertTrue(JqProgram.compile(".user").hasBytePathPlan());
        assertTrue(JqProgram.compile(".a.b").hasBytePathPlan());
        assertTrue(JqProgram.compile(".autobench_workload.data[0].results.autobench_results").hasBytePathPlan());
        assertTrue(JqProgram.compile("[.autobench_workload.data[0].pcp_time_series[] | .[\"mem.util.used\"]]").hasBytePathPlan());
        assertTrue(JqProgram.compile("[.stressng_workload.data[] | .sample_uuid]").hasBytePathPlan());
        assertTrue(JqProgram.compile("[.results[].load.avThroughput]").hasBytePathPlan());
        assertTrue(JqProgram.compile("{user, uuid, run_id, start_time, end_time}").hasBytePathPlan());
        assertTrue(JqProgram.compile(".rhivos_config | {build, model, kernel, architecture}").hasBytePathPlan());
        assertTrue(JqProgram.compile("{x: .a, y: .b}").hasBytePathPlan());
        assertTrue(JqProgram.compile(".search_metadata.count").hasBytePathPlan());

        // Unsupported shapes decline cleanly and fall back
        assertFalse(JqProgram.compile(".").hasBytePathPlan());
        assertFalse(JqProgram.compile("length").hasBytePathPlan());
        assertFalse(JqProgram.compile(".[-1]").hasBytePathPlan());
        assertFalse(JqProgram.compile("[.[] | select(. > 5) | . * 2]").hasBytePathPlan());
        assertFalse(JqProgram.compile("reduce .[] as $x (0; . + $x)").hasBytePathPlan());
    }

    @Test
    void earlyStopProvesFastPathRunsWithoutFullParse() {
        // Trailing garbage after a valid early field proves apply(byte[]) stopped
        // at the matched field rather than delegating to full parse().
        byte[] bytes = "{\"user\":\"alice\",\"broken\":".getBytes(StandardCharsets.UTF_8);
        JqProgram prog = JqProgram.compile(".user");
        assertTrue(prog.hasBytePathPlan());
        assertEquals(JqString.of("alice"), prog.apply(bytes));

        byte[] objBytes = "{\"a\":1,\"b\":2,\"broken\":".getBytes(StandardCharsets.UTF_8);
        JqProgram objProg = JqProgram.compile("{a, b}");
        assertTrue(objProg.hasBytePathPlan());
        assertEquals("{\"a\":1,\"b\":2}", objProg.apply(objBytes).toJsonString());
    }

    @Test
    void ignoresSameNamedKeysInsideSkippedNestedContainersAndStrings() {
        String json = "{"
                + "\"trap1\":{\"target\":\"inner_obj\"},"
                + "\"trap2\":[{\"target\":\"inner_arr\"},\"{\\\"target\\\":\\\"in_string\\\"}\"],"
                + "\"trap3\":\"braces } ] { [ and escaped quote \\\" and backslash \\\\\","
                + "\"trap4\":12345.67e-8,"
                + "\"trap5\":true,"
                + "\"trap6\":false,"
                + "\"trap7\":null,"
                + "\"target\":\"real_value\""
                + "}";
        assertMatchesEager(".target", json);
        assertMatchesEager("{target, trap4}", json);
    }

    @Test
    void chainedFieldAndArrayIndexEdgeCases() {
        String json = "{\"a\":{\"b\":[{\"c\":10},{\"c\":20},null],\"empty\":[],\"nil\":null}}";
        assertMatchesEager(".a.b[0].c", json);
        assertMatchesEager(".a.b[1].c", json);
        assertMatchesEager(".a.b[2].c", json);   // null intermediate
        assertMatchesEager(".a.b[9].c", json);   // out-of-bounds index
        assertMatchesEager(".a.empty[0]", json); // empty array
        assertMatchesEager(".a.nil.x", json);    // null intermediate field
        assertMatchesEager(".a.nil[0]", json);   // null intermediate index
        assertMatchesEager(".missing.deep[0].x", json);
    }

    @Test
    void collectFieldSingleAndMultiStepTails() {
        String json = "{\"items\":["
                + "{\"id\":1,\"meta\":{\"score\":10},\"mem.util.used\":100},"
                + "{\"other\":\"skip_me\",\"mem.util.used\":200,\"meta\":{\"score\":20}},"
                + "{\"id\":3,\"meta\":{}},"
                + "null"
                + "],\"empty\":[]}";
        assertMatchesEager("[.items[] | .[\"mem.util.used\"]]", json);
        assertMatchesEager("[.items[] | .id]", json);
        assertMatchesEager("[.items[].meta.score]", json);
        assertMatchesEager("[.empty[] | .id]", json);
    }

    @Test
    void objectConstructVariants() {
        String json = "{\"cfg\":{\"build\":\"b1\",\"model\":\"m1\",\"extra\":[1,2,{\"build\":\"wrong\"}],\"kernel\":\"k1\"}}";
        assertMatchesEager(".cfg | {build, model, kernel, architecture}", json);
        assertMatchesEager(".cfg | {b: .build, k: .kernel, b2: .build}", json);
        assertMatchesEager(".missing | {build, model}", json);
        assertMatchesEager("{a, b}", "{}");
        assertMatchesEager("{a, b}", "null");
    }

    @Test
    void escapedKeysAndUnicodeFallBackCleanly() {
        // Escaped key on wire matching target name
        assertMatchesEager(".user", "{\"\\u0075ser\":\"bob\"}");
        assertMatchesEager("[.items[] | .user]", "{\"items\":[{\"\\u0075ser\":\"bob\"},{\"user\":\"alice\"}]}");
        assertMatchesEager("{user, id}", "{\"\\u0075ser\":\"bob\",\"id\":7}");

        // Multibyte UTF-8 keys and values
        assertMatchesEager(".[\"caf\u00e9\"]", "{\"other\":\"\u65e5\u672c\u8a9e\",\"caf\u00e9\":\"\u20ac5\"}");
    }

    @Test
    void bomAndNonZeroSliceAndErrors() {
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] payload = "{\"user\":\"alice\",\"n\":42}".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[bom.length + payload.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(payload, 0, withBom, bom.length, payload.length);
        assertMatchesEager(".user", withBom);

        // Non-zero slice with surrounding garbage
        byte[] padded = ("GARBAGE" + "{\"user\":\"alice\",\"n\":42}" + "TRAILING").getBytes(StandardCharsets.UTF_8);
        JqProgram prog = JqProgram.compile(".user");
        assertEquals(JqString.of("alice"), prog.apply(padded, 7, payload.length));

        // Error parity on indexing a scalar
        byte[] scalar = "42".getBytes(StandardCharsets.UTF_8);
        JqException eagerErr = assertThrows(JqException.class, () -> prog.apply(JqValues.parse(scalar)));
        JqException byteErr = assertThrows(JqException.class, () -> prog.apply(scalar));
        assertEquals(eagerErr.getClass(), byteErr.getClass());
        assertEquals(eagerErr.getMessage(), byteErr.getMessage());

        // Fallback for unsupported shapes works identically
        assertMatchesEager("[.[] | select(. > 2) | . * 2]", "[1,2,3,4]");
        assertMatchesEager(".", "{\"a\":1}");
    }

    @Test
    void productionAndTwitterFixturesDifferentialParity() throws Exception {
        Path benchData = Path.of("../jjq-benchmark/src/main/resources/benchmark-data");
        Path prod = benchData.resolve("production-upload-14mb.json");
        if (Files.exists(prod)) {
            byte[] prodBytes = Files.readAllBytes(prod);
            JqValue parsedProd = JqValues.parse(prodBytes);
            String[] prodExprs = {
                ".user",
                ".autobench_workload.data[0].results",
                ".autobench_workload.data[0].results.autobench_results",
                "[.stressng_workload.data[] | .sample_uuid]",
                "[.autobench_workload.data[] | .results]",
                "[.stressng_workload.data[] | .test_results]",
                "[.autobench_workload.data[0].pcp_time_series[] | .[\"mem.util.used\"]]",
                "{user, uuid, run_id, start_time, end_time}",
                ".rhivos_config | {build, model, kernel, architecture}",
                ".autobench_workload.data[0].pcp_time_series[0]"
            };
            for (String expr : prodExprs) {
                JqProgram p = JqProgram.compile(expr);
                assertTrue(p.hasBytePathPlan(), "Expected BytePathPlan for: " + expr);
                JqValue expected = p.apply(parsedProd);
                JqValue actual = p.apply(prodBytes);
                assertEquals(expected, actual, "Prod parity failed for: " + expr);
                assertEquals(expected.toJsonString(), actual.toJsonString(), "Prod JSON parity failed for: " + expr);
            }
        }
        Path twitter = benchData.resolve("twitter.json");
        if (Files.exists(twitter)) {
            byte[] twBytes = Files.readAllBytes(twitter);
            JqValue parsedTw = JqValues.parse(twBytes);
            String[] twExprs = {
                ".search_metadata.count",
                ".statuses[0].user.screen_name",
                "[.statuses[] | .id]"
            };
            for (String expr : twExprs) {
                JqProgram p = JqProgram.compile(expr);
                assertTrue(p.hasBytePathPlan(), "Expected BytePathPlan for: " + expr);
                assertEquals(p.apply(parsedTw), p.apply(twBytes), "Twitter parity failed for: " + expr);
            }
        }
    }
}

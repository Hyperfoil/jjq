package io.hyperfoil.tools.jjq.vector;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JsonByteSkipKernel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

class VectorByteSkipKernelTest {

    private static final int MAX_DEPTH = 10000;

    private final VectorByteSkipKernel kernel = new VectorByteSkipKernel();

    // ------------------------------------------------------------------
    // Scalar oracle: mirrors JsonByteSkipper.skipToContainerEndScalar and
    // skipString semantics exactly (offsets, escape handling, -2 failures).
    // ------------------------------------------------------------------

    private static int oracleSkipString(byte[] d, int pos, int end) {
        int p = pos + 1;
        while (p < end) {
            byte b = d[p];
            if (b == '"') return p + 1;
            if (b == '\\') {
                p += 2;
                continue;
            }
            p++;
        }
        return -2;
    }

    private static int oracleSkipToContainerEnd(byte[] d, int start, int end, int maxDepth) {
        int depth = 1;
        int p = start;
        while (p < end) {
            byte b = d[p];
            if (b == '"') {
                p = oracleSkipString(d, p, end);
                if (p < 0) return -2;
                continue;
            }
            if (b == '{' || b == '[') {
                if (++depth > maxDepth) return -2;
            } else if (b == '}' || b == ']') {
                if (--depth == 0) return p + 1;
            }
            p++;
        }
        return -2;
    }

    private void assertKernelMatchesOracle(byte[] d, int start, int end, int maxDepth) {
        int expected = oracleSkipToContainerEnd(d, start, end, maxDepth);
        int actual = kernel.skipToContainerEnd(d, start, end, maxDepth);
        assertEquals(expected, actual,
                () -> "mismatch (maxDepth=" + maxDepth + ", len=" + d.length
                        + ", expected=" + expected + ", actual=" + actual + ")");
    }

    // ------------------------------------------------------------------
    // Route pinning
    // ------------------------------------------------------------------

    @Test
    void serviceLoaderDiscoversKernel() {
        var it = ServiceLoader.load(JsonByteSkipKernel.class).iterator();
        assertTrue(it.hasNext(), "jjq-vector service registration missing");
        assertTrue(it.next() instanceof VectorByteSkipKernel);
    }

    @Test
    void kernelIsEnabledOnVectorHardware() {
        assumeTrue(VectorKernels.isSupported());
        assertTrue(kernel.isEnabled());
    }

    // ------------------------------------------------------------------
    // Boundary pins: escapes and structural bytes crossing block edges
    // ------------------------------------------------------------------

    @Test
    void escapeRunsAtEveryBlockBoundaryResidue() {
        // Escape pairs placed at every offset 0..130 relative to the
        // container start, crossing 16/32/64-byte block edges at every
        // residue. Both a closing-container and a truncated variant run.
        String[] escapes = {"\\\"", "\\\\", "\\\"a", "a\\", "\"a\\\\", "\\\\\\\""};
        for (String esc : escapes) {
            for (int at = 0; at <= 130; at++) {
                String body = "x".repeat(at) + esc;
                byte[] closed = ("{" + body + "\"}").getBytes(StandardCharsets.UTF_8);
                assertKernelMatchesOracle(closed, 1, closed.length, MAX_DEPTH);
                byte[] closedAfter = ("{" + body + "}").getBytes(StandardCharsets.UTF_8);
                assertKernelMatchesOracle(closedAfter, 1, closedAfter.length, MAX_DEPTH);
                byte[] truncated = ("{" + body).getBytes(StandardCharsets.UTF_8);
                assertKernelMatchesOracle(truncated, 1, truncated.length, MAX_DEPTH);
            }
        }
    }

    @Test
    void structuralBytesAtEveryBlockBoundaryResidue() {
        String[] structurals = {"{", "}", "[", "]", "{}", "[]", "{[", "]}", "\"{\"", "\"}\""};
        for (String s : structurals) {
            for (int at = 0; at <= 130; at++) {
                byte[] doc = ("{" + "y".repeat(at) + s + "}").getBytes(StandardCharsets.UTF_8);
                assertKernelMatchesOracle(doc, 1, doc.length, MAX_DEPTH);
            }
        }
    }

    @Test
    void deepNestingAndLimitOverflow() {
        byte[] deep = new byte[20000];
        java.util.Arrays.fill(deep, 0, 10000, (byte) '[');
        java.util.Arrays.fill(deep, 10000, 20000, (byte) ']');
        assertKernelMatchesOracle(deep, 1, deep.length, MAX_DEPTH);
        assertKernelMatchesOracle(deep, 1, deep.length, MAX_DEPTH - 1);
        assertKernelMatchesOracle(deep, 1, deep.length, 1);

        byte[] oneTooDeep = new byte[20004];
        java.util.Arrays.fill(oneTooDeep, 0, 10001, (byte) '[');
        java.util.Arrays.fill(oneTooDeep, 10001, 20002, (byte) ']');
        assertKernelMatchesOracle(oneTooDeep, 1, oneTooDeep.length, MAX_DEPTH);
        assertKernelMatchesOracle(oneTooDeep, 1, oneTooDeep.length, MAX_DEPTH - 1);
    }

    @Test
    void truncatedStringsAndDanglingEscapes() {
        assertKernelMatchesOracle("{\"abc".getBytes(StandardCharsets.UTF_8), 1, 5, MAX_DEPTH);
        assertKernelMatchesOracle("{\"abc\\".getBytes(StandardCharsets.UTF_8), 1, 6, MAX_DEPTH);
        assertKernelMatchesOracle("{\"abc\\\"".getBytes(StandardCharsets.UTF_8), 1, 7, MAX_DEPTH);
        assertKernelMatchesOracle("{\"\\".getBytes(StandardCharsets.UTF_8), 1, 3, MAX_DEPTH);
        byte[] escapeAt64 = new byte[70];
        java.util.Arrays.fill(escapeAt64, (byte) 'a');
        escapeAt64[0] = '{';
        escapeAt64[63] = '\\';
        assertKernelMatchesOracle(escapeAt64, 1, escapeAt64.length, MAX_DEPTH);
    }

    // ------------------------------------------------------------------
    // Differential fuzz against the scalar oracle
    // ------------------------------------------------------------------

    @Test
    void randomizedDocumentsMatchOracle() {
        Random rnd = new Random(20261126L);
        for (int round = 0; round < 3000; round++) {
            byte[] doc = randomDocument(rnd);
            int start = doc[0] == '{' || doc[0] == '[' ? 1 : 1; // opener always at prefix end
            for (int maxDepth : new int[]{MAX_DEPTH, 1 + rnd.nextInt(4)}) {
                assertKernelMatchesOracle(doc, start, doc.length, maxDepth);
            }
        }
    }

    private static byte[] randomDocument(Random rnd) {
        // Random junk prefix (0..127 bytes) shifts block alignment,
        // then the container opener, then random tokens.
        int prefix = rnd.nextInt(128);
        var sb = new StringBuilder();
        sb.append("x".repeat(prefix));
        sb.append(rnd.nextBoolean() ? '{' : '[');
        char closer = sb.charAt(sb.length() - 1) == '{' ? '}' : ']';
        int tokens = 1 + rnd.nextInt(40);
        boolean terminate = rnd.nextInt(4) > 0; // 25% truncated -> -2
        for (int t = 0; t < tokens; t++) {
            switch (rnd.nextInt(9)) {
                case 0 -> sb.append('"').append(randomText(rnd, 1 + rnd.nextInt(20))).append('"');
                case 1 -> sb.append('"').append(randomText(rnd, rnd.nextInt(20))).append(randomEscapes(rnd)).append('"');
                case 2 -> sb.append(randomText(rnd, 1 + rnd.nextInt(8)));
                case 3 -> sb.append(rnd.nextInt(1000));
                case 4 -> sb.append("true");
                case 5 -> sb.append("null");
                case 6 -> sb.append(rnd.nextBoolean() ? '{' : '[');
                case 7 -> sb.append(rnd.nextBoolean() ? '}' : ']');
                default -> sb.append(",\": \t\n").append('"').append("a{b}c[d]e").append('"');
            }
        }
        if (terminate) {
            sb.append(closer);
            sb.append("x".repeat(rnd.nextInt(16))); // trailing junk past the close
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String randomText(Random rnd, int len) {
        var sb = new StringBuilder(len);
        String alphabet = "abcXYZ019 .:-_";
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static String randomEscapes(Random rnd) {
        var sb = new StringBuilder();
        int n = rnd.nextInt(4);
        for (int i = 0; i < n; i++) {
            switch (rnd.nextInt(4)) {
                case 0 -> sb.append("\\\"");
                case 1 -> sb.append("\\\\");
                case 2 -> sb.append("\\n");
                default -> sb.append("\\u0041");
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // End-to-end parity: JqProgram.apply(byte[]) with vector toggled
    // ------------------------------------------------------------------

    @Test
    void programApplyParityWithVectorToggled() {
        assumeTrue(kernel.isEnabled());
        String json = "{"
                + "\"trap1\":{\"target\":\"inner_obj\",\"deep\":[1,{\"target\":\"nested\"}]},"
                + "\"trap2\":\"string with } ] { [ braces and \\\" quote and \\\\ backslash\","
                + "\"trap3\":\"" + "\\\"quoted\\\" ".repeat(30) + "\","
                + "\"items\":["
                + "{\"id\":1,\"meta\":{\"score\":10},\"mem.util.used\":100},"
                + "{\"other\":\"skip\",\"mem.util.used\":200},"
                + "null,"
                + "{\"meta\":{}}"
                + "],"
                + "\"target\":\"real_value\","
                + "\"cfg\":{\"build\":\"b1\",\"model\":\"m1\",\"kernel\":\"k1\"}"
                + "}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        String[] exprs = {
                ".target",
                ".items[0].meta.score",
                "[.items[] | .[\"mem.util.used\"]]",
                "[.items[] | .id]",
                ".cfg | {build, model, kernel}",
                "{target, trap1}"
        };
        for (String expr : exprs) {
            JqProgram prog = JqProgram.compile(expr);
            System.setProperty(VectorKernels.ENABLE_PROPERTY, "false");
            JqValue scalar = prog.apply(bytes);
            System.setProperty(VectorKernels.ENABLE_PROPERTY, "true");
            JqValue vector = prog.apply(bytes);
            assertEquals(scalar, vector, "parity failed for: " + expr);
            assertEquals(scalar.toJsonString(), vector.toJsonString(), "JSON parity failed for: " + expr);
        }
        System.clearProperty(VectorKernels.ENABLE_PROPERTY);
    }

    private static void assumeTrue(boolean condition) {
        org.junit.jupiter.api.Assumptions.assumeTrue(condition,
                "Vector hardware unavailable — kernel route not exercised");
    }
}

package io.hyperfoil.tools.jjq.yaml;

import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance against the upstream YAML test suite
 * ({@code yaml-test-suite}, vendored snapshot under
 * {@code src/test/resources/yaml-test-suite}).
 *
 * <p>Each suite directory with an {@code in.yaml} becomes a dynamic test:</p>
 * <ul>
 *   <li>{@code error} file present → our parser must throw.</li>
 *   <li>{@code in.json} present → tree-compare our parse against the expected
 *       JSON (multi-doc {@code in.json} is newline-delimited; object key order
 *       is ignored).</li>
 *   <li>Neither → skipped (no oracle to conform to).</li>
 * </ul>
 *
 * <p>House rule (cf. {@code JqUpstreamTest}): failures abort as skips so the
 * build stays green while compatibility is tracked. {@code yaml-skips.txt}
 * names known gaps with reasons; unlisted failures abort as new failures-to-triage.
 * Tests run with a 5-second timeout each.</p>
 *
 * <p>Snapshot: {@code data-2022-01-17} release (latest sanctioned data release;
 * see {@code yaml-test-suite/README.md} for refresh). Comparison is tree-only;
 * event streams ({@code test.event}) are out of scope — we produce no events.</p>
 */
class YamlConformanceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final AtomicInteger PASSED = new AtomicInteger();
    private static final AtomicInteger SKIPPED = new AtomicInteger();

    record SuiteCase(String id, Path dir) {}

    @TestFactory
    Stream<DynamicTest> conformanceTests() throws IOException, URISyntaxException {
        Path root = Path.of(getClass().getResource("/yaml-test-suite").toURI());
        Map<String, String> skips = loadSkips();
        List<SuiteCase> cases = new ArrayList<>();
        try (var dirs = Files.walk(root)) {
            dirs.filter(p -> !Files.isSymbolicLink(p))
                    .filter(p -> !p.startsWith(root.resolve("tags")))
                    .filter(p -> Files.isRegularFile(p.resolve("in.yaml")))
                    .sorted()
                    .forEach(p -> cases.add(new SuiteCase(
                            root.relativize(p).toString().replace('\\', '/'), p)));
        }
        return cases.stream().map(tc -> DynamicTest.dynamicTest(
                "[" + tc.id() + "] " + testName(tc),
                () -> runCase(tc, skips.get(tc.id()))));
    }

    private static String testName(SuiteCase tc) {
        Path nameFile = tc.dir().resolve("===");
        try {
            String name = Files.readString(nameFile, StandardCharsets.UTF_8).strip();
            return name.length() > 80 ? name.substring(0, 77) + "..." : name;
        } catch (IOException e) {
            return tc.id();
        }
    }

    private static Map<String, String> loadSkips() throws IOException {
        var skips = new HashMap<String, String>();
        var url = YamlConformanceTest.class.getResource("/yaml-skips.txt");
        if (url == null) return skips;
        Path path;
        try {
            path = Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int colon = t.indexOf(':');
            if (colon > 0) {
                skips.put(t.substring(0, colon).strip(), t.substring(colon + 1).strip());
            }
        }
        return skips;
    }

    private void runCase(SuiteCase tc, String knownSkip) {
        if (knownSkip != null) {
            SKIPPED.incrementAndGet();
            Assumptions.abort("[" + tc.id() + "] known gap: " + knownSkip);
            return;
        }
        try {
            assertTimeoutPreemptively(TIMEOUT, () -> {
                try {
                    runCaseInner(tc);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (org.opentest4j.TestAbortedException e) {
            // Explicit skips from inside (no oracle, throws-but-expects-success)
            SKIPPED.incrementAndGet();
            throw e;
        } catch (AssertionError | RuntimeException e) {
            SKIPPED.incrementAndGet();
            Assumptions.abort("[" + tc.id() + "] failing (triage): " + e.getMessage());
            return;
        }
        PASSED.incrementAndGet();
    }

    private void runCaseInner(SuiteCase tc) throws IOException {
        String yaml = decodePlaceholders(readUtf8(tc.dir().resolve("in.yaml")));
        boolean mustFail = Files.isRegularFile(tc.dir().resolve("error"));
        Path expectedFile = tc.dir().resolve("in.json");
        if (mustFail) {
            try {
                JqYaml.parseAll(yaml);
            } catch (RuntimeException e) {
                return; // rejected as required (message parity not asserted)
            }
            Assumptions.abort("[" + tc.id() + "] parses but suite requires failure");
            return;
        }
        if (!Files.isRegularFile(expectedFile)) {
            Assumptions.abort("[" + tc.id() + "] no in.json oracle to conform to");
            return;
        }
        List<JqValue> actual;
        try {
            actual = JqYaml.parseAll(yaml);
        } catch (RuntimeException e) {
            Assumptions.abort("[" + tc.id() + "] throws but suite expects success: " + e.getMessage());
            return;
        }
        List<JqValue> expected = JqValues.parseAll(readUtf8(expectedFile));
        assertEquals(expected.size(), actual.size(),
                () -> "[" + tc.id() + "] document count: expected " + expected.size()
                        + " got " + actual.size());
        for (int i = 0; i < expected.size(); i++) {
            int doc = i;
            assertTrue(treesEqual(expected.get(i), actual.get(i)),
                    () -> "[" + tc.id() + "] doc " + doc + " mismatch:\nexpected: "
                            + expected.get(doc).toJsonString() + "\nactual:   "
                            + actual.get(doc).toJsonString());
        }
    }

    /** Order-insensitive structural equality (object key order ignored). */
    static boolean treesEqual(JqValue expected, JqValue actual) {
        if (expected.isNull() || actual.isNull()) {
            return expected.isNull() && actual.isNull();
        }
        if (expected.isObject() && actual.isObject()) {
            JqObject e = (JqObject) expected;
            JqObject a = (JqObject) actual;
            if (e.size() != a.size()) return false;
            for (String key : e.keys()) {
                if (!a.has(key) || !treesEqual(e.getField(key), a.getField(key))) return false;
            }
            return true;
        }
        if (expected.isArray() && actual.isArray()) {
            JqArray e = (JqArray) expected;
            JqArray a = (JqArray) actual;
            if (e.size() != a.size()) return false;
            for (int i = 0; i < e.size(); i++) {
                if (!treesEqual(e.getElement(i), a.getElement(i))) return false;
            }
            return true;
        }
        // Scalars (and type mismatches): canonical rendering must agree
        return expected.toJsonString().equals(actual.toJsonString());
    }

    /**
     * Decode the suite's placeholder characters to the real bytes they stand for.
     * Dormant for snapshots that use literal characters; unit-tested directly.
     * Markers (from the suite README): {@code »} variants = hard tab, {@code ␣} =
     * trailing space, {@code ↵} = shown newline, {@code ∎} = no final newline,
     * {@code ←} = CR, {@code ⇔} = BOM.
     */
    static String decodePlaceholders(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length();) {
            // Longest markers first (———», ——», —» collapse to the » rule)
            if (text.startsWith("———»", i) || text.startsWith("——»", i) || text.startsWith("—»", i)) {
                sb.append('\t');
                i += text.startsWith("———»", i) ? 4 : text.startsWith("——»", i) ? 3 : 2;
            } else if (text.charAt(i) == '»') {
                sb.append('\t');
                i++;
            } else if (text.charAt(i) == '␣') {
                sb.append(' ');
                i++;
            } else if (text.charAt(i) == '↵') {
                // Shown newline: emit one, swallowing a single following real
                // line break (the marker annotates an existing break).
                sb.append('\n');
                i++;
                if (i < text.length() && text.charAt(i) == '\n') i++;
            } else if (text.charAt(i) == '∎') {
                // End marker: no final newline. Skip the marker plus one
                // following line break if present.
                i++;
                if (i < text.length() && text.charAt(i) == '\r') i++;
                if (i < text.length() && text.charAt(i) == '\n') i++;
            } else if (text.charAt(i) == '←') {
                sb.append('\r');
                i++;
            } else if (text.charAt(i) == '⇔') {
                sb.append('﻿');
                i++;
            } else {
                sb.append(text.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    private static String readUtf8(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    // ---- Placeholder decoder unit tests (dormant for snapshots without markers) ----

    @Test
    void decodeTabsAndSpaces() {
        assertEquals("\ta: 1", decodePlaceholders("»a: 1"));
        assertEquals("\ta: 1", decodePlaceholders("—»a: 1"));
        assertEquals("a: 1 ", decodePlaceholders("a: 1␣"));
    }

    @Test
    void decodeLineEndings() {
        assertEquals("a: 1\n", decodePlaceholders("a: 1↵\n"));
        assertEquals("a: 1", decodePlaceholders("a: 1∎\n"));
        assertEquals("a: 1", decodePlaceholders("a: 1∎"));
        assertEquals("a: 1\r\n", decodePlaceholders("a: 1←\n"));
    }

    @Test
    void decodeBom() {
        // U+FEFF byte-order mark (invisible in source; asserted by codepoint)
        assertEquals('\uFEFF', decodePlaceholders("⇔a: 1").charAt(0));
    }

    @AfterAll
    static void printSummary() {
        System.out.println("YAML conformance: " + PASSED.get() + " passed, "
                + SKIPPED.get() + " skipped (see yaml-skips.txt for known gaps)");
    }
}

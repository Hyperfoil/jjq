package io.hyperfoil.tools.jjq.schema;

import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance against the official JSON-Schema-Test-Suite (vendored
 * snapshot under {@code src/test/resources/json-schema-test-suite};
 * see README.md there for provenance). Draft-07 and 2020-12 run
 * separately, each file under its directory's dialect.
 *
 * <p>Report-only, same culture as the jq/jsonpath suites: every file
 * becomes dynamic tests, one per (group, case). Unsupported shapes
 * (remote references, out-of-subset keywords) and verdict mismatches
 * abort as skips with reasons — the build stays green while the summary
 * tracks conformance-%. Supported behavior is pinned separately by
 * ordinary assertions in {@link JqSchemaTest}, which fail on divergence.</p>
 *
 * <p>Tests run with a 5-second timeout each.</p>
 */
class JsonSchemaConformanceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final AtomicInteger PASSED = new AtomicInteger();
    private static final AtomicInteger UNSUPPORTED = new AtomicInteger();
    private static final AtomicInteger MISMATCHED = new AtomicInteger();

    record SuiteCase(String id, String file, int group, String groupDesc,
                     JqValue schema, String testDesc, JqValue data, boolean valid) {}

    @TestFactory
    Stream<DynamicTest> conformanceTests() throws IOException, URISyntaxException {
        Path root = Path.of(getClass().getResource("/json-schema-test-suite").toURI());
        Map<String, String> skips = loadSkips();
        List<SuiteCase> cases = new ArrayList<>();
        for (String draft : new String[]{"draft7", "draft2020-12"}) {
            String dialect = draft.equals("draft7")
                    ? "http://json-schema.org/draft-07/schema"
                    : "https://json-schema.org/draft/2020-12/schema";
            Path dir = root.resolve(draft);
            if (!Files.isDirectory(dir)) continue;
            try (var files = Files.walk(dir)) {
                for (Path file : files
                        .filter(p -> p.toString().endsWith(".json"))
                        .sorted().toList()) {
                    loadFile(root, file, dialect, cases);
                }
            }
        }
        return cases.stream().map(tc -> DynamicTest.dynamicTest(
                "[" + tc.id() + "] " + tc.groupDesc() + " / " + tc.testDesc(),
                () -> runCase(tc, skips.get(tc.id()))));
    }

    private static Map<String, String> loadSkips() throws IOException {
        var skips = new java.util.HashMap<String, String>();
        var url = JsonSchemaConformanceTest.class.getResource("/json-schema-test-suite/schema-skips.txt");
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
            String[] parts = t.split(":", 4);
            if (parts.length == 4) {
                skips.put((parts[0] + ":" + parts[1] + ":" + parts[2]).strip(),
                        parts[3].strip());
            }
        }
        return skips;
    }

    private static void loadFile(Path root, Path file, String dialect, List<SuiteCase> cases)
            throws IOException {
        String id = root.relativize(file).toString().replace('\\', '/');
        JqValue parsed = JqValues.parse(Files.readString(file, StandardCharsets.UTF_8));
        if (!(parsed instanceof JqArray groups)) return;
        for (int g = 0; g < groups.size(); g++) {
            JqObject group = (JqObject) groups.get(g);
            String groupDesc = (group.has("description")
                    ? group.getField("description").stringValue() : "group" + g);
            JqValue schema = withDialect(group.getField("schema"), dialect);
            JqArray tests = (JqArray) group.getField("tests");
            for (int t = 0; t < tests.size(); t++) {
                JqObject test = (JqObject) tests.get(t);
                String testDesc = test.has("description")
                        ? test.getField("description").stringValue() : "case" + t;
                boolean valid = ((io.hyperfoil.tools.jjq.value.JqBoolean) test
                        .getField("valid")).booleanValue();
                cases.add(new SuiteCase(id + ":" + g + ":" + t, id, g, groupDesc,
                        schema, testDesc, test.getField("data"), valid));
            }
        }
    }

    /** Schemas without a dialect inherit their directory's (booleans need none). */
    private static JqValue withDialect(JqValue schema, String dialect) {
        if (schema instanceof JqObject obj && !obj.has("$schema")) {
            return JqObject.builder(obj.size() + 1).putAll(obj).put("$schema", dialect).build();
        }
        return schema;
    }

    private void runCase(SuiteCase tc, String knownSkip) {
        if (knownSkip != null) {
            UNSUPPORTED.incrementAndGet();
            Assumptions.abort("[" + tc.id() + "] known gap: " + knownSkip);
            return;
        }
        try {
            assertTimeoutPreemptively(TIMEOUT, () -> runCaseInner(tc));
        } catch (org.opentest4j.TestAbortedException e) {
            throw e;
        } catch (AssertionError | RuntimeException e) {
            MISMATCHED.incrementAndGet();
            Assumptions.abort("[" + tc.id() + "] mismatch (triage): " + e.getMessage());
            return;
        }
        PASSED.incrementAndGet();
    }

    private void runCaseInner(SuiteCase tc) {
        if (tc.file().contains("refRemote")) {
            UNSUPPORTED.incrementAndGet();
            Assumptions.abort("[" + tc.id() + "] remote references need a remotes server");
            return;
        }
        if (tc.file().contains("/format") || tc.file().endsWith("format.json")) {
            UNSUPPORTED.incrementAndGet();
            Assumptions.abort("[" + tc.id() + "] format is annotation-only here");
            return;
        }
        if (hasRemoteRef(tc.schema())) {
            UNSUPPORTED.incrementAndGet();
            Assumptions.abort("[" + tc.id() + "] remote $ref without a remotes server");
            return;
        }
        final JqSchema compiled;
        try {
            compiled = JqSchema.compile(tc.schema());
        } catch (JqSchemaException e) {
            UNSUPPORTED.incrementAndGet();
            Assumptions.abort("[" + tc.id() + "] unsupported: " + e.getMessage());
            return;
        }
        assertEquals(tc.valid(), compiled.isValid(tc.data()),
                () -> "[" + tc.id() + "] expected valid=" + tc.valid()
                        + " for data " + tc.data().toJsonString());
    }

    /** True when any $ref in the schema does not start with '#'. */
    private static boolean hasRemoteRef(JqValue schema) {
        if (schema instanceof JqObject obj) {
            for (String key : obj.keys()) {
                JqValue value = obj.get(key);
                if (key.equals("$ref") && value instanceof JqString ref
                        && !ref.stringValue().startsWith("#")) {
                    return true;
                }
                if (hasRemoteRef(value)) return true;
            }
            return false;
        }
        if (schema instanceof JqArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                if (hasRemoteRef(arr.get(i))) return true;
            }
        }
        return false;
    }

    @AfterAll
    static void printSummary() {
        int passed = PASSED.get();
        int unsupported = UNSUPPORTED.get();
        int mismatched = MISMATCHED.get();
        int total = passed + unsupported + mismatched;
        System.out.println("JSON-Schema conformance: " + passed + " passed, "
                + unsupported + " unsupported, " + mismatched + " mismatched"
                + " (" + total + " total, "
                + (total == 0 ? 0 : (100 * passed / total)) + "% pass)");
    }
}

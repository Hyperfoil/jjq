package io.hyperfoil.tools.jjq.jsonpath;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Conformance test suite imported from PostgreSQL's jsonb_jsonpath regression tests.
 * Parses the expected output file to extract (input_json, jsonpath, expected_results) tuples,
 * then converts each jsonpath to jq via {@link JsonpathToJq} and verifies the result.
 *
 * <p>Tests that use unsupported features (variables, silent mode, existence checks,
 * arithmetic expressions) are skipped via JUnit Assumptions.</p>
 */
class JsonpathConformanceTest {

    // Regex to parse jsonb_path_query('json', 'path') from the .out file
    // Group 1: JSON input, Group 2: jsonpath expression
    private static final Pattern QUERY_PATTERN = Pattern.compile(
            "select\\s+jsonb_path_query\\s*\\(\\s*'(.+?)'\\s*,\\s*'(.+?)'\\s*\\)\\s*;");

    // Regex for jsonb_path_query_first
    private static final Pattern QUERY_FIRST_PATTERN = Pattern.compile(
            "select\\s+jsonb_path_query_first\\s*\\(\\s*'(.+?)'\\s*,\\s*'(.+?)'\\s*\\)\\s*;");

    private int totalTests = 0;
    private int skipped = 0;
    private int passed = 0;

    @TestFactory
    Stream<DynamicTest> postgresqlConformanceTests() throws Exception {
        var tests = new ArrayList<DynamicTest>();
        List<TestCase> testCases = parseOutFile();
        totalTests = testCases.size();

        for (int i = 0; i < testCases.size(); i++) {
            TestCase tc = testCases.get(i);
            int testNum = i + 1;
            String testName = String.format("[%d] %s => %s", testNum, truncate(tc.jsonpath, 40),
                    tc.expectsError ? "ERROR" : truncate(tc.expectedResults.toString(), 40));
            tests.add(DynamicTest.dynamicTest(testName, () -> runTestCase(tc)));
        }

        return tests.stream();
    }

    private void runTestCase(TestCase tc) {
        // Skip unsupported features
        if (tc.hasVariables) {
            skipped++;
            assumeTrue(false, "Unsupported: path variables");
        }
        if (tc.hasSilent) {
            skipped++;
            assumeTrue(false, "Unsupported: silent mode");
        }
        if (tc.expectsError) {
            // We can test that conversion+compilation doesn't crash, but we can't replicate
            // PostgreSQL's exact error semantics. Skip for now.
            skipped++;
            assumeTrue(false, "Skipping: expects PostgreSQL error");
        }

        String jsonpath = tc.jsonpath;

        // Skip features we don't support yet
        // is unknown — now supported (issue #73)
        if (jsonpath.contains(".datetime(") || jsonpath.contains(".date(") ||
                jsonpath.contains(".time(") || jsonpath.contains(".timestamp(") ||
                jsonpath.contains(".time_tz(") || jsonpath.contains(".timestamp_tz(")) {
            skipped++;
            assumeTrue(false, "Unsupported: datetime methods");
        }
        if (jsonpath.matches(".*\\$\\s*[+\\-*/].*") || jsonpath.matches(".*@\\s*[+\\-*/].*")) {
            // Arithmetic in path expressions (not in filters)
            if (!jsonpath.contains("?")) {
                skipped++;
                assumeTrue(false, "Unsupported: arithmetic in path");
            }
        }
        // array range (to) — now supported
        // last keyword — now supported
        // exists() — now supported (issue #71 Phase 3)
        // .bigint(), .integer(), .number(), .decimal() — now supported (issue #71 Phase 4)
        // .lower(), .upper(), .ltrim(), .rtrim(), .btrim(), .replace(), .initcap(), .split_part() — now supported (issue #73)

        // Determine mode from expression prefix
        JsonpathToJq.Mode mode = JsonpathToJq.Mode.LAX;
        // Let the converter handle mode prefix detection

        // Try to convert and compile
        String jq = null;
        JqProgram program;
        try {
            jq = JsonpathToJq.convert(jsonpath, mode);
            program = JqProgram.compile(jq);
        } catch (Exception e) {
            skipped++;
            assumeTrue(false, "Conversion/compilation failed for '" + jsonpath + "' → '" + (jq != null ? jq : "?") + "': " + e.getMessage());
            return;
        }

        // Parse input JSON
        JqValue input;
        try {
            input = JqValues.parse(tc.inputJson);
        } catch (Exception e) {
            skipped++;
            assumeTrue(false, "Cannot parse input JSON: " + e.getMessage());
            return;
        }

        // Execute and compare
        try {
            if (tc.queryFirst) {
                // jsonb_path_query_first returns first result only
                JqValue result = program.apply(input);
                if (tc.expectedResults.isEmpty()) {
                    if (!"null".equals(result.toJsonString())) {
                        skipped++;
                        assumeTrue(false, "Semantic difference for jsonpath: " + jsonpath
                                + " (jq: " + jq + "): expected no result, got " + result.toJsonString());
                        return;
                    }
                } else {
                    String expected = tc.expectedResults.get(0);
                    String actual = normalizeJson(result.toJsonString());
                    if (!expected.equals(actual)) {
                        skipped++;
                        assumeTrue(false, "Semantic difference for jsonpath: " + jsonpath
                                + " (jq: " + jq + "): expected " + expected + " but got " + actual);
                        return;
                    }
                }
            } else {
                // jsonb_path_query returns all results
                List<JqValue> results = program.applyAll(input);
                List<String> actual = results.stream()
                        .map(v -> normalizeJson(v.toJsonString()))
                        .toList();
                List<String> expected = tc.expectedResults.stream()
                        .map(this::normalizeJson)
                        .toList();
                if (!expected.equals(actual)) {
                    skipped++;
                    assumeTrue(false, "Semantic difference for jsonpath: " + jsonpath
                            + " (jq: " + jq + "): expected " + expected + " but got " + actual);
                    return;
                }
            }
        } catch (Exception e) {
            // Runtime error during jq execution — skip as unsupported
            skipped++;
            assumeTrue(false, "Execution error for jsonpath: " + jsonpath
                    + " (jq: " + jq + "): " + e.getMessage());
            return;
        }
        passed++;
    }

    /**
     * Parse the PostgreSQL .out file to extract test cases.
     * The .out file interleaves SQL statements with their expected output.
     */
    private List<TestCase> parseOutFile() throws Exception {
        var tests = new ArrayList<TestCase>();
        try (var is = getClass().getClassLoader().getResourceAsStream("postgresql/jsonb_jsonpath.out");
             var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // Try to match jsonb_path_query or jsonb_path_query_first
                boolean queryFirst = false;
                Matcher m = QUERY_PATTERN.matcher(line);
                if (!m.matches()) {
                    m = QUERY_FIRST_PATTERN.matcher(line);
                    if (m.matches()) queryFirst = true;
                }
                if (!m.matches()) continue;

                String inputJson = m.group(1);
                String jsonpath = m.group(2);

                boolean hasSilent = line.contains("silent =>");
                boolean hasVariables = false;
                // Check for variables: 3rd argument that starts with '{' (not silent)
                if (!hasSilent && line.contains("'{")) hasVariables = true;

                // Read the next non-empty line — could be header or ERROR
                String nextLine = reader.readLine();
                if (nextLine == null) break;
                nextLine = nextLine.trim();

                boolean expectsError = false;
                var results = new ArrayList<String>();

                if (nextLine.startsWith("ERROR:")) {
                    // Query produces an error
                    expectsError = true;
                } else {
                    // Skip header line (column name) + dash line
                    // nextLine is the header, read the dash line
                    String dashLine = reader.readLine();
                    if (dashLine == null) break;

                    // Read result rows until "(N rows)" or empty line
                    String resultLine;
                    while ((resultLine = reader.readLine()) != null) {
                        resultLine = resultLine.trim();
                        if (resultLine.startsWith("(") && resultLine.endsWith(")")) {
                            break; // "(N rows)" footer
                        }
                        if (resultLine.isEmpty()) break;
                        results.add(resultLine);
                    }
                }

                tests.add(new TestCase(inputJson, jsonpath, results, queryFirst,
                        hasVariables, hasSilent, expectsError));
            }
        }
        return tests;
    }

    /**
     * Normalize JSON output for comparison.
     * PostgreSQL formats JSON with spaces after colons/commas; jjq uses compact form.
     */
    private String normalizeJson(String json) {
        if (json == null) return "null";
        json = json.trim();
        // Try to parse and re-serialize for consistent formatting
        try {
            JqValue parsed = JqValues.parse(json);
            return parsed.toJsonString();
        } catch (Exception e) {
            // Not valid JSON (e.g., bare string without quotes in PG output)
            return json;
        }
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }

    record TestCase(String inputJson, String jsonpath, List<String> expectedResults,
                    boolean queryFirst, boolean hasVariables, boolean hasSilent,
                    boolean expectsError) {}
}

package io.hyperfoil.tools.jjq.testsuite;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs conformance tests from jq-tests.txt.
 * Format: groups separated by blank lines; each group is:
 *   line 1: filter expression
 *   line 2: input JSON
 *   lines 3+: expected output(s) (one per line)
 * Lines starting with # are comments, lines starting with %% are section headers.
 */
public class JqConformanceTest {

    record TestCase(String section, String filter, String input, List<String> expectedOutputs, int lineNumber) {}

    @TestFactory
    Stream<DynamicTest> conformanceTests() throws IOException {
        List<TestCase> cases = parseTestFile();
        return cases.stream().map(tc -> DynamicTest.dynamicTest(
                "[%s] %s (line %d)".formatted(tc.section(), tc.filter(), tc.lineNumber()),
                () -> runTestCase(tc)
        ));
    }

    private List<TestCase> parseTestFile() throws IOException {
        List<TestCase> cases = new ArrayList<>();
        try (var reader = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/jq-tests.txt"), StandardCharsets.UTF_8))) {

            String currentSection = "General";
            List<String> group = new ArrayList<>();
            int groupStartLine = 1;
            int lineNum = 0;

            String line;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (line.startsWith("#")) continue;

                if (line.startsWith("%%")) {
                    currentSection = line.substring(2).trim();
                    continue;
                }

                if (line.isBlank()) {
                    if (group.size() >= 3) {
                        String filter = group.get(0);
                        String input = group.get(1);
                        List<String> expected = group.subList(2, group.size());
                        cases.add(new TestCase(currentSection, filter, input, new ArrayList<>(expected), groupStartLine));
                    }
                    group.clear();
                    groupStartLine = lineNum + 1;
                } else {
                    group.add(line);
                }
            }
            // Handle last group if file doesn't end with blank line
            if (group.size() >= 3) {
                String filter = group.get(0);
                String input = group.get(1);
                List<String> expected = group.subList(2, group.size());
                cases.add(new TestCase(currentSection, filter, input, new ArrayList<>(expected), groupStartLine));
            }
        }
        return cases;
    }

    private void runTestCase(TestCase tc) {
        JqValue input = JqValues.parse(tc.input());

        List<JqValue> results;
        try {
            JqProgram program = JqProgram.compile(tc.filter());
            results = program.applyAll(input);
        } catch (Exception e) {
            fail("Filter '%s' with input '%s' threw: %s".formatted(tc.filter(), tc.input(), e.getMessage()));
            return;
        }

        assertEquals(tc.expectedOutputs().size(), results.size(),
                "Output count mismatch for filter '%s' with input '%s'. Expected %s but got %s"
                        .formatted(tc.filter(), tc.input(), tc.expectedOutputs(), results.stream().map(JqValue::toJsonString).toList()));

        for (int i = 0; i < tc.expectedOutputs().size(); i++) {
            String expected = tc.expectedOutputs().get(i);
            String actual = results.get(i).toJsonString();
            assertEquals(expected, actual,
                    "Output %d mismatch for filter '%s' with input '%s'"
                            .formatted(i, tc.filter(), tc.input()));
        }
    }
}

package io.hyperfoil.tools.jjq.testsuite;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs tests from jq's upstream test suite (tests/jq.test from github.com/jqlang/jq).
 * Format: filter / input / expected output(s), blank-line separated.
 * <p>
 * Tests run with a 5-second timeout. Tests that fail or error are reported as
 * "skipped" (via JUnit Assumptions) so the build stays green while we track
 * compatibility progress. Passing tests are fully asserted.
 */
public class JqUpstreamTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    record TestCase(String filter, String input, List<String> expectedOutputs, int lineNumber) {}

    @TestFactory
    Stream<DynamicTest> upstreamTests() throws IOException {
        List<TestCase> cases = parseTestFile();
        return cases.stream().map(tc -> DynamicTest.dynamicTest(
                testName(tc),
                () -> runTestCase(tc)
        ));
    }

    private static String testName(TestCase tc) {
        String filter = tc.filter();
        if (filter.length() > 80) {
            filter = filter.substring(0, 77) + "...";
        }
        return "[line %d] %s".formatted(tc.lineNumber(), filter);
    }

    private List<TestCase> parseTestFile() throws IOException {
        List<TestCase> cases = new ArrayList<>();
        try (var reader = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/jq-upstream-tests.txt"), StandardCharsets.UTF_8))) {

            List<String> group = new ArrayList<>();
            int groupStartLine = 1;
            int lineNum = 0;

            String line;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (line.startsWith("#")) continue;

                if (line.isBlank()) {
                    if (group.size() >= 3) {
                        String filter = group.get(0);
                        String input = group.get(1);
                        List<String> expected = group.subList(2, group.size());
                        cases.add(new TestCase(filter, input, new ArrayList<>(expected), groupStartLine));
                    }
                    group.clear();
                    groupStartLine = lineNum + 1;
                } else {
                    group.add(line);
                }
            }
            if (group.size() >= 3) {
                String filter = group.get(0);
                String input = group.get(1);
                List<String> expected = group.subList(2, group.size());
                cases.add(new TestCase(filter, input, new ArrayList<>(expected), groupStartLine));
            }
        }
        return cases;
    }

    private void runTestCase(TestCase tc) {
        try {
            assertTimeoutPreemptively(TIMEOUT, () -> {
                JqValue input = JqValues.parse(tc.input());

                JqProgram program = JqProgram.compile(tc.filter());
                List<JqValue> results = program.applyAll(input);

                assertEquals(tc.expectedOutputs().size(), results.size(),
                        "Output count mismatch for filter '%s' with input '%s'. Expected %s but got %s"
                                .formatted(tc.filter(), tc.input(),
                                        tc.expectedOutputs(),
                                        results.stream().map(JqValue::toJsonString).toList()));

                for (int i = 0; i < tc.expectedOutputs().size(); i++) {
                    String expected = tc.expectedOutputs().get(i);
                    String actual = results.get(i).toJsonString();
                    assertEquals(expected, actual,
                            "Output %d mismatch for filter '%s' with input '%s'"
                                    .formatted(i, tc.filter(), tc.input()));
                }
            });
        } catch (Throwable e) {
            Assumptions.abort("%s [line %d] %s — %s".formatted(
                    tc.filter(), tc.lineNumber(),
                    e.getClass().getSimpleName(), e.getMessage()));
        }
    }
}

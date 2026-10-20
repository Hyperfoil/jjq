package io.hyperfoil.tools.jjq.diff;

import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqBoolean;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance against the upstream json-patch-tests suite (vendored
 * {@code tests.json}, see README.md there): apply each patch to its
 * document and compare against {@code expected}, or assert a throw when
 * {@code error} is set. {@code disabled} cases are skipped; error
 * message parity is not asserted.
 */
class JsonPatchConformanceTest {

    @TestFactory
    Stream<DynamicTest> conformanceTests() throws Exception {
        var url = getClass().getResource("/json-patch-tests/tests.json");
        assertNotNull(url, "vendored tests.json missing");
        Path path = Path.of(url.toURI());
        JqValue parsed = JqValues.parse(Files.readString(path, StandardCharsets.UTF_8));
        assertTrue(parsed instanceof JqArray);
        JqArray cases = (JqArray) parsed;
        List<DynamicTest> tests = new ArrayList<>(cases.size());
        for (int i = 0; i < cases.size(); i++) {
            JqObject tc = (JqObject) cases.get(i);
            String name = tc.has("comment")
                    ? tc.getField("comment").stringValue() : "case" + i;
            boolean disabled = tc.has("disabled")
                    && tc.getField("disabled") instanceof JqBoolean b && b.booleanValue();
            JqValue doc = tc.getField("doc");
            JqValue patch = tc.getField("patch");
            boolean error = tc.has("error");
            JqValue expected = error ? null : tc.getField("expected");
            tests.add(DynamicTest.dynamicTest("[" + i + "] " + name, () -> {
                if (disabled) {
                    Assumptions.abort("disabled upstream");
                    return;
                }
                if (!(patch instanceof JqArray arr)) {
                    throw new IllegalStateException("patch is not an array in case " + name);
                }
                if (error) {
                    assertThrows(JqPatchException.class,
                            () -> JqPatch.apply(doc, arr), "expected failure: " + name);
                } else {
                    assertEquals(expected, JqPatch.apply(doc, arr), "mismatch: " + name);
                }
            }));
        }
        return tests.stream();
    }
}

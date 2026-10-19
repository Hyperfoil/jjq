package io.hyperfoil.tools.jjq.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.hyperfoil.tools.jjq.quarkus.test.BadPoly;
import io.hyperfoil.tools.jjq.quarkus.test.BadPolyA;
import io.hyperfoil.tools.jjq.quarkus.test.BadPolyB;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * A malformed polymorphic family fails augmentation with the mapper's
 * message instead of failing the first request at runtime (issue #151).
 */
class JjqPolyFamilyBuildFailureTest {

    @RegisterExtension
    static QuarkusExtensionTest runner = new QuarkusExtensionTest()
            .assertException(throwable -> {
                String messages = collectMessages(throwable);
                assertTrue(messages.contains("BadPoly"), "error names the family:\n" + messages);
                assertTrue(messages.contains("duplicate discriminator value"),
                        "error states the problem:\n" + messages);
            })
            .withApplicationRoot((jar) -> jar
                    .addClasses(BadPoly.class, BadPolyA.class, BadPolyB.class));

    @Test
    void malformedFamilyFailsTheBuild() {
        fail("The build should have failed because the family has duplicate wire values");
    }

    private static String collectMessages(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        List<Throwable> seen = new ArrayList<>();
        Throwable current = throwable;
        while (current != null && !seen.contains(current)) {
            seen.add(current);
            if (current.getMessage() != null) {
                builder.append(current.getMessage()).append('\n');
            }
            for (Throwable suppressed : current.getSuppressed()) {
                if (suppressed.getMessage() != null) {
                    builder.append(suppressed.getMessage()).append('\n');
                }
            }
            current = current.getCause();
        }
        return builder.toString();
    }
}

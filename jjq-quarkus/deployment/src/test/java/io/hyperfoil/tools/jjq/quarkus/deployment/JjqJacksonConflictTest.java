package io.hyperfoil.tools.jjq.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.hyperfoil.tools.jjq.quarkus.test.Greeting;
import io.hyperfoil.tools.jjq.quarkus.test.GreetingResource;
import io.hyperfoil.tools.jjq.quarkus.test.Greeting_JqMapping;
import io.quarkus.builder.Version;
import io.quarkus.maven.dependency.Dependency;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * jjq-quarkus and quarkus-jackson cannot be used together. When both are on
 * the classpath the build must fail early with a clear message rather than
 * with nondeterministic provider selection between competing serializers.
 */
class JjqJacksonConflictTest {

    @RegisterExtension
    static QuarkusExtensionTest runner = new QuarkusExtensionTest()
            // Force the Jackson extension onto this single test's classpath, next to jjq-quarkus:
            // both the runtime (capability descriptor plus transitive deps) and its deployment artifact.
            .setForcedDependencies(List.of(
                    Dependency.of("io.quarkus", "quarkus-jackson", Version.getVersion()),
                    Dependency.of("io.quarkus", "quarkus-jackson-deployment", Version.getVersion())))
            .assertException(throwable -> {
                String messages = collectMessages(throwable);
                assertTrue(messages.contains("jjq-quarkus"), "error names jjq-quarkus:\n" + messages);
                assertTrue(messages.contains("quarkus-jackson"), "error names quarkus-jackson:\n" + messages);
                assertTrue(messages.contains("cannot be used together"), "error states mutual exclusion:\n" + messages);
            })
            .withApplicationRoot((jar) -> jar
                    .addClasses(Greeting.class, Greeting_JqMapping.class, GreetingResource.class));

    @Test
    void conflictShouldFailTheBuild() {
        fail("The build should have failed because jjq-quarkus and quarkus-jackson are both present");
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

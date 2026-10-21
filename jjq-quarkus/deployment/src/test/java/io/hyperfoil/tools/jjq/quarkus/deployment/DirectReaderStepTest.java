package io.hyperfoil.tools.jjq.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

import org.jboss.jandex.Index;
import org.junit.jupiter.api.Test;

import io.hyperfoil.tools.jjq.quarkus.test.DirectEligible;
import io.hyperfoil.tools.jjq.quarkus.test.DirectNested;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;

/**
 * Request-type discovery for direct readers (issue #174): mutating
 * endpoints taking {@code @JqMapped} bodies are found by parameter type,
 * everything else is ignored. Generation itself is covered through real
 * augmentation in the bean tests (flag off registers nothing, flag on
 * registers eligible readers only).
 */
class DirectReaderStepTest {

    static class StepEndpoints {
        @POST
        public String flat(DirectEligible in) {
            return "ok";
        }

        @POST
        public String nested(DirectNested in) {
            return "ok";
        }

        @GET
        public String get() {
            return "ok";
        }

        public String helper(DirectEligible in) {
            return "ok";
        }
    }

    @Test
    void requestDiscoveryFindsMutatingParams() throws Exception {
        var index = Index.of(StepEndpoints.class);
        assertEquals(Set.of(DirectEligible.class.getName(), DirectNested.class.getName()),
                JjqProcessor.requestBodyClassNames(index, Set.of(DirectEligible.class.getName(),
                        DirectNested.class.getName(), "com.example.Missing")));
    }

    @Test
    void directReaderNamesFollowMappingConvention() {
        assertEquals("com.foo.Bar_DirectReader",
                JjqProcessor.directReaderClassNameFor("com.foo.Bar"));
        assertEquals("com.foo.Outer_Inner_DirectReader",
                JjqProcessor.directReaderClassNameFor("com.foo.Outer$Inner"));
    }
}

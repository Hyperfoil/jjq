package io.hyperfoil.tools.jjq.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Carrier-chain tracing for the Jackson mutual-exclusion error (issue
 * #200): shortest dependent chains from the target up to model roots.
 */
class JacksonCarrierChainTest {

    private static Map<String, List<String>> graph(String[]... edges) {
        var graph = new LinkedHashMap<String, List<String>>();
        for (String[] edge : edges) {
            graph.put(edge[0], List.of(edge).subList(1, edge.length));
        }
        return graph;
    }

    @Test
    void linearChainRendersTargetFirst() {
        var chains = JjqProcessor.carrierChains(graph(
                new String[]{"app:web", "org:roq"},
                new String[]{"org:roq", "org:roq-gen"},
                new String[]{"org:roq-gen", "io.quarkus:quarkus-jackson"}),
                "io.quarkus:quarkus-jackson");
        assertEquals(1, chains.size());
        assertEquals(List.of("io.quarkus:quarkus-jackson", "org:roq-gen", "org:roq", "app:web"),
                chains.get(0));
    }

    @Test
    void diamondYieldsBothChains() {
        var chains = JjqProcessor.carrierChains(graph(
                new String[]{"app:web", "org:a", "org:b"},
                new String[]{"org:a", "io.quarkus:quarkus-jackson"},
                new String[]{"org:b", "io.quarkus:quarkus-jackson"}),
                "io.quarkus:quarkus-jackson");
        assertEquals(2, chains.size());
        assertTrue(chains.contains(
                List.of("io.quarkus:quarkus-jackson", "org:a", "app:web")));
        assertTrue(chains.contains(
                List.of("io.quarkus:quarkus-jackson", "org:b", "app:web")));
    }

    @Test
    void directDeclarationYieldsSingletonChain() {
        var chains = JjqProcessor.carrierChains(graph(
                new String[]{"app:web", "io.quarkus:quarkus-jackson"}),
                "io.quarkus:quarkus-jackson");
        // Singleton would mean "declared directly"; here it has a parent.
        assertEquals(1, chains.size());
        assertEquals(List.of("io.quarkus:quarkus-jackson", "app:web"), chains.get(0));
    }

    @Test
    void missingTargetYieldsNothing() {
        assertTrue(JjqProcessor.carrierChains(graph(
                new String[]{"app:web", "org:other"}),
                "io.quarkus:quarkus-jackson").isEmpty());
    }

    @Test
    void cyclesTerminate() {
        var chains = JjqProcessor.carrierChains(graph(
                new String[]{"org:a", "org:b"},
                new String[]{"org:b", "org:a", "io.quarkus:quarkus-jackson"}),
                "io.quarkus:quarkus-jackson");
        // No root is reachable inside the cycle; terminating empty beats
        // looping, and the error keeps its previous text.
        assertTrue(chains.isEmpty());
    }

    @Test
    void chainsCapAtThree() {
        var graph = new LinkedHashMap<String, List<String>>();
        graph.put("app:web", List.of("org:a", "org:b", "org:c", "org:d"));
        graph.put("org:a", List.of("io.quarkus:quarkus-jackson"));
        graph.put("org:b", List.of("io.quarkus:quarkus-jackson"));
        graph.put("org:c", List.of("io.quarkus:quarkus-jackson"));
        graph.put("org:d", List.of("io.quarkus:quarkus-jackson"));
        assertEquals(3, JjqProcessor.carrierChains(graph, "io.quarkus:quarkus-jackson").size());
    }
}

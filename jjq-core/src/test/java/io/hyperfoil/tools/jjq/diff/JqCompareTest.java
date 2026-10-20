package io.hyperfoil.tools.jjq.diff;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.hyperfoil.tools.jjq.diff.JqDiffEntry.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class JqCompareTest {

    private static JqValue json(String text) {
        return JqValues.parse(text);
    }

    @Test
    void renderChangeAddRemove() {
        assertEquals("~ $.b: 2 -> 3",
                JqDiff.render(json("{\"b\":2}"), json("{\"b\":3}")));
        assertEquals("+ $.c: 4", JqDiff.render(json("{}"), json("{\"c\":4}")));
        assertEquals("- $.a: 1", JqDiff.render(json("{\"a\":1}"), json("{}")));
    }

    @Test
    void renderNestedMoveAndRoot() {
        assertEquals("> $.a[1] -> $.a[0]: \"b\"",
                JqDiff.render(json("{\"a\":[\"a\",\"b\"]}"), json("{\"a\":[\"b\",\"a\"]}")));
        assertEquals("~ $: 1 -> 2", JqDiff.render(json("1"), json("2")));
        assertEquals("", JqDiff.render(json("{\"a\":[1]}"), json("{\"a\":[1]}")));
    }

    @Test
    void renderQuotesWeirdKeys() {
        assertEquals("~ $[\"a b\"]: 1 -> 2",
                JqDiff.render(json("{\"a b\":1}"), json("{\"a b\":2}")));
    }

    @Test
    void compareEntryShape() {
        List<JqDiffEntry> entries = JqDiff.compare(
                json("{\"a\":1,\"b\":2}"), json("{\"b\":3,\"c\":4}"));
        assertEquals(3, entries.size());
        assertEquals(new JqDiffEntry(REMOVED, "$.a", null, json("1"), null), entries.get(0));
        assertEquals(new JqDiffEntry(REPLACED, "$.b", null, json("2"), json("3")),
                entries.get(1));
        assertEquals(new JqDiffEntry(ADDED, "$.c", null, null, json("4")), entries.get(2));
    }

    @Test
    void compareMoveEntry() {
        List<JqDiffEntry> entries = JqDiff.compare(json("[\"a\",\"b\"]"), json("[\"b\",\"a\"]"));
        assertEquals(1, entries.size());
        assertEquals(new JqDiffEntry(MOVED, "$[0]", "$[1]", json("\"b\""), json("\"b\"")),
                entries.get(0));
    }

    @Test
    void ignorePathsSkipsSubtrees() {
        JqDiffOptions options = JqDiffOptions.EMPTY
                .withIgnorePath("$.meta.timestamp")
                .withIgnorePath("$.gone");
        List<JqDiffEntry> entries = JqDiff.compare(
                json("{\"meta\":{\"timestamp\":1,\"v\":1},\"gone\":1,\"keep\":1}"),
                json("{\"meta\":{\"timestamp\":2,\"v\":1},\"gone\":2,\"keep\":2}"),
                options);
        assertEquals(1, entries.size());
        assertEquals("$.keep", entries.get(0).path());
    }

    @Test
    void ignoreRootSkipsEverything() {
        assertTrue(JqDiff.compare(json("{\"a\":1}"), json("{\"b\":2}"),
                JqDiffOptions.EMPTY.withIgnorePath("$")).isEmpty());
        assertEquals("", JqDiff.render(json("[1]"), json("[2]"),
                JqDiffOptions.EMPTY.withIgnorePath("$")));
    }

    @Test
    void entryValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> new JqDiffEntry(ADDED, "$.a", null, json("1"), json("2")));
        assertThrows(IllegalArgumentException.class,
                () -> new JqDiffEntry(MOVED, "$.a", null, json("1"), json("1")));
        assertThrows(NullPointerException.class,
                () -> new JqDiffEntry(null, "$.a", null, null, json("1")));
    }

    @Test
    void compareAgreesWithDiffOnEmptiness() {
        java.util.Random random = new java.util.Random(7);
        for (int trial = 0; trial < 300; trial++) {
            JqValue a = randomDoc(random, 3);
            JqValue b = randomDoc(random, 3);
            assertEquals(JqDiff.diff(a, b).size() == 0, JqDiff.compare(a, b).isEmpty(),
                    "trial " + trial + ": " + a + " vs " + b);
        }
    }

    private static final String[] ATOMS = {"1", "2", "\"x\"", "\"y\"", "true", "null"};

    private static JqValue randomDoc(java.util.Random random, int depth) {
        int pick = random.nextInt(depth > 0 ? 6 : 4);
        return switch (pick) {
            case 0, 1, 2, 3 -> json(ATOMS[random.nextInt(ATOMS.length)]);
            case 4 -> {
                int size = random.nextInt(5);
                List<JqValue> elems = new ArrayList<>(size);
                for (int i = 0; i < size; i++) elems.add(randomDoc(random, depth - 1));
                yield io.hyperfoil.tools.jjq.value.JqArray.of(elems);
            }
            default -> {
                int size = random.nextInt(4);
                var builder = io.hyperfoil.tools.jjq.value.JqObject.builder(size);
                for (int i = 0; i < size; i++) {
                    builder.putUnchecked("k" + i, randomDoc(random, depth - 1));
                }
                yield builder.build();
            }
        };
    }
}

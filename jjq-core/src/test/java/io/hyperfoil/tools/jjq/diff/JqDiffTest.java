package io.hyperfoil.tools.jjq.diff;

import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JqDiffTest {

    private static JqValue json(String text) {
        return JqValues.parse(text);
    }

    /** Diff must round-trip: applying the patch to the source yields the target. */
    private static void assertRoundTrip(String source, String target) {
        JqValue a = json(source);
        JqValue b = json(target);
        JqValue patched = JqPatch.apply(a, JqDiff.diff(a, b));
        assertEquals(b, patched, "round trip: " + source + " -> " + target);
    }

    @Test
    void equalDocumentsProduceEmptyPatch() {
        assertEquals(0, JqDiff.diff(json("{\"b\":2,\"a\":1}"), json("{\"a\":1,\"b\":2}")).size());
        assertEquals(0, JqDiff.diff(json("[1,[2]]"), json("[1,[2]]")).size());
    }

    @Test
    void scalarReplace() {
        JqArray patch = JqDiff.diff(json("1"), json("2"));
        assertEquals("[{\"op\":\"replace\",\"path\":\"\",\"value\":2}]", patch.toJsonString());
        assertRoundTrip("1", "2");
    }

    @Test
    void numericEqualityIsExact() {
        assertEquals(0, JqDiff.diff(json("1"), json("1.0")).size());
        assertEquals(1, JqDiff.diff(
                json("18446744073709551615"), json("18446744073709551616")).size());
        assertRoundTrip("1", "1.0");
        assertRoundTrip("18446744073709551615", "18446744073709551616");
    }

    @Test
    void typeChangeReplaces() {
        assertEquals("[{\"op\":\"replace\",\"path\":\"/a\",\"value\":[1]}]",
                JqDiff.diff(json("{\"a\":1}"), json("{\"a\":[1]}")).toJsonString());
        assertRoundTrip("{\"a\":1}", "{\"a\":[1]}");
        assertRoundTrip("[1]", "{\"a\":1}");
    }

    @Test
    void objectAddRemoveRecurse() {
        JqArray patch = JqDiff.diff(json("{\"a\":1,\"b\":2}"), json("{\"b\":3,\"c\":4}"));
        assertEquals("[{\"op\":\"remove\",\"path\":\"/a\"},"
                + "{\"op\":\"replace\",\"path\":\"/b\",\"value\":3},"
                + "{\"op\":\"add\",\"path\":\"/c\",\"value\":4}]", patch.toJsonString());
        assertRoundTrip("{\"a\":1,\"b\":2}", "{\"b\":3,\"c\":4}");
    }

    @Test
    void nullVsMissingIsDistinct() {
        assertEquals(1, JqDiff.diff(json("{\"a\":null}"), json("{}")).size());
        assertEquals(1, JqDiff.diff(json("{}"), json("{\"a\":null}")).size());
        assertRoundTrip("{\"a\":null}", "{}");
    }

    @Test
    void pointerEscapesKeys() {
        assertRoundTrip("{\"a/b\":1,\"~x\":2}", "{\"a/b\":1,\"~x\":3,\"c/d\":4}");
        JqArray patch = JqDiff.diff(json("{\"~1\":1}"), json("{\"~1\":2}"));
        assertEquals("[{\"op\":\"replace\",\"path\":\"/~01\",\"value\":2}]", patch.toJsonString());
    }

    @Test
    void arrayAppend() {
        assertEquals("[{\"op\":\"add\",\"path\":\"/1\",\"value\":2}]",
                JqDiff.diff(json("[1]"), json("[1,2]")).toJsonString());
        assertRoundTrip("[1]", "[1,2]");
        assertRoundTrip("[1,2]", "[1]");
    }

    @Test
    void arrayMidInsertIsSingleAdd() {
        JqArray patch = JqDiff.diff(
                json("[\"a\",\"c\",\"d\"]"), json("[\"a\",\"b\",\"c\",\"d\"]"));
        assertEquals("[{\"op\":\"add\",\"path\":\"/1\",\"value\":\"b\"}]", patch.toJsonString());
        assertRoundTrip("[\"a\",\"c\",\"d\"]", "[\"a\",\"b\",\"c\",\"d\"]");
        assertRoundTrip("[\"a\",\"b\",\"c\",\"d\"]", "[\"a\",\"c\",\"d\"]");
    }

    @Test
    void arraySwapIsMove() {
        JqArray patch = JqDiff.diff(json("[\"a\",\"b\"]"), json("[\"b\",\"a\"]"));
        assertEquals("[{\"op\":\"move\",\"from\":\"/1\",\"path\":\"/0\"}]", patch.toJsonString());
        assertRoundTrip("[\"a\",\"b\"]", "[\"b\",\"a\"]");
    }

    @Test
    void arrayNestedChangeUsesNestedPath() {
        assertRoundTrip("[{\"a\":1},{\"b\":2}]", "[{\"a\":1},{\"b\":3}]");
        JqArray patch = JqDiff.diff(json("[{\"a\":1}]"), json("[{\"a\":2}]"));
        assertEquals("[{\"op\":\"replace\",\"path\":\"/0/a\",\"value\":2}]", patch.toJsonString());
    }

    @Test
    void arrayDuplicatesTracked() {
        assertRoundTrip("[1,1,2]", "[1,2,1]");
        assertRoundTrip("[1,2,1]", "[1,1,2]");
        assertRoundTrip("[1,1,1]", "[1,1]");
    }

    @Test
    void largeShuffledArrayFallsBackToReplace() {
        StringBuilder a = new StringBuilder("[");
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < 600; i++) {
            if (i > 0) {
                a.append(',');
                b.append(',');
            }
            a.append(i);
            b.append(599 - i);
        }
        a.append(']');
        b.append(']');
        JqArray patch = JqDiff.diff(json(a.toString()), json(b.toString()));
        assertEquals(1, patch.size());
        assertRoundTrip(a.toString(), b.toString());
    }

    @Test
    void fuzzRoundTrip() {
        java.util.Random random = new java.util.Random(42);
        for (int trial = 0; trial < 300; trial++) {
            JqValue a = randomDoc(random, 3);
            JqValue b = randomDoc(random, 3);
            JqValue patched = JqPatch.apply(a, JqDiff.diff(a, b));
            assertEquals(b, patched, "trial " + trial + ": " + a + " -> " + b);
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
                yield JqArray.of(elems);
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

package io.hyperfoil.tools.jjq.value;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Differential tests for the experimental stage-1 indexer: index sets must
 * match a naive reference scanner on valid documents, and malformed inputs
 * must throw rather than index.
 */
class StructuralIndexerTest {

    /** Obviously-correct reference: per-byte flags, no cleverness. */
    private static int[] reference(byte[] buf) {
        List<Integer> out = new ArrayList<>();
        boolean inString = false;
        boolean escaped = false;
        boolean boundary = true;
        for (int i = 0; i < buf.length; i++) {
            int b = buf[i] & 0xFF;
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (b == '\\') {
                    escaped = true;
                } else if (b == '"') {
                    inString = false;
                    boundary = true;
                }
                continue;
            }
            switch (b) {
                case '{', '}', '[', ']', ',', ':', '"' -> {
                    out.add(i);
                    boundary = true;
                    if (b == '"') inString = true;
                }
                case ' ', '\t', '\n', '\r' -> boundary = true;
                default -> {
                    if (boundary) {
                        out.add(i);
                        boundary = false;
                    }
                }
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static byte[] fixture() throws Exception {
        try (var in = StructuralIndexerTest.class.getClassLoader()
                .getResourceAsStream("stage1-sample.json")) {
            assertNotNull(in, "missing fixture");
            return in.readAllBytes();
        }
    }

    @Test
    void indexesMatchReferenceOnFixture() throws Exception {
        byte[] buf = fixture();
        assertArrayEquals(reference(buf), StructuralIndexer.index(buf, 0, buf.length));
    }

    @Test
    void indexesMatchReferenceOnTinyDocs() {
        String[] docs = {
            "{}", "[]", "[1,2]", "{\"a\":1}", " \"x\" ", "1",
            "{\"a\":[{\"b\":null}]}", "[\"{\",\":\",\",\"]", "{\"\\\"\":\"\\\\\"}",
        };
        for (String doc : docs) {
            byte[] buf = doc.getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(reference(buf), StructuralIndexer.index(buf, 0, buf.length), doc);
        }
    }

    @Test
    void matchesSimdjsonDocumentedExample() {
        // Offsets from the stage-1 walkthrough: scalar starts included,
        // string contents and closing quotes excluded.
        byte[] buf = "{\"a\":[10,\"x,y\"],\"b\":true}".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(new int[]{0, 1, 4, 5, 6, 8, 9, 14, 15, 16, 19, 20, 24},
                StructuralIndexer.index(buf, 0, buf.length));
    }

    @Test
    void undersizedBufferThrows() {
        byte[] buf = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> StructuralIndexer.indexInto(buf, 0, buf.length, new int[2]));
        int[] roomy = new int[buf.length];
        // { " : 1 } — the closing quote is not an index
        assertEquals(5, StructuralIndexer.indexInto(buf, 0, buf.length, roomy));
    }

    @Test
    void structuralCharsInsideStringsAreSkipped() {
        byte[] buf = "{\"x:y,z\":[\"{\",\"]\"]}".getBytes(StandardCharsets.UTF_8);
        int[] idx = StructuralIndexer.index(buf, 0, buf.length);
        // { " : [ " , " ] } -> 9 structurals, none from inside strings
        assertEquals(9, idx.length);
        assertEquals('{', buf[idx[0]]);
        assertEquals('}', buf[idx[idx.length - 1]]);
    }

    @Test
    void malformedInputsThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> StructuralIndexer.index("\"abc".getBytes(StandardCharsets.UTF_8), 0, 4));
        assertThrows(IllegalArgumentException.class,
                () -> StructuralIndexer.index(new byte[]{(byte) 0xFF}, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> StructuralIndexer.index(new byte[]{0x01}, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> StructuralIndexer.index("\"a\u0001b\"".getBytes(StandardCharsets.UTF_8), 0, 5));
    }
}

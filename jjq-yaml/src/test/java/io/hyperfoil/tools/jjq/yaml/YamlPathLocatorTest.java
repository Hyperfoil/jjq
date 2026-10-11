package io.hyperfoil.tools.jjq.yaml;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class YamlPathLocatorTest {

    private static int locate(String yaml, Object... segments) {
        return YamlPathLocator.locate(yaml, List.of(segments));
    }

    @Test
    void topLevelKey() {
        assertEquals(2, locate("name: t\npackages: vim\n", "packages"));
    }

    @Test
    void nestedKeys() {
        String yaml = "server:\n  host: h\n  port: 1\n";
        assertEquals(1, locate(yaml, "server"));
        assertEquals(3, locate(yaml, "server", "port"));
    }

    @Test
    void sequenceIndex() {
        String yaml = "tools:\n  - a\n  - b\n";
        assertEquals(3, locate(yaml, "tools", 1));
    }

    @Test
    void sequenceAtSameIndentAsKey() {
        String yaml = "key:\n- a\n- b\n";
        assertEquals(3, locate(yaml, "key", 1));
    }

    @Test
    void sequenceOfMaps() {
        String yaml = "repos:\n  - url: a\n    ref: x\n  - url: b\n";
        assertEquals(4, locate(yaml, "repos", 1, "url"));
    }

    @Test
    void nonIdentifierKey() {
        assertEquals(2, locate("name: t\nauto-clone-repos:\n  - a\n", "auto-clone-repos"));
    }

    @Test
    void commentsAndBlanksIgnored() {
        String yaml = "# top\n\nname: t\n# between\npackages: vim\n\n";
        assertEquals(5, locate(yaml, "packages"));
    }

    @Test
    void blockScalarContentNotStructural() {
        String yaml = "description: |\n  not: a key\nreal: 1\n";
        assertEquals(3, locate(yaml, "real"));
        assertEquals(1, locate(yaml, "description"));
    }

    @Test
    void flowSingleLine() {
        assertEquals(1, locate("k: {a: b}\n", "k"));
        assertEquals(1, locate("k: {a: b}\n", "k", "a"));
        assertEquals(1, locate("m: [1, 2]\n", "m", 1));
    }

    @Test
    void flowMultilineAttributesToKeyLine() {
        String yaml = "k: {a: 1,\n    b: 2}\nc: 3\n";
        assertEquals(1, locate(yaml, "k", "b"));
        assertEquals(3, locate(yaml, "c"));
    }

    @Test
    void quotedColonValueDoesNotSplit() {
        assertEquals(1, locate("msg: \"see: docs\"\n", "msg"));
    }

    @Test
    void multilineQuotedContentExcluded() {
        String yaml = "msg: \"line1\nfake: 1\n\"\nreal: 2\n";
        assertEquals(4, locate(yaml, "real"));
        assertEquals(-1, locate(yaml, "fake"));
    }

    @Test
    void duplicateKeysLastWins() {
        assertEquals(2, locate("a: 1\na: 2\n", "a"));
    }

    @Test
    void missingKeyIsUnknown() {
        assertEquals(-1, locate("a: 1\n", "b"));
        assertEquals(-1, locate("a: 1\n", "a", "b"));
        assertEquals(-1, locate("tools:\n  - a\n", "tools", 5));
    }

    @Test
    void emptySegmentsGivesFirstContentLine() {
        // Root mismatch (path `$`): the value starts at the first content line
        assertEquals(1, YamlPathLocator.locate("a: 1\n", List.of()));
        assertEquals(2, YamlPathLocator.locate("# note\n- a\n- b\n", List.of()));
        assertEquals(1, YamlPathLocator.locate("just text\n", List.of()));
        assertEquals(2, YamlPathLocator.locate("---\na: 1\n", List.of()));
        assertEquals(-1, YamlPathLocator.locate("", List.of()));
        assertEquals(-1, YamlPathLocator.locate("# only a comment\n", List.of()));
        assertEquals(-1, YamlPathLocator.locate("a: 1\n", (List<Object>) null));
    }

    @Test
    void quotedKeyIsUnknown() {
        assertEquals(-1, locate("\"my key\": 1\n", "my key"));
    }

    @Test
    void aliasDescentIsUnknown() {
        String yaml = "base: &a {x: 1}\nref: *a\n";
        assertEquals(2, locate(yaml, "ref"));
        assertEquals(-1, locate(yaml, "ref", "x"));
    }

    @Test
    void multiDocIsUnknown() {
        assertEquals(-1, locate("a: 1\n---\nb: 2\n", "b"));
    }

    @Test
    void explicitDocStartOk() {
        assertEquals(2, locate("---\na: 1\n", "a"));
    }

    @Test
    void crlfHandled() {
        assertEquals(2, locate("a: 1\r\nb: 2\r\n", "b"));
    }

    @Test
    void urlValueDoesNotSplit() {
        assertEquals(1, locate("url: http://x\n", "url"));
    }
}

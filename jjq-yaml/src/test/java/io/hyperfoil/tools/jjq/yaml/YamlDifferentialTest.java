package io.hyperfoil.tools.jjq.yaml;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Differential tests: the native parser must agree with SnakeYAML on every
 * document (canonical JSON rendering compared, so Java-type differences
 * like BigInteger vs BigDecimal cannot cause false mismatches).
 */
class YamlDifferentialTest {

    private static final Yaml SNAKE = new Yaml();

    static void assertSameAsSnakeYaml(String yaml) {
        // BigInteger normalizes to exact BigDecimal: SnakeYAML constructs huge
        // ints as BigInteger while JqYaml uses BigDecimal (pre-existing parity),
        // and fromJavaObject would lossily route BigInteger through double.
        String expected = JqValues.fromJavaObject(normalize(SNAKE.load(yaml))).toJsonString();
        JqValue actual = JqYaml.parse(yaml);
        assertEquals(expected, actual.toJsonString(), "differential mismatch for:\n" + yaml);
    }

    static Object normalize(Object o) {
        if (o instanceof java.math.BigInteger bi) return new java.math.BigDecimal(bi.toString());
        if (o instanceof java.util.Map<?, ?> m) {
            var out = new java.util.LinkedHashMap<>();
            m.forEach((k, v) -> out.put(k, normalize(v)));
            return out;
        }
        if (o instanceof java.util.List<?> l) {
            return l.stream().map(YamlDifferentialTest::normalize).toList();
        }
        return o;
    }

    static void assertSameAllAsSnakeYaml(String yaml) {
        List<Object> expectedDocs = new java.util.ArrayList<>();
        for (Object doc : SNAKE.loadAll(yaml)) expectedDocs.add(doc);
        String expected = JqValues.fromJavaObject(expectedDocs).toJsonString();
        String actual = JqValues.fromJavaObject(
                JqYaml.parseAll(yaml).stream().map(JqValue::toJavaObject).toList()).toJsonString();
        assertEquals(expected, actual, "differential multi-doc mismatch for:\n" + yaml);
    }

    static Stream<String> blockCases() {
        return Stream.of(
                // nesting + indent widths
                "a:\n  b:\n    c: 1\n",
                "a:\n    b:\n        c: deep\n",
                "a: 1\nb: 2\n",
                "list:\n  - a\n  - b\n",
                "list:\n- a\n- b\n",
                "- a\n- b\n",
                "- name: x\n  sub: 1\n",
                "- a\n  continued here\n",
                "key: a\n  continued here\n",
                // empty values
                "a:\nb: 2\n",
                "a:\n",
                "a: ~\nb:\n",
                // comments
                "# leading comment\nkey: value # trailing\n# tail\n",
                "key: value#notacomment\n",
                "key: a#b\n",
                "- a # item comment\n- b\n",
                // indented root sequence (suite 2AUY/93JH/F2C7)
                " - a\n - b\n",
                // nested dash columns (suite 3ALJ/7ZZ5/W42U)
                "- - s1\n  - s2\n- s3\n",
                "- - - []\n- - - {}\n",
                "- # Empty\n- |\n block node\n",
                // sequence value with following sibling (suite 57H4/AZ63/RLU9)
                "one:\n- 2\nfour: 5\n",
                // anchor prefix without indent change (probe anchor-sibling)
                "key:\n  &a\nsub: 1\n"
        );
    }

    static Stream<String> scalarCases() {
        return Stream.of(
                // plain multiline + folding
                "key: line one\n  line two\n",
                "key: line one\n\n  para two\n",
                // deeper `- ` folds as text (suite AB8U + probe)
                "- single multiline\n - sequence entry\n",
                "- a\n  - b\n",
                // scalar document across blank lines (probe scalar-blank)
                "1st\n\n 2nd\n",
                // quoted
                "a: 'single'\nb: \"double\"\n",
                "a: 'it''s'\n",
                "a: \"tab\\there\"\n",
                "a: \"quote \\\"q\\\"\"\n",
                "a: \"line one\n  line two\"\n",
                "a: 'line one\n  line two'\n",
                "a: \"uni \\u00e9\\u00e8\"\n",
                "a: \"hex \\x41\"\n",
                // nulls + bools
                "a: null\nb: Null\nc: NULL\nd: ~\ne:\n",
                "a: true\nb: True\nc: TRUE\nd: false\ne: False\nf: FALSE\n",
                "a: yes\nb: Yes\nc: YES\nd: no\ne: No\nf: NO\n",
                "a: on\nb: On\nc: ON\nd: off\ne: Off\nf: OFF\n",
                "a: y\nb: Y\nc: n\nd: N\n",
                // integers
                "a: 42\nb: -7\nc: +12\nd: 0\n",
                "a: 0xFF\nb: 0o17\nc: 0b101\nd: 077\n",
                "a: 09\nb: 00\n",
                "a: 9999999999999999999999\n",
                // floats
                "a: 1.5\nb: -2.25\nc: .5\nd: 1.\ne: 1e5\nf: 1E+5\n",
                "a: .inf\nb: -.Inf\nc: .NAN\n",
                // lookalikes that stay strings
                "a: 1_000\nb: 1:20\nc: hello world\nd: 12abc\n"
        );
    }

    static Stream<String> flowCases() {
        return Stream.of(
                "a: {}\nb: []\n",
                "a: {x: 1, y: two}\n",
                "a: [1, two, true, null]\n",
                "a: {x: [1, {y: two}]}\n",
                "top: {a: 1,\n  b: 2}\n",
                "top: [1,\n  2]\n",
                "a: {x: 1} # trailing\n",
                // Trailing commas are legal (suite 5KJE/UDR7)
                "a: [one, two, ]\n",
                "sequence: [ one, two, ]\nmapping: { sky: blue, sea: green }\n",
                "a: {x: 1, }\n"
        );
    }

    static Stream<String> blockScalarCases() {
        return Stream.of(
                "a: |\n  line one\n  line two\n",
                "a: >\n  line one\n  line two\n",
                "a: |-\n  no trailing\n",
                "a: |+\n  kept\n\n\n",
                "a: >-\n  folded\n  lines\n",
                "a: |2\n  indented\n",
                "a: |\n",
                "a: |\n  first\n\n  second\n",
                "a: |\n  keep  spaces  \n",
                // Explicit indent under an inline (`- key:`) map counts from keys (suite 4WA9)
                "- aaa: |2\n    xxx\n  bbb: |\n    xxx\n"
        );
    }

    static Stream<String> anchorCases() {
        return Stream.of(
                "a: &x 1\nb: *x\n",
                "base: &b\n  x: 1\nderived:\n  <<: *b\n  y: 2\n",
                "base: &b\n  x: 1\n  y: 9\nderived:\n  <<: *b\n  y: 2\n",
                "a: &x [1, 2]\nb: *x\n",
                "list:\n  - &i item\n  - *i\n",
                "a: &a {x: 1}\nb: *a\n",
                "x: &x\n  a: 1\n  b: 2\ny: *x\n",
                "base: &b\n  x: 1\nm1:\n  <<: *b\nm2:\n  <<: [*b]\n"
        );
    }

    static Stream<String> intEdgeCases() {
        // Underscores, sexagesimal, prefix edges (SnakeYAML-verified shapes)
        return Stream.of(
                "a: 1_000\nb: 1_0\nc: 1__2\nd: 0_7\n",
                "a: 1:20\nb: 1:2:3\nc: +1:20\nd: -1:20\n",
                "a: 0o17\n",
                "a: 1_0.5\n",
                "a: 0xFF_FF\nb: 0b10_1\n"
        );
    }

    static Stream<String> tagCases() {
        return Stream.of(
                "a: !!str 123\nb: !!int 42\nc: !!float 1.5\nd: !!bool yes\ne: !!null ~\n",
                "a: !!str true\n",
                // Tagged keys strip to the bare key (suite 74H7)
                "!!str a: b\nc: 42\n",
                "top1: &node1\n  &k1 key1: one\n"
        );
    }

    @Test
    void customTagLenientAsBefore() {
        // Unknown !-tags stringify (released JqYaml parity: compose never validated tags).
        // NOTE: SnakeYAML *load* (construct) rejects these; compose accepted them.
        assertEquals("hello", JqYaml.parse("a: !custom hello\n").getField("a").stringValue());
    }

    static Stream<String> docCases() {
        return Stream.of(
                "",
                "\n\n",
                "---\n",
                "--- just text\n",
                "--- just text\nmore text\n",
                "--- {a: 1}\n",
                // NOTE: `--- >` + column-0 content is ACCEPTED here (suite DK3J
                // agrees) although SnakeYAML-load itself rejects it, so those
                // shapes live in conformance, not in live-SnakeYAML differential.
                "a: 1\n---\nb: 2\n",
                "---\na: 1\n...\n---\nb: 2\n",
                "%YAML 1.2\n---\na: 1\n"
        );
    }

    static Stream<String> scalarHeaderCases() {
        // Standalone vs inline block headers, header lookalikes, leading blanks
        return Stream.of(
                "key:\n  >\n  content\n",
                "key:\n  |\n  content\n",
                "- >\n  content\n",
                "a: |\n\n\nb: 1\n",
                "key: >-\n  stripped\n"
        );
    }

    @ParameterizedTest
    @MethodSource({"blockCases", "scalarCases", "flowCases", "blockScalarCases",
            "anchorCases", "tagCases", "intEdgeCases", "scalarHeaderCases"})
    void differentialSingleDoc(String yaml) {
        assertSameAsSnakeYaml(yaml);
    }

    @ParameterizedTest
    @MethodSource("docCases")
    void differentialDocs(String yaml) {
        assertSameAllAsSnakeYaml(yaml);
    }

    @Test
    void differentialBenchmarkFixtures() throws Exception {
        var dir = java.nio.file.Path.of("../jjq-benchmark/src/main/resources/benchmark-data");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isDirectory(dir),
                "benchmark fixtures only present in a full checkout");
        try (var files = java.nio.file.Files.list(dir)) {
            for (var f : files.filter(p -> p.toString().endsWith(".yaml")).sorted().toList()) {
                String yaml = java.nio.file.Files.readString(f);
                assertSameAsSnakeYaml(yaml);
            }
        }
    }

    @Test
    void errorCasesAgreeOnRejection() {
        // Both parsers must reject (messages may differ)
        String[] bad = {
                "key: [unclosed\n",
                "a: \"unterminated\n",
                "m:\n  <<: *missing\n",
                "dup:\n  <<: [*missing]\n",
                "a: 1\n  b: 2\n\tc: 3\n",
                "{a: 1\n",
                "a: [1, 2\n",
                // Block collections cannot open on the same line as `---`
                "--- key: value\n",
                "--- - a\n",
                // `>`/`|` must be followed by chomping/indent/end (not junk)
                "key: >foo\n",
                // Over-indented mapping content: SnakeYAML requires same-indent
                // alignment ("mapping values are not allowed here")
                "a: 1\n  b: 2\n",
                "- name: x\n    sub: 1\n",
                "key: a\n  continued: x\n"
                // NOTE: `? complex` keys parse in SnakeYAML but are rejected here
                // (documented limitation, matching today's effective behavior)
        };
        for (String yaml : bad) {
            assertThrows(Exception.class, () -> JqYaml.parse(yaml), "mine accepted:\n" + yaml);
            assertThrows(Exception.class, () -> SNAKE.load(yaml), "snake accepted:\n" + yaml);
        }
    }
}

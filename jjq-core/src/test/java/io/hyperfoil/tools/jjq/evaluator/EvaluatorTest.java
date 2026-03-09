package io.hyperfoil.tools.jjq.evaluator;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.value.*;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EvaluatorTest {

    private List<JqValue> eval(String expr, JqValue input) {
        return JqProgram.compile(expr).applyTreeWalker(input);
    }

    private List<JqValue> eval(String expr, String json) {
        JqValue input = parseJson(json);
        return eval(expr, input);
    }

    private JqValue first(String expr, String json) {
        return eval(expr, json).getFirst();
    }

    private JqValue first(String expr, JqValue input) {
        return eval(expr, input).getFirst();
    }

    // ---- Identity & Field Access ----

    @Test
    void testIdentity() {
        assertEquals(JqNumber.of(42), first(".", JqNumber.of(42)));
    }

    @Test
    void testFieldAccess() {
        assertEquals(JqNumber.of(1), first(".a", "{\"a\":1}"));
    }

    @Test
    void testNestedFieldAccess() {
        assertEquals(JqNumber.of(2), first(".a.b", "{\"a\":{\"b\":2}}"));
    }

    @Test
    void testFieldAccessNull() {
        assertEquals(JqNull.NULL, first(".missing", "{\"a\":1}"));
    }

    // ---- Pipe ----

    @Test
    void testPipe() {
        assertEquals(JqNumber.of(3), first(".a | . + 1", "{\"a\":2}"));
    }

    // ---- Comma (multiple outputs) ----

    @Test
    void testComma() {
        var results = eval(".a, .b", "{\"a\":1, \"b\":2}");
        assertEquals(2, results.size());
        assertEquals(JqNumber.of(1), results.get(0));
        assertEquals(JqNumber.of(2), results.get(1));
    }

    // ---- Iteration ----

    @Test
    void testArrayIteration() {
        var results = eval(".[]", "[1,2,3]");
        assertEquals(3, results.size());
        assertEquals(JqNumber.of(1), results.get(0));
        assertEquals(JqNumber.of(2), results.get(1));
        assertEquals(JqNumber.of(3), results.get(2));
    }

    @Test
    void testObjectIteration() {
        var results = eval(".[]", "{\"a\":1,\"b\":2}");
        assertEquals(2, results.size());
    }

    // ---- Index ----

    @Test
    void testArrayIndex() {
        assertEquals(JqNumber.of(2), first(".[1]", "[1,2,3]"));
    }

    @Test
    void testArrayNegativeIndex() {
        assertEquals(JqNumber.of(3), first(".[-1]", "[1,2,3]"));
    }

    // ---- Slice ----

    @Test
    void testSlice() {
        assertEquals("[2,3]", first(".[1:3]", "[1,2,3,4]").toJsonString());
    }

    // ---- Array Construction ----

    @Test
    void testArrayConstruct() {
        assertEquals("[1,2]", first("[.a, .b]", "{\"a\":1,\"b\":2}").toJsonString());
    }

    @Test
    void testEmptyArray() {
        assertEquals("[]", first("[]", "null").toJsonString());
    }

    // ---- Object Construction ----

    @Test
    void testObjectConstruct() {
        var result = first("{name: .n, age: .a}", "{\"n\":\"Alice\",\"a\":30}");
        assertEquals("{\"name\":\"Alice\",\"age\":30}", result.toJsonString());
    }

    @Test
    void testObjectShorthand() {
        var result = first("{name}", "{\"name\":\"Alice\",\"age\":30}");
        assertEquals("{\"name\":\"Alice\"}", result.toJsonString());
    }

    // ---- Arithmetic ----

    @Test
    void testAdd() {
        assertEquals(JqNumber.of(3), first(". + 1", JqNumber.of(2)));
    }

    @Test
    void testSubtract() {
        assertEquals(JqNumber.of(1), first(". - 1", JqNumber.of(2)));
    }

    @Test
    void testMultiply() {
        assertEquals(JqNumber.of(6), first(". * 3", JqNumber.of(2)));
    }

    @Test
    void testDivide() {
        assertEquals(JqNumber.of(2), first(". / 3", JqNumber.of(6)));
    }

    @Test
    void testModulo() {
        assertEquals(JqNumber.of(1), first(". % 2", JqNumber.of(7)));
    }

    @Test
    void testNegate() {
        assertEquals(JqNumber.of(-5), first("-.", JqNumber.of(5)));
    }

    @Test
    void testStringAdd() {
        assertEquals(JqString.of("foobar"), first(". + \"bar\"", JqString.of("foo")));
    }

    @Test
    void testArrayAdd() {
        assertEquals("[1,2,3,4]", first(". + [3,4]", "[1,2]").toJsonString());
    }

    // ---- Comparison ----

    @Test
    void testEquals() {
        assertEquals(JqBoolean.TRUE, first(". == 1", JqNumber.of(1)));
        assertEquals(JqBoolean.FALSE, first(". == 2", JqNumber.of(1)));
    }

    @Test
    void testNotEquals() {
        assertEquals(JqBoolean.TRUE, first(". != 2", JqNumber.of(1)));
    }

    @Test
    void testLessThan() {
        assertEquals(JqBoolean.TRUE, first(". < 2", JqNumber.of(1)));
        assertEquals(JqBoolean.FALSE, first(". < 1", JqNumber.of(1)));
    }

    // ---- Logical ----

    @Test
    void testAnd() {
        assertEquals(JqBoolean.TRUE, first("true and true", JqNull.NULL));
        assertEquals(JqBoolean.FALSE, first("true and false", JqNull.NULL));
    }

    @Test
    void testOr() {
        assertEquals(JqBoolean.TRUE, first("true or false", JqNull.NULL));
        assertEquals(JqBoolean.FALSE, first("false or false", JqNull.NULL));
    }

    @Test
    void testNot() {
        assertEquals(JqBoolean.FALSE, first("true | not", JqNull.NULL));
        assertEquals(JqBoolean.TRUE, first("false | not", JqNull.NULL));
    }

    // ---- Alternative ----

    @Test
    void testAlternative() {
        assertEquals(JqNumber.of(1), first(".a // 42", "{\"a\":1}"));
        assertEquals(JqNumber.of(42), first(".a // 42", "{\"b\":1}"));
    }

    // ---- If/Then/Else ----

    @Test
    void testIfThenElse() {
        assertEquals(JqString.of("yes"), first("if . then \"yes\" else \"no\" end", JqBoolean.TRUE));
        assertEquals(JqString.of("no"), first("if . then \"yes\" else \"no\" end", JqBoolean.FALSE));
    }

    @Test
    void testIfWithoutElse() {
        // Without else, input passes through
        assertEquals(JqBoolean.FALSE, first("if . then \"yes\" end", JqBoolean.FALSE));
    }

    // ---- Builtins ----

    @Test
    void testLength() {
        assertEquals(JqNumber.of(3), first("length", "[1,2,3]"));
        assertEquals(JqNumber.of(5), first("length", JqString.of("hello")));
        assertEquals(JqNumber.of(0), first("length", JqNull.NULL));
    }

    @Test
    void testKeys() {
        var result = first("keys", "{\"b\":2,\"a\":1}");
        // keys should be sorted
        assertInstanceOf(JqArray.class, result);
        assertEquals("[\"a\",\"b\"]", result.toJsonString());
    }

    @Test
    void testValues() {
        var result = first("values", "{\"a\":1,\"b\":2}");
        assertInstanceOf(JqArray.class, result);
    }

    @Test
    void testType() {
        assertEquals(JqString.of("number"), first("type", JqNumber.of(1)));
        assertEquals(JqString.of("string"), first("type", JqString.of("hi")));
        assertEquals(JqString.of("array"), first("type", JqArray.EMPTY));
        assertEquals(JqString.of("object"), first("type", JqObject.EMPTY));
        assertEquals(JqString.of("null"), first("type", JqNull.NULL));
        assertEquals(JqString.of("boolean"), first("type", JqBoolean.TRUE));
    }

    @Test
    void testHas() {
        assertEquals(JqBoolean.TRUE, first("has(\"a\")", "{\"a\":1}"));
        assertEquals(JqBoolean.FALSE, first("has(\"b\")", "{\"a\":1}"));
    }

    @Test
    void testMap() {
        assertEquals("[2,3,4]", first("map(. + 1)", "[1,2,3]").toJsonString());
    }

    @Test
    void testSelect() {
        var results = eval(".[] | select(. > 2)", "[1,2,3,4]");
        assertEquals(2, results.size());
        assertEquals(JqNumber.of(3), results.get(0));
        assertEquals(JqNumber.of(4), results.get(1));
    }

    @Test
    void testAddBuiltin() {
        assertEquals(JqNumber.of(6), first("add", "[1,2,3]"));
        assertEquals(JqString.of("abc"), first("add", "[\"a\",\"b\",\"c\"]"));
    }

    @Test
    void testFlatten() {
        assertEquals("[1,2,3,4]", first("flatten", "[[1,2],[3,[4]]]").toJsonString());
    }

    @Test
    void testSort() {
        assertEquals("[1,2,3]", first("sort", "[3,1,2]").toJsonString());
    }

    @Test
    void testUnique() {
        assertEquals("[1,2,3]", first("unique", "[1,2,1,3,2]").toJsonString());
    }

    @Test
    void testReverse() {
        assertEquals("[3,2,1]", first("reverse", "[1,2,3]").toJsonString());
    }

    @Test
    void testMinMax() {
        assertEquals(JqNumber.of(1), first("min", "[3,1,2]"));
        assertEquals(JqNumber.of(3), first("max", "[3,1,2]"));
    }

    @Test
    void testRange() {
        var results = eval("range(3)", JqNull.NULL);
        assertEquals(3, results.size());
        assertEquals(JqNumber.of(0), results.get(0));
        assertEquals(JqNumber.of(1), results.get(1));
        assertEquals(JqNumber.of(2), results.get(2));
    }

    @Test
    void testToStringFn() {
        assertEquals(JqString.of("42"), first("tostring", JqNumber.of(42)));
        assertEquals(JqString.of("hello"), first("tostring", JqString.of("hello")));
    }

    @Test
    void testToNumber() {
        assertEquals(JqNumber.of(42), first("tonumber", JqString.of("42")));
        assertEquals(JqNumber.of(42), first("tonumber", JqNumber.of(42)));
    }

    @Test
    void testSplit() {
        assertEquals("[\"a\",\"b\",\"c\"]", first("split(\",\")", JqString.of("a,b,c")).toJsonString());
    }

    @Test
    void testJoin() {
        assertEquals(JqString.of("a,b,c"), first("join(\",\")", "[\"a\",\"b\",\"c\"]"));
    }

    @Test
    void testAsciiCase() {
        assertEquals(JqString.of("HELLO"), first("ascii_upcase", JqString.of("hello")));
        assertEquals(JqString.of("hello"), first("ascii_downcase", JqString.of("HELLO")));
    }

    @Test
    void testStartsEndsWith() {
        assertEquals(JqBoolean.TRUE, first("startswith(\"he\")", JqString.of("hello")));
        assertEquals(JqBoolean.FALSE, first("startswith(\"wo\")", JqString.of("hello")));
        assertEquals(JqBoolean.TRUE, first("endswith(\"lo\")", JqString.of("hello")));
    }

    @Test
    void testLtrimRtrim() {
        assertEquals(JqString.of("llo"), first("ltrimstr(\"he\")", JqString.of("hello")));
        assertEquals(JqString.of("hel"), first("rtrimstr(\"lo\")", JqString.of("hello")));
    }

    @Test
    void testFloor() {
        assertEquals(JqNumber.of(3), first("floor", JqNumber.of(3.7)));
    }

    @Test
    void testCeil() {
        assertEquals(JqNumber.of(4), first("ceil", JqNumber.of(3.2)));
    }

    @Test
    void testRound() {
        assertEquals(JqNumber.of(4), first("round", JqNumber.of(3.5)));
    }

    @Test
    void testContains() {
        assertEquals(JqBoolean.TRUE, first("contains(\"ell\")", JqString.of("hello")));
        assertEquals(JqBoolean.TRUE, first("contains([2,3])", "[1,2,3,4]"));
    }

    // ---- User-defined functions ----

    @Test
    void testFuncDef() {
        assertEquals(JqNumber.of(4), first("def double: . * 2; double", JqNumber.of(2)));
    }

    // ---- Reduce ----

    @Test
    void testReduce() {
        assertEquals(JqNumber.of(6), first("reduce .[] as $x (0; . + $x)", "[1,2,3]"));
    }

    // ---- Try/Catch ----

    @Test
    void testTryCatch() {
        // try accessing field on number should catch
        var result = first("try .foo catch \"err\"", JqNumber.of(1));
        assertEquals(JqString.of("err"), result);
    }

    @Test
    void testTryWithoutCatch() {
        // try without catch suppresses errors
        var results = eval("try .foo", JqNumber.of(1));
        assertEquals(0, results.size());
    }

    // ---- Optional ----

    @Test
    void testOptional() {
        var results = eval(".foo?", JqNumber.of(1));
        assertEquals(0, results.size());
    }

    // ---- Recurse ----

    @Test
    void testRecurse() {
        var results = eval("..", "{\"a\":{\"b\":1}}");
        assertTrue(results.size() > 1); // should produce root, .a, .b value
    }

    // ---- Update ----

    @Test
    void testUpdate() {
        assertEquals("{\"a\":2}", first(".a |= . + 1", "{\"a\":1}").toJsonString());
    }

    @Test
    void testAssign() {
        assertEquals("{\"a\":42}", first(".a = 42", "{\"a\":1}").toJsonString());
    }

    // ---- to_entries / from_entries ----

    @Test
    void testToEntries() {
        var result = first("to_entries", "{\"a\":1}");
        assertEquals("[{\"key\":\"a\",\"value\":1}]", result.toJsonString());
    }

    @Test
    void testFromEntries() {
        var result = first("from_entries", "[{\"key\":\"a\",\"value\":1}]");
        assertEquals("{\"a\":1}", result.toJsonString());
    }

    // ---- Format ----

    @Test
    void testFormatBase64() {
        var result = first("@base64", JqString.of("hello"));
        assertEquals(JqString.of("aGVsbG8="), result);
    }

    @Test
    void testFormatBase64d() {
        var result = first("@base64d", JqString.of("aGVsbG8="));
        assertEquals(JqString.of("hello"), result);
    }

    @Test
    void testFormatHtml() {
        var result = first("@html", JqString.of("<b>hi</b>"));
        assertEquals(JqString.of("&lt;b&gt;hi&lt;/b&gt;"), result);
    }

    @Test
    void testFormatCsv() {
        var result = first("@csv", "[1,\"hello\",true]");
        assertEquals(JqString.of("1,\"hello\",true"), result);
    }

    @Test
    void testTest() {
        assertEquals(JqBoolean.TRUE, first("test(\"oo\")", JqString.of("foo")));
        assertEquals(JqBoolean.FALSE, first("test(\"^oo\")", JqString.of("foo")));
    }

    // ---- Path builtins ----

    @Test
    void testGetpath() {
        assertEquals(JqNumber.of(1), first("getpath([\"a\"])", "{\"a\":1}"));
        assertEquals(JqNumber.of(2), first("getpath([\"a\",\"b\"])", "{\"a\":{\"b\":2}}"));
    }

    @Test
    void testSetpath() {
        assertEquals("{\"a\":42}", first("setpath([\"a\"]; 42)", "{\"a\":1}").toJsonString());
    }

    // ---- Tojson / Fromjson ----

    @Test
    void testTojson() {
        assertEquals(JqString.of("42"), first("tojson", JqNumber.of(42)));
        assertEquals(JqString.of("[1,2]"), first("tojson", "[1,2]"));
    }

    @Test
    void testFromjson() {
        assertEquals(JqNumber.of(42), first("fromjson", JqString.of("42")));
    }

    // ---- Variable binding ----

    @Test
    void testVariableBinding() {
        assertEquals(JqNumber.of(3), first(". as $x | $x + 1", JqNumber.of(2)));
    }

    // ---- Complex expressions ----

    @Test
    void testMapSelect() {
        assertEquals("[3,4]", first("[.[] | select(. > 2)]", "[1,2,3,4]").toJsonString());
    }

    @Test
    void testPipeChain() {
        assertEquals(JqString.of("ALICE"),
                first(".users[0].name | ascii_upcase",
                        "{\"users\":[{\"name\":\"alice\"},{\"name\":\"bob\"}]}"));
    }

    @Test
    void testObjectTransform() {
        var result = first(".[] | {name, email}",
                "[{\"name\":\"Alice\",\"email\":\"a@b.com\",\"age\":30}]");
        assertEquals("{\"name\":\"Alice\",\"email\":\"a@b.com\"}", result.toJsonString());
    }

    // ---- Helpers ----

    private static JqValue parseJson(String json) {
        return JqValues.parse(json);
    }
}

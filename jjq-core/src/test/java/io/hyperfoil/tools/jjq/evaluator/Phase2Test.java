package io.hyperfoil.tools.jjq.evaluator;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.value.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 tests: language completeness, filter arguments, new builtins,
 * special variables, and edge cases.
 */
class Phase2Test {

    private List<JqValue> eval(String expr, JqValue input) {
        return JqProgram.compile(expr).applyTreeWalker(input);
    }

    private List<JqValue> eval(String expr, String json) {
        return eval(expr, JqValues.parse(json));
    }

    private JqValue first(String expr, String json) {
        return eval(expr, json).getFirst();
    }

    private JqValue first(String expr, JqValue input) {
        return eval(expr, input).getFirst();
    }

    // =========================================================================
    // Filter Arguments for User-Defined Functions
    // =========================================================================

    @Nested
    class FilterArguments {

        @Test
        void testDefWithFilterArg() {
            // def double(f): f + f; double(. * 2)
            assertEquals(JqNumber.of(8),
                    first("def double(f): f + f; double(. * 2)", JqNumber.of(2)));
        }

        @Test
        void testDefMapLikeFunction() {
            // def mymap(f): [.[] | f]; mymap(. + 10)
            assertEquals("[11,12,13]",
                    first("def mymap(f): [.[] | f]; mymap(. + 10)", "[1,2,3]").toJsonString());
        }

        @Test
        void testDefSelectLikeFunction() {
            // def myselect(f): if f then . else empty end; [.[] | myselect(. > 2)]
            assertEquals("[3,4]",
                    first("def myselect(f): if f then . else empty end; [.[] | myselect(. > 2)]",
                            "[1,2,3,4]").toJsonString());
        }

        @Test
        void testDefWithMultipleFilterArgs() {
            // def apply(f; g): f | g; apply(. + 1; . * 2)
            assertEquals(JqNumber.of(6),
                    first("def apply(f; g): f | g; apply(. + 1; . * 2)", JqNumber.of(2)));
        }

        @Test
        void testRecursiveFunction() {
            // def fact: if . <= 1 then 1 else . * ((. - 1) | fact) end; fact
            assertEquals(JqNumber.of(120),
                    first("def fact: if . <= 1 then 1 else . * ((. - 1) | fact) end; fact",
                            JqNumber.of(5)));
        }

        @Test
        void testNestedFunctionDefs() {
            // 4 * 2 = 8, 8 + 3 = 11
            assertEquals(JqNumber.of(11),
                    first("def add3: . + 3; def double: . * 2; double | add3", JqNumber.of(4)));
        }

        @Test
        void testFilterArgWithVariable() {
            // The filter arg should capture the caller's context
            assertEquals("[2,3,4]",
                    first("1 as $n | def addN(f): [.[] | f]; addN(. + $n)",
                            "[1,2,3]").toJsonString());
        }
    }

    // =========================================================================
    // Special Variables
    // =========================================================================

    @Nested
    class SpecialVariables {

        @Test
        void testEnvVariable() {
            // $ENV should be an object
            var result = first("$ENV | type", JqNull.NULL);
            assertEquals(JqString.of("object"), result);
        }

        @Test
        void testLocVariable() {
            // $__loc__ should return an object with file and line
            var result = first("$__loc__ | keys", JqNull.NULL);
            assertTrue(result.toJsonString().contains("file"));
            assertTrue(result.toJsonString().contains("line"));
        }
    }

    // =========================================================================
    // del() builtin
    // =========================================================================

    @Nested
    class DelBuiltin {

        @Test
        void testDelField() {
            var result = first("del(.a)", "{\"a\":1,\"b\":2}");
            assertEquals("{\"b\":2}", result.toJsonString());
        }

        @Test
        void testDelArrayIndex() {
            var result = first("del(.[1])", "[1,2,3]");
            assertEquals("[1,3]", result.toJsonString());
        }

        @Test
        void testDelMultipleFields() {
            var result = first("del(.a, .c)", "{\"a\":1,\"b\":2,\"c\":3}");
            assertEquals("{\"b\":2}", result.toJsonString());
        }
    }

    // =========================================================================
    // paths builtin
    // =========================================================================

    @Nested
    class PathsBuiltin {

        @Test
        void testPaths() {
            var results = eval("paths", "{\"a\":1,\"b\":{\"c\":2}}");
            assertTrue(results.size() > 1);
            // Should include [], ["a"], ["b"], ["b","c"]
        }

        @Test
        void testPathsWithFilter() {
            // paths(type == "number") - only paths to numbers
            var results = eval("paths(type == \"number\")", "{\"a\":1,\"b\":{\"c\":2}}");
            assertEquals(2, results.size());
        }

        @Test
        void testLeafPaths() {
            var results = eval("leaf_paths", "{\"a\":1,\"b\":{\"c\":2}}");
            assertEquals(2, results.size());
        }
    }

    // =========================================================================
    // isempty
    // =========================================================================

    @Nested
    class IsemptyBuiltin {

        @Test
        void testIsemptyTrue() {
            assertEquals(JqBoolean.TRUE, first("isempty(empty)", JqNull.NULL));
        }

        @Test
        void testIsemptyFalse() {
            assertEquals(JqBoolean.FALSE, first("isempty(.[])", "[1,2]"));
        }

        @Test
        void testIsemptyEmptyArray() {
            assertEquals(JqBoolean.TRUE, first("isempty(.[])", "[]"));
        }
    }

    // =========================================================================
    // any/2, all/2
    // =========================================================================

    @Nested
    class AnyAllTwoArg {

        @Test
        void testAnyWithGenerator() {
            assertEquals(JqBoolean.TRUE,
                    first("any(.[]; . > 3)", "[1,2,3,4,5]"));
        }

        @Test
        void testAnyWithGeneratorFalse() {
            assertEquals(JqBoolean.FALSE,
                    first("any(.[]; . > 10)", "[1,2,3]"));
        }

        @Test
        void testAllWithGenerator() {
            assertEquals(JqBoolean.TRUE,
                    first("all(.[]; . > 0)", "[1,2,3]"));
        }

        @Test
        void testAllWithGeneratorFalse() {
            assertEquals(JqBoolean.FALSE,
                    first("all(.[]; . > 2)", "[1,2,3]"));
        }
    }

    // =========================================================================
    // Date builtins
    // =========================================================================

    @Nested
    class DateBuiltins {

        @Test
        void testNow() {
            var result = first("now", JqNull.NULL);
            assertInstanceOf(JqNumber.class, result);
            assertTrue(result.doubleValue() > 1700000000);
        }

        @Test
        void testTodate() {
            var result = first("todate", JqNumber.of(0));
            assertEquals(JqString.of("1970-01-01T00:00:00Z"), result);
        }

        @Test
        void testFromdate() {
            var result = first("fromdate", JqString.of("1970-01-01T00:00:00Z"));
            assertEquals(JqNumber.of(0), result);
        }
    }

    // =========================================================================
    // walk
    // =========================================================================

    @Nested
    class WalkBuiltin {

        @Test
        void testWalkNumbers() {
            assertEquals("{\"a\":2,\"b\":{\"c\":4}}",
                    first("walk(if type == \"number\" then . * 2 else . end)",
                            "{\"a\":1,\"b\":{\"c\":2}}").toJsonString());
        }

        @Test
        void testWalkStrings() {
            assertEquals("{\"a\":\"HELLO\"}",
                    first("walk(if type == \"string\" then ascii_upcase else . end)",
                            "{\"a\":\"hello\"}").toJsonString());
        }
    }

    // =========================================================================
    // combinations
    // =========================================================================

    @Nested
    class CombinationsBuiltin {

        @Test
        void testCombinations() {
            var results = eval("combinations", "[[1,2],[3,4]]");
            assertEquals(4, results.size());
            assertEquals("[1,3]", results.get(0).toJsonString());
            assertEquals("[1,4]", results.get(1).toJsonString());
            assertEquals("[2,3]", results.get(2).toJsonString());
            assertEquals("[2,4]", results.get(3).toJsonString());
        }

        @Test
        void testCombinationsN() {
            var results = eval("combinations(2)", "[1,2]");
            assertEquals(4, results.size());
        }
    }

    // =========================================================================
    // Type selectors
    // =========================================================================

    @Nested
    class TypeSelectors {

        @Test
        void testScalars() {
            var results = eval("[.[] | scalars]", "[1,\"a\",[2],{\"b\":3},null,true]");
            assertEquals("[1,\"a\",null,true]", results.getFirst().toJsonString());
        }

        @Test
        void testNumbers() {
            var results = eval("[.[] | numbers]", "[1,\"a\",2,true]");
            assertEquals("[1,2]", results.getFirst().toJsonString());
        }

        @Test
        void testStrings() {
            var results = eval("[.[] | strings]", "[1,\"a\",2,\"b\"]");
            assertEquals("[\"a\",\"b\"]", results.getFirst().toJsonString());
        }

        @Test
        void testArrays() {
            var results = eval("[.[] | arrays]", "[1,[2],[3],\"a\"]");
            assertEquals("[[2],[3]]", results.getFirst().toJsonString());
        }

        @Test
        void testObjects() {
            var results = eval("[.[] | objects]", "[1,{\"a\":1},{\"b\":2},\"x\"]");
            assertEquals("[{\"a\":1},{\"b\":2}]", results.getFirst().toJsonString());
        }

        @Test
        void testBooleans() {
            var results = eval("[.[] | booleans]", "[true,1,false,\"a\"]");
            assertEquals("[true,false]", results.getFirst().toJsonString());
        }

        @Test
        void testNulls() {
            var results = eval("[.[] | nulls]", "[null,1,null,\"a\"]");
            assertEquals("[null,null]", results.getFirst().toJsonString());
        }
    }

    // =========================================================================
    // String multiplication
    // =========================================================================

    @Nested
    class StringOps {

        @Test
        void testStringMultiply() {
            assertEquals(JqString.of("ababab"),
                    first(". * 3", JqString.of("ab")));
        }

        @Test
        void testStringMultiplyZero() {
            assertEquals(JqNull.NULL,
                    first(". * 0", JqString.of("ab")));
        }

        @Test
        void testNullMultiply() {
            assertEquals(JqNull.NULL, first("null * 5", JqNull.NULL));
            assertEquals(JqNull.NULL, first("5 * null", JqNull.NULL));
        }
    }

    // =========================================================================
    // Math builtins
    // =========================================================================

    @Nested
    class MathBuiltins {

        @Test
        void testAbs() {
            assertEquals(JqNumber.of(5), first("abs", JqNumber.of(-5)));
            assertEquals(JqNumber.of(5), first("abs", JqNumber.of(5)));
        }

        @Test
        void testSinCos() {
            var sin = first("sin", JqNumber.of(0));
            assertEquals(0.0, sin.doubleValue(), 0.001);
            var cos = first("cos", JqNumber.of(0));
            assertEquals(1.0, cos.doubleValue(), 0.001);
        }

        @Test
        void testCbrt() {
            assertEquals(3.0, first("cbrt", JqNumber.of(27)).doubleValue(), 0.001);
        }
    }

    // =========================================================================
    // Format strings
    // =========================================================================

    @Nested
    class FormatStrings {

        @Test
        void testBase32() {
            var result = first("@base32", JqString.of("hello"));
            assertEquals(JqString.of("NBSWY3DP"), result);
        }

        @Test
        void testBase32d() {
            var result = first("@base32d", JqString.of("NBSWY3DP"));
            assertEquals(JqString.of("hello"), result);
        }

        @Test
        void testShFormat() {
            var result = first("@sh", JqString.of("hello world"));
            assertEquals(JqString.of("'hello world'"), result);
        }
    }

    // =========================================================================
    // tostream / fromstream
    // =========================================================================

    @Nested
    class StreamOps {

        @Test
        void testTostream() {
            var results = eval("tostream", "{\"a\":1}");
            assertTrue(results.size() >= 1);
            // First should be [["a"],1]
            assertEquals("[[\"a\"],1]", results.get(0).toJsonString());
        }
    }

    // =========================================================================
    // halt
    // =========================================================================

    @Nested
    class HaltBuiltin {

        @Test
        void testHalt() {
            assertThrows(HaltException.class, () ->
                    eval("halt", JqNull.NULL));
        }

        @Test
        void testHaltError() {
            var ex = assertThrows(HaltException.class, () ->
                    eval("halt_error(1)", JqString.of("error msg")));
            assertEquals(1, ex.exitCode());
        }
    }

    // =========================================================================
    // Alternative operator //
    // =========================================================================

    @Nested
    class AlternativeOps {

        @Test
        void testAlternativeWithNull() {
            assertEquals(JqNumber.of(42), first("null // 42", JqNull.NULL));
        }

        @Test
        void testAlternativeWithFalse() {
            assertEquals(JqNumber.of(42), first("false // 42", JqNull.NULL));
        }

        @Test
        void testAlternativeChain() {
            assertEquals(JqNumber.of(3),
                    first(".a // .b // .c", "{\"c\":3}"));
        }
    }

    // =========================================================================
    // Complex expressions
    // =========================================================================

    @Nested
    class ComplexExpressions {

        @Test
        void testGroupByAndCount() {
            assertEquals("[{\"name\":\"a\",\"count\":2},{\"name\":\"b\",\"count\":1}]",
                    first("[group_by(.name)[] | {name: .[0].name, count: length}]",
                            "[{\"name\":\"a\"},{\"name\":\"b\"},{\"name\":\"a\"}]").toJsonString());
        }

        @Test
        void testReduceWithObject() {
            var result = first(
                    "reduce .[] as $x ({}; .[$x.name] = ((.[$x.name] // 0) + 1))",
                    "[{\"name\":\"a\"},{\"name\":\"b\"},{\"name\":\"a\"}]");
            assertEquals("{\"a\":2,\"b\":1}", result.toJsonString());
        }

        @Test
        void testForeachWithExtract() {
            var results = eval(
                    "[foreach .[] as $x (0; . + $x)]",
                    "[1,2,3,4,5]");
            assertEquals("[1,3,6,10,15]", results.getFirst().toJsonString());
        }

        @Test
        void testWithEntriesTransform() {
            var result = first(
                    "with_entries(select(.value > 1))",
                    "{\"a\":1,\"b\":2,\"c\":3}");
            assertEquals("{\"b\":2,\"c\":3}", result.toJsonString());
        }

        @Test
        void testStringInterpolation() {
            var result = first(
                    "\"Hello, \\(.name)! You are \\(.age) years old.\"",
                    "{\"name\":\"Alice\",\"age\":30}");
            assertEquals(JqString.of("Hello, Alice! You are 30 years old."), result);
        }

        @Test
        void testTryCatchWithMessage() {
            var result = first(
                    "try error(\"custom error\") catch .",
                    JqNull.NULL);
            assertEquals(JqString.of("custom error"), result);
        }

        @Test
        void testLabelBreak() {
            // label $out | foreach .[] as $x (0; . + $x; if . > 5 then ., break $out else . end)
            var results = eval(
                    "label $out | foreach .[] as $x (0; . + $x; if . > 5 then ., break $out else . end)",
                    "[1,2,3,4,5]");
            assertTrue(results.size() >= 3);
        }

        @Test
        void testNestedObjectConstruction() {
            var result = first(
                    "{user: {name: .n, info: {age: .a, city: .c}}}",
                    "{\"n\":\"Alice\",\"a\":30,\"c\":\"NYC\"}");
            assertEquals("{\"user\":{\"name\":\"Alice\",\"info\":{\"age\":30,\"city\":\"NYC\"}}}",
                    result.toJsonString());
        }

        @Test
        void testMapSelectPipe() {
            var result = first(
                    "[.[] | select(.age > 25) | .name]",
                    "[{\"name\":\"Alice\",\"age\":30},{\"name\":\"Bob\",\"age\":20},{\"name\":\"Carol\",\"age\":35}]");
            assertEquals("[\"Alice\",\"Carol\"]", result.toJsonString());
        }
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Nested
    class EdgeCases {

        @Test
        void testEmptyInArray() {
            assertEquals("[]", first("[empty]", JqNull.NULL).toJsonString());
        }

        @Test
        void testOptionalOnMissing() {
            var results = eval(".foo?", JqNumber.of(1));
            assertEquals(0, results.size());
        }

        @Test
        void testNullFieldAccess() {
            assertEquals(JqNull.NULL, first(".a", JqNull.NULL));
        }

        @Test
        void testNullIteration() {
            var results = eval("null | .[]?", JqNull.NULL);
            assertEquals(0, results.size());
        }

        @Test
        void testMultipleOutputsInPipe() {
            var results = eval(".[] | . * 2", "[1,2,3]");
            assertEquals(3, results.size());
            assertEquals(JqNumber.of(2), results.get(0));
            assertEquals(JqNumber.of(4), results.get(1));
            assertEquals(JqNumber.of(6), results.get(2));
        }

        @Test
        void testNestedTryCatch() {
            var result = first(
                    "try (try error catch \"inner\") catch \"outer\"",
                    JqNull.NULL);
            assertEquals(JqString.of("inner"), result);
        }

        @Test
        void testRecurseWithDepth() {
            var results = eval("[recurse(.+1; . < 5)]", JqNumber.of(0));
            assertEquals("[0,1,2,3,4]", results.getFirst().toJsonString());
        }

        @Test
        void testAddNullArray() {
            assertEquals(JqNull.NULL, first("add", "[]"));
        }

        @Test
        void testSortByMultiple() {
            var result = first("sort_by(.a)",
                    "[{\"a\":2},{\"a\":1},{\"a\":3}]");
            assertEquals("[{\"a\":1},{\"a\":2},{\"a\":3}]", result.toJsonString());
        }

        @Test
        void testObjectMerge() {
            var result = first(". + {\"c\":3}",
                    "{\"a\":1,\"b\":2}");
            assertEquals("{\"a\":1,\"b\":2,\"c\":3}", result.toJsonString());
        }
    }
}

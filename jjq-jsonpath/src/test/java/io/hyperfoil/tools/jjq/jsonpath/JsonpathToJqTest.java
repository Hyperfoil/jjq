package io.hyperfoil.tools.jjq.jsonpath;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonpathToJqTest {

    // ========================================================================
    //  Strict mode: basic path conversion
    // ========================================================================

    @Nested
    class StrictBasicPaths {
        @Test void identity() { assertStrict("$", "."); }
        @Test void singleField() { assertStrict("$.a", ".a"); }
        @Test void twoFields() { assertStrict("$.a.b", ".a.b"); }
        @Test void threeFields() { assertStrict("$.a.b.c", ".a.b.c"); }
        @Test void deepPath() { assertStrict("$.a.b.c.d.e", ".a.b.c.d.e"); }
        @Test void nullInput() { assertStrict(null, "."); }
        @Test void emptyInput() { assertStrict("", "."); }
        @Test void whitespace() { assertStrict("  $  ", "."); }
    }

    // ========================================================================
    //  Strict mode: array iteration
    // ========================================================================

    @Nested
    class StrictArrayIteration {
        @Test void wildcardArray() { assertStrict("$.a[*]", ".a[]?"); }
        @Test void wildcardArrayField() { assertStrict("$.a[*].b", ".a[]?.b"); }
        @Test void rootWildcard() { assertStrict("$[*]", ".[]?"); }
        @Test void wildcardDotStar() { assertStrict("$.a[*].*", ".a[]?"); }
        @Test void dotStarWildcard() { assertStrict("$.foo.*", ".foo[]?"); }
        @Test void multipleWildcards() { assertStrict("$.a[*].b[*]", ".a[]?.b[]?"); }
    }

    // ========================================================================
    //  Strict mode: recursive descent
    // ========================================================================

    @Nested
    class StrictRecursiveDescent {
        @Test void recursiveWithDepth() {
            String result = JsonpathToJq.convert("$.**{2}", JsonpathToJq.Mode.STRICT);
            assertTrue(result.contains("recurse"), "Should contain 'recurse': " + result);
        }
    }

    // ========================================================================
    //  Strict mode: methods
    // ========================================================================

    @Nested
    class StrictMethods {
        @Test void size() { assertStrict("$.config.size()", ".config | length"); }
        @Test void keyvalue() {
            String result = JsonpathToJq.convert("$.results[*].keyvalue().key", JsonpathToJq.Mode.STRICT);
            assertTrue(result.contains("to_entries[]"), "Should contain 'to_entries[]': " + result);
            assertTrue(result.contains(".key"), "Should contain '.key': " + result);
        }
        @Test void doubleMethod() { assertStrict("$.value.double()", ".value | tonumber"); }
        @Test void stringMethod() { assertStrict("$.value.string()", ".value | tostring"); }
        @Test void typeMethod() { assertStrict("$.value.type()", ".value | type"); }
        @Test void ceilingMethod() { assertStrict("$.value.ceiling()", ".value | ceil"); }
        @Test void floorMethod() { assertStrict("$.value.floor()", ".value | floor"); }
        @Test void absMethod() { assertStrict("$.value.abs()", ".value | fabs"); }
    }

    // ========================================================================
    //  Strict mode: filter expressions
    // ========================================================================

    @Nested
    class StrictFilters {
        @Test void simpleEquality() {
            String result = JsonpathToJq.convert("$.results[*] ?(@.name == \"x\")", JsonpathToJq.Mode.STRICT);
            assertTrue(result.contains("select("), "Should contain 'select(': " + result);
            assertTrue(result.contains(".name == \"x\""), "Should contain '.name == \"x\"': " + result);
        }

        @Test void greaterThan() {
            String result = JsonpathToJq.convert("$.items[*] ?(@.price > 100)", JsonpathToJq.Mode.STRICT);
            assertTrue(result.contains("select("), result);
            assertTrue(result.contains(".price > 100"), result);
        }

        @Test void logicalAnd() {
            String result = JsonpathToJq.convert("$.a[*] ?(@.b > 5 && @.c == \"y\")", JsonpathToJq.Mode.STRICT);
            assertTrue(result.contains("and"), "Should convert && to and: " + result);
            assertTrue(result.contains(".b > 5"), result);
            assertTrue(result.contains(".c == \"y\""), result);
        }

        @Test void logicalOr() {
            String result = JsonpathToJq.convert("$.a[*] ?(@.x == 1 || @.y == 2)", JsonpathToJq.Mode.STRICT);
            assertTrue(result.contains("or"), "Should convert || to or: " + result);
        }

        @Test void bareAtSign() {
            String result = JsonpathToJq.convert("$.a[*] ?(@ > 5)", JsonpathToJq.Mode.STRICT);
            assertTrue(result.contains("select(. > 5)"), "Should convert @ to .: " + result);
        }

        @Test void likeRegex() {
            String result = JsonpathToJq.convert(
                    "$.a[*] ?(@.name like_regex \"pat\" flag \"i\")", JsonpathToJq.Mode.STRICT);
            assertTrue(result.contains("test(\"pat\"; \"i\")"), "Should convert like_regex: " + result);
        }

        @Test void likeRegexNoFlags() {
            String result = JsonpathToJq.convert(
                    "$.a[*] ?(@.name like_regex \"^test$\")", JsonpathToJq.Mode.STRICT);
            assertTrue(result.contains("test(\"^test$\")"), "Should convert like_regex without flags: " + result);
        }

        @Test void filterAddsIterator() {
            // Filter on non-array path should add []?
            String result = JsonpathToJq.convert("$.data ?(@.active == true)", JsonpathToJq.Mode.STRICT);
            assertTrue(result.contains("[]?"), "Should add []? before select: " + result);
            assertTrue(result.contains("select("), result);
        }

        @Test void filterDoesNotDuplicateIterator() {
            // When the path already has []?, don't add another
            String result = JsonpathToJq.convert("$.data[*] ?(@.active == true)", JsonpathToJq.Mode.STRICT);
            assertFalse(result.contains("[]?[]?"), "Should not duplicate []?: " + result);
        }
    }

    // ========================================================================
    //  Strict mode: quoted keys
    // ========================================================================

    @Nested
    class StrictQuotedKeys {
        @Test void quotedKeyWithDot() {
            // Quoted key containing a dot — should NOT be treated as path separator
            String result = JsonpathToJq.convert("$.\"special.key\".value", JsonpathToJq.Mode.STRICT);
            assertTrue(result.contains("\"special.key\""), "Should preserve quoted key: " + result);
        }

        @Test void wildcardInQuotedKey() {
            // .* inside quotes should NOT be replaced with []?
            String result = JsonpathToJq.convert("$.\"mw********.lab\"", JsonpathToJq.Mode.STRICT);
            assertTrue(result.contains("\"mw********.lab\""), "Should preserve quoted content: " + result);
            assertFalse(result.contains("[]?"), "Should not replace .* inside quotes: " + result);
        }

        @Test void quotedFieldAccess() {
            String result = JsonpathToJq.convert("$.results[*] ?(@.\"special-field\" == \"x\")", JsonpathToJq.Mode.STRICT);
            assertTrue(result.contains("[\"special-field\"]"), "Should convert @.\"x\" to .[\"x\"]: " + result);
        }
    }

    // ========================================================================
    //  Strict mode: mode prefix in expression
    // ========================================================================

    @Nested
    class ModePrefix {
        @Test void strictPrefix() {
            String result = JsonpathToJq.convert("strict $.a.b");
            // Even though default is LAX, "strict" prefix overrides
            assertEquals(".a.b", result);
        }

        @Test void laxPrefix() {
            String result = JsonpathToJq.convert("lax $.a.b", JsonpathToJq.Mode.STRICT);
            // "lax" prefix overrides the Mode parameter
            assertTrue(result.contains("if ("), "lax prefix should enable array unwrapping: " + result);
        }
    }

    // ========================================================================
    //  Array conversion (jsonb_path_query_array equivalent)
    // ========================================================================

    @Nested
    class ArrayConversion {
        @Test void scalarPathWrapsWithEmpty() {
            String result = JsonpathToJq.convertArray("$.a.b", JsonpathToJq.Mode.STRICT);
            assertTrue(result.startsWith("["), "Should start with [: " + result);
            assertTrue(result.endsWith("]"), "Should end with ]: " + result);
            assertTrue(result.contains("// empty"), "Scalar path should use // empty: " + result);
        }

        @Test void iteratorPathWrapsPlain() {
            String result = JsonpathToJq.convertArray("$.a[*].b", JsonpathToJq.Mode.STRICT);
            assertTrue(result.startsWith("["), result);
            assertTrue(result.endsWith("]"), result);
            assertFalse(result.contains("// empty"), "Iterator path should not use // empty: " + result);
        }
    }

    // ========================================================================
    //  Lax mode: array unwrapping
    // ========================================================================

    @Nested
    class LaxMode {
        @Test void singleSegmentNoUnwrap() {
            // Single segment needs no unwrapping
            assertEquals(".a", JsonpathToJq.convert("$.a", JsonpathToJq.Mode.LAX));
        }

        @Test void twoSegmentsUnwrapsFirst() {
            String result = JsonpathToJq.convert("$.a.b", JsonpathToJq.Mode.LAX);
            assertTrue(result.contains("if (.a | type) == \"array\""), "Should unwrap .a: " + result);
            assertTrue(result.contains(".a[]"), "Should iterate .a when array: " + result);
            assertTrue(result.endsWith(".b"), "Last segment should be plain: " + result);
        }

        @Test void threeSegmentsUnwrapsFirstTwo() {
            String result = JsonpathToJq.convert("$.a.b.c", JsonpathToJq.Mode.LAX);
            assertTrue(result.contains("if (.a | type) == \"array\""), "Should unwrap .a: " + result);
            assertTrue(result.contains("if (.b | type) == \"array\""), "Should unwrap .b: " + result);
            assertTrue(result.endsWith(".c"), "Last segment should be plain: " + result);
        }

        @Test void withExistingIterator() {
            // $.a[*].b.c — a[*] is already explicit, but b.c needs lax unwrapping
            String result = JsonpathToJq.convert("$.a[*].b.c", JsonpathToJq.Mode.LAX);
            assertTrue(result.contains("[]?"), "Should have iterator: " + result);
            assertTrue(result.contains("if (.b | type) == \"array\""), "Should unwrap .b: " + result);
        }

        @Test void identityNoChange() {
            assertEquals(".", JsonpathToJq.convert("$", JsonpathToJq.Mode.LAX));
        }

        @Test void wildcardNoChange() {
            // $.a[*] has no dot chains — no lax unwrapping needed
            String result = JsonpathToJq.convert("$.a[*]", JsonpathToJq.Mode.LAX);
            assertFalse(result.contains("if ("), "Wildcard should not trigger lax: " + result);
        }

        @Test void laxIsDefault() {
            // convert() without Mode should default to LAX
            String laxExplicit = JsonpathToJq.convert("$.a.b.c", JsonpathToJq.Mode.LAX);
            String defaultMode = JsonpathToJq.convert("$.a.b.c");
            assertEquals(laxExplicit, defaultMode);
        }
    }

    // ========================================================================
    //  Compile and validate
    // ========================================================================

    @Nested
    class CompileValidation {
        @Test void compileSimplePath() {
            JqProgram program = JsonpathToJq.compile("$.a.b", JsonpathToJq.Mode.STRICT);
            assertNotNull(program);
        }

        @Test void compileLaxPath() {
            JqProgram program = JsonpathToJq.compile("$.a.b.c", JsonpathToJq.Mode.LAX);
            assertNotNull(program);
        }

        @Test void compileWithFilter() {
            JqProgram program = JsonpathToJq.compile("$.results[*] ?(@.name == \"x\")", JsonpathToJq.Mode.STRICT);
            assertNotNull(program);
        }

        @Test void compileArrayMode() {
            // convertArray output should also compile
            String jq = JsonpathToJq.convertArray("$.a[*].b", JsonpathToJq.Mode.STRICT);
            JqProgram program = JqProgram.compile(jq);
            assertNotNull(program);
        }

        @Test void compileDefaultMode() {
            JqProgram program = JsonpathToJq.compile("$.data.results");
            assertNotNull(program);
        }
    }

    // ========================================================================
    //  Round-trip tests: convert → compile → apply to JSON → verify result
    // ========================================================================

    @Nested
    class RoundTrip {
        @Test void strictSimplePath() {
            assertRoundTrip(
                    "{\"a\":{\"b\":{\"c\":\"value\"}}}",
                    "$.a.b.c", JsonpathToJq.Mode.STRICT,
                    "\"value\"");
        }

        @Test void strictArrayIteration() {
            assertRoundTrip(
                    "{\"items\":[{\"name\":\"x\"},{\"name\":\"y\"}]}",
                    "$.items[*].name", JsonpathToJq.Mode.STRICT,
                    "\"x\""); // first match
        }

        @Test void strictFilter() {
            assertRoundTrip(
                    "{\"items\":[{\"name\":\"x\",\"v\":1},{\"name\":\"y\",\"v\":2}]}",
                    "$.items[*] ?(@.name == \"y\").v", JsonpathToJq.Mode.STRICT,
                    "2");
        }

        @Test void strictLength() {
            assertRoundTrip(
                    "{\"data\":[1,2,3,4,5]}",
                    "$.data.size()", JsonpathToJq.Mode.STRICT,
                    "5");
        }

        @Test void laxArrayUnwrap() {
            // b is an array — lax mode auto-unwraps it
            assertRoundTrip(
                    "{\"a\":{\"b\":[{\"c\":\"one\"}]}}",
                    "$.a.b.c", JsonpathToJq.Mode.LAX,
                    "\"one\"");
        }

        @Test void laxNoArray() {
            // b is not an array — lax mode passes through
            assertRoundTrip(
                    "{\"a\":{\"b\":{\"c\":\"value\"}}}",
                    "$.a.b.c", JsonpathToJq.Mode.LAX,
                    "\"value\"");
        }

        @Test void arrayCollectionStrict() {
            String jq = JsonpathToJq.convertArray("$.items[*].name", JsonpathToJq.Mode.STRICT);
            JqProgram program = JqProgram.compile(jq);
            JqValue input = JqValues.parse("{\"items\":[{\"name\":\"x\"},{\"name\":\"y\"}]}");
            JqValue result = program.apply(input);
            assertEquals("[\"x\",\"y\"]", result.toJsonString());
        }

        @Test void arrayCollectionLax() {
            // b is an array — lax mode unwraps, collecting all c values
            String jq = JsonpathToJq.convertArray("$.a.b.c", JsonpathToJq.Mode.LAX);
            JqProgram program = JqProgram.compile(jq);
            JqValue input = JqValues.parse("{\"a\":{\"b\":[{\"c\":1},{\"c\":2}]}}");
            JqValue result = program.apply(input);
            assertEquals("[1,2]", result.toJsonString());
        }

        @Test void filterWithLogicalOps() {
            String jq = JsonpathToJq.convertArray("$.data[*] ?(@.x > 1 && @.x < 4)", JsonpathToJq.Mode.STRICT);
            JqProgram program = JqProgram.compile(jq);
            JqValue input = JqValues.parse("{\"data\":[{\"x\":1},{\"x\":2},{\"x\":3},{\"x\":4}]}");
            JqValue result = program.apply(input);
            assertEquals("[{\"x\":2},{\"x\":3}]", result.toJsonString());
        }

        @Test void nestedObjectPath() {
            assertRoundTrip(
                    "{\"config\":{\"network\":{\"host\":\"server01\"}}}",
                    "$.config.network.host", JsonpathToJq.Mode.STRICT,
                    "\"server01\"");
        }

        @Test void identity() {
            JqProgram program = JsonpathToJq.compile("$", JsonpathToJq.Mode.STRICT);
            JqValue input = JqValues.parse("{\"a\":1}");
            assertEquals(input, program.apply(input));
        }
    }

    // ========================================================================
    //  Edge cases
    // ========================================================================

    @Nested
    class EdgeCases {
        @Test void trailingWhitespace() {
            assertEquals(".a", JsonpathToJq.convert("$.a  ", JsonpathToJq.Mode.STRICT));
        }

        @Test void leadingWhitespace() {
            assertEquals(".a", JsonpathToJq.convert("  $.a", JsonpathToJq.Mode.STRICT));
        }

        @Test void dollarOnly() {
            assertEquals(".", JsonpathToJq.convert("$", JsonpathToJq.Mode.STRICT));
        }

        @Test void numericFieldName() {
            assertStrict("$.results.0.value", ".results.0.value");
        }

        @Test void underscoreFieldName() {
            assertStrict("$.my_field.sub_field", ".my_field.sub_field");
        }

        @Test void hyphenatedFieldName() {
            // Hyphenated field names need quoting in jq, but jsonpath handles them bare
            // The converter does direct string replacement, so this passes through as-is
            assertStrict("$.a.b-c", ".a.b-c");
        }

        @Test void multipleFilters() {
            String result = JsonpathToJq.convert(
                    "$.a[*] ?(@.x > 1) ?(@.y < 5)", JsonpathToJq.Mode.STRICT);
            // Two filters should produce two select() calls
            int selectCount = result.split("select\\(").length - 1;
            assertTrue(selectCount >= 2, "Should have 2+ select() calls: " + result);
        }

        @Test void filterNotEquality() {
            String result = JsonpathToJq.convert("$.a[*] ?(@.status != \"deleted\")", JsonpathToJq.Mode.STRICT);
            assertTrue(result.contains(".status != \"deleted\""), result);
        }
    }

    // ========================================================================
    //  Internal helpers
    // ========================================================================

    @Nested
    class Helpers {
        @Test void replaceOutsideQuotesBasic() {
            assertEquals("[]?", JsonpathToJq.replaceOutsideQuotes(".*", ".*", "[]?"));
        }

        @Test void replaceOutsideQuotesPreservesQuoted() {
            assertEquals(".\"a.b\"", JsonpathToJq.replaceOutsideQuotes(".\"a.b\"", ".*", "[]?"));
        }

        @Test void findKeyChainsSimple() {
            List<JsonpathToJq.Range> chains = JsonpathToJq.findKeyChains(".a.b.c");
            assertEquals(1, chains.size());
            assertEquals(0, chains.get(0).start());
            assertEquals(6, chains.get(0).end());
        }

        @Test void findKeyChainsNoChain() {
            List<JsonpathToJq.Range> chains = JsonpathToJq.findKeyChains(".a");
            assertTrue(chains.isEmpty(), "Single segment is not a chain");
        }

        @Test void findKeyChainsMultiple() {
            List<JsonpathToJq.Range> chains = JsonpathToJq.findKeyChains(".a.b | .c.d");
            assertEquals(2, chains.size());
        }

        @Test void splitNotInQuotesBasic() {
            List<String> parts = JsonpathToJq.splitNotInQuotes(".a.b.c", ".");
            assertEquals(List.of("a", "b", "c"), parts);
        }

        @Test void splitNotInQuotesPreservesQuoted() {
            List<String> parts = JsonpathToJq.splitNotInQuotes(".\"a.b\".c", ".");
            assertEquals(2, parts.size());
            assertTrue(parts.get(0).contains("a.b"), "Should preserve quoted content: " + parts);
        }

        @Test void convertFilterBodyAtField() {
            assertEquals(".name == \"x\"", JsonpathToJq.convertFilterBody("@.name == \"x\""));
        }

        @Test void convertFilterBodyBareAt() {
            assertEquals(". > 5", JsonpathToJq.convertFilterBody("@ > 5"));
        }

        @Test void convertFilterBodyLogicalOps() {
            String result = JsonpathToJq.convertFilterBody("@.a > 1 && @.b < 5");
            assertTrue(result.contains("and"), result);
            assertFalse(result.contains("&&"), result);
        }

        @Test void convertFilterBodyQuotedField() {
            String result = JsonpathToJq.convertFilterBody("@.\"special-key\" == \"x\"");
            assertTrue(result.contains("[\"special-key\"]"), "Should convert to bracket notation: " + result);
        }
    }

    // ========================================================================
    //  Test helpers
    // ========================================================================

    private static void assertStrict(String jsonpath, String expectedJq) {
        assertEquals(expectedJq, JsonpathToJq.convert(jsonpath, JsonpathToJq.Mode.STRICT));
    }

    private static void assertRoundTrip(String inputJson, String jsonpath,
                                         JsonpathToJq.Mode mode, String expectedOutput) {
        JqProgram program = JsonpathToJq.compile(jsonpath, mode);
        JqValue input = JqValues.parse(inputJson.getBytes(StandardCharsets.UTF_8));
        JqValue result = program.apply(input);
        assertEquals(expectedOutput, result.toJsonString(),
                "jsonpath: " + jsonpath + " (mode: " + mode + "), jq: " + JsonpathToJq.convert(jsonpath, mode));
    }
}

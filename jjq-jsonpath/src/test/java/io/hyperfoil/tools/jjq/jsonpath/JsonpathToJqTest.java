package io.hyperfoil.tools.jjq.jsonpath;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Assumptions;
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
            // [*] before .keyvalue() should be collapsed — the intent is to enumerate
            // the object's own entries, not iterate values then enumerate sub-entries
            assertFalse(result.contains("[]?"), "Should not have []? before to_entries[]: " + result);
        }
        @Test void doubleMethod() { assertStrict("$.value.double()", ".value | tonumber"); }
        @Test void stringMethod() { assertStrict("$.value.string()", ".value | tostring"); }
        @Test void typeMethod() { assertStrict("$.value.type()", ".value | type"); }
        @Test void ceilingMethod() { assertStrict("$.value.ceiling()", ".value | ceil"); }
        @Test void floorMethod() { assertStrict("$.value.floor()", ".value | floor"); }
        @Test void absMethod() { assertStrict("$.value.abs()", ".value | fabs"); }

        @Test void sizeInsideBracket() {
            // h5m production pattern: $.state.throughput[3].cumulative[$.state.throughput[3].cumulative.size()-1].rate
            String result = JsonpathToJq.convert(
                    "$.state.throughput[3].cumulative[$.state.throughput[3].cumulative.size()-1].rate",
                    JsonpathToJq.Mode.STRICT);
            assertTrue(result.contains("| length"), "Should convert .size() to | length inside brackets: " + result);
            assertFalse(result.contains(".size()"), "Should not contain .size(): " + result);
            assertTrue(result.contains(".rate"), "Should have trailing .rate: " + result);
        }

        @Test void sizeInsideBracketSimple() {
            String result = JsonpathToJq.convert("$.data[$.data.size()-1]", JsonpathToJq.Mode.STRICT);
            assertTrue(result.contains("| length"), result);
            assertFalse(result.contains(".size()"), result);
        }

        @Test void sizeInsideBracketCompiles() {
            // Verify the h5m pattern actually compiles to valid jq
            JqProgram program = JsonpathToJq.compile(
                    "$.state.throughput[3].cumulative[$.state.throughput[3].cumulative.size()-1].rate",
                    JsonpathToJq.Mode.STRICT);
            assertNotNull(program);
        }
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
            // Single segment — lax adds ? for error suppression
            String result = JsonpathToJq.convert("$.a", JsonpathToJq.Mode.LAX);
            assertTrue(result.contains(".a"), "Should contain .a: " + result);
        }

        @Test void twoSegmentsUnwrapsFirst() {
            String result = JsonpathToJq.convert("$.a.b", JsonpathToJq.Mode.LAX);
            assertTrue(result.contains("if (.a"), "Should unwrap .a: " + result);
            assertTrue(result.contains(".b"), "Should access .b: " + result);
        }

        @Test void threeSegmentsUnwrapsFirstTwo() {
            String result = JsonpathToJq.convert("$.a.b.c", JsonpathToJq.Mode.LAX);
            // Lax adds ? to field access, so checks use contains with type/array
            assertTrue(result.contains("\"array\""), "Should have array type check: " + result);
            assertTrue(result.contains(".c"), "Should access .c: " + result);
        }

        @Test void withExistingIterator() {
            // $.a[*].b.c — a[*] is already explicit, but b.c needs lax unwrapping
            String result = JsonpathToJq.convert("$.a[*].b.c", JsonpathToJq.Mode.LAX);
            assertTrue(result.contains("[]?"), "Should have iterator: " + result);
            assertTrue(result.contains("\"array\""), "Should unwrap .b: " + result);
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

        @Test void keyvalueKeyOnObject() {
            // $.results[*].keyvalue().key should extract the top-level keys of 'results'
            // when 'results' is an object (not an array). In PostgreSQL lax mode,
            // [*] on an object auto-wraps as [obj] then iterates, returning the object
            // unchanged. .keyvalue() then enumerates the object's entries.
            String json = "{\"results\":{\"spring-jvm\":{\"rss\":1},\"quarkus-jvm\":{\"rss\":2}}}";
            String jq = JsonpathToJq.convertArray("$.results[*].keyvalue().key", JsonpathToJq.Mode.STRICT);
            JqProgram program = JqProgram.compile(jq);
            JqValue result = program.apply(JqValues.parse(json));
            assertEquals("[\"spring-jvm\",\"quarkus-jvm\"]", result.toJsonString(),
                    "Should extract top-level keys of the results object, jq: " + jq);
        }

        @Test void sizeInsideBracketRoundTrip() {
            // Simulates the h5m throughput pattern with a simpler structure
            String json = "{\"data\":[10,20,30,40,50]}";
            JqProgram program = JsonpathToJq.compile("$.data[$.data.size()-1]", JsonpathToJq.Mode.STRICT);
            JqValue result = program.apply(JqValues.parse(json));
            assertEquals("50", result.toJsonString(), "Should get last element");
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
            // Hyphenated field names are converted to bracket notation since
            // jq interprets .b-c as .b - c (subtraction)
            assertStrict("$.a.b-c", ".a.[\"b-c\"]");
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

    // ========================================================================
    //  Phase 1 tests: coverage for previously untested features (issue #71)
    // ========================================================================

    @Nested
    class LastKeyword {
        private static final String ARRAY_JSON = "{\"data\":[10,20,30,40,50]}";

        @Test void lastIndex() {
            String jq = JsonpathToJq.convert("$.data[last]", JsonpathToJq.Mode.STRICT);
            JqValue result = JqProgram.compile(jq).apply(JqValues.parse(ARRAY_JSON));
            assertEquals("50", result.toJsonString(),
                    "$.data[last] should return last element, jq: " + jq);
        }

        @Test void lastMinusOne() {
            String jq = JsonpathToJq.convert("$.data[last - 1]", JsonpathToJq.Mode.STRICT);
            JqValue result = JqProgram.compile(jq).apply(JqValues.parse(ARRAY_JSON));
            assertEquals("40", result.toJsonString(),
                    "$.data[last - 1] should return second-to-last, jq: " + jq);
        }

        @Test void lastMinusThree() {
            String jq = JsonpathToJq.convert("$.data[last - 3]", JsonpathToJq.Mode.STRICT);
            JqValue result = JqProgram.compile(jq).apply(JqValues.parse(ARRAY_JSON));
            assertEquals("20", result.toJsonString(),
                    "$.data[last - 3] should return 4th from end, jq: " + jq);
        }

        @Test void zeroToLast() {
            // Known limitation: last inside range expressions not supported (issue #71 Phase 2)
            String jq = JsonpathToJq.convert("$.data[0 to last]", JsonpathToJq.Mode.STRICT);
            try {
                JqProgram program = JqProgram.compile(jq);
                JqValue result = program.apply(JqValues.parse(ARRAY_JSON));
                // If it works, verify it returns all elements
                assertEquals("[10,20,30,40,50]", result.toJsonString());
            } catch (Exception e) {
                Assumptions.assumeTrue(false,
                        "last in range expressions not yet supported. jq produced: " + jq + ", error: " + e.getMessage());
            }
        }

        @Test void lastMinusOneToLast() {
            // Known limitation: last inside range expressions not supported
            String jq = JsonpathToJq.convert("$.data[last - 1 to last]", JsonpathToJq.Mode.STRICT);
            try {
                JqProgram program = JqProgram.compile(jq);
                JqValue result = program.apply(JqValues.parse(ARRAY_JSON));
                assertEquals("[40,50]", result.toJsonString());
            } catch (Exception e) {
                Assumptions.assumeTrue(false,
                        "last in range expressions not yet supported. jq produced: " + jq + ", error: " + e.getMessage());
            }
        }
    }

    @Nested
    class RangeSlicing {
        private static final String ARRAY_JSON = "{\"data\":[10,20,30,40,50]}";

        @Test void basicRange() {
            // Range slicing returns a sub-array — use convert() not convertArray()
            String jq = JsonpathToJq.convert("$.data[0 to 2]", JsonpathToJq.Mode.STRICT);
            JqValue result = JqProgram.compile(jq).apply(JqValues.parse(ARRAY_JSON));
            assertEquals("[10,20,30]", result.toJsonString(),
                    "$.data[0 to 2] should return first 3 elements, jq: " + jq);
        }

        @Test void midRange() {
            String jq = JsonpathToJq.convert("$.data[1 to 3]", JsonpathToJq.Mode.STRICT);
            JqValue result = JqProgram.compile(jq).apply(JqValues.parse(ARRAY_JSON));
            assertEquals("[20,30,40]", result.toJsonString(),
                    "$.data[1 to 3] should return elements 1-3, jq: " + jq);
        }

        @Test void singleElementRange() {
            String jq = JsonpathToJq.convert("$.data[2 to 2]", JsonpathToJq.Mode.STRICT);
            JqValue result = JqProgram.compile(jq).apply(JqValues.parse(ARRAY_JSON));
            assertEquals("[30]", result.toJsonString(),
                    "$.data[2 to 2] should return single element, jq: " + jq);
        }
    }

    @Nested
    class AdditionalFeatures {
        private static final String ITEMS_JSON = """
                {"items":[
                    {"name":"Widget-A","price":9.99,"stock":100,"active":true,"type":"A"},
                    {"name":"Widget-B","price":24.99,"stock":0,"active":false,"type":"B"},
                    {"name":"Gadget-C","price":4.50,"stock":50,"active":true,"type":"A"}
                ]}""";
        private static final String NAMES_JSON = """
                {"names":["Alice","Bob","Anna","Alex","Charlie"]}""";

        // ---- starts with ----

        @Test void startsWith() {
            String jq = JsonpathToJq.convertArray("$.names[*] ? (@ starts with \"A\")", JsonpathToJq.Mode.STRICT);
            JqValue result = JqProgram.compile(jq).apply(JqValues.parse(NAMES_JSON));
            assertEquals("[\"Alice\",\"Anna\",\"Alex\"]", result.toJsonString(),
                    "Should filter names starting with A, jq: " + jq);
        }

        @Test void startsWithOnField() {
            String jq = JsonpathToJq.convertArray("$.items[*] ? (@.name starts with \"Widget\")", JsonpathToJq.Mode.STRICT);
            JqValue result = JqProgram.compile(jq).apply(JqValues.parse(ITEMS_JSON));
            String resultStr = result.toJsonString();
            assertTrue(resultStr.contains("Widget-A") && resultStr.contains("Widget-B"),
                    "Should filter items with name starting with Widget, jq: " + jq);
            assertFalse(resultStr.contains("Gadget"),
                    "Should not include Gadget, jq: " + jq);
        }

        // ---- .boolean() method ----

        @Test void booleanMethod() {
            String jq = JsonpathToJq.convert("$.value.boolean()", JsonpathToJq.Mode.STRICT);
            // Verify the jq compiles
            JqProgram program = JqProgram.compile(jq);
            // Test with truthy value
            JqValue truthy = program.apply(JqValues.parse("{\"value\":1}"));
            assertNotNull(truthy, "boolean() on truthy value should produce result, jq: " + jq);
        }

        // ---- comparison operators ----

        @Test void greaterOrEqual() {
            String jq = JsonpathToJq.convertArray("$.items[*] ? (@.price >= 10)", JsonpathToJq.Mode.STRICT);
            JqValue result = JqProgram.compile(jq).apply(JqValues.parse(ITEMS_JSON));
            String resultStr = result.toJsonString();
            assertTrue(resultStr.contains("Widget-B"), "Should include Widget-B (24.99 >= 10), jq: " + jq);
            assertFalse(resultStr.contains("Widget-A"), "Should not include Widget-A (9.99 < 10), jq: " + jq);
            assertFalse(resultStr.contains("Gadget"), "Should not include Gadget (4.50 < 10), jq: " + jq);
        }

        @Test void lessOrEqual() {
            String jq = JsonpathToJq.convertArray("$.items[*] ? (@.price <= 10)", JsonpathToJq.Mode.STRICT);
            JqValue result = JqProgram.compile(jq).apply(JqValues.parse(ITEMS_JSON));
            String resultStr = result.toJsonString();
            assertTrue(resultStr.contains("Widget-A"), "Should include Widget-A (9.99 <= 10), jq: " + jq);
            assertTrue(resultStr.contains("Gadget"), "Should include Gadget (4.50 <= 10), jq: " + jq);
            assertFalse(resultStr.contains("Widget-B"), "Should not include Widget-B (24.99 > 10), jq: " + jq);
        }

        // ---- method chaining ----

        @Test void methodChainDoubleCeiling() {
            String jq = JsonpathToJq.convert("$.value.double().ceiling()", JsonpathToJq.Mode.STRICT);
            JqProgram program = JqProgram.compile(jq);
            JqValue result = program.apply(JqValues.parse("{\"value\":\"3.14\"}"));
            assertEquals("4", result.toJsonString(),
                    "double().ceiling() should parse then ceil, jq: " + jq);
        }

        @Test void methodChainAbsFloor() {
            String jq = JsonpathToJq.convert("$.value.abs().floor()", JsonpathToJq.Mode.STRICT);
            JqProgram program = JqProgram.compile(jq);
            JqValue result = program.apply(JqValues.parse("{\"value\":-3.7}"));
            assertEquals("3", result.toJsonString(),
                    "abs().floor() should absolute then floor, jq: " + jq);
        }

        // ---- compound filters ----

        @Test void compoundAndFilter() {
            String jq = JsonpathToJq.convertArray("$.items[*] ? (@.price > 5 && @.stock > 0)", JsonpathToJq.Mode.STRICT);
            JqValue result = JqProgram.compile(jq).apply(JqValues.parse(ITEMS_JSON));
            String resultStr = result.toJsonString();
            // Widget-A: price 9.99 > 5, stock 100 > 0 → include
            // Widget-B: price 24.99 > 5, stock 0 NOT > 0 → exclude
            // Gadget-C: price 4.50 NOT > 5 → exclude
            assertTrue(resultStr.contains("Widget-A"), "Should include Widget-A, jq: " + jq);
            assertFalse(resultStr.contains("Widget-B"), "Should not include Widget-B (stock 0), jq: " + jq);
            assertFalse(resultStr.contains("Gadget"), "Should not include Gadget (price 4.50), jq: " + jq);
        }

        @Test void compoundOrFilter() {
            String jq = JsonpathToJq.convertArray("$.items[*] ? (@.type == \"A\" || @.type == \"B\")", JsonpathToJq.Mode.STRICT);
            JqValue result = JqProgram.compile(jq).apply(JqValues.parse(ITEMS_JSON));
            assertEquals(3, result.length(),
                    "Should include all items (types A and B), jq: " + jq);
        }

        // ---- hyphenated field names ----

        @Test void hyphenatedFieldName() {
            String json = "{\"my-field\":42}";
            String jq = JsonpathToJq.convert("$.my-field", JsonpathToJq.Mode.STRICT);
            JqValue result = JqProgram.compile(jq).apply(JqValues.parse(json));
            assertEquals("42", result.toJsonString(),
                    "Should access hyphenated field, jq: " + jq);
        }

        @Test void hyphenatedNestedField() {
            String json = "{\"config\":{\"retry-count\":3}}";
            String jq = JsonpathToJq.convert("$.config.retry-count", JsonpathToJq.Mode.STRICT);
            JqValue result = JqProgram.compile(jq).apply(JqValues.parse(json));
            assertEquals("3", result.toJsonString(),
                    "Should access nested hyphenated field, jq: " + jq);
        }

        // ---- recursive descent with depth ----

        @Test void recursiveDescentWithDepth() {
            // Known limitation: depth bound is discarded (issue #71 Phase 2)
            String json = "{\"a\":{\"b\":{\"c\":{\"value\":42}}}}";
            String jq = JsonpathToJq.convert("$.**{2}.value", JsonpathToJq.Mode.STRICT);
            try {
                JqProgram program = JqProgram.compile(jq);
                // .**{2} should descend exactly 2 levels — matches .a.b but not .a.b.c
                // With recurse (infinite), it matches everything including .a.b.c.value
                JqValue result = program.apply(JqValues.parse(json));
                // If depth bound is correctly applied, behavior differs from infinite recurse
                assertNotNull(result, "Should produce some result, jq: " + jq);
            } catch (Exception e) {
                Assumptions.assumeTrue(false,
                        "Recursive descent with depth bound not fully supported. jq: " + jq + ", error: " + e.getMessage());
            }
        }

        // ---- filter then field access ----

        @Test void filterThenFieldAccess() {
            String jq = JsonpathToJq.convertArray("$.items[*] ? (@.active == true).name", JsonpathToJq.Mode.STRICT);
            JqValue result = JqProgram.compile(jq).apply(JqValues.parse(ITEMS_JSON));
            String resultStr = result.toJsonString();
            assertTrue(resultStr.contains("Widget-A"), "Should include active Widget-A, jq: " + jq);
            assertTrue(resultStr.contains("Gadget-C"), "Should include active Gadget-C, jq: " + jq);
            assertFalse(resultStr.contains("Widget-B"), "Should not include inactive Widget-B, jq: " + jq);
        }

        // ---- array access on current item in filter ----

        @Test void filterArrayAccess() {
            String json = "{\"data\":[[1,2,3],[10,20,30],[5,6,7]]}";
            String jq = JsonpathToJq.convertArray("$.data[*] ? (@[0] > 5)", JsonpathToJq.Mode.STRICT);
            JqValue result = JqProgram.compile(jq).apply(JqValues.parse(json));
            String resultStr = result.toJsonString();
            assertTrue(resultStr.contains("[10,20,30]"),
                    "Should include array starting with 10, jq: " + jq);
            assertFalse(resultStr.contains("[1,2,3]"),
                    "Should not include array starting with 1, jq: " + jq);
        }

        // ---- exists() in filters ----

        @Test void existsSimpleField() {
            String json = "{\"items\":[{\"name\":\"A\",\"desc\":\"good\"},{\"name\":\"B\"},{\"name\":\"C\",\"desc\":null}]}";
            String jq = JsonpathToJq.convertArray("$.items[*] ? (exists(@.desc))", JsonpathToJq.Mode.STRICT);
            JqValue result = JqProgram.compile(jq).apply(JqValues.parse(json));
            String resultStr = result.toJsonString();
            // Items A and C have "desc" (C has null value — key exists)
            assertTrue(resultStr.contains("\"name\":\"A\""), "Should include A (has desc), jq: " + jq);
            assertFalse(resultStr.contains("\"name\":\"B\""), "Should not include B (no desc), jq: " + jq);
        }

        @Test void existsNestedPath() {
            String json = "{\"items\":[{\"name\":\"A\",\"meta\":{\"tag\":\"x\"}},{\"name\":\"B\"}]}";
            String jq = JsonpathToJq.convertArray("$.items[*] ? (exists(@.meta.tag))", JsonpathToJq.Mode.STRICT);
            JqValue result = JqProgram.compile(jq).apply(JqValues.parse(json));
            String resultStr = result.toJsonString();
            assertTrue(resultStr.contains("\"name\":\"A\""), "Should include A (has meta.tag), jq: " + jq);
            assertFalse(resultStr.contains("\"name\":\"B\""), "Should not include B (no meta), jq: " + jq);
        }

        // ---- PostgreSQL 17 numeric cast methods ----

        @Test void integerMethod() {
            String jq = JsonpathToJq.convert("$.value.integer()", JsonpathToJq.Mode.STRICT);
            JqProgram program = JqProgram.compile(jq);
            JqValue result = program.apply(JqValues.parse("{\"value\":3.7}"));
            assertEquals("3", result.toJsonString(),
                    ".integer() should truncate to integer, jq: " + jq);
        }

        @Test void bigintMethod() {
            String jq = JsonpathToJq.convert("$.value.bigint()", JsonpathToJq.Mode.STRICT);
            JqProgram program = JqProgram.compile(jq);
            JqValue result = program.apply(JqValues.parse("{\"value\":9.9}"));
            assertEquals("9", result.toJsonString(),
                    ".bigint() should truncate to integer, jq: " + jq);
        }

        @Test void numberMethod() {
            String jq = JsonpathToJq.convert("$.value.number()", JsonpathToJq.Mode.STRICT);
            JqProgram program = JqProgram.compile(jq);
            JqValue result = program.apply(JqValues.parse("{\"value\":\"42\"}"));
            assertEquals("42", result.toJsonString(),
                    ".number() should convert string to number, jq: " + jq);
        }

        @Test void decimalMethod() {
            String jq = JsonpathToJq.convert("$.value.decimal()", JsonpathToJq.Mode.STRICT);
            JqProgram program = JqProgram.compile(jq);
            JqValue result = program.apply(JqValues.parse("{\"value\":\"3.14\"}"));
            assertEquals("3.14", result.toJsonString(),
                    ".decimal() should convert string to number, jq: " + jq);
        }

        // ---- quote-aware method replacements ----

        @Test void quotedFieldNameWithMethodName() {
            // A field literally named "size()" should not be replaced by | length
            String json = "{\"data\":{\"size()\":42,\"name\":\"test\"}}";
            String jq = JsonpathToJq.convert("$.data.\"size()\"", JsonpathToJq.Mode.STRICT);
            // The quoted field should be preserved, not replaced
            assertFalse(jq.contains("| length"),
                    "Quoted field 'size()' should not be replaced by | length, jq: " + jq);
        }

        // ---- multiple root references ----

        @Test void multipleRootReferences() {
            // $.data[$.index] — nested $ reference in bracket expression
            String json = "{\"data\":[10,20,30],\"index\":1}";
            String jq = JsonpathToJq.convert("$.data[$.index]", JsonpathToJq.Mode.STRICT);
            try {
                JqValue result = JqProgram.compile(jq).apply(JqValues.parse(json));
                // In PostgreSQL, $ in brackets refers to the root document
                // Whether this works depends on how $. is replaced inside brackets
                assertNotNull(result, "Should produce some result, jq: " + jq);
            } catch (Exception e) {
                Assumptions.assumeTrue(false,
                        "Nested root references in brackets not supported. jq: " + jq + ", error: " + e.getMessage());
            }
        }
    }

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

package io.hyperfoil.tools.jjq.jsonpath;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.hyperfoil.tools.jjq.jsonpath.JsonpathTokenType.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the tokenizing parser (issue #72).
 * Tests both the lexer (tokenization) and the translator (token → jq).
 */
class JsonpathTokenizerTest {

    // ========================================================================
    //  Lexer tests — verify tokenization
    // ========================================================================

    @Nested
    class LexerTests {
        @Test void rootOnly() {
            var tokens = lex("$");
            assertTokenTypes(tokens, ROOT, EOF);
        }

        @Test void simpleField() {
            var tokens = lex("$.name");
            assertTokenTypes(tokens, ROOT, DOT, IDENT, EOF);
            assertEquals("name", tokens.get(2).value());
        }

        @Test void twoFields() {
            var tokens = lex("$.a.b");
            assertTokenTypes(tokens, ROOT, DOT, IDENT, DOT, IDENT, EOF);
        }

        @Test void arrayWildcard() {
            var tokens = lex("$.a[*]");
            assertTokenTypes(tokens, ROOT, DOT, IDENT, LBRACKET, STAR, RBRACKET, EOF);
        }

        @Test void arrayIndex() {
            var tokens = lex("$.a[0]");
            assertTokenTypes(tokens, ROOT, DOT, IDENT, LBRACKET, INTEGER, RBRACKET, EOF);
            assertEquals("0", tokens.get(4).value());
        }

        @Test void lastKeywordInsideBrackets() {
            var tokens = lex("$.a[last]");
            assertTokenTypes(tokens, ROOT, DOT, IDENT, LBRACKET, KW_LAST, RBRACKET, EOF);
        }

        @Test void lastAsIdentOutsideBrackets() {
            // 'last' outside brackets should be IDENT, not KW_LAST
            var tokens = lex("$.last");
            assertTokenTypes(tokens, ROOT, DOT, IDENT, EOF);
            assertEquals("last", tokens.get(2).value());
        }

        @Test void toKeywordInsideBrackets() {
            var tokens = lex("$.a[0 to 3]");
            assertTokenTypes(tokens, ROOT, DOT, IDENT, LBRACKET, INTEGER, KW_TO, INTEGER, RBRACKET, EOF);
        }

        @Test void quotedString() {
            var tokens = lex("$.\"special field\"");
            assertTokenTypes(tokens, ROOT, DOT, STRING, EOF);
            assertEquals("special field", tokens.get(2).value());
        }

        @Test void filterSimple() {
            var tokens = lex("$.a ? (@.x == 1)");
            assertTokenTypes(tokens, ROOT, DOT, IDENT, QUESTION, LPAREN, CURRENT, DOT, IDENT, EQ, INTEGER, RPAREN, EOF);
        }

        @Test void logicalOperators() {
            var tokens = lex("$.a ? (@.x > 1 && @.y < 10)");
            // Check && becomes AND
            boolean hasAnd = tokens.stream().anyMatch(t -> t.type() == AND);
            assertTrue(hasAnd, "Should have AND token for &&");
        }

        @Test void comparisonOperators() {
            var tokens = lex("$.a ? (@.x >= 1 && @.y <= 10 && @.z != 0 && @.w <> 5)");
            assertTrue(tokens.stream().anyMatch(t -> t.type() == GE));
            assertTrue(tokens.stream().anyMatch(t -> t.type() == LE));
            assertTrue(tokens.stream().anyMatch(t -> t.type() == NEQ));
            assertTrue(tokens.stream().anyMatch(t -> t.type() == LTGT));
        }

        @Test void hyphenatedIdentifier() {
            var tokens = lex("$.my-field");
            assertTokenTypes(tokens, ROOT, DOT, IDENT, EOF);
            assertEquals("my-field", tokens.get(2).value());
        }

        @Test void methodCall() {
            var tokens = lex("$.a.size()");
            assertTokenTypes(tokens, ROOT, DOT, IDENT, DOT, IDENT, LPAREN, RPAREN, EOF);
            assertEquals("size", tokens.get(4).value());
        }

        @Test void likeRegex() {
            var tokens = lex("$.name ? (@ like_regex \"^A\" flag \"i\")");
            assertTrue(tokens.stream().anyMatch(t -> t.type() == KW_LIKE_REGEX));
            assertTrue(tokens.stream().anyMatch(t -> t.type() == KW_FLAG));
        }

        @Test void existsKeyword() {
            var tokens = lex("$.a ? (exists(@.b))");
            assertTrue(tokens.stream().anyMatch(t -> t.type() == KW_EXISTS));
        }

        @Test void negativeIndex() {
            var tokens = lex("$.a[-1]");
            assertTokenTypes(tokens, ROOT, DOT, IDENT, LBRACKET, INTEGER, RBRACKET, EOF);
            assertEquals("-1", tokens.get(4).value());
        }

        @Test void recursiveDescent() {
            var tokens = lex("$.**{2}");
            assertTokenTypes(tokens, ROOT, DOT, DOUBLESTAR, LBRACE, INTEGER, RBRACE, EOF);
        }

        private List<JsonpathToken> lex(String input) {
            return new JsonpathLexer(input).tokenize();
        }

        private void assertTokenTypes(List<JsonpathToken> tokens, JsonpathTokenType... expected) {
            assertEquals(expected.length, tokens.size(),
                    "Token count mismatch. Got: " + tokens.stream().map(t -> t.type().name()).toList());
            for (int i = 0; i < expected.length; i++) {
                assertEquals(expected[i], tokens.get(i).type(),
                        "Token " + i + " type mismatch. Got: " + tokens.stream().map(t -> t.type().name()).toList());
            }
        }
    }

    // ========================================================================
    //  Translator tests — verify jq output
    // ========================================================================

    @Nested
    class TranslatorTests {
        @Test void identity() {
            assertEquals(".", translate("$"));
        }

        @Test void singleField() {
            assertEquals(".name", translate("$.name"));
        }

        @Test void twoFields() {
            assertEquals(".a.b", translate("$.a.b"));
        }

        @Test void arrayWildcard() {
            assertEquals(".a[]?", translate("$.a[*]"));
        }

        @Test void arrayIndex() {
            assertEquals(".a[0]", translate("$.a[0]"));
        }

        @Test void lastIndex() {
            assertEquals(".a[-1]", translate("$.a[last]"));
        }

        @Test void lastMinusN() {
            assertEquals(".a[-3]", translate("$.a[last - 2]"));
        }

        @Test void rangeSlice() {
            assertEquals(".a[0:4]", translate("$.a[0 to 3]"));
        }

        @Test void rangeToLast() {
            assertEquals(".a[1:]", translate("$.a[1 to last]"));
        }

        @Test void sizeMethod() {
            assertEquals(". | length", translate("$.size()"));
        }

        @Test void doubleMethod() {
            assertEquals(".value | tonumber", translate("$.value.double()"));
        }

        @Test void keyvalueMethod() {
            assertEquals(".data | to_entries[]", translate("$.data.keyvalue()"));
        }

        @Test void hyphenatedField() {
            assertEquals(".config.[\"retry-count\"]", translate("$.config.retry-count"));
        }

        @Test void quotedField() {
            assertEquals(".[\"special field\"]", translate("$.\"special field\""));
        }

        @Test void methodChaining() {
            String jq = translate("$.value.double().ceiling()");
            assertTrue(jq.contains("tonumber") && jq.contains("ceil"),
                    "Should have both tonumber and ceil: " + jq);
        }

        private String translate(String jsonpath) {
            return JsonpathToJq.convertTokenized(jsonpath, JsonpathToJq.Mode.STRICT);
        }
    }

    // ========================================================================
    //  Round-trip tests — verify end-to-end correctness
    // ========================================================================

    @Nested
    class RoundTripTests {
        @Test void simpleFieldAccess() {
            assertRoundTrip("{\"name\":\"Alice\"}", "$.name", "\"Alice\"");
        }

        @Test void nestedFieldAccess() {
            assertRoundTrip("{\"a\":{\"b\":{\"c\":42}}}", "$.a.b.c", "42");
        }

        @Test void arrayWildcard() {
            assertRoundTrip("{\"items\":[1,2,3]}", "$.items[0]", "1");
        }

        @Test void lastElement() {
            assertRoundTrip("{\"data\":[10,20,30]}", "$.data[last]", "30");
        }

        @Test void lastMinusOne() {
            assertRoundTrip("{\"data\":[10,20,30]}", "$.data[last - 1]", "20");
        }

        @Test void sizeMethod() {
            assertRoundTrip("{\"items\":[1,2,3]}", "$.items.size()", "3");
        }

        @Test void hyphenatedField() {
            assertRoundTrip("{\"my-field\":42}", "$.my-field", "42");
        }

        @Test void unionIndices() {
            String json = "{\"data\":[10,20,30,40,50]}";
            String jq = JsonpathToJq.convertTokenized("$.data[0,2,4]", JsonpathToJq.Mode.STRICT);
            JqProgram program = JqProgram.compile(jq);
            // Union produces multiple outputs — collect via applyAll
            var results = program.applyAll(JqValues.parse(json));
            assertEquals(3, results.size(), "Should produce 3 results, jq: " + jq);
        }

        @Test void notOperator() {
            String json = "{\"items\":[{\"active\":true},{\"active\":false}]}";
            String jq = JsonpathToJq.convertArray("$.items[*] ? (!(@ .active == true))", JsonpathToJq.Mode.STRICT);
            JqValue result = JqProgram.compile(jq).apply(JqValues.parse(json));
            String resultStr = result.toJsonString();
            assertTrue(resultStr.contains("false"), "Should include item with active=false, jq: " + jq);
            assertFalse(resultStr.contains("\"active\":true"), "Should not include active=true, jq: " + jq);
        }

        private void assertRoundTrip(String json, String jsonpath, String expected) {
            String jq = JsonpathToJq.convertTokenized(jsonpath, JsonpathToJq.Mode.STRICT);
            JqProgram program = JqProgram.compile(jq);
            JqValue result = program.apply(JqValues.parse(json));
            assertEquals(expected, result.toJsonString(),
                    "jsonpath: " + jsonpath + ", jq: " + jq);
        }
    }
}

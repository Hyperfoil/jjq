package io.hyperfoil.tools.jjq.jsonata;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.value.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the JSONata-to-jq transpiler.
 * Each test verifies both the translation (toJq) and the execution result.
 */
class JsonataCompilerTest {

    private static final String SAMPLE_JSON = """
            {
              "FirstName": "Fred",
              "Surname": "Smith",
              "Age": 28,
              "Address": {
                "Street": "Hursley Park",
                "City": "Winchester",
                "Postcode": "SO21 2JN"
              },
              "Phone": [
                {"type": "home", "number": "0203 544 1234"},
                {"type": "office", "number": "01962 001234"},
                {"type": "office", "number": "01962 001235"},
                {"type": "mobile", "number": "077 7700 1234"}
              ],
              "Other": {
                "Over 18 ?": true,
                "Misc": null
              }
            }
            """;

    private JqValue sampleData() {
        return JqValues.parse(SAMPLE_JSON);
    }

    private JqValue eval(String jsonata, JqValue input) {
        JqProgram program = JsonataCompiler.compile(jsonata);
        return program.apply(input);
    }

    private JqValue eval(String jsonata) {
        return eval(jsonata, sampleData());
    }

    // ========================================================================
    //  Translation tests (toJq)
    // ========================================================================

    @Nested
    class TranslationTests {

        @Test
        void simpleField() {
            assertEquals(".Surname", JsonataCompiler.toJq("Surname"));
        }

        @Test
        void nestedField() {
            // Multi-step paths use auto-mapping with singleton unwrap
            String jq = JsonataCompiler.toJq("Address.City");
            assertTrue(jq.contains(".Address"));
            assertTrue(jq.contains(".City"));
        }

        @Test
        void deepPath() {
            String jq = JsonataCompiler.toJq("Address.Street");
            assertTrue(jq.contains(".Address"));
            assertTrue(jq.contains(".Street"));
        }

        @Test
        void arrayIndex() {
            assertEquals(".Phone[0]", JsonataCompiler.toJq("Phone[0]"));
        }

        @Test
        void negativeIndex() {
            // -1 is parsed as unary minus on 1, producing -(1) — functionally correct
            assertEquals(".Phone[-(1)]", JsonataCompiler.toJq("Phone[-1]"));
        }

        @Test
        void indexedFieldAccess() {
            // Complex paths use auto-mapping with singleton unwrap
            String jq = JsonataCompiler.toJq("Phone[0].number");
            assertTrue(jq.contains(".Phone"));
            assertTrue(jq.contains(".number"));
        }

        @Test
        void rootReference() {
            assertEquals(".", JsonataCompiler.toJq("$"));
        }

        @Test
        void emptyExpression() {
            assertEquals(".", JsonataCompiler.toJq(""));
        }

        @Test
        void backtickField() {
            String jq = JsonataCompiler.toJq("Other.`Over 18 ?`");
            assertTrue(jq.contains(".Other"));
            assertTrue(jq.contains("\"Over 18 ?\""));
        }

        @Test
        void stringConcat() {
            String jq = JsonataCompiler.toJq("FirstName & ' ' & Surname");
            assertTrue(jq.contains("tostring"));
            assertTrue(jq.contains("+"));
        }

        @Test
        void sumFunction() {
            String jq = JsonataCompiler.toJq("$sum(prices)");
            assertTrue(jq.contains("add"));
        }

        @Test
        void predicate() {
            String jq = JsonataCompiler.toJq("Phone[type='mobile']");
            assertTrue(jq.contains("select"));
            assertTrue(jq.contains("\"mobile\""));
        }

        @Test
        void ternary() {
            String jq = JsonataCompiler.toJq("Age > 18 ? 'adult' : 'minor'");
            assertTrue(jq.contains("if"));
            assertTrue(jq.contains("then"));
            assertTrue(jq.contains("else"));
            assertTrue(jq.contains("end"));
        }

        @Test
        void objectConstruction() {
            String jq = JsonataCompiler.toJq("{\"name\": Surname, \"age\": Age}");
            assertTrue(jq.contains("\"name\""));
            assertTrue(jq.contains(".Surname"));
        }

        @Test
        void arrayConstruction() {
            String jq = JsonataCompiler.toJq("[1, 2, 3]");
            assertEquals("[1, 2, 3]", jq);
        }
    }

    // ========================================================================
    //  Execution tests — simple navigation
    // ========================================================================

    @Nested
    class NavigationTests {

        @Test
        void simpleField() {
            assertEquals(JqString.of("Smith"), eval("Surname"));
        }

        @Test
        void nestedField() {
            assertEquals(JqString.of("Winchester"), eval("Address.City"));
        }

        @Test
        void numberField() {
            assertEquals(JqNumber.of(28), eval("Age"));
        }

        @Test
        void arrayIndex() {
            JqValue result = eval("Phone[0]");
            assertTrue(result.isObject());
            assertEquals(JqString.of("home"), result.getField("type"));
        }

        @Test
        void negativeIndex() {
            JqValue result = eval("Phone[-1]");
            assertTrue(result.isObject());
            assertEquals(JqString.of("mobile"), result.getField("type"));
        }

        @Test
        void indexThenField() {
            assertEquals(JqString.of("0203 544 1234"), eval("Phone[0].number"));
        }

        @Test
        void backtickField() {
            assertEquals(JqBoolean.TRUE, eval("Other.`Over 18 ?`"));
        }

        @Test
        void nullField() {
            assertEquals(JqNull.NULL, eval("Other.Misc"));
        }

        @Test
        void missingField() {
            assertEquals(JqNull.NULL, eval("Other.Nothing"));
        }

        @Test
        void rootReference() {
            JqValue result = eval("$");
            assertTrue(result.isObject());
        }
    }

    // ========================================================================
    //  Execution tests — operators
    // ========================================================================

    @Nested
    class OperatorTests {

        @Test
        void addition() {
            assertEquals(JqNumber.of(5),
                    eval("2 + 3", JqNull.NULL));
        }

        @Test
        void subtraction() {
            assertEquals(JqNumber.of(18),
                    eval("Age - 10"));
        }

        @Test
        void multiplication() {
            assertEquals(JqNumber.of(6),
                    eval("2 * 3", JqNull.NULL));
        }

        @Test
        void division() {
            assertEquals(JqNumber.of(14),
                    eval("Age / 2"));
        }

        @Test
        void stringConcat() {
            assertEquals(JqString.of("Fred Smith"),
                    eval("FirstName & ' ' & Surname"));
        }

        @Test
        void comparison() {
            assertEquals(JqBoolean.TRUE, eval("Age > 18"));
            assertEquals(JqBoolean.FALSE, eval("Age < 18"));
            assertEquals(JqBoolean.TRUE, eval("Age = 28"));
            assertEquals(JqBoolean.TRUE, eval("Age != 30"));
            assertEquals(JqBoolean.TRUE, eval("Age >= 28"));
            assertEquals(JqBoolean.TRUE, eval("Age <= 28"));
        }

        @Test
        void booleanOperators() {
            assertEquals(JqBoolean.TRUE, eval("Age > 18 and Age < 65"));
            assertEquals(JqBoolean.TRUE, eval("Age < 18 or Age > 25"));
        }

        @Test
        void ternary() {
            assertEquals(JqString.of("adult"),
                    eval("Age > 18 ? 'adult' : 'minor'"));
        }

        @Test
        void negation() {
            assertEquals(JqNumber.of(-28), eval("-Age"));
        }
    }

    // ========================================================================
    //  Execution tests — predicates
    // ========================================================================

    @Nested
    class PredicateTests {

        @Test
        void filterByField() {
            JqValue result = eval("Phone[type='mobile']");
            assertTrue(result.isArray());
            assertEquals(1, result.length());
            assertEquals(JqString.of("077 7700 1234"),
                    result.getElement(0).getField("number"));
        }

        @Test
        void filterMultipleResults() {
            JqValue result = eval("Phone[type='office']");
            assertTrue(result.isArray());
            assertEquals(2, result.length());
        }
    }

    // ========================================================================
    //  Execution tests — functions
    // ========================================================================

    @Nested
    class FunctionTests {

        @Test
        void sum() {
            JqValue data = JqValues.parse("{\"prices\": [10, 20, 30]}");
            assertEquals(JqNumber.of(60), eval("$sum(prices)", data));
        }

        @Test
        void count() {
            JqValue data = JqValues.parse("{\"items\": [1, 2, 3, 4, 5]}");
            assertEquals(JqNumber.of(5), eval("$count(items)", data));
        }

        @Test
        void max() {
            JqValue data = JqValues.parse("{\"values\": [5, 1, 3, 7, 4]}");
            assertEquals(JqNumber.of(7), eval("$max(values)", data));
        }

        @Test
        void min() {
            JqValue data = JqValues.parse("{\"values\": [5, 1, 3, 7, 4]}");
            assertEquals(JqNumber.of(1), eval("$min(values)", data));
        }

        @Test
        void average() {
            JqValue data = JqValues.parse("{\"values\": [5, 1, 3, 7, 4]}");
            assertEquals(JqNumber.of(4), eval("$average(values)", data));
        }

        @Test
        void stringFunction() {
            assertEquals(JqString.of("28"), eval("$string(Age)"));
        }

        @Test
        void lengthFunction() {
            assertEquals(JqNumber.of(5), eval("$length(Surname)"));
        }

        @Test
        void uppercase() {
            assertEquals(JqString.of("SMITH"), eval("$uppercase(Surname)"));
        }

        @Test
        void lowercase() {
            assertEquals(JqString.of("smith"), eval("$lowercase(Surname)"));
        }

        @Test
        void typeFunction() {
            assertEquals(JqString.of("number"), eval("$type(Age)"));
            assertEquals(JqString.of("string"), eval("$type(Surname)"));
        }

        @Test
        void containsFunction() {
            JqValue data = JqValues.parse("{\"text\": \"hello world\"}");
            assertEquals(JqBoolean.TRUE, eval("$contains(text, 'world')", data));
            assertEquals(JqBoolean.FALSE, eval("$contains(text, 'xyz')", data));
        }

        @Test
        void splitFunction() {
            JqValue data = JqValues.parse("{\"text\": \"a,b,c\"}");
            JqValue result = eval("$split(text, ',')", data);
            assertTrue(result.isArray());
            assertEquals(3, result.length());
        }

        @Test
        void joinFunction() {
            JqValue data = JqValues.parse("{\"items\": [\"a\", \"b\", \"c\"]}");
            assertEquals(JqString.of("a-b-c"), eval("$join(items, '-')", data));
        }

        @Test
        void keysFunction() {
            JqValue result = eval("$keys(Address)");
            assertTrue(result.isArray());
            assertEquals(3, result.length());
        }

        @Test
        void sortFunction() {
            JqValue data = JqValues.parse("{\"nums\": [3, 1, 4, 1, 5]}");
            JqValue result = eval("$sort(nums)", data);
            assertEquals("[1,1,3,4,5]", result.toJsonString());
        }

        @Test
        void reverseFunction() {
            JqValue data = JqValues.parse("{\"items\": [1, 2, 3]}");
            assertEquals("[3,2,1]", eval("$reverse(items)", data).toJsonString());
        }

        @Test
        void distinctFunction() {
            JqValue data = JqValues.parse("{\"items\": [1, 2, 2, 3, 3, 3]}");
            assertEquals("[1,2,3]", eval("$distinct(items)", data).toJsonString());
        }

        @Test
        void appendFunction() {
            JqValue data = JqValues.parse("{\"a\": [1, 2], \"b\": [3, 4]}");
            assertEquals("[1,2,3,4]", eval("$append(a, b)", data).toJsonString());
        }

        @Test
        void existsFunction() {
            assertEquals(JqBoolean.TRUE, eval("$exists(Surname)"));
            assertEquals(JqBoolean.FALSE, eval("$exists(Missing)"));
        }

        @Test
        void floorCeilRound() {
            JqValue data = JqValues.parse("{\"x\": 3.7}");
            assertEquals(JqNumber.of(3), eval("$floor(x)", data));
            assertEquals(JqNumber.of(4), eval("$ceil(x)", data));
            assertEquals(JqNumber.of(4), eval("$round(x)", data));
        }
    }

    // ========================================================================
    //  Execution tests — construction
    // ========================================================================

    @Nested
    class ConstructionTests {

        @Test
        void objectConstruction() {
            JqValue result = eval("{\"name\": Surname, \"age\": Age}");
            assertTrue(result.isObject());
            assertEquals(JqString.of("Smith"), result.getField("name"));
            assertEquals(JqNumber.of(28), result.getField("age"));
        }

        @Test
        void arrayConstruction() {
            JqValue result = eval("[1, 2, 3]", JqNull.NULL);
            assertTrue(result.isArray());
            assertEquals(3, result.length());
        }
    }

    // ========================================================================
    //  Error handling
    // ========================================================================

    @Nested
    class ErrorTests {

        @Test
        void unsupportedFunction() {
            assertThrows(JsonataException.class,
                    () -> JsonataCompiler.compile("$lookup(obj, 'key')"));
        }

        @Test
        void unterminatedString() {
            assertThrows(JsonataException.class,
                    () -> JsonataCompiler.compile("'unterminated"));
        }

        @Test
        void descendantOperator() {
            // ** (recursive descent) is now supported
            String jq = JsonataCompiler.toJq("**.name");
            assertTrue(jq.contains(".."));
        }
    }

    // ========================================================================
    //  Complex expressions (h5m patterns)
    // ========================================================================

    @Nested
    class H5mPatterns {

        @Test
        void sumOfField() {
            JqValue data = JqValues.parse("""
                    {"orders": [
                        {"price": 10, "qty": 2},
                        {"price": 20, "qty": 1},
                        {"price": 5, "qty": 4}
                    ]}
                    """);
            assertEquals(JqNumber.of(35), eval("$sum(orders.price)", data));
        }

        @Test
        void arithmeticExpression() {
            JqValue data = JqValues.parse("{\"x\": 10, \"y\": 3}");
            assertEquals(JqNumber.of(7), eval("x - y", data));
            assertEquals(JqNumber.of(30), eval("x * y", data));
        }

        @Test
        void conditionalExtraction() {
            assertEquals(JqString.of("adult"),
                    eval("Age >= 18 ? 'adult' : 'minor'"));
        }
    }
}

package io.hyperfoil.tools.jjq.schema;

import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqBoolean;
import io.hyperfoil.tools.jjq.value.JqNull;
import io.hyperfoil.tools.jjq.value.JqNumber;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JqSchemaTest {

    @Test
    void booleanTrueAcceptsEverything() {
        JqSchema schema = JqSchema.compile(JqBoolean.TRUE);
        assertTrue(schema.isValid(JqNumber.of(1)));
        assertTrue(schema.isValid(JqNull.NULL));
        assertEquals(0, schema.validate(JqString.of("x")).size());
    }

    @Test
    void booleanFalseRejectsEverything() {
        JqSchema schema = JqSchema.compile(JqBoolean.FALSE);
        assertFalse(schema.isValid(JqNumber.of(1)));
        JqArray errors = schema.validate(JqNumber.of(1));
        assertEquals(1, errors.size());
        JqObject first = (JqObject) errors.get(0);
        assertEquals("", first.getField("instanceLocation").stringValue());
    }

    @Test
    void emptyObjectAcceptsEverything() {
        JqSchema schema = JqSchema.compile(JqObject.builder(0).build());
        assertTrue(schema.isValid(JqString.of("x")));
        assertEquals(0, schema.validate(JqNumber.of(1)).size());
    }

    @Test
    void hyperfoilFixturesParse() throws Exception {
        // Vendored acceptance fixtures (see resources/hyperfoil/README.md):
        // valid JSON objects today; compilation + validation once keywords land.
        for (String name : new String[]{"hyperfoil/schema.json", "hyperfoil/run-schema.json"}) {
            try (var in = getClass().getClassLoader().getResourceAsStream(name)) {
                assertNotNull(in, "missing fixture " + name);
                JqValue schema = JqValues.parse(new String(in.readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8));
                assertTrue(schema.isObject(), name + " must be an object");
            }
        }
    }

    // ---- Scalar slice: type, enum, const (issue #167) ----

    private static JqObject schemaOf(String json) {
        return (JqObject) JqValues.parse(json);
    }

    @Test
    void typeString() {
        JqSchema schema = JqSchema.compile(schemaOf("{\"type\":\"string\"}"));
        assertTrue(schema.isValid(JqString.of("a")));
        assertFalse(schema.isValid(JqNumber.of(1)));
        JqArray errors = schema.validate(JqNumber.of(1));
        assertEquals(1, errors.size());
        assertEquals("type", ((JqObject) errors.get(0)).getField("keyword").stringValue());
    }

    @Test
    void typeArray() {
        JqSchema schema = JqSchema.compile(schemaOf("{\"type\":[\"string\",\"null\"]}"));
        assertTrue(schema.isValid(JqNull.NULL));
        assertTrue(schema.isValid(JqString.of("a")));
        assertFalse(schema.isValid(JqBoolean.FALSE));
    }

    @Test
    void typeIntegerIsZeroFractionalPart() {
        JqSchema schema = JqSchema.compile(schemaOf("{\"type\":\"integer\"}"));
        assertTrue(schema.isValid(JqNumber.of(1)));
        assertTrue(schema.isValid(JqValues.parse("1.0")));
        assertTrue(schema.isValid(JqValues.parse("18446744073709551615")));
        assertTrue(schema.isValid(JqValues.parse("1e20")));
        assertFalse(schema.isValid(JqValues.parse("1.5")));
        assertFalse(schema.isValid(JqString.of("1")));
    }

    @Test
    void typeRejectsBadOperands() {
        assertThrows(JqSchemaException.class,
                () -> JqSchema.compile(schemaOf("{\"type\":\"strnig\"}")));
        assertThrows(JqSchemaException.class,
                () -> JqSchema.compile(schemaOf("{\"type\":1}")));
    }

    @Test
    void enumMatchesSchemaEquality() {
        JqSchema schema = JqSchema.compile(schemaOf("{\"enum\":[\"a\",1,null]}"));
        assertTrue(schema.isValid(JqString.of("a")));
        assertTrue(schema.isValid(JqValues.parse("1.0")));
        assertTrue(schema.isValid(JqNull.NULL));
        assertFalse(schema.isValid(JqString.of("b")));
        assertFalse(schema.isValid(JqNumber.of(2)));
        assertFalse(schema.isValid(JqBoolean.FALSE));
    }

    @Test
    void enumDistinguishesLargeIntegers() {
        JqSchema schema = JqSchema.compile(schemaOf("{\"enum\":[18446744073709551615]}"));
        assertTrue(schema.isValid(JqValues.parse("18446744073709551615")));
        assertFalse(schema.isValid(JqValues.parse("18446744073709551616")));
    }

    @Test
    void enumRequiresArray() {
        assertThrows(JqSchemaException.class,
                () -> JqSchema.compile(schemaOf("{\"enum\":\"a\"}")));
    }

    @Test
    void constMatchesStructurally() {
        JqSchema schema = JqSchema.compile(schemaOf("{\"const\":{\"a\":1,\"b\":[1,2]}}"));
        assertTrue(schema.isValid(JqValues.parse("{\"b\":[1,2],\"a\":1.0}")));
        assertFalse(schema.isValid(JqValues.parse("{\"a\":2,\"b\":[1,2]}")));
    }

    @Test
    void enumSnapshotsSuppliedValues() {
        java.util.Map<String, JqValue> backing = new java.util.LinkedHashMap<>();
        backing.put("enum", JqArray.of(JqString.of("a")));
        JqSchema schema = JqSchema.compile(JqObject.ofTrusted(backing));
        backing.put("enum", JqArray.of(JqString.of("b")));
        assertTrue(schema.isValid(JqString.of("a")));
    }

    @Test
    void annotationsIgnored() {
        JqSchema schema = JqSchema.compile(schemaOf(
                "{\"title\":\"t\",\"description\":\"d\",\"default\":1,\"deprecated\":false,"
                + "\"readOnly\":false,\"writeOnly\":false,\"examples\":[],"
                + "\"$comment\":\"c\",\"$id\":\"https://example.com/s\",\"type\":\"string\"}"));
        assertTrue(schema.isValid(JqString.of("a")));
        assertFalse(schema.isValid(JqNumber.of(1)));
    }

    @Test
    void schemaHashAgreesWithEquality() {
        assertEquals(JsonSemantics.schemaHash(JqValues.parse("1")),
                JsonSemantics.schemaHash(JqValues.parse("1.0")));
        assertEquals(JsonSemantics.schemaHash(JqValues.parse("18446744073709551615")),
                JsonSemantics.schemaHash(JqValues.parse("18446744073709551615.0")));
        assertEquals(JsonSemantics.schemaHash(JqValues.parse("{\"a\":1,\"b\":2}")),
                JsonSemantics.schemaHash(JqValues.parse("{\"b\":2,\"a\":1}")));
        assertEquals(JsonSemantics.schemaHash(JqValues.parse("1e20")),
                JsonSemantics.schemaHash(JqValues.parse("100000000000000000000")));
    }

    // ---- Numbers and strings (issue #167) ----

    @Test
    void numericBoundsInclusive() {
        JqSchema schema = JqSchema.compile(schemaOf("{\"minimum\":1,\"maximum\":10}"));
        assertTrue(schema.isValid(JqNumber.of(1)));
        assertTrue(schema.isValid(JqNumber.of(10)));
        assertFalse(schema.isValid(JqNumber.of(0)));
        assertFalse(schema.isValid(JqNumber.of(11)));
    }

    @Test
    void numericBoundsExclusive() {
        JqSchema schema = JqSchema.compile(schemaOf("{\"exclusiveMinimum\":1,\"exclusiveMaximum\":10}"));
        assertFalse(schema.isValid(JqNumber.of(1)));
        assertFalse(schema.isValid(JqNumber.of(10)));
        assertTrue(schema.isValid(JqValues.parse("1.5")));
    }

    @Test
    void numericBoundsExactBeyondLong() {
        JqSchema schema = JqSchema.compile(schemaOf("{\"minimum\":18446744073709551615}"));
        assertTrue(schema.isValid(JqValues.parse("18446744073709551615")));
        assertTrue(schema.isValid(JqValues.parse("18446744073709551616")));
        assertFalse(schema.isValid(JqValues.parse("18446744073709551614")));
    }

    @Test
    void multipleOfExact() {
        JqSchema intSchema = JqSchema.compile(schemaOf("{\"multipleOf\":3}"));
        assertTrue(intSchema.isValid(JqNumber.of(9)));
        assertFalse(intSchema.isValid(JqNumber.of(10)));
        JqSchema decSchema = JqSchema.compile(schemaOf("{\"multipleOf\":0.5}"));
        assertTrue(decSchema.isValid(JqNumber.of(2)));
        assertFalse(decSchema.isValid(JqValues.parse("2.1")));
    }

    @Test
    void multipleOfRejectsBadOperands() {
        assertThrows(JqSchemaException.class, () -> JqSchema.compile(schemaOf("{\"multipleOf\":0}")));
        assertThrows(JqSchemaException.class, () -> JqSchema.compile(schemaOf("{\"multipleOf\":-2}")));
        assertThrows(JqSchemaException.class, () -> JqSchema.compile(schemaOf("{\"multipleOf\":\"3\"}")));
    }

    @Test
    void stringLengthCountsCodePoints() {
        JqSchema schema = JqSchema.compile(schemaOf("{\"minLength\":2,\"maxLength\":2}"));
        assertTrue(schema.isValid(JqString.of("ab")));
        assertTrue(schema.isValid(JqString.of("a\ud83d\ude00")));
        assertFalse(schema.isValid(JqString.of("a")));
        assertFalse(schema.isValid(JqString.of("abc")));
    }

    @Test
    void patternIsUnanchoredSearch() {
        JqSchema schema = JqSchema.compile(schemaOf("{\"pattern\":\"es\"}"));
        assertTrue(schema.isValid(JqString.of("expression")));
        assertFalse(schema.isValid(JqString.of("xyz")));
    }

    @Test
    void patternRejectsBadOperands() {
        assertThrows(JqSchemaException.class, () -> JqSchema.compile(schemaOf("{\"pattern\":1}")));
        assertThrows(JqSchemaException.class, () -> JqSchema.compile(schemaOf("{\"pattern\":\"([\"}")));
    }

    @Test
    void typedKeywordsIgnoreOtherTypes() {
        assertTrue(JqSchema.compile(schemaOf("{\"minimum\":5}")).isValid(JqString.of("hi")));
        assertTrue(JqSchema.compile(schemaOf("{\"minLength\":5}")).isValid(JqNumber.of(42)));
        assertTrue(JqSchema.compile(schemaOf("{\"multipleOf\":3}")).isValid(JqBoolean.TRUE));
        assertTrue(JqSchema.compile(schemaOf("{\"pattern\":\"x\"}")).isValid(JqNull.NULL));
    }

    @Test
    void nonSchemaInputFailsCompilation() {        assertThrows(JqSchemaException.class, () -> JqSchema.compile(JqNumber.of(1)));
        assertThrows(JqSchemaException.class, () -> JqSchema.compile(JqNull.NULL));
        assertThrows(JqSchemaException.class, () -> JqSchema.compile(JqString.of("x")));
        assertThrows(JqSchemaException.class, () -> JqSchema.compile(JqArray.of()));
    }
}

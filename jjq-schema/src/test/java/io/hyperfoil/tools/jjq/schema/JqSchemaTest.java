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

    @Test
    void nonSchemaInputFailsCompilation() {        assertThrows(JqSchemaException.class, () -> JqSchema.compile(JqNumber.of(1)));
        assertThrows(JqSchemaException.class, () -> JqSchema.compile(JqNull.NULL));
        assertThrows(JqSchemaException.class, () -> JqSchema.compile(JqString.of("x")));
        assertThrows(JqSchemaException.class, () -> JqSchema.compile(JqArray.of()));
    }
}

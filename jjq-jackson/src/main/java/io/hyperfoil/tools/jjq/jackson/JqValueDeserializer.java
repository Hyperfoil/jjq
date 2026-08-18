package io.hyperfoil.tools.jjq.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import io.hyperfoil.tools.jjq.value.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Jackson {@link StdDeserializer} for {@link JqValue} that reads directly from a
 * {@link JsonParser} without constructing an intermediate {@code JsonNode} tree.
 *
 * <p>Handles all JSON types, including nested objects and arrays. Numbers are
 * preserved with the most specific JqNumber representation: long for integers,
 * BigDecimal for exact decimals.</p>
 *
 * @see JqValueSerializer
 * @see JqValueModule
 */
class JqValueDeserializer extends StdDeserializer<JqValue> {

    JqValueDeserializer() {
        super(JqValue.class);
    }

    @Override
    public JqValue deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        return readValue(p, ctxt);
    }

    @Override
    public JqValue getNullValue(DeserializationContext ctxt) {
        return JqNull.NULL;
    }

    private JqValue readValue(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken token = p.currentToken();
        if (token == null) {
            token = p.nextToken();
        }
        return switch (token) {
            case VALUE_NULL -> JqNull.NULL;
            case VALUE_TRUE -> JqBoolean.TRUE;
            case VALUE_FALSE -> JqBoolean.FALSE;
            case VALUE_STRING -> JqString.of(p.getText());
            case VALUE_NUMBER_INT -> JqNumber.of(p.getLongValue());
            case VALUE_NUMBER_FLOAT -> {
                BigDecimal bd = p.getDecimalValue();
                yield JqNumber.of(bd);
            }
            case START_ARRAY -> readArray(p, ctxt);
            case START_OBJECT -> readObject(p, ctxt);
            default -> throw ctxt.wrongTokenException(p, JqValue.class, token,
                    "Unexpected token for JqValue deserialization");
        };
    }

    private JqArray readArray(JsonParser p, DeserializationContext ctxt) throws IOException {
        var list = new ArrayList<JqValue>();
        while (p.nextToken() != JsonToken.END_ARRAY) {
            list.add(readValue(p, ctxt));
        }
        return JqArray.of(list);
    }

    private JqObject readObject(JsonParser p, DeserializationContext ctxt) throws IOException {
        var map = new LinkedHashMap<String, JqValue>();
        while (p.nextToken() != JsonToken.END_OBJECT) {
            String fieldName = p.currentName();
            p.nextToken();
            map.put(fieldName, readValue(p, ctxt));
        }
        return JqObject.ofTrusted(map);
    }
}

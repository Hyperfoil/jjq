package io.hyperfoil.tools.jjq.jakarta;

import io.hyperfoil.tools.jjq.value.*;
import jakarta.json.bind.serializer.DeserializationContext;
import jakarta.json.bind.serializer.JsonbDeserializer;
import jakarta.json.stream.JsonParser;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;


/**
 * Jakarta JSON-B {@link JsonbDeserializer} for {@link JqValue} that reads directly
 * from a {@link JsonParser} without constructing an intermediate tree.
 *
 * <p>Register via {@code @JsonbTypeDeserializer(JqValueJsonbDeserializer.class)} on
 * entity fields, or configure globally via {@link jakarta.json.bind.JsonbConfig}:</p>
 *
 * <pre>{@code
 * JsonbConfig config = new JsonbConfig()
 *     .withDeserializers(new JqValueJsonbDeserializer());
 * Jsonb jsonb = JsonbBuilder.create(config);
 * }</pre>
 *
 * <p>Handles all JSON types, including nested objects and arrays. Numbers are
 * preserved with the most specific JqNumber representation.</p>
 *
 * @see JqValueJsonbSerializer
 */
public class JqValueJsonbDeserializer implements JsonbDeserializer<JqValue> {

    /** Creates a new JqValueJsonbDeserializer. */
    public JqValueJsonbDeserializer() {}

    @Override
    public JqValue deserialize(JsonParser parser, DeserializationContext ctx, Type rtType) {
        // JSON-B spec: the parser is already positioned on the value event
        // by the runtime (Yasson, etc.) before calling deserialize.
        // We must NOT call parser.next() here.
        return readCurrentValue(parser);
    }

    /**
     * Read the JqValue at the parser's current position.
     * The parser must already be positioned on a value event.
     */
    private JqValue readCurrentValue(JsonParser parser) {
        return readValue(parser.getValue(), parser);
    }

    private JqValue readValue(jakarta.json.JsonValue jsonValue, JsonParser parser) {
        return switch (jsonValue.getValueType()) {
            case NULL -> JqNull.NULL;
            case TRUE -> JqBoolean.TRUE;
            case FALSE -> JqBoolean.FALSE;
            case STRING -> JqString.of(((jakarta.json.JsonString) jsonValue).getString());
            case NUMBER -> {
                jakarta.json.JsonNumber num = (jakarta.json.JsonNumber) jsonValue;
                if (num.isIntegral()) {
                    yield JqNumber.of(num.longValue());
                }
                yield JqNumber.of(num.bigDecimalValue());
            }
            case ARRAY -> {
                jakarta.json.JsonArray arr = (jakarta.json.JsonArray) jsonValue;
                var list = new ArrayList<JqValue>(arr.size());
                for (jakarta.json.JsonValue elem : arr) {
                    list.add(readValue(elem, parser));
                }
                yield JqArray.of(list);
            }
            case OBJECT -> {
                jakarta.json.JsonObject obj = (jakarta.json.JsonObject) jsonValue;
                var map = new LinkedHashMap<String, JqValue>(obj.size());
                for (var entry : obj.entrySet()) {
                    map.put(entry.getKey(), readValue(entry.getValue(), parser));
                }
                yield JqObject.ofTrusted(map);
            }
        };
    }
}

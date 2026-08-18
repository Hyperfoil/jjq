package io.hyperfoil.tools.jjq.jakarta;

import io.hyperfoil.tools.jjq.value.*;
import jakarta.json.bind.serializer.JsonbSerializer;
import jakarta.json.bind.serializer.SerializationContext;
import jakarta.json.stream.JsonGenerator;

import java.math.BigDecimal;

/**
 * Jakarta JSON-B {@link JsonbSerializer} for {@link JqValue} that writes directly
 * to a {@link JsonGenerator} without intermediate String allocation.
 *
 * <p>Register via {@code @JsonbTypeSerializer(JqValueJsonbSerializer.class)} on
 * entity fields, or configure globally via {@link jakarta.json.bind.JsonbConfig}:</p>
 *
 * <pre>{@code
 * JsonbConfig config = new JsonbConfig()
 *     .withSerializers(new JqValueJsonbSerializer());
 * Jsonb jsonb = JsonbBuilder.create(config);
 * }</pre>
 *
 * <p>Handles all six JqValue subtypes: null, boolean, number, string, array,
 * and object. NaN and Infinity are serialized as JSON null.</p>
 *
 * @see JqValueJsonbDeserializer
 */
public class JqValueJsonbSerializer implements JsonbSerializer<JqValue> {

    /** Creates a new JqValueJsonbSerializer. */
    public JqValueJsonbSerializer() {}

    @Override
    public void serialize(JqValue value, JsonGenerator generator, SerializationContext ctx) {
        if (value == null) {
            generator.writeNull();
            return;
        }
        writeValue(value, generator);
    }

    private void writeValue(JqValue value, JsonGenerator gen) {
        switch (value) {
            case JqNull ignored -> gen.writeNull();
            case JqBoolean b -> gen.write(b.booleanValue());
            case JqString s -> gen.write(s.stringValue());
            case JqNumber n -> writeNumber(n, gen);
            case JqArray a -> {
                gen.writeStartArray();
                for (JqValue elem : a.arrayValue()) {
                    writeValue(elem, gen);
                }
                gen.writeEnd();
            }
            case JqObject o -> {
                gen.writeStartObject();
                for (var entry : o.objectValue().entrySet()) {
                    gen.writeKey(entry.getKey());
                    writeValue(entry.getValue(), gen);
                }
                gen.writeEnd();
            }
        }
    }

    private void writeNumber(JqNumber n, JsonGenerator gen) {
        if (n.isNaN() || n.isInfinite()) {
            gen.writeNull();
            return;
        }
        if (n.isLongBacked()) {
            gen.write(n.longValue());
            return;
        }
        if (n.isIntegral()) {
            gen.write(n.longValue());
            return;
        }
        BigDecimal bd = n.decimalValue();
        gen.write(bd);
    }
}

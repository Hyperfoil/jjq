package io.hyperfoil.tools.jjq.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import io.hyperfoil.tools.jjq.value.*;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * Jackson {@link StdSerializer} for {@link JqValue} that writes directly to a
 * {@link JsonGenerator} without intermediate String allocation.
 *
 * <p>Handles all six JqValue subtypes: null, boolean, number, string, array, and object.
 * Numbers are serialized with the most specific generator method (long, double, or BigDecimal)
 * to preserve the exact representation through Jackson's pipeline.</p>
 *
 * @see JqValueDeserializer
 * @see JqValueModule
 */
class JqValueSerializer extends StdSerializer<JqValue> {

    JqValueSerializer() {
        super(JqValue.class);
    }

    @Override
    public void serialize(JqValue value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        writeValue(value, gen);
    }

    private void writeValue(JqValue value, JsonGenerator gen) throws IOException {
        switch (value) {
            case JqNull ignored -> gen.writeNull();
            case JqBoolean b -> gen.writeBoolean(b.booleanValue());
            case JqString s -> gen.writeString(s.stringValue());
            case JqNumber n -> writeNumber(n, gen);
            case JqArray a -> {
                gen.writeStartArray();
                for (JqValue elem : a.arrayValue()) {
                    writeValue(elem, gen);
                }
                gen.writeEndArray();
            }
            case JqObject o -> {
                gen.writeStartObject();
                for (var entry : o.objectValue().entrySet()) {
                    gen.writeFieldName(entry.getKey());
                    writeValue(entry.getValue(), gen);
                }
                gen.writeEndObject();
            }
        }
    }

    private void writeNumber(JqNumber n, JsonGenerator gen) throws IOException {
        if (n.isNaN() || n.isInfinite()) {
            // JSON has no NaN/Infinity — serialize as null (matching jq behavior)
            gen.writeNull();
            return;
        }
        if (n.isLongBacked()) {
            long l = n.longValue();
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                gen.writeNumber((int) l);
            } else {
                gen.writeNumber(l);
            }
            return;
        }
        if (n.isIntegral()) {
            gen.writeNumber(n.longValue());
            return;
        }
        // Non-integral: use BigDecimal for exact representation
        BigDecimal bd = n.decimalValue();
        double d = n.doubleValue();
        if (BigDecimal.valueOf(d).compareTo(bd) == 0) {
            gen.writeNumber(d);
        } else {
            gen.writeNumber(bd);
        }
    }
}

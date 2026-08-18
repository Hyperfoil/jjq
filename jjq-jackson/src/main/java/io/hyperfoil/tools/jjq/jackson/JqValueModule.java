package io.hyperfoil.tools.jjq.jackson;

import com.fasterxml.jackson.databind.module.SimpleModule;
import io.hyperfoil.tools.jjq.value.*;

/**
 * Jackson {@link com.fasterxml.jackson.databind.Module} that registers serializers
 * and deserializers for all {@link JqValue} types.
 *
 * <p>Once registered, any POJO containing {@code JqValue} fields can be serialized
 * and deserialized by Jackson without manual conversion:</p>
 *
 * <pre>{@code
 * ObjectMapper mapper = new ObjectMapper();
 * mapper.registerModule(new JqValueModule());
 *
 * // POJOs with JqValue fields now work automatically
 * record MyEntity(String name, JqValue metadata) {}
 * String json = mapper.writeValueAsString(new MyEntity("test", JqObject.of(Map.of("k", JqString.of("v")))));
 * MyEntity restored = mapper.readValue(json, MyEntity.class);
 * }</pre>
 *
 * <p>The module registers handlers for the sealed {@code JqValue} interface and
 * all six concrete subtypes ({@link JqNull}, {@link JqBoolean}, {@link JqNumber},
 * {@link JqString}, {@link JqArray}, {@link JqObject}).</p>
 *
 * @see JqValueSerializer
 * @see JqValueDeserializer
 */
public class JqValueModule extends SimpleModule {

    /**
     * Creates a new JqValueModule with serializers and deserializers for all JqValue types.
     */
    public JqValueModule() {
        super("jjq");

        var serializer = new JqValueSerializer();
        var deserializer = new JqValueDeserializer();

        // Register for the sealed interface — covers all subtypes when declared as JqValue
        addSerializer(JqValue.class, serializer);
        addDeserializer(JqValue.class, deserializer);

        // Also register for each concrete subtype so Jackson resolves them
        // when fields are declared with specific types (e.g., JqObject metadata)
        addSerializer(JqNull.class, castSerializer(serializer));
        addSerializer(JqBoolean.class, castSerializer(serializer));
        addSerializer(JqNumber.class, castSerializer(serializer));
        addSerializer(JqString.class, castSerializer(serializer));
        addSerializer(JqArray.class, castSerializer(serializer));
        addSerializer(JqObject.class, castSerializer(serializer));

        addDeserializer(JqNull.class, castDeserializer(deserializer));
        addDeserializer(JqBoolean.class, castDeserializer(deserializer));
        addDeserializer(JqNumber.class, castDeserializer(deserializer));
        addDeserializer(JqString.class, castDeserializer(deserializer));
        addDeserializer(JqArray.class, castDeserializer(deserializer));
        addDeserializer(JqObject.class, castDeserializer(deserializer));
    }

    @SuppressWarnings("unchecked")
    private static <T extends JqValue> com.fasterxml.jackson.databind.JsonSerializer<T> castSerializer(JqValueSerializer serializer) {
        return (com.fasterxml.jackson.databind.JsonSerializer<T>) (com.fasterxml.jackson.databind.JsonSerializer<?>) serializer;
    }

    @SuppressWarnings("unchecked")
    private static <T extends JqValue> com.fasterxml.jackson.databind.JsonDeserializer<T> castDeserializer(JqValueDeserializer deserializer) {
        return (com.fasterxml.jackson.databind.JsonDeserializer<T>) (com.fasterxml.jackson.databind.JsonDeserializer<?>) deserializer;
    }
}

package io.hyperfoil.tools.jjq.mapper.jsonb;

import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import jakarta.json.bind.annotation.JsonbProperty;
import jakarta.json.bind.annotation.JsonbTransient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the JSON-B annotation bridge.
 */
class JsonbAnnotationBridgeTest {

    private final JqMapper mapper = JqMapper.builder()
            .bridge(new JsonbAnnotationBridge())
            .build();

    // ---- @JsonbProperty ----

    record RenamedRecord(
            @JsonbProperty("full_name") String name,
            int age
    ) {}

    @Test
    void jsonbProperty_renamesField() {
        JqValue json = JqValues.parse("{\"full_name\":\"Alice\",\"age\":30}");
        RenamedRecord r = mapper.fromJqValue(json, RenamedRecord.class);
        assertEquals("Alice", r.name());
        assertEquals(30, r.age());
    }

    @Test
    void jsonbProperty_serializesWithRenamedKey() {
        JqValue result = mapper.toJqValue(new RenamedRecord("Alice", 30));
        String json = result.toJsonString();
        assertTrue(json.contains("\"full_name\""), "Should use @JsonbProperty name: " + json);
        assertFalse(json.contains("\"name\""), "Should not use Java name: " + json);
    }

    // ---- @JsonbTransient ----

    record TransientRecord(
            String name,
            @JsonbTransient String secret,
            int value
    ) {}

    @Test
    void jsonbTransient_excludesField() {
        JqValue json = JqValues.parse("{\"name\":\"Alice\",\"secret\":\"hidden\",\"value\":42}");
        TransientRecord r = mapper.fromJqValue(json, TransientRecord.class);
        assertEquals("Alice", r.name());
        assertNull(r.secret());
        assertEquals(42, r.value());
    }

    @Test
    void jsonbTransient_excludesFromSerialization() {
        JqValue result = mapper.toJqValue(new TransientRecord("Alice", "hidden", 42));
        String json = result.toJsonString();
        assertFalse(json.contains("\"secret\""), "Should exclude @JsonbTransient field: " + json);
    }

    // ---- Combined ----

    record CombinedRecord(
            @JsonbProperty("user_name") String name,
            @JsonbTransient String internal,
            int score
    ) {}

    @Test
    void combined_renameAndTransient() {
        JqValue json = JqValues.parse("{\"user_name\":\"Alice\",\"internal\":\"x\",\"score\":95}");
        CombinedRecord r = mapper.fromJqValue(json, CombinedRecord.class);
        assertEquals("Alice", r.name());
        assertNull(r.internal());
        assertEquals(95, r.score());
    }

    // ---- Round-trip ----

    @Test
    void roundTrip_withJsonbAnnotations() {
        RenamedRecord original = new RenamedRecord("Alice", 30);
        JqValue json = mapper.toJqValue(original);
        RenamedRecord restored = mapper.fromJqValue(json, RenamedRecord.class);
        assertEquals(original, restored);
    }
}

package io.hyperfoil.tools.jjq.mapper.jackson;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.mapper.JqMapped;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Jackson annotation bridge.
 */
class JacksonAnnotationBridgeTest {

    private final JqMapper mapper = JqMapper.builder()
            .bridge(new JacksonAnnotationBridge())
            .build();

    // ---- @JsonProperty ----

    record RenamedRecord(
            @JsonProperty("full_name") String name,
            int age
    ) {}

    @Test
    void jsonProperty_renamesField() {
        JqValue json = JqValues.parse("{\"full_name\":\"Alice\",\"age\":30}");
        RenamedRecord r = mapper.fromJqValue(json, RenamedRecord.class);
        assertEquals("Alice", r.name());
        assertEquals(30, r.age());
    }

    @Test
    void jsonProperty_serializesWithRenamedKey() {
        JqValue result = mapper.toJqValue(new RenamedRecord("Alice", 30));
        String json = result.toJsonString();
        assertTrue(json.contains("\"full_name\""), "Should use @JsonProperty name: " + json);
        assertFalse(json.contains("\"name\""), "Should not use Java name: " + json);
    }

    // ---- @JsonIgnore ----

    record IgnoredRecord(
            String name,
            @JsonIgnore String secret,
            int value
    ) {}

    @Test
    void jsonIgnore_excludesField() {
        JqValue json = JqValues.parse("{\"name\":\"Alice\",\"secret\":\"hidden\",\"value\":42}");
        IgnoredRecord r = mapper.fromJqValue(json, IgnoredRecord.class);
        assertEquals("Alice", r.name());
        assertNull(r.secret());
        assertEquals(42, r.value());
    }

    @Test
    void jsonIgnore_excludesFromSerialization() {
        JqValue result = mapper.toJqValue(new IgnoredRecord("Alice", "hidden", 42));
        String json = result.toJsonString();
        assertFalse(json.contains("\"secret\""), "Should exclude @JsonIgnore field: " + json);
    }

    // ---- @JsonIgnoreProperties ----

    @JsonIgnoreProperties({"password", "token"})
    record SecureRecord(String name, String password, String token, int age) {}

    @Test
    void jsonIgnoreProperties_excludesListedFields() {
        JqValue json = JqValues.parse("{\"name\":\"Alice\",\"password\":\"secret\",\"token\":\"abc\",\"age\":30}");
        SecureRecord r = mapper.fromJqValue(json, SecureRecord.class);
        assertEquals("Alice", r.name());
        assertNull(r.password());
        assertNull(r.token());
        assertEquals(30, r.age());
    }

    // ---- @JsonInclude ----

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record NonNullJacksonRecord(String data, String error) {}

    @Test
    void jsonInclude_nonNull() {
        JqValue result = mapper.toJqValue(new NonNullJacksonRecord("hello", null));
        String json = result.toJsonString();
        assertTrue(json.contains("\"data\""), json);
        assertFalse(json.contains("\"error\""), "Should exclude null field: " + json);
    }

    // ---- Combined ----

    record CombinedRecord(
            @JsonProperty("user_name") String name,
            @JsonIgnore String internal,
            int score
    ) {}

    @Test
    void combined_renameAndIgnore() {
        JqValue json = JqValues.parse("{\"user_name\":\"Alice\",\"internal\":\"x\",\"score\":95}");
        CombinedRecord r = mapper.fromJqValue(json, CombinedRecord.class);
        assertEquals("Alice", r.name());
        assertNull(r.internal());
        assertEquals(95, r.score());

        JqValue result = mapper.toJqValue(new CombinedRecord("Alice", "x", 95));
        String out = result.toJsonString();
        assertTrue(out.contains("\"user_name\""), out);
        assertFalse(out.contains("\"internal\""), out);
    }

    // ---- Round-trip ----

    @Test
    void roundTrip_withJacksonAnnotations() {
        RenamedRecord original = new RenamedRecord("Alice", 30);
        JqValue json = mapper.toJqValue(original);
        RenamedRecord restored = mapper.fromJqValue(json, RenamedRecord.class);
        assertEquals(original, restored);
    }
}

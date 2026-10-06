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

    // ---- @JsonAnySetter / @JsonAnyGetter (issue #87) ----

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    static class JacksonExtrasPojo {
        private String name;
        private final java.util.Map<String, Object> extras = new java.util.LinkedHashMap<>();

        public JacksonExtrasPojo() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void setExtra(String key, Object value) { extras.put(key, value); }

        @com.fasterxml.jackson.annotation.JsonAnyGetter
        public java.util.Map<String, Object> getExtras() { return extras; }
    }

    @Test
    void jsonAnySetter_capturesUnknownKeys() {
        JqValue json = JqValues.parse("{\"name\":\"t\",\"testProxyTool\":{\"token\":\"tpt-fixture\"}}");
        JacksonExtrasPojo p = mapper.fromJqValue(json, JacksonExtrasPojo.class);
        assertEquals("t", p.getName());
        assertEquals(1, p.getExtras().size());
        assertTrue(p.getExtras().get("testProxyTool") instanceof java.util.Map, p.getExtras().toString());
    }

    @Test
    void jsonAnyGetter_roundTrip() {
        JqValue json = JqValues.parse("{\"name\":\"t\",\"testProxyTool\":{\"token\":\"tpt-fixture\"}}");
        JacksonExtrasPojo p = mapper.fromJqValue(json, JacksonExtrasPojo.class);
        String out = mapper.toJqValue(p).toJsonString();
        assertTrue(out.contains("\"name\":\"t\""), out);
        assertTrue(out.contains("\"testProxyTool\""), out);
        assertTrue(out.contains("\"tpt-fixture\""), out);
    }

    @Test
    void jsonAnyGetter_backingMapNotEmitted() {
        // The any-getter must not ALSO be emitted as a bean property (issue #88.1)
        JqValue json = JqValues.parse("{\"name\":\"t\",\"testProxyTool\":{\"token\":\"tpt-fixture\"}}");
        JacksonExtrasPojo p = mapper.fromJqValue(json, JacksonExtrasPojo.class);
        String out = mapper.toJqValue(p).toJsonString();
        assertFalse(out.contains("\"extras\""), out);
    }

    // ---- @JsonValue / @JsonCreator (issue #88.4) ----

    enum AuthType {
        API_KEY, OAUTH;

        @com.fasterxml.jackson.annotation.JsonValue
        public String toWire() {
            return name().toLowerCase().replace('_', '-');
        }

        @com.fasterxml.jackson.annotation.JsonCreator
        public static AuthType fromWire(String wire) {
            for (AuthType t : values()) {
                if (t.toWire().equals(wire)) return t;
            }
            throw new IllegalArgumentException("unknown auth type: " + wire);
        }
    }

    record WithAuth(String name, AuthType auth) {}

    @Test
    void jsonValue_serializesWireForm() {
        JqValue out = mapper.toJqValue(new WithAuth("t", AuthType.API_KEY));
        assertEquals("api-key", out.getField("auth").stringValue());
    }

    @Test
    void jsonCreator_deserializesWireForm() {
        WithAuth r = mapper.fromJqValue(JqValues.parse("{\"name\":\"t\",\"auth\":\"api-key\"}"), WithAuth.class);
        assertEquals(AuthType.API_KEY, r.auth());
    }

    @Test
    void jsonCreator_unknownValueFailsFast() {
        assertThrows(io.hyperfoil.tools.jjq.mapper.JqMapperException.class, () ->
                mapper.fromJqValue(JqValues.parse("{\"name\":\"t\",\"auth\":\"nope\"}"), WithAuth.class));
    }

    @Test
    void plainEnum_unchanged() {
        // Enums without annotations keep name() behavior both directions
        record WithPlain(String name, PlainKind kind) {}
        JqValue out = mapper.toJqValue(new WithPlain("t", PlainKind.FOO));
        assertEquals("FOO", out.getField("kind").stringValue());
        WithPlain r = mapper.fromJqValue(JqValues.parse("{\"name\":\"t\",\"kind\":\"FOO\"}"), WithPlain.class);
        assertEquals(PlainKind.FOO, r.kind());
    }

    enum PlainKind {
        FOO, BAR
    }

    // ---- @JsonProperty(access = ...) (issue #88.2) ----

    static class AccessPojo {
        private String name;
        @com.fasterxml.jackson.annotation.JsonProperty(value = "secret", access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
        private String secret;
        @com.fasterxml.jackson.annotation.JsonProperty(value = "id", access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        private String id;

        public AccessPojo() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
    }

    @Test
    void writeOnly_boundOnDeserSkippedOnSer() {
        AccessPojo p = mapper.fromJqValue(
                JqValues.parse("{\"name\":\"t\",\"secret\":\"s3cr3t\",\"id\":\"42\"}"), AccessPojo.class);
        assertEquals("t", p.getName());
        assertEquals("s3cr3t", p.getSecret());
        // READ_ONLY is not bound on deser
        assertNull(p.getId());

        String out = mapper.toJqValue(p).toJsonString();
        // WRITE_ONLY is not emitted on ser...
        assertFalse(out.contains("s3cr3t"), out);
        assertFalse(out.contains("\"secret\""), out);
        // ...while READ_ONLY is emitted
        assertTrue(out.contains("\"name\":\"t\""), out);
    }

    @Test
    void readOnly_emittedWithValue() {
        AccessPojo p = new AccessPojo();
        p.setName("t");
        p.setId("42");
        String out = mapper.toJqValue(p).toJsonString();
        assertTrue(out.contains("\"id\":\"42\""), out);
    }

    record AccessRecord(String name,
                        @com.fasterxml.jackson.annotation.JsonProperty(value = "secret",
                                access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
                        String secret) {}

    @Test
    void access_recordComponents() {
        AccessRecord r = mapper.fromJqValue(
                JqValues.parse("{\"name\":\"t\",\"secret\":\"s\"}"), AccessRecord.class);
        assertEquals("t", r.name());
        assertEquals("s", r.secret());
        String out = mapper.toJqValue(r).toJsonString();
        assertFalse(out.contains("secret"), out);
        assertTrue(out.contains("\"name\":\"t\""), out);
    }
}

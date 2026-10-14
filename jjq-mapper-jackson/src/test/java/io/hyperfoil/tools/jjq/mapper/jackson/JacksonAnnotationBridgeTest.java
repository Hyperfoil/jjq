package io.hyperfoil.tools.jjq.mapper.jackson;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
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

    // ---- @JsonProperty on enum constants (issue #95.1) ----

    enum Action {
        @JsonProperty("set-if-unset")
        SET_IF_UNSET,
        @JsonProperty("plain")
        PLAIN
    }

    record WithAction(String name, Action action) {}

    @Test
    void jsonProperty_enumSerializesWithWireName() {
        JqValue result = mapper.toJqValue(new WithAction("x", Action.SET_IF_UNSET));
        String json = result.toJsonString();
        assertTrue(json.contains("\"set-if-unset\""), "Should use wire name: " + json);
        assertFalse(json.contains("SET_IF_UNSET"), "Should not use name(): " + json);
    }

    @Test
    void jsonProperty_enumDeserializesWireName() {
        JqValue json = JqValues.parse("{\"name\":\"x\",\"action\":\"set-if-unset\"}");
        WithAction r = mapper.fromJqValue(json, WithAction.class);
        assertEquals(Action.SET_IF_UNSET, r.action());
    }

    @Test
    void jsonProperty_enumRoundTrip() {
        WithAction original = new WithAction("x", Action.PLAIN);
        assertEquals(original, mapper.fromJqValue(mapper.toJqValue(original), WithAction.class));
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

    // ---- @JsonAutoDetect getter visibility NONE (issue #89) ----

    @com.fasterxml.jackson.annotation.JsonAutoDetect(
            fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY,
            getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE,
            isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static class FieldsOnlyPojo {
        private boolean useVertex;

        public FieldsOnlyPojo() {}

        public void setUseVertex(boolean useVertex) { this.useVertex = useVertex; }

        // Derived accessor: must never be called by the mapper (would recurse via account())
        public boolean isUseVertex() {
            throw new AssertionError("derived getter must not be bound with getterVisibility NONE");
        }
    }

    @Test
    void autoDetectNone_bindsFieldNotGetter() {
        FieldsOnlyPojo p = mapper.fromJqValue(
                JqValues.parse("{\"useVertex\":true}"), FieldsOnlyPojo.class);
        // Deser goes through the setter (no recursion, no throw)
        // Ser reads the stored field directly (no throw)
        String out = mapper.toJqValue(p).toJsonString();
        assertTrue(out.contains("\"useVertex\":true"), out);
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(
            getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static class GetSuppressedPojo {
        private String name;
        private boolean active;

        public GetSuppressedPojo() {}

        public String getName() { throw new AssertionError("getName must not be bound"); }
        public void setName(String name) { this.name = name; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
    }

    @Test
    void autoDetectNone_getterOnlyIsGetterStillApplies() {
        // getterVisibility NONE suppresses getX but leaves isX active
        GetSuppressedPojo p = mapper.fromJqValue(
                JqValues.parse("{\"name\":\"t\",\"active\":true}"), GetSuppressedPojo.class);
        String out = mapper.toJqValue(p).toJsonString();
        assertTrue(out.contains("\"active\":true"), out);
    }

    // ---- @JsonIgnore on accessor methods (issue #89) ----

    static class AccessorIgnorePojo {
        private String name;
        private String secret;
        private String token;

        public AccessorIgnorePojo() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        @com.fasterxml.jackson.annotation.JsonIgnore
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public String getToken() { return token; }
        @com.fasterxml.jackson.annotation.JsonIgnore
        public void setToken(String token) { this.token = token; }
    }

    @Test
    void jsonIgnoreOnGetter_skipsSerBindsDeser() {
        AccessorIgnorePojo p = mapper.fromJqValue(
                JqValues.parse("{\"name\":\"t\",\"secret\":\"s\",\"token\":\"k\"}"), AccessorIgnorePojo.class);
        assertEquals("s", p.getSecret());
        String out = mapper.toJqValue(p).toJsonString();
        assertFalse(out.contains("secret"), out);
        assertTrue(out.contains("\"name\":\"t\""), out);
    }

    @Test
    void jsonIgnoreOnSetter_skipsDeserEmitsSer() {
        AccessorIgnorePojo p = mapper.fromJqValue(
                JqValues.parse("{\"name\":\"t\",\"token\":\"k\"}"), AccessorIgnorePojo.class);
        assertNull(p.getToken());
        p.setToken("k");
        String out = mapper.toJqValue(p).toJsonString();
        assertTrue(out.contains("\"token\":\"k\""), out);
    }

    // ---- @JsonTypeInfo polymorphism (PROPERTY + NAME subset) ----

    @com.fasterxml.jackson.annotation.JsonTypeInfo(
            use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME,
            include = com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY,
            property = "type")
    @com.fasterxml.jackson.annotation.JsonSubTypes({
            @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = JackCircle.class, name = "circle"),
            @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = JackRect.class),
            @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = JackSquare.class)
    })
    interface JackShape {}

    record JackCircle(double radius) implements JackShape {}

    record JackRect(double w, double h) implements JackShape {}

    @com.fasterxml.jackson.annotation.JsonTypeName("sq")
    record JackSquare(double side) implements JackShape {}

    @Test
    void jsonTypeInfo_selectsByExplicitName() {
        JackShape s = mapper.fromJqValue(JqValues.parse("{\"type\":\"circle\",\"radius\":1.5}"), JackShape.class);
        assertEquals(new JackCircle(1.5), s);
    }

    @Test
    void jsonTypeInfo_defaultsToSimpleName() {
        JackShape s = mapper.fromJqValue(
                JqValues.parse("{\"type\":\"JackRect\",\"w\":2.0,\"h\":3.0}"), JackShape.class);
        assertEquals(new JackRect(2.0, 3.0), s);
    }

    @Test
    void jsonTypeInfo_honorsJsonTypeName() {
        JackShape s = mapper.fromJqValue(JqValues.parse("{\"type\":\"sq\",\"side\":4.0}"), JackShape.class);
        assertEquals(new JackSquare(4.0), s);
    }

    @Test
    void jsonTypeInfo_serializesDiscriminator() {
        String out = mapper.toJqValue(new JackCircle(1.5)).toJsonString();
        assertTrue(out.contains("\"type\":\"circle\""), out);
        String out2 = mapper.toJqValue(new JackSquare(4.0)).toJsonString();
        assertTrue(out2.contains("\"type\":\"sq\""), out2);
    }

    @Test
    void jsonTypeInfo_roundTrip() {
        for (JackShape s : java.util.List.of(new JackCircle(1.5), new JackRect(2.0, 3.0))) {
            assertEquals(s, mapper.fromJqValue(mapper.toJqValue(s), JackShape.class));
        }
    }

    @io.hyperfoil.tools.jjq.mapper.JqType(discriminator = "kind")
    @com.fasterxml.jackson.annotation.JsonTypeInfo(
            use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME,
            include = com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY,
            property = "type")
    @com.fasterxml.jackson.annotation.JsonSubTypes({
            @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = NativeWinsCircle.class, name = "circle")
    })
    sealed interface NativeWins permits NativeWinsCircle {}

    record NativeWinsCircle(double radius) implements NativeWins {}

    @Test
    void nativeJqTypeWinsOverJsonTypeInfo() {
        NativeWins s = mapper.fromJqValue(
                JqValues.parse("{\"kind\":\"NativeWinsCircle\",\"radius\":1.5}"), NativeWins.class);
        assertEquals(new NativeWinsCircle(1.5), s);
        assertTrue(mapper.toJqValue(s).toJsonString().contains("\"kind\":\"NativeWinsCircle\""));
    }

    @com.fasterxml.jackson.annotation.JsonTypeInfo(
            use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME,
            include = com.fasterxml.jackson.annotation.JsonTypeInfo.As.WRAPPER_OBJECT)
    @com.fasterxml.jackson.annotation.JsonSubTypes({
            @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = WrappedCircle.class, name = "circle")
    })
    interface WrappedShape {}

    record WrappedCircle(double radius) implements WrappedShape {}

    @Test
    void unsupportedTypeInfoFallsBackToUnannotated() {
        // WRAPPER_OBJECT is out of the supported subset: behaves as if
        // unannotated (interfaces cannot bind → fail fast as today).
        assertThrows(io.hyperfoil.tools.jjq.mapper.JqMapperException.class, () -> mapper.fromJqValue(
                JqValues.parse("{\"circle\":{\"radius\":1.5}}"), WrappedShape.class));
    }

    // ---- @JsonCreator on records and POJOs ----

    record JackUserId(String value) {
        @JsonCreator
        public static JackUserId parse(String value) {
            return new JackUserId(value);
        }
    }

    record JackUser(String name, int age) {
        @JsonCreator
        public static JackUser create(@JsonProperty("user_name") String name,
                                      @JsonProperty("age") int age) {
            return new JackUser(name, age);
        }
    }

    static class JackPojo {
        final String name;

        @JsonCreator
        public JackPojo(@JsonProperty("name") String name) { this.name = name; }

        public String getName() { return name; }
    }

    @Test
    void jsonCreatorDelegatingBindsScalar() {
        assertEquals(new JackUserId("abc"),
                mapper.fromJqValue(JqValues.parse("\"abc\""), JackUserId.class));
    }

    @Test
    void jsonCreatorPropertiesBindRenamed() {
        assertEquals(new JackUser("Ann", 3), mapper.fromJqValue(
                JqValues.parse("{\"user_name\":\"Ann\",\"age\":3}"), JackUser.class));
    }

    @Test
    void jsonCreatorConstructorBindsPojo() {
        JackPojo p = mapper.fromJqValue(JqValues.parse("{\"name\":\"x\"}"), JackPojo.class);
        assertEquals("x", p.name);
        assertEquals("{\"name\":\"x\"}", mapper.toJqValue(p).toJsonString());
    }

    @Test
    void jsonPropertyRequiredFailsOnMissing() {
        record Req(@JsonProperty(value = "name", required = true) String name, int age) {}
        io.hyperfoil.tools.jjq.mapper.JqMapperException e = assertThrows(
                io.hyperfoil.tools.jjq.mapper.JqMapperException.class,
                () -> mapper.fromJqValue(JqValues.parse("{\"age\":1}"), Req.class));
        assertTrue(e.getMessage().contains("\"name\""), e.getMessage());
        assertEquals(new Req("Ann", 3), mapper.fromJqValue(
                JqValues.parse("{\"name\":\"Ann\",\"age\":3}"), Req.class));
    }

    // ---- @JsonIdentityInfo (property generators) ----

    @JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
    record JNode(String id, int v) {}

    record JPair(JNode a, JNode b) {}

    @JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class)
    record AtNode(int v) {}

    record AtPair(AtNode a, AtNode b) {}

    @JsonIdentityInfo(generator = ObjectIdGenerators.IntSequenceGenerator.class, property = "id")
    record SeqNode(String id, int v) {}

    @Test
    void identityPropertyGeneratorBinds() {
        JPair p = mapper.fromJqValue(JqValues.parse(
                "{\"a\":{\"id\":\"n1\",\"v\":1},\"b\":\"n1\"}"), JPair.class);
        assertEquals(1, p.a().v());
        assertSame(p.a(), p.b());
        assertEquals("{\"a\":{\"id\":\"n1\",\"v\":1},\"b\":\"n1\"}",
                mapper.toJqValue(p).toJsonString());
    }

    @Test
    void identityDefaultProperty() {
        // No property member: Jackson default "@id" is used faithfully.
        // The key need not be a bound field (deser-only shape here).
        AtPair p = mapper.fromJqValue(JqValues.parse(
                "{\"a\":{\"@id\":\"x\",\"v\":1},\"b\":{\"@id\":\"x\",\"v\":1}}"), AtPair.class);
        assertEquals(1, p.a().v());
        assertSame(p.a(), p.b());
    }

    @Test
    void identityNonPropertyGeneratorDeclined() {
        // IntSequenceGenerator is out of scope: behaves as unannotated
        // (repeated full objects rebind; scalar refs mismatch as before).
        SeqNode first = mapper.fromJqValue(JqValues.parse("{\"id\":\"s1\",\"v\":1}"), SeqNode.class);
        SeqNode second = mapper.fromJqValue(JqValues.parse("{\"id\":\"s1\",\"v\":1}"), SeqNode.class);
        assertEquals(first, second);
        assertNotSame(first, second);
        assertThrows(io.hyperfoil.tools.jjq.mapper.ShapeMismatchException.class, () -> mapper.fromJqValue(
                JqValues.parse("{\"id\":\"s1\",\"v\":1}").getField("id"), SeqNode.class));
    }

    // ---- @JsonNaming / @JsonAlias / @JsonPropertyOrder ----

    @com.fasterxml.jackson.databind.annotation.JsonNaming(
            com.fasterxml.jackson.databind.PropertyNamingStrategies.KebabCaseStrategy.class)
    record KebabBean(String hostPaths, int maxRetries) {}

    record AliasedBean(@JsonAlias({"host_name", "hostname"}) String hostName, int port) {}

    @JsonPropertyOrder({"id", "name"})
    record OrderedBean(String name, String id, int age) {}

    @Test
    void jsonNamingKebabBinds() {
        KebabBean b = mapper.fromJqValue(
                JqValues.parse("{\"host-paths\":\"a\",\"max-retries\":3}"), KebabBean.class);
        assertEquals("a", b.hostPaths());
        assertEquals(3, b.maxRetries());
        assertEquals("{\"host-paths\":\"a\",\"max-retries\":3}",
                mapper.toJqValue(b).toJsonString());
    }

    @Test
    void jsonAliasBindsButSerializesPrimary() {
        assertEquals("x", mapper.fromJqValue(
                JqValues.parse("{\"host_name\":\"x\",\"port\":1}"), AliasedBean.class).hostName());
        assertEquals("{\"hostName\":\"x\",\"port\":1}",
                mapper.toJqValue(new AliasedBean("x", 1)).toJsonString());
    }

    @Test
    void jsonPropertyOrderSerializesFirst() {
        assertEquals("{\"id\":\"1\",\"name\":\"a\",\"age\":3}",
                mapper.toJqValue(new OrderedBean("a", "1", 3)).toJsonString());
        // Deser unaffected
        assertEquals(new OrderedBean("a", "1", 3), mapper.fromJqValue(
                JqValues.parse("{\"age\":3,\"name\":\"a\",\"id\":\"1\"}"), OrderedBean.class));
    }

    // ---- @JsonFormat ----

    record FormattedBean(
            @JsonFormat(pattern = "dd/MM/yyyy") java.time.LocalDate day,
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "UTC") java.time.Instant instant) {
    }

    @Test
    void jsonFormatBindsPatterns() {
        FormattedBean b = mapper.fromJqValue(
                JqValues.parse("{\"day\":\"14/10/2026\",\"instant\":\"2026-10-14 12:34:56\"}"),
                FormattedBean.class);
        assertEquals(java.time.LocalDate.parse("2026-10-14"), b.day());
        assertEquals(java.time.Instant.parse("2026-10-14T12:34:56Z"), b.instant());
        assertEquals("{\"day\":\"14/10/2026\",\"instant\":\"2026-10-14 12:34:56\"}",
                mapper.toJqValue(b).toJsonString());
    }

    @Test
    void jsonFormatShapeMismatchFailsFast() {
        record Shaped(@JsonFormat(shape = JsonFormat.Shape.NUMBER, pattern = "yyyy") java.time.LocalDate day) {
        }
        assertThrows(io.hyperfoil.tools.jjq.mapper.JqMapperException.class, () ->
                mapper.fromJqValue(JqValues.parse("{\"day\":\"2026\"}"), Shaped.class));
    }

    // ---- @JsonFormat shapes on numbers/booleans (#6) ----

    record JShaped(@JsonFormat(shape = JsonFormat.Shape.STRING) int i,
                   @JsonFormat(shape = JsonFormat.Shape.STRING) double d,
                   @JsonFormat(shape = JsonFormat.Shape.STRING) boolean flag) {}

    @Test
    void jsonFormatShapeStringQuotes() {
        assertEquals("{\"i\":\"42\",\"d\":\"4.5\",\"flag\":\"true\"}",
                mapper.toJqValue(new JShaped(42, 4.5, true)).toJsonString());
        assertEquals(new JShaped(42, 4.5, true), mapper.fromJqValue(
                JqValues.parse("{\"i\":\"42\",\"d\":\"4.5\",\"flag\":\"true\"}"), JShaped.class));
        assertEquals(new JShaped(42, 4.5, true), mapper.fromJqValue(
                JqValues.parse("{\"i\":42,\"d\":4.5,\"flag\":true}"), JShaped.class));
    }

    record JNumNoop(@JsonFormat(shape = JsonFormat.Shape.NUMBER) int i,
                    @JsonFormat(shape = JsonFormat.Shape.NUMBER) String s) {}

    @Test
    void jsonFormatShapeNumberNoop() {
        assertEquals("{\"i\":42,\"s\":\"x\"}",
                mapper.toJqValue(new JNumNoop(42, "x")).toJsonString());
    }

    record JBoolNum(@JsonFormat(shape = JsonFormat.Shape.NUMBER) boolean flag) {}

    @Test
    void jsonFormatShapeNumberOnBooleanFails() {
        io.hyperfoil.tools.jjq.mapper.JqMapperException e = assertThrows(
                io.hyperfoil.tools.jjq.mapper.JqMapperException.class, () ->
                        mapper.fromJqValue(JqValues.parse("{\"flag\":true}"), JBoolNum.class));
        assertFalse(e instanceof io.hyperfoil.tools.jjq.mapper.ShapeMismatchException,
                e.getClass().getName());
    }

    record JArrShape(@JsonFormat(shape = JsonFormat.Shape.ARRAY) int i) {}

    @Test
    void jsonFormatArrayShapeNoop() {
        // Jackson ignores non-STRING/NUMBER shapes on numbers; so do we.
        assertEquals("{\"i\":42}", mapper.toJqValue(new JArrShape(42)).toJsonString());
        assertEquals(new JArrShape(42),
                mapper.fromJqValue(JqValues.parse("{\"i\":42}"), JArrShape.class));
    }

    record JMootPattern(@JsonFormat(pattern = "0.00", shape = JsonFormat.Shape.STRING) double d) {}
    record JMootPatternBare(@JsonFormat(pattern = "0.00") double d) {}

    @Test
    void jsonFormatPatternOnNumberMoot() {
        // Jackson ignores DecimalFormat patterns on serialization; the shape
        // still applies and binding stays lenient — no fail-fast.
        assertEquals("{\"d\":\"4.5\"}",
                mapper.toJqValue(new JMootPattern(4.5)).toJsonString());
        assertEquals(new JMootPattern(4.5), mapper.fromJqValue(
                JqValues.parse("{\"d\":\"4.50\"}"), JMootPattern.class));
        assertEquals("{\"d\":4.5}",
                mapper.toJqValue(new JMootPatternBare(4.5)).toJsonString());
    }

    // ---- Jackson annotations on accessors (#1) ----
    // Jackson allows annotations on field, getter, setter, or (for creators)
    // parameters. Field placement wins; accessors are the fallback.

    static class GetterPlaced {
        private String name;
        private int age;

        public GetterPlaced() {}

        @JsonProperty("full_name")
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }
    }

    @Test
    void jsonPropertyOnGetter_renamesBothDirections() {
        GetterPlaced p = mapper.fromJqValue(
                JqValues.parse("{\"full_name\":\"Ann\",\"age\":3}"), GetterPlaced.class);
        assertEquals("Ann", p.getName());
        assertEquals(3, p.getAge());
        assertEquals("{\"full_name\":\"Ann\",\"age\":3}",
                mapper.toJqValue(p).toJsonString());
    }

    static class SetterPlaced {
        private String name;

        public SetterPlaced() {}

        public String getName() { return name; }
        @JsonProperty("full_name")
        public void setName(String name) { this.name = name; }
    }

    @Test
    void jsonPropertyOnSetter_renamesBothDirections() {
        SetterPlaced p = mapper.fromJqValue(
                JqValues.parse("{\"full_name\":\"Bo\"}"), SetterPlaced.class);
        assertEquals("Bo", p.getName());
        assertEquals("{\"full_name\":\"Bo\"}", mapper.toJqValue(p).toJsonString());
    }

    static class FieldBeatsAccessor {
        @JsonProperty("from_field")
        private String name;

        public FieldBeatsAccessor() {}

        @JsonProperty("from_getter")
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @Test
    void jsonPropertyFieldWinsOverAccessor() {
        FieldBeatsAccessor p = mapper.fromJqValue(
                JqValues.parse("{\"from_field\":\"x\"}"), FieldBeatsAccessor.class);
        assertEquals("x", p.getName());
        assertEquals("{\"from_field\":\"x\"}", mapper.toJqValue(p).toJsonString());
    }

    static class GetterIncluded {
        private String data;
        private String error;

        public GetterIncluded() {}

        public String getData() { return data; }
        public void setData(String data) { this.data = data; }
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }

    @Test
    void jsonIncludeOnGetter_filtersNull() {
        GetterIncluded p = new GetterIncluded();
        p.setData("hello");
        assertEquals("{\"data\":\"hello\"}", mapper.toJqValue(p).toJsonString());
    }

    static class GetterFormatted {
        private java.time.LocalDate day;

        public GetterFormatted() {}

        @JsonFormat(pattern = "dd/MM/yyyy")
        public java.time.LocalDate getDay() { return day; }
        public void setDay(java.time.LocalDate day) { this.day = day; }
    }

    @Test
    void jsonFormatOnGetter_bindsPattern() {
        GetterFormatted p = mapper.fromJqValue(
                JqValues.parse("{\"day\":\"14/10/2026\"}"), GetterFormatted.class);
        assertEquals(java.time.LocalDate.parse("2026-10-14"), p.getDay());
        assertEquals("{\"day\":\"14/10/2026\"}", mapper.toJqValue(p).toJsonString());
    }

    static class GetterAliased {
        private String hostName;
        private int port;

        public GetterAliased() {}

        @JsonAlias("host_name")
        public String getHostName() { return hostName; }
        public void setHostName(String hostName) { this.hostName = hostName; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }

    @Test
    void jsonAliasOnGetter_bindsButSerializesPrimary() {
        assertEquals("x", mapper.fromJqValue(
                JqValues.parse("{\"host_name\":\"x\",\"port\":1}"), GetterAliased.class).getHostName());
        GetterAliased p = new GetterAliased();
        p.setHostName("x");
        p.setPort(1);
        assertEquals("{\"hostName\":\"x\",\"port\":1}", mapper.toJqValue(p).toJsonString());
    }

    static class GetterRequired {
        private String name;

        public GetterRequired() {}

        @JsonProperty(value = "name", required = true)
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @Test
    void jsonPropertyRequiredOnGetter_failsOnMissing() {
        io.hyperfoil.tools.jjq.mapper.JqMapperException e = assertThrows(
                io.hyperfoil.tools.jjq.mapper.JqMapperException.class,
                () -> mapper.fromJqValue(JqValues.parse("{}"), GetterRequired.class));
        assertTrue(e.getMessage().contains("\"name\""), e.getMessage());
    }

    static class GetterAccess {
        private String name;
        private String secret;

        public GetterAccess() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        @JsonProperty(value = "secret", access = JsonProperty.Access.WRITE_ONLY)
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
    }

    @Test
    void jsonPropertyAccessOnGetter_bindsDeserSkipsSer() {
        GetterAccess p = mapper.fromJqValue(
                JqValues.parse("{\"name\":\"t\",\"secret\":\"s\"}"), GetterAccess.class);
        assertEquals("s", p.getSecret());
        assertEquals("{\"name\":\"t\"}", mapper.toJqValue(p).toJsonString());
    }

    record ShapedGetter(@JsonFormat(shape = JsonFormat.Shape.STRING) int i) {}

    static class GetterShaped {
        private int i;

        public GetterShaped() {}

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        public int getI() { return i; }
        public void setI(int i) { this.i = i; }
    }

    @Test
    void jsonFormatShapeOnGetter_quotes() {
        // Record components carry Jackson annotations on the field, so the
        // record form pins the field path; the POJO form pins the fallback.
        assertEquals("{\"i\":\"42\"}", mapper.toJqValue(new ShapedGetter(42)).toJsonString());
        GetterShaped p = new GetterShaped();
        p.setI(42);
        assertEquals("{\"i\":\"42\"}", mapper.toJqValue(p).toJsonString());
    }

    // ---- @JsonDeserialize(builder) + @JsonPOJOBuilder ----

    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(builder = JackBean.JackBeanBuilder.class)
    static class JackBean {
        String name;
        int age;

        public String getName() { return name; }
        public int getAge() { return age; }

        @com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder(withPrefix = "with")
        public static class JackBeanBuilder {
            private String name;
            private int age = 21;

            public JackBeanBuilder withName(String name) { this.name = name; return this; }
            public JackBeanBuilder withAge(int age) { this.age = age; return this; }

            public JackBean build() {
                JackBean b = new JackBean();
                b.name = name;
                b.age = age;
                return b;
            }
        }
    }

    @Test
    void jsonDeserializeBuilderBinds() {
        JackBean b = mapper.fromJqValue(
                JqValues.parse("{\"name\":\"Ann\",\"age\":3}"), JackBean.class);
        assertEquals("Ann", b.name);
        assertEquals(3, b.age);
        JackBean d = mapper.fromJqValue(JqValues.parse("{\"name\":\"Bo\"}"), JackBean.class);
        assertEquals(21, d.age);
        assertEquals("{\"name\":\"Ann\",\"age\":3}", mapper.toJqValue(b).toJsonString());
    }
}

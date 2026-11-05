package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.CharOutput;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BufferedJsonCompatibilityTest {
    record WireValue(String name, int age) {}
    record EncodedEnvelope(String label, WireValue value) {}
    record NativeEnvelope(WireValue value) {}

    private static class LegacyWriter extends GeneratedMapping<WireValue> {
        @Override
        public Class<WireValue> type() {
            return WireValue.class;
        }

        @Override
        public WireValue fromJqValue(JqValue input, JqMapper mapper) {
            return new WireValue(input.getField("name").stringValue(), (int) input.getField("age").longValue());
        }

        @Override
        public JqValue toJqValue(WireValue value, JqMapper mapper) {
            return JqObject.builder(2).put("name", value.name()).put("age", value.age()).build();
        }

        @Override
        public void appendJson(WireValue value, StringBuilder out, JqMapper mapper) {
            out.append("{\"age\":");
            if (value.age() < 0) throw new IllegalArgumentException("negative age");
            out.append(value.age()).append(",\"name\":");
            JqValues.appendJsonString(out, value.name());
            out.append('}');
        }
    }

    private static class NativeWriter extends GeneratedMapping<NativeEnvelope> {
        @Override
        protected boolean usesBufferedJsonWriter() {
            return true;
        }

        @Override
        public Class<NativeEnvelope> type() {
            return NativeEnvelope.class;
        }

        @Override
        public NativeEnvelope fromJqValue(JqValue input, JqMapper mapper) {
            return new NativeEnvelope(mapper.fromJqValue(input.getField("value"), WireValue.class));
        }

        @Override
        public JqValue toJqValue(NativeEnvelope value, JqMapper mapper) {
            return JqObject.builder(1).put("value", mapper.toJqValue(value.value())).build();
        }

        @Override
        public void appendJson(NativeEnvelope value, StringBuilder out, JqMapper mapper) {
            throw new UnsupportedOperationException("legacy writer must not be selected");
        }

        @Override
        public void appendJson(NativeEnvelope value, CharOutput out, JqMapper mapper) {
            out.append("{\"value\":");
            mapper.appendJson(value.value(), out);
            out.append('}');
        }
    }

    @Test
    void defaultSelectsOptedInWriterAndPreservesNestedLegacyHooks() {
        JqMapper mapper = JqMapper.builder().register(new LegacyWriter()).register(new NativeWriter()).build();
        NativeEnvelope value = new NativeEnvelope(new WireValue("Ann", 3));
        String expected = "{\"value\":{\"age\":3,\"name\":\"Ann\"}}";
        assertEquals(expected, mapper.toJson(value));
        var error = assertThrows(IllegalArgumentException.class,
                () -> mapper.toJson(new NativeEnvelope(new WireValue("bad", -1))));
        assertEquals("negative age", error.getMessage());
        assertEquals(expected, mapper.toJson(value));
        assertThrows(UnsupportedOperationException.class, () -> mapper.appendJson(value, new StringBuilder()));
    }

    @Test
    void unclaimedCharOverloadDoesNotReplaceLegacyWriter() {
        LegacyWriter legacy = new LegacyWriter() {
            @Override
            public void appendJson(WireValue value, CharOutput out, JqMapper mapper) {
                out.append("{\"unselected\":true}");
            }
        };
        JqMapper mapper = JqMapper.builder().register(legacy).build();
        WireValue value = new WireValue("Ann", 3);
        String expected = "{\"age\":3,\"name\":\"Ann\"}";
        assertEquals(expected, mapper.toJson(value));
        assertEquals(expected, mapper.toJsonBuffered(value));
        assertThrows(IllegalArgumentException.class,
                () -> mapper.toJsonBuffered(new WireValue("bad", -1)));
    }

    @Test
    void bufferedOutputPreservesLegacyWriterAndRecoversAfterFailure() {
        JqMapper mapper = JqMapper.builder().register(new LegacyWriter()).build();
        WireValue value = new WireValue("Ann", 3);
        String expected = "{\"age\":3,\"name\":\"Ann\"}";
        assertEquals(expected, mapper.toJsonBuffered(value));
        var error = assertThrows(IllegalArgumentException.class,
                () -> mapper.toJsonBuffered(new WireValue("bad", -1)));
        assertEquals("negative age", error.getMessage());
        assertEquals(expected, mapper.toJsonBuffered(value));
    }

    @Test
    void reentrantLegacyWritersDoNotShareActiveScratch() {
        GeneratedMapping<EncodedEnvelope> envelope = new GeneratedMapping<>() {
            @Override
            public Class<EncodedEnvelope> type() {
                return EncodedEnvelope.class;
            }

            @Override
            public EncodedEnvelope fromJqValue(JqValue input, JqMapper mapper) {
                return new EncodedEnvelope(input.getField("label").stringValue(),
                        mapper.fromJqValue(JqValues.parse(input.getField("encoded").stringValue()), WireValue.class));
            }

            @Override
            public JqValue toJqValue(EncodedEnvelope value, JqMapper mapper) {
                return JqObject.builder(2).put("label", value.label())
                        .put("encoded", mapper.toJson(value.value())).build();
            }

            @Override
            public void appendJson(EncodedEnvelope value, StringBuilder out, JqMapper mapper) {
                out.append("{\"label\":");
                JqValues.appendJsonString(out, value.label());
                out.append(",\"encoded\":");
                JqValues.appendJsonString(out, mapper.toJsonBuffered(value.value()));
                out.append('}');
            }
        };
        JqMapper mapper = JqMapper.builder().register(new LegacyWriter()).register(envelope).build();
        mapper.toJsonBuffered(new WireValue("prime", 0));
        EncodedEnvelope value = new EncodedEnvelope("outer", new WireValue("inner", 1));
        String expected = "{\"label\":\"outer\",\"encoded\":\"{\\\"age\\\":1,\\\"name\\\":\\\"inner\\\"}\"}";
        String retained = mapper.toJsonBuffered(value);
        assertEquals(expected, retained);
        assertEquals(expected, mapper.toJsonBuffered(value));
        assertEquals(expected, retained);
    }

    @Test
    void oversizedLegacyOutputDoesNotPolluteTheNextCall() {
        JqMapper mapper = JqMapper.builder().register(new LegacyWriter()).build();
        WireValue large = new WireValue("x".repeat(2 * 1024 * 1024), 1);
        StringBuilder expected = new StringBuilder();
        mapper.appendJson(large, expected);
        String retained = mapper.toJsonBuffered(large);
        assertEquals(expected.toString(), retained);
        assertEquals("{\"age\":2,\"name\":\"small\"}", mapper.toJsonBuffered(new WireValue("small", 2)));
        assertEquals(expected.toString(), retained);
    }

    @Test
    void legacyScratchIsIndependentAcrossThreads() throws Exception {
        JqMapper mapper = JqMapper.builder().register(new LegacyWriter()).build();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var left = pool.submit(() -> {
                for (int i = 0; i < 100; i++) {
                    assertEquals("{\"age\":1,\"name\":\"left\"}", mapper.toJsonBuffered(new WireValue("left", 1)));
                }
                return mapper.toJsonBuffered(new WireValue("left", 1));
            });
            var right = pool.submit(() -> {
                for (int i = 0; i < 100; i++) {
                    assertEquals("{\"age\":2,\"name\":\"right\"}", mapper.toJsonBuffered(new WireValue("right", 2)));
                }
                return mapper.toJsonBuffered(new WireValue("right", 2));
            });
            assertEquals("{\"age\":1,\"name\":\"left\"}", left.get());
            assertEquals("{\"age\":2,\"name\":\"right\"}", right.get());
        }
    }
}

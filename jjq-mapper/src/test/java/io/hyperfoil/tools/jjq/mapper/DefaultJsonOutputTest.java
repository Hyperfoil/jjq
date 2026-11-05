package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultJsonOutputTest {
    record WireValue(String name, int age) {}

    @Test
    void defaultJsonPreservesRegisteredStringBuilderWriter() {
        GeneratedMapping<WireValue> mapping = new GeneratedMapping<>() {
            @Override
            public Class<WireValue> type() {
                return WireValue.class;
            }

            @Override
            public WireValue fromJqValue(JqValue input, JqMapper mapper) {
                return new WireValue(input.getField("name").stringValue(),
                        (int) input.getField("age").longValue());
            }

            @Override
            public JqValue toJqValue(WireValue value, JqMapper mapper) {
                return JqObject.builder(2).put("name", value.name()).put("age", value.age()).build();
            }

            @Override
            public void appendJson(WireValue value, StringBuilder out, JqMapper mapper) {
                if (value.age() < 0) throw new IllegalArgumentException("negative age");
                out.append("{\"age\":").append(value.age()).append(",\"name\":");
                JqValues.appendJsonString(out, value.name());
                out.append('}');
            }
        };
        JqMapper mapper = JqMapper.builder().register(mapping).build();
        WireValue value = new WireValue("Ann", 3);
        String expected = "{\"age\":3,\"name\":\"Ann\"}";
        StringBuilder direct = new StringBuilder();
        mapper.appendJson(value, direct);
        assertEquals(expected, direct.toString());
        var error = assertThrows(IllegalArgumentException.class,
                () -> mapper.toJson(new WireValue("Ann", -1)));
        assertEquals("negative age", error.getMessage());
        assertEquals(expected, mapper.toJson(value));
    }
}

package io.hyperfoil.tools.jjq.yaml;

import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.mapper.TypeToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class YamlMapperTest {

    record ServerConfig(String host, int port) {}

    private static YamlMapper mapper() {
        return new YamlMapper(JqMapper.create());
    }

    @Test
    void readValueFromString() throws Exception {
        ServerConfig config = mapper().readValue("host: localhost\nport: 8080\n", ServerConfig.class);
        assertEquals("localhost", config.host());
        assertEquals(8080, config.port());
    }

    @Test
    void readValueFromPath(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.yaml");
        Files.writeString(file, "host: h\nport: 1\n");
        ServerConfig config = mapper().readValue(file, ServerConfig.class);
        assertEquals("h", config.host());
        assertEquals(1, config.port());
    }

    @Test
    void readValueFromStream() throws Exception {
        var in = new ByteArrayInputStream("host: h\nport: 1\n".getBytes(StandardCharsets.UTF_8));
        ServerConfig config = mapper().readValue(in, ServerConfig.class);
        assertEquals("h", config.host());
        assertEquals(1, config.port());
    }

    @Test
    void readValueWithGenericType() throws Exception {
        Type listOfStrings = new TypeToken<List<String>>() {}.getType();
        List<String> items = mapper().readValue("- a\n- b\n", listOfStrings);
        assertEquals(List.of("a", "b"), items);
    }

    @Test
    void strictOptionsPropagate() {
        var strict = new YamlMapper(JqMapper.create(), YamlOptions.STRICT);
        IOException e = assertThrows(IOException.class,
                () -> strict.readValue("host: a\nhost: b\n", ServerConfig.class));
        assertInstanceOf(JqYamlException.class, e.getCause());
    }

    record Env(String name, String value) {}

    @Test
    void plainScalarsKeepSourceTextForStringFields() throws Exception {
        // YAML 1.1 resolution types the tree (18, 3.1, true...) but a
        // String target receives the scalar's source text (#126)
        var mapper = new YamlMapper(JqMapper.create(), YamlOptions.STRICT);
        var cases = new String[][]{
                {"0022", "0022"}, {"3.10", "3.10"}, {"yes", "yes"},
                {"0x1F", "0x1F"}, {"1_000", "1_000"}, {"190:20:30", "190:20:30"},
        };
        for (var c : cases) {
            Env env = mapper.readValue("name: X\nvalue: " + c[0] + "\n", Env.class);
            assertEquals(c[1], env.value(), "source text lost for `" + c[0] + "`");
        }
    }

    @Test
    void treeStaysTypedWhenStringsKeepSourceText() {
        // Only binding changes: the parsed tree keeps YAML 1.1 types
        var tree = JqYaml.parse("value: 0022\n");
        assertEquals(18L, tree.getField("value").longValue());
    }

    @Test
    void explicitTagsAndQuotedFormsUnaffected() throws Exception {
        var mapper = new YamlMapper(JqMapper.create(), YamlOptions.STRICT);
        // Explicit tag means typed: renders, not source text (Jackson parity)
        Env tagged = mapper.readValue("name: X\nvalue: !!int 0022\n", Env.class);
        assertEquals("18", tagged.value());
        // Quoted scalars were always strings
        Env quoted = mapper.readValue("name: X\nvalue: \"0022\"\n", Env.class);
        assertEquals("0022", quoted.value());
        // Null stays null for String targets
        Env nulled = mapper.readValue("name: X\nvalue: ~\n", Env.class);
        assertNull(nulled.value());
    }

    @Test
    void shapeMismatchWrapsMapperException() {
        IOException e = assertThrows(IOException.class,
                () -> mapper().readValue("host: [1, 2]\nport: 1\n", ServerConfig.class));
        assertInstanceOf(io.hyperfoil.tools.jjq.mapper.JqMapperException.class, e.getCause());
    }

    @Test
    void writeValueAsStringRoundTrip() throws Exception {
        var mapper = mapper();
        String yaml = mapper.writeValueAsString(new ServerConfig("h", 8080));
        assertEquals(new ServerConfig("h", 8080), mapper.readValue(yaml, ServerConfig.class));
    }

    @Test
    void writeValueToPath(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("out.yaml");
        mapper().writeValue(file, new ServerConfig("h", 1));
        ServerConfig back = mapper().readValue(file, ServerConfig.class);
        assertEquals("h", back.host());
        assertEquals(1, back.port());
    }
}

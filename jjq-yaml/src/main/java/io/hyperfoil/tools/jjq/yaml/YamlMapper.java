package io.hyperfoil.tools.jjq.yaml;

import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Checked-exception databind facade over YAML parsing and {@link JqMapper}.
 *
 * <p>Combines {@link JqYaml} parsing, {@link JqMapper} binding, and YAML
 * emission behind an {@code IOException} contract: parse and bind failures
 * (unchecked {@code JqYamlException}/{@code JqMapperException}) are wrapped
 * with the cause chain preserved, so callers can unwrap causes for friendly
 * error rendering. I/O failures pass through untouched.</p>
 *
 * <pre>{@code
 * YamlMapper mapper = new YamlMapper(JqMapper.create());
 * SpawnConfig config = mapper.readValue(configFile, SpawnConfig.class);
 * mapper.writeValue(configFile, config);
 * }</pre>
 *
 * <p>Requires {@code jjq-mapper} on the classpath. The instance holds the
 * mapper and options and is thread-safe.</p>
 */
public final class YamlMapper {

    private final JqMapper mapper;
    private final YamlOptions options;

    /**
     * Create a facade with lenient parse options (duplicate keys last-wins,
     * mirroring {@link JqYaml#fromYaml} defaults).
     *
     * @param mapper the mapper for binding values
     */
    public YamlMapper(JqMapper mapper) {
        this(mapper, YamlOptions.LENIENT);
    }

    /**
     * Create a facade with explicit parse options (e.g. {@link YamlOptions#STRICT}
     * to reject duplicate mapping keys).
     *
     * @param mapper  the mapper for binding values
     * @param options parse options (null means lenient)
     */
    public YamlMapper(JqMapper mapper, YamlOptions options) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.options = options != null ? options : YamlOptions.LENIENT;
    }

    /**
     * Parse YAML and bind to a Java type.
     *
     * @param yaml the YAML document
     * @param type the target class
     * @param <T>  the target type
     * @return the bound value
     * @throws IOException wrapping any parse or bind failure (cause preserved)
     */
    public <T> T readValue(String yaml, Class<T> type) throws IOException {
        try {
            return mapper.fromJqValue(JqYaml.parse(yaml, options), type);
        } catch (RuntimeException e) {
            throw readFailure(type.getName(), e);
        }
    }

    /**
     * Parse YAML and bind using a generic type (e.g. {@code List<Item>} via
     * {@code new TypeToken<List<Item>>(){}.getType()}).
     *
     * @param yaml the YAML document
     * @param type the target generic type
     * @param <T>  the target type
     * @return the bound value
     * @throws IOException wrapping any parse or bind failure (cause preserved)
     */
    public <T> T readValue(String yaml, Type type) throws IOException {
        try {
            return mapper.fromJqValue(JqYaml.parse(yaml, options), type);
        } catch (RuntimeException e) {
            throw readFailure(type.getTypeName(), e);
        }
    }

    /**
     * Read a YAML file and bind to a Java type. File I/O failures pass
     * through unwrapped; only parse/bind failures are wrapped.
     *
     * @param file the YAML file to read (UTF-8)
     * @param type the target class
     * @param <T>  the target type
     * @return the bound value
     * @throws IOException on I/O failure, or wrapping parse/bind failures
     */
    public <T> T readValue(Path file, Class<T> type) throws IOException {
        return readValue(Files.readString(file, StandardCharsets.UTF_8), type);
    }

    /**
     * Read a YAML file and bind using a generic type.
     *
     * @param file the YAML file to read (UTF-8)
     * @param type the target generic type
     * @param <T>  the target type
     * @return the bound value
     * @throws IOException on I/O failure, or wrapping parse/bind failures
     */
    public <T> T readValue(Path file, Type type) throws IOException {
        return readValue(Files.readString(file, StandardCharsets.UTF_8), type);
    }

    /**
     * Read YAML from a stream and bind to a Java type.
     *
     * @param in   the stream to read fully (UTF-8)
     * @param type the target class
     * @param <T>  the target type
     * @return the bound value
     * @throws IOException on I/O failure, or wrapping parse/bind failures
     */
    public <T> T readValue(InputStream in, Class<T> type) throws IOException {
        return readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8), type);
    }

    /**
     * Read YAML from a stream and bind using a generic type.
     *
     * @param in   the stream to read fully (UTF-8)
     * @param type the target generic type
     * @param <T>  the target type
     * @return the bound value
     * @throws IOException on I/O failure, or wrapping parse/bind failures
     */
    public <T> T readValue(InputStream in, Type type) throws IOException {
        return readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8), type);
    }

    /**
     * Serialize a Java value to a YAML string ({@code toJqValue} plus
     * block-style emission). String building cannot fail, so nothing is thrown.
     *
     * @param value the value to serialize (may be a {@link JqValue} already)
     * @return the YAML document
     */
    public String writeValueAsString(Object value) {
        JqValue tree = value instanceof JqValue jv ? jv : mapper.toJqValue(value);
        return JqYaml.toYaml(tree);
    }

    /**
     * Serialize a Java value to a YAML file (UTF-8).
     *
     * @param file  the file to write
     * @param value the value to serialize
     * @throws IOException if writing fails
     */
    public void writeValue(Path file, Object value) throws IOException {
        Files.writeString(file, writeValueAsString(value), StandardCharsets.UTF_8);
    }

    private static IOException readFailure(String target, RuntimeException e) {
        return new IOException("Failed to bind YAML as " + target + ": " + e.getMessage(), e);
    }
}

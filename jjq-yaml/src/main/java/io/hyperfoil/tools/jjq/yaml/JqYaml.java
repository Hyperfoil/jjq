package io.hyperfoil.tools.jjq.yaml;

import io.hyperfoil.tools.jjq.value.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses YAML into jjq {@link JqValue} trees, enabling jq queries over YAML documents.
 *
 * <p>Parses with the native dependency-free byte parser ({@link YamlParser})
 * and emits block-style YAML ({@link YamlEmitter}). Supports single and
 * multi-document YAML streams.</p>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * JqValue config = JqYaml.parse(Files.readString(Path.of("config.yaml")));
 * String version = JqProgram.compile(".java.version").apply(config).stringValue();
 * }</pre>
 *
 * <h2>Multi-document YAML</h2>
 * <pre>{@code
 * // Kubernetes manifests with --- separators
 * List<JqValue> docs = JqYaml.parseAll(yamlString);
 *
 * // Or as a JqArray for jq queries across all documents
 * JqArray all = JqYaml.parseAllAsArray(yamlString);
 * List<JqValue> names = JqProgram.compile(".[].metadata.name").applyAll(all);
 * }</pre>
 *
 * @see io.hyperfoil.tools.jjq.value.JqValues#parse(String)
 */
public final class JqYaml {

    private JqYaml() {}

    /**
     * Parse a single YAML document into a JqValue.
     * If the input contains multiple documents, only the first is returned.
     *
     * @param yaml the YAML string to parse
     * @return the parsed JqValue, or {@link JqNull#NULL} for empty/null documents
     */
    public static JqValue parse(String yaml) {
        return parse(new StringReader(yaml));
    }

    /**
     * Parse a single YAML document with explicit options.
     *
     * @param yaml    the YAML string to parse
     * @param options parse options ({@link YamlOptions#STRICT} rejects duplicate keys)
     * @return the parsed JqValue, or {@link JqNull#NULL} for empty/null documents
     * @throws JqYamlException on duplicate mapping keys in strict mode
     */
    public static JqValue parse(String yaml, YamlOptions options) {
        byte[] bytes = yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return YamlParser.parse(bytes, 0, bytes.length, options);
    }

    /**
     * Parse a single YAML document from an InputStream.
     *
     * @param in the InputStream to read YAML from
     * @return the parsed JqValue, or {@link JqNull#NULL} for empty/null documents
     */
    public static JqValue parse(InputStream in) {
        return parse(in, YamlOptions.LENIENT);
    }

    /**
     * Parse a single YAML document from an InputStream with explicit options.
     *
     * @param in      the InputStream to read YAML from
     * @param options parse options ({@link YamlOptions#STRICT} rejects duplicate keys)
     * @return the parsed JqValue, or {@link JqNull#NULL} for empty/null documents
     * @throws JqYamlException on duplicate mapping keys in strict mode
     */
    public static JqValue parse(InputStream in, YamlOptions options) {
        try {
            byte[] bytes = in.readAllBytes();
            return YamlParser.parse(bytes, 0, bytes.length, options);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * Parse a single YAML document from a Reader.
     *
     * @param reader the Reader to read YAML from
     * @return the parsed JqValue, or {@link JqNull#NULL} for empty/null documents
     */
    public static JqValue parse(Reader reader) {
        return parse(reader, YamlOptions.LENIENT);
    }

    /**
     * Parse a single YAML document from a Reader with explicit options.
     *
     * @param reader  the Reader to read YAML from
     * @param options parse options ({@link YamlOptions#STRICT} rejects duplicate keys)
     * @return the parsed JqValue, or {@link JqNull#NULL} for empty/null documents
     * @throws JqYamlException on duplicate mapping keys in strict mode
     */
    public static JqValue parse(Reader reader, YamlOptions options) {
        try {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) != -1) sb.append(buf, 0, n);
            return parse(sb.toString(), options);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * Parse all YAML documents from a multi-document stream ({@code ---} separated).
     * Returns a list of JqValues, one per document.
     *
     * @param yaml the YAML string containing one or more documents
     * @return list of parsed JqValues (empty list for empty input)
     */
    public static List<JqValue> parseAll(String yaml) {
        return parseAll(new StringReader(yaml));
    }

    /**
     * Parse all YAML documents from a multi-document stream with explicit options.
     *
     * @param yaml    the YAML string containing one or more documents
     * @param options parse options ({@link YamlOptions#STRICT} rejects duplicate keys)
     * @return list of parsed JqValues (empty list for empty input)
     * @throws JqYamlException on duplicate mapping keys in strict mode
     */
    public static List<JqValue> parseAll(String yaml, YamlOptions options) {
        byte[] bytes = yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return YamlParser.parseAll(bytes, 0, bytes.length, options);
    }

    /**
     * Parse all YAML documents from an InputStream.
     *
     * @param in the InputStream to read YAML from
     * @return list of parsed JqValues (empty list for empty input)
     */
    public static List<JqValue> parseAll(InputStream in) {
        return parseAll(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Parse all YAML documents from an InputStream with explicit options.
     *
     * @param in      the InputStream to read YAML from
     * @param options parse options ({@link YamlOptions#STRICT} rejects duplicate keys)
     * @return list of parsed JqValues (empty list for empty input)
     * @throws JqYamlException on duplicate mapping keys in strict mode
     */
    public static List<JqValue> parseAll(InputStream in, YamlOptions options) {
        try {
            byte[] bytes = in.readAllBytes();
            return YamlParser.parseAll(bytes, 0, bytes.length, options);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static List<JqValue> parseAll(Reader reader) {
        return parseAll(reader, YamlOptions.LENIENT);
    }

    private static List<JqValue> parseAll(Reader reader, YamlOptions options) {
        try {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) != -1) sb.append(buf, 0, n);
            return parseAll(sb.toString(), options);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * Parse all YAML documents from a multi-document stream as a {@link JqArray}.
     * Enables jq queries across all documents, e.g.,
     * {@code JqProgram.compile(".[].metadata.name").applyAll(array)}.
     *
     * @param yaml the YAML string containing one or more documents
     * @return a JqArray containing one element per document
     */
    public static JqArray parseAllAsArray(String yaml) {
        return JqArray.ofTrusted(parseAll(yaml));
    }

    /**
     * Parse all YAML documents from a multi-document stream as a {@link JqArray},
     * with explicit options.
     *
     * @param yaml    the YAML string containing one or more documents
     * @param options parse options ({@link YamlOptions#STRICT} rejects duplicate keys)
     * @return a JqArray containing one element per document
     * @throws JqYamlException on duplicate mapping keys in strict mode
     */
    public static JqArray parseAllAsArray(String yaml, YamlOptions options) {
        return JqArray.ofTrusted(parseAll(yaml, options));
    }

    /**
     * Parse all YAML documents from an InputStream as a {@link JqArray}.
     *
     * @param in the InputStream to read YAML from
     * @return a JqArray containing one element per document
     */
    public static JqArray parseAllAsArray(InputStream in) {
        return JqArray.ofTrusted(parseAll(in));
    }

    /**
     * Parse all YAML documents from an InputStream as a {@link JqArray},
     * with explicit options.
     *
     * @param in      the InputStream to read YAML from
     * @param options parse options ({@link YamlOptions#STRICT} rejects duplicate keys)
     * @return a JqArray containing one element per document
     * @throws JqYamlException on duplicate mapping keys in strict mode
     */
    public static JqArray parseAllAsArray(InputStream in, YamlOptions options) {
        return JqArray.ofTrusted(parseAll(in, options));
    }

    /**
     * Serialize a JqValue to a YAML string using block style.
     *
     * @param value the JqValue to serialize
     * @return the YAML string
     */
    public static String toYaml(JqValue value) {
        return YamlEmitter.emit(value);
    }

    /**
     * Serialize a JqValue to YAML, writing to the given OutputStream.
     *
     * @param value the JqValue to serialize
     * @param out   the OutputStream to write YAML to
     * @throws IOException if writing fails
     */
    public static void toYaml(JqValue value, OutputStream out) throws IOException {
        OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        toYaml(value, writer);
        writer.flush();
    }

    /**
     * Serialize a JqValue to YAML, writing to the given Writer.
     *
     * @param value  the JqValue to serialize
     * @param writer the Writer to write YAML to
     */
    public static void toYaml(JqValue value, Writer writer) {
        try {
            writer.write(YamlEmitter.emit(value));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ========================================================================
    //  YAML → Java object mapping (requires jjq-mapper on the classpath)
    // ========================================================================

    /**
     * Parse YAML and map to a Java record or POJO.
     * Combines {@link #parse(String)} with {@link io.hyperfoil.tools.jjq.mapper.JqMapper#fromJqValue}.
     *
     * <p>Requires {@code jjq-mapper} on the classpath. The mapper uses
     * {@code @JqField}, {@code @JqIgnore}, and annotation bridges
     * ({@code @JsonProperty}, {@code @JsonbProperty}) for field mapping.</p>
     *
     * <pre>{@code
     * SpawnConfig config = JqYaml.fromYaml(yamlString, mapper, SpawnConfig.class);
     * }</pre>
     *
     * @param yaml   the YAML string to parse
     * @param mapper the JqMapper to use for deserialization
     * @param type   the target class
     * @param <T>    the target type
     * @return a new instance populated from the YAML
     */
    public static <T> T fromYaml(String yaml, io.hyperfoil.tools.jjq.mapper.JqMapper mapper, Class<T> type) {
        return fromYaml(yaml, mapper, type, YamlOptions.LENIENT);
    }

    /**
     * Parse YAML and map to a Java record or POJO, with explicit options.
     *
     * @param yaml    the YAML string to parse
     * @param mapper  the JqMapper to use for deserialization
     * @param type    the target class
     * @param options parse options ({@link YamlOptions#STRICT} rejects duplicate keys)
     * @param <T>     the target type
     * @return a new instance populated from the YAML
     * @throws JqYamlException on duplicate mapping keys in strict mode
     */
    public static <T> T fromYaml(String yaml, io.hyperfoil.tools.jjq.mapper.JqMapper mapper, Class<T> type,
                                 YamlOptions options) {
        return mapper.fromJqValue(parse(yaml, options), type);
    }

    /**
     * Parse YAML from an InputStream and map to a Java record or POJO.
     *
     * @param in     the InputStream to read YAML from
     * @param mapper the JqMapper to use for deserialization
     * @param type   the target class
     * @param <T>    the target type
     * @return a new instance populated from the YAML
     */
    public static <T> T fromYaml(InputStream in, io.hyperfoil.tools.jjq.mapper.JqMapper mapper, Class<T> type) {
        return fromYaml(in, mapper, type, YamlOptions.LENIENT);
    }

    /**
     * Parse YAML from an InputStream and map to a Java record or POJO, with explicit options.
     *
     * @param in      the InputStream to read YAML from
     * @param mapper  the JqMapper to use for deserialization
     * @param type    the target class
     * @param options parse options ({@link YamlOptions#STRICT} rejects duplicate keys)
     * @param <T>     the target type
     * @return a new instance populated from the YAML
     * @throws JqYamlException on duplicate mapping keys in strict mode
     */
    public static <T> T fromYaml(InputStream in, io.hyperfoil.tools.jjq.mapper.JqMapper mapper, Class<T> type,
                                 YamlOptions options) {
        return mapper.fromJqValue(parse(in, options), type);
    }

    /**
     * Parse YAML and map using a generic type (e.g., {@code List<Item>}).
     *
     * <pre>{@code
     * Type listOfItems = new TypeToken<List<Item>>(){}.getType();
     * List<Item> items = JqYaml.fromYaml(yamlString, mapper, listOfItems);
     * }</pre>
     *
     * @param yaml   the YAML string to parse
     * @param mapper the JqMapper to use for deserialization
     * @param type   the target generic type
     * @param <T>    the target type
     * @return the deserialized value
     */
    public static <T> T fromYaml(String yaml, io.hyperfoil.tools.jjq.mapper.JqMapper mapper, java.lang.reflect.Type type) {
        return fromYaml(yaml, mapper, type, YamlOptions.LENIENT);
    }

    /**
     * Parse YAML and map using a generic type, with explicit options.
     *
     * @param yaml    the YAML string to parse
     * @param mapper  the JqMapper to use for deserialization
     * @param type    the target generic type
     * @param options parse options ({@link YamlOptions#STRICT} rejects duplicate keys)
     * @param <T>     the target type
     * @return the deserialized value
     * @throws JqYamlException on duplicate mapping keys in strict mode
     */
    public static <T> T fromYaml(String yaml, io.hyperfoil.tools.jjq.mapper.JqMapper mapper, java.lang.reflect.Type type,
                                 YamlOptions options) {
        return mapper.fromJqValue(parse(yaml, options), type);
    }

    /**
     * Parse YAML from an InputStream and map using a generic type.
     *
     * @param in     the InputStream to read YAML from
     * @param mapper the JqMapper to use for deserialization
     * @param type   the target generic type
     * @param <T>    the target type
     * @return the deserialized value
     */
    public static <T> T fromYaml(InputStream in, io.hyperfoil.tools.jjq.mapper.JqMapper mapper, java.lang.reflect.Type type) {
        return fromYaml(in, mapper, type, YamlOptions.LENIENT);
    }

    /**
     * Parse YAML from an InputStream and map using a generic type, with explicit options.
     *
     * @param in      the InputStream to read YAML from
     * @param mapper  the JqMapper to use for deserialization
     * @param type    the target generic type
     * @param options parse options ({@link YamlOptions#STRICT} rejects duplicate keys)
     * @param <T>     the target type
     * @return the deserialized value
     * @throws JqYamlException on duplicate mapping keys in strict mode
     */
    public static <T> T fromYaml(InputStream in, io.hyperfoil.tools.jjq.mapper.JqMapper mapper, java.lang.reflect.Type type,
                                 YamlOptions options) {
        return mapper.fromJqValue(parse(in, options), type);
    }
}

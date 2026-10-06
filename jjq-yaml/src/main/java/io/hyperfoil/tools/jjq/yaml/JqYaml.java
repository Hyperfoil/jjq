package io.hyperfoil.tools.jjq.yaml;

import io.hyperfoil.tools.jjq.value.*;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses YAML into jjq {@link JqValue} trees, enabling jq queries over YAML documents.
 *
 * <p>Uses SnakeYAML for parsing, then converts the SnakeYAML node tree into jjq's
 * value types. Supports single and multi-document YAML streams.</p>
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
        return parse(new StringReader(yaml), options);
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
        Yaml snakeYaml = new Yaml();
        Node root = snakeYaml.compose(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        if (root == null) return JqNull.NULL;
        return convertNode(root, options);
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
        Yaml snakeYaml = new Yaml();
        Node root = snakeYaml.compose(reader);
        if (root == null) return JqNull.NULL;
        return convertNode(root, options);
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
        return parseAll(new StringReader(yaml), options);
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
        return parseAll(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8), options);
    }

    private static List<JqValue> parseAll(Reader reader) {
        return parseAll(reader, YamlOptions.LENIENT);
    }

    private static List<JqValue> parseAll(Reader reader, YamlOptions options) {
        Yaml snakeYaml = new Yaml();
        var results = new ArrayList<JqValue>();
        for (Node node : snakeYaml.composeAll(reader)) {
            results.add(convertNode(node, options));
        }
        return results;
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
        return new Yaml(blockOptions()).dump(value.toJavaObject());
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
        new Yaml(blockOptions()).dump(value.toJavaObject(), writer);
    }

    private static DumperOptions blockOptions() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return options;
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

    // ========================================================================
    //  SnakeYAML Node → JqValue conversion
    // ========================================================================

    private static JqValue convertNode(Node node, YamlOptions options) {
        return switch (node) {
            case MappingNode m -> convertMapping(m, options);
            case SequenceNode s -> convertSequence(s, options);
            case ScalarNode sc -> convertScalar(sc);
            default -> JqNull.NULL;
        };
    }

    private static JqObject convertMapping(MappingNode mapping, YamlOptions options) {
        List<NodeTuple> tuples = mapping.getValue();
        // First pass: collect merge keys (<<) to flatten
        var builder = JqObject.builder(tuples.size());
        // SnakeYAML 2.x no longer enforces LoaderOptions allowDuplicateKeys
        // (verified: DuplicateKeyException is unreferenced dead code), so strict
        // detection is done here. Only explicit keys participate: duplicates
        // across << merges are legal YAML (explicit keys override merged ones).
        java.util.Set<String> seen = options.allowDuplicateKeys() ? null : new java.util.HashSet<>();
        for (NodeTuple tuple : tuples) {
            String key = ((ScalarNode) tuple.getKeyNode()).getValue();
            if ("<<".equals(key)) {
                // YAML merge key: flatten the referenced mapping's entries
                Node mergeValue = tuple.getValueNode();
                if (mergeValue instanceof MappingNode mergeMapping) {
                    for (NodeTuple merged : mergeMapping.getValue()) {
                        String mergedKey = ((ScalarNode) merged.getKeyNode()).getValue();
                        builder.put(mergedKey, convertNode(merged.getValueNode(), options));
                    }
                } else if (mergeValue instanceof SequenceNode mergeSeq) {
                    // << can reference a list of mappings
                    for (Node item : mergeSeq.getValue()) {
                        if (item instanceof MappingNode itemMapping) {
                            for (NodeTuple merged : itemMapping.getValue()) {
                                String mergedKey = ((ScalarNode) merged.getKeyNode()).getValue();
                                builder.put(mergedKey, convertNode(merged.getValueNode(), options));
                            }
                        }
                    }
                }
            } else {
                if (seen != null && !seen.add(key)) {
                    // SnakeYAML Mark lines are 0-based; report 1-based
                    int line = tuple.getKeyNode().getStartMark().getLine() + 1;
                    throw new JqYamlException(key, line);
                }
                builder.put(key, convertNode(tuple.getValueNode(), options));
            }
        }
        return (JqObject) builder.build();
    }

    private static JqArray convertSequence(SequenceNode sequence, YamlOptions options) {
        List<Node> children = sequence.getValue();
        JqValue[] elements = new JqValue[children.size()];
        for (int i = 0; i < elements.length; i++) {
            elements[i] = convertNode(children.get(i), options);
        }
        return JqArray.of(elements);
    }

    private static JqValue convertScalar(ScalarNode scalar) {
        Tag tag = scalar.getTag();
        String value = scalar.getValue();

        // Explicit tags (use equals, not ==, since Tag is a class, not an enum)
        if (Tag.NULL.equals(tag)) return JqNull.NULL;
        if (Tag.BOOL.equals(tag)) return JqBoolean.of(isTrueish(value));
        if (Tag.INT.equals(tag)) return convertInteger(value);
        if (Tag.FLOAT.equals(tag)) return convertFloat(value);
        if (Tag.STR.equals(tag)) return JqString.of(value);

        // Untagged — auto-detect based on YAML core schema
        if (value == null || "null".equals(value) || "~".equals(value) || value.isEmpty()) {
            return JqNull.NULL;
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return JqBoolean.of("true".equalsIgnoreCase(value));
        }

        // Try as string (SnakeYAML's resolver handles most tag detection,
        // so reaching here with an unrecognized tag means it's a string)
        return JqString.of(value);
    }

    private static JqValue convertInteger(String value) {
        try {
            // Handle hex (0x), octal (0/0o), and binary (0b) prefixes
            if (value.startsWith("0x") || value.startsWith("0X")) {
                return JqNumber.of(Long.parseLong(value.substring(2), 16));
            }
            if (value.startsWith("0o") || value.startsWith("0O")) {
                return JqNumber.of(Long.parseLong(value.substring(2), 8));
            }
            if (value.startsWith("0b") || value.startsWith("0B")) {
                return JqNumber.of(Long.parseLong(value.substring(2), 2));
            }
            // YAML 1.1 octal: leading 0 (e.g., 077 = 63)
            if (value.startsWith("0") && value.length() > 1 && !value.contains(".")) {
                return JqNumber.of(Long.parseLong(value.substring(1), 8));
            }
            return JqNumber.of(Long.parseLong(value));
        } catch (NumberFormatException e) {
            // Overflow — use BigDecimal
            try {
                return JqNumber.of(new BigDecimal(value));
            } catch (NumberFormatException e2) {
                return JqString.of(value); // fallback
            }
        }
    }

    private static JqValue convertFloat(String value) {
        if (".inf".equals(value) || ".Inf".equals(value) || ".INF".equals(value)) {
            return JqNumber.of(Double.POSITIVE_INFINITY);
        }
        if ("-.inf".equals(value) || "-.Inf".equals(value) || "-.INF".equals(value)) {
            return JqNumber.of(Double.NEGATIVE_INFINITY);
        }
        if (".nan".equals(value) || ".NaN".equals(value) || ".NAN".equals(value)) {
            return JqNumber.of(Double.NaN);
        }
        try {
            return JqNumber.of(Double.parseDouble(value));
        } catch (NumberFormatException e) {
            return JqString.of(value); // fallback
        }
    }

    private static boolean isTrueish(String value) {
        return "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value)
                || "on".equalsIgnoreCase(value) || "y".equalsIgnoreCase(value);
    }
}

package io.hyperfoil.tools.jjq.yaml;

import io.hyperfoil.tools.jjq.value.*;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.*;

import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
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
     * Parse a single YAML document from an InputStream.
     *
     * @param in the InputStream to read YAML from
     * @return the parsed JqValue, or {@link JqNull#NULL} for empty/null documents
     */
    public static JqValue parse(InputStream in) {
        Yaml snakeYaml = new Yaml();
        Node root = snakeYaml.compose(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        if (root == null) return JqNull.NULL;
        return convertNode(root);
    }

    /**
     * Parse a single YAML document from a Reader.
     *
     * @param reader the Reader to read YAML from
     * @return the parsed JqValue, or {@link JqNull#NULL} for empty/null documents
     */
    public static JqValue parse(Reader reader) {
        Yaml snakeYaml = new Yaml();
        Node root = snakeYaml.compose(reader);
        if (root == null) return JqNull.NULL;
        return convertNode(root);
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
     * Parse all YAML documents from an InputStream.
     *
     * @param in the InputStream to read YAML from
     * @return list of parsed JqValues (empty list for empty input)
     */
    public static List<JqValue> parseAll(InputStream in) {
        return parseAll(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
    }

    private static List<JqValue> parseAll(Reader reader) {
        Yaml snakeYaml = new Yaml();
        var results = new ArrayList<JqValue>();
        for (Node node : snakeYaml.composeAll(reader)) {
            results.add(convertNode(node));
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
     * Parse all YAML documents from an InputStream as a {@link JqArray}.
     *
     * @param in the InputStream to read YAML from
     * @return a JqArray containing one element per document
     */
    public static JqArray parseAllAsArray(InputStream in) {
        return JqArray.ofTrusted(parseAll(in));
    }

    // ========================================================================
    //  SnakeYAML Node → JqValue conversion
    // ========================================================================

    private static JqValue convertNode(Node node) {
        return switch (node) {
            case MappingNode m -> convertMapping(m);
            case SequenceNode s -> convertSequence(s);
            case ScalarNode sc -> convertScalar(sc);
            default -> JqNull.NULL;
        };
    }

    private static JqObject convertMapping(MappingNode mapping) {
        List<NodeTuple> tuples = mapping.getValue();
        // First pass: collect merge keys (<<) to flatten
        var builder = JqObject.builder(tuples.size());
        for (NodeTuple tuple : tuples) {
            String key = ((ScalarNode) tuple.getKeyNode()).getValue();
            if ("<<".equals(key)) {
                // YAML merge key: flatten the referenced mapping's entries
                Node mergeValue = tuple.getValueNode();
                if (mergeValue instanceof MappingNode mergeMapping) {
                    for (NodeTuple merged : mergeMapping.getValue()) {
                        String mergedKey = ((ScalarNode) merged.getKeyNode()).getValue();
                        builder.put(mergedKey, convertNode(merged.getValueNode()));
                    }
                } else if (mergeValue instanceof SequenceNode mergeSeq) {
                    // << can reference a list of mappings
                    for (Node item : mergeSeq.getValue()) {
                        if (item instanceof MappingNode itemMapping) {
                            for (NodeTuple merged : itemMapping.getValue()) {
                                String mergedKey = ((ScalarNode) merged.getKeyNode()).getValue();
                                builder.put(mergedKey, convertNode(merged.getValueNode()));
                            }
                        }
                    }
                }
            } else {
                builder.put(key, convertNode(tuple.getValueNode()));
            }
        }
        return (JqObject) builder.build();
    }

    private static JqArray convertSequence(SequenceNode sequence) {
        List<Node> children = sequence.getValue();
        JqValue[] elements = new JqValue[children.size()];
        for (int i = 0; i < elements.length; i++) {
            elements[i] = convertNode(children.get(i));
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

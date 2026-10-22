package io.hyperfoil.tools.jjq.xml;

import io.hyperfoil.tools.jjq.value.JqValue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * XML parsing, querying support, and emission for {@link JqValue} trees
 * (issue #169): jq over XML through an xq-compatible data projection.
 *
 * <h2>Mapping</h2>
 * <ul>
 *   <li>attributes become {@code @}-prefixed keys ({@code <a x="1"/>} to
 *       {@code {"a":{"@x":"1"}}}), including retained {@code @xmlns}
 *       declarations so prefixed documents stay valid;</li>
 *   <li>character content becomes {@code #text}, dropped when it is only
 *       whitespace between child elements;</li>
 *   <li>repeated sibling elements collapse to arrays;</li>
 *   <li>empty elements ({@code <a/>}, {@code <a></a>}, whitespace-only)
 *       become {@code null};</li>
 *   <li>element and attribute names keep their prefixes
 *       ({@code <p:item>} to {@code "p:item"}); namespace URIs are not
 *       representable beyond the retained declarations;</li>
 *   <li>comments and processing instructions are dropped; mixed content
 *       concatenates text in document order (children first, then
 *       {@code #text}: order across text and child elements is not
 *       preserved).</li>
 * </ul>
 *
 * <h2>Limits</h2>
 * <p>DOCTYPE declarations and entity references are rejected (XXE
 * hardening). Documents must have a single root element. Text and
 * attributes are strings unless {@link XmlOptions#COERCE_NUMBERS} is
 * passed, which lifts JSON-number-shaped strings to numbers
 * ({@code 001} stays a string).</p>
 *
 * @see XmlOptions
 */
public final class JqXml {

    private JqXml() {}

    /**
     * Parse a single XML document into a {@code JqValue}.
     *
     * @param xml the XML document
     * @return the parsed value, or {@code JqNull.NULL} for empty input
     * @throws JqXmlException on malformed XML, DOCTYPE/entity use,
     *         multiple roots, or nesting past 10,000
     */
    public static JqValue parse(String xml) {
        return parse(xml, XmlOptions.DEFAULT);
    }

    /**
     * Parse a single XML document with explicit options.
     *
     * @param xml     the XML document
     * @param options parse options ({@link XmlOptions#COERCE_NUMBERS}
     *                lifts JSON-number-shaped strings to numbers)
     * @return the parsed value, or {@code JqNull.NULL} for empty input
     * @throws JqXmlException on malformed XML, DOCTYPE/entity use,
     *         multiple roots, or nesting past 10,000
     */
    public static JqValue parse(String xml, XmlOptions options) {
        return XmlParser.parse(xml, options);
    }

    /**
     * Parse a single XML document from an InputStream (encoding follows
     * the XML declaration or defaults).
     *
     * @param in the stream to read XML from (not closed)
     * @return the parsed value, or {@code JqNull.NULL} for empty input
     * @throws JqXmlException on malformed XML, DOCTYPE/entity use,
     *         multiple roots, or nesting past 10,000
     */
    public static JqValue parse(InputStream in) {
        return parse(in, XmlOptions.DEFAULT);
    }

    /**
     * Parse a single XML document from an InputStream with explicit options.
     *
     * @param in      the stream to read XML from (not closed)
     * @param options parse options
     * @return the parsed value, or {@code JqNull.NULL} for empty input
     * @throws JqXmlException on malformed XML, DOCTYPE/entity use,
     *         multiple roots, or nesting past 10,000
     */
    public static JqValue parse(InputStream in, XmlOptions options) {
        return XmlParser.parse(in, options);
    }

    /**
     * Render a {@code JqValue} as XML.
     *
     * @param value a single-root-element object ({@code {"a": ...}})
     * @return the XML document (no XML declaration)
     * @throws JqXmlException when the value has no single root element, or
     *         holds keys that are not attributes, {@code #text}, or child
     *         elements
     */
    public static String toXml(JqValue value) {
        return XmlEmitter.toXml(value);
    }

    /**
     * Render a {@code JqValue} as XML to an output stream (UTF-8).
     *
     * @param value a single-root-element object
     * @param out   the stream to write to (not closed)
     * @throws JqXmlException when the value has no single root element
     * @throws IOException on write failure
     */
    public static void toXml(JqValue value, OutputStream out) throws IOException {
        out.write(toXml(value).getBytes(StandardCharsets.UTF_8));
    }
}

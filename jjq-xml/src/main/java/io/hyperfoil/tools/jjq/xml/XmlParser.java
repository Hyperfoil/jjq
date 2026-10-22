package io.hyperfoil.tools.jjq.xml;

import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqBoolean;
import io.hyperfoil.tools.jjq.value.JqNull;
import io.hyperfoil.tools.jjq.value.JqNumber;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.stream.Location;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * StAX-driven XML to {@link JqValue} conversion (issue #169).
 *
 * <p>The JDK cursor feeds one open element frame at a time; no DOM tree is
 * built. Element content accumulates in a side frame ({@code JqValue} for a
 * first-seen child, {@code List} once a sibling repeats), so repeated
 * children collapse to arrays with a single pass and no re-scan.</p>
 */
final class XmlParser {

    /** Mirror of the JSON parser depth cap: nesting past this is rejected. */
    private static final int MAX_DEPTH = 10_000;

    private XmlParser() {}

    static JqValue parse(String xml, XmlOptions options) {
        if (xml.isBlank()) return JqNull.NULL;
        return parseReader(xml, options);
    }

    static JqValue parse(InputStream in, XmlOptions options) {
        // Peek for empty streams: StAX reports premature end-of-file
        // instead of an empty event sequence.
        PushbackInputStream pushback = in instanceof PushbackInputStream p ? p
                : new PushbackInputStream(in);
        int first;
        try {
            first = pushback.read();
        } catch (IOException e) {
            throw new JqXmlException("Invalid XML: " + e.getMessage());
        }
        if (first < 0) return JqNull.NULL;
        try {
            pushback.unread(first);
        } catch (IOException e) {
            throw new JqXmlException("Invalid XML: " + e.getMessage());
        }
        XMLStreamReader reader;
        try {
            reader = hardenedFactory().createXMLStreamReader(pushback);
        } catch (XMLStreamException e) {
            throw failure("Invalid XML", e);
        }
        try {
            return parseEvents(reader, options);
        } finally {
            closeQuietly(reader);
        }
    }

    private static JqValue parseReader(String xml, XmlOptions options) {
        // String content is already decoded: a Reader keeps the parser from
        // reinterpreting bytes against an encoding declaration.
        XMLStreamReader reader;
        try {
            reader = hardenedFactory().createXMLStreamReader(new StringReader(xml));
        } catch (XMLStreamException e) {
            throw failure("Invalid XML", e);
        }
        try {
            return parseEvents(reader, options);
        } finally {
            closeQuietly(reader);
        }
    }

    /**
     * Hardened factory (issue #169): no DTD support, no external entities,
     * and a resolver that throws. {@code SUPPORT_DTD=false} alone still
     * reports a DTD event on this JDK, so {@link #parseEvents} also rejects
     * DOCTYPE and entity references at the event level.
     */
    private static XMLInputFactory hardenedFactory() throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        try {
            // Match the JSON parser depth cap; the JDK default (100) would
            // reject ordinary deep documents. Set one past our own cap so
            // the explicit check in parseEvents reports the rejection with
            // a stable message; the JDK cap stays a backstop. If
            // unsettable, the JDK default still bounds depth, only earlier.
            factory.setProperty("http://www.oracle.com/xml/jaxp/properties/maxElementDepth",
                    MAX_DEPTH + 1);
        } catch (IllegalArgumentException ignored) {
            // Event-level depth check below holds regardless.
        }
        try {
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        } catch (IllegalArgumentException ignored) {
            // Property named by the JAXP spec; retained for implementations
            // that honor it. The event-level rejections below hold regardless.
        }
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("External entities are not supported");
        });
        return factory;
    }

    private static final class Frame {
        final String name;
        final Map<String, JqValue> attrs = new LinkedHashMap<>();
        final Map<String, Object> kids = new LinkedHashMap<>();
        final StringBuilder text = new StringBuilder();

        Frame(String name) {
            this.name = name;
        }
    }

    @SuppressWarnings("unchecked")
    private static JqValue parseEvents(XMLStreamReader reader, XmlOptions options) {
        ArrayDeque<Frame> stack = new ArrayDeque<>();
        JqValue root = null;
        int depth = 0;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                switch (event) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        if (++depth > MAX_DEPTH) {
                            throw new JqXmlException("XML nesting past " + MAX_DEPTH, lineOf(reader),
                                    columnOf(reader));
                        }
                        Frame frame = new Frame(qualified(reader.getPrefix(), reader.getLocalName()));
                        int namespaces = reader.getNamespaceCount();
                        for (int i = 0; i < namespaces; i++) {
                            String prefix = reader.getNamespacePrefix(i);
                            String key = prefix == null ? "@xmlns" : "@xmlns:" + prefix;
                            frame.attrs.put(key, JqString.of(reader.getNamespaceURI(i)));
                        }
                        int attrs = reader.getAttributeCount();
                        for (int i = 0; i < attrs; i++) {
                            String key = "@" + qualified(reader.getAttributePrefix(i),
                                    reader.getAttributeLocalName(i));
                            frame.attrs.put(key, scalar(reader.getAttributeValue(i), options));
                        }
                        stack.push(frame);
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA,
                            XMLStreamConstants.SPACE -> {
                        if (!stack.isEmpty()) {
                            stack.peek().text.append(reader.getText());
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        if (stack.isEmpty()) {
                            throw new JqXmlException("Unbalanced end tag", lineOf(reader),
                                    columnOf(reader));
                        }
                        Frame done = stack.pop();
                        depth--;
                        JqValue built = buildElement(done, options);
                        if (stack.isEmpty()) {
                            if (root != null) {
                                throw new JqXmlException("Multiple root elements", lineOf(reader),
                                        columnOf(reader));
                            }
                            root = JqObject.builder(1).putUnchecked(done.name, built).build();
                        } else {
                            Object existing = stack.peek().kids.get(done.name);
                            if (existing == null) {
                                stack.peek().kids.put(done.name, built);
                            } else if (existing instanceof List) {
                                ((List<JqValue>) existing).add(built);
                            } else {
                                List<JqValue> repeated = new ArrayList<>(4);
                                repeated.add((JqValue) existing);
                                repeated.add(built);
                                stack.peek().kids.put(done.name, repeated);
                            }
                        }
                    }
                    case XMLStreamConstants.DTD -> throw new JqXmlException("DOCTYPE is not supported",
                            lineOf(reader), columnOf(reader));
                    case XMLStreamConstants.ENTITY_REFERENCE -> throw new JqXmlException(
                            "Entity references are not supported", lineOf(reader), columnOf(reader));
                    default -> {
                        // START_DOCUMENT, END_DOCUMENT, COMMENT,
                        // PROCESSING_INSTRUCTION: no data for the mapping.
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw failure("Invalid XML", e);
        }
        if (!stack.isEmpty()) {
            throw new JqXmlException("Unclosed element <" + stack.peek().name + ">", -1, -1);
        }
        return root == null ? JqNull.NULL : root;
    }

    @SuppressWarnings("unchecked")
    private static JqValue buildElement(Frame frame, XmlOptions options) {
        String text = frame.text.toString();
        if (frame.kids.isEmpty() && frame.attrs.isEmpty()) {
            return text.isEmpty() || text.isBlank() ? JqNull.NULL : scalar(text, options);
        }
        var builder = JqObject.builder(frame.attrs.size() + frame.kids.size() + 1);
        for (var attr : frame.attrs.entrySet()) {
            builder.putUnchecked(attr.getKey(), attr.getValue());
        }
        for (var kid : frame.kids.entrySet()) {
            Object value = kid.getValue();
            builder.putUnchecked(kid.getKey(),
                    value instanceof List ? JqArray.of((List<JqValue>) value) : (JqValue) value);
        }
        // Whitespace-only text between child elements is formatting, not
        // data; anything else is kept verbatim (mixed content concatenates
        // in document order, order itself is not preserved).
        if (!text.isBlank()) {
            builder.putUnchecked("#text", scalar(text, options));
        }
        return builder.build();
    }

    private static String qualified(String prefix, String local) {
        return (prefix == null || prefix.isEmpty()) ? local : prefix + ':' + local;
    }

    /** Strings by default; JSON-number shapes become numbers when opted in. */
    static JqValue scalar(String text, XmlOptions options) {
        if (!options.coerceNumbers()) return JqString.of(text);
        int n = text.length();
        int i = n > 0 && (text.charAt(0) == '-' || text.charAt(0) == '+') ? 1 : 0;
        int intStart = i;
        while (i < n && isDigit(text.charAt(i))) i++;
        int intEnd = i;
        boolean frac = false;
        if (i < n && text.charAt(i) == '.') {
            int f = i + 1;
            while (f < n && isDigit(text.charAt(f))) f++;
            if (f > i + 1) {
                frac = true;
                i = f;
            }
        }
        boolean exp = false;
        if (i < n && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
            int e = i + 1;
            if (e < n && (text.charAt(e) == '-' || text.charAt(e) == '+')) e++;
            int d = e;
            while (d < n && isDigit(text.charAt(d))) d++;
            if (d > e) {
                exp = true;
                i = d;
            }
        }
        if (intEnd == intStart || i != n) return JqString.of(text);
        // Canonical-shape gate (the `001` trap): a leading zero is only
        // valid alone, and `+` never renders back.
        String digits = text.substring(intStart, intEnd);
        if (digits.length() > 1 && digits.charAt(0) == '0') return JqString.of(text);
        if (text.charAt(0) == '+') return JqString.of(text);
        if (!frac && !exp) {
            try {
                return JqNumber.of(Long.parseLong(text));
            } catch (NumberFormatException over) {
                return JqNumber.of(new BigDecimal(text));
            }
        }
        return JqNumber.of(Double.parseDouble(text));
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static void closeQuietly(XMLStreamReader reader) {
        try {
            reader.close();
        } catch (XMLStreamException ignored) {
            // Release-only; parse outcome already decided.
        }
    }

    private static int lineOf(XMLStreamReader reader) {
        Location location = reader.getLocation();
        return location == null ? -1 : location.getLineNumber();
    }

    private static int columnOf(XMLStreamReader reader) {
        Location location = reader.getLocation();
        return location == null ? -1 : location.getColumnNumber();
    }

    private static JqXmlException failure(String what, XMLStreamException cause) {
        Location location = cause.getLocation();
        if (location == null) return new JqXmlException(what + ": " + cause.getMessage());
        return new JqXmlException(what + ": " + cause.getMessage(), location.getLineNumber(),
                location.getColumnNumber());
    }
}

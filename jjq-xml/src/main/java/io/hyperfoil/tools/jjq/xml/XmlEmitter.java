package io.hyperfoil.tools.jjq.xml;

import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqBoolean;
import io.hyperfoil.tools.jjq.value.JqNull;
import io.hyperfoil.tools.jjq.value.JqNumber;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;

/**
 * {@link JqValue} to XML emission (issue #169).
 *
 * <p>Inverse of the parser mapping for the common subset: {@code @}-prefixed
 * keys become attributes (including retained {@code @xmlns} declarations),
 * {@code #text} becomes character content, other keys become child elements,
 * and arrays become repeated elements. Mixed content emits attributes, then
 * text, then children — deterministic, but order across text and children is
 * not preserved. No XML declaration is written.</p>
 */
final class XmlEmitter {

    private XmlEmitter() {}

    static String toXml(JqValue value) {
        if (!(value instanceof JqObject root) || root.size() != 1) {
            throw new JqXmlException("XML needs a single root element object");
        }
        StringBuilder sb = new StringBuilder();
        appendElement(sb, root.keyAt(0), root.valueAt(0));
        return sb.toString();
    }

    private static void appendElement(StringBuilder sb, String name, JqValue value) {
        switch (value) {
            case JqNull ignored -> sb.append('<').append(name).append("/>");
            case JqString s -> {
                sb.append('<').append(name).append('>');
                escapeText(sb, s.stringValue());
                sb.append("</").append(name).append('>');
            }
            case JqNumber n -> appendScalarText(sb, name, n.toJsonString());
            case JqBoolean b -> appendScalarText(sb, name, b.toJsonString());
            case JqArray arr -> {
                for (int i = 0; i < arr.size(); i++) {
                    appendElement(sb, name, arr.getElement(i));
                }
            }
            case JqObject obj -> appendObject(sb, name, obj);
        }
    }

    private static void appendScalarText(StringBuilder sb, String name, String text) {
        sb.append('<').append(name).append('>');
        escapeText(sb, text);
        sb.append("</").append(name).append('>');
    }

    private static void appendObject(StringBuilder sb, String name, JqObject obj) {
        sb.append('<').append(name);
        for (int i = 0; i < obj.size(); i++) {
            String key = obj.keyAt(i);
            JqValue field = obj.valueAt(i);
            if (isAttribute(key, field)) {
                sb.append(' ').append(key.substring(1)).append("=\"");
                escapeAttribute(sb, field.stringValue());
                sb.append('"');
            }
        }
        boolean hasBody = false;
        for (int i = 0; i < obj.size(); i++) {
            String key = obj.keyAt(i);
            JqValue field = obj.valueAt(i);
            if (isAttribute(key, field)) continue;
            if (key.equals("#text") && !field.isString()) {
                throw new JqXmlException("Non-scalar #text for <" + name + ">");
            }
            if (key.startsWith("@")) {
                throw new JqXmlException("Key \"" + key + "\" is not representable in XML");
            }
            hasBody = true;
        }
        if (!hasBody) {
            sb.append("/>");
            return;
        }
        sb.append('>');
        for (int i = 0; i < obj.size(); i++) {
            String key = obj.keyAt(i);
            JqValue field = obj.valueAt(i);
            if (isAttribute(key, field)) continue;
            if (key.equals("#text")) {
                escapeText(sb, field.stringValue());
            } else if (field instanceof JqArray arr) {
                for (int j = 0; j < arr.size(); j++) appendElement(sb, key, arr.getElement(j));
            } else {
                appendElement(sb, key, field);
            }
        }
        sb.append("</").append(name).append('>');
    }

    private static boolean isAttribute(String key, JqValue field) {
        return key.startsWith("@") && key.length() > 1 && field.isString();
    }

    private static void escapeText(StringBuilder sb, String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                default -> sb.append(c);
            }
        }
    }

    private static void escapeAttribute(StringBuilder sb, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
    }
}

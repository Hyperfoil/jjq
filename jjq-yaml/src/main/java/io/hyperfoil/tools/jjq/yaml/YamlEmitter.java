package io.hyperfoil.tools.jjq.yaml;

import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqBoolean;
import io.hyperfoil.tools.jjq.value.JqNull;
import io.hyperfoil.tools.jjq.value.JqNumber;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;
import java.util.ArrayList;
import java.util.List;

/**
 * Block-style YAML emitter for {@link JqValue} trees (no dependencies).
 *
 * <p>Scalars that would reparse as another type (or break structure) are
 * double-quoted; everything else emits bare. Multi-line strings use
 * {@code \n} escapes. Round-trip safety is structural: bare scalars are
 * accepted only when the parser itself would read them back as strings
 * ({@link YamlParser#parsesAsPlainString}).</p>
 */
final class YamlEmitter {

    private YamlEmitter() {}

    static String emit(JqValue value) {
        StringBuilder sb = new StringBuilder();
        appendValue(sb, value, 0);
        if (sb.length() == 0 || sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
        return sb.toString();
    }

    static void emit(JqValue value, StringBuilder sb) {
        int start = sb.length();
        appendValue(sb, value, 0);
        if (sb.length() == start || sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
    }

    private static void appendValue(StringBuilder sb, JqValue value, int indent) {
        if (value.isNull()) {
            sb.append("null");
        } else if (value.isBoolean()) {
            sb.append(((JqBoolean) value).booleanValue() ? "true" : "false");
        } else if (value.isNumber()) {
            appendNumber(sb, (JqNumber) value);
        } else if (value.isString()) {
            appendScalar(sb, value.stringValue());
        } else if (value.isArray()) {
            appendSequence(sb, (JqArray) value, indent);
        } else if (value.isObject()) {
            appendMapping(sb, (JqObject) value, indent);
        } else {
            sb.append("null");
        }
    }

    private static void appendNumber(StringBuilder sb, JqNumber number) {
        double d = number.doubleValue();
        if (Double.isNaN(d)) {
            sb.append(".nan");
        } else if (d == Double.POSITIVE_INFINITY) {
            sb.append(".inf");
        } else if (d == Double.NEGATIVE_INFINITY) {
            sb.append("-.inf");
        } else {
            sb.append(number.toJsonString());
        }
    }

    private static void appendMapping(StringBuilder sb, JqObject obj, int indent) {
        if (obj.size() == 0) {
            sb.append("{}");
            return;
        }
        List<String> keys = new ArrayList<>(obj.keys());
        for (int k = 0; k < keys.size(); k++) {
            // At indent 0 this is a no-op (already at line start).
            indent(sb, indent);
            String key = keys.get(k);
            appendScalar(sb, key);
            sb.append(':');
            JqValue value = obj.getField(key);
            if (value.isArray() && ((JqArray) value).size() > 0
                    || value.isObject() && ((JqObject) value).size() > 0) {
                sb.append('\n');
                appendValue(sb, value, indent + 2);
            } else {
                sb.append(' ');
                appendValue(sb, value, indent + 2);
                sb.append('\n');
            }
        }
    }

    private static void appendSequence(StringBuilder sb, JqArray arr, int indent) {
        if (arr.size() == 0) {
            sb.append("[]");
            return;
        }
        for (int i = 0; i < arr.size(); i++) {
            // At indent 0 this is a no-op (already at line start).
            indent(sb, indent);
            sb.append("- ");
            JqValue item = arr.getElement(i);
            if (item instanceof JqObject obj && obj.size() > 0) {
                // Compact `- key: value` first pair, rest deeper (matches the
                // inline-mapping shape the parser reads back).
                var keys = new ArrayList<>(obj.keys());
                appendScalar(sb, keys.get(0));
                sb.append(": ");
                appendInlineOrNested(sb, obj.getField(keys.get(0)), indent + 2);
                sb.append('\n');
                for (int k = 1; k < keys.size(); k++) {
                    indent(sb, indent + 2);
                    appendScalar(sb, keys.get(k));
                    sb.append(": ");
                    appendInlineOrNested(sb, obj.getField(keys.get(k)), indent + 2);
                    sb.append('\n');
                }
            } else if (item instanceof JqArray sub && sub.size() > 0) {
                sb.append('\n');
                appendValue(sb, item, indent + 2);
            } else {
                // Scalars and empty containers stay inline (`- null`, `- {}`).
                appendValue(sb, item, indent + 2);
                sb.append('\n');
            }
        }
    }

    /** A nested value on an already-opened line: inline when scalar/empty. */
    private static void appendInlineOrNested(StringBuilder sb, JqValue value, int indent) {
        if (value.isArray() || value.isObject()) {
            sb.append('\n');
            appendValue(sb, value, indent);
        } else {
            appendValue(sb, value, indent);
        }
    }

    private static void indent(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) sb.append(' ');
    }

    private static void appendScalar(StringBuilder sb, String s) {
        if (YamlParser.parsesAsPlainString(s)) {
            sb.append(s);
            return;
        }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        sb.append(String.format("\\x%02x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}

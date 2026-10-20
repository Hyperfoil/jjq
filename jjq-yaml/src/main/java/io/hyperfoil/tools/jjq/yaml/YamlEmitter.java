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
 * YAML emitter for {@link JqValue} trees (no dependencies).
 *
 * <p>Scalars that would reparse as another type (or break structure) are
 * double-quoted; everything else emits bare. Multi-line strings use
 * {@code \n} escapes. Round-trip safety is structural: bare scalars are
 * accepted only when the parser itself would read them back as strings
 * ({@link YamlParser#parsesAsPlainString}).</p>
 *
 * <p>Output shape follows {@link YamlEmitOptions}: historical output is
 * {@link YamlEmitOptions#DEFAULT} (2-space indent, block everywhere).
 * Flow collections force flow for everything nested inline with them;
 * flow scalars are quoted more aggressively (`,[]{}`, flow-significant
 * colons) since those end plain scalars in flow context (issue #164).</p>
 */
final class YamlEmitter {

    private final int indentWidth;
    private final boolean seqFlow;
    private final boolean mapFlow;

    private YamlEmitter(YamlEmitOptions options) {
        this.indentWidth = options.indentWidth();
        this.seqFlow = options.sequenceStyle() == YamlEmitOptions.Style.FLOW;
        this.mapFlow = options.mappingStyle() == YamlEmitOptions.Style.FLOW;
    }

    static String emit(JqValue value) {
        return emit(value, YamlEmitOptions.DEFAULT);
    }

    static String emit(JqValue value, YamlEmitOptions options) {
        StringBuilder sb = new StringBuilder();
        emit(value, sb, options);
        return sb.toString();
    }

    static void emit(JqValue value, StringBuilder sb) {
        emit(value, sb, YamlEmitOptions.DEFAULT);
    }

    static void emit(JqValue value, StringBuilder sb, YamlEmitOptions options) {
        int start = sb.length();
        new YamlEmitter(options).appendValue(sb, value, 0, false);
        if (sb.length() == start || sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
    }

    private void appendValue(StringBuilder sb, JqValue value, int indent, boolean flow) {
        if (value.isNull()) {
            sb.append("null");
        } else if (value.isBoolean()) {
            sb.append(((JqBoolean) value).booleanValue() ? "true" : "false");
        } else if (value.isNumber()) {
            appendNumber(sb, (JqNumber) value);
        } else if (value.isString()) {
            if (flow) {
                appendFlowScalar(sb, value.stringValue());
            } else {
                appendScalar(sb, value.stringValue());
            }
        } else if (value.isArray()) {
            if (flow || seqFlow) {
                appendFlowSequence(sb, (JqArray) value);
            } else {
                appendSequence(sb, (JqArray) value, indent);
            }
        } else if (value.isObject()) {
            if (flow || mapFlow) {
                appendFlowMapping(sb, (JqObject) value);
            } else {
                appendMapping(sb, (JqObject) value, indent);
            }
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

    /** A collection value on an opened block line: nested block goes deeper. */
    private boolean isBlockCollection(JqValue value) {
        return value.isArray() && ((JqArray) value).size() > 0 && !seqFlow
                || value.isObject() && ((JqObject) value).size() > 0 && !mapFlow;
    }

    private void appendMapping(StringBuilder sb, JqObject obj, int indent) {
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
            if (isBlockCollection(value)) {
                sb.append('\n');
                appendValue(sb, value, indent + indentWidth, false);
            } else {
                sb.append(' ');
                appendValue(sb, value, indent + indentWidth, false);
                sb.append('\n');
            }
        }
    }

    private void appendSequence(StringBuilder sb, JqArray arr, int indent) {
        if (arr.size() == 0) {
            sb.append("[]");
            return;
        }
        for (int i = 0; i < arr.size(); i++) {
            // At indent 0 this is a no-op (already at line start).
            indent(sb, indent);
            sb.append("- ");
            JqValue item = arr.getElement(i);
            if (item instanceof JqObject obj && obj.size() > 0 && !mapFlow) {
                // Compact `- key: value` first pair, rest deeper (matches the
                // inline-mapping shape the parser reads back).
                var keys = new ArrayList<>(obj.keys());
                appendScalar(sb, keys.get(0));
                sb.append(": ");
                appendInlineOrNested(sb, obj.getField(keys.get(0)), indent + indentWidth);
                sb.append('\n');
                for (int k = 1; k < keys.size(); k++) {
                    indent(sb, indent + indentWidth);
                    appendScalar(sb, keys.get(k));
                    sb.append(": ");
                    appendInlineOrNested(sb, obj.getField(keys.get(k)), indent + indentWidth);
                    sb.append('\n');
                }
            } else if (isBlockCollection(item)) {
                sb.append('\n');
                appendValue(sb, item, indent + indentWidth, false);
            } else {
                // Scalars, empty containers and flow collections stay inline
                // (`- null`, `- {}`, `- [a, b]`).
                appendValue(sb, item, indent + indentWidth, false);
                sb.append('\n');
            }
        }
    }

    /** A nested value on an already-opened line: inline when scalar/empty/flow. */
    private void appendInlineOrNested(StringBuilder sb, JqValue value, int indent) {
        if (isBlockCollection(value)) {
            sb.append('\n');
            appendValue(sb, value, indent, false);
        } else {
            appendValue(sb, value, indent, false);
        }
    }

    private void appendFlowSequence(StringBuilder sb, JqArray arr) {
        sb.append('[');
        for (int i = 0; i < arr.size(); i++) {
            if (i > 0) sb.append(", ");
            appendValue(sb, arr.getElement(i), 0, true);
        }
        sb.append(']');
    }

    private void appendFlowMapping(StringBuilder sb, JqObject obj) {
        sb.append('{');
        List<String> keys = new ArrayList<>(obj.keys());
        for (int k = 0; k < keys.size(); k++) {
            if (k > 0) sb.append(", ");
            appendFlowScalar(sb, keys.get(k));
            sb.append(": ");
            appendValue(sb, obj.getField(keys.get(k)), 0, true);
        }
        sb.append('}');
    }

    private static void indent(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) sb.append(' ');
    }

    private static void appendScalar(StringBuilder sb, String s) {
        if (YamlParser.parsesAsPlainString(s)) {
            sb.append(s);
            return;
        }
        appendQuoted(sb, s);
    }

    /**
     * Flow-context scalar: bare only when no `,[]{}` and no
     * flow-significant colon (trailing, or followed by space/tab or a
     * flow indicator) appear — all of those end a plain scalar in flow
     * context. Everything else goes through double quotes.
     */
    private static void appendFlowScalar(StringBuilder sb, String s) {
        if (isFlowPlain(s)) {
            sb.append(s);
            return;
        }
        appendQuoted(sb, s);
    }

    private static boolean isFlowPlain(String s) {
        if (!YamlParser.parsesAsPlainString(s)) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ',' || c == '[' || c == ']' || c == '{' || c == '}') return false;
            if (c == ':') {
                if (i + 1 >= s.length()) return false;
                char n = s.charAt(i + 1);
                if (n == ' ' || n == '\t' || n == ',' || n == '['
                        || n == ']' || n == '{' || n == '}') return false;
            }
        }
        return true;
    }

    private static void appendQuoted(StringBuilder sb, String s) {
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
                    } else if (c >= 0x80 && c <= 0x9f) {
                        // C1 controls are not printable YAML even quoted
                        // raw (NEL raw would read back as a line break),
                        // so escape them (issue #163).
                        sb.append(String.format("\\u%04x", (int) c));
                    } else if (Character.isHighSurrogate(c)
                            && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                        // Valid astral pair: raw UTF-8 round-trips, escaped form bloats.
                        sb.append(c);
                        sb.append(s.charAt(++i));
                    } else if (Character.isSurrogate(c)) {
                        // Lone surrogate: raw output cannot survive UTF-8
                        // encoding (decodes as U+FFFD), so escape it (#138).
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}

package io.hyperfoil.tools.jjq.value;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Utility for parsing JSON strings into JqValue without external dependencies.
 */
public final class JqValues {

    private JqValues() {}

    public static JqValue parse(String json) {
        json = json.trim();
        // Strip UTF-8 BOM if present
        if (!json.isEmpty() && json.charAt(0) == '\uFEFF') {
            json = json.substring(1);
        }
        return parseValue(json, new int[]{0});
    }

    /**
     * Strict parse: requires the entire string to be a single valid JSON value.
     * Used by fromjson to reject trailing content like "NaN1".
     */
    public static JqValue parseStrict(String json) {
        String trimmed = json.trim();
        if (trimmed.isEmpty()) return JqNull.NULL;
        // Strip UTF-8 BOM if present
        if (trimmed.charAt(0) == '\uFEFF') {
            trimmed = trimmed.substring(1);
        }
        int[] pos = {0};
        JqValue result = parseValue(trimmed, pos);
        // Skip trailing whitespace
        while (pos[0] < trimmed.length() && Character.isWhitespace(trimmed.charAt(pos[0]))) {
            pos[0]++;
        }
        if (pos[0] < trimmed.length()) {
            throw new IllegalArgumentException(
                    "Invalid numeric literal at EOF at line 1, column " + trimmed.length()
                            + " (while parsing '" + trimmed + "')");
        }
        return result;
    }

    /**
     * Parse a stream of whitespace-separated JSON values (JSONL / NDJSON / JSON stream).
     * Behaves like jq: each top-level JSON value is parsed independently.
     */
    public static List<JqValue> parseAll(String json) {
        var results = new ArrayList<JqValue>();
        int[] pos = {0};
        while (true) {
            skipWs(json, pos);
            if (pos[0] >= json.length()) break;
            results.add(parseValue(json, pos));
        }
        return results;
    }

    private static JqValue parseValue(String json, int[] pos) {
        skipWs(json, pos);
        if (pos[0] >= json.length()) return JqNull.NULL;
        char c = json.charAt(pos[0]);
        return switch (c) {
            case '{' -> parseObject(json, pos);
            case '[' -> parseArray(json, pos);
            case '"' -> parseString(json, pos);
            case 't' -> { pos[0] += 4; yield JqBoolean.TRUE; }
            case 'f' -> { pos[0] += 5; yield JqBoolean.FALSE; }
            case 'n' -> {
                // Distinguish between "null" and "nan"
                if (pos[0] + 2 < json.length() && json.charAt(pos[0] + 1) == 'a' && json.charAt(pos[0] + 2) == 'n') {
                    pos[0] += 3;
                    yield JqNumber.of(Double.NaN);
                }
                pos[0] += 4;
                yield JqNull.NULL;
            }
            case 'I' -> {
                // Infinity
                pos[0] += 8; // "Infinity"
                yield JqNumber.of(Double.POSITIVE_INFINITY);
            }
            case 'N' -> {
                // NaN
                pos[0] += 3;
                yield JqNumber.of(Double.NaN);
            }
            default -> parseNumber(json, pos);
        };
    }

    private static JqObject parseObject(String json, int[] pos) {
        pos[0]++; // skip {
        var map = new LinkedHashMap<String, JqValue>();
        skipWs(json, pos);
        if (pos[0] < json.length() && json.charAt(pos[0]) == '}') { pos[0]++; return JqObject.of(map); }
        while (true) {
            skipWs(json, pos);
            if (pos[0] >= json.length() || json.charAt(pos[0]) != '"') {
                char got = pos[0] < json.length() ? json.charAt(pos[0]) : '?';
                throw new IllegalArgumentException(
                        "Invalid string literal; expected \", but got " + got
                                + " at line 1, column " + (pos[0] + 1)
                                + " (while parsing '" + json + "')");
            }
            String key = parseString(json, pos).stringValue();
            skipWs(json, pos);
            pos[0]++; // skip :
            JqValue value = parseValue(json, pos);
            map.put(key, value);
            skipWs(json, pos);
            if (pos[0] >= json.length() || json.charAt(pos[0]) == '}') { pos[0]++; break; }
            pos[0]++; // skip ,
        }
        return JqObject.of(map);
    }

    private static JqArray parseArray(String json, int[] pos) {
        pos[0]++; // skip [
        var list = new ArrayList<JqValue>();
        skipWs(json, pos);
        if (pos[0] < json.length() && json.charAt(pos[0]) == ']') { pos[0]++; return JqArray.of(list); }
        while (true) {
            list.add(parseValue(json, pos));
            skipWs(json, pos);
            if (pos[0] >= json.length() || json.charAt(pos[0]) == ']') { pos[0]++; break; }
            pos[0]++; // skip ,
        }
        return JqArray.of(list);
    }

    private static JqString parseString(String json, int[] pos) {
        pos[0]++; // skip opening "
        var sb = new StringBuilder();
        while (pos[0] < json.length() && json.charAt(pos[0]) != '"') {
            if (json.charAt(pos[0]) == '\\') {
                pos[0]++;
                char esc = json.charAt(pos[0]);
                switch (esc) {
                    case '"', '\\', '/' -> sb.append(esc);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        String hex = json.substring(pos[0] + 1, pos[0] + 5);
                        sb.append((char) Integer.parseInt(hex, 16));
                        pos[0] += 4;
                    }
                    default -> sb.append(esc);
                }
            } else {
                sb.append(json.charAt(pos[0]));
            }
            pos[0]++;
        }
        pos[0]++; // skip closing "
        return JqString.of(sb.toString());
    }

    private static JqNumber parseNumber(String json, int[] pos) {
        int start = pos[0];
        if (pos[0] < json.length() && json.charAt(pos[0]) == '-') {
            pos[0]++;
            // Handle -Infinity and -NaN
            if (pos[0] < json.length()) {
                if (json.charAt(pos[0]) == 'I') {
                    pos[0] += 8; // "Infinity"
                    return JqNumber.of(Double.NEGATIVE_INFINITY);
                }
                if (json.charAt(pos[0]) == 'N') {
                    pos[0] += 3; // "NaN"
                    return JqNumber.of(Double.NaN); // -NaN is same as NaN
                }
            }
        }
        while (pos[0] < json.length() && Character.isDigit(json.charAt(pos[0]))) pos[0]++;
        boolean isDecimal = false;
        if (pos[0] < json.length() && json.charAt(pos[0]) == '.') {
            isDecimal = true;
            pos[0]++;
            while (pos[0] < json.length() && Character.isDigit(json.charAt(pos[0]))) pos[0]++;
        }
        if (pos[0] < json.length() && (json.charAt(pos[0]) == 'e' || json.charAt(pos[0]) == 'E')) {
            isDecimal = true;
            pos[0]++;
            if (pos[0] < json.length() && (json.charAt(pos[0]) == '+' || json.charAt(pos[0]) == '-')) pos[0]++;
            while (pos[0] < json.length() && Character.isDigit(json.charAt(pos[0]))) pos[0]++;
        }
        String numStr = json.substring(start, pos[0]);
        if (isDecimal) {
            try {
                return JqNumber.of(new BigDecimal(numStr));
            } catch (NumberFormatException | ArithmeticException e) {
                // Extreme exponents: use double (produces Infinity or 0.0)
                return JqNumber.of(Double.parseDouble(numStr));
            }
        }
        try {
            return JqNumber.of(Long.parseLong(numStr));
        } catch (NumberFormatException e) {
            try {
                return JqNumber.of(new BigDecimal(numStr));
            } catch (NumberFormatException | ArithmeticException e2) {
                return JqNumber.of(Double.parseDouble(numStr));
            }
        }
    }

    private static void skipWs(String json, int[] pos) {
        while (pos[0] < json.length() && Character.isWhitespace(json.charAt(pos[0]))) pos[0]++;
    }
}

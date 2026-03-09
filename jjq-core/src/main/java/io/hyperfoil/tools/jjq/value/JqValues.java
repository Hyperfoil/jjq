package io.hyperfoil.tools.jjq.value;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Utility for parsing JSON strings into JqValue without external dependencies.
 */
public final class JqValues {

    private JqValues() {}

    public static JqValue parse(String json) {
        json = json.trim();
        return parseValue(json, new int[]{0});
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
            case 'n' -> { pos[0] += 4; yield JqNull.NULL; }
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
        if (pos[0] < json.length() && json.charAt(pos[0]) == '-') pos[0]++;
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
            return JqNumber.of(new BigDecimal(numStr));
        }
        try {
            return JqNumber.of(Long.parseLong(numStr));
        } catch (NumberFormatException e) {
            return JqNumber.of(new BigDecimal(numStr));
        }
    }

    private static void skipWs(String json, int[] pos) {
        while (pos[0] < json.length() && Character.isWhitespace(json.charAt(pos[0]))) pos[0]++;
    }
}

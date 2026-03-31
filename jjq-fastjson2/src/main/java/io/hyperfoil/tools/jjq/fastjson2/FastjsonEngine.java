package io.hyperfoil.tools.jjq.fastjson2;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.builtin.BuiltinRegistry;
import io.hyperfoil.tools.jjq.value.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * High-level API for jq evaluation with fastjson2 integration.
 */
public final class FastjsonEngine {
    private final BuiltinRegistry builtins;

    public FastjsonEngine() {
        this(BuiltinRegistry.getDefault());
    }

    public FastjsonEngine(BuiltinRegistry builtins) {
        this.builtins = builtins;
    }

    public JqProgram compile(String expression) {
        return JqProgram.compile(expression, builtins);
    }

    public List<JqValue> apply(String expression, String json) {
        JqProgram program = compile(expression);
        JqValue input = fromJson(json);
        return program.applyAll(input);
    }

    public List<String> applyToStrings(String expression, String json) {
        return apply(expression, json).stream()
                .map(JqValue::toJsonString)
                .toList();
    }

    public byte[] applyToBytes(String expression, byte[] jsonBytes) {
        String json = new String(jsonBytes, StandardCharsets.UTF_8);
        List<JqValue> results = apply(expression, json);
        var sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(results.get(i).toJsonString());
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public Stream<JqValue> applyStream(String expression, InputStream inputStream) {
        try {
            String json = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            return apply(expression, json).stream();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read input stream", e);
        }
    }

    public Stream<JqValue> applyBuffer(JqProgram program, byte[] buffer, int offset, int length) {
        String json = new String(buffer, offset, length, StandardCharsets.UTF_8);
        JqValue input = fromJson(json);
        return program.applyAll(input).stream();
    }

    /**
     * Convert a JSON string to a JqValue using fastjson2 for parsing, with lazy conversion.
     * Values are converted from fastjson2 types only when accessed.
     */
    public static JqValue fromJsonLazy(String json) {
        Object parsed = JSON.parse(json);
        return fromFastjsonLazy(parsed);
    }

    /**
     * Convert a fastjson2 object to a JqValue lazily.
     * Nested objects/arrays are wrapped and converted on demand.
     */
    public static JqValue fromFastjsonLazy(Object obj) {
        if (obj == null) return JqNull.NULL;
        if (obj instanceof JSONObject jsonObj) return LazyConverter.lazyObject(jsonObj);
        if (obj instanceof JSONArray jsonArr) return LazyConverter.lazyArray(jsonArr);
        return fromFastjson(obj); // scalars convert immediately
    }

    /**
     * Convert a JSON string to a JqValue using fastjson2 for parsing.
     */
    public static JqValue fromJson(String json) {
        Object parsed = JSON.parse(json);
        return fromFastjson(parsed);
    }

    /**
     * Convert a fastjson2 object to a JqValue.
     */
    public static JqValue fromFastjson(Object obj) {
        if (obj == null) return JqNull.NULL;
        if (obj instanceof JSONObject jsonObj) {
            var map = new LinkedHashMap<String, JqValue>();
            for (var entry : jsonObj.entrySet()) {
                map.put(entry.getKey(), fromFastjson(entry.getValue()));
            }
            return JqObject.of(map);
        }
        if (obj instanceof JSONArray jsonArr) {
            var list = new ArrayList<JqValue>();
            for (Object elem : jsonArr) {
                list.add(fromFastjson(elem));
            }
            return JqArray.of(list);
        }
        if (obj instanceof String s) return JqString.of(s);
        if (obj instanceof Boolean b) return JqBoolean.of(b);
        if (obj instanceof Integer i) return JqNumber.of(i);
        if (obj instanceof Long l) return JqNumber.of(l);
        if (obj instanceof Double d) return JqNumber.of(d);
        if (obj instanceof Float f) return JqNumber.of(f.doubleValue());
        if (obj instanceof java.math.BigDecimal bd) return JqNumber.of(bd);
        if (obj instanceof java.math.BigInteger bi) return JqNumber.of(new java.math.BigDecimal(bi));
        throw new IllegalArgumentException("Unsupported fastjson2 type: " + obj.getClass());
    }

    /**
     * Apply a pre-compiled program to raw bytes, returning raw bytes.
     * Avoids intermediate String creation for the output.
     */
    public byte[] applyToBytes(JqProgram program, byte[] jsonBytes) {
        return applyToBytes(program, jsonBytes, 0, jsonBytes.length);
    }

    /**
     * Apply a pre-compiled program to a byte buffer region, returning raw bytes.
     */
    public byte[] applyToBytes(JqProgram program, byte[] buffer, int offset, int length) {
        String json = new String(buffer, offset, length, StandardCharsets.UTF_8);
        JqValue input = fromJsonLazy(json);
        List<JqValue> results = program.applyAll(input);
        var sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(results.get(i).toJsonString());
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Process a JSON stream containing multiple JSON values.
     * Each value is parsed and the filter applied independently.
     */
    public Stream<JqValue> applyToJsonStream(JqProgram program, InputStream inputStream) {
        try {
            byte[] bytes = inputStream.readAllBytes();
            return applyToJsonStream(program, bytes, 0, bytes.length);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to read input stream", e);
        }
    }

    /**
     * Process a buffer containing multiple JSON values (whitespace-separated).
     * Returns a stream of all outputs from applying the filter to each input value.
     */
    public Stream<JqValue> applyToJsonStream(JqProgram program, byte[] buffer, int offset, int length) {
        String content = new String(buffer, offset, length, StandardCharsets.UTF_8).trim();
        if (content.isEmpty()) return Stream.empty();

        var allResults = new ArrayList<JqValue>();
        int pos = 0;
        while (pos < content.length()) {
            // Skip whitespace between values
            while (pos < content.length() && Character.isWhitespace(content.charAt(pos))) pos++;
            if (pos >= content.length()) break;

            // Parse next JSON value
            int start = pos;
            pos = findJsonEnd(content, pos);
            String valueStr = content.substring(start, pos);
            JqValue input = fromJsonLazy(valueStr);
            allResults.addAll(program.applyAll(input));
        }
        return allResults.stream();
    }

    private static int findJsonEnd(String s, int start) {
        char c = s.charAt(start);
        if (c == '{' || c == '[') {
            return findMatchingBracket(s, start);
        } else if (c == '"') {
            return findStringEnd(s, start) + 1;
        } else {
            // number, boolean, null - scan to next whitespace or structural char
            int pos = start + 1;
            while (pos < s.length()) {
                char ch = s.charAt(pos);
                if (Character.isWhitespace(ch) || ch == '{' || ch == '[' || ch == '"') break;
                pos++;
            }
            return pos;
        }
    }

    private static int findMatchingBracket(String s, int start) {
        char open = s.charAt(start);
        char close = open == '{' ? '}' : ']';
        int depth = 1;
        boolean inString = false;
        int pos = start + 1;
        while (pos < s.length() && depth > 0) {
            char c = s.charAt(pos);
            if (inString) {
                if (c == '\\') pos++; // skip escaped char
                else if (c == '"') inString = false;
            } else {
                if (c == '"') inString = true;
                else if (c == open) depth++;
                else if (c == close) depth--;
            }
            pos++;
        }
        return pos;
    }

    private static int findStringEnd(String s, int start) {
        int pos = start + 1;
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == '\\') pos++; // skip escaped char
            else if (c == '"') return pos;
            pos++;
        }
        return pos;
    }

    /**
     * Convert a JqValue back to a fastjson2 object.
     */
    public static Object toFastjson(JqValue value) {
        return switch (value) {
            case JqNull ignored -> null;
            case JqBoolean b -> b.booleanValue();
            case JqNumber n -> n.isIntegral() ? n.longValue() : n.decimalValue();
            case JqString s -> s.stringValue();
            case JqArray a -> {
                var arr = new JSONArray();
                for (JqValue elem : a.arrayValue()) {
                    arr.add(toFastjson(elem));
                }
                yield arr;
            }
            case JqObject o -> {
                var obj = new JSONObject();
                for (var entry : o.objectValue().entrySet()) {
                    obj.put(entry.getKey(), toFastjson(entry.getValue()));
                }
                yield obj;
            }
        };
    }
}

package io.hyperfoil.tools.jjq.value;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility for parsing JSON strings into JqValue without external dependencies.
 * <p>
 * Parsing uses a lightweight inner class with a mutable position field to avoid
 * array-access overhead on every character.
 */
public final class JqValues {

    private static final int MAX_PARSE_DEPTH = 10000;
    private static final int MAX_SERIALIZE_DEPTH = 10001;
    private static final int SERIALIZE_BUFFER_INIT = 8192;
    private static final int SERIALIZE_BUFFER_MAX_RETAINED = 1024 * 1024; // 1MB

    private static final ThreadLocal<StringBuilder> SERIALIZER_BUFFER =
            ThreadLocal.withInitial(() -> new StringBuilder(SERIALIZE_BUFFER_INIT));

    private JqValues() {}

    /**
     * Serialize a JqValue to a JSON string using a thread-local StringBuilder.
     * The buffer is reused across calls on the same thread, eliminating per-call
     * StringBuilder allocation. For nested structures, {@link JqValue#appendTo}
     * writes directly into the shared buffer without intermediate allocations.
     * <p>
     * If the buffer grows beyond 1MB (e.g., for a 14MB document), it is replaced
     * with a fresh buffer after serialization to avoid retaining excessive memory.
     */
    public static String serialize(JqValue value) {
        StringBuilder sb = SERIALIZER_BUFFER.get();
        sb.setLength(0);
        value.appendTo(sb);
        String result = sb.toString();
        if (sb.capacity() > SERIALIZE_BUFFER_MAX_RETAINED) {
            SERIALIZER_BUFFER.set(new StringBuilder(SERIALIZE_BUFFER_INIT));
        }
        return result;
    }

    /** Mutable parser state — avoids int[] indirection on every character access. */
    private static final class JsonReader {
        final String json;
        final int len;
        int pos;

        JsonReader(String json) {
            this.json = json;
            this.len = json.length();
            this.pos = 0;
        }

        char peek() { return json.charAt(pos); }
        char advance() { return json.charAt(pos++); }

        void skipWs() {
            int p = pos;
            final int l = len;
            final String s = json;
            while (p < l) {
                char c = s.charAt(p);
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') break;
                p++;
            }
            pos = p;
        }
    }

    public static JqValue parse(String json) {
        json = json.trim();
        // Strip UTF-8 BOM if present
        if (!json.isEmpty() && json.charAt(0) == '\uFEFF') {
            json = json.substring(1);
        }
        var reader = new JsonReader(json);
        return parseValue(reader, 0);
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
        var reader = new JsonReader(trimmed);
        JqValue result = parseValue(reader, 0);
        // Skip trailing whitespace
        reader.skipWs();
        if (reader.pos < reader.len) {
            throw new IllegalArgumentException(
                    "Invalid numeric literal at EOF at line 1, column " + reader.len
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
        var reader = new JsonReader(json);
        while (true) {
            reader.skipWs();
            if (reader.pos >= reader.len) break;
            results.add(parseValue(reader, 0));
        }
        return results;
    }

    private static JqValue parseValue(JsonReader r, int depth) {
        r.skipWs();
        if (r.pos >= r.len) return JqNull.NULL;
        char c = r.json.charAt(r.pos);
        return switch (c) {
            case '{' -> parseObject(r, depth + 1);
            case '[' -> parseArrayIterative(r, depth);
            case '"' -> parseString(r);
            case 't' -> { r.pos += 4; yield JqBoolean.TRUE; }
            case 'f' -> { r.pos += 5; yield JqBoolean.FALSE; }
            case 'n' -> {
                // Distinguish between "null" and "nan"
                if (r.pos + 2 < r.len && r.json.charAt(r.pos + 1) == 'a' && r.json.charAt(r.pos + 2) == 'n') {
                    r.pos += 3;
                    yield JqNumber.of(Double.NaN);
                }
                r.pos += 4;
                yield JqNull.NULL;
            }
            case 'I' -> {
                // Infinity
                r.pos += 8; // "Infinity"
                yield JqNumber.of(Double.POSITIVE_INFINITY);
            }
            case 'N' -> {
                // NaN
                r.pos += 3;
                yield JqNumber.of(Double.NaN);
            }
            default -> parseNumber(r);
        };
    }

    /**
     * Parse arrays iteratively to avoid stack overflow on deeply nested structures.
     * Uses an explicit stack instead of recursion for nested arrays.
     */
    private static JqValue parseArrayIterative(JsonReader r, int depth) {
        ArrayDeque<JqValue[]> stack = null; // deferred -- only allocated for nested [[...]]
        int[] stackCounts = null;
        int stackDepth = 0;

        // Iterate through consecutive opening brackets
        while (r.pos < r.len && r.json.charAt(r.pos) == '[') {
            depth++;
            if (depth > MAX_PARSE_DEPTH) {
                throw new IllegalArgumentException("Exceeds depth limit for parsing");
            }
            r.pos++; // skip [
            r.skipWs();

            // Empty array
            if (r.pos < r.len && r.json.charAt(r.pos) == ']') {
                r.pos++;
                return unwindArrayStack(stack, stackCounts, stackDepth, JqArray.EMPTY, r, depth);
            }

            // If first element is another array, push context and continue iterating
            if (r.json.charAt(r.pos) == '[') {
                if (stack == null) {
                    stack = new ArrayDeque<>();
                    stackCounts = new int[16];
                }
                if (stackDepth >= stackCounts.length) {
                    stackCounts = java.util.Arrays.copyOf(stackCounts, stackCounts.length * 2);
                }
                stack.push(new JqValue[8]);
                stackCounts[stackDepth++] = 0;
                continue;
            }

            // First element is not an array — parse this array normally
            break;
        }

        // Parse the current array elements using raw array
        JqValue[] elems = new JqValue[8];
        int count = 0;
        elems[count++] = parseValue(r, depth);
        r.skipWs();
        while (r.pos < r.len && r.json.charAt(r.pos) != ']') {
            r.pos++; // skip ,
            if (count >= elems.length) elems = java.util.Arrays.copyOf(elems, elems.length * 2);
            elems[count++] = parseValue(r, depth);
            r.skipWs();
        }
        if (r.pos < r.len) r.pos++; // skip ]
        JqValue result = JqArray.ofTrusted(elems, count);
        return unwindArrayStack(stack, stackCounts, stackDepth, result, r, depth);
    }

    /**
     * Unwind the explicit stack of partially-built arrays, completing each level.
     */
    private static JqValue unwindArrayStack(ArrayDeque<JqValue[]> stack, int[] stackCounts,
                                             int stackDepth, JqValue result,
                                             JsonReader r, int depth) {
        if (stack == null) return result;
        while (stackDepth > 0) {
            depth--;
            stackDepth--;
            JqValue[] elems = stack.pop();
            int count = stackCounts[stackDepth];
            if (count >= elems.length) elems = java.util.Arrays.copyOf(elems, elems.length * 2);
            elems[count++] = result;
            r.skipWs();
            while (r.pos < r.len && r.json.charAt(r.pos) != ']') {
                r.pos++; // skip ,
                if (count >= elems.length) elems = java.util.Arrays.copyOf(elems, elems.length * 2);
                elems[count++] = parseValue(r, depth);
                r.skipWs();
            }
            if (r.pos < r.len) r.pos++; // skip ]
            result = JqArray.ofTrusted(elems, count);
        }
        return result;
    }

    private static JqObject parseObject(JsonReader r, int depth) {
        if (depth > MAX_PARSE_DEPTH) {
            throw new IllegalArgumentException("Exceeds depth limit for parsing");
        }
        r.pos++; // skip {
        r.skipWs();
        if (r.pos < r.len && r.json.charAt(r.pos) == '}') { r.pos++; return JqObject.EMPTY; }
        // Direct array construction -- no LinkedHashMap intermediate
        String[] keys = new String[8];
        JqValue[] values = new JqValue[8];
        int count = 0;
        while (true) {
            r.skipWs();
            if (r.pos >= r.len || r.json.charAt(r.pos) != '"') {
                char got = r.pos < r.len ? r.json.charAt(r.pos) : '?';
                throw new IllegalArgumentException(
                        "Invalid string literal; expected \", but got " + got
                                + " at line 1, column " + (r.pos + 1)
                                + " (while parsing '" + r.json + "')");
            }
            if (count >= keys.length) {
                keys = java.util.Arrays.copyOf(keys, keys.length * 2);
                values = java.util.Arrays.copyOf(values, values.length * 2);
            }
            keys[count] = parseStringRaw(r);
            r.skipWs();
            r.pos++; // skip :
            values[count] = parseValue(r, depth);
            count++;
            r.skipWs();
            if (r.pos >= r.len || r.json.charAt(r.pos) == '}') { r.pos++; break; }
            r.pos++; // skip ,
        }
        return JqObject.ofArrays(keys, values, count);
    }

    /**
     * Fast-path string parser: scans for the closing quote without escape characters.
     * If the string contains no backslash, returns a substring directly (no StringBuilder,
     * no char-by-char copy). Falls back to escape-handling path only when needed.
     */
    private static JqString parseString(JsonReader r) {
        return JqString.of(parseStringRaw(r));
    }

    /** Parse a JSON string and return the raw Java String value (without wrapping in JqString). */
    private static String parseStringRaw(JsonReader r) {
        r.pos++; // skip opening "
        int start = r.pos;
        final String s = r.json;
        final int len = r.len;

        // Fast path: scan for closing quote, bail on backslash.
        // Uses char-by-char scan rather than String.indexOf because indexOf
        // would search the entire remaining document for both '"' and '\\',
        // which is wasteful for short strings (typical JSON values are 5-20 chars).
        while (r.pos < len) {
            char c = s.charAt(r.pos);
            if (c == '"') {
                // No escapes — direct substring (zero-copy on modern JVMs with compact strings)
                String result = s.substring(start, r.pos);
                r.pos++; // skip closing "
                return result;
            }
            if (c == '\\') break; // fall through to slow path
            r.pos++;
        }

        // Slow path: string contains escape sequences
        var sb = new StringBuilder(r.pos - start + 16);
        // Copy the portion already scanned
        sb.append(s, start, r.pos);
        while (r.pos < len && s.charAt(r.pos) != '"') {
            if (s.charAt(r.pos) == '\\') {
                r.pos++;
                char esc = s.charAt(r.pos);
                switch (esc) {
                    case '"', '\\', '/' -> sb.append(esc);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        int cp = Integer.parseInt(s, r.pos + 1, r.pos + 5, 16);
                        sb.append((char) cp);
                        r.pos += 4;
                    }
                    default -> sb.append(esc);
                }
            } else {
                sb.append(s.charAt(r.pos));
            }
            r.pos++;
        }
        r.pos++; // skip closing "
        return sb.toString();
    }

    /** Precomputed powers of 10 for direct decimal accumulation. */
    private static final double[] POW10 = {
        1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9,
        1e10, 1e11, 1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18
    };

    private static JqNumber parseNumber(JsonReader r) {
        final String s = r.json;
        final int len = r.len;
        int start = r.pos;
        boolean negative = false;

        if (r.pos < len && s.charAt(r.pos) == '-') {
            negative = true;
            r.pos++;
            // Handle -Infinity and -NaN
            if (r.pos < len) {
                if (s.charAt(r.pos) == 'I') {
                    r.pos += 8; // "Infinity"
                    return JqNumber.of(Double.NEGATIVE_INFINITY);
                }
                if (s.charAt(r.pos) == 'N') {
                    r.pos += 3; // "NaN"
                    return JqNumber.of(Double.NaN); // -NaN is same as NaN
                }
            }
        }

        // Fast path: parse integer digits directly into a long accumulator
        long acc = 0;
        boolean overflow = false;
        int digitStart = r.pos;
        int intDigitCount = 0;
        while (r.pos < len) {
            int d = s.charAt(r.pos) - '0';
            if (d < 0 || d > 9) break;
            if (!overflow) {
                long next = acc * 10 + d;
                if (next < acc) overflow = true;
                else acc = next;
            }
            intDigitCount++;
            r.pos++;
        }

        // Parse fractional part with digit accumulation
        boolean isDecimal = false;
        long fracPart = 0;
        int fracCount = 0;
        if (r.pos < len && s.charAt(r.pos) == '.') {
            isDecimal = true;
            r.pos++;
            while (r.pos < len) {
                int d = s.charAt(r.pos) - '0';
                if (d < 0 || d > 9) break;
                if (fracCount < 18) { // avoid long overflow
                    fracPart = fracPart * 10 + d;
                    fracCount++;
                }
                r.pos++;
            }
        }

        // Parse exponent with value accumulation
        int expValue = 0;
        boolean hasExp = false;
        if (r.pos < len && (s.charAt(r.pos) == 'e' || s.charAt(r.pos) == 'E')) {
            isDecimal = true;
            hasExp = true;
            r.pos++;
            boolean expNegative = false;
            if (r.pos < len && s.charAt(r.pos) == '-') { expNegative = true; r.pos++; }
            else if (r.pos < len && s.charAt(r.pos) == '+') { r.pos++; }
            while (r.pos < len) {
                int d = s.charAt(r.pos) - '0';
                if (d < 0 || d > 9) break;
                expValue = expValue * 10 + d;
                r.pos++;
            }
            if (expNegative) expValue = -expValue;
        }

        if (!isDecimal && !overflow && intDigitCount <= 18) {
            // Integer that fits in a long — no string allocation needed
            return JqNumber.of(negative ? -acc : acc);
        }

        // Direct digit accumulation for decimals with <= 15 significant digits
        // and manageable exponents — zero string allocation, zero BigDecimal
        if (isDecimal && !overflow && intDigitCount <= 15 && fracCount <= 15) {
            int totalDigits = intDigitCount + fracCount;
            if (totalDigits <= 15 && expValue >= -18 && expValue <= 18) {
                double value = (double) acc;
                if (fracCount > 0) {
                    value += (double) fracPart / POW10[fracCount];
                }
                if (expValue > 0 && expValue < POW10.length) {
                    value *= POW10[expValue];
                } else if (expValue < 0 && -expValue < POW10.length) {
                    value /= POW10[-expValue];
                } else if (expValue != 0) {
                    value *= Math.pow(10, expValue);
                }
                return JqNumber.of(negative ? -value : value);
            }
        }

        // Fall back to string-based parsing for high-precision or extreme exponents
        String numStr = s.substring(start, r.pos);
        if (isDecimal) {
            try {
                return JqNumber.of(new BigDecimal(numStr));
            } catch (NumberFormatException | ArithmeticException e) {
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

    /**
     * Index into a JqValue: array[number], object["key"], null[anything] = null.
     * Shared by both the bytecode VM and the tree-walk evaluator.
     */
    public static JqValue indexValue(JqValue base, JqValue index) {
        return switch (base) {
            case JqArray arr when index instanceof JqNumber n -> {
                if (n.isNaN()) yield JqNull.NULL;
                yield arr.get((int) n.longValue());
            }
            case JqObject obj when index instanceof JqString s -> obj.get(s.stringValue());
            case JqNull _ -> JqNull.NULL;
            default -> throw new JqTypeError("Cannot index " + base.type().jqName()
                    + " with " + index.type().jqName() + " (" + index.toJsonString() + ")");
        };
    }

    /**
     * Depth-limited JSON serialization for tojson.
     * Uses iterative array handling to avoid stack overflow on deeply nested structures.
     * At depths exceeding MAX_SERIALIZE_DEPTH, outputs "&lt;skipped: too deep&gt;" instead of
     * recursing further — matching jq's behavior for extremely nested structures.
     */
    public static String toJsonStringDepthLimited(JqValue val) {
        StringBuilder sb = SERIALIZER_BUFFER.get();
        sb.setLength(0);
        appendJsonDepthLimited(val, sb, 0);
        String result = sb.toString();
        if (sb.capacity() > SERIALIZE_BUFFER_MAX_RETAINED) {
            SERIALIZER_BUFFER.set(new StringBuilder(SERIALIZE_BUFFER_INIT));
        }
        return result;
    }

    private static void appendJsonDepthLimited(JqValue val, StringBuilder sb, int depth) {
        // Handle arrays iteratively to avoid stack overflow on deeply nested structures
        int arrayNesting = 0;
        while (val instanceof JqArray arr) {
            depth++;
            if (depth > MAX_SERIALIZE_DEPTH) {
                sb.append("\"<skipped: too deep>\"");
                for (int i = 0; i < arrayNesting; i++) sb.append(']');
                return;
            }
            var elements = arr.arrayValue();
            if (elements.isEmpty()) {
                sb.append("[]");
                for (int i = 0; i < arrayNesting; i++) sb.append(']');
                return;
            }
            sb.append('[');
            arrayNesting++;
            // If single-element array whose element is also an array, continue iteratively
            if (elements.size() == 1) {
                val = elements.get(0);
                continue; // loop back to check if it's another array
            }
            // Multi-element array: serialize first element iteratively, rest recursively
            appendJsonDepthLimited(elements.get(0), sb, depth);
            for (int i = 1; i < elements.size(); i++) {
                sb.append(',');
                appendJsonDepthLimited(elements.get(i), sb, depth);
            }
            for (int i = 0; i < arrayNesting; i++) sb.append(']');
            return;
        }
        // Non-array value: use appendTo for scalars and objects
        if (val instanceof JqObject obj) {
            if (depth > MAX_SERIALIZE_DEPTH) {
                sb.append("\"<skipped: too deep>\"");
            } else {
                sb.append('{');
                boolean first = true;
                for (var e : obj.objectValue().entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append('"');
                    JqString.escapeJson(e.getKey(), sb);
                    sb.append("\":");
                    appendJsonDepthLimited(e.getValue(), sb, depth + 1);
                }
                sb.append('}');
            }
        } else {
            val.appendTo(sb);
        }
        for (int i = 0; i < arrayNesting; i++) sb.append(']');
    }
}

package io.hyperfoil.tools.jjq.value;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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

    // ========================================================================
    //  Field name interning (issue #10)
    //  Open-addressing hash table for deduplicating JSON object key strings.
    //  Reduces allocation for repeated schemas and enables reference equality
    //  in JqObject.get() via String.equals short-circuit (this == other).
    // ========================================================================

    private static final int INTERN_TABLE_SIZE = 2048; // power of 2
    private static final int INTERN_MASK = INTERN_TABLE_SIZE - 1;
    private static final String[] INTERN_TABLE = new String[INTERN_TABLE_SIZE];
    // Pre-computed JSON key form: "\"key\":" — eliminates escapeJson + 3 appends in serialization
    private static final String[] INTERN_JSON_KEY = new String[INTERN_TABLE_SIZE];

    /**
     * Intern a field name string. Returns the cached instance if one exists
     * for this hash slot, or caches and returns the given string.
     * Also pre-computes the JSON key serialization form {@code "\"key\":"}.
     * <p>
     * Thread-safe via benign races: concurrent writes to the same slot
     * produce correct results (worst case: one write is lost, no corruption).
     */
    public static String internFieldName(String name) {
        int slot = name.hashCode() & INTERN_MASK;
        String cached = INTERN_TABLE[slot];
        if (name.equals(cached)) return cached;
        INTERN_TABLE[slot] = name;
        INTERN_JSON_KEY[slot] = buildJsonKey(name);
        return name;
    }

    /**
     * Look up the pre-computed JSON key form for an interned field name.
     * Returns {@code "\"key\":"} if the key is in the intern cache, or null
     * if it's not interned or was evicted by a hash collision.
     * <p>
     * The caller must pass a key that was returned by {@link #internFieldName}
     * or one of the {@code parseAndInternKey} methods. Reference equality
     * is used for the cache check (no content comparison).
     */
    public static String internedJsonKey(String key) {
        int slot = key.hashCode() & INTERN_MASK;
        if (INTERN_TABLE[slot] == key) { // reference equality — interned keys match
            return INTERN_JSON_KEY[slot];
        }
        return null;
    }

    /** Build the JSON serialization form for an object key: {@code "\"key\":"}. */
    private static String buildJsonKey(String name) {
        // Check if key needs escaping (rare for field names)
        boolean clean = true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '"' || c == '\\' || c < 0x20) {
                clean = false;
                break;
            }
        }
        if (clean) {
            return "\"" + name + "\":";
        }
        // Rare: field name needs escaping
        var sb = new StringBuilder(name.length() + 4);
        sb.append('"');
        JqString.escapeJson(name, sb);
        sb.append("\":");
        return sb.toString();
    }

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

    /**
     * Serialize a JqValue directly to a Writer without constructing an intermediate String.
     * Uses the thread-local StringBuilder as a buffer, then writes its contents directly
     * to the Writer via {@link Writer#append(CharSequence)}, avoiding the {@code toString()}
     * copy that {@link #serialize(JqValue)} performs.
     */
    public static void serializeTo(JqValue value, Writer writer) throws IOException {
        StringBuilder sb = SERIALIZER_BUFFER.get();
        sb.setLength(0);
        value.appendTo(sb);
        writer.append(sb);
        if (sb.capacity() > SERIALIZE_BUFFER_MAX_RETAINED) {
            SERIALIZER_BUFFER.set(new StringBuilder(SERIALIZE_BUFFER_INIT));
        }
    }

    /**
     * Serialize a JqValue directly to an OutputStream as UTF-8 without constructing
     * an intermediate String. Eliminates the {@code toString()} and {@code getBytes()}
     * copies compared to {@code out.write(value.toJsonString().getBytes(UTF_8))}.
     */
    public static void serializeTo(JqValue value, OutputStream out) throws IOException {
        var writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        serializeTo(value, writer);
        writer.flush();
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
            keys[count] = parseAndInternKey(r);
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
    /**
     * Parse a JSON string value and return a deferred JqString.
     * Scans for the closing quote without materializing the Java String.
     * Field names (object keys) use {@link #parseStringRaw} instead.
     */
    private static JqString parseString(JsonReader r) {
        r.pos++; // skip opening "
        int contentStart = r.pos;
        final String s = r.json;
        final int len = r.len;

        // Fast path: scan for closing quote, check for backslash
        while (r.pos < len) {
            char c = s.charAt(r.pos);
            if (c == '"') {
                // No escapes -- deferred, zero-copy on serialization
                int contentEnd = r.pos;
                r.pos++; // skip closing "
                return JqString.deferred(s, contentStart, contentEnd, false);
            }
            if (c == '\\') {
                // Has escapes -- scan to end, mark as has-escapes
                return parseDeferredWithEscapes(r, s, contentStart);
            }
            r.pos++;
        }
        throw new IllegalArgumentException("Unterminated string");
    }

    /** Scan to closing quote for a string that contains escape sequences. */
    private static JqString parseDeferredWithEscapes(JsonReader r, String s, int contentStart) {
        final int len = r.len;
        while (r.pos < len) {
            char c = s.charAt(r.pos);
            if (c == '"') {
                int contentEnd = r.pos;
                r.pos++; // skip closing "
                return JqString.deferred(s, contentStart, contentEnd, true);
            }
            if (c == '\\') {
                r.pos++; // skip backslash
                // Skip the escaped character (including unicode escapes)
                if (r.pos < len && s.charAt(r.pos) == 'u') {
                    r.pos += 4; // skip 4 hex digits
                }
            }
            r.pos++;
        }
        throw new IllegalArgumentException("Unterminated string");
    }

    /**
     * Parse a JSON object key with intern cache lookup.
     * Two-pass approach: (1) scan to find closing quote, (2) compute hash
     * and do intern lookup over the known range (data in L1 cache from scan).
     * On cache hit, returns cached String instance without substring().
     */
    private static String parseAndInternKey(JsonReader r) {
        r.pos++; // skip opening "
        int start = r.pos;
        final String s = r.json;
        final int len = r.len;

        // Pass 1: scan for closing quote (no hash computation — keep loop tight)
        while (r.pos < len) {
            char c = s.charAt(r.pos);
            if (c == '"') {
                // Found closing quote — pass 2: hash + intern
                return internKeyFromString(s, start, r.pos++);
            }
            if (c == '\\') break; // fall through to slow path
            r.pos++;
        }

        // Slow path: escaped key — parse normally and intern the result
        // Reset pos to start for parseStringRaw to re-parse
        r.pos = start - 1; // back to opening "
        String result = parseStringRaw(r);
        return internFieldName(result);
    }

    /**
     * Compute hash over a known char range and do intern cache lookup.
     * Called after scanning has found the closing quote.
     */
    private static String internKeyFromString(String s, int start, int end) {
        int keyLen = end - start;
        int hash = 0;
        for (int i = start; i < end; i++) {
            hash = hash * 31 + s.charAt(i);
        }
        int slot = hash & INTERN_MASK;
        String cached = INTERN_TABLE[slot];

        // Cache hit: verify length + content without substring()
        if (cached != null && cached.length() == keyLen) {
            boolean match = true;
            for (int i = 0; i < keyLen; i++) {
                if (cached.charAt(i) != s.charAt(start + i)) {
                    match = false;
                    break;
                }
            }
            if (match) return cached; // ZERO ALLOCATION
        }

        // Cache miss: create string and cache (with JSON key form)
        String result = s.substring(start, end);
        INTERN_TABLE[slot] = result;
        INTERN_JSON_KEY[slot] = buildJsonKey(result);
        return result;
    }

    /** Parse a JSON string and return the raw Java String value (without wrapping in JqString). */
    private static String parseStringRaw(JsonReader r) {
        r.pos++; // skip opening "
        int start = r.pos;
        final String s = r.json;
        final int len = r.len;

        // Fast path: scan for closing quote, bail on backslash.
        while (r.pos < len) {
            char c = s.charAt(r.pos);
            if (c == '"') {
                // No escapes — direct substring
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

    // ========================================================================
    //  byte[]-based parser (mirrors the String-based parser above)
    //  In valid UTF-8, bytes 0x22 (") and 0x5C (\) only appear as single-byte
    //  characters -- continuation bytes are 0x80-0xBF. So scanning raw bytes
    //  for quote and backslash is safe.
    // ========================================================================

    /** Mutable parser state for byte[]-based parsing. */
    private static final class JsonByteReader {
        final byte[] data;
        final int end;
        int pos;

        JsonByteReader(byte[] data, int offset, int end) {
            this.data = data;
            this.pos = offset;
            this.end = end;
        }

        int peek() { return data[pos] & 0xFF; }

        void skipWs() {
            while (pos < end) {
                int b = data[pos] & 0xFF;
                if (b != ' ' && b != '\n' && b != '\r' && b != '\t') break;
                pos++;
            }
        }
    }

    /**
     * Parse a JSON value from a UTF-8 byte array.
     * Avoids constructing an intermediate String from the entire input.
     */
    public static JqValue parse(byte[] bytes) {
        return parse(bytes, 0, bytes.length);
    }

    /**
     * Parse a JSON value from a region of a UTF-8 byte array.
     */
    public static JqValue parse(byte[] bytes, int offset, int length) {
        int end = offset + length;
        // Skip leading whitespace
        while (offset < end && isWsByte(bytes[offset])) offset++;
        // Skip UTF-8 BOM (EF BB BF) if present
        if (end - offset >= 3 && (bytes[offset] & 0xFF) == 0xEF
                && (bytes[offset + 1] & 0xFF) == 0xBB && (bytes[offset + 2] & 0xFF) == 0xBF) {
            offset += 3;
        }
        // Skip whitespace after BOM
        while (offset < end && isWsByte(bytes[offset])) offset++;
        var reader = new JsonByteReader(bytes, offset, end);
        return parseValueBytes(reader, 0);
    }

    /**
     * Parse a stream of whitespace-separated JSON values from a UTF-8 byte array.
     */
    public static List<JqValue> parseAll(byte[] bytes) {
        return parseAll(bytes, 0, bytes.length);
    }

    /**
     * Parse a stream of whitespace-separated JSON values from a region of a UTF-8 byte array.
     */
    public static List<JqValue> parseAll(byte[] bytes, int offset, int length) {
        var results = new ArrayList<JqValue>();
        int end = offset + length;
        var reader = new JsonByteReader(bytes, offset, end);
        while (true) {
            reader.skipWs();
            if (reader.pos >= reader.end) break;
            results.add(parseValueBytes(reader, 0));
        }
        return results;
    }

    private static boolean isWsByte(byte b) {
        return b == ' ' || b == '\n' || b == '\r' || b == '\t';
    }

    private static JqValue parseValueBytes(JsonByteReader r, int depth) {
        r.skipWs();
        if (r.pos >= r.end) return JqNull.NULL;
        int b = r.data[r.pos] & 0xFF;
        return switch (b) {
            case '{' -> parseObjectBytes(r, depth + 1);
            case '[' -> parseArrayBytes(r, depth);
            case '"' -> parseStringBytes(r);
            case 't' -> { r.pos += 4; yield JqBoolean.TRUE; }
            case 'f' -> { r.pos += 5; yield JqBoolean.FALSE; }
            case 'n' -> {
                if (r.pos + 2 < r.end && r.data[r.pos + 1] == 'a' && r.data[r.pos + 2] == 'n') {
                    r.pos += 3;
                    yield JqNumber.of(Double.NaN);
                }
                r.pos += 4;
                yield JqNull.NULL;
            }
            case 'I' -> { r.pos += 8; yield JqNumber.of(Double.POSITIVE_INFINITY); }
            case 'N' -> { r.pos += 3; yield JqNumber.of(Double.NaN); }
            default -> parseNumberBytes(r);
        };
    }

    private static JqValue parseArrayBytes(JsonByteReader r, int depth) {
        ArrayDeque<JqValue[]> stack = null;
        int[] stackCounts = null;
        int stackDepth = 0;

        while (r.pos < r.end && (r.data[r.pos] & 0xFF) == '[') {
            depth++;
            if (depth > MAX_PARSE_DEPTH) throw new IllegalArgumentException("Exceeds depth limit for parsing");
            r.pos++;
            r.skipWs();

            if (r.pos < r.end && (r.data[r.pos] & 0xFF) == ']') {
                r.pos++;
                return unwindArrayStackBytes(stack, stackCounts, stackDepth, JqArray.EMPTY, r, depth);
            }
            if ((r.data[r.pos] & 0xFF) == '[') {
                if (stack == null) { stack = new ArrayDeque<>(); stackCounts = new int[16]; }
                if (stackDepth >= stackCounts.length) stackCounts = java.util.Arrays.copyOf(stackCounts, stackCounts.length * 2);
                stack.push(new JqValue[8]);
                stackCounts[stackDepth++] = 0;
                continue;
            }
            break;
        }

        JqValue[] elems = new JqValue[8];
        int count = 0;
        elems[count++] = parseValueBytes(r, depth);
        r.skipWs();
        while (r.pos < r.end && (r.data[r.pos] & 0xFF) != ']') {
            r.pos++;
            if (count >= elems.length) elems = java.util.Arrays.copyOf(elems, elems.length * 2);
            elems[count++] = parseValueBytes(r, depth);
            r.skipWs();
        }
        if (r.pos < r.end) r.pos++;
        JqValue result = JqArray.ofTrusted(elems, count);
        return unwindArrayStackBytes(stack, stackCounts, stackDepth, result, r, depth);
    }

    private static JqValue unwindArrayStackBytes(ArrayDeque<JqValue[]> stack, int[] stackCounts,
                                                  int stackDepth, JqValue result,
                                                  JsonByteReader r, int depth) {
        if (stack == null) return result;
        while (stackDepth > 0) {
            depth--;
            stackDepth--;
            JqValue[] elems = stack.pop();
            int count = stackCounts[stackDepth];
            if (count >= elems.length) elems = java.util.Arrays.copyOf(elems, elems.length * 2);
            elems[count++] = result;
            r.skipWs();
            while (r.pos < r.end && (r.data[r.pos] & 0xFF) != ']') {
                r.pos++;
                if (count >= elems.length) elems = java.util.Arrays.copyOf(elems, elems.length * 2);
                elems[count++] = parseValueBytes(r, depth);
                r.skipWs();
            }
            if (r.pos < r.end) r.pos++;
            result = JqArray.ofTrusted(elems, count);
        }
        return result;
    }

    private static JqObject parseObjectBytes(JsonByteReader r, int depth) {
        if (depth > MAX_PARSE_DEPTH) throw new IllegalArgumentException("Exceeds depth limit for parsing");
        r.pos++;
        r.skipWs();
        if (r.pos < r.end && (r.data[r.pos] & 0xFF) == '}') { r.pos++; return JqObject.EMPTY; }

        String[] keys = new String[8];
        JqValue[] values = new JqValue[8];
        int count = 0;
        while (true) {
            r.skipWs();
            if (r.pos >= r.end || (r.data[r.pos] & 0xFF) != '"') {
                throw new IllegalArgumentException("Expected '\"' for object key");
            }
            if (count >= keys.length) {
                keys = java.util.Arrays.copyOf(keys, keys.length * 2);
                values = java.util.Arrays.copyOf(values, values.length * 2);
            }
            // Keys interned for deduplication and reference equality in JqObject.get()
            keys[count] = parseAndInternKeyBytes(r);
            r.skipWs();
            r.pos++; // skip :
            values[count] = parseValueBytes(r, depth);
            count++;
            r.skipWs();
            if (r.pos >= r.end || (r.data[r.pos] & 0xFF) == '}') { r.pos++; break; }
            r.pos++; // skip ,
        }
        return JqObject.ofArrays(keys, values, count);
    }

    /** Parse a JSON string value from bytes, returning a deferred JqString. */
    private static JqString parseStringBytes(JsonByteReader r) {
        r.pos++; // skip opening "
        int contentStart = r.pos;

        // SWAR fast path: scan 8 bytes at a time for quote or backslash.
        // In valid UTF-8, 0x22 (") and 0x5C (\) only appear as single-byte
        // characters -- continuation bytes are 0x80-0xBF, so byte scanning is safe.
        while (r.pos + 8 <= r.end) {
            long word = SwarUtil.loadLong(r.data, r.pos);
            long quoteHits = SwarUtil.applyPattern(word, SwarUtil.QUOTE_PATTERN);
            long bsHits = SwarUtil.applyPattern(word, SwarUtil.BACKSLASH_PATTERN);
            long anyHit = quoteHits | bsHits;
            if (anyHit != 0) {
                r.pos += SwarUtil.getIndex(anyHit);
                int b = r.data[r.pos] & 0xFF;
                if (b == '"') {
                    int contentEnd = r.pos;
                    r.pos++;
                    return JqString.deferredBytes(r.data, contentStart, contentEnd, false);
                }
                // Must be backslash
                return parseDeferredWithEscapesBytes(r, contentStart);
            }
            r.pos += 8;
        }
        // Scalar tail for remaining < 8 bytes
        while (r.pos < r.end) {
            int b = r.data[r.pos] & 0xFF;
            if (b == '"') {
                int contentEnd = r.pos;
                r.pos++;
                return JqString.deferredBytes(r.data, contentStart, contentEnd, false);
            }
            if (b == '\\') {
                return parseDeferredWithEscapesBytes(r, contentStart);
            }
            r.pos++;
        }
        throw new IllegalArgumentException("Unterminated string");
    }

    private static JqString parseDeferredWithEscapesBytes(JsonByteReader r, int contentStart) {
        // After hitting a backslash, scan for closing quote.
        // Can't use pure SWAR here because backslash-escaped quotes must be
        // skipped. Use SWAR to find the next quote or backslash, then handle.
        while (r.pos < r.end) {
            int b = r.data[r.pos] & 0xFF;
            if (b == '"') {
                int contentEnd = r.pos;
                r.pos++;
                return JqString.deferredBytes(r.data, contentStart, contentEnd, true);
            }
            if (b == '\\') {
                r.pos++; // skip backslash
                if (r.pos < r.end && (r.data[r.pos] & 0xFF) == 'u') r.pos += 4;
            }
            r.pos++;
        }
        throw new IllegalArgumentException("Unterminated string");
    }

    /** Parse a JSON string from bytes and return the raw Java String (for object keys). */
    /**
     * Parse a JSON object key from bytes with SWAR scanning and intern cache.
     * Two-pass approach: (1) SWAR scan to find closing quote, (2) compute hash
     * and do intern lookup over the known range (data in L1 cache from scan).
     * On cache hit, returns cached String instance (zero allocation).
     */
    private static String parseAndInternKeyBytes(JsonByteReader r) {
        r.pos++; // skip opening "
        int start = r.pos;

        // Pass 1: SWAR scan to find closing quote (8 bytes at a time)
        while (r.pos + 8 <= r.end) {
            long word = SwarUtil.loadLong(r.data, r.pos);
            long quoteHits = SwarUtil.applyPattern(word, SwarUtil.QUOTE_PATTERN);
            long bsHits = SwarUtil.applyPattern(word, SwarUtil.BACKSLASH_PATTERN);
            long anyHit = quoteHits | bsHits;
            if (anyHit != 0) {
                r.pos += SwarUtil.getIndex(anyHit);
                if ((r.data[r.pos] & 0xFF) == '"') {
                    // Found closing quote — pass 2: hash + intern
                    return internKeyFromBytes(r.data, start, r.pos++);
                }
                break; // backslash — slow path
            }
            r.pos += 8;
        }
        // Scalar tail for remaining < 8 bytes
        while (r.pos < r.end) {
            int b = r.data[r.pos] & 0xFF;
            if (b == '"') {
                return internKeyFromBytes(r.data, start, r.pos++);
            }
            if (b == '\\') break;
            r.pos++;
        }

        // Slow path: escaped key — parse normally and intern
        r.pos = start - 1; // back to opening "
        String result = parseStringRawBytes(r);
        return internFieldName(result);
    }

    /**
     * Compute hash over a known byte range and do intern cache lookup.
     * Called after SWAR scan has already found the closing quote, so
     * the data is in L1 cache.
     */
    private static String internKeyFromBytes(byte[] data, int start, int end) {
        int keyLen = end - start;
        // Compute hash over known range (data in L1 cache from SWAR scan)
        int hash = 0;
        for (int i = start; i < end; i++) {
            hash = hash * 31 + (data[i] & 0xFF);
        }
        int slot = hash & INTERN_MASK;
        String cached = INTERN_TABLE[slot];

        // Cache hit: verify length + content without String construction
        if (cached != null && cached.length() == keyLen) {
            boolean match = true;
            for (int i = 0; i < keyLen; i++) {
                if (cached.charAt(i) != (char) (data[start + i] & 0xFF)) {
                    match = false;
                    break;
                }
            }
            if (match) return cached; // ZERO ALLOCATION
        }

        // Cache miss: create string and cache (with JSON key form)
        String result = new String(data, start, keyLen, java.nio.charset.StandardCharsets.UTF_8);
        INTERN_TABLE[slot] = result;
        INTERN_JSON_KEY[slot] = buildJsonKey(result);
        return result;
    }

    private static String parseStringRawBytes(JsonByteReader r) {
        r.pos++; // skip opening "
        int start = r.pos;

        // SWAR fast path: scan 8 bytes at a time for quote or backslash
        while (r.pos + 8 <= r.end) {
            long word = SwarUtil.loadLong(r.data, r.pos);
            long quoteHits = SwarUtil.applyPattern(word, SwarUtil.QUOTE_PATTERN);
            long bsHits = SwarUtil.applyPattern(word, SwarUtil.BACKSLASH_PATTERN);
            long anyHit = quoteHits | bsHits;
            if (anyHit != 0) {
                r.pos += SwarUtil.getIndex(anyHit);
                if ((r.data[r.pos] & 0xFF) == '"') {
                    String result = new String(r.data, start, r.pos - start, java.nio.charset.StandardCharsets.UTF_8);
                    r.pos++;
                    return result;
                }
                break; // backslash -- fall through to slow path
            }
            r.pos += 8;
        }
        // Scalar tail: scan remaining < 8 bytes
        while (r.pos < r.end) {
            int b = r.data[r.pos] & 0xFF;
            if (b == '"') {
                String result = new String(r.data, start, r.pos - start, java.nio.charset.StandardCharsets.UTF_8);
                r.pos++;
                return result;
            }
            if (b == '\\') break;
            r.pos++;
        }

        // Slow path: string contains escape sequences
        // Convert the byte range to String first, then process escapes
        int scanStart = start;
        // Find closing quote
        int escStart = r.pos;
        while (r.pos < r.end) {
            int b = r.data[r.pos] & 0xFF;
            if (b == '"') break;
            if (b == '\\') {
                r.pos++;
                if (r.pos < r.end && (r.data[r.pos] & 0xFF) == 'u') r.pos += 4;
            }
            r.pos++;
        }
        int contentEnd = r.pos;
        r.pos++; // skip closing "
        String raw = new String(r.data, start, contentEnd - start, java.nio.charset.StandardCharsets.UTF_8);
        return JqString.unescapeJson(raw, 0, raw.length());
    }

    private static JqNumber parseNumberBytes(JsonByteReader r) {
        int start = r.pos;
        boolean negative = false;

        if (r.pos < r.end && (r.data[r.pos] & 0xFF) == '-') {
            negative = true;
            r.pos++;
            if (r.pos < r.end) {
                if ((r.data[r.pos] & 0xFF) == 'I') { r.pos += 8; return JqNumber.of(Double.NEGATIVE_INFINITY); }
                if ((r.data[r.pos] & 0xFF) == 'N') { r.pos += 3; return JqNumber.of(Double.NaN); }
            }
        }

        long acc = 0;
        boolean overflow = false;
        int intDigitCount = 0;
        while (r.pos < r.end) {
            int d = (r.data[r.pos] & 0xFF) - '0';
            if (d < 0 || d > 9) break;
            if (!overflow) {
                long next = acc * 10 + d;
                if (next < acc) overflow = true;
                else acc = next;
            }
            intDigitCount++;
            r.pos++;
        }

        boolean isDecimal = false;
        long fracPart = 0;
        int fracCount = 0;
        if (r.pos < r.end && (r.data[r.pos] & 0xFF) == '.') {
            isDecimal = true;
            r.pos++;
            while (r.pos < r.end) {
                int d = (r.data[r.pos] & 0xFF) - '0';
                if (d < 0 || d > 9) break;
                if (fracCount < 18) { fracPart = fracPart * 10 + d; fracCount++; }
                r.pos++;
            }
        }

        int expValue = 0;
        if (r.pos < r.end && ((r.data[r.pos] & 0xFF) == 'e' || (r.data[r.pos] & 0xFF) == 'E')) {
            isDecimal = true;
            r.pos++;
            boolean expNegative = false;
            if (r.pos < r.end && (r.data[r.pos] & 0xFF) == '-') { expNegative = true; r.pos++; }
            else if (r.pos < r.end && (r.data[r.pos] & 0xFF) == '+') { r.pos++; }
            while (r.pos < r.end) {
                int d = (r.data[r.pos] & 0xFF) - '0';
                if (d < 0 || d > 9) break;
                expValue = expValue * 10 + d;
                r.pos++;
            }
            if (expNegative) expValue = -expValue;
        }

        if (!isDecimal && !overflow && intDigitCount <= 18) {
            return JqNumber.of(negative ? -acc : acc);
        }

        if (isDecimal && !overflow && intDigitCount <= 15 && fracCount <= 15) {
            int totalDigits = intDigitCount + fracCount;
            if (totalDigits <= 15 && expValue >= -18 && expValue <= 18) {
                double value = (double) acc;
                if (fracCount > 0) value += (double) fracPart / POW10[fracCount];
                if (expValue > 0 && expValue < POW10.length) value *= POW10[expValue];
                else if (expValue < 0 && -expValue < POW10.length) value /= POW10[-expValue];
                else if (expValue != 0) value *= Math.pow(10, expValue);
                return JqNumber.of(negative ? -value : value);
            }
        }

        // Fallback: convert byte range to String for BigDecimal/Double parsing
        String numStr = new String(r.data, start, r.pos - start, java.nio.charset.StandardCharsets.UTF_8);
        if (isDecimal) {
            try { return JqNumber.of(new BigDecimal(numStr)); }
            catch (NumberFormatException | ArithmeticException e) { return JqNumber.of(Double.parseDouble(numStr)); }
        }
        try { return JqNumber.of(Long.parseLong(numStr)); }
        catch (NumberFormatException e) {
            try { return JqNumber.of(new BigDecimal(numStr)); }
            catch (NumberFormatException | ArithmeticException e2) { return JqNumber.of(Double.parseDouble(numStr)); }
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

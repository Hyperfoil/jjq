package io.hyperfoil.tools.jjq.value;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Zero-allocation SWAR JSON byte skipper and path-guided extractor (issue #211).
 *
 * <p>Navigates raw UTF-8 JSON bytes directly for simple path plans
 * (chained field/index lookups, fused array field collection, and flat object
 * projection), materializing only the matched value(s) via
 * {@link JqValues#parseValueBytes}. Any unsupported construct, escaped wire
 * key, type mismatch, or malformed/truncated input returns {@code null} so
 * the caller falls back cleanly to full {@link JqValues#parse(byte[], int, int)}.</p>
 */
final class JsonByteSkipper {

    private static final int NOT_FOUND = -1;
    private static final int FALLBACK = -2;

    private JsonByteSkipper() {}

    /**
     * Encode a field name to clean raw UTF-8 bytes suitable for direct wire
     * matching, or {@code null} if it contains {@code '"'}, {@code '\\'}, or
     * a control byte {@code < 0x20}.
     */
    static byte[] cleanUtf8Key(String name) {
        byte[] encoded = name.getBytes(StandardCharsets.UTF_8);
        for (byte b : encoded) {
            int ub = b & 0xFF;
            if (ub == '"' || ub == '\\' || ub < 0x20) {
                return null;
            }
        }
        return encoded;
    }

    /**
     * Navigate a chain of field and non-negative array-index steps and
     * materialize the target value. Returns {@code null} to signal fallback
     * to full parsing.
     */
    static JqValue extractPath(byte[] bytes, int offset, int length,
                               byte[][] fieldSteps, int[] indexSteps) {
        int end = offset + length;
        int pos = skipLeadingWsAndBom(bytes, offset, end);
        if (pos >= end) return null;

        int targetPos = navigateChain(bytes, pos, end, fieldSteps, indexSteps);
        if (targetPos == FALLBACK) return null;
        if (targetPos == NOT_FOUND) return JqNull.NULL;

        var reader = new JqValues.JsonByteReader(bytes, targetPos, end);
        return JqValues.parseValueBytes(reader, 0);
    }

    /**
     * Navigate optional prefix steps to an array, then extract a tail chain
     * from each element without allocating intermediate element objects.
     * Returns {@code null} to signal fallback to full parsing.
     */
    static JqValue extractCollectField(byte[] bytes, int offset, int length,
                                       byte[][] prefixFields, int[] prefixIndices,
                                       byte[][] tailFields, int[] tailIndices) {
        int end = offset + length;
        int pos = skipLeadingWsAndBom(bytes, offset, end);
        if (pos >= end) return null;

        if (prefixFields.length > 0) {
            pos = navigateChain(bytes, pos, end, prefixFields, prefixIndices);
            if (pos < 0) return null; // NOT_FOUND or FALLBACK -> preserve exact error/empty semantics
        }
        if (pos >= end || bytes[pos] != '[') return null;

        int p = skipWs(bytes, pos + 1, end);
        if (p >= end) return null;
        if (bytes[p] == ']') return JqArray.EMPTY;

        boolean singleFieldTail = tailFields.length == 1 && tailFields[0] != null;
        byte[] singleField = singleFieldTail ? tailFields[0] : null;
        var builder = JqArray.arrayBuilder(16);
        var reader = new JqValues.JsonByteReader(bytes, p, end);

        while (true) {
            if (singleFieldTail && bytes[p] == '{') {
                int valPos = findFieldValue(bytes, p, end, singleField);
                if (valPos == FALLBACK) return null;
                if (valPos == NOT_FOUND) {
                    builder.add(JqNull.NULL);
                    p = skipContainer(bytes, p, end);
                    if (p < 0) return null;
                } else {
                    reader.pos = valPos;
                    builder.add(JqValues.parseValueBytes(reader, 0));
                    p = skipToContainerEnd(bytes, reader.pos, end);
                    if (p < 0) return null;
                }
            } else if (isNullLiteral(bytes, p, end)) {
                builder.add(JqNull.NULL);
                p += 4;
            } else if (!singleFieldTail && (bytes[p] == '{' || bytes[p] == '[')) {
                int valPos = navigateChain(bytes, p, end, tailFields, tailIndices);
                if (valPos == FALLBACK) return null;
                if (valPos == NOT_FOUND) {
                    builder.add(JqNull.NULL);
                } else {
                    reader.pos = valPos;
                    builder.add(JqValues.parseValueBytes(reader, 0));
                }
                p = skipContainer(bytes, p, end);
                if (p < 0) return null;
            } else {
                return null;
            }

            p = skipWs(bytes, p, end);
            if (p >= end) return null;
            byte sep = bytes[p];
            if (sep == ']') {
                return builder.build();
            }
            if (sep != ',') return null;
            p = skipWs(bytes, p + 1, end);
            if (p >= end) return null;
        }
    }

    /**
     * Navigate optional prefix steps to an object, then extract a fixed set of
     * fields into a new {@link JqObject} sharing {@code outKeys}. Stops early
     * once every distinct requested source field has been matched. Returns
     * {@code null} to signal fallback to full parsing.
     */
    static JqValue extractObjectFields(byte[] bytes, int offset, int length,
                                       byte[][] prefixFields, int[] prefixIndices,
                                       String[] outKeys, byte[][] srcFields) {
        int end = offset + length;
        int pos = skipLeadingWsAndBom(bytes, offset, end);
        if (pos >= end) return null;

        if (prefixFields.length > 0) {
            pos = navigateChain(bytes, pos, end, prefixFields, prefixIndices);
            if (pos == FALLBACK) return null;
            if (pos == NOT_FOUND) {
                JqValue[] nulls = new JqValue[outKeys.length];
                Arrays.fill(nulls, JqNull.NULL);
                return JqObject.ofArrays(outKeys, nulls, outKeys.length);
            }
        }
        if (pos >= end) return null;
        if (isNullLiteral(bytes, pos, end)) {
            JqValue[] nulls = new JqValue[outKeys.length];
            Arrays.fill(nulls, JqNull.NULL);
            return JqObject.ofArrays(outKeys, nulls, outKeys.length);
        }
        if (bytes[pos] != '{') return null;

        int n = outKeys.length;
        JqValue[] values = new JqValue[n];
        int remaining = n;
        int p = skipWs(bytes, pos + 1, end);
        if (p >= end) return null;
        if (bytes[p] == '}') {
            Arrays.fill(values, JqNull.NULL);
            return JqObject.ofArrays(outKeys, values, n);
        }

        var reader = new JqValues.JsonByteReader(bytes, p, end);
        while (true) {
            if (p >= end || bytes[p] != '"') return null;
            int keyStart = p + 1;
            int afterQuote = skipUnescapedKey(bytes, p, end);
            if (afterQuote < 0) return null;
            int keyLen = (afterQuote - 1) - keyStart;

            int colonPos = skipWs(bytes, afterQuote, end);
            if (colonPos >= end || bytes[colonPos] != ':') return null;
            int valStart = skipWs(bytes, colonPos + 1, end);
            if (valStart >= end) return null;

            int matchedSlot = -1;
            for (int j = 0; j < n; j++) {
                if (values[j] != null) continue;
                byte[] expected = srcFields[j];
                if (expected.length == keyLen
                        && Arrays.equals(bytes, keyStart, keyStart + keyLen, expected, 0, keyLen)) {
                    matchedSlot = j;
                    break;
                }
            }

            if (matchedSlot >= 0) {
                reader.pos = valStart;
                JqValue parsed = JqValues.parseValueBytes(reader, 0);
                values[matchedSlot] = parsed;
                remaining--;
                // Also fill any alias slots requesting the same source field
                byte[] matchedKey = srcFields[matchedSlot];
                for (int j = matchedSlot + 1; j < n; j++) {
                    if (values[j] == null && srcFields[j].length == keyLen
                            && Arrays.equals(matchedKey, srcFields[j])) {
                        values[j] = parsed;
                        remaining--;
                    }
                }
                if (remaining == 0) {
                    return JqObject.ofArrays(outKeys, values, n);
                }
                p = reader.pos;
            } else {
                p = skipValue(bytes, valStart, end);
                if (p < 0) return null;
            }

            p = skipWs(bytes, p, end);
            if (p >= end) return null;
            byte sep = bytes[p];
            if (sep == '}') {
                for (int j = 0; j < n; j++) {
                    if (values[j] == null) values[j] = JqNull.NULL;
                }
                return JqObject.ofArrays(outKeys, values, n);
            }
            if (sep != ',') return null;
            p = skipWs(bytes, p + 1, end);
        }
    }

    private static int navigateChain(byte[] d, int pos, int end,
                                     byte[][] fieldSteps, int[] indexSteps) {
        int p = pos;
        for (int s = 0; s < fieldSteps.length; s++) {
            byte[] field = fieldSteps[s];
            if (field != null) {
                p = findFieldValue(d, p, end, field);
            } else {
                p = findArrayElement(d, p, end, indexSteps[s]);
            }
            if (p < 0) return p; // NOT_FOUND or FALLBACK
        }
        return p;
    }

    /**
     * Find the value start offset for {@code target} inside the JSON object at
     * {@code objPos}. Returns {@link #NOT_FOUND} when absent (or on {@code null}),
     * or {@link #FALLBACK} on non-object, escaped wire key, or malformed input.
     */
    private static int findFieldValue(byte[] d, int objPos, int end, byte[] target) {
        if (objPos >= end) return FALLBACK;
        byte first = d[objPos];
        if (first != '{') {
            return isNullLiteral(d, objPos, end) ? NOT_FOUND : FALLBACK;
        }
        int p = skipWs(d, objPos + 1, end);
        if (p >= end) return FALLBACK;
        if (d[p] == '}') return NOT_FOUND;

        final int targetLen = target.length;
        while (true) {
            if (p >= end || d[p] != '"') return FALLBACK;
            int start = p + 1;
            int close = start + targetLen;
            if (close < end && d[close] == '"'
                    && Arrays.equals(d, start, close, target, 0, targetLen)) {
                int colon = skipWs(d, close + 1, end);
                if (colon >= end || d[colon] != ':') return FALLBACK;
                int valPos = skipWs(d, colon + 1, end);
                return valPos < end ? valPos : FALLBACK;
            }
            // Non-matching key: skip via SWAR, falling back on any backslash escape
            int afterQuote = skipUnescapedKey(d, p, end);
            if (afterQuote < 0) return FALLBACK;
            int colon = skipWs(d, afterQuote, end);
            if (colon >= end || d[colon] != ':') return FALLBACK;
            int afterVal = skipValue(d, colon + 1, end);
            if (afterVal < 0) return FALLBACK;
            p = skipWs(d, afterVal, end);
            if (p >= end) return FALLBACK;
            byte sep = d[p];
            if (sep == '}') return NOT_FOUND;
            if (sep != ',') return FALLBACK;
            p = skipWs(d, p + 1, end);
        }
    }

    /**
     * Find the element start offset at {@code targetIndex} (>= 0) inside the
     * JSON array at {@code arrPos}. Returns {@link #NOT_FOUND} when out of
     * bounds (or on {@code null}), or {@link #FALLBACK} on non-array/malformed.
     */
    private static int findArrayElement(byte[] d, int arrPos, int end, int targetIndex) {
        if (arrPos >= end) return FALLBACK;
        byte first = d[arrPos];
        if (first != '[') {
            return isNullLiteral(d, arrPos, end) ? NOT_FOUND : FALLBACK;
        }
        int p = skipWs(d, arrPos + 1, end);
        if (p >= end) return FALLBACK;
        if (d[p] == ']') return NOT_FOUND;

        int idx = 0;
        while (true) {
            if (idx == targetIndex) return p;
            int afterVal = skipValue(d, p, end);
            if (afterVal < 0) return FALLBACK;
            p = skipWs(d, afterVal, end);
            if (p >= end) return FALLBACK;
            byte sep = d[p];
            if (sep == ']') return NOT_FOUND;
            if (sep != ',') return FALLBACK;
            p = skipWs(d, p + 1, end);
            if (p >= end) return FALLBACK;
            idx++;
        }
    }

    /**
     * Skip an unescaped key string starting at opening quote {@code pos}.
     * Returns the offset immediately after the closing quote, or {@link #FALLBACK}
     * if a backslash escape or EOF is encountered.
     */
    static int skipUnescapedKey(byte[] d, int pos, int end) {
        int p = pos + 1;
        while (p + 8 <= end) {
            long word = SwarUtil.loadLong(d, p);
            long hit = SwarUtil.applyPattern(word, SwarUtil.QUOTE_PATTERN)
                     | SwarUtil.applyPattern(word, SwarUtil.BACKSLASH_PATTERN);
            if (hit != 0) {
                p += SwarUtil.getIndex(hit);
                return d[p] == '"' ? p + 1 : FALLBACK;
            }
            p += 8;
        }
        while (p < end) {
            byte b = d[p];
            if (b == '"') return p + 1;
            if (b == '\\') return FALLBACK;
            p++;
        }
        return FALLBACK;
    }

    /**
     * Skip any JSON string starting at opening quote {@code pos}, handling
     * backslash escapes. Returns offset past closing quote, or {@link #FALLBACK}.
     */
    static int skipString(byte[] d, int pos, int end) {
        int p = pos + 1;
        while (p + 8 <= end) {
            long word = SwarUtil.loadLong(d, p);
            long hit = SwarUtil.applyPattern(word, SwarUtil.QUOTE_PATTERN)
                     | SwarUtil.applyPattern(word, SwarUtil.BACKSLASH_PATTERN);
            if (hit != 0) {
                p += SwarUtil.getIndex(hit);
                if (d[p] == '"') return p + 1;
                p += 2; // skip backslash and escaped byte
                continue;
            }
            p += 8;
        }
        while (p < end) {
            byte b = d[p];
            if (b == '"') return p + 1;
            if (b == '\\') {
                p += 2;
                continue;
            }
            p++;
        }
        return FALLBACK;
    }

    /** Skip a JSON object or array starting at its opening {@code '{'} or {@code '['}. */
    static int skipContainer(byte[] d, int pos, int end) {
        return skipToContainerEnd(d, pos + 1, end);
    }

    /**
     * Advance from inside an already-open container (depth = 1) to the byte
     * immediately past its matching closing brace/bracket.
     */
    private static int skipToContainerEnd(byte[] d, int startPos, int end) {
        int depth = 1;
        int p = startPos;
        while (p < end) {
            byte b = d[p];
            if (b == '"') {
                p = skipString(d, p, end);
                if (p < 0) return FALLBACK;
                continue;
            }
            if (b == '{' || b == '[') {
                if (++depth > JqValues.MAX_PARSE_DEPTH) return FALLBACK;
            } else if (b == '}' || b == ']') {
                if (--depth == 0) return p + 1;
            }
            p++;
        }
        return FALLBACK;
    }

    /** Skip any JSON value starting at or after {@code pos}. */
    static int skipValue(byte[] d, int pos, int end) {
        int p = skipWs(d, pos, end);
        if (p >= end) return FALLBACK;
        byte b = d[p];
        if (b == '"') return skipString(d, p, end);
        if (b == '{' || b == '[') return skipContainer(d, p, end);
        int start = p;
        while (p < end) {
            byte c = d[p];
            if (c == ',' || c == '}' || c == ']' || c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                return p > start ? p : FALLBACK;
            }
            p++;
        }
        return p > start ? p : FALLBACK;
    }

    private static int skipLeadingWsAndBom(byte[] d, int offset, int end) {
        int p = skipWs(d, offset, end);
        if (end - p >= 3 && (d[p] & 0xFF) == 0xEF
                && (d[p + 1] & 0xFF) == 0xBB && (d[p + 2] & 0xFF) == 0xBF) {
            p = skipWs(d, p + 3, end);
        }
        return p;
    }

    private static int skipWs(byte[] d, int p, int end) {
        while (p < end) {
            byte b = d[p];
            if (b != ' ' && b != '\n' && b != '\r' && b != '\t') break;
            p++;
        }
        return p;
    }

    private static boolean isNullLiteral(byte[] d, int p, int end) {
        if (p + 4 > end || d[p] != 'n' || d[p + 1] != 'u' || d[p + 2] != 'l' || d[p + 3] != 'l') {
            return false;
        }
        if (p + 4 == end) return true;
        byte next = d[p + 4];
        return next == ',' || next == '}' || next == ']'
                || next == ' ' || next == '\n' || next == '\r' || next == '\t';
    }
}

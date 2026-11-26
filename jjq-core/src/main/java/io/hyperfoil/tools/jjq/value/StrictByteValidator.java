package io.hyperfoil.tools.jjq.value;

/**
 * Strict-grammar validation gate for on-demand {@code applyStrict(byte[])}
 * extraction (issue #211 Phase 4).
 *
 * <p>Walks raw UTF-8 JSON bytes and decides whether a leniently-extracted
 * result is trustworthy for strict semantics. This is a gate, not a parser:
 * any rejection simply makes the caller fall back to
 * {@code apply(JqValues.parseStrict(...))}, which produces the exact result
 * or exception. The validator must therefore catch every input the strict
 * parser rejects (a rejection superset) — extra rejections are safe, just
 * slower.</p>
 *
 * <p>Checks: UTF-8 validity (upfront), strict number grammar, exact literal
 * spelling, separator placement, container termination, nesting depth,
 * trailing content, escape-sequence validity, and conservative surrogate
 * handling (paired surrogate escapes pass; unpaired ones reject so the full
 * parser applies the configured policy). Raw control characters inside
 * strings are accepted, mirroring the strict parser. BOMs always reject
 * here so the full parser applies {@code BomPolicy} exactly.</p>
 */
final class StrictByteValidator {

    private StrictByteValidator() {}

    /**
     * @return true when the range holds exactly one strict-valid JSON value
     *         (modulo duplicate keys, which the caller must handle by policy)
     */
    static boolean validate(byte[] d, int offset, int length, ParseOptions options) {
        if (Utf8.validate(d, offset, length) >= 0) return false;
        int end = offset + length;
        int p = skipWs(d, offset, end);
        if (p >= end) return false; // empty: full parser binds JqNull
        if (end - p >= 3 && (d[p] & 0xFF) == 0xEF
                && (d[p + 1] & 0xFF) == 0xBB && (d[p + 2] & 0xFF) == 0xBF) {
            return false; // BOM: let the full parser apply BomPolicy
        }
        int maxDepth = options.maxDepth();
        p = validateValue(d, p, end, 0, maxDepth);
        if (p < 0) return false;
        p = skipWs(d, p, end);
        return p == end;
    }

    /** Validate one value dispatched at nesting {@code level}; containers enter at {@code level + 1}. */
    private static int validateValue(byte[] d, int p, int end, int level, int maxDepth) {
        if (p >= end) return -1;
        byte b = d[p];
        switch (b) {
            case '{': return validateObject(d, p, end, level + 1, maxDepth);
            case '[': return validateArray(d, p, end, level + 1, maxDepth);
            case '"': return validateString(d, p, end);
            case 't': return expect(d, p, end, "true");
            case 'f': return expect(d, p, end, "false");
            case 'n': return expect(d, p, end, "null");
            default:
                return (b == '-' || (b >= '0' && b <= '9')) ? validateNumber(d, p, end) : -1;
        }
    }

    private static int validateObject(byte[] d, int p, int end, int level, int maxDepth) {
        if (level > maxDepth) return -1;
        p = skipWs(d, p + 1, end);
        if (p >= end) return -1;
        if (d[p] == '}') return p + 1;
        while (true) {
            if (p >= end || d[p] != '"') return -1; // key expected
            p = validateString(d, p, end);
            if (p < 0) return -1;
            p = skipWs(d, p, end);
            if (p >= end || d[p] != ':') return -1;
            p = skipWs(d, p + 1, end);
            if (p >= end) return -1;
            p = validateValue(d, p, end, level, maxDepth);
            if (p < 0) return -1;
            p = skipWs(d, p, end);
            if (p >= end) return -1; // unterminated object
            byte sep = d[p];
            if (sep == '}') return p + 1;
            if (sep != ',') return -1;
            p = skipWs(d, p + 1, end);
            if (p >= end) return -1;
        }
    }

    private static int validateArray(byte[] d, int p, int end, int level, int maxDepth) {
        if (level > maxDepth) return -1;
        p = skipWs(d, p + 1, end);
        if (p >= end) return -1; // unterminated array
        if (d[p] == ']') return p + 1;
        while (true) {
            p = validateValue(d, p, end, level, maxDepth);
            if (p < 0) return -1;
            p = skipWs(d, p, end);
            if (p >= end) return -1; // unterminated array
            byte sep = d[p];
            if (sep == ']') return p + 1;
            if (sep != ',') return -1;
            p = skipWs(d, p + 1, end);
            if (p >= end || d[p] == ']') return -1; // trailing comma or EOF
        }
    }

    /**
     * Validate a string starting at its opening quote. Mirrors the strict
     * parser: escape sequences must be legal, paired surrogate escapes pass,
     * unpaired ones reject (conservative across policies), raw control
     * characters are accepted like the parser accepts them.
     *
     * @return the offset past the closing quote, or -1
     */
    static int validateString(byte[] d, int pos, int end) {
        int p = pos + 1;
        while (p + 8 <= end) {
            long word = SwarUtil.loadLong(d, p);
            long hit = SwarUtil.applyPattern(word, SwarUtil.QUOTE_PATTERN)
                     | SwarUtil.applyPattern(word, SwarUtil.BACKSLASH_PATTERN);
            if (hit != 0) {
                p += SwarUtil.getIndex(hit);
                if (d[p] == '"') return p + 1;
                p = validateEscape(d, p + 1, end);
                if (p < 0) return -1;
                continue;
            }
            p += 8;
        }
        while (p < end) {
            byte b = d[p];
            if (b == '"') return p + 1;
            if (b == '\\') {
                p = validateEscape(d, p + 1, end);
                if (p < 0) return -1;
                continue;
            }
            p++;
        }
        return -1; // unterminated string
    }

    /**
     * Validate the escape whose character is at {@code p} (the backslash is
     * at {@code p - 1}). Paired surrogate escapes consume both halves.
     *
     * @return the offset just past the escape sequence, or -1
     */
    private static int validateEscape(byte[] d, int p, int end) {
        if (p >= end) return -1; // unterminated escape
        int e = d[p] & 0xFF;
        switch (e) {
            case '"', '\\', '/', 'b', 'f', 'n', 'r', 't':
                return p + 1;
            case 'u': {
                if (p + 5 > end) return -1;
                for (int i = 1; i <= 4; i++) {
                    if (!isHex(d[p + i] & 0xFF)) return -1;
                }
                int unit = (hexVal(d[p + 1]) << 12) | (hexVal(d[p + 2]) << 8)
                        | (hexVal(d[p + 3]) << 4) | hexVal(d[p + 4]);
                if (unit >= 0xD800 && unit <= 0xDBFF) {
                    // High surrogate: a complete low-half escape must follow.
                    if (p + 11 > end
                            || d[p + 5] != '\\' || d[p + 6] != 'u'
                            || !isHex(d[p + 7] & 0xFF) || !isHex(d[p + 8] & 0xFF)
                            || !isHex(d[p + 9] & 0xFF) || !isHex(d[p + 10] & 0xFF)) {
                        return -1;
                    }
                    int low = (hexVal(d[p + 7]) << 12) | (hexVal(d[p + 8]) << 8)
                            | (hexVal(d[p + 9]) << 4) | hexVal(d[p + 10]);
                    if (low < 0xDC00 || low > 0xDFFF) return -1;
                    return p + 11;
                }
                if (unit >= 0xDC00 && unit <= 0xDFFF) return -1; // unpaired low
                return p + 5;
            }
            default:
                return -1; // invalid escape
        }
    }

    /**
     * Validate a strict JSON number starting at {@code p}: mirrors the
     * parser's strict arms (no missing digits, no leading zeros, fraction
     * and exponent need at least one digit).
     *
     * @return the offset just past the number token, or -1
     */
    static int validateNumber(byte[] d, int p, int end) {
        if (d[p] == '-') {
            p++;
            if (p >= end) return -1;
        }
        if (d[p] == '0') {
            p++;
        } else if (d[p] >= '1' && d[p] <= '9') {
            while (p < end && isDigit(d[p])) p++;
        } else {
            return -1; // no digits
        }
        if (p < end && isDigit(d[p])) return -1; // leading zero like "01"
        if (p < end && d[p] == '.') {
            p++;
            int frac = 0;
            while (p < end && isDigit(d[p])) { p++; frac++; }
            if (frac == 0) return -1;
        }
        if (p < end && (d[p] == 'e' || d[p] == 'E')) {
            p++;
            if (p < end && (d[p] == '+' || d[p] == '-')) p++;
            int exp = 0;
            while (p < end && isDigit(d[p])) { p++; exp++; }
            if (exp == 0) return -1;
        }
        return p;
    }

    private static int expect(byte[] d, int p, int end, String literal) {
        if (p + literal.length() > end) return -1;
        for (int i = 0; i < literal.length(); i++) {
            if ((d[p + i] & 0xFF) != literal.charAt(i)) return -1;
        }
        return p + literal.length();
    }

    private static boolean isDigit(byte b) {
        return b >= '0' && b <= '9';
    }

    private static boolean isHex(int b) {
        return (b >= '0' && b <= '9') || (b >= 'a' && b <= 'f') || (b >= 'A' && b <= 'F');
    }

    private static int hexVal(byte b) {
        int v = b & 0xFF;
        if (v <= '9') return v - '0';
        if (v <= 'F') return v - 'A' + 10;
        return v - 'a' + 10;
    }

    private static int skipWs(byte[] d, int p, int end) {
        while (p < end) {
            byte b = d[p];
            if (b != ' ' && b != '\n' && b != '\r' && b != '\t') break;
            p++;
        }
        return p;
    }
}

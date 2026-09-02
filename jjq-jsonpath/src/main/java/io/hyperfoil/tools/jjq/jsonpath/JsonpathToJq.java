package io.hyperfoil.tools.jjq.jsonpath;

import io.hyperfoil.tools.jjq.JqProgram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Converts PostgreSQL SQL/JSON path expressions to jq expressions.
 *
 * <p>Supports both <b>strict</b> and <b>lax</b> modes:</p>
 * <ul>
 *   <li><b>Strict</b>: direct translation — {@code $.a.b.c} → {@code .a.b.c}.
 *       Fails when a dot-access encounters an array (matches jq semantics).</li>
 *   <li><b>Lax</b> (PostgreSQL default): auto-unwraps arrays at intermediate
 *       dot-access segments. {@code $.a.b.c} → conditional unwrap at each level,
 *       so it works regardless of whether intermediate values are arrays or objects.</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * // Convert to jq string
 * String jq = JsonpathToJq.convert("$.results[*] ?(@.name == \"x\")");
 * // ".results[]? | select(.name == \"x\")"
 *
 * // Convert and compile (validated at conversion time)
 * JqProgram program = JsonpathToJq.compile("$.a.b.c", Mode.LAX);
 * JqValue result = program.apply(data);
 * }</pre>
 */
public final class JsonpathToJq {

    /** SQL/JSON path evaluation mode. */
    public enum Mode {
        /** Direct translation — dot-access on arrays fails (matches jq semantics). */
        STRICT,
        /** PostgreSQL default — auto-unwraps arrays at intermediate dot-access segments. */
        LAX
    }

    private JsonpathToJq() {}

    /**
     * Convert a SQL/JSON path expression to jq using lax mode (PostgreSQL default).
     *
     * @param jsonpath the SQL/JSON path expression (e.g., {@code $.a.b.c})
     * @return the equivalent jq expression
     */
    public static String convert(String jsonpath) {
        return convert(jsonpath, Mode.LAX);
    }

    /**
     * Convert a SQL/JSON path expression to jq with the specified mode.
     *
     * @param jsonpath the SQL/JSON path expression
     * @param mode strict or lax evaluation mode
     * @return the equivalent jq expression
     */
    public static String convert(String jsonpath, Mode mode) {
        if (jsonpath == null || jsonpath.isEmpty()) return ".";

        String jq = jsonpath.trim();
        // Handle "strict" / "lax" prefix in the expression itself
        if (jq.startsWith("strict ")) {
            mode = Mode.STRICT;
            jq = jq.substring(7).trim();
        } else if (jq.startsWith("lax ")) {
            mode = Mode.LAX;
            jq = jq.substring(4).trim();
        }

        if (jq.equals("$")) return ".";

        // Basic structural transformations
        jq = jq.replace("$.", ".");
        if (jq.startsWith("$[")) jq = "." + jq.substring(1); // $[*] → .[*]
        if (jq.equals("$")) jq = ".";
        // Replace remaining bare $ references (e.g., "$ ?" becomes ". ?")
        if (jq.startsWith("$ ")) jq = ". " + jq.substring(2);
        jq = jq.replace("[*].*", "[]?");
        // In PostgreSQL lax mode, [*] on an object auto-wraps as [obj] then iterates,
        // returning the object unchanged. When followed by .keyvalue(), the intent is to
        // enumerate the object's own entries — not iterate the object's values first.
        // jq's .[]? iterates values (not entries), so [*].keyvalue() must collapse to
        // just .keyvalue() (which becomes | to_entries[]).
        jq = jq.replace("[*].keyvalue()", ".keyvalue()");
        jq = jq.replace("[*]", "[]?");

        // Replace **{N} recursive descent BEFORE .* — otherwise .* corrupts the **{N} pattern
        jq = jq.replaceAll("\\.\\*\\*\\{\\d+\\}", " | recurse");

        // Replace .* (wildcard object values) with []? but only OUTSIDE quoted strings.
        jq = replaceOutsideQuotes(jq, ".*", "[]?");
        // Fix: if .* was at the start (from $.*), the leading . got consumed by the pattern.
        // A jq expression starting with [] is invalid — prepend . to restore root reference.
        if (jq.startsWith("[]?")) jq = "." + jq;

        // Replace 'last' keyword in array indices with negative indices
        // [last - N] → [-(N+1)], [last] → [-1]
        java.util.regex.Matcher lastMatcher = java.util.regex.Pattern.compile(
                "\\[last\\s*-\\s*(\\d+)\\s*]").matcher(jq);
        StringBuilder lastSb = new StringBuilder();
        while (lastMatcher.find()) {
            int n = Integer.parseInt(lastMatcher.group(1));
            lastMatcher.appendReplacement(lastSb, "[" + (-(n + 1)) + "]");
        }
        lastMatcher.appendTail(lastSb);
        jq = lastSb.toString();
        jq = jq.replaceAll("\\[last\\s*]", "[-1]");

        // Replace array range [M to N] → [M:N+1] (PostgreSQL 'to' is inclusive, jq slice end is exclusive)
        java.util.regex.Matcher rangeMatcher = java.util.regex.Pattern.compile(
                "\\[(\\d+)\\s+to\\s+(\\d+)\\s*]").matcher(jq);
        StringBuilder rangeSb = new StringBuilder();
        while (rangeMatcher.find()) {
            int from = Integer.parseInt(rangeMatcher.group(1));
            int to = Integer.parseInt(rangeMatcher.group(2));
            rangeMatcher.appendReplacement(rangeSb, "[" + from + ":" + (to + 1) + "]");
        }
        rangeMatcher.appendTail(rangeSb);
        jq = rangeSb.toString();

        // Replace PostgreSQL jsonpath methods with jq equivalents.
        // Handle .size() inside array brackets first — these need different treatment:
        // $[$.path.size()-1] → [.path | length-1] (not [.path | length-1])
        // The $.path inside brackets is already converted to .path by the $. replacement above.
        jq = convertSizeInBrackets(jq);
        jq = jq.replace(".size()", " | length");
        jq = jq.replace(".keyvalue()", " | to_entries[]");
        jq = jq.replace(".double()", " | tonumber");
        jq = jq.replace(".string()", " | tostring");
        jq = jq.replace(".type()", " | type");
        jq = jq.replace(".boolean()",
                " | if type == \"boolean\" then ." +
                " elif type == \"number\" then . != 0" +
                " elif type == \"string\" then (. == \"t\" or . == \"true\" or . == \"y\" or . == \"yes\" or . == \"on\" or . == \"1\")" +
                " elif . == null then false" +
                " else null end");
        jq = jq.replace(".ceiling()", " | ceil");
        jq = jq.replace(".floor()", " | floor");
        jq = jq.replace(".abs()", " | fabs");

        // Convert PostgreSQL jsonpath filter expressions to jq select()
        if (jq.contains("?")) {
            jq = convertFilters(jq);
        }

        // Apply lax array unwrapping if requested
        if (mode == Mode.LAX) {
            jq = convertToLaxChains(jq);
            jq = applyLaxAutoWrap(jq);
        }

        // Fix: if the expression starts with " | " or "| " (from $.method() patterns
        // where $.→. then .method()→ | method consumes the leading dot), prepend identity.
        if (jq.startsWith(" | ")) jq = "." + jq;
        else if (jq.startsWith("| ")) jq = ". " + jq;

        // Lax error suppression: PostgreSQL lax mode silently produces empty results
        // for type mismatches. Must be AFTER the leading-dot fix.
        if (mode == Mode.LAX) {
            jq = applyLaxErrorSuppression(jq);
        }

        return jq;
    }

    /**
     * Convert a SQL/JSON path to jq and wrap in {@code [...]} to collect all
     * matches into an array — equivalent to PostgreSQL's {@code jsonb_path_query_array()}.
     * Uses lax mode (PostgreSQL default).
     *
     * @param jsonpath the SQL/JSON path expression
     * @return jq expression that produces an array of all matches
     */
    public static String convertArray(String jsonpath) {
        return convertArray(jsonpath, Mode.LAX);
    }

    /**
     * Convert a SQL/JSON path to jq and wrap in {@code [...]} to collect all
     * matches into an array — equivalent to PostgreSQL's {@code jsonb_path_query_array()}.
     *
     * @param jsonpath the SQL/JSON path expression
     * @param mode strict or lax evaluation mode
     * @return jq expression that produces an array of all matches
     */
    public static String convertArray(String jsonpath, Mode mode) {
        String jq = convert(jsonpath, mode);
        if (jq.contains("[]?")) {
            // Has iterators — [...] naturally produces [] when no matches
            return "[" + jq + "]";
        } else {
            // Scalar path — filter null to produce [] for missing paths,
            // matching jsonb_path_query_array behavior
            return "[" + jq + " // empty]";
        }
    }

    /**
     * Convert a SQL/JSON path to jq and compile to a validated {@link JqProgram}.
     * Invalid conversions are caught at conversion time rather than at evaluation time.
     * Uses lax mode (PostgreSQL default).
     *
     * @param jsonpath the SQL/JSON path expression
     * @return a compiled jq program ready for execution
     * @throws IllegalArgumentException if the converted jq expression is invalid
     */
    public static JqProgram compile(String jsonpath) {
        return compile(jsonpath, Mode.LAX);
    }

    /**
     * Convert a SQL/JSON path to jq and compile to a validated {@link JqProgram}.
     *
     * @param jsonpath the SQL/JSON path expression
     * @param mode strict or lax evaluation mode
     * @return a compiled jq program ready for execution
     * @throws IllegalArgumentException if the converted jq expression is invalid
     */
    public static JqProgram compile(String jsonpath, Mode mode) {
        String jq = convert(jsonpath, mode);
        try {
            return JqProgram.compile(jq);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Cannot compile jsonpath '" + jsonpath + "' (converted to jq: '" + jq + "'): " + e.getMessage(), e);
        }
    }

    /**
     * Apply lax auto-wrapping for bracket access on scalars.
     * PostgreSQL lax mode auto-wraps non-array values in a single-element array
     * before applying bracket access. For example:
     * <ul>
     *   <li>{@code lax $[0]} on scalar {@code 1} → wraps as {@code [1]}, then {@code [0]} → {@code 1}</li>
     *   <li>{@code lax $[*]} on scalar {@code 1} → wraps as {@code [1]}, then {@code [*]} → {@code 1}</li>
     * </ul>
     */
    private static String applyLaxAutoWrap(String jq) {
        // Handle root-level bracket access: .[N], .[N,M], .[N:M], .[]?
        // These need auto-wrapping when the input is not an array.
        if (jq.startsWith(".[]?")) {
            // $[*] on scalar → return the scalar itself (auto-wrap + iterate = identity)
            String rest = jq.substring(4); // everything after .[]?
            if (rest.isEmpty()) {
                jq = "(if type == \"array\" then .[]? else . end)";
            } else {
                // rest may start with . (field access) or | (pipe) — connect properly
                String elseExpr = rest.startsWith(".") ? rest : "." + rest;
                jq = "(if type == \"array\" then .[]?" + rest + " else " + elseExpr + " end)";
            }
        } else if (jq.startsWith(".[")) {
            // .[0], .[0,1], .[0:11] — find the closing bracket
            int closeBracket = findClosingBracket(jq, 1);
            if (closeBracket > 0) {
                String bracketExpr = jq.substring(1, closeBracket + 1); // [N] or [N:M] or [N,M]
                String rest = jq.substring(closeBracket + 1);
                // Optional ? after bracket
                if (rest.startsWith("?")) rest = rest.substring(1);
                jq = "(if type == \"array\" then ." + bracketExpr + rest + " else [.]" + bracketExpr + rest + " end)";
            }
        }
        return jq;
    }

    /**
     * Convert {@code .size()} calls inside bracket expressions {@code [...]} to
     * {@code | length}. Inside brackets, the path before {@code .size()} is a jq expression
     * that should pipe to length, e.g., {@code [.path.size()-1]} → {@code [.path | length-1]}.
     */
    private static String convertSizeInBrackets(String jq) {
        // Find .size() inside [...] brackets
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < jq.length()) {
            if (jq.charAt(i) == '[') {
                int close = findClosingBracket(jq, i);
                if (close > 0) {
                    String inside = jq.substring(i + 1, close);
                    if (inside.contains(".size()")) {
                        // Replace .size() with  | length inside the brackets
                        inside = inside.replace(".size()", " | length");
                    }
                    sb.append('[').append(inside).append(']');
                    i = close + 1;
                    continue;
                }
            }
            sb.append(jq.charAt(i));
            i++;
        }
        return sb.toString();
    }

    /** Find the closing bracket matching the one at position {@code openPos}. */
    private static int findClosingBracket(String s, int openPos) {
        int depth = 0;
        for (int i = openPos; i < s.length(); i++) {
            if (s.charAt(i) == '[') depth++;
            else if (s.charAt(i) == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /**
     * Apply lax error suppression. PostgreSQL lax mode silently produces empty results
     * for type mismatches (e.g., .field on a number, [0] on a scalar).
     * <p>
     * Uses jq's {@code ?} (optional/try) operator on individual field access and bracket
     * operations to suppress errors per-element rather than wrapping the entire expression
     * in try-catch (which would discard successful results from multi-output expressions
     * when any single element fails).
     */
    private static String applyLaxErrorSuppression(String jq) {
        // Add ? to .field access that doesn't already have it
        // Matches .identifier NOT followed by ? or ( or [
        // Uses word boundary: letter/digit/underscore after the dot
        StringBuilder sb = new StringBuilder();
        int i = 0;
        boolean inQuote = false;
        boolean inParens = false;
        int parenDepth = 0;
        while (i < jq.length()) {
            char c = jq.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
                sb.append(c);
                i++;
            } else if (inQuote) {
                if (c == '\\' && i + 1 < jq.length()) {
                    sb.append(c);
                    sb.append(jq.charAt(i + 1));
                    i += 2;
                } else {
                    sb.append(c);
                    i++;
                }
            } else if (c == '(' ) {
                parenDepth++;
                sb.append(c);
                i++;
            } else if (c == ')') {
                parenDepth--;
                sb.append(c);
                i++;
            } else if (c == '.' && i + 1 < jq.length() && Character.isLetter(jq.charAt(i + 1))) {
                // .field — find the end of the identifier
                int start = i;
                i++; // skip the dot
                while (i < jq.length() && (Character.isLetterOrDigit(jq.charAt(i)) || jq.charAt(i) == '_' || jq.charAt(i) == '-')) {
                    i++;
                }
                String fieldAccess = jq.substring(start, i);
                sb.append(fieldAccess);
                // Add ? if not already present and not inside if/then/else/end keywords
                if (i < jq.length() && jq.charAt(i) == '?') {
                    sb.append('?');
                    i++; // skip existing ?
                } else if (!fieldAccess.equals(".end") && !fieldAccess.equals(".else")
                        && !fieldAccess.equals(".then") && !fieldAccess.equals(".if")) {
                    sb.append('?');
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    // ========================================================================
    //  Filter conversion (? (@.field op value) → | select(.field op value))
    // ========================================================================

    static String convertFilters(String jq) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < jq.length()) {
            // Look for ? ( or ?( — the jsonpath filter pattern, not []? which is jq optional
            int filterStart = -1;
            for (int s = i; s < jq.length(); s++) {
                if (jq.charAt(s) == '?' && s + 1 < jq.length()) {
                    // Skip []? — that's jq optional iteration, not a filter
                    if (s > 0 && jq.charAt(s - 1) == ']') continue;
                    int next = s + 1;
                    while (next < jq.length() && jq.charAt(next) == ' ') next++;
                    if (next < jq.length() && jq.charAt(next) == '(') {
                        filterStart = s;
                        break;
                    }
                }
            }
            if (filterStart == -1) {
                result.append(jq, i, jq.length());
                break;
            }
            String before = jq.substring(i, filterStart).trim();
            if (!before.isEmpty()) {
                result.append(before);
            }
            if (!result.toString().endsWith("[]?")) {
                result.append("[]?");
            }
            int parenStart = jq.indexOf("(", filterStart);
            if (parenStart == -1) {
                result.append(jq, filterStart, jq.length());
                break;
            }
            // Find matching closing paren (handling nesting)
            int depth = 1;
            int parenEnd = parenStart + 1;
            while (parenEnd < jq.length() && depth > 0) {
                if (jq.charAt(parenEnd) == '(') depth++;
                else if (jq.charAt(parenEnd) == ')') depth--;
                parenEnd++;
            }
            String filterBody = jq.substring(parenStart + 1, parenEnd - 1).trim();
            filterBody = convertFilterBody(filterBody);
            result.append(" | select(").append(filterBody).append(")");
            i = parenEnd;
        }
        return result.toString();
    }

    /**
     * Convert the body of a jsonpath filter expression to jq.
     * Handles @.field → .field, && → and, || → or, like_regex → test(), etc.
     */
    static String convertFilterBody(String body) {
        // Convert @."special" → .["special"] (quoted field access on current item)
        body = body.replaceAll("@\\.\"([^\"]+)\"", ".[\"$1\"]");
        // Convert @.field → .field (field access on current item)
        body = body.replace("@.", ".");
        // Convert bare @ → . (current item reference without field access)
        // Also handle @[ (array access on current item)
        body = body.replaceAll("@(?=[\\s)&|,\\[]|$)", ".");
        // Convert && → and, || → or
        body = body.replace("&&", "and");
        body = body.replace("||", "or");
        // Handle like_regex with optional flags → test()
        // Includes type guard: PostgreSQL lax mode silently skips non-string values,
        // but jq's test() throws on non-strings. Guard with (type == "string" and ...).
        // flag "" (empty flags) → test() without flags argument
        body = body.replaceAll(
                "(\\S+)\\s+like_regex\\s+\"([^\"]+)\"\\s+flag\\s+\"\"",
                "($1 | type == \"string\" and test(\"$2\"))");
        body = body.replaceAll(
                "(\\S+)\\s+like_regex\\s+\"([^\"]+)\"\\s+flag\\s+\"([^\"]+)\"",
                "($1 | type == \"string\" and test(\"$2\"; \"$3\"))");
        body = body.replaceAll(
                "(\\S+)\\s+like_regex\\s+\"([^\"]+)\"",
                "($1 | type == \"string\" and test(\"$2\"))");
        // Handle starts with → startswith()
        body = body.replaceAll(
                "(\\S+)\\s+starts\\s+with\\s+\"([^\"]+)\"",
                "($1 | startswith(\"$2\"))");
        // Handle != → != (already valid in jq)
        // Handle == → == (already valid in jq)
        return body;
    }

    // ========================================================================
    //  Lax mode: automatic array unwrapping at dot-access segments
    // ========================================================================

    /**
     * Convert key chains (sequences of 2+ dot-access segments) to lax-mode
     * jq that conditionally unwraps arrays at each intermediate segment.
     * <p>
     * {@code .a.b.c} becomes:
     * {@code if (.a | type) == "array" then .a[] else .a end | if (.b | type) == "array" then .b[] else .b end | .c}
     */
    static String convertToLaxChains(String input) {
        if (input == null || input.isEmpty()) return input;
        List<Range> ranges = findKeyChains(input);
        if (ranges.isEmpty()) return input;

        StringBuilder sb = new StringBuilder();
        int prevIdx = 0;
        for (Range r : ranges) {
            if (r.start > prevIdx) {
                sb.append(input, prevIdx, r.start);
            }
            List<String> keys = splitNotInQuotes(input.substring(r.start, r.end), ".");
            // Ensure proper pipe separation
            if (!sb.isEmpty()) {
                if (sb.charAt(sb.length() - 1) != ' ') sb.append(" ");
                int lastNonSpace = sb.length() - 1;
                while (lastNonSpace > 0 && sb.charAt(lastNonSpace) == ' ') lastNonSpace--;
                if (!"|(".contains(String.valueOf(sb.charAt(lastNonSpace)))) {
                    sb.append(" | ");
                }
            }
            // All keys except the last get conditional unwrapping
            for (int idx = 0; idx < keys.size() - 1; idx++) {
                String key = keys.get(idx);
                sb.append("if (.").append(key).append(" | type) == \"array\" then .").append(key)
                        .append("[] else .").append(key).append(" end | ");
            }
            sb.append(".").append(keys.get(keys.size() - 1));
            prevIdx = r.end;
        }
        if (prevIdx < input.length()) {
            sb.append(input, prevIdx, input.length());
        }
        return sb.toString();
    }

    /**
     * Find sequences of 2+ dot-access segments in the expression.
     * A "key chain" is a contiguous sequence like {@code .a.b.c} (3 segments).
     * Handles quoted keys (e.g., {@code ."special.key".value}).
     */
    static List<Range> findKeyChains(String input) {
        return findKeyChains(input, 0);
    }

    static List<Range> findKeyChains(String input, int start) {
        if (input == null || start >= input.length()) return Collections.emptyList();
        start = Math.max(0, start);

        List<Range> result = new ArrayList<>();
        boolean inQuote = false;
        boolean inChain = false;
        int keyCount = 0;
        int chainStart = -1;
        char quoteChar = ' ';

        for (int i = start; i < input.length(); i++) {
            char c = input.charAt(i);
            if (inQuote) {
                if (quoteChar == c && (i == start || input.charAt(i - 1) != '\\')) {
                    inQuote = false;
                }
            } else {
                if ((c == '"' || c == '\'') && (i == start || input.charAt(i - 1) != '\\')) {
                    inQuote = true;
                    quoteChar = c;
                } else if (c == '.') {
                    if (!inChain) {
                        chainStart = i;
                        inChain = true;
                        keyCount = 1;
                    } else {
                        // Don't count dots followed by [ (array index, not key access)
                        if (i == input.length() - 1 || input.charAt(i + 1) != '[') {
                            keyCount++;
                        }
                    }
                } else if (" \t|?[=!><".indexOf(c) >= 0) {
                    if (inChain) {
                        if (keyCount > 1) {
                            result.add(new Range(chainStart, i));
                        }
                        inChain = false;
                        keyCount = 0;
                    }
                }
            }
        }
        if (inChain && keyCount > 1) {
            result.add(new Range(chainStart, input.length()));
        }
        return result;
    }

    /**
     * Split a string at the given delimiter, respecting quoted segments.
     */
    static List<String> splitNotInQuotes(String input, String split) {
        boolean inQuote = false;
        char quoteChar = ' ';
        List<String> result = new ArrayList<>();
        int startIdx = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (inQuote) {
                if (quoteChar == c && (i == 0 || input.charAt(i - 1) != '\\')) {
                    inQuote = false;
                }
            } else {
                if ((c == '"' || c == '\'') && (i == 0 || input.charAt(i - 1) != '\\')) {
                    inQuote = true;
                    quoteChar = c;
                } else if (input.startsWith(split, i)) {
                    if (startIdx > 0) {
                        result.add(input.substring(startIdx, i));
                    }
                    i += split.length() - 1;
                    startIdx = i + 1;
                }
            }
        }
        if (startIdx > 0 && startIdx < input.length()) {
            result.add(input.substring(startIdx));
        }
        return result;
    }

    // ========================================================================
    //  Helpers
    // ========================================================================

    /**
     * Replace occurrences of target with replacement only when outside quoted strings.
     */
    static String replaceOutsideQuotes(String input, String target, String replacement) {
        StringBuilder result = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < input.length(); i++) {
            if (input.charAt(i) == '"' && (i == 0 || input.charAt(i - 1) != '\\')) {
                inQuotes = !inQuotes;
                result.append(input.charAt(i));
            } else if (!inQuotes && input.startsWith(target, i)) {
                result.append(replacement);
                i += target.length() - 1;
            } else {
                result.append(input.charAt(i));
            }
        }
        return result.toString();
    }

    /** A range [start, end) in a string. */
    record Range(int start, int end) {}
}

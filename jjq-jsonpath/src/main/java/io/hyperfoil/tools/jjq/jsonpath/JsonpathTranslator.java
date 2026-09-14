package io.hyperfoil.tools.jjq.jsonpath;

import java.util.List;
import java.util.Set;

import static io.hyperfoil.tools.jjq.jsonpath.JsonpathTokenType.*;

/**
 * Translates a tokenized PostgreSQL jsonpath expression to jq.
 *
 * <p>Recursive descent translator that walks the token list with full context
 * awareness — it knows whether it's inside a filter body, bracket expression,
 * or top-level path, and applies the correct translation rules for each.</p>
 *
 * <p>Handles lax mode as a post-processing step (same proven approach as the
 * original string-replacement converter).</p>
 */
public final class JsonpathTranslator {

    /** Method names recognized after DOT + IDENT + LPAREN + RPAREN */
    private static final Set<String> METHODS = Set.of(
            "size", "keyvalue", "double", "string", "type", "boolean",
            "ceiling", "floor", "abs",
            "integer", "bigint", "number", "decimal",
            // PG17 string methods
            "lower", "upper", "ltrim", "rtrim", "btrim",
            "replace", "initcap", "split_part"
    );

    private final List<JsonpathToken> tokens;
    private final JsonpathToJq.Mode mode;
    private int pos;
    private final StringBuilder jq;

    public JsonpathTranslator(List<JsonpathToken> tokens, JsonpathToJq.Mode mode) {
        // Copy: splitLastOffset() inserts synthetic tokens during translation
        this.tokens = new java.util.ArrayList<>(tokens);
        this.mode = mode;
        this.pos = 0;
        this.jq = new StringBuilder();
    }

    /** Matches an unspaced last-offset identifier: last-N with digits. */
    private static final java.util.regex.Pattern LAST_OFFSET =
            java.util.regex.Pattern.compile("last-(\\d+)");

    /**
     * Split an IDENT("last-N") token into KW_LAST, MINUS, INTEGER(N) in place,
     * so downstream logic sees the canonical spaced form. The lexer keeps
     * {@code last-N} whole (it can be a key in dot context: $.last-3), so this
     * split applies only where an index offset is grammatically expected.
     * Callers must have verified the peek matches LAST_OFFSET first.
     */
    private void splitLastOffset() {
        JsonpathToken tok = advance(); // consume IDENT("last-N")
        java.util.regex.Matcher m = LAST_OFFSET.matcher(tok.value());
        m.matches(); // guaranteed by caller
        int tokPos = tok.position();
        tokens.add(pos, new JsonpathToken(INTEGER, m.group(1), tokPos));
        tokens.add(pos, new JsonpathToken(MINUS, tokPos));
        tokens.add(pos, new JsonpathToken(KW_LAST, "last", tokPos));
        // peek() is now KW_LAST; existing logic applies unchanged
    }

    /** True if the peeked token is an unspaced last-offset IDENT. */
    private boolean peekLastOffset() {
        JsonpathToken tok = peek();
        return tok.is(IDENT) && LAST_OFFSET.matcher(tok.value()).matches();
    }

    /** Translate the token stream to a jq expression string. */
    public String translate() {
        // Handle mode prefix (already consumed by lexer as KW_STRICT/KW_LAX tokens)
        if (peek().is(KW_STRICT)) advance();
        else if (peek().is(KW_LAX)) advance();

        // Translate the path expression
        translatePath();

        String result = jq.toString();

        // Apply lax mode post-processing (proven approach from original converter)
        if (mode == JsonpathToJq.Mode.LAX) {
            result = JsonpathToJq.convertToLaxChains(result);
            result = JsonpathToJq.applyLaxAutoWrapStatic(result);
            // Fix leading pipe
            if (result.startsWith(" | ")) result = "." + result;
            else if (result.startsWith("| ")) result = ". " + result;
            result = JsonpathToJq.applyLaxErrorSuppressionStatic(result);
        } else {
            // Fix leading pipe for strict mode too
            if (result.startsWith(" | ")) result = "." + result;
            else if (result.startsWith("| ")) result = ". " + result;
        }

        return result;
    }

    // ========================================================================
    //  Path translation
    // ========================================================================

    /** Translate a complete path expression: $ .field [idx] .method() ?(filter) ... */
    private void translatePath() {
        // Handle parenthesized expression: (expr).method() or (expr)[idx]
        if (peek().is(LPAREN)) {
            advance(); // consume (
            translateParenthesizedExpr();
        }
        // Handle root reference
        else if (peek().is(ROOT)) {
            advance();
            // If nothing follows $, emit identity
            if (peek().is(EOF)) {
                jq.append(".");
                return;
            }
            // Don't emit anything for ROOT — the first path step emits the leading dot
            // ($.name → translateDotAccess emits ".name")
            // But if the next token is LBRACKET, we need the root dot
            if (!peek().is(DOT) && !peek().is(STAR)) {
                jq.append(".");
            } else if (peek().is(STAR)) {
                // $* — the STAR step emits []? without a dot
                jq.append(".");
            } else if (peek().is(DOT) && peekAt(1) != null && peekAt(1).is(STAR)) {
                // $.* — same: DOT+STAR emits []? without a dot
                jq.append(".");
            }
        } else if (peek().is(EOF)) {
            jq.append(".");
            return;
        }

        // Translate chain of path steps
        while (!peek().is(EOF) && !peek().is(RPAREN)) {
            translatePathStep();
        }
    }

    /**
     * Translate a parenthesized expression: (expr).method() or (expr)[idx].
     * The opening LPAREN has already been consumed.
     * Emits the inner expression, consumes RPAREN, then any trailing path steps
     * are handled by the main loop.
     */
    private void translateParenthesizedExpr() {
        // Translate the inner expression — it may be a full path or a comparison
        while (!peek().is(RPAREN) && !peek().is(EOF)) {
            translatePathStep();
        }
        if (peek().is(RPAREN)) advance(); // consume )
        // Any subsequent .method() or [idx] will be handled by the main translatePath loop
    }

    /** Translate a single path step: .field, [idx], .method(), ?(filter), *, ** */
    private void translatePathStep() {
        JsonpathToken token = peek();
        switch (token.type()) {
            case DOT -> translateDotAccess();
            case LBRACKET -> translateBracket();
            case QUESTION -> translateFilter();
            case STAR -> {
                advance();
                // Context: if preceded by a method/operator or followed by a number,
                // this is multiplication, not wildcard
                if (peek().is(INTEGER) || peek().is(DECIMAL) || peek().is(NAMED_VARIABLE) || peek().is(ROOT)) {
                    jq.append(" * ");
                } else {
                    // .* wildcard — emit []?
                    jq.append("[]?");
                }
            }
            case DOUBLESTAR -> {
                advance();
                translateRecursiveDescent();
            }
            case NAMED_VARIABLE -> {
                // $varname at path level — emit as jq variable reference
                jq.append("$").append(advance().value());
            }
            // Comparison/arithmetic operators at path level (e.g., $.s < $s)
            case EQ -> { advance(); jq.append(" == "); }
            case NEQ, LTGT -> { advance(); jq.append(" != "); }
            case LT -> { advance(); jq.append(" < "); }
            case GT -> { advance(); jq.append(" > "); }
            case LE -> { advance(); jq.append(" <= "); }
            case GE -> { advance(); jq.append(" >= "); }
            case PLUS -> { advance(); jq.append(" + "); }
            case MINUS -> { advance(); jq.append(" - "); }
            case SLASH -> { advance(); jq.append(" / "); }
            case PERCENT -> { advance(); jq.append(" % "); }
            case INTEGER, DECIMAL -> jq.append(advance().value());
            case STRING -> { jq.append("\"").append(advance().value()).append("\""); }
            case TRUE -> { advance(); jq.append("true"); }
            case FALSE -> { advance(); jq.append("false"); }
            case NULL -> { advance(); jq.append("null"); }
            default -> {
                // Unexpected token — skip to avoid infinite loop
                advance();
            }
        }
    }

    // ========================================================================
    //  Dot access: .field, .*, ."quoted", .method()
    // ========================================================================

    private void translateDotAccess() {
        advance(); // consume DOT

        JsonpathToken next = peek();
        switch (next.type()) {
            case STAR -> {
                advance();
                // .* → []?
                jq.append("[]?");
            }
            case DOUBLESTAR -> {
                advance();
                translateRecursiveDescent();
            }
             case IDENT -> {
                String name = next.value();
                advance();
                // Check if this is a method call: IDENT LPAREN ... RPAREN
                if (peek().is(LPAREN) && METHODS.contains(name)) {
                    advance(); // consume LPAREN
                    // Collect method arguments (if any) before consuming RPAREN
                    var methodArgs = consumeMethodArgs();
                    translateMethod(name, methodArgs);
                } else if (peek().is(LPAREN)) {
                    // Unknown method — fail loudly instead of emitting
                    // garbage that fails later with a confusing error
                    throw new IllegalArgumentException(
                            "Unknown jsonpath method: ." + name + "()");
                } else if (isBareJqIdentifier(name)) {
                    jq.append(".").append(name);
                } else {
                    // Key needs quoting (hyphen, dot, space, leading digit,
                    // or backslash-escaped chars from the lexer) → bracket notation
                    jq.append(".[\"");
                    appendJqEscaped(name);
                    jq.append("\"]");
                }
            }
            case STRING -> {
                // ."quoted field" → .["quoted field"]
                String fieldName = next.value();
                advance();
                jq.append(".[\"");
                appendJqEscaped(fieldName);
                jq.append("\"]");
            }
            case INTEGER -> {
                // .0, .1, etc. — numeric field name means array index (.0 is
                // invalid jq; .[0] works for both arrays and objects)
                jq.append(".[").append(advance().value()).append("]");
            }
            default -> {
                // Just a dot — unusual but possible
            }
        }
    }

    /**
     * True if the key is valid as a bare jq identifier (.name).
     * Anything else (hyphen, dot, space, leading digit, quote, backslash)
     * requires bracket notation (.["..."]).
     */
    private static boolean isBareJqIdentifier(String name) {
        if (name.isEmpty()) return false;
        char first = name.charAt(0);
        if (!Character.isLetter(first) && first != '_') return false;
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') return false;
        }
        return true;
    }

    /**
     * Append a key name jq-escaped for use inside a double-quoted string.
     * Needed because lexer values arrive unescaped (e.g. backslash escapes
     * in identifiers, escapes in quoted strings).
     */
    private void appendJqEscaped(String name) {
        jq.append(escapeJq(name));
    }

    /**
     * Return the jq-escaped form of a string for use inside double quotes.
     */
    private static String escapeJq(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        io.hyperfoil.tools.jjq.value.JqString.escapeJson(value, sb);
        return sb.toString();
    }

    /**
     * Escape only double quotes for jq string embedding, preserving backslashes
     * verbatim. Used for regex patterns without the quote flag: PostgreSQL ARE
     * constructs (e.g. \b = backspace) must reach the regex engine exactly as
     * the lexer value holds them, with jq string decoding doing the rest.
     */
    private static String escapeJqQuotesOnly(String value) {
        if (value.indexOf('"') < 0) return value;
        return value.replace("\"", "\\\"");
    }

    /**
     * Quote a literal string for use as a regex: escape every regex
     * metacharacter so the pattern matches only itself. Implements the
     * PostgreSQL like_regex "q" flag, which jq has no equivalent for.
     */
    private static String quoteRegex(String literal) {
        StringBuilder sb = new StringBuilder(literal.length() * 2);
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (c == '\\' || c == '.' || c == '^' || c == '$' || c == '*'
                    || c == '+' || c == '?' || c == '(' || c == ')'
                    || c == '[' || c == ']' || c == '{' || c == '}'
                    || c == '|') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // ========================================================================
    //  Bracket access: [N], [*], [N to M], [last], [last-N], [N,M,...]
    // ========================================================================

    private void translateBracket() {
        advance(); // consume LBRACKET

        JsonpathToken first = peek();

        if (first.is(STAR)) {
            // [*] → []?
            advance();
            if (peek().is(RBRACKET)) {
                advance();
                // Check if followed by .keyvalue() — collapse (issue #70)
                if (peek().is(DOT) && peekAt(1) != null && peekAt(1).is(IDENT)
                        && "keyvalue".equals(peekAt(1).value())) {
                    // [*].keyvalue() → .keyvalue() (collapse iteration before keyvalue)
                    return;
                }
                // Check if followed by .* — collapse [*].* to just []?
                if (peek().is(DOT) && peekAt(1) != null && peekAt(1).is(STAR)) {
                    // [*].* → []? (already emitting []? below, consume the .*)
                    advance(); // consume DOT
                    advance(); // consume STAR
                }
                jq.append("[]?");
                return;
            }
            jq.append("[]?");
            expect(RBRACKET);
            return;
        }

        if (first.is(KW_LAST)) {
            translateBracketLast();
            return;
        }

        if (first.is(IDENT) && LAST_OFFSET.matcher(first.value()).matches()) {
            // Unspaced `last-N`: normalize to KW_LAST, MINUS, INTEGER
            splitLastOffset();
            translateBracketLast();
            return;
        }

        if (first.is(INTEGER) || first.is(DECIMAL) || first.is(MINUS)) {
            translateBracketIndex();
            return;
        }

        // Complex bracket expression — may contain path expressions, .size(), arithmetic
        // Translate as a jq bracket expression
        jq.append("[");
        translateBracketExpression();
        jq.append("]");
        if (peek().is(RBRACKET)) advance();
    }

    /** [last], [last - N] */
    private void translateBracketLast() {
        advance(); // consume KW_LAST

        if (peek().is(RBRACKET)) {
            // [last] → [-1]
            advance();
            jq.append("[-1]");
            return;
        }

        if (peek().is(MINUS)) {
            advance(); // consume MINUS
            JsonpathToken n = expect(INTEGER);
            int offset = Integer.parseInt(n.value());

            if (peek().is(KW_TO)) {
                // [last - N to ...] → [-(N+1):...]
                advance(); // consume TO
                if (peek().is(KW_LAST)) {
                    // [last - N to last] → [-(N+1):]
                    advance();
                    expect(RBRACKET);
                    jq.append("[").append(-(offset + 1)).append(":]");
                } else {
                    // [last - N to M] → [-(N+1):M+1]
                    JsonpathToken to = expect(INTEGER);
                    int toVal = Integer.parseInt(to.value());
                    expect(RBRACKET);
                    jq.append("[").append(-(offset + 1)).append(":").append(toVal + 1).append("]");
                }
            } else {
                // [last - N] → [-(N+1)]
                expect(RBRACKET);
                jq.append("[").append(-(offset + 1)).append("]");
            }
            return;
        }

        if (peek().is(KW_TO)) {
            advance(); // consume TO
            if (peekLastOffset()) {
                // Unspaced `to last-N`: normalize to KW_LAST, MINUS, INTEGER
                splitLastOffset();
            }
            if (peek().is(KW_LAST)) {
                advance();
                if (peek().is(MINUS)) {
                    // [last to last - N]: descending for N >= 1 → empty result set.
                    // jq `empty` produces zero outputs, matching PG exactly
                    // (in both bare and convertArray contexts).
                    advance(); // consume MINUS
                    JsonpathToken n = expect(INTEGER);
                    int offset = Integer.parseInt(n.value());
                    expect(RBRACKET);
                    if (offset == 0) {
                        // [last to last - 0] == [last to last]
                        jq.append("[-1:]");
                    } else {
                        jq.append("empty");
                    }
                } else {
                    // [last to last] → [-1:]
                    expect(RBRACKET);
                    jq.append("[-1:]");
                }
                return;
            }
            if (peek().is(INTEGER)) {
                // [last to M]: lower bound is last (len-1), upper is M.
                // jq clamps and empties naturally: [-1:M+1] yields the
                // clamped tail when len <= M+1, [] when descending.
                JsonpathToken to = expect(INTEGER);
                int toVal = Integer.parseInt(to.value());
                expect(RBRACKET);
                jq.append("[-1:").append(toVal + 1).append("]");
                return;
            }
            // [last to <unexpected>] — consume to RBRACKET to avoid
            // dangling tokens corrupting the rest of the expression
            while (!peek().is(RBRACKET) && !peek().is(EOF)) advance();
            if (peek().is(RBRACKET)) advance();
            jq.append("empty");
            return;
        }

        expect(RBRACKET);
        jq.append("[-1]");
    }

    /** [N], [N.M], [N to M], [N to last], [N,M,...] */
    private void translateBracketIndex() {
        // Parse the first number
        boolean negative = false;
        if (peek().is(MINUS)) {
            negative = true;
            advance();
        }
        JsonpathToken num = advance(); // INTEGER or DECIMAL
        if (num.is(DECIMAL)) {
            // Decimal index like [0.3] — emit as-is (PostgreSQL truncates to integer)
            String numStr = (negative ? "-" : "") + num.value();
            expect(RBRACKET);
            jq.append("[").append(numStr).append("]");
            return;
        }
        int firstVal = Integer.parseInt(num.value());
        if (negative) firstVal = -firstVal;

        if (peek().is(KW_TO)) {
            // Range: [N to M] or [N to last]
            advance(); // consume TO

            if (peekLastOffset()) {
                // Unspaced `to last-N`: normalize to KW_LAST, MINUS, INTEGER
                splitLastOffset();
            }
            if (peek().is(KW_LAST)) {
                advance(); // consume LAST

                if (peek().is(MINUS)) {
                    // [N to last - M] → [N:-M] (PostgreSQL end is inclusive;
                    // jq end is exclusive, and last-M is index len-1-M == -M).
                    // M == 0 means through the end: [N:].
                    advance();
                    JsonpathToken m = expect(INTEGER);
                    int offset = Integer.parseInt(m.value());
                    expect(RBRACKET);
                    if (offset == 0) {
                        jq.append("[").append(firstVal).append(":]");
                    } else {
                        jq.append("[").append(firstVal).append(":").append(-offset).append("]");
                    }
                } else {
                    // [N to last] → [N:]
                    expect(RBRACKET);
                    jq.append("[").append(firstVal).append(":]");
                }
            } else {
                // [N to M] → [N:M+1] (PostgreSQL inclusive, jq exclusive)
                boolean negTo = false;
                if (peek().is(MINUS)) { negTo = true; advance(); }
                JsonpathToken toNum = expect(INTEGER);
                int toVal = Integer.parseInt(toNum.value());
                if (negTo) toVal = -toVal;
                expect(RBRACKET);
                jq.append("[").append(firstVal).append(":").append(toVal + 1).append("]");
            }
            return;
        }

        if (peek().is(COMMA)) {
            // Union: [N,M,...] → | (.[N], .[M], ...) — multiple outputs
            // jq doesn't have [N,M] syntax but comma produces multiple outputs
            // Need a pipe to separate from the preceding path
            jq.append(" | (.[").append(firstVal).append("]");
            while (peek().is(COMMA)) {
                advance(); // consume comma
                boolean negNext = false;
                if (peek().is(MINUS)) { negNext = true; advance(); }
                if (peek().is(INTEGER)) {
                    int nextVal = Integer.parseInt(advance().value());
                    if (negNext) nextVal = -nextVal;
                    jq.append(", .[").append(nextVal).append("]");
                } else if (peek().is(DECIMAL)) {
                    // Decimal index: PostgreSQL truncates toward zero
                    double d = Double.parseDouble(advance().value());
                    int nextVal = (int) d;
                    if (negNext) nextVal = -nextVal;
                    jq.append(", .[").append(nextVal).append("]");
                } else if (peek().is(STRING)) {
                    // String key arm: $["a",0] style member
                    jq.append(", .[\"");
                    appendJqEscaped(advance().value());
                    jq.append("\"]");
                } else if (peek().is(STAR)) {
                    // Wildcard arm: $[0,*] iterates everything (duplicates kept)
                    advance();
                    jq.append(", .[]?");
                }
                // Anything else: skip the arm (documented limitation)
            }
            jq.append(")");
            if (peek().is(RBRACKET)) advance();
            return;
        }

        // Simple index: [N]
        expect(RBRACKET);
        jq.append("[").append(firstVal).append("]");
    }

    /** Translate a complex expression inside brackets (e.g., $.path.size()-1). */
    private void translateBracketExpression() {
        while (!peek().is(RBRACKET) && !peek().is(EOF)) {
            JsonpathToken token = peek();
            switch (token.type()) {
                case ROOT -> {
                    advance();
                    // $ inside brackets refers to root
                }
                case DOT -> {
                    advance();
                    if (peek().is(IDENT)) {
                        String name = advance().value();
                        // Check for method call inside brackets
                        if (peek().is(LPAREN) && METHODS.contains(name)) {
                            advance(); // LPAREN
                            var mArgs = consumeMethodArgs();
                            translateMethod(name, mArgs);
                        } else {
                            jq.append(".").append(name);
                        }
                    }
                }
                case IDENT -> jq.append(advance().value());
                case INTEGER, DECIMAL -> jq.append(advance().value());
                case PLUS -> { advance(); jq.append("+"); }
                case MINUS -> { advance(); jq.append("-"); }
                case STAR -> { advance(); jq.append("*"); }
                case SLASH -> { advance(); jq.append("/"); }
                case LBRACKET -> {
                    advance();
                    jq.append("[");
                    translateBracketExpression();
                    jq.append("]");
                    if (peek().is(RBRACKET)) advance();
                }
                default -> advance(); // skip unknown
            }
        }
    }

    // ========================================================================
    //  Methods: .size(), .double(), .keyvalue(), etc.
    // ========================================================================

    /**
     * Consume method arguments between LPAREN (already consumed) and RPAREN.
     * Returns the list of argument values (strings). Consumes the closing RPAREN.
     */
    private List<String> consumeMethodArgs() {
        var args = new java.util.ArrayList<String>();
        while (!peek().is(RPAREN) && !peek().is(EOF)) {
            var tok = advance();
            if (tok.is(COMMA)) continue; // skip separators
            if (tok.is(PLUS)) continue;  // skip unary +
            if (tok.is(MINUS)) {
                // Negative number: consume the next token and prepend '-'
                if (peek().is(INTEGER) || peek().is(DECIMAL)) {
                    args.add("-" + advance().value());
                }
                continue;
            }
            if (tok.value() != null) args.add(tok.value());
        }
        if (peek().is(RPAREN)) advance(); // consume RPAREN
        return args;
    }

    private void translateMethod(String methodName, List<String> args) {
        switch (methodName) {
            case "size" -> jq.append(" | length");
            case "keyvalue" -> jq.append(" | to_entries[]");
            case "double", "number" -> jq.append(" | tonumber");
            case "decimal" -> {
                if (args.size() >= 2) {
                    // decimal(precision, scale) — round to 'scale' decimal places
                    // jq: tonumber * 10^scale | round / 10^scale
                    int s = parseMethodInt(methodName, args.get(1));
                    if (Math.abs((long) s) > 18) {
                        // Beyond long precision — jq doubles can't represent this;
                        // fall back to plain conversion rather than overflowing pow10
                        jq.append(" | tonumber");
                    } else if (s >= 0) {
                        jq.append(" | tonumber * ").append(pow10(s)).append(" | round / ").append(pow10(s));
                    } else {
                        // Negative scale: round to nearest 10^|s|
                        int absS = -s;
                        jq.append(" | tonumber / ").append(pow10(absS)).append(" | round * ").append(pow10(absS));
                    }
                } else {
                    jq.append(" | tonumber");
                }
            }
            case "string" -> jq.append(" | tostring");
            case "type" -> jq.append(" | type");
            case "boolean" -> jq.append(" | if type == \"boolean\" then ." +
                    " elif type == \"number\" then . != 0" +
                    " elif type == \"string\" then (. == \"t\" or . == \"true\" or . == \"y\" or . == \"yes\" or . == \"on\" or . == \"1\")" +
                    " elif . == null then false" +
                    " else null end");
            case "ceiling" -> jq.append(" | ceil");
            case "floor", "integer", "bigint" -> jq.append(" | floor");
            case "abs" -> jq.append(" | fabs");
            // PG17 string methods
            case "lower" -> jq.append(" | ascii_downcase");
            case "upper" -> jq.append(" | ascii_upcase");
            case "ltrim" -> {
                if (args.isEmpty()) {
                    // ltrim() — trim leading whitespace
                    jq.append(" | sub(\"^\\\\s+\"; \"\")");
                } else {
                    // ltrim("chars") — remove leading characters in the set.
                    // Regex-escape first (char class), then jq-escape for
                    // embedding (a quote in chars would otherwise break out).
                    String chars = escapeJqString(escapeRegexChars(args.get(0)));
                    jq.append(" | sub(\"^[").append(chars).append("]+\"; \"\")");
                }
            }
            case "rtrim" -> {
                if (args.isEmpty()) {
                    jq.append(" | sub(\"\\\\s+$\"; \"\")");
                } else {
                    String chars = escapeJqString(escapeRegexChars(args.get(0)));
                    jq.append(" | sub(\"[").append(chars).append("]+$\"; \"\")");
                }
            }
            case "btrim" -> {
                if (args.isEmpty()) {
                    jq.append(" | sub(\"^\\\\s+\"; \"\") | sub(\"\\\\s+$\"; \"\")");
                } else {
                    String chars = escapeJqString(escapeRegexChars(args.get(0)));
                    jq.append(" | sub(\"^[").append(chars).append("]+\"; \"\") | sub(\"[")
                      .append(chars).append("]+$\"; \"\")");
                }
            }
            case "replace" -> {
                if (args.size() >= 2) {
                    // PostgreSQL replace is literal, jq gsub is regex:
                    // quote the pattern so metacharacters match themselves
                    jq.append(" | gsub(\"").append(escapeJqString(quoteRegex(args.get(0))))
                      .append("\"; \"").append(escapeJqString(args.get(1))).append("\")");
                }
            }
            case "initcap" -> {
                // Capitalize first letter of each word: split on spaces, capitalize each, rejoin
                jq.append(" | split(\" \") | map(if length > 0 then (.[:1] | ascii_upcase) + (.[1:] | ascii_downcase) else . end) | join(\" \")");
            }
            case "split_part" -> {
                if (args.size() >= 2) {
                    String sep = escapeJqString(args.get(0));
                    int idx = parseMethodInt(methodName, args.get(1));
                    if (idx == 0) {
                        // PostgreSQL returns "" for field 0
                        jq.append(" | \"\"");
                    } else if (idx > 0) {
                        // PostgreSQL split_part is 1-based; // "" covers
                        // out-of-range (jq null) where PG returns ""
                        jq.append(" | split(\"").append(sep).append("\")[").append(idx - 1).append("] // \"\"");
                    } else {
                        // Negative index: count from end
                        jq.append(" | split(\"").append(sep).append("\")[").append(idx).append("] // \"\"");
                    }
                }
            }
            default -> jq.append(" | ").append(methodName); // unknown method — pass through
        }
    }

    /** Escape a string for use inside a jq string literal (inside double quotes). */
    private static String escapeJqString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Parse an integer method argument, throwing a clean error for dynamic
     * values (e.g. $var) instead of leaking NumberFormatException.
     */
    private static int parseMethodInt(String methodName, String arg) {
        try {
            return Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Method ." + methodName + "() requires an integer literal argument, got: " + arg);
        }
    }

    /** Escape characters for use inside a regex character class [...]. */
    private static String escapeRegexChars(String s) {
        // In a regex character class, ] - \ ^ need escaping
        return s.replace("\\", "\\\\").replace("]", "\\]").replace("-", "\\-").replace("^", "\\^");
    }

    /** Return 10^n as a long (for scale calculations). */
    private static long pow10(int n) {
        long result = 1;
        for (int i = 0; i < n; i++) result *= 10;
        return result;
    }

    // ========================================================================
    //  Recursive descent: **{N}, **{M to N}, **
    // ========================================================================

    private void translateRecursiveDescent() {
        if (peek().is(LBRACE)) {
            advance(); // consume {
            // **{N} or **{M to N}
            if (peek().is(INTEGER)) {
                advance(); // consume depth — currently discarded (TODO: depth-limited recurse)
                // 'to' is context-sensitive: inside [] it's KW_TO, but inside {} it's IDENT
                if (peek().is(KW_TO) || (peek().is(IDENT) && "to".equals(peek().value()))) {
                    advance(); // consume TO
                    if (peek().is(INTEGER)) advance(); // consume max depth
                }
            }
            if (peek().is(RBRACE)) advance();
        }
        jq.append(" | recurse");
    }

    // ========================================================================
    //  Filters: ?(expression)
    // ========================================================================

    private void translateFilter() {
        advance(); // consume QUESTION

        if (!peek().is(LPAREN)) {
            jq.append(" | select(true)"); // bare ? with no parens
            return;
        }
        advance(); // consume LPAREN

        // If the path before the filter doesn't end with []? (array iteration),
        // decide how the filter input reaches select().
        // Lax mode auto-wraps: iterate unconditionally (proven behavior).
        // Strict mode is type-directed: iterate actual arrays, test anything
        // else directly. Unconditional []? iterates object values in strict
        // mode (testing e.g. `true` instead of `{"active":true}`); no marker
        // tests the value itself.
        String currentJq = jq.toString();
        if (!currentJq.endsWith("[]?") && !currentJq.endsWith("[]")) {
            if (mode == JsonpathToJq.Mode.LAX) {
                jq.append("[]?");
            } else {
                jq.append(" | (if type == \"array\" then .[] else . end)");
            }
        }

        jq.append(" | select(");
        translateFilterBody();
        jq.append(")");

        if (peek().is(RPAREN)) advance(); // consume RPAREN
    }

    /** Translate @ plus one access step: @.field, @["key"], @[idx], @.*, or bare @. */
    private void translateCurrentAccess() {
        advance(); // consume @
        if (peek().is(DOT)) {
            advance(); // consume DOT
            if (peek().is(IDENT)) {
                String name = advance().value();
                if (isBareJqIdentifier(name)) {
                    jq.append(".").append(name);
                } else {
                    jq.append(".[\"");
                    appendJqEscaped(name);
                    jq.append("\"]");
                }
            } else if (peek().is(STRING)) {
                jq.append(".[\"");
                appendJqEscaped(advance().value());
                jq.append("\"]");
            } else if (peek().is(STAR)) {
                // @.* — wildcard on the current item
                advance();
                jq.append(".[]?");
            }
        } else if (peek().is(LBRACKET)) {
            jq.append(".");
            // Don't consume — let translateBracket handle it
            translateBracket();
        } else {
            jq.append(".");
        }
    }

    /**
     * Translate exactly one operand for prefix ! — an @-access, a balanced
     * parenthesized expression, an exists() call, or a literal. Unlike the
     * filter body loop, this stops after one operand so ! binds tightly
     * (PostgreSQL precedence), instead of swallowing the rest of the body.
     */
    private void translateNegatedOperand() {
        if (peek().is(LPAREN)) {
            advance(); // consume (
            jq.append("(");
            translateFilterBody(); // nesting handled recursively; stops at matching paren
            jq.append(")");
            if (peek().is(RPAREN)) advance(); // consume )
        } else if (peek().is(CURRENT)) {
            translateCurrentAccess();
        } else if (peek().is(KW_EXISTS)) {
            translateExists();
        } else if (peek().is(INTEGER) || peek().is(DECIMAL)) {
            jq.append(advance().value());
        } else if (peek().is(STRING)) {
            jq.append("\"").append(escapeJq(advance().value())).append("\"");
        } else if (peek().is(TRUE)) {
            advance();
            jq.append("true");
        } else if (peek().is(FALSE)) {
            advance();
            jq.append("false");
        } else if (peek().is(NULL)) {
            advance();
            jq.append("null");
        } else {
            // Unexpected token (including RPAREN on malformed input like ?(!))
            // — skip one token to avoid stalling, negate false
            if (!peek().is(EOF)) advance();
            jq.append("false");
        }
    }

    /** Translate the body of a filter expression. Handles @, &&, ||, exists, like_regex, etc. */
    private void translateFilterBody() {
        while (!peek().is(RPAREN) && !peek().is(EOF)) {
            JsonpathToken token = peek();
            switch (token.type()) {
                case CURRENT -> translateCurrentAccess();
                case AND -> { advance(); jq.append(" and "); }
                case OR -> { advance(); jq.append(" or "); }
                case NOT -> {
                    // PostgreSQL ! is prefix and binds to one operand: !(expr).
                    // jq 'not' is postfix: (expr | not).
                    advance();
                    jq.append("(");
                    translateNegatedOperand();
                    jq.append(" | not)");
                }
                case EQ -> { advance(); jq.append(" == "); }
                case NEQ, LTGT -> { advance(); jq.append(" != "); }
                case LT -> { advance(); jq.append(" < "); }
                case GT -> { advance(); jq.append(" > "); }
                case LE -> { advance(); jq.append(" <= "); }
                case GE -> { advance(); jq.append(" >= "); }
                case PLUS -> { advance(); jq.append(" + "); }
                case MINUS -> { advance(); jq.append(" - "); }
                case STAR -> { advance(); jq.append(" * "); }
                case SLASH -> { advance(); jq.append(" / "); }
                case PERCENT -> { advance(); jq.append(" % "); }
                case INTEGER, DECIMAL -> { advance(); jq.append(token.value()); }
                case STRING -> { advance(); jq.append("\"").append(escapeJq(token.value())).append("\""); }
                case TRUE -> { advance(); jq.append("true"); }
                case FALSE -> { advance(); jq.append("false"); }
                case NULL -> { advance(); jq.append("null"); }
                case KW_EXISTS -> translateExists();
                case KW_LIKE_REGEX -> translateLikeRegex();
                case KW_STARTS -> translateStartsWith();
                case KW_IS -> translateIsUnknown();
                case LPAREN -> {
                    advance();
                    jq.append("(");
                    translateFilterBody();
                    jq.append(")");
                    if (peek().is(RPAREN)) advance();
                }
                case QUESTION -> {
                    // Nested filter: ?(@.x ?(subfilter))
                    translateFilter();
                }
                case LBRACKET -> {
                    // Bracket access in filter body: @.a[*], @.a[0], etc.
                    translateBracket();
                }
                case DOT -> {
                    // .field access (after @ was already converted)
                    advance();
                    if (peek().is(IDENT)) {
                        String name = advance().value();
                        if (isBareJqIdentifier(name)) {
                            jq.append(".").append(name);
                        } else {
                            jq.append(".[\"");
                            appendJqEscaped(name);
                            jq.append("\"]");
                        }
                    } else if (peek().is(STRING)) {
                        jq.append(".[\"");
                        appendJqEscaped(advance().value());
                        jq.append("\"]");
                    }
                }
                case IDENT -> {
                    // Bare identifier in filter — could be a field reference
                    jq.append(advance().value());
                }
                case ROOT -> {
                    // $ inside filter — root reference. jq has no root access
                    // inside select; emit as-is (jq reports an error) rather
                    // than silently substituting the current item.
                    advance();
                    jq.append("$");
                }
                case NAMED_VARIABLE -> {
                    // $varname — PostgreSQL parameterized variable
                    jq.append("$").append(advance().value());
                }
                default -> advance(); // skip unknown tokens
            }
        }
    }

    /** exists(@.field) → has("field") or (try .path // null) != null */
    private void translateExists() {
        advance(); // consume KW_EXISTS
        if (!peek().is(LPAREN)) return;
        advance(); // consume LPAREN

        if (peek().is(CURRENT)) {
            advance(); // consume @
            if (peek().is(DOT)) {
                advance(); // consume DOT
                if (peek().is(IDENT)) {
                    String field = advance().value();
                    // Check if there are more dots (nested path)
                    if (peek().is(DOT)) {
                        // exists(@.a.b.c) — walk with existence checks so
                        // explicit nulls count as existing but missing keys don't
                        StringBuilder path = new StringBuilder();
                        appendExistsSegment(path, field);
                        while (peek().is(DOT)) {
                            advance();
                            if (peek().is(IDENT)) {
                                appendExistsSegment(path, advance().value());
                            } else {
                                break;
                            }
                        }
                        jq.append("((try .").append(path).append(" // null) != null)");
                    } else {
                        // exists(@.field) → has("field")
                        jq.append("has(\"").append(escapeJq(field)).append("\")");
                    }
                } else if (peek().is(STAR)) {
                    // exists(@.*) — any values present
                    advance();
                    jq.append("(length > 0)");
                }
            } else if (peek().is(LBRACKET)) {
                advance(); // consume LBRACKET
                if (peek().is(INTEGER)) {
                    // exists(@[N]) → has(N) (true even for explicit nulls)
                    jq.append("has(").append(advance().value()).append(")");
                    if (peek().is(RBRACKET)) advance();
                }
                // Anything else: leave unhandled (contributes nothing,
                // downstream select() fails loudly like before)
            }
        } else if (peek().is(ROOT)) {
            // exists($.a.b.c) — walk from root with key-existence checks:
            // ((try OBJ catch {}) | has("LAST")) is true for explicit nulls
            // (has sees the key) and false for missing keys or type errors.
            advance(); // consume $
            var segments = new java.util.ArrayList<String>();
            var indexFlags = new java.util.ArrayList<Boolean>();
            while (true) {
                if (peek().is(DOT)) {
                    advance();
                    if (peek().is(IDENT) || peek().is(STRING)) {
                        segments.add(advance().value());
                        indexFlags.add(false);
                        continue;
                    }
                    break;
                }
                if (peek().is(LBRACKET)) {
                    advance();
                    if (peek().is(INTEGER) && peekAt(1) != null && peekAt(1).is(RBRACKET)) {
                        segments.add(advance().value());
                        indexFlags.add(true);
                        advance(); // consume RBRACKET
                        continue;
                    }
                    break;
                }
                break;
            }
            if (!segments.isEmpty()) {
                StringBuilder obj = new StringBuilder();
                for (int i = 0; i < segments.size() - 1; i++) {
                    if (indexFlags.get(i)) {
                        obj.append('[').append(segments.get(i)).append(']');
                    } else {
                        appendExistsSegment(obj, segments.get(i));
                    }
                }
                jq.append("((try .").append(obj).append(" catch {}) | has(");
                String lastKey = segments.get(segments.size() - 1);
                if (indexFlags.get(indexFlags.size() - 1)) {
                    jq.append(lastKey);
                } else {
                    jq.append("\"").append(escapeJq(lastKey)).append("\"");
                }
                jq.append("))");
            }
            // No segments (bare $): contributes nothing, as before
        }
        if (peek().is(RPAREN)) advance();
    }

    /** Append one exists-path segment with bracket notation when needed. */
    private void appendExistsSegment(StringBuilder path, String name) {
        if (isBareJqIdentifier(name)) {
            if (path.length() > 0) path.append('.');
            path.append(name);
        } else {
            path.append("[\"").append(escapeJq(name)).append("\"]");
        }
    }

    /** subject like_regex "pattern" [flag "flags"] */
    private void translateLikeRegex() {
        // The subject is already emitted before like_regex was encountered
        // Actually, like_regex appears AFTER the subject in the token stream
        // We need to handle this differently — the subject was already emitted to jq
        advance(); // consume KW_LIKE_REGEX
        JsonpathToken pattern = expect(STRING);

        String flags = null;
        if (peek().is(KW_FLAG)) {
            advance(); // consume FLAG
            flags = expect(STRING).value();
        }

        // Wrap in type guard: (subject | type == "string" and test("pattern"))
        // The subject was already emitted — we need to pipe into test.
        // Flag "q" quotes the whole pattern (literal match): regex-escape it
        // and strip q (jq has no quote flag). Without q the pattern is a
        // PostgreSQL ARE regex; embed it with quotes-only escaping so ARE
        // constructs like \b (backspace in ARE, word boundary in jq/Oniguruma)
        // survive via jq string decoding, exactly as the lexer value holds them.
        String regexSource = pattern.value();
        String outFlags = flags != null ? flags : "";
        if (outFlags.indexOf('q') >= 0) {
            // Quoted pattern: regex-escape first, then jq-escape for embedding
            regexSource = escapeJq(quoteRegex(regexSource));
            outFlags = outFlags.replace("q", "");
        } else {
            // Raw ARE pattern: escape only quotes so backslash constructs
            // reach jq decoding exactly as the lexer value holds them
            regexSource = escapeJqQuotesOnly(regexSource);
        }
        jq.append(" | type == \"string\" and test(\"").append(regexSource).append("\"");
        if (!outFlags.isEmpty()) {
            jq.append("; \"").append(escapeJq(outFlags)).append("\"");
        }
        jq.append(")");
    }

    /** subject starts with "prefix" */
    private void translateStartsWith() {
        advance(); // consume KW_STARTS
        if (peek().is(KW_WITH)) advance(); // consume KW_WITH
        JsonpathToken prefix = expect(STRING);
        jq.append(" | startswith(\"").append(escapeJq(prefix.value())).append("\")");
    }

    /**
     * Translate {@code expr is unknown} — PostgreSQL's three-valued logic check.
     * Returns true when the preceding expression evaluates to SQL NULL (unknown),
     * which in jq terms means the expression either produces null or throws an error.
     * Translation: wrap the preceding expression in try-catch, returning true on error or null.
     */
    private void translateIsUnknown() {
        advance(); // consume KW_IS
        if (peek().is(KW_UNKNOWN)) {
            advance();
            // "is unknown" means the result is SQL NULL — either null or an error
            // Use (. == null or . == false) as approximation, since PostgreSQL returns
            // unknown for type mismatches and undefined comparisons
            jq.append(" | . == null");
        }
    }

    // ========================================================================
    //  Token access helpers
    // ========================================================================

    private JsonpathToken peek() {
        return pos < tokens.size() ? tokens.get(pos) : new JsonpathToken(EOF, -1);
    }

    private JsonpathToken peekAt(int offset) {
        int idx = pos + offset;
        return idx < tokens.size() ? tokens.get(idx) : null;
    }

    private JsonpathToken advance() {
        return pos < tokens.size() ? tokens.get(pos++) : new JsonpathToken(EOF, -1);
    }

    private JsonpathToken expect(JsonpathTokenType type) {
        JsonpathToken token = peek();
        if (token.is(type)) {
            return advance();
        }
        // Expected token not found — return current token for error context
        return advance();
    }
}

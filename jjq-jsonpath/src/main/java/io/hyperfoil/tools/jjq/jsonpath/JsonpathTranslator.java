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
            "integer", "bigint", "number", "decimal"
    );

    private final List<JsonpathToken> tokens;
    private final JsonpathToJq.Mode mode;
    private int pos;
    private final StringBuilder jq;

    public JsonpathTranslator(List<JsonpathToken> tokens, JsonpathToJq.Mode mode) {
        this.tokens = tokens;
        this.mode = mode;
        this.pos = 0;
        this.jq = new StringBuilder();
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
        // Handle root reference
        if (peek().is(ROOT)) {
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
                // Check if this is a method call: IDENT LPAREN RPAREN
                if (peek().is(LPAREN) && METHODS.contains(name)) {
                    advance(); // consume LPAREN
                    if (peek().is(RPAREN)) advance(); // consume RPAREN
                    translateMethod(name);
                } else if (name.contains("-")) {
                    // Hyphenated field name → bracket notation
                    jq.append(".[\"").append(name).append("\"]");
                } else {
                    jq.append(".").append(name);
                }
            }
            case STRING -> {
                // ."quoted field" → .["quoted field"]
                String fieldName = next.value();
                advance();
                jq.append(".[\"").append(fieldName).append("\"]");
            }
            case INTEGER -> {
                // .0, .1, etc. — numeric field name (array index via dot notation)
                jq.append(".").append(advance().value());
            }
            default -> {
                // Just a dot — unusual but possible
            }
        }
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
            // [last to ???] — unusual but handle it
            advance(); // consume TO
            // This would mean "from last to ..." which doesn't make much sense
            // but let's handle [last to last] → [-1:]
            if (peek().is(KW_LAST)) {
                advance();
                expect(RBRACKET);
                jq.append("[-1:]");
            }
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

            if (peek().is(KW_LAST)) {
                advance(); // consume LAST

                if (peek().is(MINUS)) {
                    // [N to last - M] → [N:-(M+1)]
                    advance();
                    JsonpathToken m = expect(INTEGER);
                    int offset = Integer.parseInt(m.value());
                    expect(RBRACKET);
                    jq.append("[").append(firstVal).append(":").append(-(offset + 1)).append("]");
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
                }
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
                            if (peek().is(RPAREN)) advance(); // RPAREN
                            translateMethod(name);
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

    private void translateMethod(String methodName) {
        switch (methodName) {
            case "size" -> jq.append(" | length");
            case "keyvalue" -> jq.append(" | to_entries[]");
            case "double", "number", "decimal" -> jq.append(" | tonumber");
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
            default -> jq.append(" | ").append(methodName); // unknown method — pass through
        }
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
                if (peek().is(KW_TO)) {
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
        // add []? to iterate elements before filtering.
        // This matches PostgreSQL behavior: $.data ?(@.active) iterates data's elements.
        String currentJq = jq.toString();
        if (!currentJq.endsWith("[]?") && !currentJq.endsWith("[]")) {
            jq.append("[]?");
        }

        jq.append(" | select(");
        translateFilterBody();
        jq.append(")");

        if (peek().is(RPAREN)) advance(); // consume RPAREN
    }

    /** Translate the body of a filter expression. Handles @, &&, ||, exists, like_regex, etc. */
    private void translateFilterBody() {
        while (!peek().is(RPAREN) && !peek().is(EOF)) {
            JsonpathToken token = peek();
            switch (token.type()) {
                case CURRENT -> {
                    advance(); // consume @
                    if (peek().is(DOT)) {
                        advance(); // consume DOT
                        if (peek().is(IDENT)) {
                            jq.append(".").append(advance().value());
                        } else if (peek().is(STRING)) {
                            jq.append(".[\"").append(advance().value()).append("\"]");
                        }
                    } else if (peek().is(LBRACKET)) {
                        jq.append(".");
                        // Don't consume — let translateBracket handle it
                        translateBracket();
                    } else {
                        jq.append(".");
                    }
                }
                case AND -> { advance(); jq.append(" and "); }
                case OR -> { advance(); jq.append(" or "); }
                case NOT -> {
                    advance();
                    // PostgreSQL ! is prefix: !(expr). jq 'not' is postfix: (expr | not).
                    // If followed by (, translate the parenthesized expression then append | not
                    if (peek().is(LPAREN)) {
                        advance(); // consume (
                        jq.append("(");
                        translateFilterBody();
                        jq.append(" | not)");
                        if (peek().is(RPAREN)) advance(); // consume )
                    } else {
                        // Bare ! — approximate as postfix not on next expression
                        jq.append("(");
                        translateFilterBody(); // will consume the next expression
                        jq.append(" | not)");
                    }
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
                case STRING -> { advance(); jq.append("\"").append(token.value()).append("\""); }
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
                        if (name.contains("-")) {
                            jq.append(".[\"").append(name).append("\"]");
                        } else {
                            jq.append(".").append(name);
                        }
                    } else if (peek().is(STRING)) {
                        jq.append(".[\"").append(advance().value()).append("\"]");
                    }
                }
                case IDENT -> {
                    // Bare identifier in filter — could be a field reference
                    jq.append(advance().value());
                }
                case ROOT -> {
                    // $ inside filter — root reference
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
                        // exists(@.a.b.c) → (try .a.b.c // null) != null
                        StringBuilder path = new StringBuilder(".").append(field);
                        while (peek().is(DOT)) {
                            advance();
                            if (peek().is(IDENT)) {
                                path.append(".").append(advance().value());
                            }
                        }
                        jq.append("((try ").append(path).append(" // null) != null)");
                    } else {
                        // exists(@.field) → has("field")
                        jq.append("has(\"").append(field).append("\")");
                    }
                }
            }
        }
        if (peek().is(RPAREN)) advance();
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
        // The subject was already emitted — we need to pipe into test
        jq.append(" | type == \"string\" and test(\"").append(pattern.value()).append("\"");
        if (flags != null && !flags.isEmpty()) {
            jq.append("; \"").append(flags).append("\"");
        }
        jq.append(")");
    }

    /** subject starts with "prefix" */
    private void translateStartsWith() {
        advance(); // consume KW_STARTS
        if (peek().is(KW_WITH)) advance(); // consume KW_WITH
        JsonpathToken prefix = expect(STRING);
        jq.append(" | startswith(\"").append(prefix.value()).append("\")");
    }

    /** expr is unknown → not supported, emit comment */
    private void translateIsUnknown() {
        advance(); // consume KW_IS
        if (peek().is(KW_UNKNOWN)) {
            advance();
            jq.append(" == null"); // approximate: "is unknown" ≈ "is null" in jq
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

    private boolean match(JsonpathTokenType type) {
        if (peek().is(type)) {
            advance();
            return true;
        }
        return false;
    }
}

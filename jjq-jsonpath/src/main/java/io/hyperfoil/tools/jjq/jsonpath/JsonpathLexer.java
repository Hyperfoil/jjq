package io.hyperfoil.tools.jjq.jsonpath;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.hyperfoil.tools.jjq.jsonpath.JsonpathTokenType.*;

/**
 * Tokenizer for PostgreSQL jsonpath expressions.
 *
 * <p>Single-pass scanner that produces a list of {@link JsonpathToken}s.
 * Tracks bracket depth for context-sensitive keyword recognition:
 * {@code last} and {@code to} are keywords inside {@code [...]} and
 * identifiers outside.</p>
 *
 * <p>Follows the same pattern as jjq's own jq expression lexer
 * ({@code io.hyperfoil.tools.jjq.lexer.Lexer}).</p>
 */
public final class JsonpathLexer {

    /** Keywords recognized everywhere (not context-sensitive). */
    private static final Map<String, JsonpathTokenType> GLOBAL_KEYWORDS = Map.ofEntries(
            Map.entry("strict", KW_STRICT),
            Map.entry("lax", KW_LAX),
            Map.entry("true", TRUE),
            Map.entry("false", FALSE),
            Map.entry("null", NULL),
            Map.entry("exists", KW_EXISTS),
            Map.entry("is", KW_IS),
            Map.entry("unknown", KW_UNKNOWN),
            Map.entry("like_regex", KW_LIKE_REGEX),
            Map.entry("flag", KW_FLAG),
            Map.entry("starts", KW_STARTS),
            Map.entry("with", KW_WITH)
    );

    /** Keywords recognized only inside brackets. */
    private static final Map<String, JsonpathTokenType> BRACKET_KEYWORDS = Map.of(
            "last", KW_LAST,
            "to", KW_TO
    );

    private final String input;
    private int pos;
    private int bracketDepth;

    public JsonpathLexer(String input) {
        this.input = input;
        this.pos = 0;
        this.bracketDepth = 0;
    }

    /** Tokenize the entire input. */
    public List<JsonpathToken> tokenize() {
        var tokens = new ArrayList<JsonpathToken>();
        while (true) {
            JsonpathToken token = nextToken();
            tokens.add(token);
            if (token.type() == EOF) break;
        }
        return tokens;
    }

    /** Scan the next token. */
    private JsonpathToken nextToken() {
        skipWhitespace();
        if (pos >= input.length()) {
            return new JsonpathToken(EOF, pos);
        }

        int startPos = pos;
        char c = input.charAt(pos);

        return switch (c) {
            case '$' -> lexDollar(startPos);
            case '@' -> { pos++; yield new JsonpathToken(CURRENT, startPos); }
            case '.' -> lexDot(startPos);
            case '[' -> { pos++; bracketDepth++; yield new JsonpathToken(LBRACKET, startPos); }
            case ']' -> { pos++; bracketDepth--; yield new JsonpathToken(RBRACKET, startPos); }
            case '(' -> { pos++; yield new JsonpathToken(LPAREN, startPos); }
            case ')' -> { pos++; yield new JsonpathToken(RPAREN, startPos); }
            case '{' -> { pos++; yield new JsonpathToken(LBRACE, startPos); }
            case '}' -> { pos++; yield new JsonpathToken(RBRACE, startPos); }
            case ',' -> { pos++; yield new JsonpathToken(COMMA, startPos); }
            case '?' -> { pos++; yield new JsonpathToken(QUESTION, startPos); }
            case '*' -> {
                pos++;
                if (pos < input.length() && input.charAt(pos) == '*') {
                    pos++;
                    yield new JsonpathToken(DOUBLESTAR, startPos);
                }
                yield new JsonpathToken(STAR, startPos);
            }
            case '+' -> { pos++; yield new JsonpathToken(PLUS, startPos); }
            case '-' -> lexMinusOrNumber(startPos);
            case '/' -> { pos++; yield new JsonpathToken(SLASH, startPos); }
            case '%' -> { pos++; yield new JsonpathToken(PERCENT, startPos); }
            case '=' -> lexEquals(startPos);
            case '!' -> lexBang(startPos);
            case '<' -> lexLessThan(startPos);
            case '>' -> lexGreaterThan(startPos);
            case '&' -> lexAmpersand(startPos);
            case '|' -> lexPipe(startPos);
            case '"' -> lexString(startPos);
            default -> {
                if (Character.isDigit(c)) yield lexNumber(startPos);
                if (isIdentStart(c)) yield lexIdentOrKeyword(startPos);
                // Unknown character — emit as single-char IDENT for robustness
                pos++;
                yield new JsonpathToken(IDENT, String.valueOf(c), startPos);
            }
        };
    }

    // ========================================================================
    //  Specific token scanners
    // ========================================================================

    /** $ alone → ROOT; $identifier → NAMED_VARIABLE */
    private JsonpathToken lexDollar(int startPos) {
        pos++; // consume $
        if (pos < input.length() && isIdentStart(input.charAt(pos))) {
            // $varname
            int nameStart = pos;
            while (pos < input.length() && isIdentPart(input.charAt(pos))) pos++;
            return new JsonpathToken(NAMED_VARIABLE, input.substring(nameStart, pos), startPos);
        }
        return new JsonpathToken(ROOT, startPos);
    }

    /** . alone → DOT; .* → DOT + (leave * for next); ** → DOUBLESTAR */
    private JsonpathToken lexDot(int startPos) {
        pos++; // consume .
        // Check for ** (recursive descent) — but only if preceded by another DOT context
        // Actually, ** in jsonpath is written as .** or just ** after $
        // The DOT is just a DOT — the caller handles .**
        return new JsonpathToken(DOT, startPos);
    }

    /** - could be MINUS or start of a negative number (inside brackets) */
    private JsonpathToken lexMinusOrNumber(int startPos) {
        if (bracketDepth > 0 && pos + 1 < input.length() && Character.isDigit(input.charAt(pos + 1))) {
            // Negative number inside brackets: [-1], [last - 3]
            return lexNumber(startPos);
        }
        pos++;
        return new JsonpathToken(MINUS, startPos);
    }

    /** == */
    private JsonpathToken lexEquals(int startPos) {
        pos++; // consume =
        if (pos < input.length() && input.charAt(pos) == '=') {
            pos++;
            return new JsonpathToken(EQ, startPos);
        }
        // Single = is not valid in jsonpath — emit as-is for error handling
        return new JsonpathToken(EQ, startPos);
    }

    /** != or ! (NOT) */
    private JsonpathToken lexBang(int startPos) {
        pos++; // consume !
        if (pos < input.length() && input.charAt(pos) == '=') {
            pos++;
            return new JsonpathToken(NEQ, startPos);
        }
        return new JsonpathToken(NOT, startPos);
    }

    /** <, <=, <> */
    private JsonpathToken lexLessThan(int startPos) {
        pos++; // consume <
        if (pos < input.length()) {
            if (input.charAt(pos) == '=') { pos++; return new JsonpathToken(LE, startPos); }
            if (input.charAt(pos) == '>') { pos++; return new JsonpathToken(LTGT, startPos); }
        }
        return new JsonpathToken(LT, startPos);
    }

    /** >, >= */
    private JsonpathToken lexGreaterThan(int startPos) {
        pos++; // consume >
        if (pos < input.length() && input.charAt(pos) == '=') {
            pos++;
            return new JsonpathToken(GE, startPos);
        }
        return new JsonpathToken(GT, startPos);
    }

    /** && */
    private JsonpathToken lexAmpersand(int startPos) {
        pos++; // consume &
        if (pos < input.length() && input.charAt(pos) == '&') {
            pos++;
            return new JsonpathToken(AND, startPos);
        }
        // Single & — not valid in jsonpath, emit for error handling
        return new JsonpathToken(AND, startPos);
    }

    /** || */
    private JsonpathToken lexPipe(int startPos) {
        pos++; // consume |
        if (pos < input.length() && input.charAt(pos) == '|') {
            pos++;
            return new JsonpathToken(OR, startPos);
        }
        // Single | — not valid in jsonpath
        return new JsonpathToken(OR, startPos);
    }

    /** Quoted string: "..." with escape handling */
    private JsonpathToken lexString(int startPos) {
        pos++; // consume opening "
        var sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == '\\' && pos + 1 < input.length()) {
                pos++;
                sb.append(input.charAt(pos));
                pos++;
            } else if (c == '"') {
                pos++; // consume closing "
                return new JsonpathToken(STRING, sb.toString(), startPos);
            } else {
                sb.append(c);
                pos++;
            }
        }
        // Unterminated string — return what we have
        return new JsonpathToken(STRING, sb.toString(), startPos);
    }

    /** Number: integer or decimal (including negative, scientific notation) */
    private JsonpathToken lexNumber(int startPos) {
        int numStart = pos;
        if (pos < input.length() && input.charAt(pos) == '-') pos++; // negative sign
        while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;

        boolean isDecimal = false;
        if (pos < input.length() && input.charAt(pos) == '.'
                && pos + 1 < input.length() && Character.isDigit(input.charAt(pos + 1))) {
            // Only consume the dot if followed by a digit — otherwise it's field access
            // (e.g., $.results.0.value — the .0 is field access, not decimal 0.value)
            isDecimal = true;
            pos++;
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
        }
        // Scientific notation
        if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
            isDecimal = true;
            pos++;
            if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) pos++;
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
        }

        String numStr = input.substring(numStart, pos);
        return new JsonpathToken(isDecimal ? DECIMAL : INTEGER, numStr, startPos);
    }

    /** Identifier or keyword. Identifiers can contain hyphens in jsonpath. */
    private JsonpathToken lexIdentOrKeyword(int startPos) {
        int identStart = pos;
        // First char: letter or underscore
        pos++;
        // Subsequent chars: letter, digit, underscore, hyphen
        while (pos < input.length() && isIdentPart(input.charAt(pos))) pos++;

        String word = input.substring(identStart, pos);

        // Check for two-word keyword "like_regex"
        // (it's stored as a single keyword with underscore)

        // Check bracket-context keywords first
        if (bracketDepth > 0) {
            JsonpathTokenType kwType = BRACKET_KEYWORDS.get(word);
            if (kwType != null) return new JsonpathToken(kwType, word, startPos);
        }

        // Check global keywords
        JsonpathTokenType kwType = GLOBAL_KEYWORDS.get(word);
        if (kwType != null) return new JsonpathToken(kwType, word, startPos);

        // Check for * or ** after identifier — handle "**" as DOUBLESTAR
        // Actually ** appears as .$STAR$STAR — handled by the caller

        return new JsonpathToken(IDENT, word, startPos);
    }

    // ========================================================================
    //  Character classification helpers
    // ========================================================================

    private void skipWhitespace() {
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break;
            pos++;
        }
    }

    /** Can start an identifier: letter or underscore */
    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    /** Can continue an identifier: letter, digit, underscore, or hyphen */
    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-';
    }
}

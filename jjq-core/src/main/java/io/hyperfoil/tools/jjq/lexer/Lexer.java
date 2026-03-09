package io.hyperfoil.tools.jjq.lexer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Lexer {
    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
            Map.entry("if", TokenType.IF),
            Map.entry("then", TokenType.THEN),
            Map.entry("elif", TokenType.ELIF),
            Map.entry("else", TokenType.ELSE),
            Map.entry("end", TokenType.END),
            Map.entry("and", TokenType.AND),
            Map.entry("or", TokenType.OR),
            Map.entry("not", TokenType.NOT),
            Map.entry("def", TokenType.DEF),
            Map.entry("as", TokenType.AS),
            Map.entry("reduce", TokenType.REDUCE),
            Map.entry("foreach", TokenType.FOREACH),
            Map.entry("try", TokenType.TRY),
            Map.entry("catch", TokenType.CATCH),
            Map.entry("import", TokenType.IMPORT),
            Map.entry("include", TokenType.INCLUDE),
            Map.entry("label", TokenType.LABEL),
            Map.entry("break", TokenType.BREAK),
            Map.entry("true", TokenType.TRUE),
            Map.entry("false", TokenType.FALSE),
            Map.entry("null", TokenType.NULL)
    );

    private final String input;
    private int pos;
    private int line;
    private int column;
    private int interpDepth;
    private int parenDepth;

    public Lexer(String input) {
        this.input = input;
        this.pos = 0;
        this.line = 1;
        this.column = 1;
        this.interpDepth = 0;
        this.parenDepth = 0;
    }

    public List<Token> tokenize() {
        var tokens = new ArrayList<Token>();
        while (true) {
            Token token = nextToken();
            tokens.add(token);
            if (token.type() == TokenType.EOF) break;
        }
        return tokens;
    }

    public Token nextToken() {
        skipWhitespaceAndComments();
        if (pos >= input.length()) {
            return new Token(TokenType.EOF, pos, line, column);
        }

        int startPos = pos;
        int startLine = line;
        int startCol = column;
        char c = input.charAt(pos);

        return switch (c) {
            case '.' -> lexDot(startPos, startLine, startCol);
            case '|' -> {
                advance();
                if (pos < input.length() && input.charAt(pos) == '=') {
                    advance();
                    yield new Token(TokenType.UPDATE_ASSIGN, startPos, startLine, startCol);
                }
                yield new Token(TokenType.PIPE, startPos, startLine, startCol);
            }
            case ',' -> { advance(); yield new Token(TokenType.COMMA, startPos, startLine, startCol); }
            case ':' -> { advance(); yield new Token(TokenType.COLON, startPos, startLine, startCol); }
            case ';' -> { advance(); yield new Token(TokenType.SEMICOLON, startPos, startLine, startCol); }
            case '(' -> {
                advance();
                if (interpDepth > 0) parenDepth++;
                yield new Token(TokenType.LPAREN, startPos, startLine, startCol);
            }
            case ')' -> {
                advance();
                if (interpDepth > 0 && parenDepth == 0) {
                    interpDepth--;
                    yield lexStringAfterInterp(startPos, startLine, startCol);
                }
                if (interpDepth > 0) parenDepth--;
                yield new Token(TokenType.RPAREN, startPos, startLine, startCol);
            }
            case '[' -> { advance(); yield new Token(TokenType.LBRACKET, startPos, startLine, startCol); }
            case ']' -> { advance(); yield new Token(TokenType.RBRACKET, startPos, startLine, startCol); }
            case '{' -> { advance(); yield new Token(TokenType.LBRACE, startPos, startLine, startCol); }
            case '}' -> { advance(); yield new Token(TokenType.RBRACE, startPos, startLine, startCol); }
            case '+' -> lexOperatorAssign('+', TokenType.PLUS, TokenType.PLUS_ASSIGN, startPos, startLine, startCol);
            case '-' -> lexMinus(startPos, startLine, startCol);
            case '*' -> lexOperatorAssign('*', TokenType.STAR, TokenType.STAR_ASSIGN, startPos, startLine, startCol);
            case '%' -> lexOperatorAssign('%', TokenType.PERCENT, TokenType.PERCENT_ASSIGN, startPos, startLine, startCol);
            case '/' -> lexSlash(startPos, startLine, startCol);
            case '=' -> lexEquals(startPos, startLine, startCol);
            case '<' -> lexLessThan(startPos, startLine, startCol);
            case '>' -> lexGreaterThan(startPos, startLine, startCol);
            case '!' -> lexBang(startPos, startLine, startCol);
            case '?' -> { advance(); yield new Token(TokenType.QUESTION, startPos, startLine, startCol); }
            case '"' -> lexString(startPos, startLine, startCol);
            case '$' -> lexVariable(startPos, startLine, startCol);
            case '@' -> lexFormat(startPos, startLine, startCol);
            default -> {
                if (Character.isDigit(c)) {
                    yield lexNumber(startPos, startLine, startCol);
                } else if (isIdentStart(c)) {
                    yield lexIdentOrKeyword(startPos, startLine, startCol);
                } else {
                    throw new LexerException("Unexpected character: '" + c + "'", startLine, startCol);
                }
            }
        };
    }

    private Token lexDot(int startPos, int startLine, int startCol) {
        advance();
        if (pos < input.length() && input.charAt(pos) == '.') {
            advance();
            return new Token(TokenType.DOTDOT, "..", startPos, startLine, startCol);
        }
        if (pos < input.length() && isIdentStart(input.charAt(pos))) {
            var sb = new StringBuilder();
            while (pos < input.length() && isIdentPart(input.charAt(pos))) {
                sb.append(input.charAt(pos));
                advance();
            }
            return new Token(TokenType.DOT, sb.toString(), startPos, startLine, startCol);
        }
        return new Token(TokenType.DOT, startPos, startLine, startCol);
    }

    private Token lexMinus(int startPos, int startLine, int startCol) {
        advance();
        if (pos < input.length() && input.charAt(pos) == '=') {
            advance();
            return new Token(TokenType.MINUS_ASSIGN, startPos, startLine, startCol);
        }
        return new Token(TokenType.MINUS, startPos, startLine, startCol);
    }

    private Token lexOperatorAssign(char op, TokenType opType, TokenType assignType,
                                     int startPos, int startLine, int startCol) {
        advance();
        if (pos < input.length() && input.charAt(pos) == '=') {
            advance();
            return new Token(assignType, startPos, startLine, startCol);
        }
        return new Token(opType, startPos, startLine, startCol);
    }

    private Token lexSlash(int startPos, int startLine, int startCol) {
        advance();
        if (pos < input.length() && input.charAt(pos) == '/') {
            advance();
            if (pos < input.length() && input.charAt(pos) == '=') {
                advance();
                return new Token(TokenType.ALTERNATIVE_ASSIGN, startPos, startLine, startCol);
            }
            return new Token(TokenType.ALTERNATIVE, startPos, startLine, startCol);
        }
        if (pos < input.length() && input.charAt(pos) == '=') {
            advance();
            return new Token(TokenType.SLASH_ASSIGN, startPos, startLine, startCol);
        }
        return new Token(TokenType.SLASH, startPos, startLine, startCol);
    }

    private Token lexEquals(int startPos, int startLine, int startCol) {
        advance();
        if (pos < input.length() && input.charAt(pos) == '=') {
            advance();
            return new Token(TokenType.EQ, startPos, startLine, startCol);
        }
        return new Token(TokenType.ASSIGN, startPos, startLine, startCol);
    }

    private Token lexLessThan(int startPos, int startLine, int startCol) {
        advance();
        if (pos < input.length() && input.charAt(pos) == '=') {
            advance();
            return new Token(TokenType.LE, startPos, startLine, startCol);
        }
        return new Token(TokenType.LT, startPos, startLine, startCol);
    }

    private Token lexGreaterThan(int startPos, int startLine, int startCol) {
        advance();
        if (pos < input.length() && input.charAt(pos) == '=') {
            advance();
            return new Token(TokenType.GE, startPos, startLine, startCol);
        }
        return new Token(TokenType.GT, startPos, startLine, startCol);
    }

    private Token lexBang(int startPos, int startLine, int startCol) {
        advance();
        if (pos < input.length() && input.charAt(pos) == '=') {
            advance();
            return new Token(TokenType.NEQ, startPos, startLine, startCol);
        }
        throw new LexerException("Expected '=' after '!'", startLine, startCol);
    }

    private Token lexNumber(int startPos, int startLine, int startCol) {
        var sb = new StringBuilder();
        while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
            sb.append(input.charAt(pos));
            advance();
        }
        if (pos < input.length() && input.charAt(pos) == '.') {
            // Check it's not a field access like 1.foo or range end like [1:2]
            if (pos + 1 < input.length() && Character.isDigit(input.charAt(pos + 1))) {
                sb.append('.');
                advance();
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                    sb.append(input.charAt(pos));
                    advance();
                }
            }
        }
        if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
            sb.append(input.charAt(pos));
            advance();
            if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
                sb.append(input.charAt(pos));
                advance();
            }
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                sb.append(input.charAt(pos));
                advance();
            }
        }
        return new Token(TokenType.NUMBER, sb.toString(), startPos, startLine, startCol);
    }

    private Token lexString(int startPos, int startLine, int startCol) {
        advance(); // skip opening quote
        var sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == '"') {
                advance();
                return new Token(TokenType.STRING, sb.toString(), startPos, startLine, startCol);
            }
            if (c == '\\') {
                advance();
                if (pos >= input.length()) {
                    throw new LexerException("Unterminated string escape", line, column);
                }
                char esc = input.charAt(pos);
                if (esc == '(') {
                    // String interpolation: \( ... )
                    advance();
                    interpDepth++;
                    parenDepth = 0;
                    return new Token(TokenType.STRING_INTERP_START, sb.toString(), startPos, startLine, startCol);
                }
                sb.append(switch (esc) {
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '/' -> '/';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'u' -> {
                        advance();
                        if (pos + 3 >= input.length()) {
                            throw new LexerException("Invalid unicode escape", line, column);
                        }
                        String hex = input.substring(pos, pos + 4);
                        pos += 3; column += 3;
                        yield (char) Integer.parseInt(hex, 16);
                    }
                    default -> throw new LexerException("Invalid escape: \\" + esc, line, column);
                });
                advance();
            } else {
                sb.append(c);
                advance();
            }
        }
        throw new LexerException("Unterminated string", startLine, startCol);
    }

    private Token lexStringAfterInterp(int startPos, int startLine, int startCol) {
        // Continue reading string after \(expr)
        var sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == '"') {
                advance();
                return new Token(TokenType.STRING_INTERP_END, sb.toString(), startPos, startLine, startCol);
            }
            if (c == '\\') {
                advance();
                if (pos >= input.length()) {
                    throw new LexerException("Unterminated string escape", line, column);
                }
                char esc = input.charAt(pos);
                if (esc == '(') {
                    advance();
                    interpDepth++;
                    parenDepth = 0;
                    return new Token(TokenType.STRING_INTERP_START, sb.toString(), startPos, startLine, startCol);
                }
                sb.append(switch (esc) {
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '/' -> '/';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'u' -> {
                        advance();
                        if (pos + 3 >= input.length()) {
                            throw new LexerException("Invalid unicode escape", line, column);
                        }
                        String hex = input.substring(pos, pos + 4);
                        pos += 3; column += 3;
                        yield (char) Integer.parseInt(hex, 16);
                    }
                    default -> throw new LexerException("Invalid escape: \\" + esc, line, column);
                });
                advance();
            } else {
                sb.append(c);
                advance();
            }
        }
        throw new LexerException("Unterminated string interpolation", startLine, startCol);
    }

    private Token lexVariable(int startPos, int startLine, int startCol) {
        advance(); // skip $
        var sb = new StringBuilder();
        while (pos < input.length() && isIdentPart(input.charAt(pos))) {
            sb.append(input.charAt(pos));
            advance();
        }
        return new Token(TokenType.VARIABLE, sb.toString(), startPos, startLine, startCol);
    }

    private Token lexFormat(int startPos, int startLine, int startCol) {
        advance(); // skip @
        var sb = new StringBuilder();
        while (pos < input.length() && isIdentPart(input.charAt(pos))) {
            sb.append(input.charAt(pos));
            advance();
        }
        return new Token(TokenType.FORMAT, sb.toString(), startPos, startLine, startCol);
    }

    private Token lexIdentOrKeyword(int startPos, int startLine, int startCol) {
        var sb = new StringBuilder();
        while (pos < input.length() && isIdentPart(input.charAt(pos))) {
            sb.append(input.charAt(pos));
            advance();
        }
        String word = sb.toString();
        TokenType keyword = KEYWORDS.get(word);
        if (keyword != null) {
            return new Token(keyword, word, startPos, startLine, startCol);
        }
        return new Token(TokenType.IDENT, word, startPos, startLine, startCol);
    }

    private void skipWhitespaceAndComments() {
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r') {
                advance();
            } else if (c == '\n') {
                advance();
                line++;
                column = 1;
            } else if (c == '#') {
                while (pos < input.length() && input.charAt(pos) != '\n') {
                    advance();
                }
            } else {
                break;
            }
        }
    }

    private void advance() {
        pos++;
        column++;
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9');
    }
}

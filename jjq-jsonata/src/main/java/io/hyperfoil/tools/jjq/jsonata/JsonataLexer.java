package io.hyperfoil.tools.jjq.jsonata;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokenizer for JSONata expressions.
 */
final class JsonataLexer {

    enum TokenType {
        // Literals
        NUMBER, STRING, TRUE, FALSE, NULL,
        // Identifiers and paths
        NAME,           // field name (unquoted identifier)
        BACKTICK_NAME,  // field name in backticks: `Over 18 ?`
        VARIABLE,       // $name
        // Operators
        DOT, COMMA, COLON, SEMICOLON,
        LBRACKET, RBRACKET, LPAREN, RPAREN, LBRACE, RBRACE,
        PLUS, MINUS, STAR, SLASH, PERCENT,
        AMPERSAND,      // & (string concatenation)
        EQ,             // =
        NEQ,            // !=
        LT, GT, LE, GE,
        QUESTION, COLON_PAIR, // ? and : for ternary
        DOTDOT,         // .. (range in array index)
        // Keywords
        AND, OR, IN, NOT,
        // Special
        EOF
    }

    record Token(TokenType type, String value, int pos) {
        @Override
        public String toString() {
            return type + (value != null ? "(" + value + ")" : "") + "@" + pos;
        }
    }

    static List<Token> tokenize(String input) {
        var tokens = new ArrayList<Token>();
        int i = 0;
        int len = input.length();

        while (i < len) {
            char c = input.charAt(i);

            // Skip whitespace
            if (Character.isWhitespace(c)) { i++; continue; }

            // Single-char tokens
            switch (c) {
                case '.' -> {
                    if (i + 1 < len && input.charAt(i + 1) == '.') {
                        tokens.add(new Token(TokenType.DOTDOT, "..", i));
                        i += 2;
                    } else {
                        tokens.add(new Token(TokenType.DOT, ".", i));
                        i++;
                    }
                    continue;
                }
                case ',' -> { tokens.add(new Token(TokenType.COMMA, ",", i)); i++; continue; }
                case ':' -> { tokens.add(new Token(TokenType.COLON, ":", i)); i++; continue; }
                case ';' -> { tokens.add(new Token(TokenType.SEMICOLON, ";", i)); i++; continue; }
                case '[' -> { tokens.add(new Token(TokenType.LBRACKET, "[", i)); i++; continue; }
                case ']' -> { tokens.add(new Token(TokenType.RBRACKET, "]", i)); i++; continue; }
                case '(' -> { tokens.add(new Token(TokenType.LPAREN, "(", i)); i++; continue; }
                case ')' -> { tokens.add(new Token(TokenType.RPAREN, ")", i)); i++; continue; }
                case '{' -> { tokens.add(new Token(TokenType.LBRACE, "{", i)); i++; continue; }
                case '}' -> { tokens.add(new Token(TokenType.RBRACE, "}", i)); i++; continue; }
                case '+' -> { tokens.add(new Token(TokenType.PLUS, "+", i)); i++; continue; }
                case '-' -> { tokens.add(new Token(TokenType.MINUS, "-", i)); i++; continue; }
                case '*' -> { tokens.add(new Token(TokenType.STAR, "*", i)); i++; continue; }
                case '/' -> { tokens.add(new Token(TokenType.SLASH, "/", i)); i++; continue; }
                case '%' -> { tokens.add(new Token(TokenType.PERCENT, "%", i)); i++; continue; }
                case '&' -> { tokens.add(new Token(TokenType.AMPERSAND, "&", i)); i++; continue; }
                case '?' -> { tokens.add(new Token(TokenType.QUESTION, "?", i)); i++; continue; }
            }

            // Multi-char operators
            if (c == '=' && i + 1 < len && input.charAt(i + 1) == '=') {
                // == is not standard JSONata (uses single =), but handle it gracefully
                tokens.add(new Token(TokenType.EQ, "==", i));
                i += 2; continue;
            }
            if (c == '=') {
                tokens.add(new Token(TokenType.EQ, "=", i));
                i++; continue;
            }
            if (c == '!' && i + 1 < len && input.charAt(i + 1) == '=') {
                tokens.add(new Token(TokenType.NEQ, "!=", i));
                i += 2; continue;
            }
            if (c == '<' && i + 1 < len && input.charAt(i + 1) == '=') {
                tokens.add(new Token(TokenType.LE, "<=", i));
                i += 2; continue;
            }
            if (c == '>' && i + 1 < len && input.charAt(i + 1) == '=') {
                tokens.add(new Token(TokenType.GE, ">=", i));
                i += 2; continue;
            }
            if (c == '<') { tokens.add(new Token(TokenType.LT, "<", i)); i++; continue; }
            if (c == '>') { tokens.add(new Token(TokenType.GT, ">", i)); i++; continue; }

            // Strings (single or double quoted)
            if (c == '\'' || c == '"') {
                int start = i;
                char quote = c;
                i++;
                var sb = new StringBuilder();
                while (i < len && input.charAt(i) != quote) {
                    if (input.charAt(i) == '\\' && i + 1 < len) {
                        i++;
                        sb.append(input.charAt(i));
                    } else {
                        sb.append(input.charAt(i));
                    }
                    i++;
                }
                if (i >= len) throw new JsonataException("Unterminated string at position " + start);
                i++; // skip closing quote
                tokens.add(new Token(TokenType.STRING, sb.toString(), start));
                continue;
            }

            // Backtick-quoted names
            if (c == '`') {
                int start = i;
                i++;
                int nameStart = i;
                while (i < len && input.charAt(i) != '`') i++;
                if (i >= len) throw new JsonataException("Unterminated backtick name at position " + start);
                String name = input.substring(nameStart, i);
                i++; // skip closing backtick
                tokens.add(new Token(TokenType.BACKTICK_NAME, name, start));
                continue;
            }

            // Numbers
            if (Character.isDigit(c) || (c == '-' && i + 1 < len && Character.isDigit(input.charAt(i + 1))
                    && (tokens.isEmpty() || isOperator(tokens.getLast().type)))) {
                int start = i;
                if (c == '-') i++;
                while (i < len && Character.isDigit(input.charAt(i))) i++;
                if (i < len && input.charAt(i) == '.') {
                    i++;
                    while (i < len && Character.isDigit(input.charAt(i))) i++;
                }
                if (i < len && (input.charAt(i) == 'e' || input.charAt(i) == 'E')) {
                    i++;
                    if (i < len && (input.charAt(i) == '+' || input.charAt(i) == '-')) i++;
                    while (i < len && Character.isDigit(input.charAt(i))) i++;
                }
                tokens.add(new Token(TokenType.NUMBER, input.substring(start, i), start));
                continue;
            }

            // Variables ($name) and function calls ($func(...))
            if (c == '$') {
                int start = i;
                i++;
                if (i < len && (Character.isLetter(input.charAt(i)) || input.charAt(i) == '_')) {
                    while (i < len && (Character.isLetterOrDigit(input.charAt(i)) || input.charAt(i) == '_'))
                        i++;
                    tokens.add(new Token(TokenType.VARIABLE, input.substring(start, i), start));
                } else {
                    // Bare $ — root reference
                    tokens.add(new Token(TokenType.VARIABLE, "$", start));
                }
                continue;
            }

            // Identifiers (field names) and keywords
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < len && (Character.isLetterOrDigit(input.charAt(i)) || input.charAt(i) == '_'))
                    i++;
                String word = input.substring(start, i);
                TokenType type = switch (word) {
                    case "and" -> TokenType.AND;
                    case "or" -> TokenType.OR;
                    case "in" -> TokenType.IN;
                    case "not" -> TokenType.NOT;
                    case "true" -> TokenType.TRUE;
                    case "false" -> TokenType.FALSE;
                    case "null" -> TokenType.NULL;
                    default -> TokenType.NAME;
                };
                tokens.add(new Token(type, word, start));
                continue;
            }

            throw new JsonataException("Unexpected character '" + c + "' at position " + i);
        }

        tokens.add(new Token(TokenType.EOF, null, len));
        return tokens;
    }

    private static boolean isOperator(TokenType type) {
        return switch (type) {
            case PLUS, MINUS, STAR, SLASH, PERCENT, AMPERSAND,
                 EQ, NEQ, LT, GT, LE, GE, QUESTION, COLON,
                 COMMA, LPAREN, LBRACKET, LBRACE -> true;
            default -> false;
        };
    }
}

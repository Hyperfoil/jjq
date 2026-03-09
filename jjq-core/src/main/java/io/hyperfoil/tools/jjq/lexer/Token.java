package io.hyperfoil.tools.jjq.lexer;

public record Token(TokenType type, String value, int position, int line, int column) {

    public Token(TokenType type, int position, int line, int column) {
        this(type, null, position, line, column);
    }

    @Override
    public String toString() {
        if (value != null) {
            return type + "(" + value + ") at " + line + ":" + column;
        }
        return type + " at " + line + ":" + column;
    }
}

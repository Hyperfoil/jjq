package io.hyperfoil.tools.jjq.lexer;

public class LexerException extends RuntimeException {
    private final int line;
    private final int column;

    public LexerException(String message, int line, int column) {
        super(message + " at line " + line + ", column " + column);
        this.line = line;
        this.column = column;
    }

    public int line() { return line; }
    public int column() { return column; }
}

package io.hyperfoil.tools.jjq.ast;

import io.hyperfoil.tools.jjq.lexer.Token;

public record SourceLocation(int line, int column, int position) {
    public static final SourceLocation UNKNOWN = new SourceLocation(0, 0, 0);

    public static SourceLocation from(Token token) {
        return new SourceLocation(token.line(), token.column(), token.position());
    }

    @Override
    public String toString() {
        return "line " + line + ", column " + column;
    }
}

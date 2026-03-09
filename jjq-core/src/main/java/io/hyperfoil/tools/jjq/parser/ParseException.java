package io.hyperfoil.tools.jjq.parser;

import io.hyperfoil.tools.jjq.lexer.Token;

public class ParseException extends RuntimeException {
    private final Token token;

    public ParseException(String message, Token token) {
        super(message + " at line " + token.line() + ", column " + token.column());
        this.token = token;
    }

    public Token token() { return token; }
}

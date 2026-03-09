package io.hyperfoil.tools.jjq.lexer;

public enum TokenType {
    // Literals
    NUMBER, STRING, TRUE, FALSE, NULL,

    // Identifiers and variables
    IDENT, VARIABLE, FORMAT,  // $name, @base64

    // Punctuation
    DOT, LBRACKET, RBRACKET, LBRACE, RBRACE, LPAREN, RPAREN,
    COMMA, COLON, SEMICOLON, PIPE, QUESTION,

    // Operators
    PLUS, MINUS, STAR, SLASH, PERCENT,
    EQ, NEQ, LT, GT, LE, GE,
    ASSIGN, UPDATE_ASSIGN, PLUS_ASSIGN, MINUS_ASSIGN,
    STAR_ASSIGN, SLASH_ASSIGN, PERCENT_ASSIGN, ALTERNATIVE_ASSIGN,
    ALTERNATIVE,  // //
    DOTDOT,       // ..

    // Keywords
    IF, THEN, ELIF, ELSE, END,
    AND, OR, NOT,
    DEF,
    AS,
    REDUCE, FOREACH,
    TRY, CATCH,
    IMPORT, INCLUDE,
    LABEL, BREAK,

    // Special
    EOF,
    RECURSE,  // .. (contextual)

    // String interpolation
    STRING_INTERP_START,  // "\(
    STRING_INTERP_END,    // )"  -- within string interpolation
}

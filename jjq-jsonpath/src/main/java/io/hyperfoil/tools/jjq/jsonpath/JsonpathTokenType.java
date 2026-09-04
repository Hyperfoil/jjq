package io.hyperfoil.tools.jjq.jsonpath;

/**
 * Token types for the PostgreSQL jsonpath grammar.
 *
 * <p>Context-sensitive keywords ({@code LAST}, {@code TO}) are emitted by the
 * lexer based on bracket depth — they are keywords inside {@code [...]} and
 * identifiers outside. This follows the jjq jq lexer pattern.</p>
 */
public enum JsonpathTokenType {
    // === Literals ===
    INTEGER,          // 0, 42
    DECIMAL,          // 3.14, .5, 1e10
    STRING,           // "hello"
    TRUE,             // true
    FALSE,            // false
    NULL,             // null

    // === References ===
    ROOT,             // $ (alone or before . or [)
    CURRENT,          // @
    NAMED_VARIABLE,   // $varname

    // === Navigation ===
    DOT,              // .
    STAR,             // * (wildcard)
    DOUBLESTAR,       // ** (recursive descent)
    LBRACKET,         // [
    RBRACKET,         // ]
    LPAREN,           // (
    RPAREN,           // )
    LBRACE,           // { (after ** only)
    RBRACE,           // } (after ** only)
    COMMA,            // ,
    QUESTION,         // ? (filter introduction)

    // === Arithmetic Operators ===
    PLUS,             // +
    MINUS,            // -
    SLASH,            // /
    PERCENT,          // %

    // === Comparison Operators ===
    EQ,               // ==
    NEQ,              // !=
    LTGT,             // <> (PostgreSQL alternative !=)
    LT,               // <
    GT,               // >
    LE,               // <=
    GE,               // >=

    // === Logical Operators ===
    AND,              // &&
    OR,               // ||
    NOT,              // ! (unary)

    // === Keywords ===
    KW_STRICT,        // strict
    KW_LAX,           // lax
    KW_LAST,          // last (only inside brackets)
    KW_TO,            // to (only inside brackets)
    KW_EXISTS,        // exists
    KW_IS,            // is
    KW_UNKNOWN,       // unknown
    KW_LIKE_REGEX,    // like_regex
    KW_FLAG,          // flag
    KW_STARTS,        // starts
    KW_WITH,          // with

    // === Identifier ===
    IDENT,            // field name or method name

    // === End ===
    EOF
}

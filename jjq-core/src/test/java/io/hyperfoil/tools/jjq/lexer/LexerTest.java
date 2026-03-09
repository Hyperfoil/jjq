package io.hyperfoil.tools.jjq.lexer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LexerTest {

    private List<Token> tokenize(String input) {
        return new Lexer(input).tokenize();
    }

    @Test
    void testIdentity() {
        var tokens = tokenize(".");
        assertEquals(TokenType.DOT, tokens.get(0).type());
        assertNull(tokens.get(0).value());
    }

    @Test
    void testFieldAccess() {
        var tokens = tokenize(".name");
        assertEquals(TokenType.DOT, tokens.get(0).type());
        assertEquals("name", tokens.get(0).value());
    }

    @Test
    void testPipe() {
        var tokens = tokenize(".foo | .bar");
        assertEquals(TokenType.DOT, tokens.get(0).type());
        assertEquals("foo", tokens.get(0).value());
        assertEquals(TokenType.PIPE, tokens.get(1).type());
        assertEquals(TokenType.DOT, tokens.get(2).type());
        assertEquals("bar", tokens.get(2).value());
    }

    @Test
    void testNumber() {
        var tokens = tokenize("42");
        assertEquals(TokenType.NUMBER, tokens.get(0).type());
        assertEquals("42", tokens.get(0).value());
    }

    @Test
    void testFloat() {
        var tokens = tokenize("3.14");
        assertEquals(TokenType.NUMBER, tokens.get(0).type());
        assertEquals("3.14", tokens.get(0).value());
    }

    @Test
    void testString() {
        var tokens = tokenize("\"hello world\"");
        assertEquals(TokenType.STRING, tokens.get(0).type());
        assertEquals("hello world", tokens.get(0).value());
    }

    @Test
    void testStringEscapes() {
        var tokens = tokenize("\"hello\\nworld\"");
        assertEquals("hello\nworld", tokens.get(0).value());
    }

    @Test
    void testKeywords() {
        var tokens = tokenize("if true then . else empty end");
        assertEquals(TokenType.IF, tokens.get(0).type());
        assertEquals(TokenType.TRUE, tokens.get(1).type());
        assertEquals(TokenType.THEN, tokens.get(2).type());
        assertEquals(TokenType.DOT, tokens.get(3).type());
        assertEquals(TokenType.ELSE, tokens.get(4).type());
        assertEquals(TokenType.IDENT, tokens.get(5).type());
        assertEquals("empty", tokens.get(5).value());
        assertEquals(TokenType.END, tokens.get(6).type());
    }

    @Test
    void testOperators() {
        var tokens = tokenize("+ - * / % == != < > <= >= //");
        assertEquals(TokenType.PLUS, tokens.get(0).type());
        assertEquals(TokenType.MINUS, tokens.get(1).type());
        assertEquals(TokenType.STAR, tokens.get(2).type());
        assertEquals(TokenType.SLASH, tokens.get(3).type());
        assertEquals(TokenType.PERCENT, tokens.get(4).type());
        assertEquals(TokenType.EQ, tokens.get(5).type());
        assertEquals(TokenType.NEQ, tokens.get(6).type());
        assertEquals(TokenType.LT, tokens.get(7).type());
        assertEquals(TokenType.GT, tokens.get(8).type());
        assertEquals(TokenType.LE, tokens.get(9).type());
        assertEquals(TokenType.GE, tokens.get(10).type());
        assertEquals(TokenType.ALTERNATIVE, tokens.get(11).type());
    }

    @Test
    void testAssignOperators() {
        var tokens = tokenize("|= += -= *= /= %= //=");
        assertEquals(TokenType.UPDATE_ASSIGN, tokens.get(0).type());
        assertEquals(TokenType.PLUS_ASSIGN, tokens.get(1).type());
        assertEquals(TokenType.MINUS_ASSIGN, tokens.get(2).type());
        assertEquals(TokenType.STAR_ASSIGN, tokens.get(3).type());
        assertEquals(TokenType.SLASH_ASSIGN, tokens.get(4).type());
        assertEquals(TokenType.PERCENT_ASSIGN, tokens.get(5).type());
        assertEquals(TokenType.ALTERNATIVE_ASSIGN, tokens.get(6).type());
    }

    @Test
    void testVariable() {
        var tokens = tokenize("$foo");
        assertEquals(TokenType.VARIABLE, tokens.get(0).type());
        assertEquals("foo", tokens.get(0).value());
    }

    @Test
    void testFormat() {
        var tokens = tokenize("@base64");
        assertEquals(TokenType.FORMAT, tokens.get(0).type());
        assertEquals("base64", tokens.get(0).value());
    }

    @Test
    void testDotDot() {
        var tokens = tokenize("..");
        assertEquals(TokenType.DOTDOT, tokens.get(0).type());
    }

    @Test
    void testBrackets() {
        var tokens = tokenize(".[] | .[0]");
        assertEquals(TokenType.DOT, tokens.get(0).type());
        assertEquals(TokenType.LBRACKET, tokens.get(1).type());
        assertEquals(TokenType.RBRACKET, tokens.get(2).type());
        assertEquals(TokenType.PIPE, tokens.get(3).type());
    }

    @Test
    void testComment() {
        var tokens = tokenize(". # this is a comment\n| .name");
        assertEquals(TokenType.DOT, tokens.get(0).type());
        assertEquals(TokenType.PIPE, tokens.get(1).type());
    }

    @Test
    void testArrayConstruction() {
        var tokens = tokenize("[.foo, .bar]");
        assertEquals(TokenType.LBRACKET, tokens.get(0).type());
        assertEquals(TokenType.DOT, tokens.get(1).type());
        assertEquals("foo", tokens.get(1).value());
        assertEquals(TokenType.COMMA, tokens.get(2).type());
    }

    @Test
    void testObjectConstruction() {
        var tokens = tokenize("{name: .foo}");
        assertEquals(TokenType.LBRACE, tokens.get(0).type());
        assertEquals(TokenType.IDENT, tokens.get(1).type());
        assertEquals("name", tokens.get(1).value());
        assertEquals(TokenType.COLON, tokens.get(2).type());
    }

    @Test
    void testDef() {
        var tokens = tokenize("def double: . * 2;");
        assertEquals(TokenType.DEF, tokens.get(0).type());
        assertEquals(TokenType.IDENT, tokens.get(1).type());
        assertEquals("double", tokens.get(1).value());
        assertEquals(TokenType.COLON, tokens.get(2).type());
    }

    @Test
    void testReduce() {
        var tokens = tokenize("reduce .[] as $x (0; . + $x)");
        assertEquals(TokenType.REDUCE, tokens.get(0).type());
        assertEquals(TokenType.DOT, tokens.get(1).type());
        assertEquals(TokenType.LBRACKET, tokens.get(2).type());
        assertEquals(TokenType.RBRACKET, tokens.get(3).type());
        assertEquals(TokenType.AS, tokens.get(4).type());
        assertEquals(TokenType.VARIABLE, tokens.get(5).type());
    }
}

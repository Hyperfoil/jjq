package io.hyperfoil.tools.jjq.parser;

import io.hyperfoil.tools.jjq.ast.JqExpr;
import io.hyperfoil.tools.jjq.ast.JqExpr.*;
import io.hyperfoil.tools.jjq.ast.SourceLocation;
import io.hyperfoil.tools.jjq.lexer.Lexer;
import io.hyperfoil.tools.jjq.lexer.Token;
import io.hyperfoil.tools.jjq.lexer.TokenType;
import io.hyperfoil.tools.jjq.value.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class Parser {
    private int destrCounter = 0;
    // Precedence levels (low to high)
    private static final int PREC_PIPE = 1;
    private static final int PREC_COMMA = 2;
    private static final int PREC_AS = 3;
    private static final int PREC_ALTERNATIVE = 4;
    private static final int PREC_OR = 5;
    private static final int PREC_AND = 6;
    private static final int PREC_COMPARISON = 7;
    private static final int PREC_ADD = 8;
    private static final int PREC_MUL = 9;

    private final List<Token> tokens;
    private int pos;

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
        this.pos = 0;
    }

    public static JqExpr parse(String expression) {
        var lexer = new Lexer(expression);
        var tokens = lexer.tokenize();
        var parser = new Parser(tokens);
        JqExpr expr = parser.parseExpr(0);
        parser.expect(TokenType.EOF);
        return expr;
    }

    public JqExpr parseProgram() {
        // A program may start with function defs: def f: body; rest
        JqExpr expr = parseExpr(0);
        expect(TokenType.EOF);
        return expr;
    }

    private JqExpr parseExpr(int minPrec) {
        JqExpr left = parsePrefixExpr();
        while (true) {
            Token t = peek();
            int prec = infixPrecedence(t);
            if (prec < minPrec) break;

            left = parseInfixExpr(left, prec, t);
        }
        // Postfix: ? (optional operator)
        while (peek().type() == TokenType.QUESTION) {
            advance();
            left = new OptionalExpr(left);
        }
        return left;
    }

    private int infixPrecedence(Token t) {
        return switch (t.type()) {
            case PIPE -> PREC_PIPE;
            case COMMA -> PREC_COMMA;
            case AS -> PREC_AS;
            case ALTERNATIVE -> PREC_ALTERNATIVE;
            case OR -> PREC_OR;
            case AND -> PREC_AND;
            case EQ, NEQ, LT, GT, LE, GE -> PREC_COMPARISON;
            case PLUS, MINUS -> PREC_ADD;
            case STAR, SLASH, PERCENT -> PREC_MUL;
            case ASSIGN -> PREC_PIPE; // = binds like pipe
            case UPDATE_ASSIGN -> PREC_PIPE;
            case PLUS_ASSIGN, MINUS_ASSIGN, STAR_ASSIGN, SLASH_ASSIGN,
                 PERCENT_ASSIGN, ALTERNATIVE_ASSIGN -> PREC_PIPE;
            default -> -1;
        };
    }

    private JqExpr parseInfixExpr(JqExpr left, int prec, Token t) {
        var loc = SourceLocation.from(t);
        return switch (t.type()) {
            case PIPE -> { advance(); yield new PipeExpr(left, parseExpr(PREC_PIPE), loc); }
            case COMMA -> { advance(); yield new CommaExpr(left, parseExpr(PREC_COMMA)); }
            case AS -> {
                advance();
                if (peek().type() == TokenType.VARIABLE) {
                    // Simple: expr as $var | body
                    advance();
                    String var = previous().value();
                    expect(TokenType.PIPE);
                    yield new VariableBindExpr(left, var, parseExpr(PREC_PIPE), loc);
                } else if (peek().type() == TokenType.LBRACKET || peek().type() == TokenType.LBRACE) {
                    yield parseDestructureAs(left, loc);
                } else {
                    throw new ParseException("Expected variable or destructuring pattern after 'as'", peek());
                }
            }

            case ALTERNATIVE -> { advance(); yield new AlternativeExpr(left, parseExpr(PREC_ALTERNATIVE + 1)); }
            case OR -> { advance(); yield new LogicalExpr(left, LogicalExpr.Op.OR, parseExpr(PREC_OR + 1)); }
            case AND -> { advance(); yield new LogicalExpr(left, LogicalExpr.Op.AND, parseExpr(PREC_AND + 1)); }
            case EQ -> { advance(); yield new ComparisonExpr(left, ComparisonExpr.Op.EQ, parseExpr(PREC_COMPARISON + 1), loc); }
            case NEQ -> { advance(); yield new ComparisonExpr(left, ComparisonExpr.Op.NEQ, parseExpr(PREC_COMPARISON + 1), loc); }
            case LT -> { advance(); yield new ComparisonExpr(left, ComparisonExpr.Op.LT, parseExpr(PREC_COMPARISON + 1), loc); }
            case GT -> { advance(); yield new ComparisonExpr(left, ComparisonExpr.Op.GT, parseExpr(PREC_COMPARISON + 1), loc); }
            case LE -> { advance(); yield new ComparisonExpr(left, ComparisonExpr.Op.LE, parseExpr(PREC_COMPARISON + 1), loc); }
            case GE -> { advance(); yield new ComparisonExpr(left, ComparisonExpr.Op.GE, parseExpr(PREC_COMPARISON + 1), loc); }
            case PLUS -> { advance(); yield new ArithmeticExpr(left, ArithmeticExpr.Op.ADD, parseExpr(PREC_ADD + 1), loc); }
            case MINUS -> { advance(); yield new ArithmeticExpr(left, ArithmeticExpr.Op.SUB, parseExpr(PREC_ADD + 1), loc); }
            case STAR -> { advance(); yield new ArithmeticExpr(left, ArithmeticExpr.Op.MUL, parseExpr(PREC_MUL + 1), loc); }
            case SLASH -> { advance(); yield new ArithmeticExpr(left, ArithmeticExpr.Op.DIV, parseExpr(PREC_MUL + 1), loc); }
            case PERCENT -> { advance(); yield new ArithmeticExpr(left, ArithmeticExpr.Op.MOD, parseExpr(PREC_MUL + 1), loc); }
            case ASSIGN -> { advance(); yield new AssignExpr(left, parseExpr(PREC_PIPE)); }
            case UPDATE_ASSIGN -> { advance(); yield new UpdateExpr(left, parseExpr(PREC_PIPE)); }
            case PLUS_ASSIGN -> { advance(); yield new AddAssignExpr(left, parseExpr(PREC_PIPE)); }
            case MINUS_ASSIGN -> { advance(); yield new SubAssignExpr(left, parseExpr(PREC_PIPE)); }
            case STAR_ASSIGN -> { advance(); yield new MulAssignExpr(left, parseExpr(PREC_PIPE)); }
            case SLASH_ASSIGN -> { advance(); yield new DivAssignExpr(left, parseExpr(PREC_PIPE)); }
            case PERCENT_ASSIGN -> { advance(); yield new ModAssignExpr(left, parseExpr(PREC_PIPE)); }
            case ALTERNATIVE_ASSIGN -> { advance(); yield new AlternativeAssignExpr(left, parseExpr(PREC_PIPE)); }
            default -> throw new ParseException("Unexpected token in infix position: " + t.type(), t);
        };
    }

    private JqExpr parsePrefixExpr() {
        Token t = peek();
        JqExpr expr = switch (t.type()) {
            case DOT -> parseDot();
            case DOTDOT -> { advance(); yield new RecurseExpr(); }
            case NUMBER -> { advance(); yield new LiteralExpr(parseNumber(t)); }
            case STRING -> { advance(); yield new LiteralExpr(JqString.of(t.value())); }
            case STRING_INTERP_START -> parseStringInterpolation();
            case TRUE -> { advance(); yield new LiteralExpr(JqBoolean.TRUE); }
            case FALSE -> { advance(); yield new LiteralExpr(JqBoolean.FALSE); }
            case NULL -> { advance(); yield new LiteralExpr(JqNull.NULL); }
            case LBRACKET -> parseArrayConstruct();
            case LBRACE -> parseObjectConstruct();
            case LPAREN -> { advance(); var e = parseExpr(0); expect(TokenType.RPAREN); yield e; }
            case MINUS -> { advance(); yield new NegateExpr(parsePrefixExpr()); }
            case IF -> parseIf();
            case TRY -> parseTryCatch();
            case REDUCE -> parseReduce();
            case FOREACH -> parseForeach();
            case DEF -> parseFuncDef();
            case LABEL -> parseLabel();
            case BREAK -> { advance(); expect(TokenType.VARIABLE); yield new BreakExpr(previous().value()); }
            case NOT -> { advance(); yield new FuncCallExpr("not", java.util.List.of(), SourceLocation.from(t)); }
            case VARIABLE -> { advance(); yield new VariableRefExpr(t.value(), SourceLocation.from(t)); }
            case IDENT -> parseFuncCallOrIdent();
            case FORMAT -> { advance(); yield parseFormatExpr(t.value()); }
            default -> throw new ParseException("Unexpected token: " + t.type(), t);
        };

        // Parse postfix operations: .field, [index], .[], [start:end]
        expr = parsePostfix(expr);
        return expr;
    }

    private JqExpr parsePostfix(JqExpr expr) {
        while (true) {
            Token t = peek();
            switch (t.type()) {
                case DOT -> {
                    if (t.value() != null) {
                        // .field access
                        advance();
                        var loc = SourceLocation.from(t);
                        expr = new PipeExpr(expr, new DotFieldExpr(t.value(), loc), loc);
                    } else {
                        break;
                    }
                }
                case LBRACKET -> {
                    var loc = SourceLocation.from(t);
                    advance();
                    if (peek().type() == TokenType.RBRACKET) {
                        // .[]
                        advance();
                        expr = new IterateExpr(expr, loc);
                    } else if (isSliceStart()) {
                        expr = parseSlice(expr);
                    } else {
                        JqExpr index = parseExpr(0);
                        if (peek().type() == TokenType.COLON) {
                            // [expr:expr]
                            advance();
                            JqExpr to = peek().type() == TokenType.RBRACKET ? null : parseExpr(0);
                            expect(TokenType.RBRACKET);
                            expr = new SliceExpr(expr, index, to);
                        } else {
                            expect(TokenType.RBRACKET);
                            expr = new IndexExpr(expr, index, loc);
                        }
                    }
                }
                default -> { return expr; }
            }
        }
    }

    private boolean isSliceStart() {
        return peek().type() == TokenType.COLON;
    }

    private JqExpr parseSlice(JqExpr expr) {
        // [start:end] where start was not parsed (starts with :)
        advance(); // skip :
        JqExpr to = peek().type() == TokenType.RBRACKET ? null : parseExpr(0);
        expect(TokenType.RBRACKET);
        return new SliceExpr(expr, null, to);
    }

    private JqExpr parseDot() {
        Token t = advance();
        var loc = SourceLocation.from(t);
        if (t.value() != null) {
            // .field
            return new DotFieldExpr(t.value(), loc);
        }
        // bare .
        if (peek().type() == TokenType.LBRACKET) {
            var bracketLoc = SourceLocation.from(peek());
            advance();
            if (peek().type() == TokenType.RBRACKET) {
                advance();
                return new IterateExpr(new IdentityExpr(), bracketLoc);
            }
            // .[expr] or .[start:end]
            if (peek().type() == TokenType.COLON) {
                advance();
                JqExpr to = peek().type() == TokenType.RBRACKET ? null : parseExpr(0);
                expect(TokenType.RBRACKET);
                return new SliceExpr(new IdentityExpr(), null, to);
            }
            JqExpr index = parseExpr(0);
            if (peek().type() == TokenType.COLON) {
                advance();
                JqExpr to = peek().type() == TokenType.RBRACKET ? null : parseExpr(0);
                expect(TokenType.RBRACKET);
                return new SliceExpr(new IdentityExpr(), index, to);
            }
            expect(TokenType.RBRACKET);
            return new IndexExpr(new IdentityExpr(), index, bracketLoc);
        }
        return new IdentityExpr();
    }

    private JqExpr parseArrayConstruct() {
        advance(); // skip [
        if (peek().type() == TokenType.RBRACKET) {
            advance();
            return new ArrayConstructExpr(null);
        }
        JqExpr body = parseExpr(0);
        expect(TokenType.RBRACKET);
        return new ArrayConstructExpr(body);
    }

    private JqExpr parseObjectConstruct() {
        advance(); // skip {
        var entries = new ArrayList<ObjectConstructExpr.ObjectEntry>();
        if (peek().type() != TokenType.RBRACE) {
            entries.add(parseObjectEntry());
            while (peek().type() == TokenType.COMMA) {
                advance();
                entries.add(parseObjectEntry());
            }
        }
        expect(TokenType.RBRACE);
        return new ObjectConstructExpr(entries);
    }

    private ObjectConstructExpr.ObjectEntry parseObjectEntry() {
        Token t = peek();

        // Shorthand: {name} equivalent to {name: .name}
        // Or {name: expr}
        // Or {(expr): expr}
        // Or {@format: expr}
        // Or {$var} shorthand for {($var | tostring): $var}

        if (t.type() == TokenType.LPAREN) {
            // Computed key: (expr): expr
            advance();
            JqExpr key = parseExpr(0);
            expect(TokenType.RPAREN);
            expect(TokenType.COLON);
            JqExpr value = parseExpr(PREC_COMMA + 1);
            return new ObjectConstructExpr.ObjectEntry(key, value);
        }

        if (t.type() == TokenType.VARIABLE) {
            advance();
            String varName = t.value();
            if (peek().type() == TokenType.COLON) {
                advance();
                JqExpr value = parseExpr(PREC_COMMA + 1);
                return new ObjectConstructExpr.ObjectEntry(new LiteralExpr(JqString.of(varName)), value);
            }
            // Shorthand: {$var} -> {($var|tostring): $var}
            return new ObjectConstructExpr.ObjectEntry(
                    new LiteralExpr(JqString.of(varName)),
                    new VariableRefExpr(varName, SourceLocation.from(t)));
        }

        if (t.type() == TokenType.IDENT || t.type() == TokenType.STRING) {
            advance();
            String keyName = t.value();
            if (peek().type() == TokenType.COLON) {
                advance();
                JqExpr value = parseExpr(PREC_COMMA + 1);
                return new ObjectConstructExpr.ObjectEntry(new LiteralExpr(JqString.of(keyName)), value);
            }
            // Shorthand: {name} -> {"name": .name}
            return new ObjectConstructExpr.ObjectEntry(
                    new LiteralExpr(JqString.of(keyName)),
                    new DotFieldExpr(keyName, SourceLocation.from(t)));
        }

        // Also handle keyword identifiers as keys
        if (isKeywordToken(t)) {
            advance();
            String keyName = t.value();
            if (peek().type() == TokenType.COLON) {
                advance();
                JqExpr value = parseExpr(PREC_COMMA + 1);
                return new ObjectConstructExpr.ObjectEntry(new LiteralExpr(JqString.of(keyName)), value);
            }
            return new ObjectConstructExpr.ObjectEntry(
                    new LiteralExpr(JqString.of(keyName)),
                    new DotFieldExpr(keyName, SourceLocation.from(t)));
        }

        if (t.type() == TokenType.FORMAT) {
            advance();
            String format = t.value();
            expect(TokenType.COLON);
            JqExpr value = parseExpr(PREC_COMMA + 1);
            return new ObjectConstructExpr.ObjectEntry(new LiteralExpr(JqString.of("@" + format)), value);
        }

        throw new ParseException("Expected object key", t);
    }

    private boolean isKeywordToken(Token t) {
        return switch (t.type()) {
            case IF, THEN, ELIF, ELSE, END, AND, OR, NOT, DEF, AS,
                 REDUCE, FOREACH, TRY, CATCH, IMPORT, INCLUDE,
                 LABEL, BREAK, TRUE, FALSE, NULL -> true;
            default -> false;
        };
    }

    private JqExpr parseIf() {
        advance(); // skip 'if'
        JqExpr condition = parseExpr(0);
        expect(TokenType.THEN);
        JqExpr thenBranch = parseExpr(0);

        var elifs = new ArrayList<IfExpr.ElifBranch>();
        while (peek().type() == TokenType.ELIF) {
            advance();
            JqExpr elifCond = parseExpr(0);
            expect(TokenType.THEN);
            JqExpr elifBody = parseExpr(0);
            elifs.add(new IfExpr.ElifBranch(elifCond, elifBody));
        }

        JqExpr elseBranch = null;
        if (peek().type() == TokenType.ELSE) {
            advance();
            elseBranch = parseExpr(0);
        }
        expect(TokenType.END);
        return new IfExpr(condition, thenBranch, elifs, elseBranch);
    }

    private JqExpr parseTryCatch() {
        advance(); // skip 'try'
        JqExpr tryExpr = parsePrefixExpr();
        JqExpr catchExpr = null;
        if (peek().type() == TokenType.CATCH) {
            advance();
            catchExpr = parsePrefixExpr();
        }
        return new TryCatchExpr(tryExpr, catchExpr);
    }

    private JqExpr parseReduce() {
        advance(); // skip 'reduce'
        JqExpr source = parsePrefixExpr();
        expect(TokenType.AS);
        var loc = SourceLocation.from(peek());
        DestructurePattern pat = parseBindingPattern();
        expect(TokenType.LPAREN);
        JqExpr init = parseExpr(0);
        expect(TokenType.SEMICOLON);
        JqExpr update = parseExpr(0);
        expect(TokenType.RPAREN);
        if (pat.isSimple()) {
            return new ReduceExpr(source, pat.variable(), init, update);
        }
        // Desugar: reduce src as [$a,$b] (init; update)
        // → reduce src as $_tmp (init; $_tmp as [$a,$b] | update)
        String tmpVar = "$_d" + (destrCounter++);
        JqExpr wrappedUpdate = pat.wrapBody(tmpVar, update, loc);
        return new ReduceExpr(source, tmpVar, init, wrappedUpdate);
    }

    private JqExpr parseForeach() {
        advance(); // skip 'foreach'
        JqExpr source = parsePrefixExpr();
        expect(TokenType.AS);
        var loc = SourceLocation.from(peek());
        DestructurePattern pat = parseBindingPattern();
        expect(TokenType.LPAREN);
        JqExpr init = parseExpr(0);
        expect(TokenType.SEMICOLON);
        JqExpr update = parseExpr(0);
        JqExpr extract = null;
        if (peek().type() == TokenType.SEMICOLON) {
            advance();
            extract = parseExpr(0);
        }
        expect(TokenType.RPAREN);
        if (pat.isSimple()) {
            return new ForeachExpr(source, pat.variable(), init, update, extract);
        }
        // Desugar: foreach src as [$a,$b] (init; update; extract)
        String tmpVar = "$_d" + (destrCounter++);
        JqExpr wrappedUpdate = pat.wrapBody(tmpVar, update, loc);
        JqExpr wrappedExtract = extract != null ? pat.wrapBody(tmpVar, extract, loc) : null;
        return new ForeachExpr(source, tmpVar, init, wrappedUpdate, wrappedExtract);
    }

    /** Represents a binding pattern: simple variable or array/object destructuring */
    private record DestructurePattern(String variable, List<String[]> bindings, boolean isArray) {
        boolean isSimple() { return bindings == null; }
        JqExpr wrapBody(String tmpVar, JqExpr body, SourceLocation loc) {
            JqExpr result = body;
            for (int i = bindings.size() - 1; i >= 0; i--) {
                String[] binding = bindings.get(i);
                JqExpr extractExpr;
                if (isArray) {
                    extractExpr = new PipeExpr(
                            new VariableRefExpr(tmpVar, loc),
                            new IndexExpr(new IdentityExpr(), new LiteralExpr(JqNumber.of(Integer.parseInt(binding[0]))), loc),
                            loc);
                } else {
                    extractExpr = new PipeExpr(
                            new VariableRefExpr(tmpVar, loc),
                            new DotFieldExpr(binding[0], loc),
                            loc);
                }
                result = new VariableBindExpr(extractExpr, binding[1], result, loc);
            }
            return result;
        }
    }

    /** Parse a binding pattern: $var, [$a, $b, ...], or {a: $a, b: $b, ...} */
    private DestructurePattern parseBindingPattern() {
        if (peek().type() == TokenType.VARIABLE) {
            advance();
            return new DestructurePattern(previous().value(), null, false);
        } else if (peek().type() == TokenType.LBRACKET) {
            advance(); // skip [
            var bindings = new ArrayList<String[]>();
            int idx = 0;
            while (peek().type() != TokenType.RBRACKET) {
                expect(TokenType.VARIABLE);
                bindings.add(new String[]{String.valueOf(idx++), previous().value()});
                if (peek().type() == TokenType.COMMA) advance();
            }
            expect(TokenType.RBRACKET);
            return new DestructurePattern(null, bindings, true);
        } else if (peek().type() == TokenType.LBRACE) {
            advance(); // skip {
            var bindings = new ArrayList<String[]>();
            while (peek().type() != TokenType.RBRACE) {
                if (peek().type() == TokenType.VARIABLE) {
                    advance();
                    String varName = previous().value();
                    String fieldName = varName.startsWith("$") ? varName.substring(1) : varName;
                    bindings.add(new String[]{fieldName, varName});
                } else {
                    String fieldName;
                    if (peek().type() == TokenType.IDENT) {
                        advance(); fieldName = previous().value();
                    } else if (peek().type() == TokenType.STRING) {
                        advance(); fieldName = previous().value();
                    } else {
                        throw new ParseException("Expected field name in object destructuring", peek());
                    }
                    expect(TokenType.COLON);
                    expect(TokenType.VARIABLE);
                    bindings.add(new String[]{fieldName, previous().value()});
                }
                if (peek().type() == TokenType.COMMA) advance();
            }
            expect(TokenType.RBRACE);
            return new DestructurePattern(null, bindings, false);
        } else {
            throw new ParseException("Expected variable or destructuring pattern", peek());
        }
    }

    private JqExpr parseFuncDef() {
        advance(); // skip 'def'
        expect(TokenType.IDENT);
        String name = previous().value();
        var params = new ArrayList<String>();

        if (peek().type() == TokenType.LPAREN) {
            advance();
            if (peek().type() != TokenType.RPAREN) {
                expect(TokenType.IDENT);
                params.add(previous().value());
                while (peek().type() == TokenType.SEMICOLON) {
                    advance();
                    expect(TokenType.IDENT);
                    params.add(previous().value());
                }
            }
            expect(TokenType.RPAREN);
        }
        expect(TokenType.COLON);
        JqExpr body = parseExpr(0);
        expect(TokenType.SEMICOLON);

        JqExpr next = parseExpr(0);
        return new FuncDefExpr(name, params, body, next);
    }

    private JqExpr parseLabel() {
        advance(); // skip 'label'
        expect(TokenType.VARIABLE);
        String label = previous().value();
        expect(TokenType.PIPE);
        JqExpr body = parseExpr(PREC_PIPE);
        return new LabelExpr(label, body);
    }

    /**
     * Parse destructuring: expr as [$a, $b, ...] | body  or  expr as {a: $a} | body
     * Desugars into nested VariableBindExpr
     */
    private JqExpr parseDestructureAs(JqExpr source, SourceLocation loc) {
        DestructurePattern pat = parseBindingPattern();
        expect(TokenType.PIPE);
        JqExpr body = parseExpr(PREC_PIPE);

        String tmpVar = "$_d" + (destrCounter++);
        JqExpr wrappedBody = pat.wrapBody(tmpVar, body, loc);
        return new VariableBindExpr(source, tmpVar, wrappedBody, loc);
    }

    private JqExpr parseFuncCallOrIdent() {
        Token t = advance();
        String name = t.value();
        var loc = SourceLocation.from(t);

        // Check for function call with args: name(expr; expr; ...)
        if (peek().type() == TokenType.LPAREN) {
            advance();
            var args = new ArrayList<JqExpr>();
            if (peek().type() != TokenType.RPAREN) {
                args.add(parseExpr(0));
                while (peek().type() == TokenType.SEMICOLON) {
                    advance();
                    args.add(parseExpr(0));
                }
            }
            expect(TokenType.RPAREN);
            return new FuncCallExpr(name, args, loc);
        }

        // Zero-arg function call (builtins)
        return new FuncCallExpr(name, List.of(), loc);
    }

    private JqExpr parseStringInterpolation() {
        var parts = new ArrayList<JqExpr>();
        Token t = advance(); // STRING_INTERP_START
        if (!t.value().isEmpty()) {
            parts.add(new LiteralExpr(JqString.of(t.value())));
        }
        // Now parse the interpolated expression
        parts.add(parseExpr(0));
        // After the expression, we expect ) which the lexer turns into STRING_INTERP_END or STRING_INTERP_START
        while (peek().type() == TokenType.STRING_INTERP_START) {
            t = advance();
            if (!t.value().isEmpty()) {
                parts.add(new LiteralExpr(JqString.of(t.value())));
            }
            parts.add(parseExpr(0));
        }
        if (peek().type() == TokenType.STRING_INTERP_END) {
            t = advance();
            if (!t.value().isEmpty()) {
                parts.add(new LiteralExpr(JqString.of(t.value())));
            }
        }
        return new StringInterpolationExpr(parts);
    }

    private JqExpr parseFormatExpr(String format) {
        // @base64, @uri, etc. Can be followed by a string for format strings
        if (peek().type() == TokenType.STRING) {
            // @base64 "string" -- format string (rare, but supported)
            Token strTok = advance();
            return new FormatExpr(format, new LiteralExpr(JqString.of(strTok.value())));
        }
        return new FormatExpr(format, null);
    }

    private JqNumber parseNumber(Token t) {
        String val = t.value();
        if (val.contains(".") || val.contains("e") || val.contains("E")) {
            return JqNumber.of(new BigDecimal(val));
        }
        try {
            return JqNumber.of(Long.parseLong(val));
        } catch (NumberFormatException e) {
            return JqNumber.of(new BigDecimal(val));
        }
    }

    private Token peek() {
        return tokens.get(pos);
    }

    private Token advance() {
        Token t = tokens.get(pos);
        pos++;
        return t;
    }

    private Token previous() {
        return tokens.get(pos - 1);
    }

    private void expect(TokenType type) {
        Token t = peek();
        if (t.type() != type) {
            throw new ParseException("Expected " + type + " but got " + t.type(), t);
        }
        advance();
    }
}

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
        outer:
        while (true) {
            // Handle postfix ? and continued postfix operations before checking infix
            while (peek().type() == TokenType.QUESTION
                    || (peek().type() == TokenType.DOT && peekIsPostfixDot())
                    || peek().type() == TokenType.LBRACKET) {
                if (peek().type() == TokenType.QUESTION) {
                    advance();
                    left = new OptionalExpr(left);
                } else {
                    left = parsePostfix(left);
                }
            }
            Token t = peek();
            int prec = infixPrecedence(t);
            if (prec < minPrec) break;

            left = parseInfixExpr(left, prec, t);
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
            case ASSIGN -> PREC_COMMA; // = binds like comma (tighter than pipe, same as comma)
            case UPDATE_ASSIGN -> PREC_COMMA;
            case PLUS_ASSIGN, MINUS_ASSIGN, STAR_ASSIGN, SLASH_ASSIGN,
                 PERCENT_ASSIGN, ALTERNATIVE_ASSIGN -> PREC_COMMA;
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
                    // Simple: expr as $var | body  OR  expr as $var ?// ...
                    advance();
                    String var = previous().value();
                    if (peek().type() == TokenType.QUESTION && peekAhead(1) != null
                            && peekAhead(1).type() == TokenType.ALTERNATIVE) {
                        // ?// alternative destructuring
                        var patterns = new ArrayList<DestructurePattern>();
                        patterns.add(new SimplePattern(var));
                        yield parseAlternativeDestructure(left, patterns, loc);
                    }
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
            case ASSIGN -> { advance(); yield new AssignExpr(left, parseExpr(PREC_COMMA + 1)); }
            case UPDATE_ASSIGN -> { advance(); yield new UpdateExpr(left, parseExpr(PREC_COMMA + 1)); }
            case PLUS_ASSIGN -> { advance(); yield new AddAssignExpr(left, parseExpr(PREC_COMMA + 1)); }
            case MINUS_ASSIGN -> { advance(); yield new SubAssignExpr(left, parseExpr(PREC_COMMA + 1)); }
            case STAR_ASSIGN -> { advance(); yield new MulAssignExpr(left, parseExpr(PREC_COMMA + 1)); }
            case SLASH_ASSIGN -> { advance(); yield new DivAssignExpr(left, parseExpr(PREC_COMMA + 1)); }
            case PERCENT_ASSIGN -> { advance(); yield new ModAssignExpr(left, parseExpr(PREC_COMMA + 1)); }
            case ALTERNATIVE_ASSIGN -> { advance(); yield new AlternativeAssignExpr(left, parseExpr(PREC_COMMA + 1)); }
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

        // Parse postfix operations: .field, [index], .[], [start:end], ?
        expr = parsePostfix(expr);
        while (peek().type() == TokenType.QUESTION) {
            advance();
            expr = new OptionalExpr(expr);
        }
        return expr;
    }

    private boolean peekIsPostfixDot() {
        Token t = peek();
        if (t.type() != TokenType.DOT) return false;
        if (t.value() != null) return true; // .field
        // bare dot — check next token
        if (pos + 1 < tokens.size()) {
            Token next = tokens.get(pos + 1);
            return next.type() == TokenType.STRING || next.type() == TokenType.LBRACKET;
        }
        return false;
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
                        // bare dot — check for ."string" field access or .[...] iteration/indexing
                        var loc = SourceLocation.from(t);
                        Token next = tokens.get(pos + 1);
                        if (next.type() == TokenType.STRING) {
                            advance(); // consume bare dot
                            advance(); // consume string
                            expr = new PipeExpr(expr, new DotFieldExpr(next.value(), loc), loc);
                        } else if (next.type() == TokenType.LBRACKET) {
                            advance(); // consume bare dot
                            // let the LBRACKET case handle the rest on next iteration
                        } else {
                            return expr;
                        }
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
        if (peek().type() == TokenType.STRING) {
            // ."string-field" — field access with quoted string key
            Token strToken = advance();
            return new DotFieldExpr(strToken.value(), loc);
        }
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
            JqExpr value = parseObjectValue();
            return new ObjectConstructExpr.ObjectEntry(key, value);
        }

        if (t.type() == TokenType.VARIABLE) {
            advance();
            String varName = t.value();
            if (peek().type() == TokenType.COLON) {
                advance();
                JqExpr value = parseObjectValue();
                return new ObjectConstructExpr.ObjectEntry(
                        new VariableRefExpr(varName, SourceLocation.from(t)), value);
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
                JqExpr value = parseObjectValue();
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
                JqExpr value = parseObjectValue();
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
            JqExpr value = parseObjectValue();
            return new ObjectConstructExpr.ObjectEntry(new LiteralExpr(JqString.of("@" + format)), value);
        }

        if (t.type() == TokenType.STRING_INTERP_START) {
            // Interpolated string as key: {"a$\(1+1)"} shorthand or {"a$\(1+1)": value}
            JqExpr keyExpr = parseStringInterpolation();
            if (peek().type() == TokenType.COLON) {
                advance();
                JqExpr value = parseObjectValue();
                return new ObjectConstructExpr.ObjectEntry(keyExpr, value);
            }
            // Shorthand: {"\(expr)"} → {("\(expr)"): .["\(expr)"]}
            // Desugar to computed key with dynamic field access
            JqExpr value = new IndexExpr(new IdentityExpr(), keyExpr, SourceLocation.UNKNOWN);
            return new ObjectConstructExpr.ObjectEntry(keyExpr, value);
        }

        throw new ParseException("Expected object key", t);
    }

    /**
     * Parse an object value expression: allows pipe but not comma or as.
     * In jq, {a: f | g, b: h} means entry a=(f|g), entry b=h.
     */
    private JqExpr parseObjectValue() {
        JqExpr expr = parseExpr(PREC_COMMA + 1);
        while (peek().type() == TokenType.PIPE) {
            advance();
            JqExpr right = parseExpr(PREC_COMMA + 1);
            expr = new PipeExpr(expr, right, SourceLocation.UNKNOWN);
        }
        return expr;
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
        JqExpr source = parseExpr(PREC_AS + 1);
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
        JqExpr wrappedUpdate = pat.wrapBody(tmpVar, update, loc, this);
        return new ReduceExpr(source, tmpVar, init, wrappedUpdate);
    }

    private JqExpr parseForeach() {
        advance(); // skip 'foreach'
        JqExpr source = parseExpr(PREC_AS + 1);
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
        JqExpr wrappedUpdate = pat.wrapBody(tmpVar, update, loc, this);
        JqExpr wrappedExtract = extract != null ? pat.wrapBody(tmpVar, extract, loc, this) : null;
        return new ForeachExpr(source, tmpVar, init, wrappedUpdate, wrappedExtract);
    }

    /** Represents a binding pattern: simple variable or array/object destructuring */
    private sealed interface DestructurePattern {
        boolean isSimple();
        String variable();
        JqExpr wrapBody(String tmpVar, JqExpr body, SourceLocation loc, Parser parser);
        default void collectVariables(java.util.Set<String> vars) {
            if (isSimple() && variable() != null) vars.add(variable());
        }
    }

    private record SimplePattern(String variable) implements DestructurePattern {
        @Override public boolean isSimple() { return true; }
        @Override public JqExpr wrapBody(String t, JqExpr body, SourceLocation loc, Parser parser) { return body; }
    }

    private record ArrayPattern(List<PatternBinding> elements) implements DestructurePattern {
        @Override public boolean isSimple() { return false; }
        @Override public String variable() { return null; }
        @Override public void collectVariables(java.util.Set<String> vars) {
            for (var elem : elements) elem.pattern().collectVariables(vars);
        }
        @Override public JqExpr wrapBody(String tmpVar, JqExpr body, SourceLocation loc, Parser parser) {
            JqExpr result = body;
            for (int i = elements.size() - 1; i >= 0; i--) {
                var elem = elements.get(i);
                JqExpr extractExpr = new PipeExpr(
                        new VariableRefExpr(tmpVar, loc),
                        new IndexExpr(new IdentityExpr(), new LiteralExpr(JqNumber.of(i)), loc),
                        loc);
                result = bindPattern(elem.pattern(), extractExpr, result, loc, parser);
            }
            return result;
        }
    }

    private record ObjectPattern(List<ObjectPatternBinding> fields) implements DestructurePattern {
        @Override public boolean isSimple() { return false; }
        @Override public String variable() { return null; }
        @Override public void collectVariables(java.util.Set<String> vars) {
            for (var field : fields) field.pattern().collectVariables(vars);
        }
        @Override public JqExpr wrapBody(String tmpVar, JqExpr body, SourceLocation loc, Parser parser) {
            JqExpr result = body;
            for (int i = fields.size() - 1; i >= 0; i--) {
                var field = fields.get(i);
                JqExpr extractExpr;
                if (field.computedKey() != null) {
                    // Computed key: $tmp | .[computed_expr]
                    extractExpr = new PipeExpr(
                            new VariableRefExpr(tmpVar, loc),
                            new IndexExpr(new IdentityExpr(), field.computedKey(), loc),
                            loc);
                } else {
                    extractExpr = new PipeExpr(
                            new VariableRefExpr(tmpVar, loc),
                            new DotFieldExpr(field.fieldName(), loc),
                            loc);
                }
                result = bindPattern(field.pattern(), extractExpr, result, loc, parser);
            }
            return result;
        }
    }

    /** Binds a variable AND applies a nested destructuring pattern */
    private record VarAndPattern(String variable, DestructurePattern nested) implements DestructurePattern {
        @Override public boolean isSimple() { return false; }
        @Override public void collectVariables(java.util.Set<String> vars) {
            if (variable != null) vars.add(variable);
            nested.collectVariables(vars);
        }
        @Override public JqExpr wrapBody(String tmpVar, JqExpr body, SourceLocation loc, Parser parser) {
            // First apply nested destructuring, then bind the variable
            JqExpr result = nested.wrapBody(tmpVar, body, loc, parser);
            // Also bind $var to the same value
            return new VariableBindExpr(new VariableRefExpr(tmpVar, loc), variable, result, loc);
        }
    }

    private record PatternBinding(DestructurePattern pattern) {}
    private record ObjectPatternBinding(String fieldName, JqExpr computedKey, DestructurePattern pattern) {
        ObjectPatternBinding(String fieldName, DestructurePattern pattern) {
            this(fieldName, null, pattern);
        }
    }

    private static JqExpr bindPattern(DestructurePattern pat, JqExpr extractExpr,
                                       JqExpr body, SourceLocation loc, Parser parser) {
        if (pat.isSimple()) {
            return new VariableBindExpr(extractExpr, pat.variable(), body, loc);
        }
        // Nested pattern: bind to temp var, then destructure
        String tmpVar = "$_d" + (parser.destrCounter++);
        JqExpr wrappedBody = pat.wrapBody(tmpVar, body, loc, parser);
        return new VariableBindExpr(extractExpr, tmpVar, wrappedBody, loc);
    }

    /** Parse a binding pattern: $var, [$a, $b, ...], or {a: $a, b: $b, ...} (recursive) */
    private DestructurePattern parseBindingPattern() {
        if (peek().type() == TokenType.VARIABLE) {
            advance();
            return new SimplePattern(previous().value());
        } else if (peek().type() == TokenType.LBRACKET) {
            advance(); // skip [
            var elements = new ArrayList<PatternBinding>();
            while (peek().type() != TokenType.RBRACKET) {
                elements.add(new PatternBinding(parseBindingPattern()));
                if (peek().type() == TokenType.COMMA) advance();
            }
            expect(TokenType.RBRACKET);
            return new ArrayPattern(elements);
        } else if (peek().type() == TokenType.LBRACE) {
            advance(); // skip {
            var fields = new ArrayList<ObjectPatternBinding>();
            while (peek().type() != TokenType.RBRACE) {
                if (peek().type() == TokenType.VARIABLE) {
                    advance();
                    String varName = previous().value();
                    String fieldName = varName.startsWith("$") ? varName.substring(1) : varName;
                    if (peek().type() == TokenType.COLON) {
                        // $var: pattern — field "var", value bound to $var AND destructured by nested pattern
                        advance();
                        DestructurePattern nested = parseBindingPattern();
                        fields.add(new ObjectPatternBinding(fieldName, new VarAndPattern(varName, nested)));
                    } else {
                        // Shorthand: {$a} means {a: $a}
                        fields.add(new ObjectPatternBinding(fieldName, new SimplePattern(varName)));
                    }
                } else if (peek().type() == TokenType.LPAREN) {
                    // Computed key: (expr): pattern
                    advance(); // skip (
                    JqExpr keyExpr = parseExpr(0);
                    expect(TokenType.RPAREN);
                    expect(TokenType.COLON);
                    DestructurePattern nested = parseBindingPattern();
                    fields.add(new ObjectPatternBinding(null, keyExpr, nested));
                } else {
                    String fieldName;
                    if (peek().type() == TokenType.IDENT) {
                        advance(); fieldName = previous().value();
                    } else if (peek().type() == TokenType.STRING) {
                        advance(); fieldName = previous().value();
                    } else if (isKeywordToken(peek())) {
                        advance(); fieldName = previous().value();
                    } else {
                        throw new ParseException("Expected field name in object destructuring", peek());
                    }
                    expect(TokenType.COLON);
                    DestructurePattern nested = parseBindingPattern();
                    fields.add(new ObjectPatternBinding(fieldName, nested));
                }
                if (peek().type() == TokenType.COMMA) advance();
            }
            expect(TokenType.RBRACE);
            return new ObjectPattern(fields);
        } else {
            throw new ParseException("Expected variable or destructuring pattern", peek());
        }
    }

    private JqExpr parseFuncDef() {
        advance(); // skip 'def'
        expect(TokenType.IDENT);
        String name = previous().value();
        var params = new ArrayList<String>();

        boolean hasVarParams = false;
        if (peek().type() == TokenType.LPAREN) {
            advance();
            if (peek().type() != TokenType.RPAREN) {
                if (peek().type() == TokenType.VARIABLE) {
                    hasVarParams = true;
                    advance();
                    params.add(previous().value());
                } else {
                    expect(TokenType.IDENT);
                    params.add(previous().value());
                }
                while (peek().type() == TokenType.SEMICOLON) {
                    advance();
                    if (hasVarParams) {
                        expect(TokenType.VARIABLE);
                    } else {
                        expect(TokenType.IDENT);
                    }
                    params.add(previous().value());
                }
            }
            expect(TokenType.RPAREN);
        }
        expect(TokenType.COLON);
        JqExpr body;
        if (hasVarParams) {
            // def f($a; $b): body  =>  def f(a; b): a as $a | b as $b | body
            JqExpr rawBody = parseExpr(0);
            // Wrap body with variable bindings for each $param
            JqExpr wrappedBody = rawBody;
            for (int i = params.size() - 1; i >= 0; i--) {
                String param = params.get(i);
                // The function arg reference uses the param name without $
                String argName = param.startsWith("$") ? param.substring(1) : param;
                wrappedBody = new VariableBindExpr(
                        new FuncCallExpr(argName, List.of(), SourceLocation.UNKNOWN),
                        param, wrappedBody, SourceLocation.UNKNOWN);
                params.set(i, argName);
            }
            body = wrappedBody;
        } else {
            body = parseExpr(0);
        }
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

        // Check for ?// alternative destructuring
        if (peek().type() == TokenType.QUESTION && peekAhead(1) != null
                && peekAhead(1).type() == TokenType.ALTERNATIVE) {
            var patterns = new ArrayList<DestructurePattern>();
            patterns.add(pat);
            return parseAlternativeDestructure(source, patterns, loc);
        }

        expect(TokenType.PIPE);
        JqExpr body = parseExpr(PREC_PIPE);

        String tmpVar = "$_d" + (destrCounter++);
        JqExpr wrappedBody = pat.wrapBody(tmpVar, body, loc, this);
        return new VariableBindExpr(source, tmpVar, wrappedBody, loc);
    }

    /**
     * Parse and desugar ?// alternative destructuring.
     * expr as pat1 ?// pat2 ?// pat3 | body
     * Desugars to type-checking if/elif/else chain.
     */
    private JqExpr parseAlternativeDestructure(JqExpr source, List<DestructurePattern> patterns,
                                                SourceLocation loc) {
        // Consume ?// and additional patterns
        while (peek().type() == TokenType.QUESTION && peekAhead(1) != null
                && peekAhead(1).type() == TokenType.ALTERNATIVE) {
            advance(); // skip ?
            advance(); // skip //
            patterns.add(parseBindingPattern());
        }
        expect(TokenType.PIPE);
        JqExpr body = parseExpr(PREC_PIPE);

        // Collect all variables from all patterns
        var allVars = new java.util.LinkedHashSet<String>();
        for (DestructurePattern p : patterns) p.collectVariables(allVars);

        // Desugar: source as $tmp |
        //   null as $v1 | null as $v2 | ... |  (initialize all vars to null)
        //   try ($tmp as pat1 | body) catch
        //   try ($tmp as pat2 | body) catch
        //   ($tmp as patN | body)
        String tmpVar = "$_d" + (destrCounter++);

        // Build from last to first
        JqExpr result = null;
        for (int i = patterns.size() - 1; i >= 0; i--) {
            DestructurePattern pat = patterns.get(i);
            JqExpr branch;
            if (pat.isSimple()) {
                branch = new VariableBindExpr(
                        new VariableRefExpr(tmpVar, loc), pat.variable(), body, loc);
            } else {
                String innerTmp = "$_d" + (destrCounter++);
                JqExpr patBody = pat.wrapBody(innerTmp, body, loc, this);
                branch = new VariableBindExpr(
                        new VariableRefExpr(tmpVar, loc), innerTmp, patBody, loc);
            }
            if (result == null) {
                // Last pattern: no try/catch, errors propagate
                result = branch;
            } else {
                // Wrap in try/catch: on failure, fall through to next pattern
                result = new TryCatchExpr(branch, result);
            }
        }

        // Wrap with null initializations for all pattern variables
        // so unmatched variables default to null instead of being undefined
        JqExpr initResult = result;
        for (String var : allVars) {
            initResult = new VariableBindExpr(new LiteralExpr(JqNull.NULL), var, initResult, loc);
        }

        return new VariableBindExpr(source, tmpVar, initResult, loc);
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
            Token strTok = advance();
            return new FormatExpr(format, new LiteralExpr(JqString.of(strTok.value())));
        }
        if (peek().type() == TokenType.STRING_INTERP_START) {
            // @html "\(expr)..." -- format string with interpolation
            JqExpr interpStr = parseStringInterpolation();
            return new FormatExpr(format, interpStr);
        }
        return new FormatExpr(format, null);
    }

    private JqNumber parseNumber(Token t) {
        String val = t.value();
        if (val.contains(".") || val.contains("e") || val.contains("E")) {
            try {
                return JqNumber.of(new BigDecimal(val));
            } catch (NumberFormatException | ArithmeticException e) {
                return JqNumber.of(Double.parseDouble(val));
            }
        }
        try {
            return JqNumber.of(Long.parseLong(val));
        } catch (NumberFormatException e) {
            try {
                return JqNumber.of(new BigDecimal(val));
            } catch (NumberFormatException | ArithmeticException e2) {
                return JqNumber.of(Double.parseDouble(val));
            }
        }
    }

    private Token peek() {
        return tokens.get(pos);
    }

    private Token peekAhead(int offset) {
        int idx = pos + offset;
        return idx < tokens.size() ? tokens.get(idx) : null;
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

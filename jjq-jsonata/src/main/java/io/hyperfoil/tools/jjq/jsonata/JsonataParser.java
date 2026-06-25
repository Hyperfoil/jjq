package io.hyperfoil.tools.jjq.jsonata;

import io.hyperfoil.tools.jjq.jsonata.JsonataLexer.Token;
import io.hyperfoil.tools.jjq.jsonata.JsonataLexer.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for JSONata expressions.
 * Produces an AST ({@link Node}) that can be translated to jq.
 */
final class JsonataParser {

    // ========================================================================
    //  AST Node types
    // ========================================================================

    sealed interface Node {}

    /** Field name: `Surname`, `Address` */
    record FieldNode(String name) implements Node {}

    /** Backtick-quoted field: `Over 18 ?` */
    record QuotedFieldNode(String name) implements Node {}

    /** Path: `Address.City` → PathNode([Address, City]) */
    record PathNode(List<Node> steps) implements Node {}

    /** Array index: `Phone[0]` → IndexNode(Phone, 0) */
    record IndexNode(Node base, Node index) implements Node {}

    /** Predicate filter: `Phone[type='mobile']` → PredicateNode(Phone, cond) */
    record PredicateNode(Node base, Node predicate) implements Node {}

    /** Number literal */
    record NumberNode(String value) implements Node {}

    /** String literal */
    record StringNode(String value) implements Node {}

    /** Boolean literal */
    record BooleanNode(boolean value) implements Node {}

    /** Null literal */
    record NullNode() implements Node {}

    /** Variable reference: `$`, `$x` */
    record VariableNode(String name) implements Node {}

    /** Function call: `$sum(...)`, `$max(...)` */
    record FunctionCallNode(String name, List<Node> args) implements Node {}

    /** Binary operator: `a + b`, `a = b`, `a & b` */
    record BinaryNode(String op, Node left, Node right) implements Node {}

    /** Unary operator: `-x`, `not x` */
    record UnaryNode(String op, Node operand) implements Node {}

    /** Ternary: `a ? b : c` */
    record TernaryNode(Node condition, Node then, Node otherwise) implements Node {}

    /** Array construction: `[1, 2, 3]` */
    record ArrayNode(List<Node> elements) implements Node {}

    /** Object construction: `{"key": value, ...}` */
    record ObjectNode(List<Entry> entries) implements Node {}
    record Entry(Node key, Node value) {}

    /** Range: `[0..4]` */
    record RangeNode(Node start, Node end) implements Node {}

    /** Parenthesized group: `(expr)` */
    record GroupNode(Node expr) implements Node {}

    /** Map operator: `.(expr)` — evaluate expr for each element */
    record MapNode(Node expr) implements Node {}

    /** Wildcard: `*` in path context */
    record WildcardNode() implements Node {}

    /** Block expression: `(expr1; expr2; expr3)` — evaluates sequentially, returns last */
    record BlockNode(List<Node> statements) implements Node {}

    /** Variable assignment: `$var := expr` */
    record AssignNode(String varName, Node value) implements Node {}

    /** Lambda expression: `function($a, $b) { body }` */
    record LambdaNode(List<String> params, Node body) implements Node {}

    /** Descendant: `**` */
    record DescendantNode() implements Node {}

    // ========================================================================
    //  Parser state
    // ========================================================================

    private final List<Token> tokens;
    private int pos;

    JsonataParser(List<Token> tokens) {
        this.tokens = tokens;
        this.pos = 0;
    }

    static Node parse(String input) {
        var tokens = JsonataLexer.tokenize(input);
        var parser = new JsonataParser(tokens);
        Node result = parser.parseExpression(0);
        if (parser.peek().type() != TokenType.EOF) {
            throw new JsonataException("Unexpected token: " + parser.peek() + " at position " + parser.peek().pos());
        }
        return result;
    }

    // ========================================================================
    //  Pratt parser — operator precedence
    // ========================================================================

    private Node parseExpression(int minPrec) {
        Node left = parsePrimary();
        left = parsePostfix(left);

        while (true) {
            Token t = peek();
            int prec = infixPrecedence(t.type());
            if (prec < minPrec) break;

            if (t.type() == TokenType.QUESTION) {
                // Ternary: a ? b : c
                advance(); // skip ?
                Node then = parseExpression(0);
                expect(TokenType.COLON, "Expected ':' in ternary expression");
                Node otherwise = parseExpression(0);
                left = new TernaryNode(left, then, otherwise);
                left = parsePostfix(left);
                continue;
            }

            String op = infixOp(t.type());
            if (op == null) break;

            advance();
            Node right = parseExpression(prec + 1);
            left = new BinaryNode(op, left, right);
            left = parsePostfix(left);
        }

        return left;
    }

    private Node parsePrimary() {
        Token t = peek();
        return switch (t.type()) {
            case NUMBER -> { advance(); yield new NumberNode(t.value()); }
            case STRING -> { advance(); yield new StringNode(t.value()); }
            case TRUE -> { advance(); yield new BooleanNode(true); }
            case FALSE -> { advance(); yield new BooleanNode(false); }
            case NULL -> { advance(); yield new NullNode(); }

            case NAME -> {
                advance();
                if ("function".equals(t.value()) && peek().type() == TokenType.LPAREN) {
                    yield parseLambda();
                }
                yield new FieldNode(t.value());
            }

            // and/or as field names when in primary position (not infix)
            case AND, OR -> { advance(); yield new FieldNode(t.value()); }

            case BACKTICK_NAME -> { advance(); yield new QuotedFieldNode(t.value()); }

            case VARIABLE -> {
                advance();
                if (t.value().equals("$")) {
                    yield new VariableNode("$");
                }
                // Check for assignment: $var := expr
                if (peek().type() == TokenType.ASSIGN) {
                    advance(); // skip :=
                    Node value = parseExpression(0);
                    yield new AssignNode(t.value(), value);
                }
                // Check if this is a function call: $func(...)
                if (peek().type() == TokenType.LPAREN) {
                    yield parseFunctionCall(t.value());
                }
                yield new VariableNode(t.value());
            }

            case MINUS -> {
                advance();
                Node operand = parsePrimary();
                operand = parsePostfix(operand);
                yield new UnaryNode("-", operand);
            }

            case NOT -> {
                advance();
                Node operand = parseExpression(70); // high precedence
                yield new UnaryNode("not", operand);
            }

            case LPAREN -> {
                advance();
                Node expr = parseExpression(0);
                if (peek().type() == TokenType.SEMICOLON) {
                    // Block expression: (expr1; expr2; expr3)
                    var stmts = new ArrayList<Node>();
                    stmts.add(expr);
                    while (peek().type() == TokenType.SEMICOLON) {
                        advance(); // skip ;
                        if (peek().type() == TokenType.RPAREN) break; // trailing semicolon
                        stmts.add(parseExpression(0));
                    }
                    expect(TokenType.RPAREN, "Expected ')'");
                    yield new BlockNode(stmts);
                }
                expect(TokenType.RPAREN, "Expected ')'");
                yield new GroupNode(expr);
            }

            case LBRACKET -> parseArrayConstruction();

            case LBRACE -> parseObjectConstruction();

            case STAR -> {
                advance();
                if (peek().type() == TokenType.STAR) {
                    advance();
                    yield new DescendantNode();
                }
                yield new WildcardNode();
            }

            default -> throw new JsonataException(
                    "Unexpected token " + t.type() + " at position " + t.pos());
        };
    }

    private Node parsePostfix(Node node) {
        while (true) {
            Token t = peek();
            if (t.type() == TokenType.DOT) {
                advance();
                Token field = peek();
                if (field.type() == TokenType.NAME || field.type() == TokenType.AND || field.type() == TokenType.OR) {
                    advance();
                    node = new PathNode(flattenPath(node, new FieldNode(field.value())));
                } else if (field.type() == TokenType.BACKTICK_NAME) {
                    advance();
                    node = new PathNode(flattenPath(node, new QuotedFieldNode(field.value())));
                } else if (field.type() == TokenType.STRING) {
                    // Product."Product Name" — quoted field access with double quotes
                    advance();
                    node = new PathNode(flattenPath(node, new QuotedFieldNode(field.value())));
                } else if (field.type() == TokenType.LPAREN) {
                    // .(expr) — map operator: evaluate expr in context of each element
                    advance();
                    Node mapExpr = parseExpression(0);
                    expect(TokenType.RPAREN, "Expected ')' in map expression");
                    node = new PathNode(flattenPath(node, new MapNode(mapExpr)));
                } else if (field.type() == TokenType.STAR) {
                    advance();
                    if (peek().type() == TokenType.STAR) {
                        advance();
                        node = new PathNode(flattenPath(node, new DescendantNode()));
                    } else {
                        node = new PathNode(flattenPath(node, new WildcardNode()));
                    }
                } else if (field.type() == TokenType.VARIABLE) {
                    advance();
                    if (peek().type() == TokenType.LPAREN) {
                        Node fn = parseFunctionCall(field.value());
                        node = new PathNode(flattenPath(node, fn));
                    } else {
                        node = new PathNode(flattenPath(node, new VariableNode(field.value())));
                    }
                } else {
                    throw new JsonataException("Expected field name after '.' at position " + field.pos());
                }
            } else if (t.type() == TokenType.LBRACKET) {
                advance();
                // Check for range [start..end]
                Node indexExpr = parseExpression(0);
                if (peek().type() == TokenType.DOTDOT) {
                    advance();
                    Node endExpr = parseExpression(0);
                    expect(TokenType.RBRACKET, "Expected ']'");
                    node = new IndexNode(node, new RangeNode(indexExpr, endExpr));
                } else {
                    expect(TokenType.RBRACKET, "Expected ']'");
                    // Determine if this is an index (numeric) or predicate (comparison)
                    if (isPredicate(indexExpr)) {
                        node = new PredicateNode(node, indexExpr);
                    } else {
                        node = new IndexNode(node, indexExpr);
                    }
                }
            } else {
                break;
            }
        }
        return node;
    }

    private List<Node> flattenPath(Node left, Node right) {
        var steps = new ArrayList<Node>();
        if (left instanceof PathNode p) {
            steps.addAll(p.steps());
        } else {
            steps.add(left);
        }
        steps.add(right);
        return steps;
    }

    private boolean isPredicate(Node node) {
        if (node instanceof BinaryNode b) {
            return b.op().equals("=") || b.op().equals("!=") ||
                   b.op().equals("<") || b.op().equals(">") ||
                   b.op().equals("<=") || b.op().equals(">=") ||
                   b.op().equals("and") || b.op().equals("or") ||
                   b.op().equals("in") || b.op().equals("%");
        }
        // Unary minus on a number is NOT a predicate (it's a negative index)
        if (node instanceof UnaryNode u && "-".equals(u.op()) && u.operand() instanceof NumberNode) {
            return false;
        }
        // Expressions involving $ in brackets are predicates
        if (containsVariable(node)) return true;
        return false;
    }

    private boolean containsVariable(Node node) {
        return switch (node) {
            case VariableNode _ -> true;
            case BinaryNode b -> containsVariable(b.left()) || containsVariable(b.right());
            case UnaryNode u -> containsVariable(u.operand());
            case GroupNode g -> containsVariable(g.expr());
            default -> false;
        };
    }

    private Node parseLambda() {
        expect(TokenType.LPAREN, "Expected '(' after 'function'");
        var params = new ArrayList<String>();
        if (peek().type() != TokenType.RPAREN) {
            Token p = peek();
            if (p.type() == TokenType.VARIABLE) {
                advance();
                params.add(p.value());
            }
            while (peek().type() == TokenType.COMMA) {
                advance();
                Token p2 = peek();
                if (p2.type() == TokenType.VARIABLE) {
                    advance();
                    params.add(p2.value());
                }
            }
        }
        expect(TokenType.RPAREN, "Expected ')' after lambda parameters");
        expect(TokenType.LBRACE, "Expected '{' for lambda body");
        Node body = parseExpression(0);
        expect(TokenType.RBRACE, "Expected '}' after lambda body");
        return new LambdaNode(params, body);
    }

    private Node parseFunctionCall(String name) {
        expect(TokenType.LPAREN, "Expected '(' after function name");
        var args = new ArrayList<Node>();
        if (peek().type() != TokenType.RPAREN) {
            args.add(parseExpression(0));
            while (peek().type() == TokenType.COMMA) {
                advance();
                args.add(parseExpression(0));
            }
        }
        expect(TokenType.RPAREN, "Expected ')'");
        return new FunctionCallNode(name, args);
    }

    private Node parseArrayConstruction() {
        expect(TokenType.LBRACKET, "Expected '['");
        var elements = new ArrayList<Node>();
        if (peek().type() != TokenType.RBRACKET) {
            Node first = parseExpression(0);
            // Check for range [start..end]
            if (peek().type() == TokenType.DOTDOT) {
                advance();
                Node end = parseExpression(0);
                expect(TokenType.RBRACKET, "Expected ']'");
                return new RangeNode(first, end);
            }
            elements.add(first);
            while (peek().type() == TokenType.COMMA) {
                advance();
                elements.add(parseExpression(0));
            }
        }
        expect(TokenType.RBRACKET, "Expected ']'");
        return new ArrayNode(elements);
    }

    private Node parseObjectConstruction() {
        expect(TokenType.LBRACE, "Expected '{'");
        var entries = new ArrayList<Entry>();
        if (peek().type() != TokenType.RBRACE) {
            entries.add(parseObjectEntry());
            while (peek().type() == TokenType.COMMA) {
                advance();
                entries.add(parseObjectEntry());
            }
        }
        expect(TokenType.RBRACE, "Expected '}'");
        return new ObjectNode(entries);
    }

    private Entry parseObjectEntry() {
        Node key;
        Token t = peek();
        if (t.type() == TokenType.STRING) {
            advance();
            key = new StringNode(t.value());
        } else if (t.type() == TokenType.NAME) {
            advance();
            key = new StringNode(t.value());
        } else {
            key = parseExpression(0);
        }
        expect(TokenType.COLON, "Expected ':' in object entry");
        Node value = parseExpression(0);
        return new Entry(key, value);
    }

    // ========================================================================
    //  Operator precedence
    // ========================================================================

    private int infixPrecedence(TokenType type) {
        return switch (type) {
            case OR -> 10;
            case AND -> 20;
            case EQ, NEQ -> 30;
            case LT, GT, LE, GE, IN -> 40;
            case AMPERSAND -> 45; // string concat
            case PLUS, MINUS -> 50;
            case STAR, SLASH, PERCENT -> 60;
            case QUESTION -> 5; // ternary
            default -> -1;
        };
    }

    private String infixOp(TokenType type) {
        return switch (type) {
            case PLUS -> "+";
            case MINUS -> "-";
            case STAR -> "*";
            case SLASH -> "/";
            case PERCENT -> "%";
            case AMPERSAND -> "&";
            case EQ -> "=";
            case NEQ -> "!=";
            case LT -> "<";
            case GT -> ">";
            case LE -> "<=";
            case GE -> ">=";
            case AND -> "and";
            case OR -> "or";
            case IN -> "in";
            default -> null;
        };
    }

    // ========================================================================
    //  Token helpers
    // ========================================================================

    private Token peek() { return tokens.get(pos); }

    private Token advance() { return tokens.get(pos++); }

    private void expect(TokenType type, String message) {
        Token t = peek();
        if (t.type() != type) {
            throw new JsonataException(message + " at position " + t.pos() + ", got " + t.type());
        }
        advance();
    }
}

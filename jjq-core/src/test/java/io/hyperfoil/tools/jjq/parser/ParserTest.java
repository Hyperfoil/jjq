package io.hyperfoil.tools.jjq.parser;

import io.hyperfoil.tools.jjq.ast.JqExpr;
import io.hyperfoil.tools.jjq.ast.JqExpr.*;
import io.hyperfoil.tools.jjq.value.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ParserTest {

    private JqExpr parse(String expr) {
        return Parser.parse(expr);
    }

    @Test
    void testIdentity() {
        assertInstanceOf(IdentityExpr.class, parse("."));
    }

    @Test
    void testFieldAccess() {
        var expr = parse(".name");
        assertInstanceOf(DotFieldExpr.class, expr);
        assertEquals("name", ((DotFieldExpr) expr).field());
    }

    @Test
    void testPipe() {
        var expr = parse(".foo | .bar");
        assertInstanceOf(PipeExpr.class, expr);
        var pipe = (PipeExpr) expr;
        assertInstanceOf(DotFieldExpr.class, pipe.left());
        assertInstanceOf(DotFieldExpr.class, pipe.right());
    }

    @Test
    void testComma() {
        var expr = parse(".foo, .bar");
        assertInstanceOf(CommaExpr.class, expr);
    }

    @Test
    void testLiteralNumber() {
        var expr = parse("42");
        assertInstanceOf(LiteralExpr.class, expr);
        assertEquals(JqNumber.of(42), ((LiteralExpr) expr).value());
    }

    @Test
    void testLiteralString() {
        var expr = parse("\"hello\"");
        assertInstanceOf(LiteralExpr.class, expr);
        assertEquals(JqString.of("hello"), ((LiteralExpr) expr).value());
    }

    @Test
    void testLiteralNull() {
        var expr = parse("null");
        assertInstanceOf(LiteralExpr.class, expr);
        assertEquals(JqNull.NULL, ((LiteralExpr) expr).value());
    }

    @Test
    void testArrayConstruct() {
        var expr = parse("[.foo, .bar]");
        assertInstanceOf(ArrayConstructExpr.class, expr);
    }

    @Test
    void testEmptyArray() {
        var expr = parse("[]");
        assertInstanceOf(ArrayConstructExpr.class, expr);
        assertNull(((ArrayConstructExpr) expr).body());
    }

    @Test
    void testObjectConstruct() {
        var expr = parse("{name: .foo}");
        assertInstanceOf(ObjectConstructExpr.class, expr);
        var obj = (ObjectConstructExpr) expr;
        assertEquals(1, obj.entries().size());
    }

    @Test
    void testObjectShorthand() {
        var expr = parse("{name}");
        assertInstanceOf(ObjectConstructExpr.class, expr);
    }

    @Test
    void testArithmetic() {
        var expr = parse(". + 1");
        assertInstanceOf(ArithmeticExpr.class, expr);
        assertEquals(ArithmeticExpr.Op.ADD, ((ArithmeticExpr) expr).op());
    }

    @Test
    void testPrecedence() {
        // . + 1 * 2 should parse as . + (1 * 2)
        var expr = parse(". + 1 * 2");
        assertInstanceOf(ArithmeticExpr.class, expr);
        var arith = (ArithmeticExpr) expr;
        assertEquals(ArithmeticExpr.Op.ADD, arith.op());
        assertInstanceOf(ArithmeticExpr.class, arith.right());
    }

    @Test
    void testComparison() {
        var expr = parse(". == 1");
        assertInstanceOf(ComparisonExpr.class, expr);
    }

    @Test
    void testIf() {
        var expr = parse("if . then \"yes\" else \"no\" end");
        assertInstanceOf(IfExpr.class, expr);
    }

    @Test
    void testIfElif() {
        var expr = parse("if . == 1 then \"one\" elif . == 2 then \"two\" else \"other\" end");
        assertInstanceOf(IfExpr.class, expr);
        var ifExpr = (IfExpr) expr;
        assertEquals(1, ifExpr.elifs().size());
    }

    @Test
    void testNegate() {
        var expr = parse("-(. + 1)");
        assertInstanceOf(NegateExpr.class, expr);
    }

    @Test
    void testFuncCall() {
        var expr = parse("length");
        assertInstanceOf(FuncCallExpr.class, expr);
        assertEquals("length", ((FuncCallExpr) expr).name());
        assertEquals(0, ((FuncCallExpr) expr).args().size());
    }

    @Test
    void testFuncCallWithArgs() {
        var expr = parse("map(. + 1)");
        assertInstanceOf(FuncCallExpr.class, expr);
        assertEquals("map", ((FuncCallExpr) expr).name());
        assertEquals(1, ((FuncCallExpr) expr).args().size());
    }

    @Test
    void testFuncDef() {
        var expr = parse("def double: . * 2; double");
        assertInstanceOf(FuncDefExpr.class, expr);
        var def = (FuncDefExpr) expr;
        assertEquals("double", def.name());
        assertEquals(0, def.params().size());
    }

    @Test
    void testReduce() {
        var expr = parse("reduce .[] as $x (0; . + $x)");
        assertInstanceOf(ReduceExpr.class, expr);
    }

    @Test
    void testTryCatch() {
        var expr = parse("try .foo catch \"error\"");
        assertInstanceOf(TryCatchExpr.class, expr);
    }

    @Test
    void testAlternative() {
        var expr = parse(".foo // \"default\"");
        assertInstanceOf(AlternativeExpr.class, expr);
    }

    @Test
    void testRecurse() {
        var expr = parse("..");
        assertInstanceOf(RecurseExpr.class, expr);
    }

    @Test
    void testIterate() {
        var expr = parse(".[]");
        assertInstanceOf(IterateExpr.class, expr);
    }

    @Test
    void testIndex() {
        var expr = parse(".[0]");
        assertInstanceOf(IndexExpr.class, expr);
    }

    @Test
    void testSlice() {
        var expr = parse(".[2:4]");
        assertInstanceOf(SliceExpr.class, expr);
    }

    @Test
    void testVariable() {
        var expr = parse(".foo as $x | $x");
        // .foo as $x | $x  parses as  VariableBindExpr(.foo, x, $x)
        // because 'as' binds the left expr and the body after |
        assertInstanceOf(VariableBindExpr.class, expr);
    }

    @Test
    void testUpdateAssign() {
        var expr = parse(".foo |= . + 1");
        assertInstanceOf(UpdateExpr.class, expr);
    }

    @Test
    void testOptional() {
        var expr = parse(".foo?");
        assertInstanceOf(OptionalExpr.class, expr);
    }

    @Test
    void testLabel() {
        var expr = parse("label $out | .foo");
        assertInstanceOf(LabelExpr.class, expr);
    }
}

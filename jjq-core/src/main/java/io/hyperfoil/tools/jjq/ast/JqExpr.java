package io.hyperfoil.tools.jjq.ast;

import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.List;
import java.util.Map;

public sealed interface JqExpr {

    default SourceLocation location() { return SourceLocation.UNKNOWN; }

    record IdentityExpr() implements JqExpr {}

    record LiteralExpr(JqValue value) implements JqExpr {}

    record PipeExpr(JqExpr left, JqExpr right, SourceLocation loc) implements JqExpr {
        public PipeExpr(JqExpr left, JqExpr right) { this(left, right, SourceLocation.UNKNOWN); }
        @Override public SourceLocation location() { return loc; }
    }

    record CommaExpr(JqExpr left, JqExpr right) implements JqExpr {}

    record DotFieldExpr(String field, SourceLocation loc) implements JqExpr {
        public DotFieldExpr(String field) { this(field, SourceLocation.UNKNOWN); }
        @Override public SourceLocation location() { return loc; }
    }

    record IndexExpr(JqExpr expr, JqExpr index, SourceLocation loc) implements JqExpr {
        public IndexExpr(JqExpr expr, JqExpr index) { this(expr, index, SourceLocation.UNKNOWN); }
        @Override public SourceLocation location() { return loc; }
    }

    record SliceExpr(JqExpr expr, JqExpr from, JqExpr to) implements JqExpr {}

    record IterateExpr(JqExpr expr, SourceLocation loc) implements JqExpr {
        public IterateExpr(JqExpr expr) { this(expr, SourceLocation.UNKNOWN); }
        @Override public SourceLocation location() { return loc; }
    }

    record RecurseExpr() implements JqExpr {}

    record ArrayConstructExpr(JqExpr body) implements JqExpr {}

    record ObjectConstructExpr(List<ObjectEntry> entries) implements JqExpr {
        public record ObjectEntry(JqExpr key, JqExpr value) {}
    }

    record StringInterpolationExpr(List<JqExpr> parts) implements JqExpr {}

    record NegateExpr(JqExpr expr) implements JqExpr {}

    record ArithmeticExpr(JqExpr left, Op op, JqExpr right, SourceLocation loc) implements JqExpr {
        public enum Op { ADD, SUB, MUL, DIV, MOD }
        public ArithmeticExpr(JqExpr left, Op op, JqExpr right) { this(left, op, right, SourceLocation.UNKNOWN); }
        @Override public SourceLocation location() { return loc; }
    }

    record ComparisonExpr(JqExpr left, Op op, JqExpr right, SourceLocation loc) implements JqExpr {
        public enum Op { EQ, NEQ, LT, GT, LE, GE }
        public ComparisonExpr(JqExpr left, Op op, JqExpr right) { this(left, op, right, SourceLocation.UNKNOWN); }
        @Override public SourceLocation location() { return loc; }
    }

    record LogicalExpr(JqExpr left, Op op, JqExpr right) implements JqExpr {
        public enum Op { AND, OR }
    }

    record NotExpr(JqExpr expr) implements JqExpr {}

    record AlternativeExpr(JqExpr left, JqExpr right) implements JqExpr {}

    record IfExpr(JqExpr condition, JqExpr thenBranch, List<ElifBranch> elifs, JqExpr elseBranch) implements JqExpr {
        public record ElifBranch(JqExpr condition, JqExpr body) {}
    }

    record TryCatchExpr(JqExpr tryExpr, JqExpr catchExpr) implements JqExpr {}

    record OptionalExpr(JqExpr expr) implements JqExpr {}

    record ReduceExpr(JqExpr source, String variable, JqExpr init, JqExpr update) implements JqExpr {}

    record ForeachExpr(JqExpr source, String variable, JqExpr init, JqExpr update, JqExpr extract) implements JqExpr {}

    record FuncDefExpr(String name, List<String> params, JqExpr body, JqExpr next) implements JqExpr {}

    record FuncCallExpr(String name, List<JqExpr> args, SourceLocation loc) implements JqExpr {
        public FuncCallExpr(String name, List<JqExpr> args) { this(name, args, SourceLocation.UNKNOWN); }
        @Override public SourceLocation location() { return loc; }
    }

    record VariableBindExpr(JqExpr expr, String variable, JqExpr body, SourceLocation loc) implements JqExpr {
        public VariableBindExpr(JqExpr expr, String variable, JqExpr body) { this(expr, variable, body, SourceLocation.UNKNOWN); }
        @Override public SourceLocation location() { return loc; }
    }

    record VariableRefExpr(String name, SourceLocation loc) implements JqExpr {
        public VariableRefExpr(String name) { this(name, SourceLocation.UNKNOWN); }
        @Override public SourceLocation location() { return loc; }
    }

    record UpdateExpr(JqExpr path, JqExpr update) implements JqExpr {}

    record AssignExpr(JqExpr path, JqExpr value) implements JqExpr {}

    record AlternativeAssignExpr(JqExpr path, JqExpr value) implements JqExpr {}

    record AddAssignExpr(JqExpr path, JqExpr value) implements JqExpr {}

    record SubAssignExpr(JqExpr path, JqExpr value) implements JqExpr {}

    record MulAssignExpr(JqExpr path, JqExpr value) implements JqExpr {}

    record DivAssignExpr(JqExpr path, JqExpr value) implements JqExpr {}

    record ModAssignExpr(JqExpr path, JqExpr value) implements JqExpr {}

    record LabelExpr(String label, JqExpr body) implements JqExpr {}

    record BreakExpr(String label) implements JqExpr {}

    record FormatExpr(String format, JqExpr input) implements JqExpr {}

    record PathExpr(JqExpr expr) implements JqExpr {}
}

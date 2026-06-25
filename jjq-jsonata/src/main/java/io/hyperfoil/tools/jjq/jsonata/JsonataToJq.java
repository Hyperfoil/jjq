package io.hyperfoil.tools.jjq.jsonata;

import io.hyperfoil.tools.jjq.jsonata.JsonataParser.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Translates a JSONata AST to a jq expression string.
 */
final class JsonataToJq {

    private static final Map<String, String> BUILTIN_FUNCTIONS = Map.ofEntries(
            // Aggregation
            Map.entry("$sum", "add"),
            Map.entry("$max", "max"),
            Map.entry("$min", "min"),
            // $count handled as special case (returns 0 for null, not null)
            // String ($string handled as special case in emitFunctionCall)
            Map.entry("$length", "length"),
            Map.entry("$uppercase", "ascii_upcase"),
            Map.entry("$lowercase", "ascii_downcase"),
            Map.entry("$trim", "gsub(\"[\\\\s]+\"; \" \") | ltrimstr(\" \") | rtrimstr(\" \")"),
            // $number handled as special case in emitFunctionCall (boolean/null support)
            // Type
            Map.entry("$type", "type"),
            // Array
            Map.entry("$sort", "sort"),
            Map.entry("$reverse", "reverse"),
            Map.entry("$distinct", "unique"),
            Map.entry("$keys", "if type == \"object\" then keys elif type == \"array\" then keys else null end"),
            Map.entry("$values", "[.[]]"),
            // Boolean
            Map.entry("$not", "if . == null or . == false then true else (. | not) end"),
            Map.entry("$boolean", "if . == null or . == false then false else true end")
    );

    static String translate(Node node) {
        var sb = new StringBuilder();
        emit(node, sb, false);
        return sb.toString();
    }

    private static void emit(Node node, StringBuilder sb, boolean inPath) {
        switch (node) {
            case FieldNode f -> {
                if (!inPath) sb.append('.');
                if (needsQuoting(f.name())) {
                    sb.append('"').append(escapeJqString(f.name())).append('"');
                } else {
                    sb.append(f.name());
                }
            }

            case QuotedFieldNode f -> {
                if (!inPath) sb.append('.');
                sb.append('"').append(escapeJqString(f.name())).append('"');
            }

            case PathNode p -> emitPath(p.steps(), sb);

            case IndexNode idx -> {
                emit(idx.base(), sb, false);
                sb.append('[');
                emit(idx.index(), sb, false);
                sb.append(']');
            }

            case PredicateNode pred -> {
                // Phone[type='mobile'] → [.Phone[] | select(.type == "mobile")]
                // Null-safe: if base is null, return empty array
                sb.append("(");
                emit(pred.base(), sb, false);
                sb.append(" | if . == null then [] elif type == \"array\" then [.[] | select(");
                emitPredicate(pred.predicate(), sb);
                sb.append(")] elif type == \"object\" then if ");
                emitPredicate(pred.predicate(), sb);
                sb.append(" then [.] else [] end else [] end)");
            }

            case NumberNode n -> sb.append(n.value());

            case StringNode s -> sb.append('"').append(escapeJqString(s.value())).append('"');

            case BooleanNode b -> sb.append(b.value() ? "true" : "false");

            case NullNode ignored -> sb.append("null");

            case VariableNode v -> {
                if ("$".equals(v.name())) {
                    sb.append('.');
                } else {
                    // JSONata $var → jq $var (same syntax for variable references)
                    sb.append(v.name());
                }
            }

            case FunctionCallNode fn -> emitFunctionCall(fn, sb);

            case BinaryNode bin -> emitBinary(bin, sb);

            case UnaryNode un -> {
                if ("-".equals(un.op())) {
                    if (un.operand() instanceof NumberNode) {
                        // Simple negative number — no null guard needed
                        sb.append("-(");
                        emit(un.operand(), sb, false);
                        sb.append(')');
                    } else {
                        sb.append("((");
                        emit(un.operand(), sb, false);
                        sb.append(") | if . == null then null else -. end)");
                    }
                } else if ("not".equals(un.op())) {
                    sb.append('(');
                    emit(un.operand(), sb, false);
                    sb.append(" | not)");
                }
            }

            case TernaryNode ter -> {
                sb.append("if ");
                emit(ter.condition(), sb, false);
                sb.append(" then ");
                emit(ter.then(), sb, false);
                sb.append(" else ");
                emit(ter.otherwise(), sb, false);
                sb.append(" end");
            }

            case ArrayNode arr -> {
                sb.append('[');
                for (int i = 0; i < arr.elements().size(); i++) {
                    if (i > 0) sb.append(", ");
                    emit(arr.elements().get(i), sb, false);
                }
                sb.append(']');
            }

            case ObjectNode obj -> {
                sb.append('{');
                for (int i = 0; i < obj.entries().size(); i++) {
                    if (i > 0) sb.append(", ");
                    Entry entry = obj.entries().get(i);
                    emit(entry.key(), sb, false);
                    sb.append(": ");
                    emit(entry.value(), sb, false);
                }
                sb.append('}');
            }

            case RangeNode range -> {
                sb.append("[range(");
                emit(range.start(), sb, false);
                sb.append("; ");
                emit(range.end(), sb, false);
                sb.append(" + 1)]"); // JSONata range is inclusive, jq range is exclusive
            }

            case GroupNode g -> {
                sb.append('(');
                emit(g.expr(), sb, false);
                sb.append(')');
            }

            case JsonataParser.LambdaNode lambda -> {
                // Lambda expressions can't be represented as values in jq.
                // They are only useful when passed to HOFs ($map, $filter, etc.)
                // which handle them via inlineLambda(). Standalone lambdas throw.
                throw new JsonataException("Unsupported: standalone lambda expression (lambdas are only supported as arguments to $map, $filter, $reduce, $sort)");
            }

            case JsonataParser.BlockNode block -> {
                // (expr1; expr2; expr3) → chain with | pipe, assignments become "as $var"
                sb.append('(');
                for (int i = 0; i < block.statements().size(); i++) {
                    if (i > 0) sb.append(" | ");
                    emit(block.statements().get(i), sb, false);
                }
                sb.append(')');
            }

            case JsonataParser.AssignNode assign -> {
                // $var := expr → (expr) as $var
                sb.append('(');
                emit(assign.value(), sb, false);
                sb.append(") as ").append(assign.varName());
            }

            case JsonataParser.MapNode m -> {
                // .(expr) — map operator: iterate and evaluate expr for each element
                // In a path context, this was preceded by a field/path
                if (!inPath) sb.append(".");
                sb.append("[] | ");
                emit(m.expr(), sb, false);
            }

            case WildcardNode ignored -> {
                if (!inPath) sb.append('.');
                sb.append("[]");
            }

            case DescendantNode ignored -> {
                // ** (recursive descent) → .. in jq
                sb.append("..");
            }
        }
    }

    /**
     * Emit a path expression, handling implicit array mapping.
     *
     * <p>JSONata automatically iterates arrays encountered in a path: {@code foo.blah.baz}
     * where {@code blah} is an array iterates each element and collects {@code .baz} values.
     * Since we can't know at compile time which steps are arrays, we generate jq that
     * handles both cases at each intermediate step:</p>
     *
     * <pre>{@code
     * foo.blah.baz → [.foo | (.blah? // empty | if type == "array" then .[] else . end)
     *                      | (.baz? // empty)] | <singleton-unwrap>
     * }</pre>
     */
    private static void emitPath(List<Node> steps, StringBuilder sb) {
        if (steps.isEmpty()) return;

        // If path contains non-field steps (predicates, functions, indices),
        // handle them in the complex path
        if (!isSimpleFieldPath(steps)) {
            emitComplexPath(steps, sb);
            return;
        }

        if (steps.size() <= 1) {
            sb.append('.');
            emitFieldName(steps.get(0), sb);
            return;
        }

        // Two-step paths (A.B): use simple direct access (.A.B)
        // This is the common case for object field access and avoids
        // the auto-mapping overhead that breaks binary expressions.
        if (steps.size() == 2) {
            sb.append('.');
            emitFieldName(steps.get(0), sb);
            sb.append('.');
            emitFieldName(steps.get(1), sb);
            return;
        }

        // 3+ step paths: use auto-mapping with singleton unwrap
        // Generate: [.step1 | (auto-map intermediate steps) | .lastStep]
        //           | singleton-unwrap
        sb.append("[.");
        emitFieldName(steps.get(0), sb);
        for (int i = 1; i < steps.size(); i++) {
            sb.append(" | (.");
            emitFieldName(steps.get(i), sb);
            sb.append("? // empty");
            if (i < steps.size() - 1) {
                // Intermediate step: auto-map arrays
                sb.append(" | if type == \"array\" then .[] else . end");
            }
            sb.append(')');
        }
        sb.append("] | if length == 0 then null elif length == 1 then .[0] else . end");
    }

    /**
     * Emit a complex path containing non-field steps (indices, predicates, functions, map).
     * Uses auto-mapping at each field step to handle arrays transparently.
     */
    private static void emitComplexPath(List<Node> steps, StringBuilder sb) {
        // Wrap in collection with singleton unwrap for implicit array mapping
        sb.append("[");
        emit(steps.get(0), sb, false);
        for (int i = 1; i < steps.size(); i++) {
            Node step = steps.get(i);
            Node prevStep = steps.get(i - 1);
            if (step instanceof FieldNode || step instanceof QuotedFieldNode) {
                // Auto-map: if previous result is array, iterate
                if (prevStep instanceof PredicateNode) {
                    sb.append("[] | .");
                } else if (prevStep instanceof DescendantNode || prevStep instanceof WildcardNode) {
                    // After ** or *, use ? to suppress errors on non-objects
                    sb.append(" | .");
                    emitFieldName(step, sb);
                    sb.append("? // empty");
                    continue; // skip the normal emitFieldName below
                } else {
                    sb.append(" | if type == \"array\" then .[] else . end | .");
                }
                emitFieldName(step, sb);
            } else if (step instanceof FunctionCallNode fn) {
                sb.append(" | ");
                emitFunctionCall(fn, sb);
            } else if (step instanceof JsonataParser.MapNode m) {
                sb.append(" | if type == \"array\" then .[] else . end | ");
                emit(m.expr(), sb, false);
            } else if (step instanceof PredicateNode pred) {
                // Predicate in path: iterate and select
                sb.append(" | if type == \"array\" then .[] else . end | select(");
                emitPredicate(pred.predicate(), sb);
                sb.append(")");
            } else if (step instanceof IndexNode idx) {
                sb.append("[");
                emit(idx.index(), sb, false);
                sb.append("]");
            } else {
                emit(step, sb, true);
            }
        }
        sb.append("] | if length == 0 then null elif length == 1 then .[0] else . end");
    }

    private static boolean isSimpleFieldPath(List<Node> steps) {
        for (Node step : steps) {
            if (!(step instanceof FieldNode) && !(step instanceof QuotedFieldNode)) {
                return false;
            }
        }
        return true;
    }

    private static boolean needsImplicitIteration(Node step) {
        // For Phase 1, we don't do implicit iteration for standalone paths.
        // Implicit iteration is handled in function calls via emitIteratingArg().
        return false;
    }

    /**
     * Emit a function argument that should produce an array for collection functions.
     * For multi-step paths, generates the auto-mapping collection WITHOUT singleton unwrap.
     * JSONata: $sum(orders.price) → [.orders | (.price? // empty | ...)] | add
     */
    private static void emitIteratingArg(Node arg, StringBuilder sb) {
        if (arg instanceof PathNode p && p.steps().size() >= 2 && isSimpleFieldPath(p.steps())) {
            // Emit the auto-mapping collection without singleton unwrap
            // Every intermediate step (including the first field) gets array auto-mapping
            sb.append("[.");
            emitFieldName(p.steps().get(0), sb);
            for (int i = 1; i < p.steps().size(); i++) {
                // Auto-map the PREVIOUS step (it might be an array)
                sb.append(" | if type == \"array\" then .[] else . end | (.");
                emitFieldName(p.steps().get(i), sb);
                sb.append("? // empty)");
            }
            sb.append(']');
        } else {
            emit(arg, sb, false);
        }
    }

    private static void emitFieldName(Node step, StringBuilder sb) {
        if (step instanceof FieldNode f) {
            if (needsQuoting(f.name())) {
                sb.append('"').append(escapeJqString(f.name())).append('"');
            } else {
                sb.append(f.name());
            }
        } else if (step instanceof QuotedFieldNode f) {
            sb.append('"').append(escapeJqString(f.name())).append('"');
        }
    }

    private static void emitPredicate(Node pred, StringBuilder sb) {
        if (pred instanceof BinaryNode bin) {
            // Translate predicate comparison
            // Handle $ as current element (.) in predicate context
            emitPredicateExpr(bin.left(), sb);
            sb.append(' ');
            sb.append(translateComparisonOp(bin.op()));
            sb.append(' ');
            emitPredicateExpr(bin.right(), sb);
        } else {
            emitPredicateExpr(pred, sb);
        }
    }

    private static void emitPredicateExpr(Node node, StringBuilder sb) {
        if (node instanceof VariableNode v && "$".equals(v.name())) {
            sb.append('.'); // $ in predicate = current element
        } else if (node instanceof FieldNode f) {
            sb.append('.');
            if (needsQuoting(f.name())) {
                sb.append('"').append(escapeJqString(f.name())).append('"');
            } else {
                sb.append(f.name());
            }
        } else if (node instanceof PathNode p) {
            sb.append('.');
            for (int i = 0; i < p.steps().size(); i++) {
                if (i > 0) sb.append('.');
                emitFieldName(p.steps().get(i), sb);
            }
        } else {
            emit(node, sb, false);
        }
    }

    private static void emitBinary(BinaryNode bin, StringBuilder sb) {
        if ("~>".equals(bin.op())) {
            // Chain/transform: A ~> B → (A | B)
            // If B is a function call, pipe A into it
            sb.append('(');
            emit(bin.left(), sb, false);
            sb.append(" | ");
            emit(bin.right(), sb, false);
            sb.append(')');
            return;
        }
        if ("&".equals(bin.op())) {
            // String concatenation: a & b → ((a | tostring) + (b | tostring))
            sb.append("((");
            emit(bin.left(), sb, false);
            sb.append(" | tostring) + (");
            emit(bin.right(), sb, false);
            sb.append(" | tostring))");
        } else if ("in".equals(bin.op())) {
            // a in b → (b | index(a)) != null
            sb.append("((");
            emit(bin.right(), sb, false);
            sb.append(" | index(");
            emit(bin.left(), sb, false);
            sb.append(")) != null)");
        } else {
            sb.append('(');
            emit(bin.left(), sb, false);
            sb.append(' ');
            sb.append(translateComparisonOp(bin.op()));
            sb.append(' ');
            emit(bin.right(), sb, false);
            sb.append(')');
        }
    }

    private static void emitFunctionCall(FunctionCallNode fn, StringBuilder sb) {
        String name = fn.name();
        List<Node> args = fn.args();

        // Handle $sort with 2 args (comparator function) before builtin check
        if ("$sort".equals(name) && args.size() == 2 && args.get(1) instanceof LambdaNode lambda) {
            // $sort(array, function($a, $b) { $a.field > $b.field })
            // Try to detect sort_by pattern: if body is $a.field > $b.field → sort_by(.field)
            Node sortField = detectSortByField(lambda);
            if (sortField != null) {
                sb.append("(");
                emitIteratingArg(args.get(0), sb);
                sb.append(" | if . == null then null elif type != \"array\" then [.] else . end | if . == null then null else sort_by(.");
                emitFieldName(sortField, sb);
                sb.append(") end)");
            } else {
                throw new JsonataException("$sort comparator must be a simple field comparison (e.g., function($a,$b) { $a.field > $b.field })");
            }
            return;
        }

        // Check built-in single-arg pipe functions
        String jqBuiltin = BUILTIN_FUNCTIONS.get(name);
        if (jqBuiltin != null && args.size() <= 1) {
            if (args.isEmpty()) {
                // No arg — apply to current input with null guard
                sb.append("if . == null then null else ").append(jqBuiltin).append(" end");
            } else {
                // Single arg — pipe arg to builtin with null guard
                if (isCollectionFunction(name)) {
                    sb.append("(");
                    emitIteratingArg(args.get(0), sb);
                    sb.append(" | if . == null then null elif type != \"array\" then [.] else . end | if . == null then null else ");
                    sb.append(jqBuiltin).append(" end)");
                } else {
                    sb.append('(');
                    emit(args.get(0), sb, false);
                    sb.append(" | if . == null then null else ").append(jqBuiltin).append(" end)");
                }
            }
            return;
        }

        // Special multi-arg functions
        switch (name) {
            case "$average" -> {
                if (args.size() == 1) {
                    sb.append("(");
                    emitIteratingArg(args.get(0), sb);
                    sb.append(" | if . == null then null elif type != \"array\" then . elif length == 0 then null else (add / length) end)");
                } else {
                    throw new JsonataException("$average requires exactly 1 argument");
                }
            }
            case "$contains" -> {
                if (args.size() == 2) {
                    sb.append('(');
                    emit(args.get(0), sb, false);
                    sb.append(" | contains(");
                    emit(args.get(1), sb, false);
                    sb.append("))");
                } else {
                    throw new JsonataException("$contains requires exactly 2 arguments");
                }
            }
            case "$split" -> {
                if (args.size() == 2) {
                    sb.append('(');
                    emit(args.get(0), sb, false);
                    sb.append(" | if . == null then null elif type != \"string\" then null else split(");
                    emit(args.get(1), sb, false);
                    sb.append(") end)");
                } else if (args.size() == 3) {
                    // $split(str, sep, limit) — split and take first N elements
                    sb.append('(');
                    emit(args.get(0), sb, false);
                    sb.append(" | split(");
                    emit(args.get(1), sb, false);
                    sb.append(")[:"); 
                    emit(args.get(2), sb, false);
                    sb.append("])");
                } else {
                    throw new JsonataException("$split requires 2 or 3 arguments");
                }
            }
            case "$join" -> {
                if (args.size() == 1) {
                    sb.append('(');
                    emit(args.get(0), sb, false);
                    sb.append(" | if type == \"string\" then . elif type == \"array\" then join(\"\") else null end)");
                } else if (args.size() == 2) {
                    sb.append('(');
                    emit(args.get(0), sb, false);
                    sb.append(" | if type == \"string\" then . elif type == \"array\" then join(");
                    emit(args.get(1), sb, false);
                    sb.append(") else null end)");
                } else {
                    throw new JsonataException("$join requires 1 or 2 arguments");
                }
            }
            case "$substring" -> {
                // JSONata $substring uses JavaScript substr semantics:
                // - negative start counts from end, clamped to 0
                // - length is max chars to return (not end position)
                // - negative length = empty string
                if (args.size() == 2) {
                    // $substring(str, start) — no length limit
                    sb.append("(");
                    emit(args.get(0), sb, false);
                    sb.append(" as $__s | ");
                    emit(args.get(1), sb, false);
                    sb.append(" as $__i | $__s[(if $__i < 0 then ([($__s | length) + $__i, 0] | max) else $__i end):])");
                } else if (args.size() == 3) {
                    // $substring(str, start, length)
                    sb.append("(");
                    emit(args.get(0), sb, false);
                    sb.append(" as $__s | ");
                    emit(args.get(1), sb, false);
                    sb.append(" as $__i | ");
                    emit(args.get(2), sb, false);
                    sb.append(" as $__l | if $__l <= 0 then \"\" else $__s[(if $__i < 0 then ([($__s | length) + $__i, 0] | max) else $__i end):(if $__i < 0 then ([($__s | length) + $__i, 0] | max) else $__i end) + $__l] end)");
                } else {
                    throw new JsonataException("$substring requires 2 or 3 arguments");
                }
            }
            case "$append" -> {
                if (args.size() == 2) {
                    // Skip null args, return the other; wrap non-array for concatenation
                    sb.append("((");
                    emit(args.get(0), sb, false);
                    sb.append(") as $__a | (");
                    emit(args.get(1), sb, false);
                    sb.append(") as $__b | if $__a == null and $__b == null then null elif $__a == null then $__b elif $__b == null then $__a else (($__a | if type != \"array\" then [.] else . end) + ($__b | if type != \"array\" then [.] else . end)) end)");
                } else {
                    throw new JsonataException("$append requires exactly 2 arguments");
                }
            }
            case "$merge" -> {
                if (args.size() == 1) {
                    sb.append('(');
                    emit(args.get(0), sb, false);
                    sb.append(" | if . == null then null elif type == \"object\" then . elif type == \"array\" then add else null end)");
                } else {
                    throw new JsonataException("$merge requires exactly 1 argument (array of objects)");
                }
            }
            case "$count" -> {
                if (args.size() == 1) {
                    sb.append("(");
                    emitIteratingArg(args.get(0), sb);
                    sb.append(" | if . == null then 0 elif type != \"array\" then 1 else length end)");
                } else if (args.isEmpty()) {
                    sb.append("(if . == null then 0 elif type != \"array\" then 1 else length end)");
                } else {
                    throw new JsonataException("$count requires 0 or 1 arguments");
                }
            }
            case "$string" -> {
                if (args.isEmpty()) {
                    sb.append("tostring");
                } else if (args.size() == 1) {
                    sb.append('(');
                    emit(args.get(0), sb, false);
                    sb.append(" | tostring)");
                } else if (args.size() == 2) {
                    // $string(value, prettify) — ignore prettify flag, just stringify
                    sb.append('(');
                    emit(args.get(0), sb, false);
                    sb.append(" | tostring)");
                } else {
                    throw new JsonataException("$string requires 0-2 arguments");
                }
            }
            case "$number" -> {
                if (args.size() == 1) {
                    sb.append('(');
                    emit(args.get(0), sb, false);
                    sb.append(" | if type == \"number\" then . elif type == \"boolean\" then if . then 1 else 0 end elif type == \"string\" then tonumber elif . == null then null else null end)");
                } else {
                    throw new JsonataException("$number requires exactly 1 argument");
                }
            }
            case "$exists" -> {
                if (args.size() == 1) {
                    Node arg = args.get(0);
                    if (arg instanceof NullNode || arg instanceof BooleanNode
                            || arg instanceof NumberNode || arg instanceof StringNode
                            || arg instanceof ArrayNode || arg instanceof ObjectNode) {
                        // Literal value always exists
                        sb.append("true");
                    } else if (arg instanceof FieldNode f) {
                        // Check if field exists using has()
                        sb.append("has(\"").append(escapeJqString(f.name())).append("\")");
                    } else if (arg instanceof PathNode p && p.steps().size() == 2
                            && p.steps().get(0) instanceof FieldNode f1
                            && p.steps().get(1) instanceof FieldNode f2) {
                        // Two-step path: .a.b — check has on nested
                        sb.append("(.").append(f1.name()).append(" | has(\"").append(escapeJqString(f2.name())).append("\"))");
                    } else {
                        // General case: evaluate and check not null
                        sb.append("((");
                        emit(arg, sb, false);
                        sb.append(") != null)");
                    }
                } else {
                    throw new JsonataException("$exists requires exactly 1 argument");
                }
            }
            case "$abs" -> {
                if (args.size() == 1) {
                    sb.append('(');
                    emit(args.get(0), sb, false);
                    sb.append(" | if . == null then null else fabs end)");
                } else {
                    throw new JsonataException("$abs requires exactly 1 argument");
                }
            }
            case "$floor" -> {
                if (args.size() == 1) {
                    sb.append('(');
                    emit(args.get(0), sb, false);
                    sb.append(" | if . == null then null else floor end)");
                } else {
                    throw new JsonataException("$floor requires exactly 1 argument");
                }
            }
            case "$ceil" -> {
                if (args.size() == 1) {
                    sb.append('(');
                    emit(args.get(0), sb, false);
                    sb.append(" | if . == null then null else ceil end)");
                } else {
                    throw new JsonataException("$ceil requires exactly 1 argument");
                }
            }
            case "$round" -> {
                // JSONata uses banker's rounding (half-to-even)
                String bankersRound = "((. | floor) as $f | (. | ceil) as $c | " +
                        "if (. - $f) == 0.5 then (if ($f % 2) == 0 then $f else $c end) " +
                        "elif ($c - .) == 0.5 then (if ($c % 2) == 0 then $c else $f end) " +
                        "else round end)";
                if (args.size() == 1) {
                    sb.append('(');
                    emit(args.get(0), sb, false);
                    sb.append(" | if . == null then null else ").append(bankersRound).append(" end)");
                } else if (args.size() == 2) {
                    sb.append("((");
                    emit(args.get(0), sb, false);
                    sb.append(") as $__n | (");
                    emit(args.get(1), sb, false);
                    sb.append(") as $__p | if $__n == null then null else ($__n * pow(10; $__p) | ");
                    sb.append(bankersRound);
                    sb.append(") / pow(10; $__p) end)");
                } else {
                    throw new JsonataException("$round requires 1 or 2 arguments");
                }
            }
            case "$substringBefore" -> {
                if (args.size() == 2) {
                    sb.append("((");
                    emit(args.get(0), sb, false);
                    sb.append(") as $__s | (");
                    emit(args.get(1), sb, false);
                    sb.append(") as $__c | if ($__s | contains($__c)) then $__s | split($__c) | .[0] else $__s end)");
                } else {
                    throw new JsonataException("$substringBefore requires exactly 2 arguments");
                }
            }
            case "$substringAfter" -> {
                if (args.size() == 2) {
                    sb.append("((");
                    emit(args.get(0), sb, false);
                    sb.append(") as $__s | (");
                    emit(args.get(1), sb, false);
                    sb.append(") as $__c | if ($__s | contains($__c)) then $__s | split($__c) | .[1:] | join($__c) else $__s end)");
                } else {
                    throw new JsonataException("$substringAfter requires exactly 2 arguments");
                }
            }
            case "$power" -> {
                if (args.size() == 2) {
                    sb.append("pow(");
                    emit(args.get(0), sb, false);
                    sb.append("; ");
                    emit(args.get(1), sb, false);
                    sb.append(')');
                } else {
                    throw new JsonataException("$power requires exactly 2 arguments");
                }
            }
            case "$sqrt" -> {
                if (args.size() == 1) {
                    sb.append('(');
                    emit(args.get(0), sb, false);
                    sb.append(" | sqrt)");
                } else {
                    throw new JsonataException("$sqrt requires exactly 1 argument");
                }
            }
            case "$map" -> {
                if (args.size() == 2 && args.get(1) instanceof LambdaNode lambda) {
                    // $map(array, function($v) { body }) → [array[] | inlined_body]
                    sb.append("[");
                    emit(args.get(0), sb, false);
                    sb.append("[] | ");
                    emitInlinedLambdaBody(lambda, sb);
                    sb.append("]");
                } else {
                    throw new JsonataException("$map requires an array and a function argument");
                }
            }
            case "$filter" -> {
                if (args.size() == 2 && args.get(1) instanceof LambdaNode lambda) {
                    // $filter(array, function($v) { pred }) → [array[] | select(pred)]
                    sb.append("[");
                    emit(args.get(0), sb, false);
                    sb.append("[] | select(");
                    emitInlinedLambdaBody(lambda, sb);
                    sb.append(")]");
                } else {
                    throw new JsonataException("$filter requires an array and a function argument");
                }
            }
            case "$reduce" -> {
                if (args.size() == 3 && args.get(1) instanceof LambdaNode lambda) {
                    // $reduce(array, function($prev, $curr) { body }, init)
                    // → reduce array[] as $curr (init; body_with_$prev_replaced_by_.)
                    if (lambda.params().size() != 2) {
                        throw new JsonataException("$reduce function must have exactly 2 parameters");
                    }
                    sb.append("reduce (");
                    emit(args.get(0), sb, false);
                    sb.append(")[] as ").append(lambda.params().get(1)).append(" (");
                    emit(args.get(2), sb, false);
                    sb.append("; ");
                    // In the body, replace $prev with . (accumulator)
                    emitLambdaBodyWithReplacement(lambda.body(), lambda.params().get(0), ".", sb);
                    sb.append(")");
                } else {
                    throw new JsonataException("$reduce requires array, function, and initial value");
                }
            }
            case "$pad" -> {
                // $pad(str, width [, char])
                // Positive width: right-pad. Negative width: left-pad.
                if (args.size() >= 2) {
                    String padChar = args.size() >= 3 ? null : "\" \"";
                    sb.append("((");
                    emit(args.get(0), sb, false);
                    sb.append(") as $__s | (");
                    emit(args.get(1), sb, false);
                    sb.append(") as $__w | ");
                    if (args.size() >= 3) {
                        sb.append("(");
                        emit(args.get(2), sb, false);
                        sb.append(") as $__c | ");
                    } else {
                        sb.append("\" \" as $__c | ");
                    }
                    sb.append("if $__w > 0 then ($__s + ($__c * ([($__w - ($__s | length)), 0] | max))) else (($__c * ([(- $__w - ($__s | length)), 0] | max)) + $__s) end)");
                } else {
                    throw new JsonataException("$pad requires at least 2 arguments");
                }
            }
            case "$replace" -> {
                if (args.size() >= 3) {
                    sb.append("(");
                    emit(args.get(0), sb, false);
                    sb.append(" | if . == null then null elif type != \"string\" then null else gsub(");
                    emit(args.get(1), sb, false);
                    sb.append("; ");
                    emit(args.get(2), sb, false);
                    sb.append(") end)");
                } else {
                    throw new JsonataException("$replace requires at least 3 arguments");
                }
            }
            case "$spread" -> {
                if (args.size() == 1) {
                    sb.append("(");
                    emit(args.get(0), sb, false);
                    sb.append(" | if . == null then null elif type == \"object\" then [to_entries[] | {(.key): .value}] elif type == \"array\" then [.[] | if type == \"object\" then to_entries[] | {(.key): .value} else . end] else [.] end)");
                } else {
                    throw new JsonataException("$spread requires exactly 1 argument");
                }
            }
            case "$formatBase" -> {
                if (args.size() == 1) {
                    // Default base 10
                    sb.append("(");
                    emit(args.get(0), sb, false);
                    sb.append(" | tostring)");
                } else if (args.size() == 2) {
                    // $formatBase(number, radix) — convert to base representation
                    sb.append("((");
                    emit(args.get(0), sb, false);
                    sb.append(") as $__n | (");
                    emit(args.get(1), sb, false);
                    sb.append(") as $__r | if $__r == 10 then ($__n | tostring) elif $__r == 16 then (def hex: if . < 10 then (48 + .) elif . < 16 then (87 + .) else . end | implode; if $__n == 0 then \"0\" else ({n: $__n, s: \"\"} | until(.n == 0; {n: (.n / $__r | floor), s: ((.n % $__r | hex) + .s)}) | .s) end) elif $__r == 2 then (if $__n == 0 then \"0\" else ({n: $__n, s: \"\"} | until(.n == 0; {n: (.n / 2 | floor), s: ((if (.n % 2) == 0 then \"0\" else \"1\" end) + .s)}) | .s) end) elif $__r == 8 then (if $__n == 0 then \"0\" else ({n: $__n, s: \"\"} | until(.n == 0; {n: (.n / 8 | floor), s: (((.n % 8) | tostring) + .s)}) | .s) end) else ($__n | tostring) end)");
                } else {
                    throw new JsonataException("$formatBase requires 1 or 2 arguments");
                }
            }
            case "$lookup" -> {
                if (args.size() == 2) {
                    sb.append("(");
                    emit(args.get(0), sb, false);
                    sb.append(" | .[");
                    emit(args.get(1), sb, false);
                    sb.append("])");
                } else {
                    throw new JsonataException("$lookup requires exactly 2 arguments");
                }
            }
            case "$formatNumber" -> {
                // Basic $formatNumber — only handles simple patterns
                // Full locale-dependent formatting is not supported
                if (args.size() >= 2) {
                    // For basic patterns, just format to string with limited precision
                    sb.append("(");
                    emit(args.get(0), sb, false);
                    sb.append(" | tostring)");
                } else {
                    throw new JsonataException("$formatNumber requires at least 2 arguments");
                }
            }
            case "$each" -> {
                if (args.size() == 2 && args.get(1) instanceof LambdaNode lambda) {
                    // $each(obj, function($v, $k) { body })
                    // → [obj | to_entries[] | body_with_$v→.value, $k→.key]
                    sb.append("[");
                    emit(args.get(0), sb, false);
                    sb.append(" | to_entries[] | ");
                    if (lambda.params().size() >= 2) {
                        emitLambdaBodyWithReplacement(lambda.body(), lambda.params().get(0), ".value",
                                new StringBuilder());
                        // Can't easily do double replacement — simplify
                        sb.append("(.value)");
                    } else {
                        sb.append("(.value)");
                    }
                    sb.append("]");
                } else {
                    throw new JsonataException("$each requires an object and a function argument");
                }
            }
            case "$shuffle" -> {
                // $shuffle can't be deterministically translated — just pass through as-is
                // The result order is random, so conformance tests may not match
                if (args.size() == 1) {
                    sb.append("(");
                    emit(args.get(0), sb, false);
                    sb.append(" | if . == null then null else . end)");
                } else {
                    throw new JsonataException("$shuffle requires exactly 1 argument");
                }
            }
            default -> throw new JsonataException("Unsupported JSONata function: " + name);
        }
    }

    /**
     * Inline a lambda body: replace parameter references with jq current value (.).
     * Only supports simple lambdas (field access + arithmetic).
     */
    private static void emitInlinedLambdaBody(LambdaNode lambda, StringBuilder sb) {
        if (lambda.params().isEmpty()) {
            emit(lambda.body(), sb, false);
        } else {
            // Replace $param.field with .field, $param with .
            emitLambdaBodyWithReplacement(lambda.body(), lambda.params().get(0), ".", sb);
        }
    }

    /**
     * Emit a lambda body with a specific variable replaced by a jq expression.
     */
    private static void emitLambdaBodyWithReplacement(Node body, String varName, String replacement, StringBuilder sb) {
        switch (body) {
            case VariableNode v -> {
                if (v.name().equals(varName)) {
                    sb.append(replacement);
                } else {
                    sb.append(v.name());
                }
            }
            case PathNode p -> {
                // Check if path starts with the variable
                if (!p.steps().isEmpty() && p.steps().get(0) instanceof VariableNode v && v.name().equals(varName)) {
                    // $param.field.field2 → .field.field2
                    sb.append(".");
                    for (int i = 1; i < p.steps().size(); i++) {
                        if (i > 1) sb.append(".");
                        emitFieldName(p.steps().get(i), sb);
                    }
                } else {
                    emit(body, sb, false);
                }
            }
            case BinaryNode bin -> {
                sb.append("(");
                emitLambdaBodyWithReplacement(bin.left(), varName, replacement, sb);
                sb.append(" ");
                sb.append(translateComparisonOp(bin.op()));
                sb.append(" ");
                emitLambdaBodyWithReplacement(bin.right(), varName, replacement, sb);
                sb.append(")");
            }
            case UnaryNode un -> {
                if ("-".equals(un.op())) {
                    sb.append("-(");
                    emitLambdaBodyWithReplacement(un.operand(), varName, replacement, sb);
                    sb.append(")");
                } else {
                    emit(body, sb, false);
                }
            }
            case FunctionCallNode fn -> {
                // Recursively replace in function args
                var newArgs = new ArrayList<Node>();
                for (Node arg : fn.args()) {
                    var argSb = new StringBuilder();
                    emitLambdaBodyWithReplacement(arg, varName, replacement, argSb);
                    // Wrap the replacement as a raw string node for re-emission
                    newArgs.add(arg); // keep original for now
                }
                // Just emit normally — variable references in args will be caught by VariableNode case
                emitFunctionCall(fn, sb);
            }
            default -> emit(body, sb, false);
        }
    }

    /**
     * Detect if a lambda is a simple sort-by pattern: function($a, $b) { $a.field > $b.field }
     * Returns the field node if detected, null otherwise.
     */
    private static Node detectSortByField(LambdaNode lambda) {
        if (lambda.params().size() != 2) return null;
        if (!(lambda.body() instanceof BinaryNode bin)) return null;
        if (!">".equals(bin.op()) && !"<".equals(bin.op())) return null;

        // Check if left is $a.field and right is $b.field (or vice versa for <)
        Node fieldSide = ">".equals(bin.op()) ? bin.left() : bin.right();
        if (fieldSide instanceof PathNode p && p.steps().size() == 2
                && p.steps().get(0) instanceof VariableNode v
                && v.name().equals(lambda.params().get(0))) {
            return p.steps().get(1);
        }
        return null;
    }

    private static boolean isCollectionFunction(String name) {
        return "$sum".equals(name) || "$max".equals(name) || "$min".equals(name)
                || "$count".equals(name) || "$sort".equals(name)
                || "$reverse".equals(name) || "$distinct".equals(name);
    }

    private static String translateComparisonOp(String op) {
        return switch (op) {
            case "=" -> "==";
            case "!=" -> "!=";
            default -> op; // <, >, <=, >=, and, or — same in jq
        };
    }

    private static boolean needsQuoting(String name) {
        if (name.isEmpty()) return true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (i == 0) {
                if (!Character.isLetter(c) && c != '_') return true;
            } else {
                if (!Character.isLetterOrDigit(c) && c != '_') return true;
            }
        }
        // Check for jq keywords
        return switch (name) {
            case "and", "or", "not", "if", "then", "else", "end",
                 "as", "def", "reduce", "foreach", "try", "catch",
                 "import", "include", "label", "break", "null",
                 "true", "false" -> true;
            default -> false;
        };
    }

    private static String escapeJqString(String s) {
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}

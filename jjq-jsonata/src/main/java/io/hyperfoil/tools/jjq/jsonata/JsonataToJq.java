package io.hyperfoil.tools.jjq.jsonata;

import io.hyperfoil.tools.jjq.jsonata.JsonataParser.*;

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
            Map.entry("$count", "length"),
            // String ($string handled as special case in emitFunctionCall)
            Map.entry("$length", "length"),
            Map.entry("$uppercase", "ascii_upcase"),
            Map.entry("$lowercase", "ascii_downcase"),
            Map.entry("$trim", "gsub(\"[\\\\s]+\"; \" \") | ltrimstr(\" \") | rtrimstr(\" \")"),
            Map.entry("$number", "tonumber"),
            // Type
            Map.entry("$type", "type"),
            // Array
            Map.entry("$sort", "sort"),
            Map.entry("$reverse", "reverse"),
            Map.entry("$distinct", "unique"),
            Map.entry("$keys", "keys"),
            Map.entry("$values", "[.[]]"),
            // Boolean
            Map.entry("$not", "not"),
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
                sb.append('[');
                emit(pred.base(), sb, false);
                sb.append("[] | select(");
                emitPredicate(pred.predicate(), sb);
                sb.append(")]");
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
                    sb.append("-(");
                    emit(un.operand(), sb, false);
                    sb.append(')');
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
                throw new JsonataException("Unsupported JSONata feature: recursive descent (**)");
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
     */
    private static void emitComplexPath(List<Node> steps, StringBuilder sb) {
        emit(steps.get(0), sb, false);
        for (int i = 1; i < steps.size(); i++) {
            Node step = steps.get(i);
            Node prevStep = steps.get(i - 1);
            if (step instanceof FieldNode || step instanceof QuotedFieldNode) {
                // If previous step was a predicate (produces array), need to iterate
                if (prevStep instanceof PredicateNode) {
                    sb.append("[] | .");
                    emitFieldName(step, sb);
                } else {
                    sb.append('.');
                    emitFieldName(step, sb);
                }
            } else if (step instanceof FunctionCallNode fn) {
                sb.append(" | ");
                emitFunctionCall(fn, sb);
            } else if (step instanceof JsonataParser.MapNode m) {
                sb.append("[] | ");
                emit(m.expr(), sb, false);
            } else {
                emit(step, sb, true);
            }
        }
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
                    sb.append(" | split(");
                    emit(args.get(1), sb, false);
                    sb.append("))");
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
                    sb.append(" | join(\"\"))");
                } else if (args.size() == 2) {
                    sb.append('(');
                    emit(args.get(0), sb, false);
                    sb.append(" | join(");
                    emit(args.get(1), sb, false);
                    sb.append("))");
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
                    // Wrap non-array args in [.] before concatenation
                    sb.append("((");
                    emit(args.get(0), sb, false);
                    sb.append(" | if type != \"array\" then [.] else . end) + (");
                    emit(args.get(1), sb, false);
                    sb.append(" | if type != \"array\" then [.] else . end))");
                } else {
                    throw new JsonataException("$append requires exactly 2 arguments");
                }
            }
            case "$merge" -> {
                if (args.size() == 1) {
                    sb.append('(');
                    emit(args.get(0), sb, false);
                    sb.append(" | add)");
                } else {
                    throw new JsonataException("$merge requires exactly 1 argument (array of objects)");
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
                    // JSONata: $exists returns true if the value is not undefined/missing
                    // In jq, missing fields return null. $exists(null_literal) should be true
                    // but $exists(missing_field) should be false.
                    // Best approximation: check if result is not null
                    sb.append("((");
                    emit(args.get(0), sb, false);
                    sb.append(") != null)");
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
                if (args.size() == 1) {
                    sb.append('(');
                    emit(args.get(0), sb, false);
                    sb.append(" | if . == null then null else round end)");
                } else if (args.size() == 2) {
                    // $round(number, precision) — round to N decimal places
                    // Note: uses jq's round (banker's rounding / half-to-even).
                    // JSONata uses half-away-from-zero — documented as known difference.
                    sb.append("((");
                    emit(args.get(0), sb, false);
                    sb.append(") as $__n | (");
                    emit(args.get(1), sb, false);
                    sb.append(") as $__p | ($__n * pow(10; $__p) | round) / pow(10; $__p))");
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
            default -> throw new JsonataException("Unsupported JSONata function: " + name);
        }
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

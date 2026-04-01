package io.hyperfoil.tools.jjq.evaluator;

import io.hyperfoil.tools.jjq.ast.JqExpr;
import io.hyperfoil.tools.jjq.ast.JqExpr.*;
import io.hyperfoil.tools.jjq.ast.SourceLocation;
import io.hyperfoil.tools.jjq.builtin.BuiltinRegistry;
import io.hyperfoil.tools.jjq.value.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

public final class Evaluator {
    private final BuiltinRegistry builtins;

    public Evaluator() {
        this.builtins = BuiltinRegistry.getDefault();
    }

    public Evaluator(BuiltinRegistry builtins) {
        this.builtins = builtins;
    }

    public List<JqValue> eval(JqExpr expr, JqValue input) {
        return eval(expr, input, new Environment());
    }

    public List<JqValue> eval(JqExpr expr, JqValue input, Environment env) {
        var results = new ArrayList<JqValue>();
        try {
            eval(expr, input, env, results::add);
        } catch (EmptyException ignored) {
            // empty produces no results
        }
        return results;
    }

    public void eval(JqExpr expr, JqValue input, Environment env, Consumer<JqValue> output) {
        switch (expr) {
            case IdentityExpr ignored -> output.accept(input);

            case LiteralExpr lit -> output.accept(lit.value());

            case DotFieldExpr df -> {
                if (input instanceof JqObject obj) {
                    output.accept(obj.get(df.field()));
                } else if (input instanceof JqNull) {
                    output.accept(JqNull.NULL);
                } else {
                    throw new JqException("Cannot index " + input.type().jqName() + " with string (\"" + df.field() + "\")");
                }
            }

            case IndexExpr idx -> {
                List<JqValue> exprs = eval(idx.expr(), input, env);
                for (JqValue base : exprs) {
                    for (JqValue index : eval(idx.index(), input, env)) {
                        output.accept(indexValue(base, index));
                    }
                }
            }

            case SliceExpr sl -> {
                for (JqValue base : eval(sl.expr(), input, env)) {
                    Integer from = sl.from() != null ? sliceIndexOrNull(eval(sl.from(), input, env).getFirst(), true) : null;
                    Integer to = sl.to() != null ? sliceIndexOrNull(eval(sl.to(), input, env).getFirst(), false) : null;
                    if (base instanceof JqArray arr) {
                        output.accept(arr.slice(from, to));
                    } else if (base instanceof JqString s) {
                        String str = s.stringValue();
                        int len = str.length();
                        int start = from == null ? 0 : (from < 0 ? Math.max(0, len + from) : from);
                        int end = to == null ? len : (to < 0 ? Math.max(0, len + to) : Math.min(to, len));
                        output.accept(JqString.of(start < end ? str.substring(start, end) : ""));
                    } else if (base instanceof JqNull) {
                        output.accept(JqNull.NULL);
                    } else {
                        throw new JqException("Cannot slice " + base.type().jqName());
                    }
                }
            }

            case IterateExpr iter -> {
                for (JqValue base : eval(iter.expr(), input, env)) {
                    if (base instanceof JqArray arr) {
                        for (JqValue v : arr.arrayValue()) {
                            output.accept(v);
                        }
                    } else if (base instanceof JqObject obj) {
                        for (JqValue v : obj.objectValue().values()) {
                            output.accept(v);
                        }
                    } else if (base instanceof JqNull) {
                        throw new JqException("Cannot iterate over null (null)");
                    } else {
                        throw new JqException("Cannot iterate over " + base.type().jqName() + " (" + base.toJsonString() + ")");
                    }
                }
            }

            case RecurseExpr ignored -> recurse(input, output);

            case PipeExpr pipe -> {
                eval(pipe.left(), input, env, leftVal -> {
                    try {
                        eval(pipe.right(), leftVal, env, output);
                    } catch (EmptyException ignored) {
                        // empty in pipe right side: skip this input, continue with next
                    }
                });
            }

            case CommaExpr comma -> {
                try {
                    eval(comma.left(), input, env, output);
                } catch (EmptyException ignored) {}
                try {
                    eval(comma.right(), input, env, output);
                } catch (EmptyException ignored) {}
            }

            case ArrayConstructExpr arr -> {
                if (arr.body() == null) {
                    output.accept(JqArray.EMPTY);
                } else {
                    var elements = new ArrayList<JqValue>();
                    try {
                        eval(arr.body(), input, env, elements::add);
                    } catch (EmptyException ignored) {
                        // empty in array context produces no elements
                    }
                    output.accept(JqArray.of(elements));
                }
            }

            case ObjectConstructExpr obj -> {
                var map = new LinkedHashMap<String, JqValue>();
                buildObject(obj.entries(), 0, input, env, map, output);
            }

            case StringInterpolationExpr si -> {
                buildStringInterp(si.parts(), 0, input, env, new StringBuilder(), output);
            }

            case NegateExpr neg -> {
                for (JqValue v : eval(neg.expr(), input, env)) {
                    output.accept(v.negate());
                }
            }

            case ArithmeticExpr arith -> {
                // jq semantics: right is outer loop, left is inner (left varies faster)
                for (JqValue right : eval(arith.right(), input, env)) {
                    for (JqValue left : eval(arith.left(), input, env)) {
                        output.accept(switch (arith.op()) {
                            case ADD -> left.add(right);
                            case SUB -> left.subtract(right);
                            case MUL -> left.multiply(right);
                            case DIV -> left.divide(right);
                            case MOD -> left.modulo(right);
                        });
                    }
                }
            }

            case ComparisonExpr comp -> {
                // jq semantics: right is outer loop, left is inner (left varies faster)
                for (JqValue right : eval(comp.right(), input, env)) {
                    for (JqValue left : eval(comp.left(), input, env)) {
                        boolean result = switch (comp.op()) {
                            case EQ -> left.equals(right);
                            case NEQ -> !left.equals(right);
                            case LT -> left.compareTo(right) < 0;
                            case GT -> left.compareTo(right) > 0;
                            case LE -> left.compareTo(right) <= 0;
                            case GE -> left.compareTo(right) >= 0;
                        };
                        output.accept(JqBoolean.of(result));
                    }
                }
            }

            case LogicalExpr logical -> {
                for (JqValue left : eval(logical.left(), input, env)) {
                    if (logical.op() == LogicalExpr.Op.AND) {
                        if (left.isTruthy()) {
                            for (JqValue right : eval(logical.right(), input, env)) {
                                output.accept(JqBoolean.of(right.isTruthy()));
                            }
                        } else {
                            output.accept(JqBoolean.FALSE);
                        }
                    } else { // OR
                        if (left.isTruthy()) {
                            output.accept(JqBoolean.TRUE);
                        } else {
                            for (JqValue right : eval(logical.right(), input, env)) {
                                output.accept(JqBoolean.of(right.isTruthy()));
                            }
                        }
                    }
                }
            }

            case NotExpr not -> {
                for (JqValue v : eval(not.expr(), input, env)) {
                    output.accept(JqBoolean.of(!v.isTruthy()));
                }
            }

            case AlternativeExpr alt -> {
                var lefts = eval(alt.left(), input, env);
                boolean anyTruthy = false;
                for (JqValue v : lefts) {
                    if (v.isTruthy()) {
                        output.accept(v);
                        anyTruthy = true;
                    }
                }
                if (!anyTruthy) {
                    eval(alt.right(), input, env, output);
                }
            }

            case IfExpr ifExpr -> {
                for (JqValue cond : eval(ifExpr.condition(), input, env)) {
                    if (cond.isTruthy()) {
                        eval(ifExpr.thenBranch(), input, env, output);
                    } else {
                        boolean handled = false;
                        for (var elif : ifExpr.elifs()) {
                            for (JqValue elifCond : eval(elif.condition(), input, env)) {
                                if (elifCond.isTruthy()) {
                                    eval(elif.body(), input, env, output);
                                    handled = true;
                                    break;
                                }
                            }
                            if (handled) break;
                        }
                        if (!handled) {
                            if (ifExpr.elseBranch() != null) {
                                eval(ifExpr.elseBranch(), input, env, output);
                            } else {
                                output.accept(input);
                            }
                        }
                    }
                }
            }

            case TryCatchExpr tc -> {
                try {
                    eval(tc.tryExpr(), input, env, output);
                } catch (JqException | JqTypeError e) {
                    if (tc.catchExpr() != null) {
                        JqValue errorVal = (e instanceof JqException je && je.jqValue() != null)
                                ? je.jqValue()
                                : JqString.of(e.getMessage());
                        eval(tc.catchExpr(), errorVal, env, output);
                    }
                    // If no catch, silently swallow (try without catch = optional)
                }
            }

            case OptionalExpr opt -> {
                try {
                    eval(opt.expr(), input, env, output);
                } catch (JqException | JqTypeError ignored) {
                    // suppress errors
                }
            }

            case ReduceExpr red -> {
                var childEnv = env.child();
                var accumulator = new JqValue[]{ eval(red.init(), input, env).getFirst() };
                eval(red.source(), input, env, val -> {
                    childEnv.setVariable(red.variable(), val);
                    accumulator[0] = eval(red.update(), accumulator[0], childEnv).getFirst();
                });
                output.accept(accumulator[0]);
            }

            case ForeachExpr fe -> {
                eval(fe.init(), input, env, initVal -> {
                    var childEnv = env.child();
                    var accumulator = new JqValue[]{ initVal };
                    eval(fe.source(), input, env, val -> {
                        childEnv.setVariable(fe.variable(), val);
                        accumulator[0] = eval(fe.update(), accumulator[0], childEnv).getFirst();
                        if (fe.extract() != null) {
                            eval(fe.extract(), accumulator[0], childEnv, output);
                        } else {
                            output.accept(accumulator[0]);
                        }
                    });
                });
            }

            case FuncDefExpr fd -> {
                var childEnv = env.child();
                var funcDef = new Environment.FuncDef(fd.name(), fd.params(), fd.body(), childEnv);
                childEnv.defineFunction(fd.name(), fd.params().size(), funcDef);
                eval(fd.next(), input, childEnv, output);
            }

            case FuncCallExpr fc -> evalFuncCall(fc, input, env, output);

            case VariableBindExpr vb -> {
                eval(vb.expr(), input, env, val -> {
                    var childEnv = env.child();
                    childEnv.setVariable(vb.variable(), val);
                    eval(vb.body(), input, childEnv, output);
                });
            }

            case VariableRefExpr vr -> {
                if ("ENV".equals(vr.name())) {
                    var map = new java.util.LinkedHashMap<String, JqValue>();
                    System.getenv().forEach((k, v) -> map.put(k, JqString.of(v)));
                    output.accept(JqObject.of(map));
                } else if ("__loc__".equals(vr.name())) {
                    var map = new java.util.LinkedHashMap<String, JqValue>();
                    map.put("file", JqString.of("<top-level>"));
                    map.put("line", JqNumber.of(1));
                    output.accept(JqObject.of(map));
                } else {
                    output.accept(env.getVariable(vr.name()));
                }
            }

            case UpdateExpr upd -> {
                JqValue result = updatePath(upd.path(), input, env, old ->
                        eval(upd.update(), old.getFirst(), env));
                if (result != null) output.accept(result);
            }

            case AssignExpr assign -> {
                List<JqValue> newVals = eval(assign.value(), input, env);
                for (JqValue newVal : newVals) {
                    output.accept(updatePath(assign.path(), input, env, old -> List.of(newVal)));
                }
            }

            case AddAssignExpr aa -> {
                output.accept(updatePath(aa.path(), input, env, old -> {
                    var results = new ArrayList<JqValue>();
                    for (JqValue newVal : eval(aa.value(), input, env)) {
                        results.add(old.getFirst().add(newVal));
                    }
                    return results;
                }));
            }

            case SubAssignExpr sa -> {
                output.accept(updatePath(sa.path(), input, env, old -> {
                    var results = new ArrayList<JqValue>();
                    for (JqValue newVal : eval(sa.value(), input, env)) {
                        results.add(old.getFirst().subtract(newVal));
                    }
                    return results;
                }));
            }

            case MulAssignExpr ma -> {
                output.accept(updatePath(ma.path(), input, env, old -> {
                    var results = new ArrayList<JqValue>();
                    for (JqValue newVal : eval(ma.value(), input, env)) {
                        results.add(old.getFirst().multiply(newVal));
                    }
                    return results;
                }));
            }

            case DivAssignExpr da -> {
                output.accept(updatePath(da.path(), input, env, old -> {
                    var results = new ArrayList<JqValue>();
                    for (JqValue newVal : eval(da.value(), input, env)) {
                        results.add(old.getFirst().divide(newVal));
                    }
                    return results;
                }));
            }

            case ModAssignExpr moda -> {
                output.accept(updatePath(moda.path(), input, env, old -> {
                    var results = new ArrayList<JqValue>();
                    for (JqValue newVal : eval(moda.value(), input, env)) {
                        results.add(old.getFirst().modulo(newVal));
                    }
                    return results;
                }));
            }

            case AlternativeAssignExpr ala -> {
                output.accept(updatePath(ala.path(), input, env, old -> {
                    JqValue oldVal = old.getFirst();
                    if (oldVal.isTruthy()) {
                        return List.of(oldVal);
                    }
                    return eval(ala.value(), input, env);
                }));
            }

            case LabelExpr lbl -> {
                try {
                    eval(lbl.body(), input, env, output);
                } catch (BreakException e) {
                    if (!e.label().equals(lbl.label())) throw e;
                }
            }

            case BreakExpr brk -> throw new BreakException(brk.label());

            case FormatExpr fmt -> evalFormat(fmt, input, env, output);

            case PathExpr path -> {
                // path(expr) returns the path to the output
                evalPath(path.expr(), input, env, output);
            }
        }
    }

    private void evalFuncCall(FuncCallExpr fc, JqValue input, Environment env, Consumer<JqValue> output) {
        // Zero-arg call might be a filter argument reference
        if (fc.args().isEmpty()) {
            Environment.FilterClosure filterArg = env.getFilterArg(fc.name());
            if (filterArg != null) {
                // Evaluate the captured filter expression with the current input
                // but use the captured environment for variable lookups
                var mergedEnv = filterArg.capturedEnv().child();
                eval(filterArg.expr(), input, mergedEnv, output);
                return;
            }
        }

        // Check user-defined functions
        Environment.FuncDef funcDef = env.getFunction(fc.name(), fc.args().size());
        if (funcDef != null) {
            var callEnv = funcDef.closureEnv().child();
            for (int i = 0; i < funcDef.params().size(); i++) {
                // jq functions pass filters as arguments (closures), not values
                callEnv.setFilterArg(funcDef.params().get(i),
                        new Environment.FilterClosure(fc.args().get(i), env));
            }
            eval(funcDef.body(), input, callEnv, output);
            return;
        }

        // Check builtins
        var builtin = builtins.get(fc.name(), fc.args().size());
        if (builtin != null) {
            builtin.apply(input, fc.args(), env, this, output);
            return;
        }

        throw new JqException("Undefined function: " + fc.name() + "/" + fc.args().size());
    }

    private void recurse(JqValue input, Consumer<JqValue> output) {
        output.accept(input);
        switch (input) {
            case JqArray arr -> {
                for (JqValue v : arr.arrayValue()) {
                    recurse(v, output);
                }
            }
            case JqObject obj -> {
                for (JqValue v : obj.objectValue().values()) {
                    recurse(v, output);
                }
            }
            default -> { /* leaf: no recursion */ }
        }
    }

    /** Convert a JqValue to an integer slice index. Floor for start, ceil for end. */
    /** Returns null for NaN (treat as unbounded), otherwise converts to int with clamping */
    private Integer sliceIndexOrNull(JqValue val, boolean isStart) {
        long l;
        if (val instanceof JqNumber n) {
            if (n.isNaN()) return null; // NaN → treat as unbounded
            if (n.isIntegral()) {
                l = n.longValue();
            } else {
                double d = n.doubleValue();
                l = isStart ? (long) Math.floor(d) : (long) Math.ceil(d);
            }
        } else {
            l = val.longValue();
        }
        // Clamp to int range to avoid overflow
        if (l > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (l < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) l;
    }

    private JqValue indexValue(JqValue base, JqValue index) {
        return JqValues.indexValue(base, index);
    }

    private void buildObject(List<ObjectConstructExpr.ObjectEntry> entries, int idx,
                              JqValue input, Environment env,
                              LinkedHashMap<String, JqValue> map, Consumer<JqValue> output) {
        if (idx >= entries.size()) {
            output.accept(JqObject.of(new LinkedHashMap<>(map)));
            return;
        }
        var entry = entries.get(idx);
        for (JqValue key : eval(entry.key(), input, env)) {
            String keyStr = key instanceof JqString s ? s.stringValue() : key.toJsonString();
            for (JqValue val : eval(entry.value(), input, env)) {
                map.put(keyStr, val);
                buildObject(entries, idx + 1, input, env, map, output);
                map.remove(keyStr);
            }
        }
    }

    private void buildStringInterp(List<JqExpr> parts, int idx, JqValue input,
                                    Environment env, StringBuilder sb, Consumer<JqValue> output) {
        if (idx >= parts.size()) {
            output.accept(JqString.of(sb.toString()));
            return;
        }
        JqExpr part = parts.get(idx);
        if (part instanceof LiteralExpr lit && lit.value() instanceof JqString s) {
            int len = sb.length();
            sb.append(s.stringValue());
            buildStringInterp(parts, idx + 1, input, env, sb, output);
            sb.setLength(len);
        } else {
            for (JqValue v : eval(part, input, env)) {
                int len = sb.length();
                sb.append(v instanceof JqString s ? s.stringValue() : v.toJsonString());
                buildStringInterp(parts, idx + 1, input, env, sb, output);
                sb.setLength(len);
            }
        }
    }

    public JqValue updatePath(JqExpr pathExpr, JqValue input, Environment env,
                                java.util.function.Function<List<JqValue>, List<JqValue>> updater) {
        return switch (pathExpr) {
            case IdentityExpr ignored -> updater.apply(List.of(input)).getFirst();
            case DotFieldExpr df -> {
                if (input instanceof JqObject obj) {
                    var map = new LinkedHashMap<>(obj.objectValue());
                    JqValue oldVal = map.getOrDefault(df.field(), JqNull.NULL);
                    JqValue newVal = updater.apply(List.of(oldVal)).getFirst();
                    map.put(df.field(), newVal);
                    yield JqObject.of(map);
                } else if (input instanceof JqNull) {
                    var map = new LinkedHashMap<String, JqValue>();
                    JqValue newVal = updater.apply(List.of(JqNull.NULL)).getFirst();
                    map.put(df.field(), newVal);
                    yield JqObject.of(map);
                } else {
                    throw new JqException("Cannot index " + input.type().jqName() + " with string");
                }
            }
            case IndexExpr idx -> {
                // For nested index like .[2][3], we need to recurse into idx.expr()
                // so the update propagates back to the root
                if (!(idx.expr() instanceof IdentityExpr)) {
                    yield updatePath(idx.expr(), input, env, old -> {
                        JqValue base = old.getFirst();
                        List<JqValue> indices = eval(idx.index(), input, env);
                        return List.of(multiIndexUpdate(base, indices, updater));
                    });
                }
                List<JqValue> indices = eval(idx.index(), input, env);
                yield multiIndexUpdate(input, indices, updater);
            }
            case IterateExpr iter -> {
                if (!(iter.expr() instanceof IdentityExpr)) {
                    // Nested iterate like map(select(...))[]: validate inner path first
                    try {
                        yield updatePath(iter.expr(), input, env, old -> {
                            JqValue base2 = old.getFirst();
                            if (base2 instanceof JqArray arr2) {
                                var list2 = new ArrayList<JqValue>();
                                for (JqValue elem : arr2.arrayValue()) {
                                    List<JqValue> r = updater.apply(List.of(elem));
                                    if (!r.isEmpty()) list2.add(r.getFirst());
                                }
                                return List.of(JqArray.of(list2));
                            } else if (base2 instanceof JqObject obj2) {
                                var map2 = new LinkedHashMap<String, JqValue>();
                                for (var entry : obj2.objectValue().entrySet()) {
                                    List<JqValue> r = updater.apply(List.of(entry.getValue()));
                                    if (!r.isEmpty()) map2.put(entry.getKey(), r.getFirst());
                                }
                                return List.of(JqObject.of(map2));
                            } else {
                                throw new JqException("Cannot iterate over " + base2.type().jqName());
                            }
                        });
                    } catch (JqException e) {
                        if (e.getMessage().startsWith("Invalid path expression with result ")) {
                            // Add iterate context
                            JqValue[] innerResult = {null};
                            try {
                                eval(iter.expr(), input, env, val -> innerResult[0] = val);
                            } catch (EmptyException ignored) {}
                            if (innerResult[0] != null) {
                                throw new JqException("Invalid path expression near attempt to iterate through " + innerResult[0].toJsonString());
                            }
                        }
                        throw e;
                    }
                }
                JqValue base = input;
                if (base instanceof JqArray arr) {
                    var list = new ArrayList<JqValue>();
                    for (JqValue elem : arr.arrayValue()) {
                        List<JqValue> result = updater.apply(List.of(elem));
                        if (!result.isEmpty()) list.add(result.getFirst());
                        // empty result = element removed (e.g., .[] |= select(...))
                    }
                    yield JqArray.of(list);
                } else if (base instanceof JqObject obj) {
                    var map = new LinkedHashMap<String, JqValue>();
                    for (var entry : obj.objectValue().entrySet()) {
                        List<JqValue> result = updater.apply(List.of(entry.getValue()));
                        if (!result.isEmpty()) map.put(entry.getKey(), result.getFirst());
                    }
                    yield JqObject.of(map);
                } else {
                    throw new JqException("Cannot iterate over " + base.type().jqName() + " (" + base.toJsonString() + ")");
                }
            }
            case SliceExpr sl -> {
                // Slice assignment: .[2:4] = newVal or .[2:4] |= expr
                JqValue base = sl.expr() instanceof IdentityExpr ? input :
                        eval(sl.expr(), input, env).getFirst();
                JqArray arr;
                if (base instanceof JqArray a) {
                    arr = a;
                } else if (base instanceof JqNull) {
                    arr = JqArray.of(List.of());
                } else {
                    throw new JqException("Cannot update " + base.type().jqName() + " slices");
                }
                int len = arr.arrayValue().size();
                Integer fromIdx = sl.from() != null ? sliceIndexOrNull(eval(sl.from(), input, env).getFirst(), true) : null;
                Integer toIdx = sl.to() != null ? sliceIndexOrNull(eval(sl.to(), input, env).getFirst(), false) : null;
                int start = fromIdx == null ? 0 : (fromIdx < 0 ? Math.max(0, len + fromIdx) : Math.min(fromIdx, len));
                int end = toIdx == null ? len : (toIdx < 0 ? Math.max(0, len + toIdx) : Math.min(toIdx, len));
                if (start > end) start = end;

                JqValue oldSlice = arr.slice(fromIdx, toIdx);
                List<JqValue> updated = updater.apply(List.of(oldSlice));

                var result = new ArrayList<JqValue>();
                for (int i = 0; i < start; i++) result.add(arr.arrayValue().get(i));
                if (!updated.isEmpty()) {
                    JqValue newSlice = updated.getFirst();
                    if (newSlice instanceof JqArray newArr) {
                        result.addAll(newArr.arrayValue());
                    } else {
                        result.add(newSlice);
                    }
                }
                // else: empty = delete (e.g., del(.[2:4]))
                for (int i = end; i < len; i++) result.add(arr.arrayValue().get(i));

                if (!(sl.expr() instanceof IdentityExpr)) {
                    yield updatePath(sl.expr(), input, env, old -> List.of(JqArray.of(result)));
                }
                yield JqArray.of(result);
            }
            case PipeExpr pipe -> {
                // .foo | .bar |= expr  =>  update .foo, then within that update .bar
                yield updatePath(pipe.left(), input, env, old -> {
                    JqValue result = updatePath(pipe.right(), old.getFirst(), env, updater);
                    return result == null ? List.of() : List.of(result);
                });
            }
            case VariableBindExpr vb -> {
                // .a as $x | .b — bind the variable and continue with the body path
                var childEnv = env.child();
                var vals = eval(vb.expr(), input, env);
                if (!vals.isEmpty()) {
                    childEnv.setVariable(vb.variable(), vals.getFirst());
                }
                yield updatePath(vb.body(), input, childEnv, updater);
            }
            case FuncCallExpr fc when fc.name().equals("getpath") && fc.args().size() == 1 -> {
                // getpath(["a",0,"b"]) |= expr  =>  update value at the given path
                JqValue pathArr = eval(fc.args().getFirst(), input, env).getFirst();
                if (!(pathArr instanceof JqArray pathList)) {
                    throw new JqException("Path must be an array");
                }
                yield updateAtPath(input, pathList.arrayValue(), 0, updater);
            }
            case FuncCallExpr fc when fc.name().equals("select") && fc.args().size() == 1 -> {
                // select(cond) in path context: only update if condition is truthy
                var condResults = eval(fc.args().getFirst(), input, env);
                if (!condResults.isEmpty() && condResults.getFirst().isTruthy()) {
                    var result = updater.apply(List.of(input));
                    yield result.isEmpty() ? null : result.getFirst();
                }
                yield input; // not selected, keep unchanged
            }
            case FuncCallExpr fc when fc.args().isEmpty() -> {
                // Zero-arg call might be a filter argument reference (e.g., x in def inc(x): x |= .+1)
                Environment.FilterClosure filterArg = env.getFilterArg(fc.name());
                if (filterArg != null) {
                    // Resolve to the actual argument expression and recurse
                    yield updatePath(filterArg.expr(), input, filterArg.capturedEnv(), updater);
                }
                // Also check user-defined functions
                Environment.FuncDef funcDef = env.getFunction(fc.name(), 0);
                if (funcDef != null && funcDef.params().isEmpty()) {
                    yield updatePath(funcDef.body(), input, funcDef.closureEnv() != null ? funcDef.closureEnv() : env, updater);
                }
                // Fall through to default case for path-based fallback
                yield updatePathFallback(pathExpr, input, env, updater);
            }
            default -> {
                yield updatePathFallback(pathExpr, input, env, updater);
            }
        };
    }

    private JqValue updatePathFallback(JqExpr pathExpr, JqValue input, Environment env,
                                        java.util.function.Function<List<JqValue>, List<JqValue>> updater) {
        // General fallback: compute paths via path() and update each one
        var paths = new ArrayList<List<JqValue>>();
        try {
            evalPath(pathExpr, input, env, pathVal -> {
                if (pathVal instanceof JqArray arr) {
                    paths.add(arr.arrayValue());
                }
            });
        } catch (JqException e) {
            // Path evaluation failed — show result in error message like jq does
            String resultStr = null;
            try {
                var results = eval(pathExpr, input, env);
                if (!results.isEmpty()) {
                    resultStr = results.getFirst().toJsonString();
                }
            } catch (JqException | EmptyException ignored) {}
            if (resultStr != null) {
                throw new JqException("Invalid path expression with result " + resultStr);
            }
            throw new JqException("Invalid path expression for update");
        }
        if (paths.isEmpty()) return input;
        // Sort paths deepest-first, higher indices first (to avoid index shifting)
        paths.sort((a, b) -> {
            int lenCmp = Integer.compare(b.size(), a.size());
            if (lenCmp != 0) return lenCmp;
            for (int i = 0; i < a.size(); i++) {
                if (a.get(i) instanceof JqNumber na && b.get(i) instanceof JqNumber nb) {
                    int cmp = Long.compare(nb.longValue(), na.longValue());
                    if (cmp != 0) return cmp;
                }
            }
            return 0;
        });
        JqValue result = input;
        for (List<JqValue> path : paths) {
            result = updateAtPath(result, path, 0, updater);
        }
        return result;
    }

    private JqValue multiIndexUpdate(JqValue base, List<JqValue> indices,
                                      java.util.function.Function<List<JqValue>, List<JqValue>> updater) {
        if (indices.size() > 1 && base instanceof JqArray arr) {
            // Multi-index: resolve all against original, apply all at once
            var list = new ArrayList<>(arr.arrayValue());
            int len = list.size();
            var toRemove = new java.util.TreeSet<Integer>(java.util.Collections.reverseOrder());
            for (JqValue index : indices) {
                if (!(index instanceof JqNumber n)) continue;
                int i = (int) n.longValue();
                if (i < 0) i = len + i;
                if (i >= 0 && i < len) {
                    List<JqValue> newVals = updater.apply(List.of(list.get(i)));
                    if (newVals.isEmpty()) {
                        toRemove.add(i);
                    } else {
                        list.set(i, newVals.getFirst());
                    }
                }
            }
            for (int ri : toRemove) list.remove(ri);
            return JqArray.of(list);
        }
        JqValue result = base;
        for (JqValue index : indices) {
            result = updateAtIndex(result, index, updater);
        }
        return result;
    }

    private JqValue updateAtPath(JqValue input, List<JqValue> path, int idx,
                                  java.util.function.Function<List<JqValue>, List<JqValue>> updater) {
        if (idx >= path.size()) {
            var result = updater.apply(List.of(input));
            return result.isEmpty() ? input : result.getFirst();
        }
        JqValue key = path.get(idx);
        return updateAtIndex(input, key, old -> List.of(updateAtPath(old.getFirst(), path, idx + 1, updater)));
    }

    private JqValue updateAtIndex(JqValue base, JqValue index,
                                   java.util.function.Function<List<JqValue>, List<JqValue>> updater) {
        if ((base instanceof JqArray || base instanceof JqNull) && index instanceof JqNumber n) {
            if (n.isNaN()) throw new JqException("Cannot set array element at NaN index");
            int i = (int) n.longValue();
            var list = base instanceof JqArray arr
                    ? new ArrayList<>(arr.arrayValue())
                    : new ArrayList<JqValue>();
            if (i < 0) {
                i = list.size() + i;
                if (i < 0) throw new JqException("Out of bounds negative array index");
            }
            if (i > 536870911) throw new JqException("Array index too large");
            while (list.size() <= i) list.add(JqNull.NULL);
            JqValue oldVal = list.get(i);
            List<JqValue> newVals = updater.apply(List.of(oldVal));
            if (newVals.isEmpty()) {
                // empty = remove element
                list.remove(i);
            } else {
                list.set(i, newVals.getFirst());
            }
            return JqArray.of(list);
        } else if (base instanceof JqObject obj && index instanceof JqString s) {
            var map = new LinkedHashMap<>(obj.objectValue());
            JqValue oldVal = map.getOrDefault(s.stringValue(), JqNull.NULL);
            List<JqValue> newVals = updater.apply(List.of(oldVal));
            if (newVals.isEmpty()) {
                map.remove(s.stringValue());
            } else {
                map.put(s.stringValue(), newVals.getFirst());
            }
            return JqObject.of(map);
        } else if (base instanceof JqNull && index instanceof JqString s) {
            var map = new LinkedHashMap<String, JqValue>();
            List<JqValue> newVals = updater.apply(List.of(JqNull.NULL));
            if (!newVals.isEmpty()) {
                map.put(s.stringValue(), newVals.getFirst());
            }
            return JqObject.of(map);
        } else {
            String indexType = index instanceof JqNumber ? "number" : "string";
            String indexStr = index instanceof JqNumber n ? String.valueOf((int) n.longValue())
                    : "\"" + ((JqString) index).stringValue() + "\"";
            throw new JqException("Cannot index " + base.type().jqName()
                    + " with " + indexType + " (" + indexStr + ")");
        }
    }

    private void evalFormat(FormatExpr fmt, JqValue input, Environment env, Consumer<JqValue> output) {
        // Format string with interpolation: @html "<b>\(.)</b>"
        // Apply format to each interpolated expression, keep literal parts as-is
        if (fmt.input() instanceof StringInterpolationExpr interp) {
            var sb = new StringBuilder();
            for (JqExpr part : interp.parts()) {
                if (part instanceof LiteralExpr lit && lit.value() instanceof JqString s) {
                    sb.append(s.stringValue());
                } else {
                    JqValue val = eval(part, input, env).getFirst();
                    String str = val instanceof JqString s ? s.stringValue() : val.toJsonString();
                    sb.append(applyFormat(fmt.format(), val, str));
                }
            }
            output.accept(JqString.of(sb.toString()));
            return;
        }

        JqValue val = fmt.input() != null ? eval(fmt.input(), input, env).getFirst() : input;
        String str = val instanceof JqString s ? s.stringValue() : val.toJsonString();
        output.accept(JqString.of(applyFormat(fmt.format(), val, str)));
    }

    private String applyFormat(String format, JqValue val, String str) {
        return switch (format) {
            case "json" -> val.toJsonString();
            case "text" -> str;
            case "csv" -> formatCsv(val);
            case "tsv" -> formatTsv(val);
            case "html" -> escapeHtml(str);
            case "uri" -> encodeUri(str);
            case "urid" -> decodeUri(str);
            case "base64" -> java.util.Base64.getEncoder().encodeToString(str.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            case "base64d" -> new String(java.util.Base64.getDecoder().decode(str), java.nio.charset.StandardCharsets.UTF_8);
            case "base32" -> base32Encode(str.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            case "base32d" -> new String(base32Decode(str), java.nio.charset.StandardCharsets.UTF_8);
            case "sh" -> shellQuote(str);
            default -> throw new JqException("Unknown format: @" + format);
        };
    }

    private String formatCsv(JqValue val) {
        if (!(val instanceof JqArray arr)) throw new JqException("@csv requires array input");
        var sb = new StringBuilder();
        for (int i = 0; i < arr.arrayValue().size(); i++) {
            if (i > 0) sb.append(',');
            JqValue elem = arr.arrayValue().get(i);
            if (elem instanceof JqString s) {
                sb.append('"');
                sb.append(s.stringValue().replace("\"", "\"\""));
                sb.append('"');
            } else {
                sb.append(elem.toJsonString());
            }
        }
        return sb.toString();
    }

    private String formatTsv(JqValue val) {
        if (!(val instanceof JqArray arr)) throw new JqException("@tsv requires array input");
        var sb = new StringBuilder();
        for (int i = 0; i < arr.arrayValue().size(); i++) {
            if (i > 0) sb.append('\t');
            JqValue elem = arr.arrayValue().get(i);
            String s = elem instanceof JqString str ? str.stringValue() : elem.toJsonString();
            sb.append(s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r"));
        }
        return sb.toString();
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&apos;")
                .replace("\"", "&quot;");
    }

    private String encodeUri(String s) {
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20");
        } catch (Exception e) {
            throw new JqException("URI encoding failed: " + e.getMessage());
        }
    }

    private String decodeUri(String s) {
        try {
            return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new JqException("URI decoding failed: " + e.getMessage());
        }
    }

    private void evalPath(JqExpr expr, JqValue input, Environment env, Consumer<JqValue> output) {
        evalPathComponents(expr, input, env, new ArrayList<>(), output);
    }

    private void evalPathComponents(JqExpr expr, JqValue input, Environment env,
                                     List<JqValue> prefix, Consumer<JqValue> output) {
        switch (expr) {
            case IdentityExpr ignored -> output.accept(JqArray.of(new ArrayList<>(prefix)));
            case DotFieldExpr df -> {
                prefix.add(JqString.of(df.field()));
                output.accept(JqArray.of(new ArrayList<>(prefix)));
                prefix.removeLast();
            }
            case IndexExpr idx -> {
                if (idx.expr() instanceof IdentityExpr) {
                    // .[0,1] — just output prefix + each index
                    for (JqValue index : eval(idx.index(), input, env)) {
                        prefix.add(index);
                        output.accept(JqArray.of(new ArrayList<>(prefix)));
                        prefix.removeLast();
                    }
                } else {
                    // .foo[0,1] — recursively evaluate base path, then append each index
                    evalPathComponents(idx.expr(), input, env, prefix, basePath -> {
                        var baseComponents = new ArrayList<>(((JqArray) basePath).arrayValue());
                        JqValue baseVal = getAtPath(input, baseComponents);
                        for (JqValue index : eval(idx.index(), baseVal, env)) {
                            baseComponents.add(index);
                            output.accept(JqArray.of(new ArrayList<>(baseComponents)));
                            baseComponents.removeLast();
                        }
                    });
                }
            }
            case IterateExpr iter -> {
                JqValue base = iter.expr() instanceof IdentityExpr ? input
                        : eval(iter.expr(), input, env).getFirst();
                if (base instanceof JqArray arr) {
                    for (int i = 0; i < arr.arrayValue().size(); i++) {
                        prefix.add(JqNumber.of(i));
                        output.accept(JqArray.of(new ArrayList<>(prefix)));
                        prefix.removeLast();
                    }
                } else if (base instanceof JqObject obj) {
                    for (String key : obj.objectValue().keySet()) {
                        prefix.add(JqString.of(key));
                        output.accept(JqArray.of(new ArrayList<>(prefix)));
                        prefix.removeLast();
                    }
                } else if (base instanceof JqNull) {
                    throw new JqException("Cannot iterate over null (null)");
                } else {
                    throw new JqException("Cannot iterate over " + base.type().jqName() + " (" + base.toJsonString() + ")");
                }
            }
            case PipeExpr pipe -> {
                // path(.foo[0,1]) = path(.foo | .[0,1])
                // For each path component in left, evaluate right
                try {
                    evalPathComponents(pipe.left(), input, env, prefix, leftPath -> {
                        JqValue leftVal = getAtPath(input, ((JqArray) leftPath).arrayValue());
                        var newPrefix = new ArrayList<>(((JqArray) leftPath).arrayValue());
                        evalPathComponents(pipe.right(), leftVal, env, newPrefix, output);
                    });
                } catch (JqException e) {
                    if (e.getMessage().startsWith("Invalid path expression near ")) {
                        // Already has specific context — propagate as-is
                        throw e;
                    }
                    if (e.getMessage().startsWith("Invalid path expression")) {
                        // Sub-expression failed — evaluate left to get result value, then
                        // generate "near attempt to ..." context from the right side
                        JqValue[] leftResult = {null};
                        try {
                            eval(pipe.left(), input, env, val -> leftResult[0] = val);
                        } catch (EmptyException ignored) {}
                        if (leftResult[0] != null) {
                            String context = describePathAttempt(pipe.right(), leftResult[0]);
                            if (context != null) {
                                throw new JqException("Invalid path expression near " + context);
                            }
                            throw new JqException("Invalid path expression with result " + leftResult[0].toJsonString());
                        }
                    }
                    throw e;
                }
            }
            case RecurseExpr ignored -> {
                // path(..) outputs all paths
                allPathsRecursive(input, new ArrayList<>(prefix), output);
            }
            case CommaExpr comma -> {
                evalPathComponents(comma.left(), input, env, prefix, output);
                evalPathComponents(comma.right(), input, env, prefix, output);
            }
            case FuncCallExpr fc when fc.name().equals("select") -> {
                // path(select(f)) outputs the current path if f is truthy
                List<JqValue> results = eval(fc.args().getFirst(), input, env);
                if (!results.isEmpty() && results.getFirst().isTruthy()) {
                    output.accept(JqArray.of(new ArrayList<>(prefix)));
                }
            }
            case FuncCallExpr fc -> {
                // Special handling for first/0 and last/0 — desugar to .[0] and .[-1]
                if (fc.args().isEmpty() && fc.name().equals("first")) {
                    evalPathComponents(
                            new IndexExpr(new IdentityExpr(), new LiteralExpr(JqNumber.of(0)), SourceLocation.UNKNOWN),
                            input, env, prefix, output);
                    break;
                }
                if (fc.args().isEmpty() && fc.name().equals("last")) {
                    evalPathComponents(
                            new IndexExpr(new IdentityExpr(), new LiteralExpr(JqNumber.of(-1)), SourceLocation.UNKNOWN),
                            input, env, prefix, output);
                    break;
                }
                // Try to resolve function definition and evaluate its body as a path
                Environment.FuncDef funcDef = env.getFunction(fc.name(), fc.args().size());
                if (funcDef != null) {
                    var callEnv = funcDef.closureEnv().child();
                    for (int i = 0; i < funcDef.params().size(); i++) {
                        callEnv.setFilterArg(funcDef.params().get(i),
                                new Environment.FilterClosure(fc.args().get(i), env));
                    }
                    evalPathComponents(funcDef.body(), input, callEnv, prefix, output);
                } else {
                    // Builtin function — evaluate and check if it produces output
                    JqValue[] result = {null};
                    try {
                        eval(expr, input, env, val -> result[0] = val);
                    } catch (EmptyException ignored) {}
                    if (result[0] != null) {
                        throw new JqException("Invalid path expression with result " + result[0].toJsonString());
                    }
                    // No outputs (like empty) — valid for path(empty)
                }
            }
            default -> {
                // For other expressions: evaluate and check if they produce output.
                // If no outputs, that's valid (like path(empty)).
                JqValue[] result = {null};
                try {
                    eval(expr, input, env, val -> result[0] = val);
                } catch (EmptyException ignored) {}
                if (result[0] != null) {
                    throw new JqException("Invalid path expression with result " + result[0].toJsonString());
                }
            }
        }
    }

    /**
     * Describes what path operation was attempted on a value, for error messages.
     * Returns null if the expression doesn't represent a recognizable path operation.
     */
    private String describePathAttempt(JqExpr expr, JqValue value) {
        // For pipe expressions, describe the first (leftmost) path operation
        if (expr instanceof JqExpr.PipeExpr pipe) {
            return describePathAttempt(pipe.left(), value);
        }
        if (expr instanceof JqExpr.IndexExpr idx) {
            if (idx.index() == null) {
                // .[] — iterate
                return "attempt to iterate through " + value.toJsonString();
            }
            // .[N] or .field
            try {
                var results = eval(idx.index(), value, new Environment());
                if (!results.isEmpty()) {
                    JqValue indexVal = results.getFirst();
                    return "attempt to access element " + indexVal.toJsonString() + " of " + value.toJsonString();
                }
            } catch (Exception ignored) {}
        }
        if (expr instanceof JqExpr.DotFieldExpr df) {
            return "attempt to access element \"" + df.field() + "\" of " + value.toJsonString();
        }
        if (expr instanceof JqExpr.IterateExpr) {
            return "attempt to iterate through " + value.toJsonString();
        }
        return null;
    }

    private void allPathsRecursive(JqValue value, List<JqValue> currentPath, Consumer<JqValue> output) {
        output.accept(JqArray.of(new ArrayList<>(currentPath)));
        switch (value) {
            case JqArray arr -> {
                for (int i = 0; i < arr.arrayValue().size(); i++) {
                    currentPath.add(JqNumber.of(i));
                    allPathsRecursive(arr.arrayValue().get(i), currentPath, output);
                    currentPath.removeLast();
                }
            }
            case JqObject obj -> {
                for (var entry : obj.objectValue().entrySet()) {
                    currentPath.add(JqString.of(entry.getKey()));
                    allPathsRecursive(entry.getValue(), currentPath, output);
                    currentPath.removeLast();
                }
            }
            default -> {}
        }
    }

    private JqValue getAtPath(JqValue value, List<JqValue> path) {
        JqValue current = value;
        for (JqValue key : path) {
            current = switch (current) {
                case JqObject obj when key instanceof JqString s -> obj.get(s.stringValue());
                case JqArray arr when key instanceof JqNumber n -> arr.get((int) n.longValue());
                case JqNull ignored -> JqNull.NULL;
                default -> JqNull.NULL;
            };
        }
        return current;
    }

    private static final String BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private static String base32Encode(byte[] data) {
        var sb = new StringBuilder();
        int i = 0;
        while (i < data.length) {
            int b0 = data[i++] & 0xFF;
            sb.append(BASE32_CHARS.charAt(b0 >> 3));
            if (i >= data.length) {
                sb.append(BASE32_CHARS.charAt((b0 & 0x07) << 2));
                sb.append("======");
                break;
            }
            int b1 = data[i++] & 0xFF;
            sb.append(BASE32_CHARS.charAt(((b0 & 0x07) << 2) | (b1 >> 6)));
            sb.append(BASE32_CHARS.charAt((b1 >> 1) & 0x1F));
            if (i >= data.length) {
                sb.append(BASE32_CHARS.charAt((b1 & 0x01) << 4));
                sb.append("====");
                break;
            }
            int b2 = data[i++] & 0xFF;
            sb.append(BASE32_CHARS.charAt(((b1 & 0x01) << 4) | (b2 >> 4)));
            if (i >= data.length) {
                sb.append(BASE32_CHARS.charAt((b2 & 0x0F) << 1));
                sb.append("===");
                break;
            }
            int b3 = data[i++] & 0xFF;
            sb.append(BASE32_CHARS.charAt(((b2 & 0x0F) << 1) | (b3 >> 7)));
            sb.append(BASE32_CHARS.charAt((b3 >> 2) & 0x1F));
            if (i >= data.length) {
                sb.append(BASE32_CHARS.charAt((b3 & 0x03) << 3));
                sb.append("=");
                break;
            }
            int b4 = data[i++] & 0xFF;
            sb.append(BASE32_CHARS.charAt(((b3 & 0x03) << 3) | (b4 >> 5)));
            sb.append(BASE32_CHARS.charAt(b4 & 0x1F));
        }
        return sb.toString();
    }

    private static byte[] base32Decode(String encoded) {
        String s = encoded.replaceAll("=", "").toUpperCase();
        var out = new java.io.ByteArrayOutputStream();
        int buffer = 0, bitsLeft = 0;
        for (char c : s.toCharArray()) {
            int val = BASE32_CHARS.indexOf(c);
            if (val < 0) continue;
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }

    private static String shellQuote(String s) {
        if (s.isEmpty()) return "''";
        if (s.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '/')) {
            return s;
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }
}

package io.hyperfoil.tools.jjq.vm;

import io.hyperfoil.tools.jjq.ast.JqExpr;
import io.hyperfoil.tools.jjq.ast.JqExpr.*;
import io.hyperfoil.tools.jjq.value.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.hyperfoil.tools.jjq.vm.Opcode.*;

public final class Compiler {
    private final List<Bytecode.Instruction> code = new ArrayList<>();
    private final List<JqValue> constants = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private final Map<String, Integer> nameIndex = new HashMap<>();
    private final List<JqExpr> subExprs = new ArrayList<>();
    private final List<Bytecode.CallInfo> callInfos = new ArrayList<>();
    // Indexed variable slots (replaces HashMap access for compiler-known variables)
    private final Map<String, Integer> varSlots = new HashMap<>();
    private int nextVarSlot = 0;

    public static Bytecode compile(JqExpr expr) {
        var compiler = new Compiler();
        compiler.compileExpr(expr);
        compiler.emit(OUTPUT);
        compiler.emit(HALT);
        compiler.peepholeOptimize();
        return compiler.build();
    }

    private Bytecode build() {
        return new Bytecode(
                code.toArray(new Bytecode.Instruction[0]),
                constants.toArray(new JqValue[0]),
                names.toArray(new String[0]),
                subExprs.toArray(new JqExpr[0]),
                callInfos.toArray(new Bytecode.CallInfo[0]),
                nextVarSlot
        );
    }

    private int allocSlot(String name) {
        return varSlots.computeIfAbsent(name, n -> nextVarSlot++);
    }

    private int addConstant(JqValue value) {
        int idx = constants.indexOf(value);
        if (idx >= 0) return idx;
        constants.add(value);
        return constants.size() - 1;
    }

    private int addName(String name) {
        return nameIndex.computeIfAbsent(name, n -> {
            names.add(n);
            return names.size() - 1;
        });
    }

    private int addSubExpr(JqExpr expr) {
        subExprs.add(expr);
        return subExprs.size() - 1;
    }

    private int addCallInfo(String name, int arity, List<JqExpr> args) {
        callInfos.add(new Bytecode.CallInfo(name, arity, args));
        return callInfos.size() - 1;
    }

    private void emit(int op) {
        code.add(new Bytecode.Instruction(op));
    }

    private void emit(int op, int arg1) {
        code.add(new Bytecode.Instruction(op, arg1));
    }

    private void emit(int op, int arg1, int arg2) {
        code.add(new Bytecode.Instruction(op, arg1, arg2));
    }

    private int emitPlaceholder(int op) {
        int pc = currentPc();
        code.add(new Bytecode.Instruction(op, 0));
        return pc;
    }

    private void patch(int pc, int target) {
        var old = code.get(pc);
        code.set(pc, new Bytecode.Instruction(old.op(), target, old.arg2()));
    }

    private int currentPc() {
        return code.size();
    }

    private void compileExpr(JqExpr expr) {
        switch (expr) {
            case IdentityExpr _ -> emit(LOAD_INPUT);

            case LiteralExpr lit -> {
                JqValue v = lit.value();
                if (v instanceof JqNull) emit(PUSH_NULL);
                else if (v.equals(JqBoolean.TRUE)) emit(PUSH_TRUE);
                else if (v.equals(JqBoolean.FALSE)) emit(PUSH_FALSE);
                else emit(PUSH_CONST, addConstant(v));
            }

            case DotFieldExpr df -> {
                emit(LOAD_INPUT);
                emit(DOT_FIELD, addName(df.field()));
            }

            case PipeExpr pipe -> {
                // Optimization: .a.b -> DOT_FIELD2 (single instruction)
                if (pipe.left() instanceof DotFieldExpr df1 && pipe.right() instanceof DotFieldExpr df2) {
                    emit(LOAD_INPUT);
                    emit(Opcode.DOT_FIELD2, addName(df1.field()), addName(df2.field()));
                    break;
                }
                compileExpr(pipe.left());
                emit(SET_INPUT);
                compileExpr(pipe.right());
            }

            case CommaExpr comma -> {
                int forkPc = emitPlaceholder(FORK);
                compileExpr(comma.left());
                int jumpPc = emitPlaceholder(JUMP);
                patch(forkPc, currentPc());
                compileExpr(comma.right());
                patch(jumpPc, currentPc());
            }

            case IterateExpr iter -> {
                compileExpr(iter.expr());
                emit(EACH);
            }

            case IndexExpr idx -> {
                if (!modifiesInput(idx.expr()) && !modifiesInput(idx.index())
                        && !containsGenerator(idx.index())) {
                    compileExpr(idx.expr());
                    compileExpr(idx.index());
                    emit(INDEX);
                } else {
                    emitEvalAst(expr);
                }
            }

            case ArrayConstructExpr arr -> {
                if (arr.body() == null) {
                    emit(PUSH_CONST, addConstant(io.hyperfoil.tools.jjq.value.JqArray.EMPTY));
                } else if (tryEmitCollectSelectIterate(arr)) {
                    // Fused collect-select-iterate emitted
                } else if (tryEmitCollectIterate(arr)) {
                    // Fused collect-iterate emitted
                } else {
                    emit(COLLECT_BEGIN);
                    int forkPc = emitPlaceholder(FORK);
                    compileExpr(arr.body());
                    emit(COLLECT_ADD);
                    patch(forkPc, currentPc());
                    emit(COLLECT_END);
                }
            }

            case NegateExpr neg -> {
                compileExpr(neg.expr());
                emit(NEGATE);
            }

            case ArithmeticExpr arith -> {
                // Constant folding: if both sides are literals, compute at compile time
                JqValue folded = tryFoldArithmetic(arith);
                if (folded != null) {
                    emit(PUSH_CONST, addConstant(folded));
                } else if (!modifiesInput(arith.left()) && !modifiesInput(arith.right())
                        && !containsGenerator(arith.left()) && !containsGenerator(arith.right())) {
                    compileExpr(arith.left());
                    compileExpr(arith.right());
                    emit(switch (arith.op()) {
                        case ADD -> ADD;
                        case SUB -> SUB;
                        case MUL -> MUL;
                        case DIV -> DIV;
                        case MOD -> MOD;
                    });
                } else {
                    emitEvalAst(expr);
                }
            }

            case ComparisonExpr comp -> {
                JqValue folded = tryFoldComparison(comp);
                if (folded != null) {
                    if (folded.equals(JqBoolean.TRUE)) emit(PUSH_TRUE);
                    else emit(PUSH_FALSE);
                } else if (!modifiesInput(comp.left()) && !modifiesInput(comp.right())
                        && !containsGenerator(comp.left()) && !containsGenerator(comp.right())) {
                    compileExpr(comp.left());
                    compileExpr(comp.right());
                    emit(switch (comp.op()) {
                        case EQ -> Opcode.EQ;
                        case NEQ -> Opcode.NEQ;
                        case LT -> Opcode.LT;
                        case GT -> Opcode.GT;
                        case LE -> Opcode.LE;
                        case GE -> Opcode.GE;
                    });
                } else {
                    emitEvalAst(expr);
                }
            }

            case LogicalExpr log -> {
                if (!modifiesInput(log.left()) && !modifiesInput(log.right())
                        && !containsGenerator(log.left()) && !containsGenerator(log.right())) {
                    if (log.op() == JqExpr.LogicalExpr.Op.AND) {
                        compileExpr(log.left());
                        int jumpFalse1 = emitPlaceholder(JUMP_IF_FALSE);
                        compileExpr(log.right());
                        int jumpFalse2 = emitPlaceholder(JUMP_IF_FALSE);
                        emit(PUSH_TRUE);
                        int jumpEnd = emitPlaceholder(JUMP);
                        int falsePc = currentPc();
                        patch(jumpFalse1, falsePc);
                        patch(jumpFalse2, falsePc);
                        emit(PUSH_FALSE);
                        patch(jumpEnd, currentPc());
                    } else { // OR
                        compileExpr(log.left());
                        int jumpTrue1 = emitPlaceholder(JUMP_IF_TRUE);
                        compileExpr(log.right());
                        int jumpTrue2 = emitPlaceholder(JUMP_IF_TRUE);
                        emit(PUSH_FALSE);
                        int jumpEnd = emitPlaceholder(JUMP);
                        int truePc = currentPc();
                        patch(jumpTrue1, truePc);
                        patch(jumpTrue2, truePc);
                        emit(PUSH_TRUE);
                        patch(jumpEnd, currentPc());
                    }
                } else {
                    emitEvalAst(expr);
                }
            }

            case NotExpr notExpr -> {
                compileExpr(notExpr.expr());
                emit(NOT);
            }

            case IfExpr ifExpr -> {
                var endJumps = new ArrayList<Integer>();
                compileExpr(ifExpr.condition());
                int jumpFalse = emitPlaceholder(JUMP_IF_FALSE);
                compileExpr(ifExpr.thenBranch());
                endJumps.add(emitPlaceholder(JUMP));
                patch(jumpFalse, currentPc());

                for (var elif : ifExpr.elifs()) {
                    compileExpr(elif.condition());
                    jumpFalse = emitPlaceholder(JUMP_IF_FALSE);
                    compileExpr(elif.body());
                    endJumps.add(emitPlaceholder(JUMP));
                    patch(jumpFalse, currentPc());
                }

                if (ifExpr.elseBranch() != null) {
                    compileExpr(ifExpr.elseBranch());
                } else {
                    emit(LOAD_INPUT);
                }

                int endPc = currentPc();
                for (int jp : endJumps) patch(jp, endPc);
            }

            case TryCatchExpr tc -> {
                int tryBeginPc = emitPlaceholder(TRY_BEGIN);
                compileExpr(tc.tryExpr());
                emit(TRY_END);
                int jumpEnd = emitPlaceholder(JUMP);
                patch(tryBeginPc, currentPc());
                if (tc.catchExpr() != null) {
                    // Error message is on stack, set as input for catch
                    emit(SET_INPUT);
                    compileExpr(tc.catchExpr());
                } else {
                    emit(POP); // discard error, produce nothing
                    emit(BACKTRACK);
                }
                patch(jumpEnd, currentPc());
            }

            case VariableRefExpr vr -> {
                Integer slot = varSlots.get(vr.name());
                if (slot != null) {
                    emit(LOAD_SLOT, slot);
                } else {
                    emit(LOAD_VAR, addName(vr.name()));
                }
            }

            case VariableBindExpr vb -> {
                boolean exprModifiesInput = modifiesInput(vb.expr());
                if (exprModifiesInput) {
                    emit(LOAD_INPUT); // save current input on stack
                }
                compileExpr(vb.expr());
                int slot = allocSlot(vb.variable());
                boolean needsEnv = bodyUsesTreeWalker(vb.body());
                if (needsEnv) {
                    emit(PUSH_SCOPE);
                    emit(DUP);
                    emit(STORE_SLOT, slot);
                    emit(STORE_VAR, addName(vb.variable()));
                } else {
                    emit(STORE_SLOT, slot);
                }
                if (exprModifiesInput) {
                    // saved input is now on top of stack (expr result was consumed by STORE_SLOT/STORE_VAR)
                    emit(SET_INPUT); // restore original input
                }
                compileExpr(vb.body());
                if (needsEnv) {
                    emit(POP_SCOPE);
                }
            }

            case ReduceExpr red -> {
                // Try fused reduce for simple patterns: reduce .[] as $x (INIT; . OP $x)
                if (tryEmitFusedReduce(red)) break;

                // reduce .source as $x (init; update)
                int accSlot = allocSlot("_reduce_acc");
                int origSlot = allocSlot("_reduce_orig");
                int varSlot = allocSlot(red.variable());
                int accName = addName("_reduce_acc");
                int origInputName = addName("_reduce_orig");
                emit(LOAD_INPUT);
                emit(STORE_SLOT, origSlot); // save original input
                compileExpr(red.init());
                emit(STORE_SLOT, accSlot);
                int forkPc = emitPlaceholder(FORK); // catch source exhaustion
                // Restore original input for source evaluation
                emit(LOAD_SLOT, origSlot);
                emit(SET_INPUT);
                compileExpr(red.source());
                if (bodyUsesTreeWalker(red.update())) {
                    emit(DUP);
                    emit(STORE_SLOT, varSlot);
                    emit(PUSH_SCOPE);
                    emit(STORE_VAR, addName(red.variable()));
                } else {
                    emit(STORE_SLOT, varSlot);
                }
                // Set input = accumulator for update expression
                emit(LOAD_SLOT, accSlot);
                emit(SET_INPUT);
                compileExpr(red.update());
                if (bodyUsesTreeWalker(red.update())) {
                    emit(POP_SCOPE);
                }
                emit(STORE_SLOT, accSlot);
                emit(BACKTRACK); // get next source value
                patch(forkPc, currentPc());
                emit(LOAD_SLOT, accSlot);
            }

            case FuncCallExpr fc -> {
                // Inline common zero-arg builtins
                if (fc.args().isEmpty()) {
                    int inlined = inlineBuiltin(fc.name());
                    if (inlined >= 0) {
                        emit(LOAD_INPUT);
                        emit(inlined);
                        break;
                    }
                }
                // Native select(cond): condition ? pass-through : backtrack
                if ("select".equals(fc.name()) && fc.args().size() == 1) {
                    JqExpr cond = fc.args().getFirst();
                    if (!modifiesInput(cond)) {
                        compileExpr(cond);
                        int jumpTrue = emitPlaceholder(JUMP_IF_TRUE);
                        emit(BACKTRACK);
                        patch(jumpTrue, currentPc());
                        emit(LOAD_INPUT);
                        break;
                    }
                }
                int ci = addCallInfo(fc.name(), fc.args().size(), fc.args());
                emit(CALL_FUNC, ci);
            }

            case FuncDefExpr _ -> emitEvalAst(expr);

            case StringInterpolationExpr _ -> emitEvalAst(expr);

            case AlternativeExpr alt -> {
                if (isSingleOutputExpr(alt.left()) && isSingleOutputExpr(alt.right())
                        && !modifiesInput(alt.left()) && !modifiesInput(alt.right())) {
                    compileExpr(alt.left());
                    emit(DUP);
                    int jumpTrue = emitPlaceholder(JUMP_IF_TRUE);
                    emit(POP);
                    compileExpr(alt.right());
                    patch(jumpTrue, currentPc());
                } else {
                    emitEvalAst(expr);
                }
            }

            case RecurseExpr _ -> emitEvalAst(expr);

            case SliceExpr _ -> emitEvalAst(expr);

            case ObjectConstructExpr _ -> emitEvalAst(expr);

            case UpdateExpr _ -> emitEvalAst(expr);
            case AssignExpr _ -> emitEvalAst(expr);
            case AddAssignExpr _ -> emitEvalAst(expr);
            case SubAssignExpr _ -> emitEvalAst(expr);
            case MulAssignExpr _ -> emitEvalAst(expr);
            case DivAssignExpr _ -> emitEvalAst(expr);
            case ModAssignExpr _ -> emitEvalAst(expr);
            case AlternativeAssignExpr _ -> emitEvalAst(expr);

            case LabelExpr _ -> emitEvalAst(expr);
            case BreakExpr _ -> emitEvalAst(expr);
            case FormatExpr _ -> emitEvalAst(expr);
            case PathExpr _ -> emitEvalAst(expr);
            case ForeachExpr _ -> emitEvalAst(expr);
            case OptionalExpr _ -> emitEvalAst(expr);
        }
    }

    /**
     * Try to emit a fused COLLECT_ITERATE for [.[] | simple-expr].
     * Pattern: ArrayConstructExpr whose body is PipeExpr(IterateExpr(IdentityExpr), body)
     * where body is a single-output expression (no generators/backtracking).
     */
    private boolean tryEmitCollectIterate(ArrayConstructExpr arr) {
        JqExpr body = arr.body();
        if (!(body instanceof PipeExpr pipe)) return false;
        if (!(pipe.left() instanceof IterateExpr iter)) return false;
        if (!(iter.expr() instanceof IdentityExpr)) return false;
        JqExpr mapBody = pipe.right();
        if (!isSingleOutputExpr(mapBody)) return false;

        // Emit: LOAD_INPUT, COLLECT_ITERATE <bodyLen>, <body bytecode>
        emit(LOAD_INPUT);
        int collectPc = currentPc();
        emitPlaceholder(COLLECT_ITERATE); // will patch with body length
        int bodyStart = currentPc();
        compileExpr(mapBody);
        int bodyLen = currentPc() - bodyStart;
        patch(collectPc, bodyLen);
        return true;
    }

    /**
     * Try to emit a fused COLLECT_SELECT_ITERATE for [.[] | select(cond) | expr].
     * Pattern: ArrayConstructExpr body is PipeExpr chain with IterateExpr, select, and single-output expr.
     */
    private boolean tryEmitCollectSelectIterate(ArrayConstructExpr arr) {
        JqExpr body = arr.body();
        // Match: .[] | select(cond) | expr  — a PipeExpr chain
        // After parsing, this is PipeExpr(PipeExpr(.[] , select(cond)), expr)
        if (!(body instanceof PipeExpr outerPipe)) return false;
        if (!(outerPipe.left() instanceof PipeExpr innerPipe)) return false;
        if (!(innerPipe.left() instanceof IterateExpr iter)) return false;
        if (!(iter.expr() instanceof IdentityExpr)) return false;
        // innerPipe.right() must be select(cond)
        if (!(innerPipe.right() instanceof FuncCallExpr selectCall)) return false;
        if (!"select".equals(selectCall.name()) || selectCall.args().size() != 1) return false;
        JqExpr cond = selectCall.args().getFirst();
        JqExpr mapExpr = outerPipe.right();
        // Both condition and map expression must be single-output and not modify input
        if (!isSingleOutputExpr(cond) || modifiesInput(cond)) return false;
        if (!isSingleOutputExpr(mapExpr) || modifiesInput(mapExpr)) return false;

        // Emit: LOAD_INPUT, COLLECT_SELECT_ITERATE <bodyLen>, <cond bytecode>, JUMP_IF_FALSE <skip>, <mapExpr bytecode>
        emit(LOAD_INPUT);
        int collectPc = currentPc();
        emitPlaceholder(COLLECT_SELECT_ITERATE);
        int bodyStart = currentPc();
        // Compile condition
        compileExpr(cond);
        int jumpFalsePc = emitPlaceholder(JUMP_IF_FALSE);
        // Compile map expression
        compileExpr(mapExpr);
        int bodyLen = currentPc() - bodyStart;
        patch(collectPc, bodyLen);
        // Patch JUMP_IF_FALSE to point to end of body (bodyStart + bodyLen)
        patch(jumpFalsePc, bodyStart + bodyLen);
        return true;
    }

    /**
     * Try to emit a fused REDUCE_ITERATE for reduce .[] as $x (INIT; . OP $x)
     * where INIT is a literal and OP is +, -, or *.
     */
    private boolean tryEmitFusedReduce(ReduceExpr red) {
        if (!(red.source() instanceof IterateExpr iter)) return false;
        if (!(iter.expr() instanceof IdentityExpr)) return false;
        if (!(red.init() instanceof LiteralExpr initLit)) return false;
        if (!(red.update() instanceof ArithmeticExpr arith)) return false;
        // update must be: . OP $x
        if (!(arith.left() instanceof IdentityExpr)) return false;
        if (!(arith.right() instanceof VariableRefExpr vr)) return false;
        if (!vr.name().equals(red.variable())) return false;
        int opCode = switch (arith.op()) {
            case ADD -> 0;
            case SUB -> 1;
            case MUL -> 2;
            default -> -1;
        };
        if (opCode < 0) return false;

        int initIdx = addConstant(initLit.value());
        emit(LOAD_INPUT);
        emit(REDUCE_ITERATE, initIdx, opCode);
        return true;
    }

    /**
     * Check if an expression produces exactly one output per input
     * (no generators, no backtracking, no pipes that change input).
     */
    private boolean isSingleOutputExpr(JqExpr expr) {
        return switch (expr) {
            case IdentityExpr _ -> true;
            case LiteralExpr _ -> true;
            case VariableRefExpr _ -> true;
            case DotFieldExpr _ -> true;
            case NegateExpr n -> isSingleOutputExpr(n.expr());
            case ArithmeticExpr a -> isSingleOutputExpr(a.left()) && isSingleOutputExpr(a.right());
            case ComparisonExpr c -> isSingleOutputExpr(c.left()) && isSingleOutputExpr(c.right());
            case NotExpr n -> isSingleOutputExpr(n.expr());
            case FuncCallExpr fc -> fc.args().isEmpty() && inlineBuiltin(fc.name()) >= 0
                    && !isFilterBuiltin(fc.name());
            default -> false;
        };
    }

    private boolean bodyUsesTreeWalker(JqExpr expr) {
        return switch (expr) {
            case IdentityExpr _ -> false;
            case LiteralExpr _ -> false;
            case VariableRefExpr _ -> false;
            case DotFieldExpr _ -> false;
            case NegateExpr n -> bodyUsesTreeWalker(n.expr());
            case ArithmeticExpr a -> modifiesInput(a.left()) || modifiesInput(a.right())
                    || containsGenerator(a.left()) || containsGenerator(a.right())
                    || bodyUsesTreeWalker(a.left()) || bodyUsesTreeWalker(a.right());
            case ComparisonExpr c -> modifiesInput(c.left()) || modifiesInput(c.right())
                    || containsGenerator(c.left()) || containsGenerator(c.right())
                    || bodyUsesTreeWalker(c.left()) || bodyUsesTreeWalker(c.right());
            case PipeExpr p -> bodyUsesTreeWalker(p.left()) || bodyUsesTreeWalker(p.right());
            case ArrayConstructExpr a -> a.body() != null && bodyUsesTreeWalker(a.body());
            case IterateExpr i -> bodyUsesTreeWalker(i.expr());
            case CommaExpr c -> bodyUsesTreeWalker(c.left()) || bodyUsesTreeWalker(c.right());
            case IfExpr i -> bodyUsesTreeWalker(i.condition()) || bodyUsesTreeWalker(i.thenBranch())
                    || (i.elseBranch() != null && bodyUsesTreeWalker(i.elseBranch()));
            case NotExpr n -> bodyUsesTreeWalker(n.expr());
            case LogicalExpr l -> modifiesInput(l.left()) || modifiesInput(l.right())
                    || containsGenerator(l.left()) || containsGenerator(l.right())
                    || bodyUsesTreeWalker(l.left()) || bodyUsesTreeWalker(l.right());
            case ReduceExpr r -> bodyUsesTreeWalker(r.source()) || bodyUsesTreeWalker(r.init()) || bodyUsesTreeWalker(r.update());
            case VariableBindExpr vb -> bodyUsesTreeWalker(vb.expr()) || bodyUsesTreeWalker(vb.body());
            case FuncCallExpr fc -> fc.args().isEmpty() && inlineBuiltin(fc.name()) >= 0 ? false : true;
            case TryCatchExpr tc -> bodyUsesTreeWalker(tc.tryExpr()) || (tc.catchExpr() != null && bodyUsesTreeWalker(tc.catchExpr()));
            case AlternativeExpr a -> modifiesInput(a.left()) || modifiesInput(a.right())
                    || bodyUsesTreeWalker(a.left()) || bodyUsesTreeWalker(a.right());
            case IndexExpr i -> modifiesInput(i.expr()) || modifiesInput(i.index())
                    || bodyUsesTreeWalker(i.expr()) || bodyUsesTreeWalker(i.index());
            default -> true; // EVAL_AST, CALL_FUNC, etc.
        };
    }

    private void emitEvalAst(JqExpr expr) {
        int idx = addSubExpr(expr);
        emit(EVAL_AST, idx);
    }

    private JqValue tryFoldArithmetic(ArithmeticExpr arith) {
        if (arith.left() instanceof LiteralExpr ll && arith.right() instanceof LiteralExpr rl) {
            try {
                return switch (arith.op()) {
                    case ADD -> ll.value().add(rl.value());
                    case SUB -> ll.value().subtract(rl.value());
                    case MUL -> ll.value().multiply(rl.value());
                    case DIV -> ll.value().divide(rl.value());
                    case MOD -> ll.value().modulo(rl.value());
                };
            } catch (Exception e) {
                return null; // can't fold, compile normally
            }
        }
        return null;
    }

    private JqValue tryFoldComparison(ComparisonExpr comp) {
        if (comp.left() instanceof LiteralExpr ll && comp.right() instanceof LiteralExpr rl) {
            try {
                boolean result = switch (comp.op()) {
                    case EQ -> ll.value().equals(rl.value());
                    case NEQ -> !ll.value().equals(rl.value());
                    case LT -> ll.value().compareTo(rl.value()) < 0;
                    case GT -> ll.value().compareTo(rl.value()) > 0;
                    case LE -> ll.value().compareTo(rl.value()) <= 0;
                    case GE -> ll.value().compareTo(rl.value()) >= 0;
                };
                return JqBoolean.of(result);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private void peepholeOptimize() {
        for (int i = 0; i < code.size() - 1; i++) {
            var curr = code.get(i);
            var next = code.get(i + 1);

            // LOAD_INPUT followed by SET_INPUT is a no-op (sets input to itself)
            if (curr.op() == LOAD_INPUT && next.op() == SET_INPUT) {
                if (!isJumpTarget(i) && !isJumpTarget(i + 1)) {
                    code.set(i, new Bytecode.Instruction(Opcode.JUMP, i + 2));
                    code.set(i + 1, new Bytecode.Instruction(Opcode.JUMP, i + 2));
                }
            }

            // SET_INPUT followed by LOAD_INPUT → SET_INPUT_PEEK (value stays on stack)
            if (curr.op() == SET_INPUT && next.op() == LOAD_INPUT) {
                if (!isJumpTarget(i) && !isJumpTarget(i + 1)) {
                    code.set(i, new Bytecode.Instruction(SET_INPUT_PEEK));
                    code.set(i + 1, new Bytecode.Instruction(Opcode.JUMP, i + 2));
                }
            }
        }
    }

    private boolean isJumpTarget(int pc) {
        for (var inst : code) {
            if ((inst.op() == FORK || inst.op() == JUMP || inst.op() == JUMP_IF_TRUE
                    || inst.op() == JUMP_IF_FALSE || inst.op() == TRY_BEGIN)
                    && inst.arg1() == pc) {
                return true;
            }
        }
        return false;
    }

    private int inlineBuiltin(String name) {
        return switch (name) {
            case "length" -> BUILTIN_LENGTH;
            case "type" -> BUILTIN_TYPE;
            case "keys" -> BUILTIN_KEYS;
            case "values" -> BUILTIN_VALUES;
            case "not" -> BUILTIN_NOT;
            case "empty" -> BUILTIN_EMPTY;
            case "tostring" -> BUILTIN_TOSTRING;
            case "tonumber" -> BUILTIN_TONUMBER;
            case "add" -> BUILTIN_ADD;
            case "reverse" -> BUILTIN_REVERSE;
            case "sort" -> BUILTIN_SORT;
            case "min" -> BUILTIN_MIN;
            case "max" -> BUILTIN_MAX;
            case "flatten" -> BUILTIN_FLATTEN;
            case "unique" -> BUILTIN_UNIQUE;
            case "floor" -> BUILTIN_FLOOR;
            case "ceil" -> BUILTIN_CEIL;
            case "round" -> BUILTIN_ROUND;
            case "abs" -> BUILTIN_ABS;
            case "tojson" -> BUILTIN_TOJSON;
            case "fromjson" -> BUILTIN_FROMJSON;
            default -> -1;
        };
    }

    /** Builtins that can produce 0 outputs (filters) — not safe for COLLECT_ITERATE single-output path */
    private boolean isFilterBuiltin(String name) {
        return "values".equals(name) || "empty".equals(name);
    }

    private boolean containsGenerator(JqExpr expr) {
        return switch (expr) {
            case CommaExpr _ -> true;
            case IterateExpr _ -> true;
            case NegateExpr n -> containsGenerator(n.expr());
            case ArithmeticExpr a -> containsGenerator(a.left()) || containsGenerator(a.right());
            case IndexExpr i -> containsGenerator(i.expr()) || containsGenerator(i.index());
            default -> false;
        };
    }

    private boolean modifiesInput(JqExpr expr) {
        return switch (expr) {
            case IdentityExpr _ -> false;
            case LiteralExpr _ -> false;
            case VariableRefExpr _ -> false;
            case DotFieldExpr _ -> false;
            case NegateExpr n -> modifiesInput(n.expr());
            case IndexExpr i -> modifiesInput(i.expr()) || modifiesInput(i.index());
            case IterateExpr i -> modifiesInput(i.expr());
            case CommaExpr c -> modifiesInput(c.left()) || modifiesInput(c.right());
            case ArithmeticExpr a -> modifiesInput(a.left()) || modifiesInput(a.right());
            case ComparisonExpr c -> modifiesInput(c.left()) || modifiesInput(c.right());
            case ArrayConstructExpr a -> a.body() != null && modifiesInput(a.body());
            case PipeExpr _ -> true;
            default -> true;
        };
    }
}

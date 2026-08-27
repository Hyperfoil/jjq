package io.hyperfoil.tools.jjq.vm;

import io.hyperfoil.tools.jjq.ast.JqExpr;
import io.hyperfoil.tools.jjq.builtin.BuiltinRegistry;
import io.hyperfoil.tools.jjq.evaluator.*;
import io.hyperfoil.tools.jjq.value.*;

import java.util.ArrayList;
import java.util.List;

import static io.hyperfoil.tools.jjq.vm.Opcode.*;

public final class VirtualMachine {
    private static final int INIT_STACK = 64;
    private static final int INIT_BT = 64;
    private static final int INIT_TRY = 16;
    private static final int INIT_COLLECT = 16;

    // Cached JqString singletons for type names (avoids allocation per `type` call)
    private static final JqString TYPE_NULL = JqString.of("null");
    private static final JqString TYPE_BOOLEAN = JqString.of("boolean");
    private static final JqString TYPE_NUMBER = JqString.of("number");
    private static final JqString TYPE_STRING = JqString.of("string");
    private static final JqString TYPE_ARRAY = JqString.of("array");
    private static final JqString TYPE_OBJECT = JqString.of("object");

    private enum ProgramShape { IDENTITY, FIELD_ACCESS, FIELD_ACCESS2, PIPE_FIELD_ARITH, GENERAL }

    private final Bytecode bytecode;
    private final BuiltinRegistry builtins;
    private final ExprEvaluator treeWalker;
    private final ProgramShape shape;
    private final boolean needsEnv;
    private final boolean needsMutableEnv;
    private final boolean[] uniqueLayouts; // per object layout: true if all name indices are distinct
    private final String fastField1;  // cached for FIELD_ACCESS / FIELD_ACCESS2 / PIPE_FIELD_ARITH
    private final String fastField2;  // cached for FIELD_ACCESS2
    private final JqValue fastConst;  // cached for PIPE_FIELD_ARITH
    private final int fastArithOp;    // cached for PIPE_FIELD_ARITH

    // Pre-allocated VM state (reused across executions)
    private JqValue[] stack;
    private int sp;
    private BacktrackPoint[] btStack;
    private int btp;
    private TryPoint[] tryStack;
    private int tp;
    private List<JqValue>[] collectStack;
    private int cp;
    private JqValue input;
    private Environment env;
    private int pc;
    private boolean halted;
    // Indexed variable slots
    private JqValue[] varSlots;
    // Output collection state
    private boolean singleOutputMode;
    private JqValue firstOutput;
    private ArrayList<JqValue> multiOutputs;
    // Reusable scratch array for BUILD_OBJECT / STRING_CONCAT (avoids per-op allocation)
    private JqValue[] scratchValues;
    // Reusable StringBuilder for STRING_CONCAT (avoids per-interpolation allocation)
    private final StringBuilder concatBuffer = new StringBuilder(64);

    private static final class BacktrackPoint {
        int pc, sp, tryDepth, collectDepth, iterIndex;
        JqValue input, pushValue;
        Environment env;
        List<JqValue> iterItems; // non-null for iterator-based points

        void set(int pc, int sp, JqValue input, Environment env,
                 JqValue pushValue, int tryDepth, int collectDepth) {
            this.pc = pc; this.sp = sp; this.input = input; this.env = env;
            this.pushValue = pushValue; this.tryDepth = tryDepth; this.collectDepth = collectDepth;
            this.iterItems = null;
        }

        void setIterator(int pc, int sp, JqValue input, Environment env,
                         int tryDepth, int collectDepth, List<JqValue> items, int startIndex) {
            this.pc = pc; this.sp = sp; this.input = input; this.env = env;
            this.pushValue = null; this.tryDepth = tryDepth; this.collectDepth = collectDepth;
            this.iterItems = items; this.iterIndex = startIndex;
        }

        void clear() { input = null; env = null; pushValue = null; iterItems = null; }
    }
    private static final class TryPoint {
        int catchPc, btDepth;
        void set(int catchPc, int btDepth) {
            this.catchPc = catchPc;
            this.btDepth = btDepth;
        }
    }

    @SuppressWarnings("unchecked")
    public VirtualMachine(Bytecode bytecode, BuiltinRegistry builtins) {
        this.bytecode = bytecode;
        this.builtins = builtins;
        this.treeWalker = ExprEvaluator.create(builtins);
        this.stack = new JqValue[INIT_STACK];
        this.btStack = new BacktrackPoint[INIT_BT];
        for (int i = 0; i < INIT_BT; i++) btStack[i] = new BacktrackPoint();
        this.tryStack = new TryPoint[INIT_TRY];
        for (int i = 0; i < INIT_TRY; i++) tryStack[i] = new TryPoint();
        this.collectStack = new List[INIT_COLLECT];
        for (int i = 0; i < INIT_COLLECT; i++) collectStack[i] = new ArrayList<>();
        this.varSlots = bytecode.varSlotCount() > 0 ? new JqValue[bytecode.varSlotCount()] : null;
        this.scratchValues = new JqValue[computeMaxScratchSize(bytecode)];
        this.shape = detectShape();
        this.needsEnv = detectNeedsEnv();
        this.needsMutableEnv = detectNeedsMutableEnv();
        this.uniqueLayouts = computeUniqueLayouts();

        // Cache field names for fast-path shapes
        if (shape == ProgramShape.FIELD_ACCESS) {
            fastField1 = bytecode.name(bytecode.get(1).arg1());
            fastField2 = null;
            fastConst = null;
            fastArithOp = 0;
        } else if (shape == ProgramShape.FIELD_ACCESS2) {
            var inst = bytecode.get(1);
            fastField1 = bytecode.name(inst.arg1());
            fastField2 = bytecode.name(inst.arg2());
            fastConst = null;
            fastArithOp = 0;
        } else if (shape == ProgramShape.PIPE_FIELD_ARITH) {
            // Pattern: LOAD_INPUT(0) DOT_FIELD(1) SET_INPUT_PEEK(2) NOP(3) PUSH_CONST(4) ARITH(5) OUTPUT(6) HALT(7)
            fastField1 = bytecode.name(bytecode.arg1s()[1]);
            fastField2 = null;
            fastConst = bytecode.constant(bytecode.arg1s()[4]);
            fastArithOp = bytecode.ops()[5];
        } else {
            fastField1 = null;
            fastField2 = null;
            fastConst = null;
            fastArithOp = 0;
        }
    }

    /** Scan bytecode for max BUILD_OBJECT/STRING_CONCAT operand count to pre-size scratch array. */
    private static int computeMaxScratchSize(Bytecode bc) {
        int max = 0;
        int[] ops = bc.ops();
        int[] arg1s = bc.arg1s();
        for (int i = 0; i < ops.length; i++) {
            if (ops[i] == Opcode.BUILD_OBJECT || ops[i] == Opcode.STRING_CONCAT) {
                max = Math.max(max, arg1s[i]);
            }
        }
        return Math.max(max, 4); // minimum 4 to avoid zero-length edge cases
    }

    private ProgramShape detectShape() {
        if (bytecode.size() < 3) return ProgramShape.GENERAL;

        // Identity: LOAD_INPUT, OUTPUT, HALT
        if (bytecode.size() == 3
                && bytecode.get(0).op() == Opcode.LOAD_INPUT
                && bytecode.get(1).op() == Opcode.OUTPUT
                && bytecode.get(2).op() == Opcode.HALT) {
            return ProgramShape.IDENTITY;
        }

        if (bytecode.size() == 4
                && bytecode.get(0).op() == Opcode.LOAD_INPUT
                && bytecode.get(2).op() == Opcode.OUTPUT
                && bytecode.get(3).op() == Opcode.HALT) {
            // Field access: LOAD_INPUT, DOT_FIELD, OUTPUT, HALT
            if (bytecode.get(1).op() == Opcode.DOT_FIELD) {
                return ProgramShape.FIELD_ACCESS;
            }
            // Two-level field: LOAD_INPUT, DOT_FIELD2, OUTPUT, HALT
            if (bytecode.get(1).op() == Opcode.DOT_FIELD2) {
                return ProgramShape.FIELD_ACCESS2;
            }
        }

        // Pipe field arith: LOAD_INPUT, DOT_FIELD, SET_INPUT_PEEK, NOP, PUSH_CONST, ARITH, OUTPUT, HALT
        if (bytecode.size() == 8) {
            int[] ops = bytecode.ops();
            if (ops[0] == Opcode.LOAD_INPUT && ops[1] == Opcode.DOT_FIELD
                    && ops[2] == Opcode.SET_INPUT_PEEK && ops[3] == Opcode.NOP
                    && ops[4] == Opcode.PUSH_CONST && isArithOp(ops[5])
                    && ops[6] == Opcode.OUTPUT && ops[7] == Opcode.HALT) {
                return ProgramShape.PIPE_FIELD_ARITH;
            }
        }

        return ProgramShape.GENERAL;
    }

    private static boolean isArithOp(int op) {
        return op == Opcode.ADD || op == Opcode.SUB || op == Opcode.MUL || op == Opcode.DIV || op == Opcode.MOD;
    }

    private boolean detectNeedsEnv() {
        for (int i = 0; i < bytecode.size(); i++) {
            switch (bytecode.get(i).op()) {
                case PUSH_SCOPE, POP_SCOPE, LOAD_VAR, STORE_VAR, CALL_FUNC, EVAL_AST:
                    return true;
                default:
                    break;
            }
        }
        return false;
    }

    /**
     * Detect whether the program needs a mutable (freshly allocated) Environment.
     * Programs that only use CALL_FUNC/EVAL_AST (builtin function calls) can share
     * the immutable {@link Environment#EMPTY} singleton. Programs that use
     * PUSH_SCOPE/POP_SCOPE/LOAD_VAR/STORE_VAR need a fresh mutable Environment
     * because they create child scopes and write variables.
     */
    private boolean detectNeedsMutableEnv() {
        for (int i = 0; i < bytecode.size(); i++) {
            switch (bytecode.get(i).op()) {
                case PUSH_SCOPE, POP_SCOPE, LOAD_VAR, STORE_VAR:
                    return true;
                default:
                    break;
            }
        }
        return false;
    }

    /** Pre-compute which object layouts have all-distinct name indices. */
    private boolean[] computeUniqueLayouts() {
        int[][] layouts = bytecode.objectLayouts();
        if (layouts.length == 0) return new boolean[0];
        boolean[] unique = new boolean[layouts.length];
        for (int li = 0; li < layouts.length; li++) {
            int[] layout = layouts[li];
            boolean isUnique = true;
            for (int i = 0; i < layout.length && isUnique; i++) {
                for (int j = i + 1; j < layout.length; j++) {
                    if (layout[i] == layout[j]) { isUnique = false; break; }
                }
            }
            unique[li] = isUnique;
        }
        return unique;
    }

    public List<JqValue> execute(JqValue inputValue) {
        // Fast paths for trivial programs (bypass all VM overhead)
        switch (shape) {
            case IDENTITY:
                return List.of(inputValue);
            case FIELD_ACCESS:
                return List.of(fieldAccess(inputValue, fastField1));
            case FIELD_ACCESS2: {
                JqValue mid = fieldAccess(inputValue, fastField1);
                return List.of(fieldAccess(mid, fastField2));
            }
            case PIPE_FIELD_ARITH:
                return List.of(applyArith(fieldAccess(inputValue, fastField1), fastConst, fastArithOp));
            default:
                break;
        }
        return execute(inputValue, needsEnv ? (needsMutableEnv ? new Environment() : Environment.EMPTY) : null);
    }

    /**
     * Execute and return the first result directly, without wrapping in a List.
     * Zero-allocation for single-output programs (identity, field access, arithmetic, reduce, etc.).
     * Returns {@link JqNull#NULL} if the program produces no output.
     */
    public JqValue executeOne(JqValue inputValue) {
        switch (shape) {
            case IDENTITY:
                return inputValue;
            case FIELD_ACCESS:
                return fieldAccess(inputValue, fastField1);
            case FIELD_ACCESS2: {
                JqValue mid = fieldAccess(inputValue, fastField1);
                return fieldAccess(mid, fastField2);
            }
            case PIPE_FIELD_ARITH:
                return applyArith(fieldAccess(inputValue, fastField1), fastConst, fastArithOp);
            default:
                break;
        }
        return executeOne(inputValue, needsEnv ? (needsMutableEnv ? new Environment() : Environment.EMPTY) : null);
    }

    public JqValue executeOne(JqValue inputValue, Environment environment) {
        this.sp = 0;
        this.btp = 0;
        this.tp = 0;
        this.cp = 0;
        this.input = inputValue;
        this.env = environment;
        this.pc = 0;
        this.halted = false;
        this.singleOutputMode = true;
        this.firstOutput = null;
        this.multiOutputs = null;

        runLoop();

        return firstOutput != null ? firstOutput : JqNull.NULL;
    }

    private static JqString typeString(JqValue val) {
        return switch (val.type()) {
            case NULL -> TYPE_NULL;
            case BOOLEAN -> TYPE_BOOLEAN;
            case NUMBER -> TYPE_NUMBER;
            case STRING -> TYPE_STRING;
            case ARRAY -> TYPE_ARRAY;
            case OBJECT -> TYPE_OBJECT;
        };
    }

    private static JqValue applyArith(JqValue left, JqValue right, int op) {
        return switch (op) {
            case Opcode.ADD -> left.add(right);
            case Opcode.SUB -> left.subtract(right);
            case Opcode.MUL -> left.multiply(right);
            case Opcode.DIV -> left.divide(right);
            case Opcode.MOD -> left.modulo(right);
            default -> throw new JqException("Unsupported arithmetic op: " + op);
        };
    }

    private static JqValue fieldAccess(JqValue val, String field) {
        if (val instanceof JqObject obj) return obj.get(field);
        if (val instanceof JqNull) return JqNull.NULL;
        throw new JqException("Cannot index " + val.type().jqName() + " with string (\"" + field + "\")");
    }

    private static JqValue fieldAccess2(JqValue val, String f1, String f2) {
        return fieldAccess(fieldAccess(val, f1), f2);
    }

    public List<JqValue> execute(JqValue inputValue, Environment environment) {
        // Reset state (reuse pre-allocated arrays)
        this.sp = 0;
        this.btp = 0;
        this.tp = 0;
        this.cp = 0;
        this.input = inputValue;
        this.env = environment;
        this.pc = 0;
        this.halted = false;
        this.singleOutputMode = false;
        this.firstOutput = null;
        this.multiOutputs = null;

        runLoop();

        if (multiOutputs != null) return multiOutputs;
        if (firstOutput != null) return List.of(firstOutput);
        return List.of();
    }

    private void runLoop() {
        // Extract arrays to locals for zero-overhead dispatch
        final int[] ops = bytecode.ops();
        final int[] arg1s = bytecode.arg1s();
        final int[] arg2s = bytecode.arg2s();
        final JqValue[] consts = bytecode.constants();
        final String[] names = bytecode.names();
        final int[][] objLayouts = bytecode.objectLayouts();
        final int codeSize = ops.length;

        while (!halted && pc < codeSize) {
            final int curPc = pc;
            pc++;

            try {
                switch (ops[curPc]) {
                    case NOP -> {}
                    // Constants & Stack
                    case PUSH_CONST -> push(consts[arg1s[curPc]]);
                    case PUSH_NULL -> push(JqNull.NULL);
                    case PUSH_TRUE -> push(JqBoolean.TRUE);
                    case PUSH_FALSE -> push(JqBoolean.FALSE);
                    case POP -> pop();
                    case DUP -> push(peek());
                    case SWAP -> {
                        JqValue a = pop();
                        JqValue b = pop();
                        push(a);
                        push(b);
                    }

                    // Input register
                    case LOAD_INPUT -> push(input);
                    case SET_INPUT -> input = pop();
                    case SET_INPUT_PEEK -> input = peek();

                    // Field access
                    case DOT_FIELD -> push(fieldAccess(pop(), names[arg1s[curPc]]));

                    case INDEX -> {
                        JqValue index = pop();
                        JqValue base = pop();
                        push(JqValues.indexValue(base, index));
                    }

                    case EACH -> {
                        JqValue val = pop();
                        if (val instanceof JqArray arr) {
                            var items = arr.arrayValue();
                            if (!items.isEmpty()) {
                                if (items.size() > 1) {
                                    btPushIterator(pc, sp, input, env, items, 1);
                                }
                                push(items.getFirst());
                            } else {
                                doBacktrack();
                            }
                        } else if (val instanceof JqObject obj) {
                            var values = obj.valuesAsList(); // zero-copy for array-backed objects
                            if (!values.isEmpty()) {
                                if (values.size() > 1) {
                                    btPushIterator(pc, sp, input, env, values, 1);
                                }
                                push(values.getFirst());
                            } else {
                                doBacktrack();
                            }
                        } else {
                            throw cannotIterate(val);
                        }
                    }

                    // Compound field access
                    case DOT_FIELD2 -> push(fieldAccess2(pop(), names[arg1s[curPc]], names[arg2s[curPc]]));

                    // Inlined builtins — dispatched to separate method to keep runLoop() small
                    // for JIT optimization (see issue #61, Norman Maurer's inlining article).
                    case BUILTIN_LENGTH, BUILTIN_TYPE, BUILTIN_KEYS, BUILTIN_VALUES,
                         BUILTIN_NOT, BUILTIN_EMPTY, BUILTIN_TOSTRING, BUILTIN_TONUMBER,
                         BUILTIN_ADD, BUILTIN_REVERSE, BUILTIN_SORT, BUILTIN_MIN, BUILTIN_MAX,
                         BUILTIN_FLATTEN, BUILTIN_UNIQUE, BUILTIN_FLOOR, BUILTIN_CEIL,
                         BUILTIN_ROUND, BUILTIN_ABS, BUILTIN_TOJSON, BUILTIN_FROMJSON ->
                        dispatchBuiltin(ops[curPc]);

                    // Arithmetic
                    case ADD -> { JqValue r = pop(); JqValue l = pop(); push(l.add(r)); }
                    case SUB -> { JqValue r = pop(); JqValue l = pop(); push(l.subtract(r)); }
                    case MUL -> { JqValue r = pop(); JqValue l = pop(); push(l.multiply(r)); }
                    case DIV -> { JqValue r = pop(); JqValue l = pop(); push(l.divide(r)); }
                    case MOD -> { JqValue r = pop(); JqValue l = pop(); push(l.modulo(r)); }
                    case NEGATE -> push(pop().negate());

                    // Comparison
                    case EQ -> { JqValue r = pop(); JqValue l = pop(); push(JqBoolean.of(l.equals(r))); }
                    case NEQ -> { JqValue r = pop(); JqValue l = pop(); push(JqBoolean.of(!l.equals(r))); }
                    case LT -> { JqValue r = pop(); JqValue l = pop(); push(JqBoolean.of(l.compareTo(r) < 0)); }
                    case GT -> { JqValue r = pop(); JqValue l = pop(); push(JqBoolean.of(l.compareTo(r) > 0)); }
                    case LE -> { JqValue r = pop(); JqValue l = pop(); push(JqBoolean.of(l.compareTo(r) <= 0)); }
                    case GE -> { JqValue r = pop(); JqValue l = pop(); push(JqBoolean.of(l.compareTo(r) >= 0)); }

                    // Logic
                    case NOT -> push(JqBoolean.of(!pop().isTruthy()));

                    // Control flow
                    case FORK -> btPush(arg1s[curPc], sp, input, env, null);

                    case JUMP -> pc = arg1s[curPc];

                    case JUMP_IF_TRUE -> { if (pop().isTruthy()) pc = arg1s[curPc]; }

                    case JUMP_IF_FALSE -> { if (!pop().isTruthy()) pc = arg1s[curPc]; }

                    case BACKTRACK -> doBacktrack();

                    case OUTPUT -> {
                        JqValue val = pop();
                        if (singleOutputMode) {
                            firstOutput = val;
                            halted = true;
                        } else {
                            if (firstOutput == null) {
                                firstOutput = val;
                            } else {
                                if (multiOutputs == null) {
                                    multiOutputs = new ArrayList<>();
                                    multiOutputs.add(firstOutput);
                                }
                                multiOutputs.add(val);
                            }
                            doBacktrack();
                        }
                    }

                    case HALT -> halted = true;

                    // Variables
                    case LOAD_VAR -> {
                        String name = names[arg1s[curPc]];
                        if ("ENV".equals(name)) {
                            push(buildEnvObject());
                        } else if ("__loc__".equals(name)) {
                            push(buildLocObject());
                        } else {
                            push(env.getVariable(name));
                        }
                    }

                    case STORE_VAR -> {
                        String name = names[arg1s[curPc]];
                        env.setVariable(name, pop());
                    }

                    // Indexed variable slots
                    case LOAD_SLOT -> { JqValue sv = varSlots[arg1s[curPc]]; push(sv != null ? sv : JqNull.NULL); }
                    case STORE_SLOT -> varSlots[arg1s[curPc]] = pop();

                    // Scope
                    case PUSH_SCOPE -> env = env.child();
                    case POP_SCOPE -> {
                        Environment parent = env.parent();
                        if (parent != null) env = parent;
                    }

                    // Collection (array construction)
                    // Lists are pre-allocated in collectStack — clear and reuse
                    // instead of allocating a new ArrayList per array construction.
                    case COLLECT_BEGIN -> {
                        if (cp >= collectStack.length) growCollectStack();
                        collectStack[cp++].clear();
                    }

                    case COLLECT_ADD -> {
                        collectStack[cp - 1].add(pop());
                        doBacktrack();
                    }

                    case COLLECT_END -> {
                        List<JqValue> items = collectStack[--cp];
                        // Copy items into an array — the ArrayList is reused on the next
                        // COLLECT_BEGIN, so we cannot pass it to ofTrusted(List) directly.
                        push(JqArray.ofTrusted(items.toArray(new JqValue[0])));
                    }

                    // Fused collect-iterate: [.[] | simple-expr]
                    case COLLECT_ITERATE -> {
                        JqValue val = pop(); // the array to iterate
                        int bodyLen = arg1s[curPc];
                        int bodyStart = pc;
                        pc = bodyStart + bodyLen; // skip body in main loop
                        if (val instanceof JqArray arr) {
                            push(collectIterateArray(arr, bodyStart, bodyLen));
                        } else if (val instanceof JqNull) {
                            push(JqArray.EMPTY);
                        } else {
                            throw cannotIterate(val);
                        }
                    }

                    // Fused collect-select-iterate: [.[] | select(cond) | expr]
                    case COLLECT_SELECT_ITERATE -> {
                        JqValue val = pop();
                        int bodyLen = arg1s[curPc];
                        int bodyStart = pc;
                        pc = bodyStart + bodyLen; // skip body in main loop
                        if (val instanceof JqArray arr) {
                            push(collectSelectIterateArray(arr, bodyStart, bodyLen));
                        } else if (val instanceof JqNull) {
                            push(JqArray.EMPTY);
                        } else {
                            throw cannotIterate(val);
                        }
                    }

                    // Fused reduce-iterate: reduce .[] as $x (init; . op $x)
                    case REDUCE_ITERATE -> {
                        JqValue val = pop(); // the array to iterate
                        JqValue initVal = consts[arg1s[curPc]];
                        int op = arg2s[curPc];
                        if (val instanceof JqArray arr) {
                            push(reduceIterateArray(arr, initVal, op));
                        } else if (val instanceof JqNull) {
                            push(initVal);
                        } else {
                            throw cannotIterate(val);
                        }
                    }

                    // Object construction (direct array via Builder, no LinkedHashMap)
                    case BUILD_OBJECT -> {
                        int count = arg1s[curPc];
                        int[] layout = objLayouts[arg2s[curPc]];
                        // Values are on stack with first field's value deepest.
                        // Pop all values into reusable scratch array (reverse order).
                        for (int i = count - 1; i >= 0; i--) {
                            scratchValues[i] = pop();
                        }
                        var builder = JqObject.builder(count);
                        if (uniqueLayouts[arg2s[curPc]]) {
                            for (int i = 0; i < count; i++) {
                                builder.putUnchecked(names[layout[i]], scratchValues[i]);
                            }
                        } else {
                            for (int i = 0; i < count; i++) {
                                builder.put(names[layout[i]], scratchValues[i]);
                            }
                        }
                        push(builder.build());
                    }

                    // String interpolation — reuses concatBuffer to avoid per-op StringBuilder allocation
                    case STRING_CONCAT -> {
                        int partCount = arg1s[curPc];
                        for (int i = partCount - 1; i >= 0; i--) {
                            scratchValues[i] = pop();
                        }
                        concatBuffer.setLength(0);
                        for (int i = 0; i < partCount; i++) {
                            JqValue v = scratchValues[i];
                            if (v instanceof JqString s) concatBuffer.append(s.stringValue());
                            else concatBuffer.append(v.toJsonString());
                        }
                        push(JqString.of(concatBuffer.toString()));
                    }

                    // Try-catch
                    case TRY_BEGIN -> {
                        if (tp >= tryStack.length) growTryStack();
                        tryStack[tp++].set(arg1s[curPc], btp);
                    }

                    case TRY_END -> tp--;

                    // Function calls (delegate to tree-walker via cached expr)
                    case CALL_FUNC -> {
                        var ci = bytecode.callInfo(arg1s[curPc]);
                        evalViaTreeWalker(ci.cachedExpr());
                    }

                    // Sub-expression evaluation (delegate to tree-walker)
                    case EVAL_AST -> {
                        JqExpr subExpr = bytecode.subExpr(arg1s[curPc]);
                        evalSubExpr(subExpr);
                    }
                }
            } catch (EmptyException e) {
                doBacktrack();
            } catch (JqException | JqTypeError e) {
                if (tp > 0) {
                    handleError(e);
                } else if (firstOutput != null || multiOutputs != null) {
                    // Already produced outputs — halt gracefully (matches jq behavior:
                    // generators can yield partial results before an uncaught error)
                    halted = true;
                    return;
                } else {
                    throw e;
                }
            }
        }
    }

    private void evalViaTreeWalker(JqExpr cachedExpr) {
        evalSubExpr(cachedExpr);
    }

    private void evalSubExpr(JqExpr subExpr) {
        var results = treeWalker.eval(subExpr, input, env);
        if (results.isEmpty()) {
            doBacktrack();
            return;
        }
        int nextPc = pc;
        for (int i = results.size() - 1; i >= 1; i--) {
            btPush(nextPc, sp, input, env, results.get(i));
        }
        push(results.getFirst());
    }

    private void push(JqValue val) {
        if (sp >= stack.length) stack = java.util.Arrays.copyOf(stack, stack.length * 2);
        stack[sp++] = val;
    }

    private JqValue pop() {
        return stack[--sp];
    }

    private JqValue peek() {
        return stack[sp - 1];
    }

    /**
     * Dispatch inlined builtin opcodes. Extracted from runLoop() to keep the main
     * dispatch loop small enough for C2 to optimize effectively (issue #61).
     * The JIT can inline this into runLoop() if it's hot, or optimize it separately
     * with its own register allocation budget.
     */
    private void dispatchBuiltin(int op) {
        JqValue val = pop();
        switch (op) {
            case BUILTIN_LENGTH -> {
                if (val instanceof JqNumber n) {
                    if (n.isNaN() || n.isInfinite()) push(JqNumber.of(Math.abs(n.doubleValue())));
                    else if (n.isIntegral()) push(JqNumber.of(Math.abs(n.longValue())));
                    else push(JqNumber.of(n.decimalValue().abs()));
                } else {
                    push(JqNumber.of(val.length()));
                }
            }
            case BUILTIN_TYPE -> push(typeString(val));
            case BUILTIN_KEYS -> {
                if (val instanceof JqObject obj) push(obj.sortedKeysAsArray());
                else if (val instanceof JqArray arr) {
                    var keys = new ArrayList<JqValue>(arr.arrayValue().size());
                    for (int i = 0; i < arr.arrayValue().size(); i++) keys.add(JqNumber.of(i));
                    push(JqArray.of(keys));
                } else throw builtinTypeError(val, "has no keys");
            }
            case BUILTIN_VALUES -> { if (val instanceof JqNull) doBacktrack(); else push(val); }
            case BUILTIN_NOT -> push(JqBoolean.of(!val.isTruthy()));
            case BUILTIN_EMPTY -> doBacktrack();
            case BUILTIN_TOSTRING -> push(val instanceof JqString ? val : JqString.of(val.toJsonString()));
            case BUILTIN_TONUMBER -> dispatchToNumber(val);
            case BUILTIN_ADD -> {
                if (val instanceof JqArray arr) {
                    var items = arr.arrayValue();
                    if (items.isEmpty()) { push(JqNull.NULL); return; }
                    JqValue result = items.getFirst();
                    for (int i = 1; i < items.size(); i++) result = result.add(items.get(i));
                    push(result);
                } else throw builtinTypeError(val, "is not iterable");
            }
            case BUILTIN_REVERSE -> {
                if (val instanceof JqArray arr) {
                    var list = new ArrayList<>(arr.arrayValue());
                    java.util.Collections.reverse(list);
                    push(JqArray.of(list));
                } else throw builtinTypeError(val, "cannot be reversed");
            }
            case BUILTIN_SORT -> {
                if (val instanceof JqArray arr) {
                    var list = new ArrayList<>(arr.arrayValue());
                    list.sort(JqValue::compareTo);
                    push(JqArray.of(list));
                } else throw builtinTypeError(val, "cannot be sorted");
            }
            case BUILTIN_MIN -> {
                if (val instanceof JqArray arr) {
                    var items = arr.arrayValue();
                    if (items.isEmpty()) { push(JqNull.NULL); return; }
                    JqValue min = items.getFirst();
                    for (int i = 1; i < items.size(); i++) if (items.get(i).compareTo(min) < 0) min = items.get(i);
                    push(min);
                } else throw builtinTypeError(val, "is not iterable");
            }
            case BUILTIN_MAX -> {
                if (val instanceof JqArray arr) {
                    var items = arr.arrayValue();
                    if (items.isEmpty()) { push(JqNull.NULL); return; }
                    JqValue max = items.getFirst();
                    for (int i = 1; i < items.size(); i++) if (items.get(i).compareTo(max) > 0) max = items.get(i);
                    push(max);
                } else throw builtinTypeError(val, "is not iterable");
            }
            case BUILTIN_FLATTEN -> {
                if (val instanceof JqArray arr) {
                    var flat = new ArrayList<JqValue>();
                    flattenDeep(arr, flat);
                    push(JqArray.of(flat));
                } else throw builtinTypeError(val, "cannot be flattened");
            }
            case BUILTIN_UNIQUE -> {
                if (val instanceof JqArray arr) {
                    var sorted = new ArrayList<>(arr.arrayValue());
                    sorted.sort(JqValue::compareTo);
                    var unique = new ArrayList<JqValue>();
                    JqValue prev = null;
                    for (JqValue item : sorted) {
                        if (prev == null || !prev.equals(item)) unique.add(item);
                        prev = item;
                    }
                    push(JqArray.of(unique));
                } else throw builtinTypeError(val, "cannot be uniquified");
            }
            case BUILTIN_FLOOR -> {
                if (val instanceof JqNumber n) push(JqNumber.of((long) Math.floor(n.doubleValue())));
                else throw builtinTypeError(val, "cannot be floored");
            }
            case BUILTIN_CEIL -> {
                if (val instanceof JqNumber n) push(JqNumber.of((long) Math.ceil(n.doubleValue())));
                else throw builtinTypeError(val, "cannot be ceiled");
            }
            case BUILTIN_ROUND -> {
                if (val instanceof JqNumber n) push(JqNumber.of(Math.round(n.doubleValue())));
                else throw builtinTypeError(val, "cannot be rounded");
            }
            case BUILTIN_ABS -> {
                if (val instanceof JqNumber n) {
                    if (n.isNaN() || n.isInfinite()) push(JqNumber.of(Math.abs(n.doubleValue())));
                    else if (n.isIntegral()) push(JqNumber.of(Math.abs(n.longValue())));
                    else push(JqNumber.of(n.decimalValue().abs()));
                } else if (val instanceof JqNull) push(JqNull.NULL);
                else push(val);
            }
            case BUILTIN_TOJSON -> push(JqString.of(JqValues.toJsonStringDepthLimited(val)));
            case BUILTIN_FROMJSON -> {
                if (val instanceof JqString s) {
                    try { push(JqValues.parseStrict(s.stringValue())); }
                    catch (IllegalArgumentException e) { throw new JqException(e.getMessage()); }
                } else throw builtinTypeError(val, "cannot be parsed as JSON");
            }
            default -> throw new JqException("Unknown builtin opcode: " + op);
        }
    }

    /** Extract tonumber logic — has try-catch which inflates bytecode. */
    private void dispatchToNumber(JqValue val) {
        if (val instanceof JqNumber) { push(val); return; }
        if (val instanceof JqString s) {
            String str = s.stringValue().trim();
            try {
                if (str.contains(".") || str.contains("e") || str.contains("E")) {
                    push(JqNumber.of(new java.math.BigDecimal(str)));
                } else {
                    push(JqNumber.of(Long.parseLong(str)));
                }
            } catch (NumberFormatException e) {
                throw new JqException("string (" + JqString.formatForError(s.stringValue()) + ") cannot be parsed as a number");
            }
            return;
        }
        throw new JqException(val.type().jqName() + " cannot be converted to number");
    }

    // ========================================================================
    //  Error factory methods — extracted from hot paths to reduce bytecode
    //  size (issue #61). String concatenation and toJsonString() calls are
    //  cold code that inflates the JIT's optimization budget.
    // ========================================================================

    private static JqException cannotIterate(JqValue val) {
        if (val instanceof JqNull) {
            return new JqException("Cannot iterate over null (null)");
        }
        return new JqException("Cannot iterate over " + val.type().jqName() + " (" + val.toJsonString() + ")");
    }

    private static JqException builtinTypeError(JqValue val, String message) {
        return new JqException(val.type().jqName() + " " + message);
    }

    private static JqObject buildEnvObject() {
        var map = new java.util.LinkedHashMap<String, JqValue>();
        System.getenv().forEach((k, v) -> map.put(k, JqString.of(v)));
        return JqObject.ofTrusted(map);
    }

    private static JqObject buildLocObject() {
        var map = new java.util.LinkedHashMap<String, JqValue>();
        map.put("file", JqString.of("<top-level>"));
        map.put("line", JqNumber.of(1));
        return JqObject.ofTrusted(map);
    }

    private void btPush(int targetPc, int savedSp, JqValue savedInput, Environment savedEnv, JqValue pushValue) {
        if (btp >= btStack.length) growBtStack();
        btStack[btp++].set(targetPc, savedSp, savedInput, savedEnv, pushValue, tp, cp);
    }

    private void btPushIterator(int targetPc, int savedSp, JqValue savedInput, Environment savedEnv,
                                List<JqValue> items, int startIndex) {
        if (btp >= btStack.length) growBtStack();
        btStack[btp++].setIterator(targetPc, savedSp, savedInput, savedEnv, tp, cp, items, startIndex);
    }

    private void growBtStack() {
        int oldLen = btStack.length;
        btStack = java.util.Arrays.copyOf(btStack, oldLen * 2);
        for (int i = oldLen; i < btStack.length; i++) btStack[i] = new BacktrackPoint();
    }

    private void growTryStack() {
        int oldLen = tryStack.length;
        tryStack = java.util.Arrays.copyOf(tryStack, oldLen * 2);
        for (int i = oldLen; i < tryStack.length; i++) tryStack[i] = new TryPoint();
    }

    @SuppressWarnings("unchecked")
    private void growCollectStack() {
        int oldLen = collectStack.length;
        collectStack = java.util.Arrays.copyOf(collectStack, oldLen * 2);
        for (int i = oldLen; i < collectStack.length; i++) collectStack[i] = new ArrayList<>();
    }

    private void doBacktrack() {
        while (btp > 0) {
            BacktrackPoint bp = btStack[btp - 1];
            sp = bp.sp;
            input = bp.input;
            env = bp.env;
            tp = bp.tryDepth;
            cp = bp.collectDepth;
            pc = bp.pc;
            if (bp.iterItems != null) {
                push(bp.iterItems.get(bp.iterIndex++));
                if (bp.iterIndex >= bp.iterItems.size()) {
                    btp--;
                    bp.clear();
                }
                return;
            }
            btp--;
            if (bp.pushValue != null) {
                push(bp.pushValue);
            }
            bp.clear();
            return;
        }
        halted = true;
    }

    private void handleError(Exception e) {
        TryPoint tryPoint = tryStack[--tp];
        int newBtp = tryPoint.btDepth;
        for (int i = newBtp; i < btp; i++) btStack[i].clear();
        btp = newBtp;
        // Preserve the original JqValue from error() if available
        if (e instanceof JqException je && je.jqValue() != null) {
            push(je.jqValue());
        } else {
            push(JqString.of(e.getMessage()));
        }
        pc = tryPoint.catchPc;
    }

    private static void flattenDeep(JqArray arr, List<JqValue> result) {
        // Iterative flattening to avoid stack overflow on deeply nested arrays
        var stack = new java.util.ArrayDeque<java.util.Iterator<JqValue>>();
        stack.push(arr.arrayValue().iterator());
        while (!stack.isEmpty()) {
            var iter = stack.peek();
            if (!iter.hasNext()) {
                stack.pop();
                continue;
            }
            JqValue v = iter.next();
            if (v instanceof JqArray inner) {
                stack.push(inner.arrayValue().iterator());
            } else {
                result.add(v);
            }
        }
    }

    private JqArray collectIterateArray(JqArray arr, int bodyStart, int bodyLen) {
        var items = arr.arrayValue();
        int size = items.size();
        if (size == 0) return JqArray.EMPTY;

        // Fast path: detect common body patterns to avoid stack/dispatch overhead
        // Pattern: LOAD_INPUT, PUSH_CONST <c>, <arith-op> (bodyLen == 3)
        if (bodyLen == 3) {
            final int[] ops = bytecode.ops();
            final int[] arg1s = bytecode.arg1s();
            final JqValue[] consts = bytecode.constants();
            if (ops[bodyStart] == Opcode.LOAD_INPUT && ops[bodyStart + 1] == Opcode.PUSH_CONST) {
                JqValue constVal = consts[arg1s[bodyStart + 1]];
                // For integer * integer, use raw long math
                if (ops[bodyStart + 2] == Opcode.MUL && constVal instanceof JqNumber cn && cn.isIntegral()) {
                    long multiplier = cn.longValue();
                    var result = new JqValue[size];
                    boolean allIntegral = true;
                    for (int i = 0; i < size; i++) {
                        JqValue item = items.get(i);
                        if (item instanceof JqNumber n && n.isIntegral()) {
                            result[i] = JqNumber.of(Math.multiplyExact(n.longValue(), multiplier));
                        } else {
                            allIntegral = false;
                            break;
                        }
                    }
                    if (allIntegral) return JqArray.ofTrusted(result);
                }
                // General arith: apply via JqValue methods (no stack needed)
                var result = new JqValue[size];
                for (int i = 0; i < size; i++) {
                    JqValue item = items.get(i);
                    result[i] = switch (ops[bodyStart + 2]) {
                        case ADD -> item.add(constVal);
                        case SUB -> item.subtract(constVal);
                        case MUL -> item.multiply(constVal);
                        case DIV -> item.divide(constVal);
                        case MOD -> item.modulo(constVal);
                        default -> collectIterateBodyGeneral(item, bodyStart, bodyLen);
                    };
                }
                return JqArray.ofTrusted(result);
            }
        }

        // General path: mini-interpreter with stack
        // Pre-ensure stack capacity for body execution to help JIT eliminate
        // bounds checks in push() during the inner loop
        if (sp + 16 >= stack.length) {
            stack = java.util.Arrays.copyOf(stack, Math.max(stack.length * 2, sp + 16));
        }
        var result = new JqValue[size];
        int savedSp = sp;
        JqValue savedInput = input;
        for (int i = 0; i < size; i++) {
            input = items.get(i);
            sp = savedSp;
            collectIterateBody(bodyStart, bodyLen);
            result[i] = pop();
        }
        sp = savedSp;
        input = savedInput;
        return JqArray.ofTrusted(result);
    }

    private JqValue collectIterateBodyGeneral(JqValue item, int bodyStart, int bodyLen) {
        int savedSp = sp;
        JqValue savedInput = input;
        input = item;
        collectIterateBody(bodyStart, bodyLen);
        JqValue result = pop();
        sp = savedSp;
        input = savedInput;
        return result;
    }

    private JqArray collectSelectIterateArray(JqArray arr, int bodyStart, int bodyLen) {
        var items = arr.arrayValue();
        int size = items.size();
        if (size == 0) return JqArray.EMPTY;

        // Pre-ensure stack capacity
        if (sp + 16 >= stack.length) {
            stack = java.util.Arrays.copyOf(stack, Math.max(stack.length * 2, sp + 16));
        }
        var result = new JqValue[size];
        int count = 0;
        int savedSp = sp;
        JqValue savedInput = input;
        for (int i = 0; i < size; i++) {
            input = items.get(i);
            sp = savedSp;
            // Execute body: condition check + JUMP_IF_FALSE + map expr
            // Body contains: <cond>, JUMP_IF_FALSE <bodyEnd>, <mapExpr>
            collectIterateBody(bodyStart, bodyLen);
            // If JUMP_IF_FALSE jumped past bodyEnd, sp == savedSp (nothing pushed)
            if (sp > savedSp) {
                result[count++] = pop();
            }
        }
        sp = savedSp;
        input = savedInput;
        return JqArray.ofTrusted(result, count);
    }

    private void collectIterateBody(int bodyStart, int bodyLen) {
        final int[] ops = bytecode.ops();
        final int[] arg1s = bytecode.arg1s();
        final int[] arg2s = bytecode.arg2s();
        final JqValue[] consts = bytecode.constants();
        final String[] names = bytecode.names();
        int bodyPc = bodyStart;
        int bodyEnd = bodyStart + bodyLen;
        while (bodyPc < bodyEnd) {
            int bpc = bodyPc;
            bodyPc++;
            switch (ops[bpc]) {
                case NOP -> {}
                case LOAD_INPUT -> push(input);
                case PUSH_CONST -> push(consts[arg1s[bpc]]);
                case PUSH_NULL -> push(JqNull.NULL);
                case PUSH_TRUE -> push(JqBoolean.TRUE);
                case PUSH_FALSE -> push(JqBoolean.FALSE);
                case ADD -> { JqValue r = pop(); JqValue l = pop(); push(l.add(r)); }
                case SUB -> { JqValue r = pop(); JqValue l = pop(); push(l.subtract(r)); }
                case MUL -> { JqValue r = pop(); JqValue l = pop(); push(l.multiply(r)); }
                case DIV -> { JqValue r = pop(); JqValue l = pop(); push(l.divide(r)); }
                case MOD -> { JqValue r = pop(); JqValue l = pop(); push(l.modulo(r)); }
                case NEGATE -> push(pop().negate());
                case DOT_FIELD -> push(fieldAccess(pop(), names[arg1s[bpc]]));
                case DOT_FIELD2 -> push(fieldAccess2(pop(), names[arg1s[bpc]], names[arg2s[bpc]]));

                case NOT -> push(JqBoolean.of(!pop().isTruthy()));
                case EQ -> { JqValue r = pop(); JqValue l = pop(); push(JqBoolean.of(l.equals(r))); }
                case NEQ -> { JqValue r = pop(); JqValue l = pop(); push(JqBoolean.of(!l.equals(r))); }
                case LT -> { JqValue r = pop(); JqValue l = pop(); push(JqBoolean.of(l.compareTo(r) < 0)); }
                case GT -> { JqValue r = pop(); JqValue l = pop(); push(JqBoolean.of(l.compareTo(r) > 0)); }
                case LE -> { JqValue r = pop(); JqValue l = pop(); push(JqBoolean.of(l.compareTo(r) <= 0)); }
                case GE -> { JqValue r = pop(); JqValue l = pop(); push(JqBoolean.of(l.compareTo(r) >= 0)); }
                case BUILTIN_LENGTH -> {
                    JqValue v = pop();
                    if (v instanceof JqNumber n) {
                        if (n.isNaN() || n.isInfinite()) push(JqNumber.of(Math.abs(n.doubleValue())));
                        else if (n.isIntegral()) push(JqNumber.of(Math.abs(n.longValue())));
                        else push(JqNumber.of(n.decimalValue().abs()));
                    } else {
                        push(JqNumber.of(v.length()));
                    }
                }
                case BUILTIN_TYPE -> { JqValue v = pop(); push(typeString(v)); }
                case BUILTIN_TOSTRING -> {
                    JqValue v = pop();
                    if (v instanceof JqString) push(v);
                    else push(JqString.of(v.toJsonString()));
                }
                case BUILTIN_NOT -> { JqValue v = pop(); push(JqBoolean.of(!v.isTruthy())); }
                case DUP -> push(peek());
                case SWAP -> { JqValue a = pop(); JqValue b = pop(); push(a); push(b); }
                case POP -> pop();
                case JUMP -> bodyPc = arg1s[bpc];
                case JUMP_IF_TRUE -> { if (pop().isTruthy()) bodyPc = arg1s[bpc]; }
                case JUMP_IF_FALSE -> { if (!pop().isTruthy()) bodyPc = arg1s[bpc]; }
                case SET_INPUT_PEEK -> input = peek();
                case LOAD_SLOT -> { JqValue sv2 = varSlots[arg1s[bpc]]; push(sv2 != null ? sv2 : JqNull.NULL); }
                case STORE_SLOT -> varSlots[arg1s[bpc]] = pop();
                case BUILTIN_FLOOR -> {
                    JqValue v = pop();
                    if (v instanceof JqNumber n) push(JqNumber.of((long) Math.floor(n.doubleValue())));
                    else throw new JqException(v.type().jqName() + " cannot be floored");
                }
                case INDEX -> { JqValue idx = pop(); JqValue base = pop(); push(JqValues.indexValue(base, idx)); }
                case SET_INPUT -> input = pop();
                case BUILTIN_TOJSON -> push(JqString.of(JqValues.toJsonStringDepthLimited(pop())));
                case BUILTIN_FROMJSON -> {
                    JqValue v = pop();
                    if (!(v instanceof JqString s)) throw new JqException("fromjson requires string");
                    try { push(JqValues.parseStrict(s.stringValue())); }
                    catch (IllegalArgumentException e) { throw new JqException(e.getMessage()); }
                }
                case BUILTIN_ABS -> {
                    JqValue v = pop();
                    if (v instanceof JqNumber n) push(JqNumber.of(Math.abs(n.doubleValue())));
                    else throw new JqException(v.type().jqName() + " cannot be made absolute");
                }
                case BUILTIN_KEYS -> {
                    JqValue v = pop();
                    if (v instanceof JqObject obj) {
                        push(obj.sortedKeysAsArray());
                    } else if (v instanceof JqArray arr) {
                        var keys = new java.util.ArrayList<JqValue>();
                        for (int j = 0; j < arr.arrayValue().size(); j++) keys.add(JqNumber.of(j));
                        push(JqArray.of(keys));
                    } else throw new JqException(v.type().jqName() + " has no keys");
                }
                case BUILTIN_VALUES -> {
                    JqValue v = pop();
                    if (v instanceof JqObject obj) push(JqArray.of(new java.util.ArrayList<>(obj.objectValue().values())));
                    else if (v instanceof JqArray) push(v);
                    else throw new JqException(v.type().jqName() + " has no values");
                }
                case BUILTIN_REVERSE -> {
                    JqValue v = pop();
                    if (v instanceof JqArray arr) {
                        var reversed = new java.util.ArrayList<>(arr.arrayValue());
                        java.util.Collections.reverse(reversed);
                        push(JqArray.of(reversed));
                    } else if (v instanceof JqString s) {
                        push(JqString.of(new StringBuilder(s.stringValue()).reverse().toString()));
                    } else throw new JqException(v.type().jqName() + " cannot be reversed");
                }
                case BUILTIN_SORT -> {
                    JqValue v = pop();
                    if (v instanceof JqArray arr) {
                        var sorted = new java.util.ArrayList<>(arr.arrayValue());
                        sorted.sort(null);
                        push(JqArray.of(sorted));
                    } else throw new JqException(v.type().jqName() + " cannot be sorted");
                }
                case BUILTIN_UNIQUE -> {
                    JqValue v = pop();
                    if (v instanceof JqArray arr) {
                        var sorted = new java.util.ArrayList<>(arr.arrayValue());
                        sorted.sort(null);
                        var unique = new java.util.ArrayList<JqValue>();
                        JqValue prev = null;
                        for (JqValue item : sorted) {
                            if (prev == null || !prev.equals(item)) unique.add(item);
                            prev = item;
                        }
                        push(JqArray.of(unique));
                    } else throw new JqException(v.type().jqName() + " cannot be uniqued");
                }
                case BUILTIN_CEIL -> {
                    JqValue v = pop();
                    if (v instanceof JqNumber n) push(JqNumber.of((long) Math.ceil(n.doubleValue())));
                    else throw new JqException(v.type().jqName() + " cannot be ceiled");
                }
                case BUILTIN_ROUND -> {
                    JqValue v = pop();
                    if (v instanceof JqNumber n) push(JqNumber.of(Math.round(n.doubleValue())));
                    else throw new JqException(v.type().jqName() + " cannot be rounded");
                }
                case BUILTIN_TONUMBER -> {
                    JqValue v = pop();
                    if (v instanceof JqNumber) push(v);
                    else if (v instanceof JqString s) {
                        String str = s.stringValue().trim();
                        try {
                            if (str.contains(".") || str.contains("e") || str.contains("E"))
                                push(JqNumber.of(new java.math.BigDecimal(str)));
                            else push(JqNumber.of(Long.parseLong(str)));
                        } catch (NumberFormatException e) {
                            throw new JqException("string (" + JqString.formatForError(s.stringValue()) + ") cannot be parsed as a number");
                        }
                    } else throw new JqException(v.type().jqName() + " cannot be converted to number");
                }
                case BUILD_OBJECT -> {
                    int count = arg1s[bpc];
                    int[] layout = bytecode.objectLayout(arg2s[bpc]);
                    for (int j = count - 1; j >= 0; j--) scratchValues[j] = pop();
                    var builder = JqObject.builder(count);
                    if (uniqueLayouts[arg2s[bpc]]) {
                        for (int j = 0; j < count; j++) builder.putUnchecked(names[layout[j]], scratchValues[j]);
                    } else {
                        for (int j = 0; j < count; j++) builder.put(names[layout[j]], scratchValues[j]);
                    }
                    push(builder.build());
                }
                case STRING_CONCAT -> {
                    int partCount = arg1s[bpc];
                    for (int j = partCount - 1; j >= 0; j--) scratchValues[j] = pop();
                    concatBuffer.setLength(0);
                    for (int j = 0; j < partCount; j++) {
                        JqValue v = scratchValues[j];
                        if (v instanceof JqString s) concatBuffer.append(s.stringValue());
                        else concatBuffer.append(v.toJsonString());
                    }
                    push(JqString.of(concatBuffer.toString()));
                }
                default -> throw new JqException("Unsupported opcode in COLLECT_ITERATE body: " + ops[bpc]);
            }
        }
    }

    private JqValue reduceIterateArray(JqArray arr, JqValue initVal, int op) {
        var items = arr.arrayValue();
        int size = items.size();
        if (size == 0) return initVal;

        // Fast path: integer-only reduce with add — use raw long accumulation
        if (op == 0 && initVal instanceof JqNumber initNum && initNum.isIntegral()) {
            long acc = initNum.longValue();
            boolean allIntegral = true;
            for (int i = 0; i < size; i++) {
                JqValue item = items.get(i);
                if (item instanceof JqNumber n && n.isIntegral()) {
                    acc = Math.addExact(acc, n.longValue());
                } else {
                    allIntegral = false;
                    break;
                }
            }
            if (allIntegral) return JqNumber.of(acc);
        }

        // Fast path: integer-only reduce with multiply
        if (op == 2 && initVal instanceof JqNumber initNum && initNum.isIntegral()) {
            long acc = initNum.longValue();
            boolean allIntegral = true;
            for (int i = 0; i < size; i++) {
                JqValue item = items.get(i);
                if (item instanceof JqNumber n && n.isIntegral()) {
                    acc = Math.multiplyExact(acc, n.longValue());
                } else {
                    allIntegral = false;
                    break;
                }
            }
            if (allIntegral) return JqNumber.of(acc);
        }

        // General path: use JqValue operations
        JqValue acc = initVal;
        for (int i = 0; i < size; i++) {
            acc = switch (op) {
                case 0 -> acc.add(items.get(i));
                case 1 -> acc.subtract(items.get(i));
                case 2 -> acc.multiply(items.get(i));
                default -> throw new JqException("Unknown reduce op: " + op);
            };
        }
        return acc;
    }
}

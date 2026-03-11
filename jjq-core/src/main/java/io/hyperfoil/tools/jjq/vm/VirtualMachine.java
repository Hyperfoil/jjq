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

    private enum ProgramShape { IDENTITY, FIELD_ACCESS, FIELD_ACCESS2, GENERAL }

    private final Bytecode bytecode;
    private final BuiltinRegistry builtins;
    private final Evaluator treeWalker;
    private final ProgramShape shape;
    private final boolean needsEnv;
    private final String fastField1;  // cached for FIELD_ACCESS / FIELD_ACCESS2
    private final String fastField2;  // cached for FIELD_ACCESS2

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
    private record TryPoint(int catchPc, int btDepth) {}

    @SuppressWarnings("unchecked")
    public VirtualMachine(Bytecode bytecode, BuiltinRegistry builtins) {
        this.bytecode = bytecode;
        this.builtins = builtins;
        this.treeWalker = new Evaluator(builtins);
        this.stack = new JqValue[INIT_STACK];
        this.btStack = new BacktrackPoint[INIT_BT];
        for (int i = 0; i < INIT_BT; i++) btStack[i] = new BacktrackPoint();
        this.tryStack = new TryPoint[INIT_TRY];
        this.collectStack = new List[INIT_COLLECT];
        this.varSlots = bytecode.varSlotCount() > 0 ? new JqValue[bytecode.varSlotCount()] : null;
        this.shape = detectShape();
        this.needsEnv = detectNeedsEnv();

        // Cache field names for fast-path shapes
        if (shape == ProgramShape.FIELD_ACCESS) {
            fastField1 = bytecode.name(bytecode.get(1).arg1());
            fastField2 = null;
        } else if (shape == ProgramShape.FIELD_ACCESS2) {
            var inst = bytecode.get(1);
            fastField1 = bytecode.name(inst.arg1());
            fastField2 = bytecode.name(inst.arg2());
        } else {
            fastField1 = null;
            fastField2 = null;
        }
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

        return ProgramShape.GENERAL;
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
            default:
                break;
        }
        return execute(inputValue, needsEnv ? new Environment() : null);
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
            default:
                break;
        }
        return executeOne(inputValue, needsEnv ? new Environment() : null);
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

    private static JqValue fieldAccess(JqValue val, String field) {
        if (val instanceof JqObject obj) return obj.get(field);
        if (val instanceof JqNull) return JqNull.NULL;
        throw new JqException("Cannot index " + val.type().jqName() + " with string \"" + field + "\"");
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
        // Inlined dispatch loop (avoids per-instruction method call overhead)
        while (!halted && pc < bytecode.size()) {
            var inst = bytecode.get(pc);
            pc++;

            try {
                switch (inst.op()) {
                    // Constants & Stack
                    case PUSH_CONST -> push(bytecode.constant(inst.arg1()));
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
                    case DOT_FIELD -> {
                        JqValue val = pop();
                        String field = bytecode.name(inst.arg1());
                        if (val instanceof JqObject obj) {
                            push(obj.get(field));
                        } else if (val instanceof JqNull) {
                            push(JqNull.NULL);
                        } else {
                            throw new JqException("Cannot index " + val.type().jqName() + " with string \"" + field + "\"");
                        }
                    }

                    case INDEX -> {
                        JqValue index = pop();
                        JqValue base = pop();
                        push(indexValue(base, index));
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
                            var values = new ArrayList<>(obj.objectValue().values());
                            if (!values.isEmpty()) {
                                if (values.size() > 1) {
                                    btPushIterator(pc, sp, input, env, values, 1);
                                }
                                push(values.getFirst());
                            } else {
                                doBacktrack();
                            }
                        } else if (val instanceof JqNull) {
                            throw new JqException("Cannot iterate over null (null)");
                        } else {
                            throw new JqException("Cannot iterate over " + val.type().jqName());
                        }
                    }

                    // Compound field access
                    case DOT_FIELD2 -> {
                        JqValue val = pop();
                        String f1 = bytecode.name(inst.arg1());
                        String f2 = bytecode.name(inst.arg2());
                        JqValue mid;
                        if (val instanceof JqObject obj) mid = obj.get(f1);
                        else if (val instanceof JqNull) mid = JqNull.NULL;
                        else throw new JqException("Cannot index " + val.type().jqName() + " with string \"" + f1 + "\"");
                        if (mid instanceof JqObject obj2) push(obj2.get(f2));
                        else if (mid instanceof JqNull) push(JqNull.NULL);
                        else throw new JqException("Cannot index " + mid.type().jqName() + " with string \"" + f2 + "\"");
                    }

                    // Inlined builtins
                    case BUILTIN_LENGTH -> {
                        JqValue val = pop();
                        push(JqNumber.of(val.length()));
                    }

                    case BUILTIN_TYPE -> {
                        JqValue val = pop();
                        push(JqString.of(val.type().jqName()));
                    }

                    case BUILTIN_KEYS -> {
                        JqValue val = pop();
                        if (val instanceof JqObject obj) {
                            var keySet = obj.objectValue().keySet();
                            var keys = new ArrayList<JqValue>(keySet.size());
                            for (var k : keySet) keys.add(JqString.of(k));
                            keys.sort(JqValue::compareTo);
                            push(JqArray.of(keys));
                        } else if (val instanceof JqArray arr) {
                            var keys = new ArrayList<JqValue>(arr.arrayValue().size());
                            for (int i = 0; i < arr.arrayValue().size(); i++) keys.add(JqNumber.of(i));
                            push(JqArray.of(keys));
                        } else {
                            throw new JqException(val.type().jqName() + " has no keys");
                        }
                    }

                    case BUILTIN_VALUES -> {
                        JqValue val = pop();
                        if (val instanceof JqObject obj) {
                            push(JqArray.of(new ArrayList<>(obj.objectValue().values())));
                        } else if (val instanceof JqArray) {
                            push(val);
                        } else {
                            throw new JqException(val.type().jqName() + " has no values");
                        }
                    }

                    case BUILTIN_NOT -> {
                        JqValue val = pop();
                        push(JqBoolean.of(!val.isTruthy()));
                    }

                    case BUILTIN_EMPTY -> doBacktrack();

                    case BUILTIN_TOSTRING -> {
                        JqValue val = pop();
                        if (val instanceof JqString) push(val);
                        else push(JqString.of(val.toJsonString()));
                    }

                    case BUILTIN_TONUMBER -> {
                        JqValue val = pop();
                        if (val instanceof JqNumber) push(val);
                        else if (val instanceof JqString s) {
                            String str = s.stringValue().trim();
                            if (str.contains(".") || str.contains("e") || str.contains("E")) {
                                push(JqNumber.of(new java.math.BigDecimal(str)));
                            } else {
                                push(JqNumber.of(Long.parseLong(str)));
                            }
                        } else {
                            throw new JqException(val.type().jqName() + " cannot be converted to number");
                        }
                    }

                    case BUILTIN_ADD -> {
                        JqValue val = pop();
                        if (val instanceof JqArray arr) {
                            var items = arr.arrayValue();
                            if (items.isEmpty()) { push(JqNull.NULL); break; }
                            JqValue result = items.getFirst();
                            for (int i = 1; i < items.size(); i++) {
                                result = result.add(items.get(i));
                            }
                            push(result);
                        } else {
                            throw new JqException(val.type().jqName() + " is not iterable");
                        }
                    }

                    case BUILTIN_REVERSE -> {
                        JqValue val = pop();
                        if (val instanceof JqArray arr) {
                            var list = new ArrayList<>(arr.arrayValue());
                            java.util.Collections.reverse(list);
                            push(JqArray.of(list));
                        } else {
                            throw new JqException(val.type().jqName() + " cannot be reversed");
                        }
                    }

                    case BUILTIN_SORT -> {
                        JqValue val = pop();
                        if (val instanceof JqArray arr) {
                            var list = new ArrayList<>(arr.arrayValue());
                            list.sort(JqValue::compareTo);
                            push(JqArray.of(list));
                        } else {
                            throw new JqException(val.type().jqName() + " cannot be sorted");
                        }
                    }

                    case BUILTIN_MIN -> {
                        JqValue val = pop();
                        if (val instanceof JqArray arr) {
                            var items = arr.arrayValue();
                            if (items.isEmpty()) { push(JqNull.NULL); break; }
                            JqValue min = items.getFirst();
                            for (int i = 1; i < items.size(); i++) {
                                if (items.get(i).compareTo(min) < 0) min = items.get(i);
                            }
                            push(min);
                        } else {
                            throw new JqException(val.type().jqName() + " is not iterable");
                        }
                    }

                    case BUILTIN_MAX -> {
                        JqValue val = pop();
                        if (val instanceof JqArray arr) {
                            var items = arr.arrayValue();
                            if (items.isEmpty()) { push(JqNull.NULL); break; }
                            JqValue max = items.getFirst();
                            for (int i = 1; i < items.size(); i++) {
                                if (items.get(i).compareTo(max) > 0) max = items.get(i);
                            }
                            push(max);
                        } else {
                            throw new JqException(val.type().jqName() + " is not iterable");
                        }
                    }

                    case BUILTIN_FLATTEN -> {
                        JqValue val = pop();
                        if (val instanceof JqArray arr) {
                            var flat = new ArrayList<JqValue>();
                            flattenDeep(arr, flat);
                            push(JqArray.of(flat));
                        } else {
                            throw new JqException(val.type().jqName() + " cannot be flattened");
                        }
                    }

                    case BUILTIN_UNIQUE -> {
                        JqValue val = pop();
                        if (val instanceof JqArray arr) {
                            var seen = new java.util.LinkedHashSet<JqValue>();
                            for (var item : arr.arrayValue()) seen.add(item);
                            var sorted = new ArrayList<>(seen);
                            sorted.sort(JqValue::compareTo);
                            push(JqArray.of(sorted));
                        } else {
                            throw new JqException(val.type().jqName() + " cannot be uniquified");
                        }
                    }

                    case BUILTIN_FLOOR -> {
                        JqValue val = pop();
                        if (val instanceof JqNumber n) push(JqNumber.of((long) Math.floor(n.doubleValue())));
                        else throw new JqException(val.type().jqName() + " cannot be floored");
                    }

                    case BUILTIN_CEIL -> {
                        JqValue val = pop();
                        if (val instanceof JqNumber n) push(JqNumber.of((long) Math.ceil(n.doubleValue())));
                        else throw new JqException(val.type().jqName() + " cannot be ceiled");
                    }

                    case BUILTIN_ROUND -> {
                        JqValue val = pop();
                        if (val instanceof JqNumber n) push(JqNumber.of(Math.round(n.doubleValue())));
                        else throw new JqException(val.type().jqName() + " cannot be rounded");
                    }

                    case BUILTIN_ABS -> {
                        JqValue val = pop();
                        if (val instanceof JqNumber n) {
                            if (n.isIntegral()) push(JqNumber.of(Math.abs(n.longValue())));
                            else push(JqNumber.of(n.decimalValue().abs()));
                        } else {
                            throw new JqException(val.type().jqName() + " has no absolute value");
                        }
                    }

                    case BUILTIN_TOJSON -> {
                        JqValue val = pop();
                        push(JqString.of(val.toJsonString()));
                    }

                    case BUILTIN_FROMJSON -> {
                        JqValue val = pop();
                        if (val instanceof JqString s) {
                            push(JqValues.parse(s.stringValue()));
                        } else {
                            throw new JqException(val.type().jqName() + " cannot be parsed as JSON");
                        }
                    }

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
                    case FORK -> btPush(inst.arg1(), sp, input, env, null);

                    case JUMP -> pc = inst.arg1();

                    case JUMP_IF_TRUE -> { if (pop().isTruthy()) pc = inst.arg1(); }

                    case JUMP_IF_FALSE -> { if (!pop().isTruthy()) pc = inst.arg1(); }

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
                        String name = bytecode.name(inst.arg1());
                        if ("ENV".equals(name)) {
                            var map = new java.util.LinkedHashMap<String, JqValue>();
                            System.getenv().forEach((k, v) -> map.put(k, JqString.of(v)));
                            push(JqObject.of(map));
                        } else if ("__loc__".equals(name)) {
                            var map = new java.util.LinkedHashMap<String, JqValue>();
                            map.put("file", JqString.of("<stdin>"));
                            map.put("line", JqNumber.of(1));
                            push(JqObject.of(map));
                        } else {
                            push(env.getVariable(name));
                        }
                    }

                    case STORE_VAR -> {
                        String name = bytecode.name(inst.arg1());
                        env.setVariable(name, pop());
                    }

                    // Indexed variable slots
                    case LOAD_SLOT -> push(varSlots[inst.arg1()]);
                    case STORE_SLOT -> varSlots[inst.arg1()] = pop();

                    // Scope
                    case PUSH_SCOPE -> env = env.child();
                    case POP_SCOPE -> {
                        Environment parent = env.parent();
                        if (parent != null) env = parent;
                    }

                    // Collection (array construction)
                    case COLLECT_BEGIN -> collectStack[cp++] = new ArrayList<>();

                    case COLLECT_ADD -> {
                        collectStack[cp - 1].add(pop());
                        doBacktrack();
                    }

                    case COLLECT_END -> {
                        List<JqValue> items = collectStack[--cp];
                        push(JqArray.ofTrusted(items));
                    }

                    // Fused collect-iterate: [.[] | simple-expr]
                    case COLLECT_ITERATE -> {
                        JqValue val = pop(); // the array to iterate
                        int bodyLen = inst.arg1();
                        int bodyStart = pc;
                        pc = bodyStart + bodyLen; // skip body in main loop
                        if (val instanceof JqArray arr) {
                            push(collectIterateArray(arr, bodyStart, bodyLen));
                        } else if (val instanceof JqNull) {
                            push(JqArray.EMPTY);
                        } else {
                            throw new JqException("Cannot iterate over " + val.type().jqName());
                        }
                    }

                    // Fused collect-select-iterate: [.[] | select(cond) | expr]
                    case COLLECT_SELECT_ITERATE -> {
                        JqValue val = pop();
                        int bodyLen = inst.arg1();
                        int bodyStart = pc;
                        pc = bodyStart + bodyLen; // skip body in main loop
                        if (val instanceof JqArray arr) {
                            push(collectSelectIterateArray(arr, bodyStart, bodyLen));
                        } else if (val instanceof JqNull) {
                            push(JqArray.EMPTY);
                        } else {
                            throw new JqException("Cannot iterate over " + val.type().jqName());
                        }
                    }

                    // Fused reduce-iterate: reduce .[] as $x (init; . op $x)
                    case REDUCE_ITERATE -> {
                        JqValue val = pop(); // the array to iterate
                        JqValue initVal = bytecode.constant(inst.arg1());
                        int op = inst.arg2();
                        if (val instanceof JqArray arr) {
                            push(reduceIterateArray(arr, initVal, op));
                        } else if (val instanceof JqNull) {
                            push(initVal);
                        } else {
                            throw new JqException("Cannot iterate over " + val.type().jqName());
                        }
                    }

                    // Try-catch
                    case TRY_BEGIN -> tryStack[tp++] = new TryPoint(inst.arg1(), btp);

                    case TRY_END -> tp--;

                    // Function calls (delegate to tree-walker)
                    case CALL_FUNC -> {
                        var ci = bytecode.callInfo(inst.arg1());
                        evalViaTreeWalker(ci.name(), ci.arity(), ci.args());
                    }

                    // Sub-expression evaluation (delegate to tree-walker)
                    case EVAL_AST -> {
                        JqExpr subExpr = bytecode.subExpr(inst.arg1());
                        evalSubExpr(subExpr);
                    }
                }
            } catch (EmptyException e) {
                doBacktrack();
            } catch (JqException | JqTypeError e) {
                if (tp > 0) {
                    handleError(e);
                } else {
                    throw e;
                }
            }
        }
    }

    private void evalViaTreeWalker(String name, int arity, List<JqExpr> args) {
        var fc = new JqExpr.FuncCallExpr(name, args);
        evalSubExpr(fc);
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

    private JqValue indexValue(JqValue base, JqValue index) {
        return switch (base) {
            case JqArray arr when index instanceof JqNumber n -> arr.get((int) n.longValue());
            case JqObject obj when index instanceof JqString s -> obj.get(s.stringValue());
            case JqNull _ -> JqNull.NULL;
            default -> throw new JqException("Cannot index " + base.type().jqName() + " with " + index.type().jqName());
        };
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
        push(JqString.of(e.getMessage()));
        pc = tryPoint.catchPc;
    }

    private void flattenDeep(JqArray arr, List<JqValue> result) {
        for (JqValue v : arr.arrayValue()) {
            if (v instanceof JqArray inner) flattenDeep(inner, result);
            else result.add(v);
        }
    }

    private JqArray collectIterateArray(JqArray arr, int bodyStart, int bodyLen) {
        var items = arr.arrayValue();
        int size = items.size();
        if (size == 0) return JqArray.EMPTY;

        // Fast path: detect common body patterns to avoid stack/dispatch overhead
        // Pattern: LOAD_INPUT, PUSH_CONST <c>, <arith-op> (bodyLen == 3)
        if (bodyLen == 3) {
            var b0 = bytecode.get(bodyStart);
            var b1 = bytecode.get(bodyStart + 1);
            var b2 = bytecode.get(bodyStart + 2);
            if (b0.op() == Opcode.LOAD_INPUT && b1.op() == Opcode.PUSH_CONST) {
                JqValue constVal = bytecode.constant(b1.arg1());
                // For integer * integer, use raw long math
                if (b2.op() == Opcode.MUL && constVal instanceof JqNumber cn && cn.isIntegral()) {
                    long multiplier = cn.longValue();
                    var result = new ArrayList<JqValue>(size);
                    boolean allIntegral = true;
                    for (int i = 0; i < size; i++) {
                        JqValue item = items.get(i);
                        if (item instanceof JqNumber n && n.isIntegral()) {
                            result.add(JqNumber.of(Math.multiplyExact(n.longValue(), multiplier)));
                        } else {
                            allIntegral = false;
                            break;
                        }
                    }
                    if (allIntegral) return JqArray.ofTrusted(result);
                }
                // General arith: apply via JqValue methods (no stack needed)
                var result = new ArrayList<JqValue>(size);
                for (int i = 0; i < size; i++) {
                    JqValue item = items.get(i);
                    result.add(switch (b2.op()) {
                        case ADD -> item.add(constVal);
                        case SUB -> item.subtract(constVal);
                        case MUL -> item.multiply(constVal);
                        case DIV -> item.divide(constVal);
                        case MOD -> item.modulo(constVal);
                        default -> collectIterateBodyGeneral(item, bodyStart, bodyLen);
                    });
                }
                return JqArray.ofTrusted(result);
            }
        }

        // General path: mini-interpreter with stack
        var result = new ArrayList<JqValue>(size);
        int savedSp = sp;
        JqValue savedInput = input;
        for (int i = 0; i < size; i++) {
            input = items.get(i);
            sp = savedSp;
            collectIterateBody(bodyStart, bodyLen);
            result.add(pop());
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

        var result = new ArrayList<JqValue>();
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
                result.add(pop());
            }
        }
        sp = savedSp;
        input = savedInput;
        return JqArray.ofTrusted(result);
    }

    private void collectIterateBody(int bodyStart, int bodyLen) {
        int bodyPc = bodyStart;
        int bodyEnd = bodyStart + bodyLen;
        while (bodyPc < bodyEnd) {
            var bodyInst = bytecode.get(bodyPc);
            bodyPc++;
            switch (bodyInst.op()) {
                case LOAD_INPUT -> push(input);
                case PUSH_CONST -> push(bytecode.constant(bodyInst.arg1()));
                case PUSH_NULL -> push(JqNull.NULL);
                case PUSH_TRUE -> push(JqBoolean.TRUE);
                case PUSH_FALSE -> push(JqBoolean.FALSE);
                case ADD -> { JqValue r = pop(); JqValue l = pop(); push(l.add(r)); }
                case SUB -> { JqValue r = pop(); JqValue l = pop(); push(l.subtract(r)); }
                case MUL -> { JqValue r = pop(); JqValue l = pop(); push(l.multiply(r)); }
                case DIV -> { JqValue r = pop(); JqValue l = pop(); push(l.divide(r)); }
                case MOD -> { JqValue r = pop(); JqValue l = pop(); push(l.modulo(r)); }
                case NEGATE -> push(pop().negate());
                case DOT_FIELD -> {
                    JqValue v = pop();
                    String field = bytecode.name(bodyInst.arg1());
                    if (v instanceof JqObject obj) push(obj.get(field));
                    else if (v instanceof JqNull) push(JqNull.NULL);
                    else throw new JqException("Cannot index " + v.type().jqName() + " with string \"" + field + "\"");
                }
                case DOT_FIELD2 -> {
                    JqValue v = pop();
                    String f1 = bytecode.name(bodyInst.arg1());
                    String f2 = bytecode.name(bodyInst.arg2());
                    JqValue mid;
                    if (v instanceof JqObject obj) mid = obj.get(f1);
                    else if (v instanceof JqNull) mid = JqNull.NULL;
                    else throw new JqException("Cannot index " + v.type().jqName() + " with string \"" + f1 + "\"");
                    if (mid instanceof JqObject obj2) push(obj2.get(f2));
                    else if (mid instanceof JqNull) push(JqNull.NULL);
                    else throw new JqException("Cannot index " + mid.type().jqName() + " with string \"" + f2 + "\"");
                }
                case NOT -> push(JqBoolean.of(!pop().isTruthy()));
                case EQ -> { JqValue r = pop(); JqValue l = pop(); push(JqBoolean.of(l.equals(r))); }
                case NEQ -> { JqValue r = pop(); JqValue l = pop(); push(JqBoolean.of(!l.equals(r))); }
                case LT -> { JqValue r = pop(); JqValue l = pop(); push(JqBoolean.of(l.compareTo(r) < 0)); }
                case GT -> { JqValue r = pop(); JqValue l = pop(); push(JqBoolean.of(l.compareTo(r) > 0)); }
                case LE -> { JqValue r = pop(); JqValue l = pop(); push(JqBoolean.of(l.compareTo(r) <= 0)); }
                case GE -> { JqValue r = pop(); JqValue l = pop(); push(JqBoolean.of(l.compareTo(r) >= 0)); }
                case BUILTIN_LENGTH -> { JqValue v = pop(); push(JqNumber.of(v.length())); }
                case BUILTIN_TYPE -> { JqValue v = pop(); push(JqString.of(v.type().jqName())); }
                case BUILTIN_TOSTRING -> {
                    JqValue v = pop();
                    if (v instanceof JqString) push(v);
                    else push(JqString.of(v.toJsonString()));
                }
                case BUILTIN_NOT -> { JqValue v = pop(); push(JqBoolean.of(!v.isTruthy())); }
                case DUP -> push(peek());
                case SWAP -> { JqValue a = pop(); JqValue b = pop(); push(a); push(b); }
                case POP -> pop();
                case JUMP -> bodyPc = bodyInst.arg1();
                case JUMP_IF_TRUE -> { if (pop().isTruthy()) bodyPc = bodyInst.arg1(); }
                case JUMP_IF_FALSE -> { if (!pop().isTruthy()) bodyPc = bodyInst.arg1(); }
                case SET_INPUT_PEEK -> input = peek();
                case LOAD_SLOT -> push(varSlots[bodyInst.arg1()]);
                case STORE_SLOT -> varSlots[bodyInst.arg1()] = pop();
                default -> throw new JqException("Unsupported opcode in COLLECT_ITERATE body: " + bodyInst.op());
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

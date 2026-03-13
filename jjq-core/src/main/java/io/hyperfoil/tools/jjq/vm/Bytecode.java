package io.hyperfoil.tools.jjq.vm;

import io.hyperfoil.tools.jjq.ast.JqExpr;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.List;

public final class Bytecode {

    public record Instruction(int op, int arg1, int arg2) {
        public Instruction(int op) { this(op, 0, 0); }
        public Instruction(int op, int arg1) { this(op, arg1, 0); }
    }

    public record CallInfo(String name, int arity, List<JqExpr> args) {}

    private final Instruction[] code;
    // Parallel arrays for zero-overhead dispatch (avoids record accessor calls in hot loop)
    private final int[] ops;
    private final int[] arg1s;
    private final int[] arg2s;
    private final JqValue[] constants;
    private final String[] names;
    private final JqExpr[] subExprs;
    private final CallInfo[] callInfos;
    private final int varSlotCount;

    public Bytecode(Instruction[] code, JqValue[] constants, String[] names,
                    JqExpr[] subExprs, CallInfo[] callInfos) {
        this(code, constants, names, subExprs, callInfos, 0);
    }

    public Bytecode(Instruction[] code, JqValue[] constants, String[] names,
                    JqExpr[] subExprs, CallInfo[] callInfos, int varSlotCount) {
        this.code = code;
        this.constants = constants;
        this.names = names;
        this.subExprs = subExprs;
        this.callInfos = callInfos;
        this.varSlotCount = varSlotCount;
        // Build parallel arrays from instruction records
        this.ops = new int[code.length];
        this.arg1s = new int[code.length];
        this.arg2s = new int[code.length];
        for (int i = 0; i < code.length; i++) {
            ops[i] = code[i].op();
            arg1s[i] = code[i].arg1();
            arg2s[i] = code[i].arg2();
        }
    }

    public int size() { return ops.length; }
    public Instruction get(int pc) { return code[pc]; }
    public JqValue constant(int idx) { return constants[idx]; }
    public String name(int idx) { return names[idx]; }
    public JqExpr subExpr(int idx) { return subExprs[idx]; }
    public CallInfo callInfo(int idx) { return callInfos[idx]; }
    public int varSlotCount() { return varSlotCount; }

    // Direct array accessors for hot-loop dispatch
    public int[] ops() { return ops; }
    public int[] arg1s() { return arg1s; }
    public int[] arg2s() { return arg2s; }
    public JqValue[] constants() { return constants; }
    public String[] names() { return names; }

    public String disassemble() {
        var sb = new StringBuilder();
        for (int i = 0; i < code.length; i++) {
            var inst = code[i];
            sb.append(String.format("%4d: %-24s", i, Opcode.name(inst.op())));
            switch (inst.op()) {
                case Opcode.PUSH_CONST -> sb.append(" ").append(constants[inst.arg1()].toJsonString());
                case Opcode.DOT_FIELD, Opcode.LOAD_VAR, Opcode.STORE_VAR -> sb.append(" ").append(names[inst.arg1()]);
                case Opcode.LOAD_SLOT, Opcode.STORE_SLOT -> sb.append(" slot[").append(inst.arg1()).append("]");
                case Opcode.COLLECT_ITERATE, Opcode.COLLECT_SELECT_ITERATE -> sb.append(" bodyLen=").append(inst.arg1());
                case Opcode.REDUCE_ITERATE -> sb.append(" init=").append(constants[inst.arg1()].toJsonString()).append(" op=").append(inst.arg2());
                case Opcode.DOT_FIELD2 -> sb.append(" ").append(names[inst.arg1()]).append(".").append(names[inst.arg2()]);
                case Opcode.FORK, Opcode.JUMP, Opcode.JUMP_IF_TRUE, Opcode.JUMP_IF_FALSE, Opcode.TRY_BEGIN -> sb.append(" -> ").append(inst.arg1());
                case Opcode.CALL_FUNC -> {
                    var ci = callInfos[inst.arg1()];
                    sb.append(" ").append(ci.name()).append("/").append(ci.arity());
                }
                case Opcode.EVAL_AST -> sb.append(" [").append(inst.arg1()).append("]");
                default -> {}
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}

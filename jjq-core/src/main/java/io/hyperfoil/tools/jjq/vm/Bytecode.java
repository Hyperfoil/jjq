package io.hyperfoil.tools.jjq.vm;

import io.hyperfoil.tools.jjq.ast.JqExpr;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.List;

public final class Bytecode {

    public record Instruction(Opcode op, int arg1, int arg2) {
        public Instruction(Opcode op) { this(op, 0, 0); }
        public Instruction(Opcode op, int arg1) { this(op, arg1, 0); }
    }

    public record CallInfo(String name, int arity, List<JqExpr> args) {}

    private final Instruction[] code;
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
    }

    public int size() { return code.length; }
    public Instruction get(int pc) { return code[pc]; }
    public JqValue constant(int idx) { return constants[idx]; }
    public String name(int idx) { return names[idx]; }
    public JqExpr subExpr(int idx) { return subExprs[idx]; }
    public CallInfo callInfo(int idx) { return callInfos[idx]; }
    public int varSlotCount() { return varSlotCount; }

    public String disassemble() {
        var sb = new StringBuilder();
        for (int i = 0; i < code.length; i++) {
            var inst = code[i];
            sb.append(String.format("%4d: %-16s", i, inst.op()));
            switch (inst.op()) {
                case PUSH_CONST -> sb.append(" ").append(constants[inst.arg1()].toJsonString());
                case DOT_FIELD, LOAD_VAR, STORE_VAR -> sb.append(" ").append(names[inst.arg1()]);
                case LOAD_SLOT, STORE_SLOT -> sb.append(" slot[").append(inst.arg1()).append("]");
                case COLLECT_ITERATE -> sb.append(" bodyLen=").append(inst.arg1());
                case REDUCE_ITERATE -> sb.append(" init=").append(constants[inst.arg1()].toJsonString()).append(" op=").append(inst.arg2());
                case SET_INPUT_PEEK -> {}
                case DOT_FIELD2 -> sb.append(" ").append(names[inst.arg1()]).append(".").append(names[inst.arg2()]);
                case FORK, JUMP, JUMP_IF_TRUE, JUMP_IF_FALSE, TRY_BEGIN -> sb.append(" -> ").append(inst.arg1());
                case CALL_FUNC -> {
                    var ci = callInfos[inst.arg1()];
                    sb.append(" ").append(ci.name()).append("/").append(ci.arity());
                }
                case EVAL_AST -> sb.append(" [").append(inst.arg1()).append("]");
                default -> {}
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}

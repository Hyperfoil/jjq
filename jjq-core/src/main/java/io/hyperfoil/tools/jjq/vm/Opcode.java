package io.hyperfoil.tools.jjq.vm;

/**
 * Bytecode opcodes as int constants for zero-overhead dispatch.
 * Using int constants instead of enum avoids Enum.ordinal() and $SwitchMap$ lookups.
 */
public final class Opcode {
    private Opcode() {}

    // Constants & Stack
    public static final int PUSH_CONST = 0;
    public static final int PUSH_NULL = 1;
    public static final int PUSH_TRUE = 2;
    public static final int PUSH_FALSE = 3;
    public static final int POP = 4;
    public static final int DUP = 5;
    public static final int SWAP = 6;

    // Input register
    public static final int LOAD_INPUT = 7;
    public static final int SET_INPUT = 8;
    public static final int SET_INPUT_PEEK = 9;

    // Field/Index access
    public static final int DOT_FIELD = 10;
    public static final int INDEX = 11;
    public static final int EACH = 12;

    // Arithmetic
    public static final int ADD = 13;
    public static final int SUB = 14;
    public static final int MUL = 15;
    public static final int DIV = 16;
    public static final int MOD = 17;
    public static final int NEGATE = 18;

    // Comparison
    public static final int EQ = 19;
    public static final int NEQ = 20;
    public static final int LT = 21;
    public static final int GT = 22;
    public static final int LE = 23;
    public static final int GE = 24;

    // Logic
    public static final int NOT = 25;

    // Control flow
    public static final int FORK = 26;
    public static final int JUMP = 27;
    public static final int JUMP_IF_TRUE = 28;
    public static final int JUMP_IF_FALSE = 29;
    public static final int BACKTRACK = 30;
    public static final int OUTPUT = 31;
    public static final int HALT = 32;

    // Variables
    public static final int LOAD_VAR = 33;
    public static final int STORE_VAR = 34;

    // Scope
    public static final int PUSH_SCOPE = 35;
    public static final int POP_SCOPE = 36;

    // Collection (for array construction)
    public static final int COLLECT_BEGIN = 37;
    public static final int COLLECT_ADD = 38;
    public static final int COLLECT_END = 39;

    // Try-catch
    public static final int TRY_BEGIN = 40;
    public static final int TRY_END = 41;

    // Inlined builtins
    public static final int BUILTIN_LENGTH = 42;
    public static final int BUILTIN_TYPE = 43;
    public static final int BUILTIN_KEYS = 44;
    public static final int BUILTIN_VALUES = 45;
    public static final int BUILTIN_NOT = 46;
    public static final int BUILTIN_EMPTY = 47;
    public static final int BUILTIN_TOSTRING = 48;
    public static final int BUILTIN_TONUMBER = 49;
    public static final int BUILTIN_ADD = 50;
    public static final int BUILTIN_REVERSE = 51;
    public static final int BUILTIN_SORT = 52;
    public static final int BUILTIN_MIN = 53;
    public static final int BUILTIN_MAX = 54;
    public static final int BUILTIN_FLATTEN = 55;
    public static final int BUILTIN_UNIQUE = 56;
    public static final int BUILTIN_FLOOR = 57;
    public static final int BUILTIN_CEIL = 58;
    public static final int BUILTIN_ROUND = 59;
    public static final int BUILTIN_ABS = 60;
    public static final int BUILTIN_TOJSON = 61;
    public static final int BUILTIN_FROMJSON = 62;

    // Compound field lookup
    public static final int DOT_FIELD2 = 63;

    // Fused iteration opcodes
    public static final int COLLECT_ITERATE = 64;
    public static final int REDUCE_ITERATE = 65;

    // Indexed variable slots
    public static final int LOAD_SLOT = 66;
    public static final int STORE_SLOT = 67;

    // Function/expression evaluation
    public static final int CALL_FUNC = 68;
    public static final int EVAL_AST = 69;

    // Fused select-iterate: [.[] | select(cond) | expr]
    public static final int COLLECT_SELECT_ITERATE = 70;

    // No-op (replaces dead JUMP-to-next instructions)
    public static final int NOP = 71;

    // Object construction: BUILD_OBJECT arg1=fieldCount, arg2=layoutIdx
    public static final int BUILD_OBJECT = 72;

    // String concatenation: STRING_CONCAT arg1=partCount
    public static final int STRING_CONCAT = 73;

    public static final int COUNT = 74;

    private static final String[] NAMES = new String[COUNT];
    static {
        NAMES[PUSH_CONST] = "PUSH_CONST";
        NAMES[PUSH_NULL] = "PUSH_NULL";
        NAMES[PUSH_TRUE] = "PUSH_TRUE";
        NAMES[PUSH_FALSE] = "PUSH_FALSE";
        NAMES[POP] = "POP";
        NAMES[DUP] = "DUP";
        NAMES[SWAP] = "SWAP";
        NAMES[LOAD_INPUT] = "LOAD_INPUT";
        NAMES[SET_INPUT] = "SET_INPUT";
        NAMES[SET_INPUT_PEEK] = "SET_INPUT_PEEK";
        NAMES[DOT_FIELD] = "DOT_FIELD";
        NAMES[INDEX] = "INDEX";
        NAMES[EACH] = "EACH";
        NAMES[ADD] = "ADD";
        NAMES[SUB] = "SUB";
        NAMES[MUL] = "MUL";
        NAMES[DIV] = "DIV";
        NAMES[MOD] = "MOD";
        NAMES[NEGATE] = "NEGATE";
        NAMES[EQ] = "EQ";
        NAMES[NEQ] = "NEQ";
        NAMES[LT] = "LT";
        NAMES[GT] = "GT";
        NAMES[LE] = "LE";
        NAMES[GE] = "GE";
        NAMES[NOT] = "NOT";
        NAMES[FORK] = "FORK";
        NAMES[JUMP] = "JUMP";
        NAMES[JUMP_IF_TRUE] = "JUMP_IF_TRUE";
        NAMES[JUMP_IF_FALSE] = "JUMP_IF_FALSE";
        NAMES[BACKTRACK] = "BACKTRACK";
        NAMES[OUTPUT] = "OUTPUT";
        NAMES[HALT] = "HALT";
        NAMES[LOAD_VAR] = "LOAD_VAR";
        NAMES[STORE_VAR] = "STORE_VAR";
        NAMES[PUSH_SCOPE] = "PUSH_SCOPE";
        NAMES[POP_SCOPE] = "POP_SCOPE";
        NAMES[COLLECT_BEGIN] = "COLLECT_BEGIN";
        NAMES[COLLECT_ADD] = "COLLECT_ADD";
        NAMES[COLLECT_END] = "COLLECT_END";
        NAMES[TRY_BEGIN] = "TRY_BEGIN";
        NAMES[TRY_END] = "TRY_END";
        NAMES[BUILTIN_LENGTH] = "BUILTIN_LENGTH";
        NAMES[BUILTIN_TYPE] = "BUILTIN_TYPE";
        NAMES[BUILTIN_KEYS] = "BUILTIN_KEYS";
        NAMES[BUILTIN_VALUES] = "BUILTIN_VALUES";
        NAMES[BUILTIN_NOT] = "BUILTIN_NOT";
        NAMES[BUILTIN_EMPTY] = "BUILTIN_EMPTY";
        NAMES[BUILTIN_TOSTRING] = "BUILTIN_TOSTRING";
        NAMES[BUILTIN_TONUMBER] = "BUILTIN_TONUMBER";
        NAMES[BUILTIN_ADD] = "BUILTIN_ADD";
        NAMES[BUILTIN_REVERSE] = "BUILTIN_REVERSE";
        NAMES[BUILTIN_SORT] = "BUILTIN_SORT";
        NAMES[BUILTIN_MIN] = "BUILTIN_MIN";
        NAMES[BUILTIN_MAX] = "BUILTIN_MAX";
        NAMES[BUILTIN_FLATTEN] = "BUILTIN_FLATTEN";
        NAMES[BUILTIN_UNIQUE] = "BUILTIN_UNIQUE";
        NAMES[BUILTIN_FLOOR] = "BUILTIN_FLOOR";
        NAMES[BUILTIN_CEIL] = "BUILTIN_CEIL";
        NAMES[BUILTIN_ROUND] = "BUILTIN_ROUND";
        NAMES[BUILTIN_ABS] = "BUILTIN_ABS";
        NAMES[BUILTIN_TOJSON] = "BUILTIN_TOJSON";
        NAMES[BUILTIN_FROMJSON] = "BUILTIN_FROMJSON";
        NAMES[DOT_FIELD2] = "DOT_FIELD2";
        NAMES[COLLECT_ITERATE] = "COLLECT_ITERATE";
        NAMES[REDUCE_ITERATE] = "REDUCE_ITERATE";
        NAMES[LOAD_SLOT] = "LOAD_SLOT";
        NAMES[STORE_SLOT] = "STORE_SLOT";
        NAMES[CALL_FUNC] = "CALL_FUNC";
        NAMES[EVAL_AST] = "EVAL_AST";
        NAMES[COLLECT_SELECT_ITERATE] = "COLLECT_SELECT_ITERATE";
        NAMES[NOP] = "NOP";
        NAMES[BUILD_OBJECT] = "BUILD_OBJECT";
        NAMES[STRING_CONCAT] = "STRING_CONCAT";
    }

    public static String name(int op) {
        return op >= 0 && op < COUNT ? NAMES[op] : "UNKNOWN(" + op + ")";
    }

    /** Check if an opcode is a jump/branch instruction (for peephole optimization). */
    public static boolean isJump(int op) {
        return op == FORK || op == JUMP || op == JUMP_IF_TRUE
                || op == JUMP_IF_FALSE || op == TRY_BEGIN;
    }
}

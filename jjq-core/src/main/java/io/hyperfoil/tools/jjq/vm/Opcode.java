package io.hyperfoil.tools.jjq.vm;

public enum Opcode {
    // Constants & Stack
    PUSH_CONST,     // arg1: constant pool index
    PUSH_NULL,
    PUSH_TRUE,
    PUSH_FALSE,
    POP,
    DUP,
    SWAP,

    // Input register
    LOAD_INPUT,
    SET_INPUT,      // pop value, set as current input
    SET_INPUT_PEEK, // set input = peek() (no pop, no push — value stays on stack)

    // Field/Index access
    DOT_FIELD,      // arg1: name pool index
    INDEX,          // pop index, pop base, push base[index]
    EACH,           // pop collection, fork for each element

    // Arithmetic
    ADD, SUB, MUL, DIV, MOD, NEGATE,

    // Comparison
    EQ, NEQ, LT, GT, LE, GE,

    // Logic
    NOT,

    // Control flow
    FORK,           // arg1: target PC for backtrack
    JUMP,           // arg1: target PC
    JUMP_IF_TRUE,   // arg1: target PC
    JUMP_IF_FALSE,  // arg1: target PC
    BACKTRACK,
    OUTPUT,         // emit top of stack, then backtrack
    HALT,

    // Variables
    LOAD_VAR,       // arg1: name pool index
    STORE_VAR,      // arg1: name pool index; pops value

    // Scope
    PUSH_SCOPE,
    POP_SCOPE,

    // Collection (for array construction)
    COLLECT_BEGIN,
    COLLECT_ADD,    // add top of stack to collection, then backtrack
    COLLECT_END,    // finalize collection, push as JqArray

    // Try-catch
    TRY_BEGIN,      // arg1: catch handler PC
    TRY_END,        // pop try handler

    // Inlined builtins (avoid tree-walker overhead for common ops)
    BUILTIN_LENGTH,     // push length of input
    BUILTIN_TYPE,       // push type string of input
    BUILTIN_KEYS,       // push keys array of input
    BUILTIN_VALUES,     // push values array of input
    BUILTIN_NOT,        // push negated truthiness of input
    BUILTIN_EMPTY,      // trigger backtrack (produce no output)
    BUILTIN_TOSTRING,   // push string representation
    BUILTIN_TONUMBER,   // push numeric representation
    BUILTIN_ADD,        // fold array with add
    BUILTIN_REVERSE,    // reverse array
    BUILTIN_SORT,       // sort array
    BUILTIN_MIN,        // min of array
    BUILTIN_MAX,        // max of array
    BUILTIN_FLATTEN,    // flatten array
    BUILTIN_UNIQUE,     // unique elements
    BUILTIN_FLOOR,      // floor number
    BUILTIN_CEIL,       // ceil number
    BUILTIN_ROUND,      // round number
    BUILTIN_ABS,        // absolute value
    BUILTIN_TOJSON,     // convert to JSON string
    BUILTIN_FROMJSON,   // parse JSON string

    // Compound field lookup (fast-path for .a.b.c)
    DOT_FIELD2,         // arg1: name1 idx, arg2: name2 idx - two-level field access

    // Fused iteration opcodes (bypass backtracking for simple patterns)
    COLLECT_ITERATE,    // arg1: body length; inline iterate+map, body bytecode follows
    REDUCE_ITERATE,     // arg1: constant pool index (init), arg2: arithmetic op (0=ADD,1=SUB,2=MUL)

    // Indexed variable slots (array-based, avoids HashMap)
    LOAD_SLOT,      // arg1: slot index
    STORE_SLOT,     // arg1: slot index

    // Function/expression evaluation (delegates to tree-walker)
    CALL_FUNC,      // arg1: call info index
    EVAL_AST,       // arg1: sub-expression index
}

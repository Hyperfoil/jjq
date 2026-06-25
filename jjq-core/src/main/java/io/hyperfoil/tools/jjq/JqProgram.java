package io.hyperfoil.tools.jjq;

import io.hyperfoil.tools.jjq.ast.JqExpr;
import io.hyperfoil.tools.jjq.builtin.BuiltinRegistry;
import io.hyperfoil.tools.jjq.evaluator.Environment;
import io.hyperfoil.tools.jjq.evaluator.JqException;

import io.hyperfoil.tools.jjq.parser.Parser;
import io.hyperfoil.tools.jjq.value.JqNull;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.vm.Bytecode;
import io.hyperfoil.tools.jjq.vm.Compiler;
import io.hyperfoil.tools.jjq.vm.Opcode;
import io.hyperfoil.tools.jjq.vm.VirtualMachine;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A compiled jq program. Thread-safe: the AST is immutable.
 * Each invocation creates its own evaluator state.
 *
 * <p>The primary API uses the bytecode VM for execution:
 * <ul>
 *   <li>{@link #apply(JqValue)} — returns the first result (zero-allocation for single-output programs)</li>
 *   <li>{@link #applyAll(JqValue)} — returns all results as a list</li>
 * </ul>
 *
 */
public final class JqProgram {

    private enum ProgramShape { IDENTITY, FIELD_ACCESS, FIELD_ACCESS2, GENERAL }

    private final String expression;
    private final JqExpr ast;
    private final BuiltinRegistry builtins;
    private volatile Bytecode bytecode; // lazily compiled

    // Inlined fast-path fields — bypass ThreadLocal VM lookup for simple programs
    private final ProgramShape shape;
    private final String fastField1; // cached for FIELD_ACCESS / FIELD_ACCESS2
    private final String fastField2; // cached for FIELD_ACCESS2

    /**
     * Per-thread cached VM instance. Each thread gets its own VM to avoid
     * synchronization, and the VM resets all state on each execute() call
     * so it is safe to reuse across invocations. This eliminates 67+ object
     * allocations per call (64 BacktrackPoint objects, stack arrays, Evaluator).
     */
    private final ThreadLocal<VirtualMachine> cachedVM = new ThreadLocal<>();

    JqProgram(String expression, JqExpr ast, BuiltinRegistry builtins) {
        this.expression = expression;
        this.ast = ast;
        this.builtins = builtins;

        // Detect simple shapes and cache field names for inlined fast paths
        Bytecode bc = getBytecode();
        if (bc.size() == 3
                && bc.get(0).op() == Opcode.LOAD_INPUT
                && bc.get(1).op() == Opcode.OUTPUT
                && bc.get(2).op() == Opcode.HALT) {
            this.shape = ProgramShape.IDENTITY;
            this.fastField1 = null;
            this.fastField2 = null;
        } else if (bc.size() == 4
                && bc.get(0).op() == Opcode.LOAD_INPUT
                && bc.get(1).op() == Opcode.DOT_FIELD
                && bc.get(2).op() == Opcode.OUTPUT
                && bc.get(3).op() == Opcode.HALT) {
            this.shape = ProgramShape.FIELD_ACCESS;
            this.fastField1 = bc.name(bc.get(1).arg1());
            this.fastField2 = null;
        } else if (bc.size() == 4
                && bc.get(0).op() == Opcode.LOAD_INPUT
                && bc.get(1).op() == Opcode.DOT_FIELD2
                && bc.get(2).op() == Opcode.OUTPUT
                && bc.get(3).op() == Opcode.HALT) {
            this.shape = ProgramShape.FIELD_ACCESS2;
            this.fastField1 = bc.name(bc.get(1).arg1());
            this.fastField2 = bc.name(bc.get(1).arg2());
        } else {
            this.shape = ProgramShape.GENERAL;
            this.fastField1 = null;
            this.fastField2 = null;
        }
    }

    public static JqProgram compile(String expression) {
        return compile(expression, BuiltinRegistry.getDefault());
    }

    public static JqProgram compile(String expression, BuiltinRegistry builtins) {
        JqExpr ast = Parser.parse(expression);
        return new JqProgram(expression, ast, builtins);
    }

    // ========================================================================
    //  Primary API — bytecode VM (recommended)
    // ========================================================================

    /**
     * Execute and return the first result.
     * Zero-allocation for single-output programs (identity, field access, arithmetic, reduce, etc.).
     * For IDENTITY and FIELD_ACCESS shapes, bypasses the VM entirely — no ThreadLocal
     * lookup, no stack allocation, just direct field access.
     * Returns {@link io.hyperfoil.tools.jjq.value.JqNull#NULL} if the program produces no output.
     */
    public JqValue apply(JqValue input) {
        return switch (shape) {
            case IDENTITY -> input;
            case FIELD_ACCESS -> fieldAccess(input, fastField1);
            case FIELD_ACCESS2 -> fieldAccess(fieldAccess(input, fastField1), fastField2);
            case GENERAL -> getVM().executeOne(input);
        };
    }

    public JqValue apply(JqValue input, Environment env) {
        return getVM().executeOne(input, env != null ? env : Environment.EMPTY);
    }

    /**
     * Execute and return all results as a list.
     * Use this when the program may produce multiple outputs (e.g. {@code .[]}).
     * For IDENTITY and FIELD_ACCESS shapes, bypasses the VM entirely.
     */
    public List<JqValue> applyAll(JqValue input) {
        return switch (shape) {
            case IDENTITY -> List.of(input);
            case FIELD_ACCESS -> List.of(fieldAccess(input, fastField1));
            case FIELD_ACCESS2 -> List.of(fieldAccess(fieldAccess(input, fastField1), fastField2));
            case GENERAL -> getVM().execute(input);
        };
    }

    public List<JqValue> applyAll(JqValue input, Environment env) {
        return getVM().execute(input, env != null ? env : Environment.EMPTY);
    }

    private static JqValue fieldAccess(JqValue val, String field) {
        if (val instanceof JqObject obj) return obj.get(field);
        if (val instanceof JqNull) return JqNull.NULL;
        throw new JqException("Cannot index " + val.type().jqName() + " with string (\"" + field + "\")");
    }

    // ========================================================================
    //  Null-input API — jq's --null-input mode
    // ========================================================================

    /**
     * Execute with null input, making the given values available via the
     * {@code input} and {@code inputs} builtins. This is equivalent to
     * jq's {@code --null-input} flag when processing multiple files.
     *
     * <pre>{@code
     * var program = JqProgram.compile("[inputs | .name]");
     * List<JqValue> results = program.applyNullInput(List.of(obj1, obj2, obj3));
     * // results: [["alice", "bob", "charlie"]]
     * }</pre>
     */
    public List<JqValue> applyNullInput(List<JqValue> inputs) {
        return applyAll(JqNull.NULL, Environment.withInputs(inputs));
    }

    // ========================================================================
    //  Multi-input API — process a stream of inputs (JSONL-style)
    // ========================================================================

    /**
     * Process multiple inputs through the same filter, like jq processes JSONL.
     * Each input is evaluated independently; results are concatenated in order.
     * Reuses a single VM instance across all inputs for efficiency.
     *
     * <pre>{@code
     * var program = JqProgram.compile(".name");
     * List<JqValue> results = program.applyAll(List.of(obj1, obj2, obj3));
     * }</pre>
     */
    public List<JqValue> applyAll(Iterable<JqValue> inputs) {
        return applyAll(inputs, (Environment) null);
    }

    public List<JqValue> applyAll(Iterable<JqValue> inputs, Environment env) {
        var vm = getVM();
        var results = new ArrayList<JqValue>();
        for (JqValue input : inputs) {
            results.addAll(env != null ? vm.execute(input, env) : vm.execute(input));
        }
        return results;
    }

    /**
     * Process multiple inputs, streaming each result to a consumer.
     * Reuses a single VM instance across all inputs for efficiency.
     */
    public void applyAll(Iterable<JqValue> inputs, Consumer<JqValue> output) {
        var vm = getVM();
        for (JqValue input : inputs) {
            for (JqValue v : vm.execute(input)) {
                output.accept(v);
            }
        }
    }

    /**
     * Process a stream of inputs through the same filter.
     * Returns a lazily-evaluated stream of results. Reuses a single VM instance.
     */
    public Stream<JqValue> stream(Iterable<JqValue> inputs) {
        var vm = getVM();
        return StreamSupport.stream(inputs.spliterator(), false)
                .flatMap(input -> vm.execute(input).stream());
    }

    public Bytecode getBytecode() {
        if (bytecode == null) {
            synchronized (this) {
                if (bytecode == null) {
                    bytecode = Compiler.compile(ast);
                }
            }
        }
        return bytecode;
    }

    /**
     * Get or create a thread-local VM instance for this program.
     * The VM is reused across calls on the same thread, eliminating
     * per-call allocation of stacks, backtrack points, and evaluator.
     */
    private VirtualMachine getVM() {
        VirtualMachine vm = cachedVM.get();
        if (vm == null) {
            vm = new VirtualMachine(getBytecode(), builtins);
            cachedVM.set(vm);
        }
        return vm;
    }

    public String expression() { return expression; }
    public JqExpr ast() { return ast; }
}

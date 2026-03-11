package io.hyperfoil.tools.jjq;

import io.hyperfoil.tools.jjq.ast.JqExpr;
import io.hyperfoil.tools.jjq.builtin.BuiltinRegistry;
import io.hyperfoil.tools.jjq.evaluator.Environment;

import io.hyperfoil.tools.jjq.parser.Parser;
import io.hyperfoil.tools.jjq.value.JqNull;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.vm.Bytecode;
import io.hyperfoil.tools.jjq.vm.Compiler;
import io.hyperfoil.tools.jjq.vm.VirtualMachine;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

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
    private final String expression;
    private final JqExpr ast;
    private final BuiltinRegistry builtins;
    private volatile Bytecode bytecode; // lazily compiled

    JqProgram(String expression, JqExpr ast, BuiltinRegistry builtins) {
        this.expression = expression;
        this.ast = ast;
        this.builtins = builtins;
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
     * Returns {@link io.hyperfoil.tools.jjq.value.JqNull#NULL} if the program produces no output.
     */
    public JqValue apply(JqValue input) {
        Bytecode bc = getBytecode();
        var vm = new VirtualMachine(bc, builtins);
        return vm.executeOne(input);
    }

    public JqValue apply(JqValue input, Environment env) {
        Bytecode bc = getBytecode();
        var vm = new VirtualMachine(bc, builtins);
        return vm.executeOne(input, env != null ? env : new Environment());
    }

    /**
     * Execute and return all results as a list.
     * Use this when the program may produce multiple outputs (e.g. {@code .[]}).
     */
    public List<JqValue> applyAll(JqValue input) {
        Bytecode bc = getBytecode();
        var vm = new VirtualMachine(bc, builtins);
        return vm.execute(input);
    }

    public List<JqValue> applyAll(JqValue input, Environment env) {
        Bytecode bc = getBytecode();
        var vm = new VirtualMachine(bc, builtins);
        return vm.execute(input, env != null ? env : new Environment());
    }

    /**
     * Execute and stream results to a consumer.
     */
    public void apply(JqValue input, Consumer<JqValue> output) {
        for (JqValue v : applyAll(input)) {
            output.accept(v);
        }
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
        Bytecode bc = getBytecode();
        var vm = new VirtualMachine(bc, builtins);
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
        Bytecode bc = getBytecode();
        var vm = new VirtualMachine(bc, builtins);
        for (JqValue input : inputs) {
            for (JqValue v : vm.execute(input)) {
                output.accept(v);
            }
        }
    }

    /**
     * Process a stream of inputs through the same filter.
     * Returns a stream of results. Reuses a single VM instance.
     */
    public Stream<JqValue> stream(Iterable<JqValue> inputs) {
        Bytecode bc = getBytecode();
        var vm = new VirtualMachine(bc, builtins);
        var builder = Stream.<JqValue>builder();
        for (JqValue input : inputs) {
            for (JqValue v : vm.execute(input)) {
                builder.accept(v);
            }
        }
        return builder.build();
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

    public String expression() { return expression; }
    public JqExpr ast() { return ast; }
}

package io.hyperfoil.tools.jjq;

import io.hyperfoil.tools.jjq.ast.JqExpr;
import io.hyperfoil.tools.jjq.builtin.BuiltinRegistry;
import io.hyperfoil.tools.jjq.evaluator.Environment;
import io.hyperfoil.tools.jjq.evaluator.Evaluator;
import io.hyperfoil.tools.jjq.parser.Parser;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.vm.Bytecode;
import io.hyperfoil.tools.jjq.vm.Compiler;
import io.hyperfoil.tools.jjq.vm.VirtualMachine;

import java.util.List;
import java.util.function.Consumer;

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
 * <p>The tree-walker evaluator is available via {@link #applyTreeWalker(JqValue)} for compatibility.
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
    //  Tree-walker evaluator (alternative engine)
    // ========================================================================

    public List<JqValue> applyTreeWalker(JqValue input) {
        var evaluator = new Evaluator(builtins);
        return evaluator.eval(ast, input);
    }

    public List<JqValue> applyTreeWalker(JqValue input, Environment env) {
        var evaluator = new Evaluator(builtins);
        return evaluator.eval(ast, input, env);
    }

    public void applyTreeWalker(JqValue input, Consumer<JqValue> output) {
        var evaluator = new Evaluator(builtins);
        evaluator.eval(ast, input, new Environment(), output);
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

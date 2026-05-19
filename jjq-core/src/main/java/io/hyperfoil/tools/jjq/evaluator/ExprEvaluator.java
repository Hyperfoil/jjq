package io.hyperfoil.tools.jjq.evaluator;

import io.hyperfoil.tools.jjq.ast.JqExpr;
import io.hyperfoil.tools.jjq.builtin.BuiltinRegistry;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Narrow interface for evaluating jq sub-expressions, used by builtin functions.
 * <p>
 * This decouples builtins from the concrete tree-walk evaluator implementation,
 * which is an internal fallback used by the bytecode VM for expressions it cannot
 * compile to bytecode directly. External code should use
 * {@link io.hyperfoil.tools.jjq.JqProgram} instead.
 */
public interface ExprEvaluator {

    /** Evaluate an expression and return all results. */
    List<JqValue> eval(JqExpr expr, JqValue input, Environment env);

    /** Evaluate an expression and stream results to a consumer. */
    void eval(JqExpr expr, JqValue input, Environment env, Consumer<JqValue> output);

    /** Update a value at the given path expression. */
    JqValue updatePath(JqExpr pathExpr, JqValue input, Environment env,
                       Function<List<JqValue>, List<JqValue>> updater);

    /**
     * Create a new ExprEvaluator instance backed by the tree-walk evaluator.
     * This is intended for internal use by the bytecode VM.
     */
    static ExprEvaluator create(BuiltinRegistry builtins) {
        return new Evaluator(builtins);
    }
}

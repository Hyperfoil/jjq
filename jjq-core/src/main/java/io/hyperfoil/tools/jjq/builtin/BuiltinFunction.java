package io.hyperfoil.tools.jjq.builtin;

import io.hyperfoil.tools.jjq.ast.JqExpr;
import io.hyperfoil.tools.jjq.evaluator.Environment;
import io.hyperfoil.tools.jjq.evaluator.Evaluator;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.List;
import java.util.function.Consumer;

@FunctionalInterface
public interface BuiltinFunction {
    void apply(JqValue input, List<JqExpr> args, Environment env, Evaluator evaluator, Consumer<JqValue> output);
}

package io.hyperfoil.tools.jjq.evaluator;

import io.hyperfoil.tools.jjq.ast.JqExpr;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Environment {
    private final Environment parent;
    private final int depth;
    private final Map<String, JqValue> variables;
    private Map<String, FuncDef> functions;
    private Map<String, FilterClosure> filterArgs;
    private List<JqValue> inputs;
    private int inputIndex;

    public record FuncDef(String name, List<String> params, JqExpr body, Environment closureEnv) {}

    /**
     * A filter closure captures a filter expression and the environment + evaluator
     * needed to evaluate it. In jq, function arguments are filters (not values).
     * When def f(g): ... g ...; is called as f(. + 1), g is a closure that
     * applies ". + 1" to whatever input it receives.
     */
    public record FilterClosure(JqExpr expr, Environment capturedEnv) {}

    public Environment() {
        this(null, 0);
    }

    private Environment(Environment parent, int depth) {
        this.parent = parent;
        this.depth = depth;
        this.variables = new HashMap<>(4);
    }

    /**
     * Create an Environment with an input stream pre-configured.
     * This is a convenience for the common null-input pattern where
     * {@code input} and {@code inputs} builtins read from a list of values.
     *
     * <pre>{@code
     * var env = Environment.withInputs(List.of(val1, val2, val3));
     * var results = program.applyAll(JqNull.NULL, env);
     * }</pre>
     */
    public static Environment withInputs(List<JqValue> inputs) {
        Environment env = new Environment();
        env.setInputs(inputs);
        return env;
    }

    /**
     * Create an Environment with pre-set variables.
     * This is a convenience for the common {@code --arg} / {@code --argjson} pattern.
     *
     * <pre>{@code
     * var env = Environment.withVariables(Map.of(
     *     "name", JqString.of("Alice"),
     *     "age", JqNumber.of(30)
     * ));
     * var results = program.applyAll(input, env);
     * }</pre>
     */
    public static Environment withVariables(Map<String, JqValue> vars) {
        Environment env = new Environment();
        env.variables.putAll(vars);
        return env;
    }

    public Environment child() {
        return new Environment(this, depth + 1);
    }

    public Environment parent() {
        return parent;
    }

    public int depth() {
        return depth;
    }

    public void setVariable(String name, JqValue value) {
        variables.put(name, value);
    }

    public JqValue getVariable(String name) {
        JqValue val = variables.get(name);
        if (val != null) return val;
        if (parent != null) return parent.getVariable(name);
        throw new JqException("Undefined variable: $" + name);
    }

    /**
     * Look up a variable, returning null if not defined.
     * Unlike {@link #getVariable(String)}, this does not throw for undefined variables.
     */
    public JqValue findVariable(String name) {
        JqValue val = variables.get(name);
        if (val != null) return val;
        if (parent != null) return parent.findVariable(name);
        return null;
    }

    public boolean hasVariable(String name) {
        if (variables.containsKey(name)) return true;
        if (parent != null) return parent.hasVariable(name);
        return false;
    }

    public void defineFunction(String name, int arity, FuncDef def) {
        if (functions == null) functions = new HashMap<>(4);
        functions.put(name + "/" + arity, def);
    }

    public FuncDef getFunction(String name, int arity) {
        if (functions != null) {
            FuncDef def = functions.get(name + "/" + arity);
            if (def != null) return def;
        }
        if (parent != null) return parent.getFunction(name, arity);
        return null;
    }

    public void setFilterArg(String name, FilterClosure closure) {
        if (filterArgs == null) filterArgs = new HashMap<>(4);
        filterArgs.put(name, closure);
    }

    public FilterClosure getFilterArg(String name) {
        if (filterArgs != null) {
            FilterClosure fc = filterArgs.get(name);
            if (fc != null) return fc;
        }
        if (parent != null) return parent.getFilterArg(name);
        return null;
    }

    /**
     * Set the input stream for {@code input} and {@code inputs} builtins.
     * This provides the list of values that {@code input} reads one-at-a-time
     * and {@code inputs} reads all-at-once, matching jq's {@code --null-input}
     * with multi-file semantics.
     */
    public void setInputs(List<JqValue> inputs) {
        this.inputs = inputs;
        this.inputIndex = 0;
    }

    /**
     * Returns true if an input stream has been configured via {@link #setInputs(List)}.
     */
    public boolean hasInputs() {
        if (inputs != null) return true;
        if (parent != null) return parent.hasInputs();
        return false;
    }

    /**
     * Read the next value from the input stream (for the {@code input} builtin).
     * Returns null if no more inputs are available.
     */
    public JqValue nextInput() {
        if (inputs != null) {
            if (inputIndex < inputs.size()) {
                return inputs.get(inputIndex++);
            }
            return null;
        }
        if (parent != null) return parent.nextInput();
        return null;
    }

    /**
     * Return all remaining values from the input stream (for the {@code inputs} builtin).
     * After this call, the input stream is fully consumed.
     */
    public List<JqValue> remainingInputs() {
        if (inputs != null) {
            List<JqValue> remaining = inputs.subList(inputIndex, inputs.size());
            inputIndex = inputs.size();
            return remaining;
        }
        if (parent != null) return parent.remainingInputs();
        return List.of();
    }
}

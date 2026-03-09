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
}

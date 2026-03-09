package io.hyperfoil.tools.jjq.evaluator;

public class BreakException extends RuntimeException {
    private final String label;

    public BreakException(String label) {
        super("break $" + label);
        this.label = label;
    }

    public String label() { return label; }
}

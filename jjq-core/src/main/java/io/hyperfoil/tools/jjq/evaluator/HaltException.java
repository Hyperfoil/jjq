package io.hyperfoil.tools.jjq.evaluator;

public class HaltException extends RuntimeException {
    private final int exitCode;
    private final String errorMessage;

    public HaltException(int exitCode, String errorMessage) {
        super(errorMessage != null ? errorMessage : "halt");
        this.exitCode = exitCode;
        this.errorMessage = errorMessage;
    }

    public int exitCode() { return exitCode; }
    public String errorMessage() { return errorMessage; }
}

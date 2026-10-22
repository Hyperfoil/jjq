package io.hyperfoil.tools.jjq.xml;

/**
 * Thrown when XML parsing or emission fails.
 *
 * <p>Carries the 1-based line and column from the parser location when
 * known ({@code -1} otherwise, e.g. emission errors).</p>
 */
public class JqXmlException extends RuntimeException {

    private final int line;
    private final int column;

    public JqXmlException(String message, int line, int column) {
        super(line > 0 ? message + " at line " + line + ", column " + column : message);
        this.line = line;
        this.column = column;
    }

    public JqXmlException(String message) {
        this(message, -1, -1);
    }

    /** The 1-based line number of the failure, or -1 when unknown. */
    public int line() { return line; }

    /** The 1-based column number of the failure, or -1 when unknown. */
    public int column() { return column; }
}

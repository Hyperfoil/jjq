package io.hyperfoil.tools.jjq.yaml;

/**
 * Thrown when YAML parsing fails in strict mode ({@link YamlOptions#STRICT}),
 * currently duplicate mapping keys.
 *
 * <p>Carries the offending key name and 1-based line number so callers can
 * render friendly diagnostics.</p>
 */
public class JqYamlException extends RuntimeException {

    private final String key;
    private final int line;

    public JqYamlException(String key, int line) {
        super("found duplicate key \"" + key + "\" at line " + line);
        this.key = key;
        this.line = line;
    }

    /** The duplicated mapping key. */
    public String key() { return key; }

    /** The 1-based line number of the duplicate occurrence. */
    public int line() { return line; }
}

package io.hyperfoil.tools.jjq.yaml;

/**
 * Syntax errors from the native YAML parser: message plus 1-based line number.
 * Unchecked, mirroring SnakeYAML's unchecked failures on malformed input.
 */
public class YamlParseException extends RuntimeException {

    private final int line;

    public YamlParseException(String message, int line) {
        super(message + " at line " + line);
        this.line = line;
    }

    /** The 1-based line number where parsing failed. */
    public int line() { return line; }
}

package io.hyperfoil.tools.jjq.jsonata;

import io.hyperfoil.tools.jjq.JqProgram;

/**
 * Compile-time JSONata-to-jq transpiler. Parses a JSONata expression,
 * translates it to an equivalent jq expression, and compiles it to a
 * {@link JqProgram} for execution through jjq's bytecode VM.
 *
 * <p>The translation happens once at compile time. The resulting {@link JqProgram}
 * is thread-safe and can be reused across invocations with zero overhead —
 * the same 3 ns field access and zero-allocation queries as native jq.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Compile JSONata to jq (one-time cost)
 * JqProgram program = JsonataCompiler.compile("$sum(Price * Quantity)");
 *
 * // Execute through jjq's bytecode VM
 * JqValue result = program.apply(data);
 * }</pre>
 *
 * <h3>Inspecting the translation</h3>
 * <pre>{@code
 * String jq = JsonataCompiler.toJq("Address.City");
 * // → ".Address.City"
 * }</pre>
 *
 * <p>Unsupported JSONata features throw {@link JsonataException} at compile time.
 * No silent semantic differences — if it compiles, it produces correct results.</p>
 *
 * @see JqProgram
 */
public final class JsonataCompiler {

    private JsonataCompiler() {}

    /**
     * Compile a JSONata expression to a {@link JqProgram}.
     * The expression is parsed, translated to jq, and compiled to bytecode.
     *
     * @param jsonataExpression the JSONata expression to compile
     * @return a compiled JqProgram ready for execution
     * @throws JsonataException if the expression contains unsupported JSONata features
     *         or has syntax errors
     */
    public static JqProgram compile(String jsonataExpression) {
        String jq = toJq(jsonataExpression);
        try {
            return JqProgram.compile(jq);
        } catch (Exception e) {
            throw new JsonataException(
                    "Failed to compile translated jq expression: " + jq
                    + " (from JSONata: " + jsonataExpression + ")", e);
        }
    }

    /**
     * Translate a JSONata expression to its jq equivalent without compiling.
     * Useful for inspection, debugging, and testing the translation.
     *
     * @param jsonataExpression the JSONata expression to translate
     * @return the equivalent jq expression string
     * @throws JsonataException if the expression contains unsupported features
     */
    public static String toJq(String jsonataExpression) {
        if (jsonataExpression == null || jsonataExpression.isBlank()) {
            return ".";
        }
        var ast = JsonataParser.parse(jsonataExpression);
        return JsonataToJq.translate(ast);
    }
}

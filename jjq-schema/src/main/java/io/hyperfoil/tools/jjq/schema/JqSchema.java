package io.hyperfoil.tools.jjq.schema;

import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A compiled JSON Schema validator over {@link JqValue} trees
 * (issue #167, pragmatic 2020-12 subset with draft-07 compatibility).
 *
 * <p>Compile once with {@link #compile(JqValue)}, then share the result
 * across documents and threads: the compiled graph is immutable and holds
 * no per-validation state. Validation never reparses, coerces, mutates,
 * or inserts defaults into the instance.</p>
 *
 * <pre>{@code
 * JqSchema schema = JqSchema.compile(schemaTree);
 * if (!schema.isValid(document)) {
 *     JqArray errors = schema.validate(document);
 * }
 * }</pre>
 */
public final class JqSchema {

    private final SchemaNode root;

    private JqSchema(SchemaNode root) {
        this.root = root;
    }

    /**
     * Compile a schema tree into a reusable validator.
     *
     * @param schema the schema: a boolean, or an object of keywords
     * @return the compiled validator
     * @throws JqSchemaException on malformed schemas, unresolved references,
     *                           unsupported dialects, or recognized-but-unsupported features
     */
    public static JqSchema compile(JqValue schema) {
        Objects.requireNonNull(schema, "schema must not be null");
        return new JqSchema(new SchemaCompiler().compile(schema));
    }

    /**
     * Check an instance without building diagnostics.
     *
     * @param instance the document to check
     * @return true when the instance validates against the schema
     */
    public boolean isValid(JqValue instance) {
        Objects.requireNonNull(instance, "instance must not be null");
        return root.isValid(instance);
    }

    /**
     * Validate an instance, collecting diagnostics on failure.
     *
     * @param instance the document to check
     * @return an empty array when valid, otherwise one error object per
     *         failure with {@code instanceLocation}, {@code keywordLocation},
     *         {@code keyword}, and {@code message} fields
     */
    public JqArray validate(JqValue instance) {
        Objects.requireNonNull(instance, "instance must not be null");
        if (root.isValid(instance)) return JqArray.EMPTY;
        List<JqValue> errors = new ArrayList<>();
        root.validateInto(instance, "", errors);
        return JqArray.ofTrusted(errors);
    }
}

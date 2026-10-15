package io.hyperfoil.tools.jjq.value;

import java.util.Objects;

/**
 * Strict-parse options for {@link JqValues#parseStrict(String, ParseOptions)}
 * and {@link JqValues#parseStrict(byte[], ParseOptions)}.
 *
 * <p>Immutable and thread-safe: build once, share freely. The defaults match
 * {@code parseStrict} today (10000 nesting levels, BOMs stripped, NUL bytes
 * allowed), so existing callers are unaffected; adapters with stricter
 * contracts (e.g. serde-compatible layers rejecting deep nesting,
 * byte-order marks, and NUL bytes) narrow from there:</p>
 *
 * <pre>{@code
 * ParseOptions opts = ParseOptions.defaults()
 *         .withMaxDepth(128)
 *         .withBomPolicy(ParseOptions.BomPolicy.REJECT)
 *         .withNulPolicy(ParseOptions.NulPolicy.REJECT);
 * }</pre>
 */
public final class ParseOptions {

    /** What to do with a byte-order mark where stripping applies. */
    public enum BomPolicy {
        /** Skip the mark silently (current behavior). */
        STRIP,
        /** Fail with a positioned error instead. */
        REJECT
    }

    /** What to do with raw NUL bytes/characters in the input. */
    public enum NulPolicy {
        /** Bind them like any other content (current behavior). */
        ALLOW,
        /** Fail with a positioned error instead. */
        REJECT
    }

    private final int maxDepth;
    private final BomPolicy bomPolicy;
    private final NulPolicy nulPolicy;

    private ParseOptions(int maxDepth, BomPolicy bomPolicy, NulPolicy nulPolicy) {
        this.maxDepth = maxDepth;
        this.bomPolicy = bomPolicy;
        this.nulPolicy = nulPolicy;
    }

    /**
     * The default options: the current {@code parseStrict} behavior.
     *
     * @return options with depth 10000, BOM stripping, NUL allowed
     */
    public static ParseOptions defaults() {
        return new ParseOptions(JqValues.MAX_PARSE_DEPTH, BomPolicy.STRIP, NulPolicy.ALLOW);
    }

    /**
     * Maximum tolerated nesting depth for arrays and objects (a document
     * nested deeper fails). Must be at least 1.
     *
     * @param maxDepth the depth limit
     * @return new options with this limit
     * @throws IllegalArgumentException if {@code maxDepth} is less than 1
     */
    public ParseOptions withMaxDepth(int maxDepth) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be >= 1, got " + maxDepth);
        }
        return new ParseOptions(maxDepth, bomPolicy, nulPolicy);
    }

    /**
     * Byte-order-mark handling.
     *
     * @param bomPolicy the policy (non-null)
     * @return new options with this policy
     * @throws NullPointerException if {@code bomPolicy} is null
     */
    public ParseOptions withBomPolicy(BomPolicy bomPolicy) {
        Objects.requireNonNull(bomPolicy, "bomPolicy");
        return new ParseOptions(maxDepth, bomPolicy, nulPolicy);
    }

    /**
     * Raw-NUL handling (escaped {@code \u0000} sequences contain no raw
     * NUL and always stay legal).
     *
     * @param nulPolicy the policy (non-null)
     * @return new options with this policy
     * @throws NullPointerException if {@code nulPolicy} is null
     */
    public ParseOptions withNulPolicy(NulPolicy nulPolicy) {
        Objects.requireNonNull(nulPolicy, "nulPolicy");
        return new ParseOptions(maxDepth, bomPolicy, nulPolicy);
    }

    /**
     * @return the maximum tolerated nesting depth
     */
    public int maxDepth() {
        return maxDepth;
    }

    /**
     * @return the byte-order-mark policy
     */
    public BomPolicy bomPolicy() {
        return bomPolicy;
    }

    /**
     * @return the raw-NUL policy
     */
    public NulPolicy nulPolicy() {
        return nulPolicy;
    }
}

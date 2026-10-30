package io.hyperfoil.tools.jjq.value;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Caller-compiled expected object keys for strict parsing (issue #1
 * exploration): compile once per stable shape (REST endpoint DTO,
 * repeated feed), reuse across requests.
 *
 * <p>Keys are interned at compile time, so a byte-exact hit returns the
 * canonical instance with no hash computation and no table probes. Any
 * mismatch (different order, extra keys, escaped spellings, longer or
 * shorter input) falls back to the full interning path per key, and
 * duplicate detection runs unchanged — the schema is an expectation
 * about untrusted input, never a guarantee. Pass {@code null} (or use
 * the two-argument overloads) for today's behavior, byte-identical.</p>
 */
public final class CompiledKeys {

    final String[] keys;
    final byte[][] bytes;

    private CompiledKeys(String[] keys, byte[][] bytes) {
        this.keys = keys;
        this.bytes = bytes;
    }

    /**
     * Compile expected keys. Each name is interned once here, so later
     * hits resolve to canonical instances.
     *
     * @param keys the expected key names in order (non-null, non-empty entries)
     * @return the compiled schema (never null)
     * @throws NullPointerException if {@code keys} or an entry is null
     */
    public static CompiledKeys of(String... keys) {
        Objects.requireNonNull(keys, "keys");
        String[] canonical = new String[keys.length];
        byte[][] encodings = new byte[keys.length][];
        for (int i = 0; i < keys.length; i++) {
            Objects.requireNonNull(keys[i], "keys[" + i + "]");
            canonical[i] = JqValues.internFieldName(keys[i]);
            encodings[i] = keys[i].getBytes(StandardCharsets.UTF_8);
        }
        return new CompiledKeys(canonical, encodings);
    }

    /** @return the number of compiled keys */
    public int size() {
        return keys.length;
    }
}

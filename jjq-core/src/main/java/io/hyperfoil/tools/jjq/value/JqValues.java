package io.hyperfoil.tools.jjq.value;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility for parsing JSON strings into JqValue without external dependencies.
 * <p>
 * Parsing uses a lightweight inner class with a mutable position field to avoid
 * array-access overhead on every character.
 */
public final class JqValues {

    // Package-visible for ParseOptions.defaults(): the single canonical depth.
    static final int MAX_PARSE_DEPTH = 10000;
    private static final int MAX_SERIALIZE_DEPTH = 10001;
    private static final int SERIALIZE_BUFFER_INIT = 8192;
    private static final int SERIALIZE_BUFFER_MAX_RETAINED = 1024 * 1024; // 1MB

    // ========================================================================
    //  Field name interning (issue #10)
    //  Open-addressing hash table for deduplicating JSON object key strings.
    //  Reduces allocation for repeated schemas and enables reference equality
    //  in JqObject.get() via String.equals short-circuit (this == other).
    // ========================================================================

    private static final int INTERN_TABLE_SIZE = 4096; // power of 2
    private static final int INTERN_MASK = INTERN_TABLE_SIZE - 1;
    private static final int INTERN_MAX_PROBES = 8; // linear probing depth before eviction

    /**
     * Immutable snapshot of all interned data for a single field name slot.
     * Stored as a single reference in the intern table — atomic writes prevent
     * torn reads under concurrent parsing (see issue #50).
     */
    /**
     * All interned data for a single field name slot. Key identity fields are
     * final (safe publication via the table's atomic reference stores, issue
     * #50); the JSON serialization forms are computed lazily on first
     * {@link #internedJsonKey} use so parse-only workloads never pay for them.
     * Duplicate lazy computation under races is benign (identical content).
     */
    private static final class InternSlot {
        final String key;
        final byte[] keyBytes;
        final int hash;
        final int q1;
        final int q2;
        final int q3;
        final int qlen;
        volatile String jsonKey;
        volatile byte[] jsonKeyBytes;

        InternSlot(String key, byte[] keyBytes, int hash, int q1, int q2, int q3, int qlen) {
            this.key = key;
            this.keyBytes = keyBytes;
            this.hash = hash;
            this.q1 = q1;
            this.q2 = q2;
            this.q3 = q3;
            this.qlen = qlen;
        }
    }

    /** Lazily computed {@code ""key":"} form (see class note above). */
    private static String jsonKeyOf(InternSlot slot) {
        String j = slot.jsonKey;
        if (j == null) {
            j = buildJsonKey(slot.key);
            slot.jsonKey = j;
        }
        return j;
    }

    /** Lazily computed UTF-8 bytes of {@link #jsonKeyOf}. */
    private static byte[] jsonKeyBytesOf(InternSlot slot) {
        byte[] b = slot.jsonKeyBytes;
        if (b == null) {
            b = jsonKeyOf(slot).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            slot.jsonKeyBytes = b;
        }
        return b;
    }

    private static final InternSlot[] INTERN_SLOTS = new InternSlot[INTERN_TABLE_SIZE];

    // Per-thread L1 intern cache — eliminates cross-thread eviction (issue #55).
    // Each thread has its own 256-slot cache with 2-probe linear probing.
    // L1 hits return immediately without touching the shared L2 table.
    private static final int L1_SIZE = 256;
    private static final int L1_MASK = L1_SIZE - 1;
    private static final int L1_MAX_PROBES = 2;
    private static final ThreadLocal<InternSlot[]> INTERN_L1 =
            ThreadLocal.withInitial(() -> new InternSlot[L1_SIZE]);

    /**
     * Intern a field name from a byte range, without materializing an
     * intermediate String on lookup hits. The range must hold the key's UTF-8
     * bytes (no surrounding quotes or whitespace).
     *
     * <p>ASCII ranges hash inline (identical to {@link String#hashCode}); ranges
     * containing non-ASCII bytes fall back to materializing once and delegating
     * to {@link #internFieldName(String)}. Returned instances come from the
     * same shared table in both cases.</p>
     *
     * @param src   source buffer holding the key bytes
     * @param start start offset (inclusive) of the key
     * @param end   end offset (exclusive) of the key
     * @return the interned (canonical) key instance
     */
    public static String internFieldName(byte[] src, int start, int end) {
        int hash = 0;
        int p = start;
        while (p < end) {
            int b = src[p] & 0xFF;
            if (b >= 0x80) {
                return internFieldName(new String(src, start, end - start,
                        java.nio.charset.StandardCharsets.UTF_8));
            }
            hash = hash * 31 + b;
            p++;
        }
        return internKeyWithHash(src, start, end, hash);
    }

    /**
     * Intern a field name string. Returns the cached instance if one exists
     * for this hash slot, or caches and returns the given string.
     * Also pre-computes the JSON key serialization form {@code "\"key\":"}.
     * <p>
     * Thread-safe: each slot is an immutable {@link InternSlot} record written
     * as a single atomic reference. Concurrent writes to the same slot are benign
     * (worst case: one write is lost, no corruption or torn reads).
     */
    public static String internFieldName(String name) {
        int hash = name.hashCode();
        // L1 lookup (thread-local, no contention)
        InternSlot[] l1 = INTERN_L1.get();
        for (int p = 0; p < L1_MAX_PROBES; p++) {
            InternSlot cached = l1[(mix(hash) + p) & L1_MASK];
            if (cached != null && name.equals(cached.key)) return cached.key;
        }
        // L2 lookup (global, atomic snapshots)
        for (int probe = 0; probe < INTERN_MAX_PROBES; probe++) {
            int s = (mix(hash) + probe) & INTERN_MASK;
            InternSlot slot = INTERN_SLOTS[s];
            if (slot == null) {
                InternSlot newSlot = createInternSlot(name, hash);
                INTERN_SLOTS[s] = newSlot;
                l1[mix(hash) & L1_MASK] = newSlot; // promote to L1
                return name;
            }
            if (name.equals(slot.key)) {
                l1[mix(hash) & L1_MASK] = slot; // promote to L1
                return slot.key;
            }
        }
        // All probes occupied — evict first slot
        int s = mix(hash) & INTERN_MASK;
        InternSlot newSlot = createInternSlot(name, hash);
        INTERN_SLOTS[s] = newSlot;
        l1[mix(hash) & L1_MASK] = newSlot; // promote to L1
        return name;
    }

    private static InternSlot createInternSlot(String name, int hash) {
        byte[] keyBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int qlen = keyBytes.length;
        int q1 = qlen >= 4 ? SwarUtil.loadInt(keyBytes, 0) : SwarUtil.packPartialQuad(keyBytes, 0, qlen);
        int q2 = qlen >= 8 ? SwarUtil.loadInt(keyBytes, 4) : (qlen > 4 ? SwarUtil.packPartialQuad(keyBytes, 4, qlen - 4) : 0);
        int q3 = qlen >= 12 ? SwarUtil.loadInt(keyBytes, 8) : (qlen > 8 ? SwarUtil.packPartialQuad(keyBytes, 8, qlen - 8) : 0);
        return new InternSlot(name, keyBytes, hash, q1, q2, q3, qlen);
    }

    /**
     * Look up the pre-computed JSON key form for an interned field name.
     * Returns {@code "\"key\":"} if the key is in the intern cache, or null
     * if it's not interned or was evicted by a hash collision.
     * <p>
     * The caller must pass a key that was returned by {@link #internFieldName}
     * or one of the {@code parseAndInternKey} methods. Reference equality
     * is used for the cache check (no content comparison).
     */
    public static String internedJsonKey(String key) {
        int hash = key.hashCode();
        // L1 lookup (reference equality — interned keys match by identity)
        InternSlot[] l1 = INTERN_L1.get();
        for (int p = 0; p < L1_MAX_PROBES; p++) {
            InternSlot cached = l1[(mix(hash) + p) & L1_MASK];
            if (cached != null && cached.key == key) return jsonKeyOf(cached);
        }
        // L2 lookup
        for (int probe = 0; probe < INTERN_MAX_PROBES; probe++) {
            int s = (mix(hash) + probe) & INTERN_MASK;
            InternSlot slot = INTERN_SLOTS[s];
            if (slot == null) break;
            if (slot.key == key) {
                l1[mix(hash) & L1_MASK] = slot; // promote to L1
                return jsonKeyOf(slot);
            }
        }
        return null;
    }

    /**
     * Look up the pre-computed JSON key form as UTF-8 bytes for an interned field name.
     * Returns the byte form of {@code "\"key\":"} if interned, or null otherwise.
     */
    static byte[] internedJsonKeyBytes(String key) {
        int hash = key.hashCode();
        // L1 lookup
        InternSlot[] l1 = INTERN_L1.get();
        for (int p = 0; p < L1_MAX_PROBES; p++) {
            InternSlot cached = l1[(mix(hash) + p) & L1_MASK];
            if (cached != null && cached.key == key) return jsonKeyBytesOf(cached);
        }
        // L2 lookup
        for (int probe = 0; probe < INTERN_MAX_PROBES; probe++) {
            int s = (mix(hash) + probe) & INTERN_MASK;
            InternSlot slot = INTERN_SLOTS[s];
            if (slot == null) break;
            if (slot.key == key) {
                l1[mix(hash) & L1_MASK] = slot; // promote to L1
                return jsonKeyBytesOf(slot);
            }
        }
        return null;
    }

    /** Build the JSON serialization form for an object key: {@code "\"key\":"}. */
    private static String buildJsonKey(String name) {
        // Check if key needs escaping (rare for field names)
        boolean clean = true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '"' || c == '\\' || c < 0x20) {
                clean = false;
                break;
            }
        }
        if (clean) {
            return "\"" + name + "\":";
        }
        // Rare: field name needs escaping
        var sb = new StringBuilder(name.length() + 4);
        sb.append('"');
        JqString.escapeJson(name, sb);
        sb.append("\":");
        return sb.toString();
    }

    private static final ThreadLocal<StringBuilder> SERIALIZER_BUFFER =
            ThreadLocal.withInitial(() -> new StringBuilder(SERIALIZE_BUFFER_INIT));

    private static final ThreadLocal<BytOutput> BYTE_SERIALIZER_BUFFER =
            ThreadLocal.withInitial(() -> new BytOutput(SERIALIZE_BUFFER_INIT));

    // Only the uncommon number fallback uses this buffer. Bound retention so
    // one unusually long literal does not enlarge every subsequent parse.
    private static final int MAX_NUMBER_BUFFER_CHARS = 4096;
    private static final ThreadLocal<char[]> NUMBER_BUFFER =
            ThreadLocal.withInitial(() -> new char[64]);

    /**
     * Spread hash bits before masking. Similar field names (e.g. "...-cpu0", "...-cpu1")
     * differ only in low bits of the polynomial hash, which clusters them into adjacent
     * slots and fills linear-probe windows.
     */
    private static int mix(int h) {
        h *= 0x9E3779B1;
        return h ^ (h >>> 16);
    }

    private JqValues() {}

    /**
     * Append a JSON-quoted, escaped string to a StringBuilder.
     * Writes the opening quote, the escaped content, and the closing quote.
     * Null values are written as the JSON literal {@code null} (unquoted).
     *
     * <p>Used by generated mapping classes for direct-to-JSON serialization
     * without building intermediate JqValue objects.</p>
     *
     * @param sb the target StringBuilder
     * @param value the string to write (may be null)
     */
    public static void appendJsonString(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        JqString.escapeJson(value, sb);
        sb.append('"');
    }

    /**
     * Append a {@link Character} as JSON to a StringBuilder.
     * Null renders as the JSON literal {@code null} (unquoted),
     * matching JqValue tree serialization.
     *
     * @param sb the target StringBuilder
     * @param value the character to write (may be null)
     */
    public static void appendJsonString(StringBuilder sb, Character value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        appendJsonString(sb, String.valueOf((char) value));
    }

    /**
     * Append a double to a StringBuilder in jq's plain notation.
     * Integral values serialize without a decimal point (3.0 becomes "3"),
     * matching {@link JqNumber} tree serialization exactly.
     *
     * @param sb the target StringBuilder
     * @param value the double to write
     */
    public static void appendJsonDouble(StringBuilder sb, double value) {
        JqNumber.appendDouble(sb, value);
    }

    /**
     * Append a boxed {@link Double} as JSON, rendering null as the
     * JSON literal {@code null}.
     *
     * @param sb the target StringBuilder
     * @param value the double to write (may be null)
     */
    public static void appendJsonDouble(StringBuilder sb, Double value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        JqNumber.appendDouble(sb, (double) value);
    }

    /**
     * Append a float as JSON in jq's plain notation.
     *
     * @param sb the target StringBuilder
     * @param value the float to write
     */
    public static void appendJsonDouble(StringBuilder sb, float value) {
        JqNumber.appendDouble(sb, (double) value);
    }

    /**
     * Append a boxed {@link Float} as JSON, rendering null as the
     * JSON literal {@code null}.
     *
     * @param sb the target StringBuilder
     * @param value the float to write (may be null)
     */
    public static void appendJsonDouble(StringBuilder sb, Float value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        JqNumber.appendDouble(sb, (double) value);
    }

    /**
     * Append a {@link java.math.BigDecimal} as JSON.
     * Null renders as the JSON literal {@code null}. Non-null values use
     * {@link JqNumber} formatting (trailing-zero stripping), matching tree
     * serialization exactly.
     *
     * @param sb the target StringBuilder
     * @param value the decimal to write (may be null)
     */
    public static void appendJsonDecimal(StringBuilder sb, java.math.BigDecimal value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        JqNumber.of(value).appendTo(sb);
    }

    /**
     * Append a {@link JqValue} as JSON, rendering a null reference as the
     * JSON literal {@code null} (record fields of JqValue type may hold null
     * when populated reflectively or via converters).
     *
     * @param sb the target StringBuilder
     * @param value the value to write (may be null)
     */
    public static void appendJqValue(StringBuilder sb, JqValue value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        value.appendTo(sb);
    }

    /**
     * Append a JSON-quoted, escaped string to a {@link BytOutput} buffer.
     * Null renders as the JSON literal {@code null} (unquoted).
     *
     * @param out the target byte buffer
     * @param value the string to write (may be null)
     */
    public static void appendJsonString(BytOutput out, String value) {
        if (value == null) {
            out.writeNull();
            return;
        }
        out.writeJsonString(value);
    }

    /**
     * Append a {@link Character} as JSON bytes.
     * Null renders as the JSON literal {@code null} (unquoted).
     *
     * @param out the target byte buffer
     * @param value the character to write (may be null)
     */
    public static void appendJsonString(BytOutput out, Character value) {
        if (value == null) {
            out.writeNull();
            return;
        }
        out.writeJsonString(String.valueOf((char) value));
    }

    /**
     * Append a double as JSON bytes in jq's plain notation.
     *
     * @param out the target byte buffer
     * @param value the double to write
     */
    public static void appendJsonDouble(BytOutput out, double value) {
        out.writeDouble(value);
    }

    /**
     * Append a boxed {@link Double} as JSON bytes, rendering null as the
     * JSON literal {@code null}.
     *
     * @param out the target byte buffer
     * @param value the double to write (may be null)
     */
    public static void appendJsonDouble(BytOutput out, Double value) {
        if (value == null) {
            out.writeNull();
            return;
        }
        out.writeDouble((double) value);
    }

    /**
     * Append a float as JSON bytes in jq's plain notation.
     *
     * @param out the target byte buffer
     * @param value the float to write
     */
    public static void appendJsonDouble(BytOutput out, float value) {
        out.writeDouble((double) value);
    }

    /**
     * Append a boxed {@link Float} as JSON bytes, rendering null as the
     * JSON literal {@code null}.
     *
     * @param out the target byte buffer
     * @param value the float to write (may be null)
     */
    public static void appendJsonDouble(BytOutput out, Float value) {
        if (value == null) {
            out.writeNull();
            return;
        }
        out.writeDouble((double) value);
    }

    /**
     * Append a {@link java.math.BigDecimal} as JSON bytes.
     * Null renders as the JSON literal {@code null}. Non-null values use
     * {@link JqNumber} formatting, matching tree serialization exactly.
     *
     * @param out the target byte buffer
     * @param value the decimal to write (may be null)
     */
    public static void appendJsonDecimal(BytOutput out, java.math.BigDecimal value) {
        if (value == null) {
            out.writeNull();
            return;
        }
        JqNumber.of(value).appendToBytes(out);
    }

    /**
     * Append a boxed {@link Integer} as JSON bytes, rendering null as the
     * JSON literal {@code null}.
     *
     * @param out the target byte buffer
     * @param value the integer to write (may be null)
     */
    public static void appendJsonLong(BytOutput out, Integer value) {
        if (value == null) {
            out.writeNull();
            return;
        }
        out.writeLong((long) value);
    }

    /**
     * Append a boxed {@link Long} as JSON bytes, rendering null as the
     * JSON literal {@code null}.
     *
     * @param out the target byte buffer
     * @param value the long to write (may be null)
     */
    public static void appendJsonLong(BytOutput out, Long value) {
        if (value == null) {
            out.writeNull();
            return;
        }
        out.writeLong((long) value);
    }

    /**
     * Append a boxed {@link Short} as JSON bytes, rendering null as the
     * JSON literal {@code null}.
     *
     * @param out the target byte buffer
     * @param value the short to write (may be null)
     */
    public static void appendJsonLong(BytOutput out, Short value) {
        if (value == null) {
            out.writeNull();
            return;
        }
        out.writeLong((long) value);
    }

    /**
     * Append a boxed {@link Byte} as JSON bytes, rendering null as the
     * JSON literal {@code null}.
     *
     * @param out the target byte buffer
     * @param value the byte to write (may be null)
     */
    public static void appendJsonLong(BytOutput out, Byte value) {
        if (value == null) {
            out.writeNull();
            return;
        }
        out.writeLong((long) value);
    }

    /**
     * Append a boxed {@link Boolean} as JSON bytes, rendering null as the
     * JSON literal {@code null}.
     *
     * @param out the target byte buffer
     * @param value the boolean to write (may be null)
     */
    public static void appendJsonBoolean(BytOutput out, Boolean value) {
        if (value == null) {
            out.writeNull();
        } else if (value) {
            out.writeTrue();
        } else {
            out.writeFalse();
        }
    }

    /**
     * Append a {@link JqValue} as JSON bytes, rendering a null reference as
     * the JSON literal {@code null}.
     *
     * @param out the target byte buffer
     * @param value the value to write (may be null)
     */
    public static void appendJqValue(BytOutput out, JqValue value) {
        if (value == null) {
            out.writeNull();
            return;
        }
        value.appendToBytes(out);
    }

    /**
     * Return the thread-local byte buffer for JSON serialization.
     * The caller must hand the buffer to {@link #releaseByteBuffer(BytOutput)}
     * or {@link #writeByteBuffer(BytOutput, java.io.OutputStream)} after use.
     *
     * @return a reset BytOutput ready for use
     */
    public static BytOutput acquireByteBuffer() {
        BytOutput out = BYTE_SERIALIZER_BUFFER.get();
        out.reset();
        return out;
    }

    /**
     * Release the thread-local byte buffer, returning its contents as a
     * freshly allocated array (safe ownership transfer — the thread-local
     * buffer is never aliased to the caller).
     *
     * @param out the buffer previously obtained from {@link #acquireByteBuffer()}
     * @return the serialized JSON bytes
     */
    public static byte[] releaseByteBuffer(BytOutput out) {
        byte[] result = java.util.Arrays.copyOf(out.buf, out.pos);
        if (out.buf.length > SERIALIZE_BUFFER_MAX_RETAINED) {
            BYTE_SERIALIZER_BUFFER.set(new BytOutput(SERIALIZE_BUFFER_INIT));
        }
        return result;
    }

    /**
     * Write the contents of a byte buffer to an OutputStream without copying,
     * then perform the thread-local retention check.
     *
     * @param out the buffer previously obtained from {@link #acquireByteBuffer()}
     * @param os the target stream
     * @throws java.io.IOException if the stream write fails
     */
    public static void writeByteBuffer(BytOutput out, java.io.OutputStream os) throws java.io.IOException {
        os.write(out.buf, 0, out.pos);
        if (out.buf.length > SERIALIZE_BUFFER_MAX_RETAINED) {
            BYTE_SERIALIZER_BUFFER.set(new BytOutput(SERIALIZE_BUFFER_INIT));
        }
    }

    /**
     * Return the thread-local StringBuilder for JSON serialization.
     * The caller must call {@link #releaseSerializerBuffer(StringBuilder)} after use.
     *
     * <p>Used by generated mapping classes and JqMapper for direct-to-JSON
     * serialization without allocating a new StringBuilder per call.</p>
     *
     * @return a reset StringBuilder ready for use
     */
    public static StringBuilder acquireSerializerBuffer() {
        StringBuilder sb = SERIALIZER_BUFFER.get();
        sb.setLength(0);
        return sb;
    }

    /**
     * Release the thread-local StringBuilder after use.
     * If the buffer grew beyond the retention limit, replaces it with a fresh buffer.
     *
     * @param sb the StringBuilder previously obtained from {@link #acquireSerializerBuffer()}
     * @return the serialized JSON string
     */
    public static String releaseSerializerBuffer(StringBuilder sb) {
        String result = sb.toString();
        if (sb.capacity() > SERIALIZE_BUFFER_MAX_RETAINED) {
            SERIALIZER_BUFFER.set(new StringBuilder(SERIALIZE_BUFFER_INIT));
        }
        return result;
    }

    /**
     * Serialize a JqValue to a JSON string using a thread-local StringBuilder.
     * The buffer is reused across calls on the same thread, eliminating per-call
     * StringBuilder allocation. For nested structures, {@link JqValue#appendTo}
     * writes directly into the shared buffer without intermediate allocations.
     * <p>
     * If the buffer grows beyond 1MB (e.g., for a 14MB document), it is replaced
     * with a fresh buffer after serialization to avoid retaining excessive memory.
     */
    public static String serialize(JqValue value) {
        StringBuilder sb = SERIALIZER_BUFFER.get();
        sb.setLength(0);
        // Pre-size the buffer to avoid repeated doubling during serialization.
        // estimatedSizeInBytes() returns the source byte length for parsed values,
        // which is a good approximation of the serialized output size.
        int estimate = value.estimatedSizeInBytes();
        if (estimate > 1 && estimate > sb.capacity()) {
            sb.ensureCapacity(estimate);
        }
        value.appendTo(sb);
        String result = sb.toString();
        if (sb.capacity() > SERIALIZE_BUFFER_MAX_RETAINED) {
            SERIALIZER_BUFFER.set(new StringBuilder(SERIALIZE_BUFFER_INIT));
        }
        return result;
    }

    /**
     * Serialize a JqValue directly to a Writer without constructing an intermediate String.
     * Uses the thread-local StringBuilder as a buffer, then writes its contents directly
     * to the Writer via {@link Writer#append(CharSequence)}, avoiding the {@code toString()}
     * copy that {@link #serialize(JqValue)} performs.
     */
    public static void serializeTo(JqValue value, Writer writer) throws IOException {
        StringBuilder sb = SERIALIZER_BUFFER.get();
        sb.setLength(0);
        int estimate = value.estimatedSizeInBytes();
        if (estimate > 1 && estimate > sb.capacity()) {
            sb.ensureCapacity(estimate);
        }
        value.appendTo(sb);
        writer.append(sb);
        if (sb.capacity() > SERIALIZE_BUFFER_MAX_RETAINED) {
            SERIALIZER_BUFFER.set(new StringBuilder(SERIALIZE_BUFFER_INIT));
        }
    }

    /**
     * Serialize a JqValue directly to an OutputStream as UTF-8 bytes.
     * Uses the direct byte serialization path — no intermediate String or StringBuilder.
     * Deferred-bytes string values are copied as raw bytes (zero encoding overhead).
     */
    public static void serializeTo(JqValue value, OutputStream out) throws IOException {
        SerializedBytes result = serializeToByteOutput(value);
        out.write(result.data, 0, result.length);
    }

    /**
     * Serialize a JqValue directly to a UTF-8 byte array.
     * This is the most efficient serialization path — no intermediate String allocation.
     *
     * <p>Key optimizations vs {@code toJsonString().getBytes(UTF_8)}:</p>
     * <ul>
     *   <li>Deferred-bytes string values copy raw source bytes (zero UTF-8 encoding)</li>
     *   <li>Interned field names use pre-computed byte forms</li>
     *   <li>Numbers serialize directly to ASCII bytes (no StringBuilder)</li>
     *   <li>Thread-local byte buffer reused across calls</li>
     *   <li>Only one allocation: the final result {@code byte[]}</li>
     * </ul>
     *
     * @param value the JqValue to serialize
     * @return UTF-8 encoded JSON byte array
     */
    public static byte[] serializeToBytes(JqValue value) {
        int estimate = value.estimatedSizeInBytes();
        if (estimate > 1) {
            // Known size — allocate exact buffer, serialize into it.
            // If the estimate is exact (common for parse→serialize pass-through),
            // the buffer IS the result — zero copy.
            BytOutput out = new BytOutput(new byte[estimate]);
            value.appendToBytes(out);
            if (out.pos == out.buf.length) {
                return out.buf; // exact fit — no copy
            }
            return out.toByteArray(); // estimate was off — trim
        }
        // Unknown size — use thread-local with copy
        BytOutput out = BYTE_SERIALIZER_BUFFER.get();
        out.reset();
        value.appendToBytes(out);
        byte[] result = out.toByteArray();
        if (out.buf.length > SERIALIZE_BUFFER_MAX_RETAINED) {
            BYTE_SERIALIZER_BUFFER.set(new BytOutput(SERIALIZE_BUFFER_INIT));
        }
        return result;
    }

    /**
     * Result of serializing a JqValue to bytes without a final array copy.
     * The {@code data} array may be larger than {@code length} — callers must
     * only read bytes {@code data[0..length-1]}.
     *
     * <p>Use this to avoid the defensive copy that {@link #serializeToBytes(JqValue)}
     * performs for exact-size trimming. Designed for JDBC {@code setBinaryStream}
     * and streaming I/O where the caller can provide an offset+length.</p>
     *
     * @param data   the byte array containing the serialized JSON (may be oversized)
     * @param length the number of valid bytes in {@code data}
     */
    public record SerializedBytes(byte[] data, int length) {}

    /**
     * Serialize a JqValue to a byte buffer without final array copy.
     * Returns a {@link SerializedBytes} containing the raw buffer and valid length.
     *
     * <p>For values with known size ({@link JqValue#estimatedSizeInBytes()} > 1),
     * allocates a pre-sized buffer. For others, uses the thread-local buffer and
     * copies (same as {@link #serializeToBytes(JqValue)}).</p>
     *
     * @param value the JqValue to serialize
     * @return buffer and length — the buffer may be larger than length
     */
    public static SerializedBytes serializeToByteOutput(JqValue value) {
        int estimate = value.estimatedSizeInBytes();
        if (estimate > 1) {
            // Known size — allocate exact buffer, serialize into it
            BytOutput out = new BytOutput(new byte[estimate]);
            value.appendToBytes(out);
            return new SerializedBytes(out.buf, out.pos);
        }
        // Unknown size — serialize and copy (can't return thread-local buffer)
        byte[] result = serializeToBytes(value);
        return new SerializedBytes(result, result.length);
    }

    // ========================================================================
    //  Pretty-print serialization
    // ========================================================================

    private static final String INDENT = "  ";

    /**
     * Serialize a JqValue to an indented, human-readable JSON string.
     * Uses 2-space indentation. Scalars produce compact output (same as {@link JqValue#toJsonString()}).
     * Objects and arrays are formatted with newlines and indentation.
     *
     * <p>This method is intended for export files, debugging, and CLI output.
     * For performance-sensitive serialization, use {@link JqValue#toJsonString()} instead.</p>
     */
    public static String toPrettyJsonString(JqValue value) {
        return toPrettyJsonString(value, PrettyPrintOptions.DEFAULT);
    }

    /**
     * Serialize a JqValue to an indented, human-readable JSON string with explicit style control.
     *
     * @param value   the JqValue to serialize
     * @param options the output style (separator, array layout, escaping policy)
     * @return the formatted JSON string
     * @see PrettyPrintOptions
     */
    public static String toPrettyJsonString(JqValue value, PrettyPrintOptions options) {
        java.util.Objects.requireNonNull(options, "options");
        if (value.isScalar()) {
            if (value instanceof JqString s
                    && options.escaping() == PrettyPrintOptions.Escaping.STRICT) {
                StringBuilder sb = new StringBuilder(s.stringValue().length() + 16);
                sb.append('"');
                JqString.escapeJson(s.stringValue(), sb, options.escaping());
                sb.append('"');
                return sb.toString();
            }
            return value.toJsonString();
        }
        StringBuilder sb = SERIALIZER_BUFFER.get();
        sb.setLength(0);
        appendPretty(value, sb, 0, options);
        String result = sb.toString();
        if (sb.capacity() > SERIALIZE_BUFFER_MAX_RETAINED) {
            SERIALIZER_BUFFER.set(new StringBuilder(SERIALIZE_BUFFER_INIT));
        }
        return result;
    }

    private static void appendPretty(JqValue value, StringBuilder sb, int depth, PrettyPrintOptions options) {
        switch (value) {
            case JqObject obj -> {
                if (obj.size() == 0) { sb.append(options.emptyObject()); return; }
                sb.append("{\n");
                boolean first = true;
                for (var entry : obj.objectValue().entrySet()) {
                    if (!first) sb.append(",\n");
                    first = false;
                    appendIndent(sb, depth + 1);
                    sb.append('"');
                    JqString.escapeJson(entry.getKey(), sb, options.escaping());
                    sb.append('"');
                    sb.append(options.separator().text());
                    appendPretty(entry.getValue(), sb, depth + 1, options);
                }
                sb.append('\n');
                appendIndent(sb, depth);
                sb.append('}');
            }
            case JqArray arr -> {
                if (arr.size() == 0) { sb.append(options.emptyArray()); return; }
                if (options.arrayStyle() == PrettyPrintOptions.ArrayStyle.FLOW) {
                    sb.append("[ ");
                    for (int i = 0; i < arr.size(); i++) {
                        if (i > 0) sb.append(", ");
                        // FLOW arrays are transparent to nesting (Jackson parity):
                        // elements render at the array's depth, not depth+1.
                        appendPretty(arr.get(i), sb, depth, options);
                    }
                    sb.append(" ]");
                    return;
                }
                sb.append("[\n");
                for (int i = 0; i < arr.size(); i++) {
                    if (i > 0) sb.append(",\n");
                    appendIndent(sb, depth + 1);
                    appendPretty(arr.get(i), sb, depth + 1, options);
                }
                sb.append('\n');
                appendIndent(sb, depth);
                sb.append(']');
            }
            case JqString s -> {
                sb.append('"');
                JqString.escapeJson(s.stringValue(), sb, options.escaping());
                sb.append('"');
            }
            default -> value.appendTo(sb);
        }
    }

    private static void appendIndent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) sb.append(INDENT);
    }

    /** Mutable parser state — avoids int[] indirection on every character access. */
    private static final class JsonReader {
        final String json;
        final int len;
        int pos;
        // Maximum tolerated nesting depth (configurable strict option;
        // defaults to MAX_PARSE_DEPTH so lenient parsing is unchanged).
        int maxDepth = MAX_PARSE_DEPTH;
        // Key sharing: track previous object's keys for schema detection.
        // When consecutive objects have the same interned keys (reference equality),
        // share the keys[] array to reduce heap pressure and improve L1 cache locality.
        String[] previousKeys;
        // Second-level schema cache, checked when previousKeys misses. Objects complete in
        // post-order, so for nested schemas the previous completed object is never the same
        // schema and a one-entry cache never hits. Mirrors JsonByteReader.schemaCache.
        private static final int SCHEMA_CACHE_SIZE = 8;
        // Allocated on first use, not in the constructor: small documents
        // never need either table.
        private String[][] schemaCache;
        private int schemaCacheNext;
        // Per-depth scratch buffers for objects with more than 8 members. The member count
        // is unknown until '}', so the arrays must grow; growing them fresh per object is
        // the doubling garbage this avoids. Keys at 2*depth, values at 2*depth+1.
        // A buffer is dropped once its unused slack exceeds MAX_SCRATCH_SLACK, which bounds
        // retained waste per depth without assuming anything about object sizes.
        private static final int MAX_SCRATCH_SLACK = 1024;
        private Object[][] scratch;

        JsonReader(String json) {
            this.json = json;
            this.len = json.length();
            this.pos = 0;
        }

        char peek() { return json.charAt(pos); }
        char advance() { return json.charAt(pos++); }

        void skipWs() {
            int p = pos;
            final int l = len;
            final String s = json;
            while (p < l) {
                char c = s.charAt(p);
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') break;
                p++;
            }
            pos = p;
        }

        String[] growKeys(int depth, String[] keys, int count) {
            Object[][] table = scratch;
            if (table == null) {
                table = new Object[32][];
                scratch = table;
            }
            if (2 * depth + 1 >= table.length) {
                table = java.util.Arrays.copyOf(table, Math.max(2 * depth + 2, table.length * 2));
                scratch = table;
            }
            String[] k = (String[]) table[2 * depth];
            if (k == null || k.length < count * 2) {
                k = new String[Math.max(count * 2, 32)];
                table[2 * depth] = k;
            }
            System.arraycopy(keys, 0, k, 0, count);
            return k;
        }

        JqValue[] growValues(int depth, JqValue[] values, int count) {
            // growKeys runs first at the same depth, so the table exists
            // and is already sized; only the null case needs handling.
            Object[][] table = scratch;
            if (table == null) {
                table = new Object[32][];
                scratch = table;
            }
            JqValue[] v = (JqValue[]) table[2 * depth + 1];
            if (v == null || v.length < count * 2) {
                v = new JqValue[Math.max(count * 2, 32)];
                table[2 * depth + 1] = v;
            }
            System.arraycopy(values, 0, v, 0, count);
            return v;
        }

        void releaseScratchIfWasteful(int depth, int capacity, int count) {
            Object[][] table = scratch;
            // Only runs for objects past the small inline capacity, which
            // always grew the table first; the guard is purely defensive.
            if (table == null) return;
            if (capacity - count > MAX_SCRATCH_SLACK) {
                table[2 * depth] = null;
                table[2 * depth + 1] = null;
            }
        }

        String[] findSharedKeys(String[] keys, int count) {
            String[] prev = previousKeys;
            if (prev != null && prev.length == count) {
                int i = 0;
                while (i < count && prev[i] == keys[i]) i++;
                if (i == count) return prev;
            }
            String[][] cache = schemaCache;
            if (cache == null) return null;
            for (String[] c : cache) {
                if (c == null || c.length != count) continue;
                int i = 0;
                while (i < count && c[i] == keys[i]) i++;
                if (i == count) { previousKeys = c; return c; }
            }
            return null;
        }

        void rememberKeys(String[] exactKeys) {
            previousKeys = exactKeys;
            String[][] cache = schemaCache;
            if (cache == null) {
                cache = new String[SCHEMA_CACHE_SIZE][];
                schemaCache = cache;
            }
            cache[schemaCacheNext] = exactKeys;
            schemaCacheNext = (schemaCacheNext + 1) & (SCHEMA_CACHE_SIZE - 1);
        }
    }

    // ========================================================================
    //  Recursive Java type bridge
    // ========================================================================

    /**
     * Recursively convert a plain Java object to a JqValue.
     * <ul>
     *   <li>{@code null} → {@link JqNull#NULL}</li>
     *   <li>{@link JqValue} → returned as-is (passthrough, avoids double-wrapping)</li>
     *   <li>{@link Map} → {@link JqObject} (via builder, insertion order preserved for {@link LinkedHashMap})</li>
     *   <li>{@link List} → {@link JqArray} (recursively converted)</li>
     *   <li>{@link String} → {@link JqString}</li>
     *   <li>{@link Number} → {@link JqNumber} (integral values promoted to long-backed)</li>
     *   <li>{@link Boolean} → {@link JqBoolean}</li>
     *   <li>Other types → {@link JqString} via {@code toString()} (fallback)</li>
     * </ul>
     *
     * <p>This is the inverse of {@link JqValue#toJavaObject()}.</p>
     *
     * @param value the Java object to convert
     * @return the corresponding JqValue, never null ({@code null} input returns {@link JqNull#NULL})
     */
    @SuppressWarnings("unchecked")
    public static JqValue fromJavaObject(Object value) {
        if (value == null) return JqNull.NULL;
        if (value instanceof JqValue jv) return jv;
        if (value instanceof String s) return JqString.of(s);
        if (value instanceof Number n) return JqNumber.of(n);
        if (value instanceof Boolean b) return JqBoolean.of(b);
        if (value instanceof Map<?, ?> map) {
            var builder = JqObject.builder(map.size());
            for (var entry : map.entrySet()) {
                builder.put(String.valueOf(entry.getKey()), fromJavaObject(entry.getValue()));
            }
            return builder.build();
        }
        if (value instanceof List<?> list) {
            JqValue[] elements = new JqValue[list.size()];
            for (int i = 0; i < list.size(); i++) {
                elements[i] = fromJavaObject(list.get(i));
            }
            return JqArray.of(elements);
        }
        // Fallback: serialize to string
        return JqString.of(value.toString());
    }

    // ========================================================================
    //  Type structure (schema inference)
    // ========================================================================

    /**
     * Compute a type-structure schema from a JqValue tree.
     * Replaces leaf values with their type names, preserving the object/array structure.
     *
     * <p>Type mapping:</p>
     * <ul>
     *   <li>{@code null} → {@code "null"}</li>
     *   <li>{@code boolean} → {@code "boolean"}</li>
     *   <li>integral number → {@code "integer"}</li>
     *   <li>floating-point number → {@code "number"}</li>
     *   <li>{@code string} → {@code "string"}</li>
     *   <li>object → recurse, preserving keys</li>
     *   <li>array → recurse, merging element schemas into a single representative</li>
     * </ul>
     *
     * <p>Example: {@code {"name":"Alice","age":30,"scores":[95,87]}}
     * → {@code {"name":"string","age":"integer","scores":["integer"]}}</p>
     *
     * @param value the value to compute the type structure for
     * @return a JqValue tree where all leaf values are replaced by type name strings
     */
    public static JqValue typeStructure(JqValue value) {
        return switch (value) {
            case JqNull ignored -> JqString.of("null");
            case JqBoolean ignored -> JqString.of("boolean");
            case JqNumber n -> JqString.of(n.isIntegral() ? "integer" : "number");
            case JqString ignored -> JqString.of("string");
            case JqArray arr -> {
                if (arr.size() == 0) yield JqArray.EMPTY;
                JqValue elementSchema = typeStructure(arr.get(0));
                for (int i = 1; i < arr.size(); i++) {
                    elementSchema = mergeTypeStructures(elementSchema, typeStructure(arr.get(i)));
                }
                yield JqArray.of(elementSchema);
            }
            case JqObject obj -> {
                var builder = JqObject.builder(obj.size());
                obj.forEach((key, val) -> builder.put(key, typeStructure(val)));
                yield builder.build();
            }
        };
    }

    /**
     * Merge two type-structure schemas into a combined schema.
     * Used to build a unified type structure from multiple JSON documents.
     *
     * <p>Merge rules:</p>
     * <ul>
     *   <li>Identical structures → return as-is</li>
     *   <li>Both objects → merge keys recursively (union of keys)</li>
     *   <li>Both arrays → merge representative element schemas</li>
     *   <li>{@code "integer"} + {@code "number"} → {@code "number"} (widening promotion)</li>
     *   <li>Incompatible leaf types → keep first</li>
     * </ul>
     *
     * @param a the first type structure
     * @param b the second type structure
     * @return the merged type structure
     */
    public static JqValue mergeTypeStructures(JqValue a, JqValue b) {
        if (a.equals(b)) return a;
        // Both objects: merge keys recursively
        if (a instanceof JqObject objA && b instanceof JqObject objB) {
            var builder = JqObject.builder(objA.size() + objB.size());
            objA.forEach((key, val) -> {
                if (objB.has(key)) {
                    builder.put(key, mergeTypeStructures(val, objB.get(key)));
                } else {
                    builder.put(key, val);
                }
            });
            objB.forEach((key, val) -> {
                if (!objA.has(key)) {
                    builder.put(key, val);
                }
            });
            return builder.build();
        }
        // Both arrays: merge representative element schemas
        if (a instanceof JqArray arrA && b instanceof JqArray arrB) {
            if (arrA.size() == 0) return b;
            if (arrB.size() == 0) return a;
            return JqArray.of(mergeTypeStructures(arrA.get(0), arrB.get(0)));
        }
        // Type promotion: "integer" + "number" → "number"
        if (a instanceof JqString sa && b instanceof JqString sb) {
            String ta = sa.stringValue();
            String tb = sb.stringValue();
            if (("integer".equals(ta) && "number".equals(tb)) ||
                    ("number".equals(ta) && "integer".equals(tb))) {
                return JqString.of("number");
            }
        }
        // Incompatible types at same path — keep first
        return a;
    }

    // ========================================================================
    //  JSON parsing
    // ========================================================================

    /**
     * Threshold below which {@code parse(String)} delegates to the byte[]-based
     * parser via {@code getBytes(UTF_8)}. Below this size, the copy cost is
     * negligible and the byte parser's SWAR scanning + deferred-bytes strings
     * provide better overall performance. Above this size, the char-based parser
     * avoids the large byte array allocation.
     *
     * <p>The byte delegation also avoids {@code String.charAt()} calls that are
     * vulnerable to C2 profile pollution (see issue #68, #69).</p>
     */
    private static final int BYTE_DELEGATION_THRESHOLD = Integer.MAX_VALUE; // always delegate to byte path

    /**
     * Parse a JSON value from a String.
     *
     * <p>For strings up to 64 KB, delegates to the byte[]-based parser via
     * {@code getBytes(UTF_8)}. The byte parser uses SWAR scanning, byte-based
     * field name interning, and produces deferred-bytes strings with zero-copy
     * serialization support. This also avoids {@code String.charAt()} calls
     * that are vulnerable to C2 profile pollution (see issue #68, #69).</p>
     *
     * <p>For larger strings, uses the char-based parser directly to avoid
     * allocating a large byte array copy.</p>
     *
     * <p>Lenient contract: duplicate object keys are kept as-is.
     * Use {@link #parseStrict} to reject them.</p>
     */
    public static JqValue parse(String json) {
        if (json.length() <= BYTE_DELEGATION_THRESHOLD) {
            return parse(json.getBytes(StandardCharsets.UTF_8));
        }
        // Large string: use char-based parser to avoid large byte[] allocation
        json = json.trim();
        // Strip UTF-8 BOM if present
        if (!json.isEmpty() && json.charAt(0) == '\uFEFF') {
            json = json.substring(1);
        }
        var reader = new JsonReader(json);
        JqValue result = parseValue(reader, 0);
        setSourceLength(result, json.length());
        return result;
    }

    /**
     * Strict parse: requires the entire string to be a single valid JSON value.
     * Unlike {@link #parse(String)}, rejects trailing commas, missing values,
     * truncated input, non-canonical numbers (leading zeros, dangling
     * fraction/exponent parts), unknown literals ({@code NaN},
     * {@code Infinity}), invalid string escapes, and duplicate object keys,
     * with line/column positions. Used by fromjson; suitable for API responses
     * and files that must fail loudly instead of reading as partial values.
     * Empty input still yields null, matching {@code parse}.
     * Every malformed document surfaces exclusively as
     * {@code IllegalArgumentException} (positions included) — adapters map
     * invalid input by catch-umbrella instead of matching messages.
     */
    public static JqValue parseStrict(String json) {
        return parseStrict(json, ParseOptions.defaults());
    }

    /**
     * Strict parse of a JSON string with configurable options: nesting depth
     * limit, byte-order-mark policy, and raw-NUL policy. Defaults match
     * {@link #parseStrict(String)} (10000 levels, BOMs stripped, NULs
     * allowed); narrowing any of them only rejects more input, never more.
     *
     * @param json    the JSON string to parse
     * @param options the strict-parse options (non-null)
     * @return the parsed value ({@code null} for empty input)
     * @throws IllegalArgumentException with line/column position on malformed
     *         input or policy violation
     * Every malformed document surfaces exclusively as
     * {@code IllegalArgumentException} (positions included) — adapters map
     * invalid input by catch-umbrella instead of matching messages.
     */
    public static JqValue parseStrict(String json, ParseOptions options) {
        if (options.nulPolicy() == ParseOptions.NulPolicy.REJECT) {
            int nul = json.indexOf('\0');
            if (nul >= 0) {
                throw new IllegalArgumentException("NUL character rejected" + lineColOf(json, nul));
            }
        }
        if (json.length() <= BYTE_DELEGATION_THRESHOLD) {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            return parseStrictBytes(bytes, 0, bytes.length, json, options);
        }
        // Large string: use char-based parser
        String trimmed = json.trim();
        if (trimmed.isEmpty()) return JqNull.NULL;
        if (trimmed.charAt(0) == '\uFEFF') {
            if (options.bomPolicy() == ParseOptions.BomPolicy.REJECT) {
                throw new IllegalArgumentException("Byte-order mark (BOM) rejected"
                        + lineColOf(json, firstNonWs(json)));
            }
            trimmed = trimmed.substring(1);
        }
        var reader = new JsonReader(trimmed);
        reader.maxDepth = options.maxDepth();
        JqValue result = parseValue(reader, 0);
        reader.skipWs();
        if (reader.pos < reader.len) {
            throw new IllegalArgumentException(
                    "Invalid numeric literal at EOF at line 1, column " + reader.len
                            + " (while parsing '" + trimmed + "')");
        }
        return result;
    }

    /**
     * Strict parse of a UTF-8 JSON document: requires the entire input to be
     * a single valid JSON value, with no leniency for trailing commas,
     * missing values, truncated input, non-canonical numbers, unknown
     * literals, or invalid string escapes. See {@link #parseStrict(String)}.
     * Malformed UTF-8 is rejected (unlike {@link #parse(byte[])}, which
     * replaces).
     *
     * @param bytes the UTF-8 JSON document
     * @return the parsed value ({@code null} for empty input)
     * @throws IllegalArgumentException with line/column position on malformed input
     * Every malformed document surfaces exclusively as
     * {@code IllegalArgumentException} (positions included) — adapters map
     * invalid input by catch-umbrella instead of matching messages.
     */
    public static JqValue parseStrict(byte[] bytes) {
        return parseStrict(bytes, ParseOptions.defaults());
    }

    /**
     * Strict parse of a UTF-8 JSON byte array with configurable options:
     * nesting depth limit, byte-order-mark policy, and raw-NUL policy.
     * Defaults match {@link #parseStrict(byte[])} (10000 levels, BOMs
     * stripped, NULs allowed); narrowing any of them only rejects more
     * input, never more.
     *
     * @param bytes   the UTF-8 JSON document
     * @param options the strict-parse options (non-null)
     * @return the parsed value ({@code null} for empty input)
     * @throws IllegalArgumentException with line/column position on malformed
     *         input or policy violation
     * Every malformed document surfaces exclusively as
     * {@code IllegalArgumentException} (positions included) — adapters map
     * invalid input by catch-umbrella instead of matching messages.
     */
    public static JqValue parseStrict(byte[] bytes, ParseOptions options) {
        int bad = Utf8.validate(bytes, 0, bytes.length);
        if (bad >= 0) throw utf8Error(bytes, 0, bad);
        if (options.nulPolicy() == ParseOptions.NulPolicy.REJECT) {
            for (int i = 0; i < bytes.length; i++) {
                if (bytes[i] == 0) {
                    StringBuilder sb = new StringBuilder("NUL byte rejected");
                    appendPosition(sb, bytes, 0, i);
                    throw new IllegalArgumentException(sb.toString());
                }
            }
        }
        return parseStrictBytes(bytes, 0, bytes.length, null, options);
    }

    /** Malformed-UTF-8 error with line/column for a byte offset (cold path). */
    private static IllegalArgumentException utf8Error(byte[] bytes, int base, int badOffset) {
        StringBuilder sb = new StringBuilder("Invalid UTF-8");
        appendPosition(sb, bytes, base, badOffset);
        return new IllegalArgumentException(sb.toString());
    }

    /** Append ` at line L, column C` for a byte offset into a helper buffer. */
    private static void appendPosition(StringBuilder sb, byte[] data, int base, int pos) {
        int line = 1;
        int lineStart = Math.max(base, 0);
        int stop = Math.min(pos, data.length);
        for (int i = lineStart; i < stop; i++) {
            if (data[i] == '\n') { line++; lineStart = i + 1; }
        }
        sb.append(" at line ").append(line).append(", column ").append(stop - lineStart + 1);
    }

    /** Byte-based strict parse with trailing content validation. */
    private static JqValue parseStrictBytes(byte[] bytes, int offset, int length,
                                            String originalForError, ParseOptions options) {
        int end = offset + length;
        int docStart = offset;
        while (offset < end && isWsByte(bytes[offset])) offset++;
        if (offset >= end) return JqNull.NULL;
        if (end - offset >= 3 && (bytes[offset] & 0xFF) == 0xEF
                && (bytes[offset + 1] & 0xFF) == 0xBB && (bytes[offset + 2] & 0xFF) == 0xBF) {
            if (options.bomPolicy() == ParseOptions.BomPolicy.REJECT) {
                StringBuilder sb = new StringBuilder("Byte-order mark (BOM) rejected");
                appendPosition(sb, bytes, docStart, offset);
                throw new IllegalArgumentException(sb.toString());
            }
            offset += 3;
            while (offset < end && isWsByte(bytes[offset])) offset++;
        }
        if (offset >= end) return JqNull.NULL;
        var reader = new JsonByteReader(bytes, offset, end);
        reader.strict = true;
        reader.base = offset;
        reader.maxDepth = options.maxDepth();
        reader.duplicatePolicy = options.duplicatePolicy();
        JqValue result = parseValueBytes(reader, 0);
        reader.skipWs();
        if (reader.pos < reader.end) {
            String input = originalForError != null ? originalForError.trim() : new String(bytes, StandardCharsets.UTF_8).trim();
            throw new IllegalArgumentException(
                    "Invalid numeric literal at EOF at line 1, column " + input.length()
                            + " (while parsing '" + input + "')");
        }
        return result;
    }

    /**
     * Build a strict-mode parse error with line/column position.
     * Cold path only (throwing already allocates); the backward newline
     * scan costs nothing measurable next to exception construction.
     */
    private static IllegalArgumentException strictError(JsonByteReader r, String msg) {
        StringBuilder sb = new StringBuilder(msg);
        appendPosition(sb, r, r.pos);
        return new IllegalArgumentException(sb.toString());
    }

    /** Append ` at line L, column C` for a byte offset (cold error paths only). */
    private static void appendPosition(StringBuilder sb, JsonByteReader r, int pos) {
        appendPosition(sb, r.data, r.base, Math.min(pos, r.end));
    }

    /**
     * Cold constructor for the single-quote rejection (issue #166): kept
     * out of line so both value dispatches pay only a call-sized arm
     * against the hot-inline budget.
     */
    private static IllegalArgumentException singleQuoteError() {
        return new IllegalArgumentException(
                "Single-quoted strings are not valid JSON; use double quotes");
    }

    /** Index of the first non-whitespace char (cold paths only). */
    private static int firstNonWs(String text) {
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return i;
            i++;
        }
        return i;
    }

    /** ` at line L, column C` for a char index (cold error paths only). */
    private static String lineColOf(String text, int index) {
        int line = 1;
        int lineStart = 0;
        int stop = Math.min(index, text.length());
        for (int i = 0; i < stop; i++) {
            if (text.charAt(i) == '\n') { line++; lineStart = i + 1; }
        }
        return " at line " + line + ", column " + (stop - lineStart + 1);
    }

    /** Duplicate-key error naming the key and its position (strict mode). */
    private static IllegalArgumentException duplicateKeyError(JsonByteReader r, String key, int keyStart) {
        StringBuilder sb = new StringBuilder("Duplicate key \"");
        sb.append(key.replace("\\", "\\\\").replace("\"", "\\\""));
        sb.append('"');
        appendPosition(sb, r, keyStart);
        return new IllegalArgumentException(sb.toString());
    }

    /** Require an exact literal at the reader position (strict mode). */
    private static void expectLiteral(JsonByteReader r, String literal) {
        for (int i = 0; i < literal.length(); i++) {
            if (r.pos + i >= r.end || (r.data[r.pos + i] & 0xFF) != literal.charAt(i)) {
                throw strictError(r, "Invalid literal, expected '" + literal + "'");
            }
        }
        r.pos += literal.length();
    }

    private static boolean isHexByte(int b) {
        return (b >= '0' && b <= '9') || (b >= 'a' && b <= 'f') || (b >= 'A' && b <= 'F');
    }

    /** Numeric value of an ASCII hex digit (call only after {@link #isHexByte}). */
    private static int hexVal(int b) {
        if (b <= '9') return b - '0';
        if (b <= 'F') return b - 'A' + 10;
        return b - 'a' + 10;
    }

    /**
     * Validate one escape sequence with the reader positioned at the escape
     * character (backslash already consumed). Advances past the sequence,
     * mirroring the lenient path's landing positions on valid input.
     */
    private static void validateEscapeBytes(JsonByteReader r) {
        if (r.pos >= r.end) throw strictError(r, "Unterminated escape sequence");
        int e = r.data[r.pos] & 0xFF;
        // Simple escapes: validate only. The caller advances past the escape
        // character with its shared increment (mirrors the lenient landing).
        switch (e) {
            case '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> { }
            case 'u' -> {
                for (int i = 1; i <= 4; i++) {
                    if (r.pos + i >= r.end || !isHexByte(r.data[r.pos + i] & 0xFF)) {
                        throw strictError(r, "Invalid \\u escape sequence");
                    }
                }
                int unit = (hexVal(r.data[r.pos + 1] & 0xFF) << 12)
                        | (hexVal(r.data[r.pos + 2] & 0xFF) << 8)
                        | (hexVal(r.data[r.pos + 3] & 0xFF) << 4)
                        | hexVal(r.data[r.pos + 4] & 0xFF);
                r.pos += 4;
                if (unit >= 0xD800 && unit <= 0xDBFF) {
                    // High surrogate: the low half must follow immediately
                    // (a missing follower is unpaired just as surely as a
                    // truncated document is). It is consumed inline, so a
                    // low escape standing alone below is by definition
                    // unpaired — no cross-call state.
                    if (r.pos + 2 >= r.end
                            || (r.data[r.pos + 1] & 0xFF) != '\\'
                            || (r.data[r.pos + 2] & 0xFF) != 'u') {
                        throw strictError(r, "Unpaired surrogate escape");
                    }
                    boolean pair = r.pos + 6 < r.end;
                    int low = 0;
                    for (int i = 3; pair && i <= 6; i++) {
                        int h = r.data[r.pos + i] & 0xFF;
                        if (!isHexByte(h)) pair = false;
                        else low = (low << 4) | hexVal(h);
                    }
                    if (!pair) throw strictError(r, "Invalid \\u escape sequence");
                    if (low < 0xDC00 || low > 0xDFFF) throw strictError(r, "Unpaired surrogate escape");
                    r.pos += 6;
                } else if (unit >= 0xDC00 && unit <= 0xDFFF) {
                    throw strictError(r, "Unpaired surrogate escape");
                }
            }
            default -> throw strictError(r, "Invalid escape '\\" + (char) e + "'");
        }
    }

    /**
     * Parse a stream of whitespace-separated JSON values (JSONL / NDJSON / JSON stream).
     * Behaves like jq: each top-level JSON value is parsed independently.
     */
    public static List<JqValue> parseAll(String json) {
        if (json.length() <= BYTE_DELEGATION_THRESHOLD) {
            return parseAll(json.getBytes(StandardCharsets.UTF_8));
        }
        // Large string: use char-based parser
        var results = new ArrayList<JqValue>();
        var reader = new JsonReader(json);
        while (true) {
            reader.skipWs();
            if (reader.pos >= reader.len) break;
            int startPos = reader.pos;
            JqValue value = parseValue(reader, 0);
            setSourceLength(value, reader.pos - startPos);
            results.add(value);
        }
        return results;
    }

    private static JqValue parseValue(JsonReader r, int depth) {
        r.skipWs();
        if (r.pos >= r.len) return JqNull.NULL;
        char c = r.json.charAt(r.pos);
        return switch (c) {
            case '{' -> parseObject(r, depth + 1);
            case '[' -> parseArrayIterative(r, depth);
            case '"' -> parseString(r);
            case 't' -> { r.pos += 4; yield JqBoolean.TRUE; }
            case 'f' -> { r.pos += 5; yield JqBoolean.FALSE; }
            case 'n' -> {
                // Distinguish between "null" and "nan"
                if (r.pos + 2 < r.len && r.json.charAt(r.pos + 1) == 'a' && r.json.charAt(r.pos + 2) == 'n') {
                    r.pos += 3;
                    yield JqNumber.of(Double.NaN);
                }
                r.pos += 4;
                yield JqNull.NULL;
            }
            case 'I' -> {
                // Infinity
                r.pos += 8; // "Infinity"
                yield JqNumber.of(Double.POSITIVE_INFINITY);
            }
            case 'N' -> {
                // NaN
                r.pos += 3;
                yield JqNumber.of(Double.NaN);
            }
            case '\'' -> throw singleQuoteError();
            default -> parseNumber(r);
        };
    }

    /**
     * Parse arrays iteratively to avoid stack overflow on deeply nested structures.
     * Uses an explicit stack instead of recursion for nested arrays.
     */
    private static JqValue parseArrayIterative(JsonReader r, int depth) {
        ArrayDeque<JqValue[]> stack = null; // deferred -- only allocated for nested [[...]]
        int[] stackCounts = null;
        int stackDepth = 0;

        // Iterate through consecutive opening brackets
        while (r.pos < r.len && r.json.charAt(r.pos) == '[') {
            depth++;
            if (depth > r.maxDepth) {
                throw new IllegalArgumentException("Exceeds depth limit for parsing");
            }
            r.pos++; // skip [
            r.skipWs();

            // Empty array
            if (r.pos < r.len && r.json.charAt(r.pos) == ']') {
                r.pos++;
                return unwindArrayStack(stack, stackCounts, stackDepth, JqArray.EMPTY, r, depth);
            }

            // If first element is another array, push context and continue iterating
            if (r.json.charAt(r.pos) == '[') {
                if (stack == null) {
                    stack = new ArrayDeque<>();
                    stackCounts = new int[16];
                }
                if (stackDepth >= stackCounts.length) {
                    stackCounts = java.util.Arrays.copyOf(stackCounts, stackCounts.length * 2);
                }
                stack.push(new JqValue[8]);
                stackCounts[stackDepth++] = 0;
                continue;
            }

            // First element is not an array — parse this array normally
            break;
        }

        // Parse the current array elements using raw array
        JqValue[] elems = new JqValue[8];
        int count = 0;
        elems[count++] = parseValue(r, depth);
        r.skipWs();
        while (r.pos < r.len && r.json.charAt(r.pos) != ']') {
            r.pos++; // skip ,
            if (count >= elems.length) elems = java.util.Arrays.copyOf(elems, elems.length * 2);
            elems[count++] = parseValue(r, depth);
            r.skipWs();
        }
        if (r.pos < r.len) r.pos++; // skip ]
        JqValue result = JqArray.ofTrusted(elems, count);
        return unwindArrayStack(stack, stackCounts, stackDepth, result, r, depth);
    }

    /**
     * Unwind the explicit stack of partially-built arrays, completing each level.
     */
    private static JqValue unwindArrayStack(ArrayDeque<JqValue[]> stack, int[] stackCounts,
                                             int stackDepth, JqValue result,
                                             JsonReader r, int depth) {
        if (stack == null) return result;
        while (stackDepth > 0) {
            depth--;
            stackDepth--;
            JqValue[] elems = stack.pop();
            int count = stackCounts[stackDepth];
            if (count >= elems.length) elems = java.util.Arrays.copyOf(elems, elems.length * 2);
            elems[count++] = result;
            r.skipWs();
            while (r.pos < r.len && r.json.charAt(r.pos) != ']') {
                r.pos++; // skip ,
                if (count >= elems.length) elems = java.util.Arrays.copyOf(elems, elems.length * 2);
                elems[count++] = parseValue(r, depth);
                r.skipWs();
            }
            if (r.pos < r.len) r.pos++; // skip ]
            result = JqArray.ofTrusted(elems, count);
        }
        return result;
    }

    private static JqObject parseObject(JsonReader r, int depth) {
        if (depth > r.maxDepth) {
            throw new IllegalArgumentException("Exceeds depth limit for parsing");
        }
        r.pos++; // skip {
        r.skipWs();
        if (r.pos < r.len && r.json.charAt(r.pos) == '}') { r.pos++; return JqObject.EMPTY; }

        String[] keys = new String[8];
        JqValue[] values = new JqValue[8];
        int count = 0;
        while (true) {
            r.skipWs();
            if (r.pos >= r.len || r.json.charAt(r.pos) != '"') {
                char got = r.pos < r.len ? r.json.charAt(r.pos) : '?';
                throw new IllegalArgumentException(
                        "Invalid string literal; expected \", but got " + got
                                + " at line 1, column " + (r.pos + 1)
                                + " (while parsing '" + r.json + "')");
            }
            if (count >= keys.length) {
                // Large object: continue in per-depth scratch buffers, no doubling garbage
                keys = r.growKeys(depth, keys, count);
                values = r.growValues(depth, values, count);
            }
            keys[count] = parseAndInternKey(r);
            r.skipWs();
            r.pos++; // skip :
            values[count] = parseValue(r, depth);
            count++;
            r.skipWs();
            if (r.pos >= r.len || r.json.charAt(r.pos) == '}') { r.pos++; break; }
            r.pos++; // skip ,
        }
        // Objects that overflowed into scratch buffers get exact-size arrays;
        // small objects keep the arrays they were built in.
        boolean scratch = count > 8;
        if (scratch) values = java.util.Arrays.copyOf(values, count);
        if (scratch) r.releaseScratchIfWasteful(depth, keys.length, count);
        // Key sharing: 8-entry schema cache with reference equality on interned keys
        String[] sharedKeys = r.findSharedKeys(keys, count);
        if (sharedKeys != null) {
            return JqObject.ofArrays(sharedKeys, values, count);
        }
        String[] exactKeys = (scratch || count != keys.length) ? java.util.Arrays.copyOf(keys, count) : keys;
        r.rememberKeys(exactKeys);
        return JqObject.ofArrays(exactKeys, values, count);
    }

    /**
     * Fast-path string parser: scans for the closing quote without escape characters.
     * If the string contains no backslash, returns a substring directly (no StringBuilder,
     * no char-by-char copy). Falls back to escape-handling path only when needed.
     */
    /**
     * Parse a JSON string value and return a deferred JqString.
     * Scans for the closing quote without materializing the Java String.
     * Field names (object keys) use {@link #parseStringRaw} instead.
     */
    private static JqString parseString(JsonReader r) {
        r.pos++; // skip opening "
        int contentStart = r.pos;
        final String s = r.json;
        final int len = r.len;

        // Fast path: scan for closing quote, check for backslash
        while (r.pos < len) {
            char c = s.charAt(r.pos);
            if (c == '"') {
                // No escapes -- deferred, zero-copy on serialization
                int contentEnd = r.pos;
                r.pos++; // skip closing "
                return JqString.deferred(s, contentStart, contentEnd, false);
            }
            if (c == '\\') {
                // Has escapes -- scan to end, mark as has-escapes
                return parseDeferredWithEscapes(r, s, contentStart);
            }
            r.pos++;
        }
        throw new IllegalArgumentException("Unterminated string");
    }

    /** Scan to closing quote for a string that contains escape sequences. */
    private static JqString parseDeferredWithEscapes(JsonReader r, String s, int contentStart) {
        final int len = r.len;
        while (r.pos < len) {
            char c = s.charAt(r.pos);
            if (c == '"') {
                int contentEnd = r.pos;
                r.pos++; // skip closing "
                return JqString.deferred(s, contentStart, contentEnd, true);
            }
            if (c == '\\') {
                r.pos++; // skip backslash
                // Skip the escaped character (including unicode escapes)
                if (r.pos < len && s.charAt(r.pos) == 'u') {
                    r.pos += 4; // skip 4 hex digits
                }
            }
            r.pos++;
        }
        throw new IllegalArgumentException("Unterminated string");
    }

    /**
     * Parse a JSON object key with intern cache lookup.
     * Two-pass approach: (1) scan to find closing quote, (2) compute hash
     * and do intern lookup over the known range (data in L1 cache from scan).
     * On cache hit, returns cached String instance without substring().
     */
    private static String parseAndInternKey(JsonReader r) {
        r.pos++; // skip opening "
        int start = r.pos;
        final String s = r.json;
        final int len = r.len;

        // Pass 1: scan for closing quote (no hash computation — keep loop tight)
        while (r.pos < len) {
            char c = s.charAt(r.pos);
            if (c == '"') {
                // Found closing quote — pass 2: hash + intern
                return internKeyFromString(s, start, r.pos++);
            }
            if (c == '\\') break; // fall through to slow path
            r.pos++;
        }

        // Slow path: escaped key — parse normally and intern the result
        // Reset pos to start for parseStringRaw to re-parse
        r.pos = start - 1; // back to opening "
        String result = parseStringRaw(r);
        return internFieldName(result);
    }

    /**
     * Compute hash over a known char range and do intern cache lookup.
     * Called after scanning has found the closing quote.
     */
    private static String internKeyFromString(String s, int start, int end) {
        int keyLen = end - start;
        int hash = 0;
        for (int i = start; i < end; i++) {
            hash = hash * 31 + s.charAt(i);
        }
        // L1 lookup (thread-local)
        InternSlot[] l1 = INTERN_L1.get();
        for (int p = 0; p < L1_MAX_PROBES; p++) {
            InternSlot cached = l1[(mix(hash) + p) & L1_MASK];
            if (cached != null && cached.key.length() == keyLen) {
                boolean match = true;
                for (int i = 0; i < keyLen; i++) {
                    if (cached.key.charAt(i) != s.charAt(start + i)) { match = false; break; }
                }
                if (match) return cached.key;
            }
        }
        // L2 lookup (global)
        for (int probe = 0; probe < INTERN_MAX_PROBES; probe++) {
            int idx = (mix(hash) + probe) & INTERN_MASK;
            InternSlot slot = INTERN_SLOTS[idx];
            if (slot == null) break;
            if (slot.key.length() == keyLen) {
                boolean match = true;
                for (int i = 0; i < keyLen; i++) {
                    if (slot.key.charAt(i) != s.charAt(start + i)) { match = false; break; }
                }
                if (match) {
                    l1[mix(hash) & L1_MASK] = slot; // promote to L1
                    return slot.key;
                }
            }
        }
        // Cache miss — internFieldName handles L1+L2 storage
        String result = s.substring(start, end);
        return internFieldName(result);
    }

    /** Parse a JSON string and return the raw Java String value (without wrapping in JqString). */
    private static String parseStringRaw(JsonReader r) {
        r.pos++; // skip opening "
        int start = r.pos;
        final String s = r.json;
        final int len = r.len;

        // Fast path: scan for closing quote, bail on backslash.
        while (r.pos < len) {
            char c = s.charAt(r.pos);
            if (c == '"') {
                // No escapes — direct substring
                String result = s.substring(start, r.pos);
                r.pos++; // skip closing "
                return result;
            }
            if (c == '\\') break; // fall through to slow path
            r.pos++;
        }

        // Slow path: string contains escape sequences
        var sb = new StringBuilder(r.pos - start + 16);
        // Copy the portion already scanned
        sb.append(s, start, r.pos);
        while (r.pos < len && s.charAt(r.pos) != '"') {
            if (s.charAt(r.pos) == '\\') {
                r.pos++;
                char esc = s.charAt(r.pos);
                switch (esc) {
                    case '"', '\\', '/' -> sb.append(esc);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        int cp = Integer.parseInt(s, r.pos + 1, r.pos + 5, 16);
                        sb.append((char) cp);
                        r.pos += 4;
                    }
                    default -> sb.append(esc);
                }
            } else {
                sb.append(s.charAt(r.pos));
            }
            r.pos++;
        }
        r.pos++; // skip closing "
        return sb.toString();
    }

    /** Precomputed powers of 10 for direct decimal accumulation. */
    private static final double[] POW10 = {
        1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9,
        1e10, 1e11, 1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18
    };

    private static JqNumber parseNumber(JsonReader r) {
        final String s = r.json;
        final int len = r.len;
        int start = r.pos;
        boolean negative = false;

        if (r.pos < len && s.charAt(r.pos) == '-') {
            negative = true;
            r.pos++;
            // Handle -Infinity and -NaN
            if (r.pos < len) {
                if (s.charAt(r.pos) == 'I') {
                    r.pos += 8; // "Infinity"
                    return JqNumber.of(Double.NEGATIVE_INFINITY);
                }
                if (s.charAt(r.pos) == 'N') {
                    r.pos += 3; // "NaN"
                    return JqNumber.of(Double.NaN); // -NaN is same as NaN
                }
            }
        }

        // Fast path: parse integer digits directly into a long accumulator
        long acc = 0;
        boolean overflow = false;
        int digitStart = r.pos;
        int intDigitCount = 0;
        while (r.pos < len) {
            int d = s.charAt(r.pos) - '0';
            if (d < 0 || d > 9) break;
            if (!overflow) {
                long next = acc * 10 + d;
                if (next < acc) overflow = true;
                else acc = next;
            }
            intDigitCount++;
            r.pos++;
        }

        // Parse fractional part with digit accumulation
        boolean isDecimal = false;
        long fracPart = 0;
        int fracCount = 0;
        if (r.pos < len && s.charAt(r.pos) == '.') {
            isDecimal = true;
            r.pos++;
            while (r.pos < len) {
                int d = s.charAt(r.pos) - '0';
                if (d < 0 || d > 9) break;
                if (fracCount < 18) { // avoid long overflow
                    fracPart = fracPart * 10 + d;
                    fracCount++;
                }
                r.pos++;
            }
        }

        // Parse exponent with value accumulation
        int expValue = 0;
        int expCount = 0;
        boolean hasExp = false;
        if (r.pos < len && (s.charAt(r.pos) == 'e' || s.charAt(r.pos) == 'E')) {
            isDecimal = true;
            hasExp = true;
            r.pos++;
            boolean expNegative = false;
            if (r.pos < len && s.charAt(r.pos) == '-') { expNegative = true; r.pos++; }
            else if (r.pos < len && s.charAt(r.pos) == '+') { r.pos++; }
            while (r.pos < len) {
                int d = s.charAt(r.pos) - '0';
                if (d < 0 || d > 9) break;
                // Past 9 digits the magnitude is decisive; keep counting for
                // validation but stop accumulating so the int cannot wrap.
                if (expCount < 9) expValue = expValue * 10 + d;
                expCount++;
                r.pos++;
            }
            if (expNegative) expValue = -expValue;
        }

        if (!isDecimal && !overflow && intDigitCount <= 18) {
            // Integer that fits in a long — no string allocation needed
            // Integer-lexed negative zero is a float (serde/jq distinguish -0 from 0).
            if (negative && acc == 0) return JqNumber.of(-0.0, true);
            return JqNumber.of(negative ? -acc : acc);
        }

        // Direct digit accumulation for decimals with <= 15 significant digits
        // and manageable exponents — zero string allocation, zero BigDecimal
        if (isDecimal && !overflow && intDigitCount <= 15 && fracCount <= 15) {
            int totalDigits = intDigitCount + fracCount;
            if (totalDigits <= 15 && expValue >= -18 && expValue <= 18) {
                double value = (double) acc;
                if (fracCount > 0) {
                    value += (double) fracPart / POW10[fracCount];
                }
                if (expValue > 0 && expValue < POW10.length) {
                    value *= POW10[expValue];
                } else if (expValue < 0 && -expValue < POW10.length) {
                    value /= POW10[-expValue];
                } else if (expValue != 0) {
                    value *= Math.pow(10, expValue);
                }
                return JqNumber.of(negative ? -value : value, isDecimal);
            }
        }

        return parseNumberFallback(s, start, r.pos, isDecimal, negative);
    }

    // ========================================================================
    //  byte[]-based parser (mirrors the String-based parser above)
    //  In valid UTF-8, bytes 0x22 (") and 0x5C (\) only appear as single-byte
    //  characters -- continuation bytes are 0x80-0xBF. So scanning raw bytes
    //  for quote and backslash is safe.
    // ========================================================================

    /** Mutable parser state for byte[]-based parsing. */
    private static final class JsonByteReader {
        final byte[] data;
        final int end;
        int pos;
        // Document start for error positions (strict mode); reader pos may
        // begin past leading whitespace/BOM skipping.
        int base;
        // Strict mode (parseStrict): reject malformed input instead of
        // tolerating it. Off for parse/parseAll: zero behavior change there
        // beyond one predictable branch per check site.
        boolean strict;
        // Maximum tolerated nesting depth (configurable strict option;
        // defaults to MAX_PARSE_DEPTH so lenient parsing is unchanged).
        int maxDepth = MAX_PARSE_DEPTH;
        // Duplicate-key policy for strict objects (configurable strict
        // option; defaults to REJECT so lenient parsing is unchanged).
        // Only consulted when a repeated key is actually found.
        ParseOptions.DuplicatePolicy duplicatePolicy = ParseOptions.DuplicatePolicy.REJECT;
        // Key sharing: track previous object's keys for schema detection.
        // When consecutive objects have the same interned keys (reference equality),
        // share the keys[] array to reduce heap pressure and improve L1 cache locality.
        String[] previousKeys;
        // Second-level schema cache, checked when previousKeys misses. Objects complete in
        // post-order, so for nested schemas the previous completed object is never the same
        // schema and a one-entry cache never hits.
        private static final int SCHEMA_CACHE_SIZE = 8;
        // Allocated on first use, not in the constructor: small documents
        // never need either table.
        private String[][] schemaCache;
        private int schemaCacheNext;
        // Per-depth scratch buffers for objects with more than 8 members. The member count
        // is unknown until '}', so the arrays must grow; growing them fresh per object is
        // the doubling garbage this avoids. Keys at 2*depth, values at 2*depth+1.
        // A buffer is dropped once its unused slack exceeds MAX_SCRATCH_SLACK, which bounds
        // retained waste per depth without assuming anything about object sizes.
        private static final int MAX_SCRATCH_SLACK = 1024;
        private Object[][] scratch;
        // Per-depth duplicate-tracking tables, retained across sequential
        // objects at the same depth (nested objects use deeper slots, so a
        // table is never live for two objects at once). Filled on checkout,
        // so an exception mid-object cannot poison the next user; dropped
        // when the finished object leaves more slack than MAX_SCRATCH_SLACK.
        private long[][] dupTables;

        JsonByteReader(byte[] data, int offset, int end) {
            this.data = data;
            this.pos = offset;
            this.end = end;
        }

        String[] growKeys(int depth, String[] keys, int count) {
            Object[][] table = scratch;
            if (table == null) {
                table = new Object[32][];
                scratch = table;
            }
            if (2 * depth + 1 >= table.length) {
                table = java.util.Arrays.copyOf(table, Math.max(2 * depth + 2, table.length * 2));
                scratch = table;
            }
            String[] k = (String[]) table[2 * depth];
            if (k == null || k.length < count * 2) {
                k = new String[Math.max(count * 2, 32)];
                table[2 * depth] = k;
            }
            System.arraycopy(keys, 0, k, 0, count);
            return k;
        }

        JqValue[] growValues(int depth, JqValue[] values, int count) {
            // growKeys runs first at the same depth, so the table exists
            // and is already sized; only the null case needs handling.
            Object[][] table = scratch;
            if (table == null) {
                table = new Object[32][];
                scratch = table;
            }
            JqValue[] v = (JqValue[]) table[2 * depth + 1];
            if (v == null || v.length < count * 2) {
                v = new JqValue[Math.max(count * 2, 32)];
                table[2 * depth + 1] = v;
            }
            System.arraycopy(values, 0, v, 0, count);
            return v;
        }

        /**
         * Drop both buffers at this depth once their unused slack exceeds
         * {@value #MAX_SCRATCH_SLACK} slots, so retained waste is bounded per depth however
         * large an object happened to be.
         * <p>
         * Only the buffers are dropped, never their contents. Clearing the cells would free
         * nothing: every reference in them is also stored in the {@link JqObject} that was just
         * built and handed to the caller. Lifetime is bounded by the reader, which is created
         * inside {@code parse()} and unreachable when it returns; {@link #parseAll} reuses one
         * reader for a whole stream, so there the bound is per stream rather than per document.
         */
        void releaseScratchIfWasteful(int depth, int capacity, int count) {
            Object[][] table = scratch;
            // Only runs for objects past the small inline capacity, which
            // always grew the table first; the guard is purely defensive.
            if (table == null) return;
            if (capacity - count > MAX_SCRATCH_SLACK) {
                table[2 * depth] = null;
                table[2 * depth + 1] = null;
            }
        }

        /**
         * Take the retained duplicate table for this depth, cleared and
         * ready, or null when none was kept. Single-threaded parsing
         * reaches one object per depth at a time, so taking it out cannot
         * alias a live user; filling it here (not on release) keeps
         * exception paths safe.
         */
        long[] checkoutDupTable(int depth) {
            long[][] tables = dupTables;
            if (tables == null || depth >= tables.length) return null;
            long[] table = tables[depth];
            tables[depth] = null;
            if (table != null) java.util.Arrays.fill(table, 0);
            return table;
        }

        /** Keep a finished table for the next object at this depth. */
        void retainDupTable(int depth, long[] table, int count) {
            if (table.length - count > MAX_SCRATCH_SLACK) return;
            long[][] tables = dupTables;
            if (tables == null) {
                tables = new long[32][];
                dupTables = tables;
            }
            if (depth >= tables.length) {
                tables = java.util.Arrays.copyOf(tables, Math.max(2 * depth + 2, tables.length * 2));
                dupTables = tables;
            }
            tables[depth] = table;
        }

        String[] findSharedKeys(String[] keys, int count) {
            String[] prev = previousKeys;
            if (prev != null && prev.length == count) {
                int i = 0;
                while (i < count && prev[i] == keys[i]) i++;
                if (i == count) return prev;
            }
            String[][] cache = schemaCache;
            if (cache == null) return null;
            for (String[] c : cache) {
                if (c == null || c.length != count) continue;
                int i = 0;
                while (i < count && c[i] == keys[i]) i++;
                if (i == count) { previousKeys = c; return c; }
            }
            return null;
        }

        void rememberKeys(String[] exactKeys) {
            previousKeys = exactKeys;
            String[][] cache = schemaCache;
            if (cache == null) {
                cache = new String[SCHEMA_CACHE_SIZE][];
                schemaCache = cache;
            }
            cache[schemaCacheNext] = exactKeys;
            schemaCacheNext = (schemaCacheNext + 1) & (SCHEMA_CACHE_SIZE - 1);
        }

        int peek() { return data[pos] & 0xFF; }

        void skipWs() {
            int p = pos;
            final byte[] d = data;
            final int e = end;
            if (p < e && (d[p] & 0xFF) > ' ') return;   // compact JSON: no whitespace at all
            while (p < e) {
                int b = d[p] & 0xFF;
                if (b != ' ' && b != '\n' && b != '\r' && b != '\t') break;
                p++;
            }
            pos = p;
        }
    }

    /**
     * Parse a JSON value from an InputStream. Reads all bytes and delegates
     * to the byte[]-based parser.
     *
     * @throws IOException if reading from the stream fails
     */
    public static JqValue parse(InputStream in) throws IOException {
        return parse(in.readAllBytes());
    }

    /**
     * Parse a JSON value from a UTF-8 byte array.
     * Avoids constructing an intermediate String from the entire input.
     */
    public static JqValue parse(byte[] bytes) {
        return parse(bytes, 0, bytes.length);
    }

    /**
     * Parse a JSON value from a region of a UTF-8 byte array.
     */
    public static JqValue parse(byte[] bytes, int offset, int length) {
        int end = offset + length;
        // Skip leading whitespace
        while (offset < end && isWsByte(bytes[offset])) offset++;
        // Skip UTF-8 BOM (EF BB BF) if present
        if (end - offset >= 3 && (bytes[offset] & 0xFF) == 0xEF
                && (bytes[offset + 1] & 0xFF) == 0xBB && (bytes[offset + 2] & 0xFF) == 0xBF) {
            offset += 3;
        }
        // Skip whitespace after BOM
        while (offset < end && isWsByte(bytes[offset])) offset++;
        var reader = new JsonByteReader(bytes, offset, end);
        JqValue result = parseValueBytes(reader, 0);
        setSourceLength(result, length);
        return result;
    }

    /**
     * Parse a stream of whitespace-separated JSON values from a UTF-8 byte array.
     */
    public static List<JqValue> parseAll(byte[] bytes) {
        return parseAll(bytes, 0, bytes.length);
    }

    /**
     * Parse a stream of whitespace-separated JSON values from a region of a UTF-8 byte array.
     */
    public static List<JqValue> parseAll(byte[] bytes, int offset, int length) {
        var results = new ArrayList<JqValue>();
        int end = offset + length;
        var reader = new JsonByteReader(bytes, offset, end);
        while (true) {
            reader.skipWs();
            if (reader.pos >= reader.end) break;
            int startPos = reader.pos;
            JqValue value = parseValueBytes(reader, 0);
            setSourceLength(value, reader.pos - startPos);
            results.add(value);
        }
        return results;
    }

    private static boolean isWsByte(byte b) {
        return b == ' ' || b == '\n' || b == '\r' || b == '\t';
    }

    // tryShareKeys removed — replaced by JsonReader.findSharedKeys() with 8-entry schema cache

    /** Set source byte length on root values for cache weighing. No-op for scalars and singletons. */
    private static void setSourceLength(JqValue value, int length) {
        if (value instanceof JqObject obj && obj != JqObject.EMPTY) obj.setSourceLengthBytes(length);
        else if (value instanceof JqArray arr && arr != JqArray.EMPTY) arr.setSourceLengthBytes(length);
    }

    private static JqValue parseValueBytes(JsonByteReader r, int depth) {
        r.skipWs();
        // Single diversion keeps this hot dispatch at baseline size: the JIT
        // inlines it at element sites (hot budget 325 bytes), which the
        // inline strict arms broke (+99 bytes). Strict bodies live in the
        // cold parseValueStrict below, never on the lenient path.
        if (r.strict) return parseValueStrict(r, depth);
        if (r.pos >= r.end) return JqNull.NULL;
        int b = r.data[r.pos] & 0xFF;
        return switch (b) {
            case '{' -> parseObjectBytes(r, depth + 1);
            case '[' -> parseArrayBytes(r, depth);
            case '"' -> parseStringBytes(r);
            case 't' -> { r.pos += 4; yield JqBoolean.TRUE; }
            case 'f' -> { r.pos += 5; yield JqBoolean.FALSE; }
            case 'n' -> {
                if (r.pos + 2 < r.end && r.data[r.pos + 1] == 'a' && r.data[r.pos + 2] == 'n') {
                    r.pos += 3;
                    yield JqNumber.of(Double.NaN);
                }
                r.pos += 4;
                yield JqNull.NULL;
            }
            case 'I' -> { r.pos += 8; yield JqNumber.of(Double.POSITIVE_INFINITY); }
            case 'N' -> { r.pos += 3; yield JqNumber.of(Double.NaN); }
            // A single quote can never start a value: without this arm it
            // falls into parseNumberBytes, which yields 0 consuming nothing,
            // and the lenient array loop chews the string char-by-char into
            // garbage (issue #166). Other junk keeps lenient tolerance.
            case '\'' -> throw singleQuoteError();
            default -> parseNumberBytes(r);
        };
    }

    /**
     * Strict value dispatch (cold; only reachable with {@code strict} set).
     * Always-strict, so no flag branches inside: literals verified exactly,
     * {@code NaN}/{@code Infinity} rejected, and structural characters or
     * missing digits fall into {@link #parseNumberBytes}, whose digit-count
     * check rejects them.
     */
    private static JqValue parseValueStrict(JsonByteReader r, int depth) {
        if (r.pos >= r.end) throw strictError(r, "Unexpected end of input");
        int b = r.data[r.pos] & 0xFF;
        return switch (b) {
            case '{' -> parseObjectBytes(r, depth + 1);
            case '[' -> parseArrayBytes(r, depth);
            case '"' -> parseStringBytes(r);
            case 't' -> { expectLiteral(r, "true"); yield JqBoolean.TRUE; }
            case 'f' -> { expectLiteral(r, "false"); yield JqBoolean.FALSE; }
            case 'n' -> { expectLiteral(r, "null"); yield JqNull.NULL; }
            case 'N' -> throw strictError(r, "NaN is not valid JSON");
            case 'I' -> throw strictError(r, "Infinity is not valid JSON");
            default -> parseNumberBytes(r);
        };
    }

    private static JqValue parseArrayBytes(JsonByteReader r, int depth) {
        ArrayDeque<JqValue[]> stack = null;
        int[] stackCounts = null;
        int stackDepth = 0;

        while (r.pos < r.end && (r.data[r.pos] & 0xFF) == '[') {
            depth++;
            if (depth > r.maxDepth) throw new IllegalArgumentException("Exceeds depth limit for parsing");
            r.pos++;
            r.skipWs();

            if (r.pos < r.end && (r.data[r.pos] & 0xFF) == ']') {
                r.pos++;
                return unwindArrayStackBytes(stack, stackCounts, stackDepth, JqArray.EMPTY, r, depth);
            }
            if (r.pos >= r.end) {
                if (r.strict) throw strictError(r, "Unterminated array");
                break;
            }
            if ((r.data[r.pos] & 0xFF) == '[') {
                if (stack == null) { stack = new ArrayDeque<>(); stackCounts = new int[16]; }
                if (stackDepth >= stackCounts.length) stackCounts = java.util.Arrays.copyOf(stackCounts, stackCounts.length * 2);
                stack.push(new JqValue[8]);
                stackCounts[stackDepth++] = 0;
                continue;
            }
            break;
        }

        JqValue[] elems = new JqValue[8];
        int count = 0;
        elems[count++] = parseValueBytes(r, depth);
        r.skipWs();
        while (r.pos < r.end && (r.data[r.pos] & 0xFF) != ']') {
            if (r.strict && (r.data[r.pos] & 0xFF) != ',') {
                throw strictError(r, "Expected ',' or ']' in array");
            }
            r.pos++;
            if (count >= elems.length) elems = java.util.Arrays.copyOf(elems, elems.length * 2);
            elems[count++] = parseValueBytes(r, depth);
            r.skipWs();
        }
        if (r.pos >= r.end) {
            if (r.strict) throw strictError(r, "Unterminated array");
        } else r.pos++;
        JqValue result = JqArray.ofTrusted(elems, count);
        return unwindArrayStackBytes(stack, stackCounts, stackDepth, result, r, depth);
    }

    private static JqValue unwindArrayStackBytes(ArrayDeque<JqValue[]> stack, int[] stackCounts,
                                                  int stackDepth, JqValue result,
                                                  JsonByteReader r, int depth) {
        if (stack == null) return result;
        while (stackDepth > 0) {
            depth--;
            stackDepth--;
            JqValue[] elems = stack.pop();
            int count = stackCounts[stackDepth];
            if (count >= elems.length) elems = java.util.Arrays.copyOf(elems, elems.length * 2);
            elems[count++] = result;
            r.skipWs();
            while (r.pos < r.end && (r.data[r.pos] & 0xFF) != ']') {
                if (r.strict && (r.data[r.pos] & 0xFF) != ',') {
                    throw strictError(r, "Expected ',' or ']' in array");
                }
                r.pos++;
                if (count >= elems.length) elems = java.util.Arrays.copyOf(elems, elems.length * 2);
                elems[count++] = parseValueBytes(r, depth);
                r.skipWs();
            }
            if (r.pos >= r.end) {
                if (r.strict) throw strictError(r, "Unterminated array");
            } else r.pos++;
            result = JqArray.ofTrusted(elems, count);
        }
        return result;
    }

    // Linear duplicate-scan range: at most MAX compares per key with no
    // allocation. Past it, promotion builds a presized HashSet once.
    // Aligned above the inline key/value arrays (8) so small objects never
    // promote; wide objects pay a bounded linear head, then a set sized to
    // skip the early doubling resizes.
    private static final int DUP_LINEAR_MAX = 32;

    /**
     * Double a duplicate-tracking table, rehashing stored key indices.
     * Only the long array allocates; entries are positions, never nodes.
     */
    private static long[] dupGrow(long[] slots, String[] keys, int count) {
        long[] grown = new long[slots.length * 2];
        int mask = grown.length - 1;
        for (int i = 0; i <= count; i++) {
            int hash = JqObject.mix(keys[i].hashCode());
            int slot = hash & mask;
            while (grown[slot] != 0) slot = (slot + 1) & mask;
            grown[slot] = ((long) hash << 32) | (i + 1);
        }
        return grown;
    }

    private static JqObject parseObjectBytes(JsonByteReader r, int depth) {
        if (depth > r.maxDepth) throw new IllegalArgumentException("Exceeds depth limit for parsing");
        r.pos++;
        r.skipWs();
        if (r.pos < r.end && (r.data[r.pos] & 0xFF) == '}') { r.pos++; return JqObject.EMPTY; }

        String[] keys = new String[8];
        JqValue[] values = new JqValue[8];
        int count = 0;
        // Whether the arrays ever grew into scratch buffers. KEEP_LAST can
        // leave the final count at or below 8 after growing, so the scratch
        // decision must not be inferred from the count alone — otherwise the
        // finished object would retain a reusable scratch array that a later
        // wide sibling overwrites.
        boolean grew = false;
        // Strict duplicate tracking: linear scan while the object stays
        // small (no set, no per-key nodes), promoted to a flat
        // open-addressed table once past DUP_LINEAR_MAX. Slots pack the
        // mixed hash in the high 32 bits and the 1-based key index in
        // the low 32 (0 = empty), so probes reject on one int compare
        // and only run equals on hash matches. The mix must avalanche:
        // sequential keys hash sequentially, and open addressing merges
        // sequential homes into long runs (HashMap chains tolerate them,
        // linear probing does not) — hence the shared multiplicative
        // mixer, not a bare shift-xor. Indices (not references) are
        // stored, so buffer growth cannot invalidate the table. Lenient
        // parsing skips detection entirely, so it pays one predictable
        // branch per key and nothing else.
        long[] dupSlots = null;
        int dupSize = 0;
        while (true) {
            r.skipWs();
            if (r.pos >= r.end || (r.data[r.pos] & 0xFF) != '"') {
                throw new IllegalArgumentException("Expected '\"' for object key");
            }
            if (count >= keys.length) {
                // Large object: continue in per-depth scratch buffers, no doubling garbage
                keys = r.growKeys(depth, keys, count);
                values = r.growValues(depth, values, count);
                grew = true;
            }
            int keyStart = r.pos;
            // Keys interned for deduplication and reference equality in JqObject.get()
            keys[count] = parseAndInternKeyBytes(r);
            boolean dup = false;
            if (r.strict) {
                String key = keys[count];
                if (dupSlots != null) {
                    int hash = JqObject.mix(key.hashCode());
                    int mask = dupSlots.length - 1;
                    int slot = hash & mask;
                    while (true) {
                        long entry = dupSlots[slot];
                        if (entry == 0) {
                            dupSlots[slot] = ((long) hash << 32) | (count + 1);
                            if (++dupSize * 4 >= dupSlots.length * 3) {
                                dupSlots = dupGrow(dupSlots, keys, count);
                            }
                            break;
                        }
                        int idx = (int) entry - 1;
                        if ((int) (entry >>> 32) == hash
                                && (keys[idx] == key || keys[idx].equals(key))) {
                            dup = true;
                            break;
                        }
                        slot = (slot + 1) & mask;
                    }
                } else if (count <= DUP_LINEAR_MAX) {
                    // Escaped keys may not be interned, so == needs the
                    // equals fallback (same rule as the KEEP_LAST scan below).
                    for (int i = 0; i < count; i++) {
                        if (keys[i] == key || keys[i].equals(key)) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup && count == DUP_LINEAR_MAX) {
                        // Genuinely growing past the linear range: take the
                        // retained per-depth table when it fits, else a fresh
                        // one. A dup at exactly MAX leaves count frozen, so
                        // count can never exceed MAX with dupSlots == null
                        // after this point.
                        dupSlots = r.checkoutDupTable(depth);
                        if (dupSlots == null) dupSlots = new long[128];
                        int mask = dupSlots.length - 1;
                        for (int i = 0; i <= count; i++) {
                            int hash = JqObject.mix(keys[i].hashCode());
                            int slot = hash & mask;
                            while (dupSlots[slot] != 0) slot = (slot + 1) & mask;
                            dupSlots[slot] = ((long) hash << 32) | (i + 1);
                        }
                        dupSize = count + 1;
                    }
                }
            }
            if (dup && r.duplicatePolicy == ParseOptions.DuplicatePolicy.REJECT) {
                throw duplicateKeyError(r, keys[count], keyStart);
            }
            r.skipWs();
            if (r.strict && (r.pos >= r.end || (r.data[r.pos] & 0xFF) != ':')) {
                throw strictError(r, "Expected ':' in object");
            }
            r.pos++; // skip :
            JqValue parsed = parseValueBytes(r, depth);
            if (dup) {
                // KEEP_LAST (serde): the later value wins at the first-seen
                // key position (LinkedHashMap put semantics). The count stays
                // put, so the trailing slot is simply overwritten next round.
                for (int i = 0; i < count; i++) {
                    if (keys[i] == keys[count] || keys[i].equals(keys[count])) {
                        values[i] = parsed;
                        break;
                    }
                }
            } else {
                values[count] = parsed;
                count++;
            }
            r.skipWs();
            if (r.pos >= r.end || (r.data[r.pos] & 0xFF) == '}') {
                if (r.pos >= r.end && r.strict) throw strictError(r, "Unterminated object");
                r.pos++;
                break;
            }
            if (r.strict && (r.data[r.pos] & 0xFF) != ',') {
                throw strictError(r, "Expected ',' or '}' in object");
            }
            r.pos++; // skip ,
        }
        // Objects that overflowed into the scratch buffers get exact-size arrays; small
        // objects keep the arrays they were built in, as before.
        boolean scratch = grew;
        if (scratch) values = java.util.Arrays.copyOf(values, count);
        if (scratch) r.releaseScratchIfWasteful(depth, keys.length, count);
        if (dupSlots != null) r.retainDupTable(depth, dupSlots, count);
        // Reference equality is safe because all keys are interned.
        String[] sharedKeys = r.findSharedKeys(keys, count);
        if (sharedKeys != null) {
            return JqObject.ofArrays(sharedKeys, values, count);
        }
        String[] exactKeys = (scratch || count != keys.length) ? java.util.Arrays.copyOf(keys, count) : keys;
        r.rememberKeys(exactKeys);
        return JqObject.ofArrays(exactKeys, values, count);
    }

    /** Parse a JSON string value from bytes, returning a deferred JqString. */
    private static JqString parseStringBytes(JsonByteReader r) {
        r.pos++; // skip opening "
        int contentStart = r.pos;

        // SWAR fast path: scan 8 bytes at a time for quote or backslash.
        // In valid UTF-8, 0x22 (") and 0x5C (\) only appear as single-byte
        // characters -- continuation bytes are 0x80-0xBF, so byte scanning is safe.
        while (r.pos + 8 <= r.end) {
            long word = SwarUtil.loadLong(r.data, r.pos);
            long quoteHits = SwarUtil.applyPattern(word, SwarUtil.QUOTE_PATTERN);
            long bsHits = SwarUtil.applyPattern(word, SwarUtil.BACKSLASH_PATTERN);
            long anyHit = quoteHits | bsHits;
            if (anyHit != 0) {
                r.pos += SwarUtil.getIndex(anyHit);
                int b = r.data[r.pos] & 0xFF;
                if (b == '"') {
                    int contentEnd = r.pos;
                    r.pos++;
                    return JqString.deferredBytes(r.data, contentStart, contentEnd, false);
                }
                // Must be backslash
                return parseDeferredWithEscapesBytes(r, contentStart);
            }
            r.pos += 8;
        }
        // Scalar tail for remaining < 8 bytes
        while (r.pos < r.end) {
            int b = r.data[r.pos] & 0xFF;
            if (b == '"') {
                int contentEnd = r.pos;
                r.pos++;
                return JqString.deferredBytes(r.data, contentStart, contentEnd, false);
            }
            if (b == '\\') {
                return parseDeferredWithEscapesBytes(r, contentStart);
            }
            r.pos++;
        }
        throw new IllegalArgumentException("Unterminated string");
    }

    private static JqString parseDeferredWithEscapesBytes(JsonByteReader r, int contentStart) {
        // SWAR resume (issue #161): scan 8 bytes at a time for the next
        // quote or backslash, exactly like the clean fast path. An escaped
        // quote still opens with a backslash, so landing on either marker
        // is exact — handle the escape with the identical landing positions
        // as the scalar path, then resume block scanning. UTF-8 safety is
        // the same argument: continuation bytes never match either marker.
        //
        // Density gate: SWAR setup per hit-word is pure overhead when
        // nearly every word hits (escape-dense strings), so after 4
        // consecutive hit-words take one bounded scalar burst (same body as
        // the tail loop, via scanScalar) and re-check. Sparse shapes almost
        // never trip the gate; dense shapes approach scalar parity instead
        // of paying word setup per escape. Both constants are heuristics.
        int streak = 0;
        while (r.pos + 8 <= r.end) {
            if (streak >= 8) {
                JqString burst = scanScalar(r, contentStart, Math.min(r.pos + 256, r.end));
                if (burst != null) return burst;
                streak = 0;
                continue;
            }
            long word = SwarUtil.loadLong(r.data, r.pos);
            long anyHit = SwarUtil.applyPattern(word, SwarUtil.QUOTE_PATTERN)
                    | SwarUtil.applyPattern(word, SwarUtil.BACKSLASH_PATTERN);
            if (anyHit == 0) {
                r.pos += 8;
                streak = 0;
                continue;
            }
            streak++;
            r.pos += SwarUtil.getIndex(anyHit);
            int b = r.data[r.pos] & 0xFF;
            if (b == '"') {
                int contentEnd = r.pos;
                r.pos++;
                return JqString.deferredBytes(r.data, contentStart, contentEnd, true);
            }
            // Must be backslash: same handling as the scalar tail below.
            r.pos++; // skip backslash
            if (r.strict) validateEscapeBytes(r);
            else if (r.pos < r.end && (r.data[r.pos] & 0xFF) == 'u') r.pos += 4;
            r.pos++;
        }
        // Scalar tail for remaining < 8 bytes.
        while (r.pos < r.end) {
            int b = r.data[r.pos] & 0xFF;
            if (b == '"') {
                int contentEnd = r.pos;
                r.pos++;
                return JqString.deferredBytes(r.data, contentStart, contentEnd, true);
            }
            if (b == '\\') {
                r.pos++; // skip backslash
                if (r.strict) validateEscapeBytes(r);
                else if (r.pos < r.end && (r.data[r.pos] & 0xFF) == 'u') r.pos += 4;
            }
            r.pos++;
        }
        throw new IllegalArgumentException("Unterminated string");
    }

    /**
     * Scalar quote/backslash scan to {@code bound}, sharing the exact body
     * of the tail loop above (keep the two in sync: quote returns the
     * deferred string, backslash handling lands identically, anything else
     * advances one). Returns null when the bound is reached with no quote
     * so the caller resumes block scanning.
     */
    private static JqString scanScalar(JsonByteReader r, int contentStart, int bound) {
        while (r.pos < bound) {
            int b = r.data[r.pos] & 0xFF;
            if (b == '"') {
                int contentEnd = r.pos;
                r.pos++;
                return JqString.deferredBytes(r.data, contentStart, contentEnd, true);
            }
            if (b == '\\') {
                r.pos++; // skip backslash
                if (r.strict) validateEscapeBytes(r);
                else if (r.pos < r.end && (r.data[r.pos] & 0xFF) == 'u') r.pos += 4;
            }
            r.pos++;
        }
        return null;
    }

    /** Parse a JSON string from bytes and return the raw Java String (for object keys). */
    /**
     * Parse a JSON object key from bytes with fused SWAR scanning + hash computation.
     * Single-pass approach: hash is computed during the SWAR scan, eliminating
     * a separate pass over the key bytes. Cache verification uses byte-level
     * comparison via stored key bytes (quad-style, avoids char-by-char crossing).
     */
    private static String parseAndInternKeyBytes(JsonByteReader r) {
        final byte[] d = r.data;
        final int end = r.end;
        final int start = r.pos + 1;
        int p = start;
        int hash = 0;
        while (p < end) {
            int b = d[p] & 0xFF;
            if ((b == '"') | (b == '\\')) break;
            hash = hash * 31 + b;
            p++;
        }
        if (p < end && (d[p] & 0xFF) == '"') {
            r.pos = p + 1;
            return internKeyWithHash(d, start, p, hash);
        }
        return internKeyEscaped(r, start);
    }

    /** Cold path: escape inside the key. Kept out of the scan loop. */
    private static String internKeyEscaped(JsonByteReader r, int start) {
        r.pos = start - 1;
        return internFieldName(parseStringRawBytes(r));
    }

    /**
     * Intern a key using a pre-computed hash and quad-based verification.
     * The hash was computed during the SWAR scan (fused, no separate pass).
     *
     * <p>Verification strategy (Jackson-inspired):</p>
     * <ul>
     *   <li>Fast reject: compare hash (1 int) and length (1 int)</li>
     *   <li>Keys ≤ 12 bytes: compare 1-3 quads (int comparisons, no byte crossing)</li>
     *   <li>Keys &gt; 12 bytes: compare first 3 quads + matchBytes for remainder</li>
     * </ul>
     *
     * <p>Multi-slot linear probing (max 4 probes) replaces single-slot eviction.</p>
     */
    private static String internKeyWithHash(byte[] data, int start, int end, int hash) {
        int keyLen = end - start;

        // Compute quads for this key
        int q1 = keyLen >= 4 ? SwarUtil.loadInt(data, start) : SwarUtil.packPartialQuad(data, start, keyLen);
        int q2 = keyLen >= 8 ? SwarUtil.loadInt(data, start + 4) : (keyLen > 4 ? SwarUtil.packPartialQuad(data, start + 4, keyLen - 4) : 0);
        int q3 = keyLen >= 12 ? SwarUtil.loadInt(data, start + 8) : (keyLen > 8 ? SwarUtil.packPartialQuad(data, start + 8, keyLen - 8) : 0);

        // L1 lookup (thread-local, no contention, no cross-thread eviction)
        InternSlot[] l1 = INTERN_L1.get();
        for (int p = 0; p < L1_MAX_PROBES; p++) {
            InternSlot cached = l1[(mix(hash) + p) & L1_MASK];
            if (cached != null && cached.hash == hash && cached.qlen == keyLen
                    && cached.q1 == q1
                    && (keyLen <= 4 || cached.q2 == q2)
                    && (keyLen <= 8 || cached.q3 == q3)
                    && (keyLen <= 12 || matchBytesFrom(cached.keyBytes, data, start, 12, keyLen))) {
                return cached.key; // L1 HIT — zero allocation, no L2 access
            }
        }

        // L2 lookup (global, atomic snapshots)
        int firstEmpty = -1;
        for (int probe = 0; probe < INTERN_MAX_PROBES; probe++) {
            int s = (mix(hash) + probe) & INTERN_MASK;
            InternSlot slot = INTERN_SLOTS[s];
            if (slot == null) {
                if (firstEmpty < 0) firstEmpty = s;
                break;
            }
            if (slot.hash != hash || slot.qlen != keyLen) continue;
            if (q1 != slot.q1) continue;
            if (keyLen > 4 && q2 != slot.q2) continue;
            if (keyLen > 8 && q3 != slot.q3) continue;
            if (keyLen > 12) {
                if (!matchBytesFrom(slot.keyBytes, data, start, 12, keyLen)) continue;
            }
            l1[mix(hash) & L1_MASK] = slot; // promote to L1
            return slot.key;
        }

        // Cache miss: create string and store in both L1 and L2
        String result = new String(data, start, keyLen, java.nio.charset.StandardCharsets.UTF_8);
        result.hashCode(); // force JDK to cache hashCode
        int storeSlot = firstEmpty >= 0 ? firstEmpty : (mix(hash) & INTERN_MASK);
        InternSlot newSlot = new InternSlot(result,
                java.util.Arrays.copyOfRange(data, start, end),
                hash, q1, q2, q3, keyLen);
        INTERN_SLOTS[storeSlot] = newSlot;
        l1[mix(hash) & L1_MASK] = newSlot; // store in L1
        return result;
    }

    /**
     * Look up a field name in the intern table by byte range without inserting.
     * Returns the cached instance on a hit, or null on a miss (including any
     * range containing non-ASCII bytes, which take the String interning path).
     * <p>
     * The lookup mirrors {@link #internKeyWithHash} exactly (same quads, same
     * L1/L2 probes, L1 promotion on L2 hit) minus the miss-side allocation —
     * a hit here observes the same instance an interning call would return.
     * Used for second-sighting admission: callers intern only keys seen more
     * than once, keeping unique keys out of the shared table.
     */
    public static String probeFieldName(byte[] data, int start, int end) {
        int hash = 0;
        for (int p = start; p < end; p++) {
            int b = data[p] & 0xFF;
            if (b >= 0x80) return null;
            hash = hash * 31 + b;
        }
        int keyLen = end - start;
        int q1 = keyLen >= 4 ? SwarUtil.loadInt(data, start) : SwarUtil.packPartialQuad(data, start, keyLen);
        int q2 = keyLen >= 8 ? SwarUtil.loadInt(data, start + 4) : (keyLen > 4 ? SwarUtil.packPartialQuad(data, start + 4, keyLen - 4) : 0);
        int q3 = keyLen >= 12 ? SwarUtil.loadInt(data, start + 8) : (keyLen > 8 ? SwarUtil.packPartialQuad(data, start + 8, keyLen - 8) : 0);

        InternSlot[] l1 = INTERN_L1.get();
        for (int p = 0; p < L1_MAX_PROBES; p++) {
            InternSlot cached = l1[(mix(hash) + p) & L1_MASK];
            if (cached != null && cached.hash == hash && cached.qlen == keyLen
                    && cached.q1 == q1
                    && (keyLen <= 4 || cached.q2 == q2)
                    && (keyLen <= 8 || cached.q3 == q3)
                    && (keyLen <= 12 || matchBytesFrom(cached.keyBytes, data, start, 12, keyLen))) {
                return cached.key;
            }
        }
        for (int probe = 0; probe < INTERN_MAX_PROBES; probe++) {
            int s = (mix(hash) + probe) & INTERN_MASK;
            InternSlot slot = INTERN_SLOTS[s];
            if (slot == null) return null;
            if (slot.hash != hash || slot.qlen != keyLen) continue;
            if (q1 != slot.q1) continue;
            if (keyLen > 4 && q2 != slot.q2) continue;
            if (keyLen > 8 && q3 != slot.q3) continue;
            if (keyLen > 12) {
                if (!matchBytesFrom(slot.keyBytes, data, start, 12, keyLen)) continue;
            }
            l1[mix(hash) & L1_MASK] = slot; // promote to L1
            return slot.key;
        }
        return null;
    }

    /**
     * Compare bytes from a given offset (for keys > 12 bytes where quads cover first 12).
     */
    private static boolean matchBytesFrom(byte[] cached, byte[] data, int dataStart, int fromOffset, int totalLen) {
        for (int i = fromOffset; i < totalLen; i++) {
            if (cached[i] != data[dataStart + i]) return false;
        }
        return true;
    }

    private static String parseStringRawBytes(JsonByteReader r) {
        r.pos++; // skip opening "
        int start = r.pos;

        // SWAR fast path: scan 8 bytes at a time for quote or backslash
        while (r.pos + 8 <= r.end) {
            long word = SwarUtil.loadLong(r.data, r.pos);
            long quoteHits = SwarUtil.applyPattern(word, SwarUtil.QUOTE_PATTERN);
            long bsHits = SwarUtil.applyPattern(word, SwarUtil.BACKSLASH_PATTERN);
            long anyHit = quoteHits | bsHits;
            if (anyHit != 0) {
                r.pos += SwarUtil.getIndex(anyHit);
                if ((r.data[r.pos] & 0xFF) == '"') {
                    String result = new String(r.data, start, r.pos - start, java.nio.charset.StandardCharsets.UTF_8);
                    r.pos++;
                    return result;
                }
                break; // backslash -- fall through to slow path
            }
            r.pos += 8;
        }
        // Scalar tail: scan remaining < 8 bytes
        while (r.pos < r.end) {
            int b = r.data[r.pos] & 0xFF;
            if (b == '"') {
                String result = new String(r.data, start, r.pos - start, java.nio.charset.StandardCharsets.UTF_8);
                r.pos++;
                return result;
            }
            if (b == '\\') break;
            r.pos++;
        }

        // Slow path: string contains escape sequences
        // Convert the byte range to String first, then process escapes
        int scanStart = start;
        // Find closing quote
        int escStart = r.pos;
        while (r.pos < r.end) {
            int b = r.data[r.pos] & 0xFF;
            if (b == '"') break;
            if (b == '\\') {
                r.pos++;
                if (r.strict) validateEscapeBytes(r);
                else if (r.pos < r.end && (r.data[r.pos] & 0xFF) == 'u') r.pos += 4;
            }
            r.pos++;
        }
        int contentEnd = r.pos;
        r.pos++; // skip closing "
        String raw = new String(r.data, start, contentEnd - start, java.nio.charset.StandardCharsets.UTF_8);
        return JqString.unescapeJson(raw, 0, raw.length());
    }

    private static JqNumber parseNumberBytes(JsonByteReader r) {
        int start = r.pos;
        boolean negative = false;

        if (r.pos < r.end && (r.data[r.pos] & 0xFF) == '-') {
            negative = true;
            r.pos++;
            if (r.pos < r.end) {
                if ((r.data[r.pos] & 0xFF) == 'I') {
                    if (r.strict) throw strictError(r, "Infinity is not valid JSON");
                    r.pos += 8;
                    return JqNumber.of(Double.NEGATIVE_INFINITY);
                }
                if ((r.data[r.pos] & 0xFF) == 'N') {
                    if (r.strict) throw strictError(r, "NaN is not valid JSON");
                    r.pos += 3;
                    return JqNumber.of(Double.NaN);
                }
            }
        }

        long acc = 0;
        int intDigitCount = 0;
        while (r.pos < r.end) {
            int d = (r.data[r.pos] & 0xFF) - '0';
            if (d < 0 || d > 9) break;
            // No overflow bookkeeping: at most 18 digits reach the fast
            // paths below and 10^18-1 fits in a long, so any wraparound
            // implies 19+ digits, which the count gates reject anyway.
            acc = acc * 10 + d;
            intDigitCount++;
            r.pos++;
        }

        boolean isDecimal = false;
        long fracPart = 0;
        int fracCount = 0;
        if (r.pos < r.end && (r.data[r.pos] & 0xFF) == '.') {
            isDecimal = true;
            r.pos++;
            while (r.pos < r.end) {
                int d = (r.data[r.pos] & 0xFF) - '0';
                if (d < 0 || d > 9) break;
                if (fracCount < 18) { fracPart = fracPart * 10 + d; fracCount++; }
                r.pos++;
            }
            if (r.strict && fracCount == 0) throw strictError(r, "Invalid number");
        }

        int expValue = 0;
        int expCount = 0;
        if (r.pos < r.end && ((r.data[r.pos] & 0xFF) == 'e' || (r.data[r.pos] & 0xFF) == 'E')) {
            isDecimal = true;
            r.pos++;
            boolean expNegative = false;
            if (r.pos < r.end && (r.data[r.pos] & 0xFF) == '-') { expNegative = true; r.pos++; }
            else if (r.pos < r.end && (r.data[r.pos] & 0xFF) == '+') { r.pos++; }
            while (r.pos < r.end) {
                int d = (r.data[r.pos] & 0xFF) - '0';
                if (d < 0 || d > 9) break;
                // Past 9 digits the magnitude is decisive (far outside the
                // ±18 fast-path window); keep counting digits for validation
                // but stop accumulating so the int cannot wrap around.
                if (expCount < 9) expValue = expValue * 10 + d;
                expCount++;
                r.pos++;
            }
            if (r.strict && expCount == 0) throw strictError(r, "Invalid number");
            if (expNegative) expValue = -expValue;
        }

        // Single branch when off: canonical numbers short-circuit before any load.
        if (r.strict && (intDigitCount == 0 || (intDigitCount > 1
                && (r.data[negative ? start + 1 : start] & 0xFF) == '0'))) {
            throw strictError(r, "Invalid number");
        }

        if (!isDecimal && intDigitCount <= 18) {
            // Integer-lexed negative zero is a float (serde/jq distinguish -0 from 0).
            if (negative && acc == 0) return JqNumber.of(-0.0, true);
            return JqNumber.of(negative ? -acc : acc);
        }

        if (isDecimal && intDigitCount <= 15 && fracCount <= 15) {
            int totalDigits = intDigitCount + fracCount;
            if (totalDigits <= 15 && expValue >= -18 && expValue <= 18) {
                double value = (double) acc;
                if (fracCount > 0) value += (double) fracPart / POW10[fracCount];
                if (expValue > 0 && expValue < POW10.length) value *= POW10[expValue];
                else if (expValue < 0 && -expValue < POW10.length) value /= POW10[-expValue];
                else if (expValue != 0) value *= Math.pow(10, expValue);
                return JqNumber.of(negative ? -value : value, isDecimal);
            }
        }

        return parseNumberFallback(r.data, start, r.pos, isDecimal, negative);
    }

    /** The char parser shares the fallback without allocating a substring. */
    private static JqNumber parseNumberFallback(String source, int start, int end,
                                                 boolean isDecimal, boolean negative) {
        int length = end - start;
        // Only literals longer than a 19-digit long can be beyond-long;
        // shorter ones take the shared path with zero added work.
        if (!isDecimal && length - (negative ? 1 : 0) > 19) {
            int scan = negative ? 1 : 0;
            while (scan < length && source.charAt(start + scan) == '0') scan++;
            int digits = length - scan;
            if (digits > 19 || (digits == 19 && !fitsLongLiteral(source, start + scan, negative))) {
                // Beyond long: slice the normalized digits directly, no buffer copy.
                String literal = source.substring(start + scan, end);
                if (negative) literal = "-" + literal;
                return JqNumber.ofDeferredInteger(literal);
            }
        }
        char[] chars = numberBuffer(length);
        source.getChars(start, end, chars, 0);
        return parseNumberChars(chars, length, isDecimal, negative);
    }

    /** Numeric tokens contain only ASCII; BigDecimal can parse the copied slice directly. */
    private static JqNumber parseNumberFallback(byte[] source, int start, int end,
                                                 boolean isDecimal, boolean negative) {
        int length = end - start;
        if (!isDecimal && length - (negative ? 1 : 0) > 19) {
            int scan = negative ? 1 : 0;
            while (scan < length && source[start + scan] == '0') scan++;
            int digits = length - scan;
            if (digits > 19 || (digits == 19 && !fitsLongLiteral(source, start + scan, negative))) {
                String literal = new String(source, start + scan, digits, java.nio.charset.StandardCharsets.UTF_8);
                if (negative) literal = "-" + literal;
                return JqNumber.ofDeferredInteger(literal);
            }
        }
        char[] chars = numberBuffer(length);
        for (int i = 0; i < length; i++) {
            chars[i] = (char) (source[start + i] & 0xFF);
        }
        return parseNumberChars(chars, length, isDecimal, negative);
    }

    private static char[] numberBuffer(int length) {
        char[] chars = NUMBER_BUFFER.get();
        if (length <= chars.length) return chars;
        char[] expanded = new char[length];
        if (length <= MAX_NUMBER_BUFFER_CHARS) NUMBER_BUFFER.set(expanded);
        return expanded;
    }

    /** Range-check integer literals before conversion: overflow is not an exceptional input. */
    private static JqNumber parseNumberChars(char[] chars, int length,
                                             boolean isDecimal, boolean negative) {
        if (!isDecimal) {
            int first = negative ? 1 : 0;
            while (first < length && chars[first] == '0') first++;
            int digits = length - first;
            if (digits < 19 || (digits == 19 && fitsLongLiteral(chars, first, negative))) {
                // Accumulate negatively so Long.MIN_VALUE is representable too.
                long parsed = 0;
                for (int i = first; i < length; i++) {
                    parsed = parsed * 10 - (chars[i] - '0');
                }
                if (parsed == 0 && negative) return JqNumber.of(-0.0, true);
                return JqNumber.of(negative ? parsed : -parsed);
            }
            // Beyond long: retain the normalized significant digits and defer
            // conversion until first numeric access.
            return parseDeferredInteger(chars, first, digits, negative);
        }
        try {
            return JqNumber.of(new BigDecimal(chars, 0, length), isDecimal);
        } catch (NumberFormatException | ArithmeticException e) {
            // Extreme exponents retain the existing double fallback. Only
            // this exceptional path needs a String.
            return JqNumber.of(Double.parseDouble(new String(chars, 0, length)), isDecimal);
        }
    }

    /**
     * Beyond-long integer literals: at least 20 significant digits (or a
     * 19-digit overflow, proven by the caller). Copies the normalized
     * significant digits (leading zeros were skipped into {@code first})
     * into an owned String and defers conversion — parsing pays only the
     * copy, numeric access materializes the eager-identical value.
     */
    private static JqNumber parseDeferredInteger(char[] chars, int first, int digits, boolean negative) {
        String literal = new String(chars, first, digits);
        if (negative) literal = "-" + literal;
        return JqNumber.ofDeferredInteger(literal);
    }

    /** Compare exactly 19 significant digits against the appropriate signed-long boundary. */
    private static boolean fitsLongLiteral(char[] chars, int first, boolean negative) {
        String limit = negative ? "9223372036854775808" : "9223372036854775807";
        for (int i = 0; i < limit.length(); i++) {
            int difference = chars[first + i] - limit.charAt(i);
            if (difference != 0) return difference < 0;
        }
        return true;
    }

    /** String-slice variant of the 19-digit boundary check, for the pre-gate. */
    private static boolean fitsLongLiteral(String source, int offset, boolean negative) {
        String limit = negative ? "9223372036854775808" : "9223372036854775807";
        for (int i = 0; i < limit.length(); i++) {
            int difference = source.charAt(offset + i) - limit.charAt(i);
            if (difference != 0) return difference < 0;
        }
        return true;
    }

    /** Byte-slice variant (digits are validated ASCII, so masking is exact). */
    private static boolean fitsLongLiteral(byte[] source, int offset, boolean negative) {
        String limit = negative ? "9223372036854775808" : "9223372036854775807";
        for (int i = 0; i < limit.length(); i++) {
            int difference = (source[offset + i] & 0xFF) - limit.charAt(i);
            if (difference != 0) return difference < 0;
        }
        return true;
    }

    /**
     * Index into a JqValue: array[number], object["key"], null[anything] = null.
     * Shared by both the bytecode VM and the tree-walk evaluator.
     */
    public static JqValue indexValue(JqValue base, JqValue index) {
        return switch (base) {
            case JqArray arr when index instanceof JqNumber n -> {
                if (n.isNaN()) yield JqNull.NULL;
                yield arr.get((int) n.longValue());
            }
            case JqObject obj when index instanceof JqString s -> obj.get(s.stringValue());
            case JqNull _ -> JqNull.NULL;
            default -> throw new JqTypeError("Cannot index " + base.type().jqName()
                    + " with " + index.type().jqName() + " (" + index.toJsonString() + ")");
        };
    }

    /**
     * Depth-limited JSON serialization for tojson.
     * Uses iterative array handling to avoid stack overflow on deeply nested structures.
     * At depths exceeding MAX_SERIALIZE_DEPTH, outputs "&lt;skipped: too deep&gt;" instead of
     * recursing further — matching jq's behavior for extremely nested structures.
     */
    public static String toJsonStringDepthLimited(JqValue val) {
        StringBuilder sb = SERIALIZER_BUFFER.get();
        sb.setLength(0);
        appendJsonDepthLimited(val, sb, 0);
        String result = sb.toString();
        if (sb.capacity() > SERIALIZE_BUFFER_MAX_RETAINED) {
            SERIALIZER_BUFFER.set(new StringBuilder(SERIALIZE_BUFFER_INIT));
        }
        return result;
    }

    private static void appendJsonDepthLimited(JqValue val, StringBuilder sb, int depth) {
        // Handle arrays iteratively to avoid stack overflow on deeply nested structures
        int arrayNesting = 0;
        while (val instanceof JqArray arr) {
            depth++;
            if (depth > MAX_SERIALIZE_DEPTH) {
                sb.append("\"<skipped: too deep>\"");
                for (int i = 0; i < arrayNesting; i++) sb.append(']');
                return;
            }
            var elements = arr.arrayValue();
            if (elements.isEmpty()) {
                sb.append("[]");
                for (int i = 0; i < arrayNesting; i++) sb.append(']');
                return;
            }
            sb.append('[');
            arrayNesting++;
            // If single-element array whose element is also an array, continue iteratively
            if (elements.size() == 1) {
                val = elements.get(0);
                continue; // loop back to check if it's another array
            }
            // Multi-element array: serialize first element iteratively, rest recursively
            appendJsonDepthLimited(elements.get(0), sb, depth);
            for (int i = 1; i < elements.size(); i++) {
                sb.append(',');
                appendJsonDepthLimited(elements.get(i), sb, depth);
            }
            for (int i = 0; i < arrayNesting; i++) sb.append(']');
            return;
        }
        // Non-array value: use appendTo for scalars and objects
        if (val instanceof JqObject obj) {
            if (depth > MAX_SERIALIZE_DEPTH) {
                sb.append("\"<skipped: too deep>\"");
            } else {
                sb.append('{');
                boolean first = true;
                for (var e : obj.objectValue().entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append('"');
                    JqString.escapeJson(e.getKey(), sb);
                    sb.append("\":");
                    appendJsonDepthLimited(e.getValue(), sb, depth + 1);
                }
                sb.append('}');
            }
        } else {
            val.appendTo(sb);
        }
        for (int i = 0; i < arrayNesting; i++) sb.append(']');
    }
}

package io.hyperfoil.tools.jjq.value;

import java.util.Arrays;

/**
 * Growable byte buffer for direct UTF-8 JSON serialization.
 * Used by {@link JqValue#appendToBytes(BytOutput)} to serialize JSON
 * directly to bytes without intermediate String/StringBuilder allocation.
 *
 * <p>Public so generated mapping classes can emit JSON bytes directly.
 * Obtain an instance via {@link JqValues#acquireByteBuffer()} and release it
 * with {@link JqValues#releaseByteBuffer(BytOutput)} or
 * {@link JqValues#writeByteBuffer(BytOutput, java.io.OutputStream)}.</p>
 */
public final class BytOutput {

    private static final byte[] NULL_BYTES = {'n', 'u', 'l', 'l'};
    private static final byte[] TRUE_BYTES = {'t', 'r', 'u', 'e'};
    private static final byte[] FALSE_BYTES = {'f', 'a', 'l', 's', 'e'};

    /** Two-digit ASCII pairs {@code "00".."99"} for paired long emission. */
    private static final byte[] LONG_DIGIT_PAIRS = new byte[200];
    static {
        for (int i = 0; i < 100; i++) {
            LONG_DIGIT_PAIRS[i * 2] = (byte) ('0' + i / 10);
            LONG_DIGIT_PAIRS[i * 2 + 1] = (byte) ('0' + i % 10);
        }
    }

    /**
     * Retained scratch for getChars-based string encoding (issue #157):
     * 8K chars (16 KB) per buffer, so retention stays bounded no matter
     * how large any single string is. Longer strings encode in windows;
     * windows never split a surrogate pair (back off one char).
     */
    private static final int CHAR_SCRATCH_SIZE = 8192;

    /**
     * Length at or below which ASCII writes use a plain charAt loop:
     * pollution damage on a short copy is bounded below the getChars
     * fixed cost (same crossover argument as JqString.SHORT_SCAN_MAX).
     */
    private static final int SHORT_WRITE_MAX = 64;

    byte[] buf;
    int pos;
    private char[] charScratch;

    public BytOutput(int initialCapacity) {
        this.buf = new byte[initialCapacity];
    }

    /** Create a BytOutput backed by the given buffer. Used for pre-sized serialization. */
    public BytOutput(byte[] buffer) {
        this.buf = buffer;
    }

    public void reset() {
        pos = 0;
    }

    // --- Primitive writes ---

    public void writeByte(int b) {
        ensureCapacity(1);
        buf[pos++] = (byte) b;
    }

    public void writeBytes(byte[] src, int off, int len) {
        ensureCapacity(len);
        System.arraycopy(src, off, buf, pos, len);
        pos += len;
    }

    public void writeBytes(byte[] src) {
        writeBytes(src, 0, src.length);
    }

    public void writeNull() { writeBytes(NULL_BYTES); }
    public void writeTrue() { writeBytes(TRUE_BYTES); }
    public void writeFalse() { writeBytes(FALSE_BYTES); }

    // --- ASCII string write (field names, number formatting) ---

    public void writeAsciiString(String s) {
        int len = s.length();
        ensureCapacity(len);
        if (len <= SHORT_WRITE_MAX) {
            for (int i = 0; i < len; i++) {
                buf[pos++] = (byte) s.charAt(i);
            }
            return;
        }
        // getChars copy instead of a charAt loop: charAt carries a
        // coder-profile branch that any UTF-16 charAt in the process
        // poisons for every call site (paper "Escaping JSON one lane at
        // a time", profile pollution). Callers pass ASCII-only content.
        char[] scratch = charScratch();
        byte[] dst = buf;
        int p = pos;
        int off = 0;
        while (off < len) {
            int wEnd = Math.min(off + CHAR_SCRATCH_SIZE, len);
            s.getChars(off, wEnd, scratch, 0);
            for (int i = 0; i < wEnd - off; i++) {
                dst[p++] = (byte) scratch[i];
            }
            off = wEnd;
        }
        pos = p;
    }

    /**
     * Write a double in jq's plain notation for common values.
     * Integral values serialize without a decimal point (3.0 becomes "3").
     */
    public void writeDouble(double d) {
        if (Double.doubleToRawLongBits(d) == Long.MIN_VALUE) {
            writeAsciiString("-0");
            return;
        }
        long asLong = (long) d;
        if ((double) asLong == d) {
            writeLong(asLong);
        } else if ((d > 1e-3 && d < 1e15) || (d < -1e-3 && d > -1e15)) {
            writeAsciiString(Double.toString(d));
        } else {
            writeAsciiString(java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString());
        }
    }

    // --- Long to ASCII digits ---

    public void writeLong(long v) {
        if (v == 0) {
            writeByte('0');
            return;
        }
        if (v == Long.MIN_VALUE) {
            writeAsciiString("-9223372036854775808");
            return;
        }
        boolean neg = v < 0;
        if (neg) {
            writeByte('-');
            v = -v;
        }
        int digits = longDigitCount(v);
        ensureCapacity(digits);
        int end = pos + digits;
        pos = end;
        byte[] b = buf;
        // Paired emission, backward: one division by 100 per two digits.
        // Long arithmetic throughout — an int cast would overflow past
        // ~2^31, and the pair remainder past ~10^11.
        while (v >= 100) {
            long q = v / 100;
            int r = (int) (v - q * 100);
            end -= 2;
            b[end] = LONG_DIGIT_PAIRS[r * 2];
            b[end + 1] = LONG_DIGIT_PAIRS[r * 2 + 1];
            v = q;
        }
        if (v >= 10) {
            end -= 2;
            int r = (int) v;
            b[end] = LONG_DIGIT_PAIRS[r * 2];
            b[end + 1] = LONG_DIGIT_PAIRS[r * 2 + 1];
        } else {
            b[--end] = (byte) ('0' + v);
        }
    }

    /**
     * Digit count for a positive long via binary descent: at most four
     * constant divisions (multiply-shift after JIT), zero for values
     * below 100. Does not modify the argument.
     */
    private static int longDigitCount(long v) {
        int digits = 1;
        if (v >= 100_000_000_000_000L) { v /= 100_000_000_000_000L; digits += 14; }
        if (v >= 100_000_000L)         { v /= 100_000_000L;         digits += 8; }
        if (v >= 10_000L)              { v /= 10_000L;              digits += 4; }
        if (v >= 100L)                 { v /= 100L;                 digits += 2; }
        if (v >= 10L)                  {                             digits += 1; }
        return digits;
    }

    // --- UTF-8 encoding ---

    /**
     * Encode a Java String as UTF-8 bytes (raw, no JSON escaping).
     * Used for number formatting and other non-string contexts.
     * Copies through the bounded scratch via {@code getChars} like the
     * escape path (issue #162 experiment): same pollution immunity,
     * same window discipline for surrogate pairs.
     */
    public void writeUTF8(String s) {
        int len = s.length();
        ensureCapacity(len * 3); // worst case UTF-8
        char[] scratch = charScratch();
        int off = 0;
        while (off < len) {
            int wEnd = Math.min(off + CHAR_SCRATCH_SIZE, len);
            if (wEnd < len && Character.isHighSurrogate(s.charAt(wEnd - 1))) {
                wEnd--; // never split a surrogate pair across windows
            }
            s.getChars(off, wEnd, scratch, 0);
            // Hand-inlined segment encode with locals (issue #162): the
            // shared helper must stay callable from the escape path, but
            // inlining it here regressed mixed strings while forbidding
            // the inline won everywhere. Local buffer/position copies;
            // buf already sized above and scratch never aliases it.
            byte[] dst = buf;
            int p = pos;
            for (int i = 0; i < wEnd - off; i++) {
                char c = scratch[i];
                if (c < 0x80) {
                    dst[p++] = (byte) c;
                } else if (c < 0x800) {
                    dst[p++] = (byte) (0xC0 | (c >> 6));
                    dst[p++] = (byte) (0x80 | (c & 0x3F));
                } else if (Character.isHighSurrogate(c) && i + 1 < wEnd - off
                        && Character.isLowSurrogate(scratch[i + 1])) {
                    char low = scratch[++i];
                    int cp = Character.toCodePoint(c, low);
                    dst[p++] = (byte) (0xF0 | (cp >> 18));
                    dst[p++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                    dst[p++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                    dst[p++] = (byte) (0x80 | (cp & 0x3F));
                } else {
                    dst[p++] = (byte) (0xE0 | (c >> 12));
                    dst[p++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                    dst[p++] = (byte) (0x80 | (c & 0x3F));
                }
            }
            pos = p;
            off = wEnd;
        }
    }

    /**
     * Write a JSON-escaped string value as UTF-8 bytes (with surrounding quotes).
     * Handles escaping of control characters, backslash, and double quote.
     */
    public void writeJsonString(String s) {
        writeByte('"');
        escapeJsonToBytes(s);
        writeByte('"');
    }

    /**
     * JSON-escape a string and write as UTF-8 bytes (without quotes).
     * Copies the string through a bounded scratch buffer with
     * {@code getChars} (an intrinsic, no per-call-site coder profile) and
     * runs the segment scan/encode over the array (issue #157 experiment).
     * Semantics match the previous charAt loop exactly, including lone
     * surrogates (3-byte form) and window-boundary behavior.
     *
     * @return true when any escape sequence was emitted (lets callers fuse
     *         the cleanliness memo into the escape run instead of
     *         pre-scanning with a pollution-exposed charAt loop)
     */
    public boolean escapeJsonToBytes(String s) {
        final int len = s.length();
        ensureCapacity(len * 3 + 12); // worst case: all escaped + UTF-8 expansion
        boolean escaped = false;
        if (len <= CHAR_SCRATCH_SIZE) {
            // Vector route first: it encodes through getBytes(UTF_8) and
            // never reads the char scratch, so routed values skip the
            // getChars copy (and the scratch's lazy allocation) entirely.
            // A zero return (lone surrogate, or the module disabled via
            // property) falls through and pays getChars once — the same
            // total work as before the reorder.
            if (ESCAPE_KERNEL_PRESENT && len >= ESCAPE_KERNEL_MIN_LEN) {
                int routed = escapeVectorRoute(s);
                if (routed != 0) {
                    return routed == ROUTED_ESCAPED;
                }
            }
            char[] scratch = charScratch();
            s.getChars(0, len, scratch, 0);
            // Fused clean-ASCII fast path: write the bytes while scanning
            // and break to the segment scanner at the first character that
            // needs it — control character, quote, backslash, or non-ASCII.
            // Fully clean ASCII values — the dominant shape — encode in a
            // single pass instead of scan-then-encode. The escape and
            // UTF-8 arms stay out of this loop: merging them into the scan
            // is what regressed escape-dense strings in the reverted #175
            // fusion, and the break hands off at the offending index so an
            // escape-heavy value never re-walks its prefix.
            byte[] dst = buf; // capture after ensureCapacity, which may replace it
            int p = pos;
            int i = 0;
            for (; i < len; i++) {
                char c = scratch[i];
                if (c < 0x20 || c >= 0x80 || c == '"' || c == '\\') {
                    break;
                }
                dst[p++] = (byte) c;
            }
            pos = p;
            if (i == len) {
                return false;
            }
            return escapeWindow(scratch, i, len);
        }
        char[] scratch = charScratch();
        int off = 0;
        while (off < len) {
            int wEnd = Math.min(off + CHAR_SCRATCH_SIZE, len);
            if (wEnd < len && Character.isHighSurrogate(s.charAt(wEnd - 1))) {
                wEnd--; // never split a surrogate pair across windows
            }
            s.getChars(off, wEnd, scratch, 0);
            escaped |= escapeWindow(scratch, 0, wEnd - off);
            off = wEnd;
        }
        return escaped;
    }

    /**
     * Optional vector escape-copy kernel (jjq-vector), discovered once.
     * Null when the module is absent — the fused scalar loop below then
     * runs unchanged, keeping jjq-core dependency-free.
     */
    private static final JsonEscapeCopyKernel ESCAPE_KERNEL = loadEscapeKernel();

    /**
     * Compile-time constant when no implementation is present: C2 folds
     * the whole dispatch block away, so the module-absent path compiles
     * exactly like the pre-SPI code (a never-taken branch would still
     * perturb the compilation of short-string-heavy workloads).
     */
    private static final boolean ESCAPE_KERNEL_PRESENT = ESCAPE_KERNEL != null;

    private static JsonEscapeCopyKernel loadEscapeKernel() {
        try {
            var it = java.util.ServiceLoader.load(JsonEscapeCopyKernel.class).iterator();
            return it.hasNext() ? it.next() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Shortest value routed to the escape-copy kernel: below two vector
     * iterations the interface call and setup outweigh the width gain,
     * so short values stay on the scalar loop.
     */
    private static final int ESCAPE_KERNEL_MIN_LEN = 64;

    /** Lazily-created bounded scratch (retained with the buffer). */
    private char[] charScratch() {
        char[] c = charScratch;
        if (c == null) {
            charScratch = c = new char[CHAR_SCRATCH_SIZE];
        }
        return c;
    }

    /** Route outcomes from {@link #escapeVectorRoute(String)}. */
    private static final int ROUTED_ESCAPED = 2;

    /**
     * Vector escape route (#1B), kept out of {@code escapeJsonToBytes}
     * so that method stays under the inlining cutoff: UTF-8 encode, one
     * vector call scans and copies the clean prefix — the bytes are
     * already UTF-8, so multi-byte sequences are clean and copy
     * verbatim. A '?' in the encode (getUTF8Bytes replaces lone
     * surrogates with '?') is verified lossless by a UTF-8 round-trip
     * and retried trusted; a failed verify — a real lone surrogate —
     * returns 0 so the caller uses the char-based route, which
     * preserves the CESU-8 form.
     *
     * @return 0 when not routed (fall back to the scalar loop), 1 when
     *         routed and clean, {@link #ROUTED_ESCAPED} when routed with
     *         escape sequences emitted
     */
    private int escapeVectorRoute(String s) {
        JsonEscapeCopyKernel kernel = ESCAPE_KERNEL;
        if (!kernel.isEnabled()) {
            return 0;
        }
        byte[] utf8 = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int n = utf8.length;
        int copied = kernel.copyCleanUtf8(utf8, 0, n, buf, pos, false);
        if (copied < 0
                && s.equals(new String(utf8, 0, n, java.nio.charset.StandardCharsets.UTF_8))) {
            copied = kernel.copyCleanUtf8(utf8, 0, n, buf, pos, true);
        }
        if (copied < 0) {
            return 0; // lone surrogate: char route preserves CESU-8
        }
        pos += copied;
        if (copied == n) {
            return 1;
        }
        return escapeWindowBytes(utf8, copied, n) ? ROUTED_ESCAPED : 1;
    }

    /**
     * Byte-level escape scan over a UTF-8-encoded window: the same
     * emission as {@link #escapeWindow}, but the source is already
     * UTF-8, so clean runs — including multi-byte sequences — copy
     * verbatim and only controls, quotes, and backslashes escape.
     * Called for the window remainder after the vector kernel's clean
     * prefix, which ends at {@code start}.
     *
     * @return true when any escape sequence was emitted in this window
     */
    private boolean escapeWindowBytes(byte[] a, int start, int end) {
        int segStart = start;
        boolean escaped = false;

        for (int i = start; i < end; i++) {
            int b = a[i] & 0xFF;
            if (b >= 0x20 && b != '"' && b != '\\') continue; // scan — no writes

            escaped = true;
            // Found escape byte — flush clean segment before it
            if (i > segStart) {
                int n = i - segStart;
                ensureCapacity(n);
                System.arraycopy(a, segStart, buf, pos, n);
                pos += n;
            }
            // Emit escape sequence
            ensureCapacity(6);
            switch (b) {
                case '"'  -> { buf[pos++] = '\\'; buf[pos++] = '"'; }
                case '\\' -> { buf[pos++] = '\\'; buf[pos++] = '\\'; }
                case '\b' -> { buf[pos++] = '\\'; buf[pos++] = 'b'; }
                case '\f' -> { buf[pos++] = '\\'; buf[pos++] = 'f'; }
                case '\n' -> { buf[pos++] = '\\'; buf[pos++] = 'n'; }
                case '\r' -> { buf[pos++] = '\\'; buf[pos++] = 'r'; }
                case '\t' -> { buf[pos++] = '\\'; buf[pos++] = 't'; }
                default -> {
                    // Control character: \\u00XX
                    buf[pos++] = '\\';
                    buf[pos++] = 'u';
                    buf[pos++] = '0';
                    buf[pos++] = '0';
                    buf[pos++] = (byte) Character.forDigit((b >> 4) & 0xF, 16);
                    buf[pos++] = (byte) Character.forDigit(b & 0xF, 16);
                }
            }
            segStart = i + 1;
        }
        // Flush remaining clean segment
        if (segStart < end) {
            int n = end - segStart;
            ensureCapacity(n);
            System.arraycopy(a, segStart, buf, pos, n);
            pos += n;
        }
        return escaped;
    }

    /**
     * Segment-based escape scan over a scratch window: scans for the next
     * escape character without writing, then bulk-flushes the clean segment.
     * Called with a nonzero start after the fused clean-ASCII prefix in
     * {@link #escapeJsonToBytes} breaks at the first character needing
     * this path, and with zero for every window of a long string.
     *
     * @return true when any escape sequence was emitted in this window
     */
    private boolean escapeWindow(char[] a, int start, int end) {
        int segStart = start;
        boolean escaped = false;

        for (int i = start; i < end; i++) {
            char c = a[i];
            if (c >= 0x20 && c != '"' && c != '\\') continue; // scan — no writes

            escaped = true;
            // Found escape character — flush clean segment before it
            if (i > segStart) {
                writeSegmentChars(a, segStart, i);
            }
            // Emit escape sequence
            ensureCapacity(6);
            switch (c) {
                case '"'  -> { buf[pos++] = '\\'; buf[pos++] = '"'; }
                case '\\' -> { buf[pos++] = '\\'; buf[pos++] = '\\'; }
                case '\b' -> { buf[pos++] = '\\'; buf[pos++] = 'b'; }
                case '\f' -> { buf[pos++] = '\\'; buf[pos++] = 'f'; }
                case '\n' -> { buf[pos++] = '\\'; buf[pos++] = 'n'; }
                case '\r' -> { buf[pos++] = '\\'; buf[pos++] = 'r'; }
                case '\t' -> { buf[pos++] = '\\'; buf[pos++] = 't'; }
                default -> {
                    // Control character: \\u00XX
                    buf[pos++] = '\\';
                    buf[pos++] = 'u';
                    buf[pos++] = '0';
                    buf[pos++] = '0';
                    buf[pos++] = (byte) Character.forDigit((c >> 4) & 0xF, 16);
                    buf[pos++] = (byte) Character.forDigit(c & 0xF, 16);
                }
            }
            segStart = i + 1;
        }
        // Flush remaining clean segment
        if (segStart < end) {
            writeSegmentChars(a, segStart, end);
        }
        return escaped;
    }

    /**
     * Bulk-write a clean scratch segment (no escape characters) as UTF-8.
     * Most JSON string values are ASCII, so the inner loop is a simple byte cast.
     */
    private void writeSegmentChars(char[] a, int start, int end) {
        int segLen = end - start;
        ensureCapacity(segLen * 3); // worst case UTF-8
        // Local buffer/position copies: every buf[pos++] is two field
        // accesses in bytecode, and this loop is almost all stores.
        // Capture buf after ensureCapacity, which may replace the array.
        byte[] dst = buf;
        int p = pos;
        for (int i = start; i < end; i++) {
            char c = a[i];
            if (c < 0x80) {
                dst[p++] = (byte) c;
            } else if (c < 0x800) {
                dst[p++] = (byte) (0xC0 | (c >> 6));
                dst[p++] = (byte) (0x80 | (c & 0x3F));
            } else if (Character.isHighSurrogate(c) && i + 1 < end
                    && Character.isLowSurrogate(a[i + 1])) {
                char low = a[++i];
                int cp = Character.toCodePoint(c, low);
                dst[p++] = (byte) (0xF0 | (cp >> 18));
                dst[p++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                dst[p++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                dst[p++] = (byte) (0x80 | (cp & 0x3F));
            } else {
                dst[p++] = (byte) (0xE0 | (c >> 12));
                dst[p++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                dst[p++] = (byte) (0x80 | (c & 0x3F));
            }
        }
        pos = p;
    }

    public byte[] toByteArray() {
        return (pos == buf.length) ? buf : Arrays.copyOf(buf, pos);
    }

    private void ensureCapacity(int needed) {
        if (pos + needed > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, pos + needed));
        }
    }
}

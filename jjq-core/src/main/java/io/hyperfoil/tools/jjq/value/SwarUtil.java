package io.hyperfoil.tools.jjq.value;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * SWAR (SIMD Within A Register) utility for processing 8 bytes in parallel
 * using standard {@code long} arithmetic. No {@code Unsafe}, no Vector API,
 * no JDK internals -- just portable Java.
 *
 * <p>Core algorithms ported from Netty's {@code SWARUtil} (Apache 2.0, 2024)
 * and Lemire's SWAR digit parsing technique.</p>
 *
 * <p>Used by the byte[]-based JSON parser to scan strings, skip whitespace,
 * and validate digits 8 bytes at a time instead of 1.</p>
 *
 * @see <a href="https://github.com/netty/netty/blob/4.1/common/src/main/java/io/netty/util/internal/SWARUtil.java">Netty SWARUtil</a>
 * @see <a href="https://lemire.me/blog/2022/01/21/swar-explained-parsing-eight-digits/">Lemire: SWAR digit parsing</a>
 */
final class SwarUtil {

    /** VarHandle for reading 8 bytes from a byte[] as a little-endian long. */
    static final VarHandle LONG_HANDLE =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /** Pre-compiled patterns for common JSON bytes. */
    static final long QUOTE_PATTERN = compilePattern((byte) '"');
    static final long BACKSLASH_PATTERN = compilePattern((byte) '\\');

    private SwarUtil() {}

    /**
     * Broadcast a byte value to all 8 lanes of a long.
     * E.g., {@code compilePattern((byte)'"')} produces {@code 0x2222222222222222L}.
     */
    static long compilePattern(byte b) {
        return (b & 0xFFL) * 0x101010101010101L;
    }

    /**
     * Apply a compiled pattern to a word. Returns a bitmask where each byte
     * that matches the pattern has its high bit (bit 7) set.
     *
     * @param word    8 bytes loaded from the input
     * @param pattern compiled pattern from {@link #compilePattern}
     * @return bitmask with high bit set on matching bytes
     */
    static long applyPattern(long word, long pattern) {
        long input = word ^ pattern;
        long tmp = (input & 0x7F7F7F7F7F7F7F7FL) + 0x7F7F7F7F7F7F7F7FL;
        return ~(tmp | input | 0x7F7F7F7F7F7F7F7FL);
    }

    /**
     * Return the byte index (0-7) of the first matching byte in the result
     * of {@link #applyPattern}. Returns 8 if no match found.
     * Uses little-endian ordering (lowest memory address = lowest bits).
     */
    static int getIndex(long patternResult) {
        return Long.numberOfTrailingZeros(patternResult) >>> 3;
    }

    /**
     * Load 8 bytes from a byte array as a little-endian long.
     * The caller must ensure {@code offset + 8 <= data.length}.
     */
    static long loadLong(byte[] data, int offset) {
        return (long) LONG_HANDLE.get(data, offset);
    }

    // ========================================================================
    //  ASCII detection
    // ========================================================================

    /**
     * Check if 8 bytes are all ASCII (bit 7 clear on every byte).
     * A single AND + comparison -- the fastest possible 8-byte ASCII check.
     */
    static boolean isAscii8(long word) {
        return (word & 0x8080808080808080L) == 0;
    }

    /**
     * Check if all bytes in a range are ASCII using SWAR.
     * Processes 8 bytes at a time via {@link #isAscii8}, with a scalar
     * tail for remaining bytes.
     *
     * <p><b>Precondition:</b> {@code offset + length <= data.length}.
     * The caller must ensure the range is within the array bounds.
     * The {@link #loadLong} call via VarHandle will throw
     * {@link ArrayIndexOutOfBoundsException} on out-of-bounds access.</p>
     *
     * @param data   the byte array to check
     * @param offset start position (inclusive)
     * @param length number of bytes to check
     * @return true if all bytes in the range have bit 7 clear (are ASCII)
     */
    static boolean isAscii(byte[] data, int offset, int length) {
        int end = offset + length;
        // SWAR: check 8 bytes at a time
        while (offset + 8 <= end) {
            if (!isAscii8(loadLong(data, offset))) return false;
            offset += 8;
        }
        // Scalar tail for remaining < 8 bytes
        while (offset < end) {
            if ((data[offset] & 0x80) != 0) return false;
            offset++;
        }
        return true;
    }

    // ========================================================================
    //  Case conversion (ported from Netty SWARUtil / Facebook Folly)
    // ========================================================================

    /**
     * Convert 8 ASCII bytes to lowercase in ~7 operations.
     * Non-ASCII bytes and non-letter bytes are unchanged.
     */
    static long toLowerCase(long word) {
        long mask = applyUpperCasePattern(word) >>> 2;
        return word | mask;
    }

    /**
     * Convert 8 ASCII bytes to uppercase in ~7 operations.
     * Non-ASCII bytes and non-letter bytes are unchanged.
     */
    static long toUpperCase(long word) {
        long mask = applyLowerCasePattern(word) >>> 2;
        return word & ~mask;
    }

    /** Returns a bitmask with high bit set on ASCII uppercase bytes (A-Z). */
    private static long applyUpperCasePattern(long word) {
        long rotated = word & 0x7F7F7F7F7F7F7F7FL;
        rotated += 0x2525252525252525L;
        rotated &= 0x7F7F7F7F7F7F7F7FL;
        rotated += 0x1A1A1A1A1A1A1A1AL;
        rotated &= ~word;
        rotated &= 0x8080808080808080L;
        return rotated;
    }

    /** Returns a bitmask with high bit set on ASCII lowercase bytes (a-z). */
    private static long applyLowerCasePattern(long word) {
        long rotated = word & 0x7F7F7F7F7F7F7F7FL;
        rotated += 0x0505050505050505L;
        rotated &= 0x7F7F7F7F7F7F7F7FL;
        rotated += 0x1A1A1A1A1A1A1A1AL;
        rotated &= ~word;
        rotated &= 0x8080808080808080L;
        return rotated;
    }
}

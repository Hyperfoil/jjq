package io.hyperfoil.tools.jjq.value;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class Utf8Test {

    @Test
    void validInputs() {
        assertEquals(-1, Utf8.validate(new byte[0], 0, 0));
        assertValid("hello");
        assertValid("caf\u00e9"); // 2-byte
        assertValid("\u20ac100"); // 3-byte
        assertValid("\uD83D\uDE00"); // 4-byte astral
        assertValid("a\u00e9b\u20acc\uD83D\uDE00d");
    }

    @Test
    void loneContinuationAndLeadBytes() {
        assertEquals(1, Utf8.validate(new byte[]{'a', (byte) 0x80}, 0, 2));
        assertEquals(0, Utf8.validate(new byte[]{(byte) 0xC0, (byte) 0xAF}, 0, 2)); // overlong /
        assertEquals(0, Utf8.validate(new byte[]{(byte) 0xC1, (byte) 0xBF}, 0, 2)); // overlong
        assertEquals(0, Utf8.validate(new byte[]{(byte) 0xF5, (byte) 0x80}, 0, 2)); // above max
        assertEquals(0, Utf8.validate(new byte[]{(byte) 0xFF}, 0, 1));
        assertEquals(0, Utf8.validate(new byte[]{(byte) 0xFE}, 0, 1));
    }

    @Test
    void truncatedSequencesReportLeadByte() {
        assertEquals(1, Utf8.validate(new byte[]{'a', (byte) 0xC3}, 0, 2));
        assertEquals(0, Utf8.validate(new byte[]{(byte) 0xE2, (byte) 0x82}, 0, 2));
        assertEquals(0, Utf8.validate(new byte[]{(byte) 0xF0, (byte) 0x9F, (byte) 0x98}, 0, 3));
    }

    @Test
    void overlongThreeByte() {
        assertEquals(0, Utf8.validate(new byte[]{(byte) 0xE0, (byte) 0x80, (byte) 0xAF}, 0, 3));
        assertEquals(-1, Utf8.validate(new byte[]{(byte) 0xE0, (byte) 0xA0, (byte) 0x80}, 0, 3));
    }

    @Test
    void surrogatesRejected() {
        assertEquals(0, Utf8.validate(new byte[]{(byte) 0xED, (byte) 0xA0, (byte) 0x80}, 0, 3)); // D800
        assertEquals(-1, Utf8.validate(new byte[]{(byte) 0xED, (byte) 0x9F, (byte) 0xBF}, 0, 3)); // D7FF ok
    }

    @Test
    void aboveMaximumRejected() {
        assertEquals(0, Utf8.validate(new byte[]{(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80}, 0, 4));
        assertEquals(-1, Utf8.validate(new byte[]{(byte) 0xF4, (byte) 0x8F, (byte) 0xBF, (byte) 0xBF}, 0, 4)); // 10FFFF ok
    }

    @Test
    void badContinuation() {
        assertEquals(0, Utf8.validate(new byte[]{(byte) 0xC3, (byte) 0x28}, 0, 2));
    }

    @Test
    void offsetAndLengthHonored() {
        byte[] bytes = "a\u00e9".getBytes(StandardCharsets.UTF_8); // [a, C3, A9]
        assertEquals(-1, Utf8.validate(bytes, 0, 1));
        assertEquals(-1, Utf8.validate(bytes, 0, 3));
        assertEquals(1, Utf8.validate(bytes, 1, 1)); // truncated C3
        byte[] bad = {(byte) 0x80};
        assertEquals(-1, Utf8.validate(new byte[]{'a', (byte) 0x80}, 0, 1)); // outside range
        assertEquals(0, Utf8.validate(bad, 0, 1));
    }

    @Test
    void wordBoundariesKeepOffsets() {
        // Multibyte sequences straddling 8-byte lanes, invalid leads at
        // every lane offset, and truncated tails: the word skip must never
        // move or mask an error offset.
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < 100; i++) prefix.append('x');
        String twoByte = "\u00e9"; // C3 A9
        String threeByte = "\u20ac"; // E2 82 AC
        String fourByte = "\uD83D\uDE00"; // F0 9F 98 80
        assertEquals(-1, Utf8.validate((prefix + twoByte + "yyyyyyyy" + threeByte
                + "zzzzzzzz" + fourByte + "0123456789").getBytes(StandardCharsets.UTF_8), 0, 135));
        for (int lane = 0; lane < 8; lane++) {
            byte[] bytes = new byte[64];
            java.util.Arrays.fill(bytes, (byte) 'a');
            bytes[lane] = (byte) 0x80; // lone continuation
            assertEquals(lane, Utf8.validate(bytes, 0, 64), "lane " + lane);
        }
        for (int i = 56; i < 64; i++) {
            byte[] truncated = new byte[64];
            java.util.Arrays.fill(truncated, (byte) 'a');
            truncated[i] = (byte) 0xE2; // 3-byte lead with no continuations
            assertEquals(i, Utf8.validate(truncated, 0, 64), "truncated at " + i);
        }
        byte[] overlong = new byte[72];
        java.util.Arrays.fill(overlong, (byte) 'a');
        overlong[63] = (byte) 0xC0;
        overlong[64] = (byte) 0xAF;
        assertEquals(63, Utf8.validate(overlong, 0, 72));
    }

    private static void assertValid(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        assertEquals(-1, Utf8.validate(bytes, 0, bytes.length), s);
    }
}

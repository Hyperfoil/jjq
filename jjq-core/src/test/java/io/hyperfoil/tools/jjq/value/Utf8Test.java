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

    private static void assertValid(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        assertEquals(-1, Utf8.validate(bytes, 0, bytes.length), s);
    }
}

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

    @Test
    void bulkReentryKeepsOffsets() {
        // Issue #160: complete 2/3/4-byte sequences starting at every lane
        // offset exercise the bulk re-entry loop; a malformed lead after
        // several sequences must still report its exact offset.
        for (String ch : new String[]{"\u00e9", "\u20ac", "\uD83D\uDE00"}) {
            byte[] enc = ch.getBytes(StandardCharsets.UTF_8);
            for (int at = 60; at < 76; at++) {
                byte[] bytes = new byte[at + enc.length + 20];
                java.util.Arrays.fill(bytes, (byte) 'q');
                System.arraycopy(enc, 0, bytes, at, enc.length);
                assertEquals(-1, Utf8.validate(bytes, 0, bytes.length), ch + " at " + at);
            }
        }
        String doc = "x".repeat(100) + "\u00e9" + "y".repeat(100) + "\u20ac"
                + "z".repeat(100) + "\uD83D\uDE00" + "w".repeat(100);
        byte[] good = doc.getBytes(StandardCharsets.UTF_8);
        assertEquals(-1, Utf8.validate(good, 0, good.length));
        byte[] bad = good.clone();
        bad[250] = (byte) 0x80; // lone continuation past several sequences
        assertEquals(250, Utf8.validate(bad, 0, bad.length));
        bad = good.clone();
        bad[bad.length - 1] = (byte) 0xC3; // truncated tail past re-entries
        assertEquals(bad.length - 1, Utf8.validate(bad, 0, bad.length));
    }

    @Test
    void sharedSequenceHelperContract() {
        // Issue #190: the package-private sequence helper shared by Utf8
        // and StructuralIndexer returns the offset just past a valid
        // sequence, or -1 when malformed. Both callers translate -1 into
        // their own error form (lead offset vs exception) without
        // re-encoding the rules.
        byte[] two = {(byte) 0xC3, (byte) 0xA9};
        assertEquals(2, Utf8.sequenceEnd(two, 0, 2));
        byte[] three = {(byte) 0xE2, (byte) 0x82, (byte) 0xAC};
        assertEquals(3, Utf8.sequenceEnd(three, 0, 3));
        byte[] four = {(byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x80};
        assertEquals(4, Utf8.sequenceEnd(four, 0, 4));
        // Malformed of every class reports -1; the caller owns the offset.
        assertEquals(-1, Utf8.sequenceEnd(new byte[]{(byte) 0x80}, 0, 1));
        assertEquals(-1, Utf8.sequenceEnd(new byte[]{(byte) 0xC0, (byte) 0xAF}, 0, 2));
        assertEquals(-1, Utf8.sequenceEnd(new byte[]{(byte) 0xC3, (byte) 0x28}, 0, 2));
        assertEquals(-1, Utf8.sequenceEnd(new byte[]{(byte) 0xE0, (byte) 0x80, (byte) 0xAF}, 0, 3));
        assertEquals(-1, Utf8.sequenceEnd(new byte[]{(byte) 0xED, (byte) 0xA0, (byte) 0x80}, 0, 3));
        assertEquals(-1, Utf8.sequenceEnd(new byte[]{(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80}, 0, 4));
        assertEquals(-1, Utf8.sequenceEnd(new byte[]{(byte) 0xF5}, 0, 1));
        // Truncated sequences report -1 regardless of how many bytes remain.
        assertEquals(-1, Utf8.sequenceEnd(new byte[]{(byte) 0xE2, (byte) 0x82}, 0, 2));
        assertEquals(-1, Utf8.sequenceEnd(new byte[]{(byte) 0xF0, (byte) 0x9F, (byte) 0x98}, 0, 3));
        // Mid-buffer starts work: end offsets are absolute.
        byte[] buf = {'a', (byte) 0xE2, (byte) 0x82, (byte) 0xAC, 'b'};
        assertEquals(4, Utf8.sequenceEnd(buf, 1, 5));
    }

    private static void assertValid(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        assertEquals(-1, Utf8.validate(bytes, 0, bytes.length), s);
    }

    @Test
    void everyLeadByteAtEveryMod16Offset() {
        // Issue #193: lead classification must not depend on word alignment.
        // Every possible first byte, placed at every offset modulo 16 in an
        // otherwise-ASCII buffer. ASCII bytes are valid; anything else alone
        // is malformed at exactly its own offset.
        for (int value = 0; value < 256; value++) {
            for (int at = 0; at < 16; at++) {
                // 80 bytes so the 8-byte word skip engages; the probe sits
                // at every lane offset modulo 16.
                byte[] bytes = new byte[80];
                java.util.Arrays.fill(bytes, (byte) 'a');
                bytes[at] = (byte) value;
                int expected = value < 0x80 ? -1 : at;
                assertEquals(expected, Utf8.validate(bytes, 0, bytes.length),
                        String.format("byte 0x%02X at %d", value, at));
            }
        }
    }

    @Test
    void sequencesCrossingSixteenByteBoundaries() {
        // Valid 2/3/4-byte sequences starting at every offset across 8- and
        // 16-byte boundaries, plus truncated variants reporting the lead.
        String[] chars = {"\u00e9", "\u20ac", "\uD83D\uDE00"};
        for (String ch : chars) {
            byte[] enc = ch.getBytes(StandardCharsets.UTF_8);
            for (int at = 0; at < 24; at++) {
                // 96 bytes so the word skip engages and the sequence crosses
                // 8- and 16-byte boundaries at every residue.
                byte[] bytes = new byte[96];
                java.util.Arrays.fill(bytes, (byte) 'q');
                System.arraycopy(enc, 0, bytes, at, enc.length);
                assertEquals(-1, Utf8.validate(bytes, 0, bytes.length),
                        ch + " at " + at);
                // Truncate the sequence by one byte: the lead offset reports.
                byte[] cut = java.util.Arrays.copyOf(bytes, at + enc.length - 1);
                assertEquals(at, Utf8.validate(cut, 0, cut.length),
                        "truncated " + ch + " at " + at);
            }
        }
    }

    @Test
    void nonzeroOffsetRanges() {
        // Ranges starting mid-buffer: a complete sequence at a nonzero
        // offset validates; a range starting on a continuation byte of an
        // otherwise-valid sequence reports that byte.
        byte[] bytes = ("ab\u00e9cd\u20acef").getBytes(StandardCharsets.UTF_8);
        assertEquals(-1, Utf8.validate(bytes, 2, bytes.length - 2));
        assertEquals(-1, Utf8.validate(bytes, 0, bytes.length));
        // bytes[3] is the A9 continuation of C3 A9 ("\u00e9" starts at 2).
        assertEquals(3, Utf8.validate(bytes, 3, bytes.length - 3));
        // Single-byte range on a lead reports truncation at the lead.
        assertEquals(2, Utf8.validate(bytes, 2, 1));
        // Range ending exactly at a sequence end is valid.
        assertEquals(-1, Utf8.validate(bytes, 0, 4));
    }

    @Test
    void secondByteBoundaryValues() {
        // Each special second-byte rule pinned on both sides of its edge.
        // E0: second byte must be >= A0 (else overlong).
        assertEquals(0, Utf8.validate(new byte[]{(byte) 0xE0, (byte) 0x9F, (byte) 0xBF}, 0, 3));
        assertEquals(-1, Utf8.validate(new byte[]{(byte) 0xE0, (byte) 0xA0, (byte) 0x80}, 0, 3));
        // ED: second byte must be <= 9F (else surrogate).
        assertEquals(-1, Utf8.validate(new byte[]{(byte) 0xED, (byte) 0x9F, (byte) 0xBF}, 0, 3)); // D7FF
        assertEquals(0, Utf8.validate(new byte[]{(byte) 0xED, (byte) 0xA0, (byte) 0x80}, 0, 3)); // D800
        assertEquals(0, Utf8.validate(new byte[]{(byte) 0xED, (byte) 0xBF, (byte) 0xBF}, 0, 3)); // DFFF
        // F0: second byte must be >= 90 (else overlong).
        assertEquals(0, Utf8.validate(new byte[]{(byte) 0xF0, (byte) 0x8F, (byte) 0xBF, (byte) 0xBF}, 0, 4));
        assertEquals(-1, Utf8.validate(new byte[]{(byte) 0xF0, (byte) 0x90, (byte) 0x80, (byte) 0x80}, 0, 4));
        // F4: second byte must be <= 8F (else above U+10FFFF).
        assertEquals(-1, Utf8.validate(new byte[]{(byte) 0xF4, (byte) 0x8F, (byte) 0xBF, (byte) 0xBF}, 0, 4));
        assertEquals(0, Utf8.validate(new byte[]{(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80}, 0, 4));
        // C2..DF always need exactly one continuation.
        assertEquals(-1, Utf8.validate(new byte[]{(byte) 0xC2, (byte) 0x80}, 0, 2));
        assertEquals(-1, Utf8.validate(new byte[]{(byte) 0xDF, (byte) 0xBF}, 0, 2));
        assertEquals(0, Utf8.validate(new byte[]{(byte) 0xDF, (byte) 0xC0}, 0, 2));
    }
}

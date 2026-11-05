package io.hyperfoil.tools.jjq.value;

import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.io.IOException;
import java.io.Writer;

import static org.junit.jupiter.api.Assertions.*;

class CharOutputTest {

    @Test
    void segmentsPreserveOrderForStringAndWriter() throws Exception {
        String prefix = "a".repeat(31);
        String suffix = "\uD83D\uDE00\u4E2D".repeat(30);
        String expected = prefix + "\u00E9" + suffix;
        try (CharOutput out = new CharOutput(32)) {
            out.append(prefix).append('\u00E9').append(suffix);
            assertEquals(expected, out.toString());
            StringWriter writer = new StringWriter();
            out.writeTo(writer);
            assertEquals(expected, writer.toString());
        }
    }

    @Test
    void escapingAgreesWithExistingCharPathForEveryChar() {
        try (CharOutput out = new CharOutput(32)) {
            for (int code = 0; code <= 0xFFFF; code++) {
                String input = String.valueOf((char) code);
                StringBuilder expected = new StringBuilder();
                boolean escaped = JqString.escapeJson(input, expected);
                assertEquals(escaped, out.escapeJson(input), "escape flag for " + code);
                assertEquals(expected.toString(), out.toString(), "char " + code);
                out.close();
            }
        }
    }

    @Test
    void quotingAndEscapesSurviveSegmentEdges() {
        for (String value : new String[]{"", null, "\u00E9\u4E2D\uD83D\uDE00",
                "\"\\\u0000\b\f\n\r\t", "\uD83Dx\uDE00", "\u2028\u2066"}) {
            for (int padding : new int[]{0, 1, 30, 31, 32, 63}) {
                StringBuilder expected = new StringBuilder("x".repeat(padding));
                JqValues.appendJsonString(expected, value);
                try (CharOutput out = new CharOutput(32)) {
                    out.append("x".repeat(padding));
                    out.appendJsonString(value);
                    assertEquals(expected.toString(), out.toString());
                }
            }
        }
    }

    @Test
    void numbersAndNullsKeepExistingRendering() {
        for (long value : new long[]{Long.MIN_VALUE, -100, -1, 0, 9, 10, Long.MAX_VALUE}) {
            try (CharOutput out = new CharOutput(32)) {
                out.append("x".repeat(31)).append(value);
                assertEquals("x".repeat(31) + value, out.toString());
            }
        }
        for (double value : new double[]{-0.0, 0.0, 3.0, 98.5, -2.5, 1e-3, 1e15,
                Double.MIN_VALUE, Double.MAX_VALUE}) {
            StringBuilder expected = new StringBuilder();
            JqNumber.appendDouble(expected, value);
            try (CharOutput out = new CharOutput(32)) {
                out.appendDouble(value);
                assertEquals(expected.toString(), out.toString());
            }
        }
        try (CharOutput out = new CharOutput(32)) {
            out.append(42).append(true).append(false).append((Object) null);
            assertEquals("42truefalsenull", out.toString());
        }
    }

    @Test
    void parsedTreesAndDeferredStringsKeepExistingOutput() throws Exception {
        for (String json : new String[]{"null", "true", "-0", "1.5", "1e30",
                "[null,false,12,\"text\",{\"q\\\"\":\"\\n\"}]",
                "{\"text\":\"" + "\u00E9\u4E2D\uD83D\uDE00".repeat(3000) + "\"}"}) {
            JqValue value = JqValues.parse(json);
            String expected = value.toJsonString();
            for (int pass = 0; pass < 3; pass++) {
                try (CharOutput out = new CharOutput(32)) {
                    value.appendTo(out);
                    assertEquals(expected, out.toString());
                    StringWriter writer = new StringWriter();
                    out.writeTo(writer);
                    assertEquals(expected, writer.toString());
                }
            }
        }
    }

    @Test
    void nestedAcquisitionDoesNotResetOuterOutput() {
        try (CharOutput outer = CharOutput.acquire()) {
            outer.append("before");
            try (CharOutput inner = CharOutput.acquire()) {
                assertNotSame(outer, inner);
                inner.append("inner");
                assertEquals("inner", inner.toString());
            }
            outer.append("after");
            assertEquals("beforeafter", outer.toString());
        }
    }

    @Test
    void failedWriterDoesNotPolluteTheNextCall() throws Exception {
        JqValue value = JqValues.parse("{\"text\":\"" + "x".repeat(20000) + "\"}");
        Writer failing = new Writer() {
            @Override
            public void write(char[] chars, int offset, int length) throws IOException {
                throw new IOException("expected writer failure");
            }
            @Override
            public void flush() { }
            @Override
            public void close() { }
        };
        assertThrows(IOException.class, () -> JqValues.serializeBufferedTo(value, failing));
        StringWriter recovered = new StringWriter();
        JqValues.serializeBufferedTo(JqString.of("next"), recovered);
        assertEquals("\"next\"", recovered.toString());
    }

    @Test
    void independentThreadsAndOversizedCallsKeepTheirOwnContents() throws Exception {
        String large = "x".repeat(1024 * 1024);
        try (CharOutput out = CharOutput.acquire()) {
            out.append(large);
            assertEquals(large, out.toString());
        }
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                try (CharOutput out = CharOutput.acquire()) {
                    return out.append("a".repeat(20000)).toString();
                }
            });
            var second = executor.submit(() -> {
                try (CharOutput out = CharOutput.acquire()) {
                    return out.append("b".repeat(20000)).toString();
                }
            });
            assertEquals("a".repeat(20000), first.get());
            assertEquals("b".repeat(20000), second.get());
        }
        try (CharOutput next = CharOutput.acquire()) {
            assertEquals(0, next.length());
            assertEquals("", next.toString());
        }
    }
}

package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.value.CharOutput;

/** Copies legacy writer output without sharing active scratch or creating a String. */
final class LegacyJsonOutput {
    private static final int INITIAL_CHARS = 128;
    private static final int MAX_RETAINED_CHARS = 1024 * 1024;
    private static final ThreadLocal<StringBuilder> BUFFERS = new ThreadLocal<>();

    private LegacyJsonOutput() {}

    static <T> void append(Mapping<T> mapping, T value, CharOutput out, JqMapper mapper) {
        StringBuilder buffer = BUFFERS.get();
        if (buffer == null) buffer = new StringBuilder(INITIAL_CHARS);
        else BUFFERS.set(null);
        try {
            mapping.appendJson(value, buffer, mapper);
            out.append(buffer);
        } finally {
            buffer.setLength(0);
            if (buffer.capacity() <= MAX_RETAINED_CHARS) BUFFERS.set(buffer);
        }
    }
}

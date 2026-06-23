package io.hyperfoil.tools.jjq.benchmark;

import org.openjdk.jmh.annotations.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Microbenchmark comparing three approaches to constructing a Java String
 * from ASCII byte arrays:
 * <ol>
 *   <li>{@code new String(bytes, UTF_8)} -- standard, JDK handles ASCII detection internally</li>
 *   <li>{@code new String(bytes, US_ASCII)} -- explicit ASCII charset, skips UTF-8 validation</li>
 *   <li>{@code isAscii(bytes) ? new String(bytes, US_ASCII) : new String(bytes, UTF_8)}
 *       -- SWAR pre-check + conditional charset selection</li>
 * </ol>
 *
 * <p>Decision criteria: if {@code checked_ascii} is > 5% faster than {@code direct_utf8}
 * at typical JSON field name lengths (5-20 bytes), implement the ASCII fast path
 * in the byte parser. Otherwise, skip it.</p>
 *
 * <h3>Running</h3>
 * <pre>
 *   java -jar jjq-benchmark-*.jar AsciiStringConstructionBenchmark \
 *     -prof gc -rf json -rff ascii-benchmark.json
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgs = {"-Xmx2g", "-Xms2g"})
@State(Scope.Benchmark)
public class AsciiStringConstructionBenchmark {

    @Param({"5", "10", "20", "50", "100"})
    int len;

    private byte[] asciiBytes;

    @Setup(Level.Trial)
    public void setup() {
        // Generate ASCII-only content (typical JSON field name / value pattern)
        var sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + (i % 26)));
        }
        asciiBytes = sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    @Benchmark
    public String direct_utf8() {
        return new String(asciiBytes, 0, len, StandardCharsets.UTF_8);
    }

    @Benchmark
    public String direct_ascii() {
        return new String(asciiBytes, 0, len, StandardCharsets.US_ASCII);
    }

    @Benchmark
    public String checked_ascii() {
        if (isAscii(asciiBytes, 0, len)) {
            return new String(asciiBytes, 0, len, StandardCharsets.US_ASCII);
        }
        return new String(asciiBytes, 0, len, StandardCharsets.UTF_8);
    }

    private static final java.lang.invoke.VarHandle LONG_HANDLE =
            java.lang.invoke.MethodHandles.byteArrayViewVarHandle(
                    long[].class, java.nio.ByteOrder.LITTLE_ENDIAN);

    /**
     * SWAR ASCII check (mirrors SwarUtil.isAscii).
     */
    private static boolean isAscii(byte[] data, int offset, int length) {
        int end = offset + length;
        while (offset + 8 <= end) {
            long word = (long) LONG_HANDLE.get(data, offset);
            if ((word & 0x8080808080808080L) != 0) return false;
            offset += 8;
        }
        while (offset < end) {
            if ((data[offset] & 0x80) != 0) return false;
            offset++;
        }
        return true;
    }
}

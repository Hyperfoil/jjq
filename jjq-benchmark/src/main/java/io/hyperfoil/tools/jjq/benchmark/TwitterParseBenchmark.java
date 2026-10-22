package io.hyperfoil.tools.jjq.benchmark;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Full-document parse of simdjson's {@code twitter.json} (631,515 bytes,
 * 100 tweets, heavy Unicode, no duplicate keys), plus a parse-and-extract
 * row matching the On-Demand {@code search_metadata.count} example.
 *
 * <p>Primary comparison is {@code parse_jjq_strict_bytes} against
 * simdjson's DOM parser: both fully validate and build a complete result.
 * Lenient rows are diagnostic only (duplicate-key checks skipped).</p>
 *
 * <h3>Running</h3>
 * <pre>
 *   ./scripts/run-benchmarks.sh TwitterParseBenchmark
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgs = {"-Xmx2g", "-Xms2g"})
@State(Scope.Benchmark)
public class TwitterParseBenchmark {

    private String twitterJson;
    private byte[] twitterJsonBytes;
    private JqProgram countQuery;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("benchmark-data/twitter.json")) {
            if (in == null) throw new IOException("Missing resource: benchmark-data/twitter.json");
            twitterJsonBytes = in.readAllBytes();
        }
        twitterJson = new String(twitterJsonBytes, StandardCharsets.UTF_8);
        countQuery = JqProgram.compile(".search_metadata.count");
        JqValue count = countQuery.apply(JqValues.parseStrict(twitterJsonBytes));
        if (!"100".equals(count.toJsonString())) {
            throw new IllegalStateException("twitter.json count mismatch: " + count.toJsonString());
        }
    }

    @Benchmark
    public JqValue parse_jjq() {
        return JqValues.parse(twitterJson);
    }

    @Benchmark
    public JqValue parse_jjq_bytes() {
        return JqValues.parse(twitterJsonBytes);
    }

    @Benchmark
    public JqValue parse_jjq_strict_bytes() {
        return JqValues.parseStrict(twitterJsonBytes);
    }

    @Benchmark
    public JqValue query_count() {
        return countQuery.apply(JqValues.parseStrict(twitterJsonBytes));
    }
}

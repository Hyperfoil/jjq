package io.hyperfoil.tools.jjq.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyperfoil.tools.jjq.value.*;
import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.info.GraphLayout;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Analyzes the memory layout of jjq value types using JOL (Java Object Layout).
 *
 * <p>Reports exact object sizes including headers, field alignment, padding,
 * and deep footprint of parsed documents. Compares jjq vs Jackson memory usage.</p>
 *
 * <h3>Running</h3>
 * <pre>
 *   # Build first
 *   mvn package -pl jjq-core,jjq-jackson,jjq-fastjson2,jjq-benchmark -q -DskipTests
 *
 *   # Run the analysis
 *   java --enable-preview -cp jjq-benchmark/target/jjq-benchmark-*.jar \
 *     io.hyperfoil.tools.jjq.benchmark.JqValueLayoutAnalysis
 *
 *   # Run with Compact Object Headers (JDK 25)
 *   java --enable-preview -XX:+UseCompactObjectHeaders \
 *     -cp jjq-benchmark/target/jjq-benchmark-*.jar \
 *     io.hyperfoil.tools.jjq.benchmark.JqValueLayoutAnalysis
 * </pre>
 */
public class JqValueLayoutAnalysis {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        System.out.println("JOL Java Object Layout Analysis for jjq Value Types");
        System.out.println("====================================================");
        System.out.println("JVM: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        System.out.println("Compact Object Headers: " + isCompactHeaders());
        System.out.println();

        // ================================================================
        // Section 1: Individual type layouts
        // ================================================================
        System.out.println("=== INDIVIDUAL TYPE LAYOUTS ===");
        System.out.println();

        printClassLayout(JqNull.class, "JqNull");
        printClassLayout(JqBoolean.class, "JqBoolean");
        printClassLayout(JqNumber.class, "JqNumber");
        printClassLayout(JqString.class, "JqString");
        printClassLayout(JqArray.class, "JqArray");
        printClassLayout(JqObject.class, "JqObject");

        // ================================================================
        // Section 2: Instance layouts for different JqString variants
        // ================================================================
        System.out.println("=== JqString INSTANCE VARIANTS ===");
        System.out.println();

        JqString eager = JqString.of("hello");
        System.out.println("JqString.of(\"hello\") -- eager:");
        System.out.println(ClassLayout.parseInstance(eager).toPrintable());

        // Parse a JSON string to get a deferred instance
        JqValue parsed = JqValues.parse("{\"key\":\"value\"}");
        if (parsed instanceof JqObject obj) {
            JqValue val = obj.get("key");
            if (val instanceof JqString s) {
                System.out.println("Deferred JqString from parser (string value):");
                System.out.println(ClassLayout.parseInstance(s).toPrintable());
            }
        }

        // ================================================================
        // Section 3: JqNumber variants
        // ================================================================
        System.out.println("=== JqNumber INSTANCE VARIANTS ===");
        System.out.println();

        System.out.println("JqNumber.of(42) -- cached long:");
        System.out.println(ClassLayout.parseInstance(JqNumber.of(42)).toPrintable());

        System.out.println("JqNumber.of(99999) -- uncached long:");
        System.out.println(ClassLayout.parseInstance(JqNumber.of(99999)).toPrintable());

        System.out.println("JqNumber.of(3.14) -- double-backed:");
        System.out.println(ClassLayout.parseInstance(JqNumber.of(3.14)).toPrintable());

        // ================================================================
        // Section 4: JqObject layout (parallel arrays)
        // ================================================================
        System.out.println("=== JqObject INSTANCE LAYOUT ===");
        System.out.println();

        JqValue simpleObj = JqValues.parse("{\"name\":\"Alice\",\"age\":30,\"a\":42}");
        System.out.println("JqObject with 3 fields (parallel arrays):");
        System.out.println(ClassLayout.parseInstance(simpleObj).toPrintable());

        // ================================================================
        // Section 5: Deep footprint comparison -- small document
        // ================================================================
        System.out.println("=== DEEP FOOTPRINT: Small Document (nested-1kb.json) ===");
        System.out.println();

        String smallJson = loadResource("benchmark-data/nested-1kb.json");

        JqValue jjqSmall = JqValues.parse(smallJson);
        System.out.println("jjq parsed (String path):");
        System.out.println(GraphLayout.parseInstance(jjqSmall).toFootprint());

        byte[] smallBytes = smallJson.getBytes(StandardCharsets.UTF_8);
        JqValue jjqSmallBytes = JqValues.parse(smallBytes);
        System.out.println("jjq parsed (byte[] path):");
        System.out.println(GraphLayout.parseInstance(jjqSmallBytes).toFootprint());

        JsonNode jacksonSmall = MAPPER.readTree(smallJson);
        System.out.println("Jackson parsed:");
        System.out.println(GraphLayout.parseInstance(jacksonSmall).toFootprint());

        // ================================================================
        // Section 6: Deep footprint comparison -- medium document
        // ================================================================
        System.out.println("=== DEEP FOOTPRINT: Medium Document (nested-10kb.json) ===");
        System.out.println();

        String mediumJson = loadResource("benchmark-data/nested-10kb.json");

        JqValue jjqMedium = JqValues.parse(mediumJson);
        System.out.println("jjq parsed (String path):");
        System.out.println(GraphLayout.parseInstance(jjqMedium).toFootprint());

        byte[] mediumBytes = mediumJson.getBytes(StandardCharsets.UTF_8);
        JqValue jjqMediumBytes = JqValues.parse(mediumBytes);
        System.out.println("jjq parsed (byte[] path):");
        System.out.println(GraphLayout.parseInstance(jjqMediumBytes).toFootprint());

        JsonNode jacksonMedium = MAPPER.readTree(mediumJson);
        System.out.println("Jackson parsed:");
        System.out.println(GraphLayout.parseInstance(jacksonMedium).toFootprint());

        // ================================================================
        // Section 7: Summary table
        // ================================================================
        System.out.println("=== SUMMARY ===");
        System.out.println();

        long jjqSmallSize = GraphLayout.parseInstance(jjqSmall).totalSize();
        long jjqSmallBytesSize = GraphLayout.parseInstance(jjqSmallBytes).totalSize();
        long jacksonSmallSize = GraphLayout.parseInstance(jacksonSmall).totalSize();

        long jjqMediumSize = GraphLayout.parseInstance(jjqMedium).totalSize();
        long jjqMediumBytesSize = GraphLayout.parseInstance(jjqMediumBytes).totalSize();
        long jacksonMediumSize = GraphLayout.parseInstance(jacksonMedium).totalSize();

        System.out.println("Document footprint comparison:");
        System.out.printf("  %-30s %12s %12s %12s%n", "", "jjq (String)", "jjq (byte[])", "Jackson");
        System.out.printf("  %-30s %12d %12d %12d%n", "nested-1kb.json", jjqSmallSize, jjqSmallBytesSize, jacksonSmallSize);
        System.out.printf("  %-30s %12d %12d %12d%n", "nested-10kb.json", jjqMediumSize, jjqMediumBytesSize, jacksonMediumSize);
        System.out.println();
        System.out.printf("  %-30s %11.0f%% %11.0f%% %12s%n", "jjq/Jackson (1kb)",
                jjqSmallSize * 100.0 / jacksonSmallSize,
                jjqSmallBytesSize * 100.0 / jacksonSmallSize, "baseline");
        System.out.printf("  %-30s %11.0f%% %11.0f%% %12s%n", "jjq/Jackson (10kb)",
                jjqMediumSize * 100.0 / jacksonMediumSize,
                jjqMediumBytesSize * 100.0 / jacksonMediumSize, "baseline");

        System.out.println();
        System.out.println("Individual type sizes (instance size in bytes):");
        System.out.printf("  JqNull:    %d%n", ClassLayout.parseClass(JqNull.class).instanceSize());
        System.out.printf("  JqBoolean: %d%n", ClassLayout.parseClass(JqBoolean.class).instanceSize());
        System.out.printf("  JqNumber:  %d%n", ClassLayout.parseClass(JqNumber.class).instanceSize());
        System.out.printf("  JqString:  %d%n", ClassLayout.parseClass(JqString.class).instanceSize());
        System.out.printf("  JqArray:   %d%n", ClassLayout.parseClass(JqArray.class).instanceSize());
        System.out.printf("  JqObject:  %d%n", ClassLayout.parseClass(JqObject.class).instanceSize());
    }

    private static void printClassLayout(Class<?> clazz, String label) {
        System.out.println(label + " (instance size: " + ClassLayout.parseClass(clazz).instanceSize() + " bytes):");
        System.out.println(ClassLayout.parseClass(clazz).toPrintable());
    }

    private static String loadResource(String name) throws IOException {
        try (InputStream is = JqValueLayoutAnalysis.class.getClassLoader().getResourceAsStream(name)) {
            if (is == null) throw new IOException("Resource not found: " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String isCompactHeaders() {
        try {
            // Check if compact headers are enabled via ManagementFactory
            var mxBean = java.lang.management.ManagementFactory.getRuntimeMXBean();
            return mxBean.getInputArguments().stream()
                    .filter(a -> a.contains("CompactObjectHeaders"))
                    .findFirst()
                    .orElse("not specified (using JVM default)");
        } catch (Exception e) {
            return "unknown";
        }
    }
}

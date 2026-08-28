package io.hyperfoil.tools.jjq.benchmark;

import io.hyperfoil.tools.jjq.mapper.JqMapped;

/**
 * Records with varying field counts for register spilling investigation.
 * All fields are String to maximize inlined asString() calls — the worst
 * case for register pressure on x86_64.
 *
 * <p>Three variants per size:</p>
 * <ul>
 *   <li>{@code Gen*} — annotated with {@code @JqMapped}, uses generated mapping</li>
 *   <li>{@code Refl*} — no annotation, uses reflection-based mapping</li>
 *   <li>Jackson uses the {@code Gen*} records directly (Jackson ignores {@code @JqMapped})</li>
 * </ul>
 */
public final class FieldCountRecords {
    private FieldCountRecords() {}

    // ========================================================================
    //  5 fields — baseline
    // ========================================================================

    @JqMapped
    public record Gen5(String f0, String f1, String f2, String f3, String f4) {}

    public record Refl5(String f0, String f1, String f2, String f3, String f4) {}

    // ========================================================================
    //  10 fields
    // ========================================================================

    @JqMapped
    public record Gen10(
            String f0, String f1, String f2, String f3, String f4,
            String f5, String f6, String f7, String f8, String f9) {}

    public record Refl10(
            String f0, String f1, String f2, String f3, String f4,
            String f5, String f6, String f7, String f8, String f9) {}

    // ========================================================================
    //  20 fields
    // ========================================================================

    @JqMapped
    public record Gen20(
            String f0, String f1, String f2, String f3, String f4,
            String f5, String f6, String f7, String f8, String f9,
            String f10, String f11, String f12, String f13, String f14,
            String f15, String f16, String f17, String f18, String f19) {}

    public record Refl20(
            String f0, String f1, String f2, String f3, String f4,
            String f5, String f6, String f7, String f8, String f9,
            String f10, String f11, String f12, String f13, String f14,
            String f15, String f16, String f17, String f18, String f19) {}

    // ========================================================================
    //  JSON generation helper
    // ========================================================================

    /**
     * Generate a JSON object string with N fields: {"f0":"v0","f1":"v1",...}
     */
    public static String generateJson(int fieldCount) {
        var sb = new StringBuilder("{");
        for (int i = 0; i < fieldCount; i++) {
            if (i > 0) sb.append(',');
            sb.append("\"f").append(i).append("\":\"v").append(i).append("\"");
        }
        sb.append('}');
        return sb.toString();
    }
}

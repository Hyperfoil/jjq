package io.hyperfoil.tools.jjq.schema;

import io.hyperfoil.tools.jjq.value.JqNumber;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Compiled string rules: code-point lengths plus {@code pattern}.
 * Lengths count Unicode code points, not UTF-16 units. Patterns are
 * compiled once and matched unanchored (search semantics); callers need
 * the pattern object itself only at compile time.
 *
 * <p>Java regex is not ECMA-262: unsupported constructs fail compilation
 * rather than silently diverging, and adversarial patterns are a trust
 * boundary (a node budget cannot interrupt one catastrophic match).</p>
 */
final class StringPlan {

    private final JqNumber minLength;
    private final JqNumber maxLength;
    private final Pattern pattern;
    private final String patternSource;

    StringPlan(JqNumber minLength, JqNumber maxLength, Pattern pattern, String patternSource) {
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.pattern = pattern;
        this.patternSource = patternSource;
    }

    boolean isValid(JqString instance) {
        String value = instance.stringValue();
        if (minLength != null || maxLength != null) {
            long length = value.codePointCount(0, value.length());
            if (minLength != null
                    && JsonSemantics.compareNumbers(JqNumber.of(length), minLength) < 0) {
                return false;
            }
            if (maxLength != null
                    && JsonSemantics.compareNumbers(JqNumber.of(length), maxLength) > 0) {
                return false;
            }
        }
        return pattern == null || pattern.matcher(value).find();
    }

    void validateInto(JqString instance, String instanceLocation, String schemaLocation,
            List<JqValue> errors) {
        String value = instance.stringValue();
        if (minLength != null || maxLength != null) {
            long length = value.codePointCount(0, value.length());
            if (minLength != null
                    && JsonSemantics.compareNumbers(JqNumber.of(length), minLength) < 0) {
                errors.add(Failures.failure(instanceLocation, schemaLocation, "minLength",
                        "string is shorter than minLength"));
            }
            if (maxLength != null
                    && JsonSemantics.compareNumbers(JqNumber.of(length), maxLength) > 0) {
                errors.add(Failures.failure(instanceLocation, schemaLocation, "maxLength",
                        "string is longer than maxLength"));
            }
        }
        if (pattern != null && !pattern.matcher(value).find()) {
            errors.add(Failures.failure(instanceLocation, schemaLocation, "pattern",
                    "string does not match pattern '" + patternSource + "'"));
        }
    }
}

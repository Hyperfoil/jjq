package io.hyperfoil.tools.jjq.diff;

import io.hyperfoil.tools.jjq.value.JqValue;
import java.util.Objects;

/**
 * One reported difference between two documents. Paths are jq-style
 * ({@code "$.a.b[0]"}); they locate the value in the target document,
 * except removals and move sources, which locate it in the source.
 *
 * @param kind     what happened
 * @param path     where (see above for which document)
 * @param fromPath move source, or null for every other kind
 * @param oldValue source value, or null when added
 * @param newValue target value, or null when removed
 */
public record JqDiffEntry(Kind kind, String path, String fromPath,
                          JqValue oldValue, JqValue newValue) {

    /** The shape of a difference. */
    public enum Kind { ADDED, REMOVED, REPLACED, MOVED }

    public JqDiffEntry {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(path, "path must not be null");
        if ((fromPath != null) != (kind == Kind.MOVED)) {
            throw new IllegalArgumentException("fromPath is required exactly for MOVED");
        }
        if ((oldValue == null) != (kind == Kind.ADDED)) {
            throw new IllegalArgumentException("oldValue is absent exactly for ADDED");
        }
        if ((newValue == null) != (kind == Kind.REMOVED)) {
            throw new IllegalArgumentException("newValue is absent exactly for REMOVED");
        }
    }
}

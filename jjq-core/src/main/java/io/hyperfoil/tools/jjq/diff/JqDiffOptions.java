package io.hyperfoil.tools.jjq.diff;

import java.util.Objects;
import java.util.Set;

/**
 * Leniency options for {@link JqDiff#compare} / {@link JqDiff#render}.
 * Strict {@link JqDiff#diff} takes no options: a filtered patch could
 * never reconstruct the target, so options must not reach it.
 *
 * @param ignorePaths subtrees to skip, as jq-style paths
 *                    ({@code "$.a.b[0]"}, {@code "$"} for the root).
 *                    A skipped subtree reports nothing, however much
 *                    it differs; its descendants are never visited.
 */
public record JqDiffOptions(Set<String> ignorePaths) {

    /** Strict comparison: nothing ignored. */
    public static final JqDiffOptions EMPTY = new JqDiffOptions(Set.of());

    public JqDiffOptions {
        Objects.requireNonNull(ignorePaths, "ignorePaths must not be null");
        ignorePaths = Set.copyOf(ignorePaths);
    }

    /**
     * Return options ignoring one more subtree.
     *
     * @param path the subtree root, e.g. {@code "$.meta.timestamp"}
     * @return new options with {@code path} added
     */
    public JqDiffOptions withIgnorePath(String path) {
        Objects.requireNonNull(path, "path must not be null");
        var paths = new java.util.HashSet<>(ignorePaths);
        paths.add(path);
        return new JqDiffOptions(paths);
    }
}

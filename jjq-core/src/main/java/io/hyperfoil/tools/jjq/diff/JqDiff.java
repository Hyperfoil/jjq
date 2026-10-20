package io.hyperfoil.tools.jjq.diff;

import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Structural diff over {@link JqValue} (issue #168): strict RFC 6902
 * patches plus human-readable comparison reports.
 *
 * <p>The one invariant, unconditional: {@code apply(source,
 * diff(source, target))} is exactly {@code target}. Key order is
 * insignificant, array order is significant, {@code null} and missing
 * are distinct, and numbers compare mathematically ({@code 1} equals
 * {@code 1.0}).</p>
 *
 * <p>Leniency lives only in {@link #compare} / {@link #render} via
 * {@link JqDiffOptions} (ignored subtrees): a filtered comparison can
 * never produce a patch that reconstructs the target, so options must
 * not reach {@link #diff}. Report paths locate values in the target
 * document (source document for removals and move sources).</p>
 */
public final class JqDiff {

    /**
     * Middle regions larger than this (elements on both sides combined)
     * skip alignment: diff replaces the whole array, comparison reports
     * a single wholesale change. Bounds the alignment's quadratic worst
     * case; prefix/suffix trimming still applies.
     */
    static final int ALIGN_LIMIT = 1024;

    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private JqDiff() {}

    /**
     * Compute the RFC 6902 patch transforming {@code source} into
     * {@code target}.
     *
     * @param source the document to transform (never modified)
     * @param target the desired document
     * @return the patch as a {@link JqArray} of operation objects
     */
    public static JqArray diff(JqValue source, JqValue target) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(target, "target must not be null");
        List<JqValue> ops = new ArrayList<>();
        diffInto(source, target, "", ops);
        return JqArray.of(ops);
    }

    /**
     * Compare two documents, reporting every difference.
     *
     * @param source the base document
     * @param target the document to compare against
     * @return the differences, empty when exactly equal
     */
    public static List<JqDiffEntry> compare(JqValue source, JqValue target) {
        return compare(source, target, JqDiffOptions.EMPTY);
    }

    /**
     * Compare two documents with leniency options.
     *
     * @param source the base document
     * @param target the document to compare against
     * @param options ignored subtrees (jq-style paths)
     * @return the differences outside ignored subtrees
     */
    public static List<JqDiffEntry> compare(JqValue source, JqValue target,
            JqDiffOptions options) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(options, "options must not be null");
        List<JqDiffEntry> out = new ArrayList<>();
        compareInto(source, target, "$", options, out);
        return out;
    }

    /**
     * Render the differences between two documents as human-readable
     * text: {@code ~ path: old -> new}, {@code + path: value},
     * {@code - path: value}, {@code > from -> to: value}. Empty when equal.
     *
     * @param source the base document
     * @param target the document to compare against
     * @return the report, lines joined without a trailing newline
     */
    public static String render(JqValue source, JqValue target) {
        return render(source, target, JqDiffOptions.EMPTY);
    }

    /**
     * Render the differences with leniency options.
     *
     * @param source the base document
     * @param target the document to compare against
     * @param options ignored subtrees (jq-style paths)
     * @return the report, lines joined without a trailing newline
     */
    public static String render(JqValue source, JqValue target, JqDiffOptions options) {
        StringBuilder sb = new StringBuilder();
        for (JqDiffEntry entry : compare(source, target, options)) {
            if (!sb.isEmpty()) sb.append('\n');
            switch (entry.kind()) {
                case ADDED -> sb.append("+ ").append(entry.path()).append(": ")
                        .append(entry.newValue().toJsonString());
                case REMOVED -> sb.append("- ").append(entry.path()).append(": ")
                        .append(entry.oldValue().toJsonString());
                case REPLACED -> sb.append("~ ").append(entry.path()).append(": ")
                        .append(entry.oldValue().toJsonString())
                        .append(" -> ").append(entry.newValue().toJsonString());
                case MOVED -> sb.append("> ").append(entry.fromPath()).append(" -> ")
                        .append(entry.path()).append(": ")
                        .append(entry.oldValue().toJsonString());
            }
        }
        return sb.toString();
    }

    static void diffInto(JqValue source, JqValue target, String path, List<JqValue> ops) {
        if (Exact.equal(source, target)) return;
        if (source instanceof JqObject a && target instanceof JqObject b) {
            diffObject(a, b, path, ops);
            return;
        }
        if (source instanceof JqArray a && target instanceof JqArray b) {
            diffArray(a, b, path, ops);
            return;
        }
        ops.add(replaceOp(path, target));
    }

    private static void diffObject(JqObject source, JqObject target, String path,
            List<JqValue> ops) {
        for (String key : source.keys()) {
            String child = path + "/" + escape(key);
            if (!target.has(key)) {
                ops.add(removeOp(child));
            } else {
                diffInto(source.get(key), target.get(key), child, ops);
            }
        }
        for (String key : target.keys()) {
            if (!source.has(key)) {
                ops.add(addOp(path + "/" + escape(key), target.get(key)));
            }
        }
    }

    private static void diffArray(JqArray source, JqArray target, String path,
            List<JqValue> ops) {
        ArrayAligner.Span span = ArrayAligner.trim(source, target);
        int middle = span.aEnd() - span.aStart() + 1 + (span.bEnd() - span.bStart() + 1);
        if (middle <= 0) return;
        if (middle > ALIGN_LIMIT) {
            ops.add(replaceOp(path, target));
            return;
        }
        for (ArrayAligner.Edit edit : ArrayAligner.align(source, span, target)) {
            String live = path + "/" + (span.aStart() + edit.live());
            switch (edit.kind()) {
                case REMOVE -> ops.add(removeOp(live));
                case ADD -> ops.add(addOp(live, edit.valB()));
                case MOVE -> ops.add(moveOp(path + "/" + (span.aStart() + edit.fromLive()), live));
                case RECURSE -> diffInto(edit.valA(), edit.valB(), live, ops);
                case REPLACE -> ops.add(replaceOp(live, edit.valB()));
            }
        }
    }

    private static void compareInto(JqValue source, JqValue target, String path,
            JqDiffOptions options, List<JqDiffEntry> out) {
        if (options.ignorePaths().contains(path)) return;
        if (Exact.equal(source, target)) return;
        if (source instanceof JqObject a && target instanceof JqObject b) {
            for (String key : a.keys()) {
                String child = fieldPath(path, key);
                if (!b.has(key)) {
                    if (!options.ignorePaths().contains(child)) {
                        out.add(new JqDiffEntry(JqDiffEntry.Kind.REMOVED, child, null,
                                a.get(key), null));
                    }
                } else {
                    compareInto(a.get(key), b.get(key), child, options, out);
                }
            }
            for (String key : b.keys()) {
                if (!a.has(key)) {
                    String child = fieldPath(path, key);
                    if (!options.ignorePaths().contains(child)) {
                        out.add(new JqDiffEntry(JqDiffEntry.Kind.ADDED, child, null,
                                null, b.get(key)));
                    }
                }
            }
            return;
        }
        if (source instanceof JqArray a && target instanceof JqArray b) {
            compareArray(a, b, path, options, out);
            return;
        }
        out.add(new JqDiffEntry(JqDiffEntry.Kind.REPLACED, path, null, source, target));
    }

    private static void compareArray(JqArray source, JqArray target, String path,
            JqDiffOptions options, List<JqDiffEntry> out) {
        ArrayAligner.Span span = ArrayAligner.trim(source, target);
        int middle = span.aEnd() - span.aStart() + 1 + (span.bEnd() - span.bStart() + 1);
        if (middle <= 0) return;
        if (middle > ALIGN_LIMIT) {
            out.add(new JqDiffEntry(JqDiffEntry.Kind.REPLACED, path, null, source, target));
            return;
        }
        for (ArrayAligner.Edit edit : ArrayAligner.align(source, span, target)) {
            switch (edit.kind()) {
                case REMOVE -> {
                    String child = path + "[" + edit.origA() + "]";
                    if (!options.ignorePaths().contains(child)) {
                        out.add(new JqDiffEntry(JqDiffEntry.Kind.REMOVED, child, null,
                                edit.valA(), null));
                    }
                }
                case ADD -> {
                    String child = path + "[" + edit.bIdx() + "]";
                    if (!options.ignorePaths().contains(child)) {
                        out.add(new JqDiffEntry(JqDiffEntry.Kind.ADDED, child, null,
                                null, edit.valB()));
                    }
                }
                case MOVE -> {
                    String child = path + "[" + edit.bIdx() + "]";
                    if (!options.ignorePaths().contains(child)) {
                        out.add(new JqDiffEntry(JqDiffEntry.Kind.MOVED, child,
                                path + "[" + edit.origA() + "]", edit.valB(), edit.valB()));
                    }
                }
                case REPLACE -> {
                    String child = path + "[" + edit.bIdx() + "]";
                    if (!options.ignorePaths().contains(child)) {
                        out.add(new JqDiffEntry(JqDiffEntry.Kind.REPLACED, child, null,
                                edit.valA(), edit.valB()));
                    }
                }
                case RECURSE -> compareInto(edit.valA(), edit.valB(),
                        path + "[" + edit.bIdx() + "]", options, out);
            }
        }
    }

    /** Display path segment for an object key from a base path. */
    static String fieldPath(String base, String key) {
        if (IDENT.matcher(key).matches()) return base + "." + key;
        return base + "[\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
    }

    /** Escape a reference token ({@code ~} then {@code /}). */
    static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    static JqValue addOp(String path, JqValue value) {
        return JqObject.builder(3)
                .putUnchecked("op", JqString.of("add"))
                .putUnchecked("path", JqString.of(path))
                .putUnchecked("value", value)
                .build();
    }

    static JqValue removeOp(String path) {
        return JqObject.builder(2)
                .putUnchecked("op", JqString.of("remove"))
                .putUnchecked("path", JqString.of(path))
                .build();
    }

    static JqValue replaceOp(String path, JqValue value) {
        return JqObject.builder(3)
                .putUnchecked("op", JqString.of("replace"))
                .putUnchecked("path", JqString.of(path))
                .putUnchecked("value", value)
                .build();
    }

    static JqValue moveOp(String from, String path) {
        return JqObject.builder(3)
                .putUnchecked("op", JqString.of("move"))
                .putUnchecked("from", JqString.of(from))
                .putUnchecked("path", JqString.of(path))
                .build();
    }
}

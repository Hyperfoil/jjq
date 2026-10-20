package io.hyperfoil.tools.jjq.diff;

import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Strict structural diff over {@link JqValue} producing RFC 6902 JSON
 * Patch documents (issue #168).
 *
 * <p>The one invariant: {@code apply(source, diff(source, target))}
 * is exactly {@code target}. Key order is insignificant, array order
 * is significant, {@code null} and missing are distinct, and numbers
 * compare mathematically ({@code 1} equals {@code 1.0}).</p>
 *
 * <p>Arrays align by trimming equal prefixes/suffixes, then a greedy
 * walk over a live copy: equal advances, pull-forward moves, nested
 * recursion for same-type containers, insertion when the current
 * element is still needed later, replacement, removal. Every emitted
 * index is computed against live state, so validity holds by
 * construction; termination is structural. Regions over
 * {@value #ALIGN_LIMIT} total elements fall back to whole-array
 * replacement, and minimality is not promised.</p>
 */
public final class JqDiff {

    /**
     * Middle regions larger than this (elements on both sides combined)
     * skip alignment and replace the whole array. Bounds the greedy
     * walk's quadratic worst case; prefix/suffix trimming still applies.
     */
    static final int ALIGN_LIMIT = 1024;

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
        int n = source.size();
        int m = target.size();
        int start = 0;
        while (start < n && start < m
                && Exact.equal(source.get(start), target.get(start))) {
            start++;
        }
        int aEnd = n - 1;
        int bEnd = m - 1;
        while (aEnd >= start && bEnd >= start
                && Exact.equal(source.get(aEnd), target.get(bEnd))) {
            aEnd--;
            bEnd--;
        }
        if (start > aEnd && start > bEnd) return;
        if (aEnd - start + 1 + (bEnd - start + 1) > ALIGN_LIMIT) {
            ops.add(replaceOp(path, target));
            return;
        }
        // Live copy; every emitted index is live.
        List<JqValue> cur = new ArrayList<>(aEnd - start + 1);
        for (int i = start; i <= aEnd; i++) {
            cur.add(source.get(i));
        }
        int i = 0;
        int j = start;
        while (j <= bEnd) {
            if (i < cur.size() && Exact.equal(cur.get(i), target.get(j))) {
                i++;
                j++;
                continue;
            }
            int k = -1;
            if (i < cur.size()) {
                for (int t = i + 1; t < cur.size(); t++) {
                    if (Exact.equal(cur.get(t), target.get(j))) {
                        k = t;
                        break;
                    }
                }
            }
            if (k >= 0) {
                ops.add(moveOp(path + "/" + (start + k), path + "/" + (start + i)));
                JqValue moved = cur.remove(k);
                cur.add(i, moved);
                i++;
                j++;
                continue;
            }
            if (i < cur.size() && sameContainer(cur.get(i), target.get(j))) {
                diffInto(cur.get(i), target.get(j), path + "/" + (start + i), ops);
                cur.set(i, target.get(j));
                i++;
                j++;
                continue;
            }
            if (i < cur.size() && neededLater(cur.get(i), target, j + 1, bEnd)) {
                ops.add(addOp(path + "/" + (start + i), target.get(j)));
                cur.add(i, target.get(j));
                i++;
                j++;
                continue;
            }
            if (i < cur.size()) {
                if (target.get(j).isScalar() || cur.get(i).isScalar()
                        || target.get(j).type() != cur.get(i).type()) {
                    ops.add(replaceOp(path + "/" + (start + i), target.get(j)));
                    cur.set(i, target.get(j));
                    i++;
                    j++;
                    continue;
                }
                ops.add(removeOp(path + "/" + (start + i)));
                cur.remove(i);
                continue;
            }
            ops.add(addOp(path + "/" + (start + i), target.get(j)));
            cur.add(target.get(j));
            i++;
            j++;
        }
        while (cur.size() > bEnd - start + 1) {
            ops.add(removeOp(path + "/" + (start + cur.size() - 1)));
            cur.remove(cur.size() - 1);
        }
    }

    private static boolean sameContainer(JqValue a, JqValue b) {
        return a instanceof JqObject && b instanceof JqObject
                || a instanceof JqArray && b instanceof JqArray;
    }

    private static boolean neededLater(JqValue value, JqArray target, int from, int to) {
        for (int l = from; l <= to; l++) {
            if (Exact.equal(value, target.get(l))) return true;
        }
        return false;
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

package io.hyperfoil.tools.jjq.diff;

import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared array alignment for diff and comparison (issue #168).
 *
 * <p>Trims equal prefixes/suffixes, then greedily walks a live copy:
 * equal advances, pull-forward moves, nested recursion for same-type
 * containers, insertion when the current element is still needed later,
 * replacement, removal. Every decision is recorded with both the live
 * position (for patch emission) and the original indices (for reports),
 * so both consumers share one algorithm instead of drifting apart.</p>
 */
final class ArrayAligner {

    enum Kind { REMOVE, ADD, MOVE, RECURSE, REPLACE }

    /**
     * One alignment decision. {@code live} is the cur-relative position
     * ({@code fromLive} the move source); {@code origA} is the original
     * source index or -1; {@code bIdx} the target index or -1.
     */
    record Edit(Kind kind, int live, int fromLive, int origA, int bIdx,
                JqValue valA, JqValue valB) {}

    /** Trimmed middle region, half-open on both sides. */
    record Span(int aStart, int aEnd, int bStart, int bEnd) {}

    private ArrayAligner() {}

    /** Equal prefix/suffix need no entries in either consumer. */
    static Span trim(JqArray source, JqArray target) {
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
        return new Span(start, aEnd, start, bEnd);
    }

    static List<Edit> align(JqArray source, Span span, JqArray target) {
        List<Edit> edits = new ArrayList<>();
        List<JqValue> cur = new ArrayList<>();
        List<Integer> orig = new ArrayList<>();
        for (int i = span.aStart(); i <= span.aEnd(); i++) {
            cur.add(source.get(i));
            orig.add(i);
        }
        int i = 0;
        int j = span.bStart();
        while (j <= span.bEnd()) {
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
                edits.add(new Edit(Kind.MOVE, i, k, orig.remove(k), j,
                        cur.remove(k), target.get(j)));
                cur.add(i, target.get(j));
                orig.add(i, -1);
                i++;
                j++;
                continue;
            }
            if (i < cur.size() && sameContainer(cur.get(i), target.get(j))) {
                edits.add(new Edit(Kind.RECURSE, i, -1, orig.get(i), j,
                        cur.get(i), target.get(j)));
                cur.set(i, target.get(j));
                i++;
                j++;
                continue;
            }
            if (i < cur.size() && neededLater(cur.get(i), target, j + 1, span.bEnd())) {
                edits.add(new Edit(Kind.ADD, i, -1, -1, j, null, target.get(j)));
                cur.add(i, target.get(j));
                orig.add(i, -1);
                i++;
                j++;
                continue;
            }
            if (i < cur.size()) {
                if (target.get(j).isScalar() || cur.get(i).isScalar()
                        || target.get(j).type() != cur.get(i).type()) {
                    edits.add(new Edit(Kind.REPLACE, i, -1, orig.get(i), j,
                            cur.get(i), target.get(j)));
                    cur.set(i, target.get(j));
                    i++;
                    j++;
                    continue;
                }
                edits.add(new Edit(Kind.REMOVE, i, -1, orig.remove(i), -1, cur.remove(i), null));
                continue;
            }
            edits.add(new Edit(Kind.ADD, i, -1, -1, j, null, target.get(j)));
            cur.add(target.get(j));
            orig.add(-1);
            i++;
            j++;
        }
        while (cur.size() > span.bEnd() - span.bStart() + 1) {
            int live = cur.size() - 1;
            edits.add(new Edit(Kind.REMOVE, live, -1, orig.remove(live), -1,
                    cur.remove(live), null));
        }
        return edits;
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
}

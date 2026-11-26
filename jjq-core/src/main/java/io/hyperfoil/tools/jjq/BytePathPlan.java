package io.hyperfoil.tools.jjq;

import io.hyperfoil.tools.jjq.ast.JqExpr;
import io.hyperfoil.tools.jjq.value.JqNumber;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Compile-time path plan for on-demand {@link JqProgram#apply(byte[])}
 * extraction (issue #211).
 *
 * <p>Recognizes four dominant query shapes from the immutable AST once at
 * {@link JqProgram} construction time:</p>
 * <ul>
 *   <li>{@link Kind#CHAIN}: chained field accesses and non-negative array
 *       indices (e.g. {@code .user}, {@code .a.b[0].c})</li>
 *   <li>{@link Kind#COLLECT_FIELD}: array iteration collecting a field or
 *       chain per element (e.g. {@code [.pcp[] | .["mem.util.used"]]},
 *       {@code [.results[].load.avThroughput]})</li>
 *   <li>{@link Kind#OBJECT_CONSTRUCT}: optional prefix path followed by a
 *       flat object projection of distinct output keys sourced from single
 *       field lookups (e.g. {@code {user, uuid}}, {@code .cfg | {a, b}})</li>
 * </ul>
 * <p>Returns {@code null} from {@link #compile(JqExpr)} for any expression
 * outside these shapes or containing escapable characters in a key name,
 * causing {@code apply(byte[])} to fall back cleanly to full parsing.</p>
 */
final class BytePathPlan {

    private static final byte[][] EMPTY_FIELDS = new byte[0][];
    private static final int[] EMPTY_INDICES = new int[0];

    enum Kind { CHAIN, COLLECT_FIELD, OBJECT_CONSTRUCT }

    final Kind kind;
    // Primary steps (for CHAIN) or prefix steps (for COLLECT_FIELD / OBJECT_CONSTRUCT)
    final byte[][] fields;
    final int[] indices;
    // Tail steps for COLLECT_FIELD
    final byte[][] tailFields;
    final int[] tailIndices;
    // Output layout and source wire keys for OBJECT_CONSTRUCT
    final String[] outKeys;
    final byte[][] srcFields;

    private BytePathPlan(Kind kind, byte[][] fields, int[] indices,
                         byte[][] tailFields, int[] tailIndices,
                         String[] outKeys, byte[][] srcFields) {
        this.kind = kind;
        this.fields = fields;
        this.indices = indices;
        this.tailFields = tailFields;
        this.tailIndices = tailIndices;
        this.outKeys = outKeys;
        this.srcFields = srcFields;
    }

    /** Execute this plan on raw UTF-8 JSON bytes, or return {@code null} to fall back. */
    JqValue execute(byte[] bytes, int offset, int length) {
        return switch (kind) {
            case CHAIN -> JqValues.extractPath(bytes, offset, length, fields, indices);
            case COLLECT_FIELD -> JqValues.extractCollectField(bytes, offset, length,
                    fields, indices, tailFields, tailIndices);
            case OBJECT_CONSTRUCT -> JqValues.extractObjectFields(bytes, offset, length,
                    fields, indices, outKeys, srcFields);
        };
    }

    static BytePathPlan compile(JqExpr ast) {
        // 1. Try plain field/index chain: .user, .a.b[0].c
        StepList chain = new StepList();
        if (extractChain(ast, chain) && !chain.isEmpty()) {
            return new BytePathPlan(Kind.CHAIN, chain.toFields(), chain.toIndices(),
                    null, null, null, null);
        }

        // 2. Try collect-field: [.prefix[] | .tail] / [.prefix[].tail]
        if (ast instanceof JqExpr.ArrayConstructExpr arr && arr.body() != null) {
            BytePathPlan collect = tryCompileCollectField(arr.body());
            if (collect != null) return collect;
        }

        // 3. Try flat object projection: {a, b} or .prefix | {a, b}
        return tryCompileObjectConstruct(ast);
    }

    private static BytePathPlan tryCompileCollectField(JqExpr body) {
        // Walk left-associative PipeExpr chain to locate the single IterateExpr:
        //   PipeExpr(PipeExpr(IterateExpr(prefix), step1), step2)
        // Also handle IndexExpr(IterateExpr(prefix), LiteralExpr(JqString))
        List<JqExpr> rightParts = new ArrayList<>();
        JqExpr cur = body;
        while (cur instanceof JqExpr.PipeExpr p) {
            rightParts.add(p.right());
            cur = p.left();
        }
        // Unwrap trailing IndexExpr(base, string/int) wrapping IterateExpr
        while (cur instanceof JqExpr.IndexExpr idx && idx.expr() != null) {
            rightParts.add(new JqExpr.IndexExpr(new JqExpr.IdentityExpr(), idx.index(), idx.location()));
            cur = idx.expr();
        }
        if (!(cur instanceof JqExpr.IterateExpr iter)) {
            return null;
        }
        if (rightParts.isEmpty()) {
            return null;
        }

        StepList prefix = new StepList();
        if (iter.expr() != null && !(iter.expr() instanceof JqExpr.IdentityExpr)) {
            if (!extractChain(iter.expr(), prefix)) return null;
        }

        StepList tail = new StepList();
        for (int i = rightParts.size() - 1; i >= 0; i--) {
            if (!extractChain(rightParts.get(i), tail)) return null;
        }
        if (tail.isEmpty()) return null;

        return new BytePathPlan(Kind.COLLECT_FIELD,
                prefix.isEmpty() ? EMPTY_FIELDS : prefix.toFields(),
                prefix.isEmpty() ? EMPTY_INDICES : prefix.toIndices(),
                tail.toFields(), tail.toIndices(), null, null);
    }

    private static BytePathPlan tryCompileObjectConstruct(JqExpr ast) {
        JqExpr prefixExpr = null;
        JqExpr objExpr = ast;
        if (ast instanceof JqExpr.PipeExpr pipe && pipe.right() instanceof JqExpr.ObjectConstructExpr) {
            prefixExpr = pipe.left();
            objExpr = pipe.right();
        }
        if (!(objExpr instanceof JqExpr.ObjectConstructExpr obj) || obj.entries().isEmpty()) {
            return null;
        }

        StepList prefix = new StepList();
        if (prefixExpr != null && !(prefixExpr instanceof JqExpr.IdentityExpr)) {
            if (!extractChain(prefixExpr, prefix)) return null;
        }

        int n = obj.entries().size();
        String[] outKeys = new String[n];
        byte[][] srcFields = new byte[n][];
        var seenOut = new HashSet<String>(n * 2);
        for (int i = 0; i < n; i++) {
            var entry = obj.entries().get(i);
            if (!(entry.key() instanceof JqExpr.LiteralExpr keyLit && keyLit.value() instanceof JqString keyStr)) {
                return null;
            }
            String outKey = JqValues.internFieldName(keyStr.stringValue());
            if (!seenOut.add(outKey)) {
                return null; // duplicate output key: fall back to full VM semantics
            }
            String srcField = extractSingleFieldName(entry.value());
            if (srcField == null) return null;
            byte[] srcBytes = JqValues.cleanUtf8Key(srcField);
            if (srcBytes == null) return null;
            outKeys[i] = outKey;
            srcFields[i] = srcBytes;
        }

        return new BytePathPlan(Kind.OBJECT_CONSTRUCT,
                prefix.isEmpty() ? EMPTY_FIELDS : prefix.toFields(),
                prefix.isEmpty() ? EMPTY_INDICES : prefix.toIndices(),
                null, null, outKeys, srcFields);
    }

    private static String extractSingleFieldName(JqExpr expr) {
        if (expr instanceof JqExpr.DotFieldExpr df) {
            return df.field();
        }
        if (expr instanceof JqExpr.IndexExpr idx
                && (idx.expr() == null || idx.expr() instanceof JqExpr.IdentityExpr)
                && idx.index() instanceof JqExpr.LiteralExpr lit
                && lit.value() instanceof JqString s) {
            return s.stringValue();
        }
        return null;
    }

    private static boolean extractChain(JqExpr expr, StepList out) {
        if (expr instanceof JqExpr.IdentityExpr) {
            return true;
        }
        if (expr instanceof JqExpr.DotFieldExpr df) {
            byte[] clean = JqValues.cleanUtf8Key(df.field());
            if (clean == null) return false;
            out.addField(clean);
            return true;
        }
        if (expr instanceof JqExpr.PipeExpr pipe) {
            return extractChain(pipe.left(), out) && extractChain(pipe.right(), out);
        }
        if (expr instanceof JqExpr.IndexExpr idx) {
            if (idx.expr() != null && !(idx.expr() instanceof JqExpr.IdentityExpr)) {
                if (!extractChain(idx.expr(), out)) return false;
            }
            if (idx.index() instanceof JqExpr.LiteralExpr lit) {
                JqValue v = lit.value();
                if (v instanceof JqString s) {
                    byte[] clean = JqValues.cleanUtf8Key(s.stringValue());
                    if (clean == null) return false;
                    out.addField(clean);
                    return true;
                }
                if (v instanceof JqNumber num && num.isLongBacked()) {
                    long l = num.longValue();
                    if (l >= 0 && l <= Integer.MAX_VALUE) {
                        out.addIndex((int) l);
                        return true;
                    }
                }
            }
            return false;
        }
        return false;
    }

    private static final class StepList {
        private final List<byte[]> fields = new ArrayList<>();
        private final List<Integer> indices = new ArrayList<>();

        void addField(byte[] field) {
            fields.add(field);
            indices.add(-1);
        }

        void addIndex(int index) {
            fields.add(null);
            indices.add(index);
        }

        boolean isEmpty() {
            return fields.isEmpty();
        }

        byte[][] toFields() {
            return fields.toArray(new byte[0][]);
        }

        int[] toIndices() {
            int[] arr = new int[indices.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = indices.get(i);
            return arr;
        }
    }
}

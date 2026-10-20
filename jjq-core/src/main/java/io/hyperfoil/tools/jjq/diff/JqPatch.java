package io.hyperfoil.tools.jjq.diff;

import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqBoolean;
import io.hyperfoil.tools.jjq.value.JqNull;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * RFC 6902 patch application over {@link JqValue} (issue #168).
 *
 * <p>All six operations are supported, including {@code test} and
 * {@code copy}, so patches from any implementation apply — not just
 * ones {@link JqDiff} produced. Operations apply sequentially; values
 * are immutable, so a throwing apply leaves the original untouched
 * (atomic by construction, no rollback needed).</p>
 *
 * <p>Strict where the RFC is strict: missing parents are errors (unlike
 * jq {@code setpath}, nothing is created), array indices are plain
 * digits without leading zeros, {@code -} appends only for {@code add},
 * and a location never moves into its own child.</p>
 */
public final class JqPatch {

    private JqPatch() {}

    /**
     * Apply a patch document to a document.
     *
     * @param document the target document (never modified)
     * @param patch    the patch as a {@link JqValue} (must be an array)
     * @return the patched document
     * @throws JqPatchException on any malformed operation or failed application
     */
    public static JqValue apply(JqValue document, JqValue patch) {
        Objects.requireNonNull(document, "document must not be null");
        Objects.requireNonNull(patch, "patch must not be null");
        if (!(patch instanceof JqArray arr)) {
            throw new JqPatchException(-1, "patch must be an array");
        }
        return apply(document, arr);
    }

    /**
     * Apply a patch document to a document.
     *
     * @param document the target document (never modified)
     * @param patch    the patch operations
     * @return the patched document
     * @throws JqPatchException on any malformed operation or failed application
     */
    public static JqValue apply(JqValue document, JqArray patch) {
        Objects.requireNonNull(document, "document must not be null");
        Objects.requireNonNull(patch, "patch must not be null");
        JqValue current = document;
        for (int i = 0; i < patch.size(); i++) {
            if (!(patch.get(i) instanceof JqObject op)) {
                throw new JqPatchException(i, "operation must be an object");
            }
            current = applyOne(current, op, i);
        }
        return current;
    }

    private static JqValue applyOne(JqValue document, JqObject op, int index) {
        String operation = requireString(op, "op", index);
        String path = requireString(op, "path", index);
        return switch (operation) {
            case "add" -> {
                JqValue value = requirePresent(op, "value", index);
                yield add(document, path, value, index);
            }
            case "remove" -> remove(document, path, index);
            case "replace" -> {
                JqValue value = requirePresent(op, "value", index);
                yield replace(document, path, value, index);
            }
            case "move" -> {
                String from = requireString(op, "from", index);
                yield move(document, from, path, index);
            }
            case "copy" -> {
                String from = requireString(op, "from", index);
                yield add(document, path, resolve(document, from, index, "from"), index);
            }
            case "test" -> {
                JqValue value = requirePresent(op, "value", index);
                yield test(document, path, value, index);
            }
            default -> throw new JqPatchException(index, "unrecognized op '" + operation + "'");
        };
    }

    private static String requireString(JqObject op, String member, int index) {
        JqValue value = op.has(member) ? op.get(member) : null;
        if (!(value instanceof JqString s)) {
            throw new JqPatchException(index,
                    value == null ? "missing '" + member + "' parameter"
                            : "member '" + member + "' must be a string");
        }
        return s.stringValue();
    }

    private static JqValue requirePresent(JqObject op, String member, int index) {
        if (!op.has(member)) {
            throw new JqPatchException(index, "missing '" + member + "' parameter");
        }
        return op.get(member);
    }

    private static JqValue add(JqValue document, String path, JqValue value, int index) {
        if (path.isEmpty()) return value;
        Chain chain = resolveChain(document, path, index);
        JqValue parent = chain.parent();
        JqValue updated;
        if (parent instanceof JqObject obj) {
            updated = obj.with(chain.token(), value);
        } else {
            JqArray arr = (JqArray) parent;
            int at = arrayIndex(chain.token(), arr.size(), true, index);
            List<JqValue> elems = new ArrayList<>(arr.arrayValue());
            elems.add(at, value);
            updated = JqArray.of(elems);
        }
        return rebuildUp(chain, updated);
    }

    private static JqValue remove(JqValue document, String path, int index) {
        if (path.isEmpty()) {
            throw new JqPatchException(index, "cannot remove the document root");
        }
        Chain chain = resolveChain(document, path, index);
        JqValue parent = chain.parent();
        JqValue updated;
        if (parent instanceof JqObject obj) {
            if (!obj.has(chain.token())) {
                throw new JqPatchException(index, "missing location '" + path + "'");
            }
            updated = obj.without(chain.token());
        } else {
            JqArray arr = (JqArray) parent;
            int at = arrayIndex(chain.token(), arr.size(), false, index);
            List<JqValue> elems = new ArrayList<>(arr.arrayValue());
            elems.remove(at);
            updated = JqArray.of(elems);
        }
        return rebuildUp(chain, updated);
    }

    private static JqValue replace(JqValue document, String path, JqValue value, int index) {
        if (path.isEmpty()) return value;
        Chain chain = resolveChain(document, path, index);
        JqValue parent = chain.parent();
        JqValue updated;
        if (parent instanceof JqObject obj) {
            if (!obj.has(chain.token())) {
                throw new JqPatchException(index, "missing location '" + path + "'");
            }
            updated = obj.with(chain.token(), value);
        } else {
            JqArray arr = (JqArray) parent;
            int at = arrayIndex(chain.token(), arr.size(), false, index);
            List<JqValue> elems = new ArrayList<>(arr.arrayValue());
            elems.set(at, value);
            updated = JqArray.of(elems);
        }
        return rebuildUp(chain, updated);
    }

    private static JqValue move(JqValue document, String from, String path, int index) {
        if (from.equals(path)) {
            // Same location: valid no-op once the location resolves.
            resolve(document, from, index, "from");
            return document;
        }
        if (!path.isEmpty() && (path.equals(from) || path.startsWith(from + "/"))) {
            throw new JqPatchException(index, "cannot move a location into its own child");
        }
        if (from.isEmpty() && path.isEmpty()) return document;
        JqValue value = resolve(document, from, index, "from");
        JqValue removed = from.isEmpty() ? null : remove(document, from, index);
        // add() on the post-removal document: paths evaluate after removal.
        return removed == null ? value : add(removed, path, value, index);
    }

    private static JqValue test(JqValue document, String path, JqValue value, int index) {
        JqValue actual = path.isEmpty() ? document : resolve(document, path, index, "path");
        if (!Exact.equal(actual, value)) {
            throw new JqPatchException(index,
                    "test failed at '" + path + "': expected " + value.toJsonString()
                            + " but was " + actual.toJsonString());
        }
        return document;
    }

    /** Resolve a pointer strictly; missing locations and bad tokens throw. */
    private static JqValue resolve(JqValue document, String pointer, int index, String role) {
        if (pointer.isEmpty()) return document;
        if (!pointer.startsWith("/")) {
            throw new JqPatchException(index, "pointer must start with '/': '" + pointer + "'");
        }
        JqValue current = document;
        String[] tokens = pointer.substring(1).split("/", -1);
        for (String raw : tokens) {
            String token = unescape(raw);
            if (current instanceof JqObject obj) {
                if (!obj.has(token)) {
                    throw new JqPatchException(index, "missing location '" + pointer + "'");
                }
                current = obj.get(token);
            } else if (current instanceof JqArray arr) {
                current = arr.get(arrayIndex(token, arr.size(), false, index));
            } else {
                throw new JqPatchException(index, "missing location '" + pointer + "'");
            }
        }
        return current;
    }

    /**
     * Strict chain to the parent of the final token: every intermediate
     * container must exist. Iterative (document depth is unbounded).
     */
    private static Chain resolveChain(JqValue document, String pointer, int index) {
        List<JqValue> containers = new ArrayList<>();
        List<String> edges = new ArrayList<>();
        containers.add(document);
        String[] tokens = pointer.substring(1).split("/", -1);
        JqValue current = document;
        for (int t = 0; t < tokens.length - 1; t++) {
            String token = unescape(tokens[t]);
            if (current instanceof JqObject obj) {
                if (!obj.has(token)) {
                    throw new JqPatchException(index, "missing location '" + pointer + "'");
                }
                current = obj.get(token);
            } else if (current instanceof JqArray arr) {
                current = arr.get(arrayIndex(token, arr.size(), false, index));
            } else {
                throw new JqPatchException(index, "missing location '" + pointer + "'");
            }
            containers.add(current);
            edges.add(token);
        }
        return new Chain(containers, edges, current, unescape(tokens[tokens.length - 1]));
    }

    /** Rebuild the chain bottom-up around the updated parent. */
    private static JqValue rebuildUp(Chain chain, JqValue updatedParent) {
        List<JqValue> containers = chain.containers();
        List<String> edges = chain.edges();
        JqValue result = updatedParent;
        // containers[size-1] is the parent, already rebuilt; re-point ancestors.
        for (int level = containers.size() - 2; level >= 0; level--) {
            JqValue container = containers.get(level);
            String edge = edges.get(level);
            if (container instanceof JqObject obj) {
                result = obj.with(edge, result);
            } else {
                JqArray arr = (JqArray) container;
                List<JqValue> elems = new ArrayList<>(arr.arrayValue());
                elems.set(Integer.parseInt(edge), result);
                result = JqArray.of(elems);
            }
        }
        return result;
    }

    private record Chain(List<JqValue> containers, List<String> edges,
                         JqValue parent, String token) {}

    /**
     * Strict array index: plain digits, no leading zeros, {@code -} only
     * for appends. Never negative, never lenient.
     */
    private static int arrayIndex(String token, int size, boolean forAdd, int index) {
        if (token.equals("-")) {
            if (!forAdd) {
                throw new JqPatchException(index, "'-' is only valid for appends");
            }
            return size;
        }
        if (token.isEmpty()) {
            throw new JqPatchException(index, "invalid array index ''");
        }
        long parsed = 0;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < '0' || c > '9') {
                throw new JqPatchException(index, "invalid array index '" + token + "'");
            }
            if (i == 0 && token.length() > 1 && c == '0') {
                throw new JqPatchException(index, "array index has leading zeros: '" + token + "'");
            }
            parsed = parsed * 10 + (c - '0');
            if (parsed > size) {
                throw new JqPatchException(index,
                        "array index " + token + " out of bounds for size " + size);
            }
        }
        if (!forAdd && parsed == size) {
            throw new JqPatchException(index,
                    "array index " + token + " out of bounds for size " + size);
        }
        return (int) parsed;
    }

    private static String unescape(String token) {
        if (token.indexOf('~') < 0) return token;
        return token.replace("~1", "/").replace("~0", "~");
    }
}

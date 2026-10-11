package io.hyperfoil.tools.jjq.yaml;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps a mapper failure path to a 1-based source line in YAML text.
 *
 * <p>Best-effort, block-structure only: called after a successful parse, so
 * the document is well-formed and indentation is trustworthy. Each path
 * segment resolves against the outline (mapping keys by exact match,
 * sequence items by index); anything confusing bails to {@code -1} rather
 * than risk a wrong line — quoted keys, explicit {@code ?} keys, aliases
 * as descent targets, multi-document streams, tab indentation, and flow
 * collections spanning lines.</p>
 *
 * <p>Two rules keep single-line flow exact without in-line parsing: a value
 * whose whole extent is one line is trivially on that line, and flow that
 * spans lines is attributed to its starting key line. A wrong line is worse
 * than none, so every bail-out returns {@code -1}.</p>
 */
final class YamlPathLocator {

    private YamlPathLocator() {}

    /** A source line with its 1-based number and flow-bracket depth at its start. */
    private record Line(String text, int num, int flowDepth) {}

    /**
     * Locate the 1-based line of the value addressed by path segments
     * (String fields, Integer indexes, outermost first). An empty path
     * (root mismatch) resolves to the first content line.
     *
     * @param yaml     the source document (must have parsed successfully)
     * @param segments the failure path segments
     * @return the 1-based line number, or -1 when it cannot be determined
     */
    static int locate(String yaml, List<Object> segments) {
        if (segments == null) return -1;
        for (Object s : segments) {
            if (!(s instanceof String) && !(s instanceof Integer)) return -1;
        }
        String[] raw = yaml.split("\r\n|\r|\n", -1);
        List<Line> lines = new ArrayList<>(raw.length);
        boolean seenDocStart = false;
        boolean directivesDone = false;
        int num = 0;
        for (String r : raw) {
            num++;
            String stripped = stripComment(r).strip();
            if (!directivesDone && stripped.startsWith("%")) continue;
            directivesDone = true;
            if (stripped.equals("---")) {
                // An explicit start marker is benign only before any content;
                // mid-document it opens a second document.
                if (seenDocStart || !lines.isEmpty()) return -1;
                seenDocStart = true;
                continue;
            }
            if (stripped.startsWith("---")) return -1; // inline-root doc, unsupported
            if (stripped.equals("...") || stripped.startsWith("... ")) break; // end marker: ignore rest
            lines.add(new Line(r, num, 0));
        }
        // Tab indentation cannot occur in parsed docs; refuse outright.
        for (Line l : lines) {
            int i = 0;
            while (i < l.text.length() && (l.text.charAt(i) == ' ' || l.text.charAt(i) == '\t')) i++;
            for (int j = 0; j < i; j++) {
                if (l.text.charAt(j) == '\t') return -1;
            }
        }
        lines = excludeMultilineQuotes(lines);
        lines = withFlowDepth(lines);
        if (segments.isEmpty()) {
            // Root mismatch (path `$`): the offending value starts at the
            // first content line (#130 comment).
            for (Line l : lines) {
                if (indentOf(l.text) >= 0) return l.num;
            }
            return -1;
        }
        int line = resolve(segments, 0, -1, true, lines);
        return line;
    }

    /**
     * Drop continuation lines of multi-line quoted scalars (their `key:`-shaped
     * content must not resolve as structure). Only quotes at value-start
     * positions open multiline mode (after `: `, `- `, `---`, `[{,`, or line
     * start); quotes inside plain scalars are literal. The opening line is
     * always kept (it may hold the key itself).
     */
    private static List<Line> excludeMultilineQuotes(List<Line> lines) {
        List<Line> out = new ArrayList<>(lines.size());
        boolean inSingle = false, inDouble = false;
        for (Line l : lines) {
            String t = l.text;
            int i = 0;
            if (inSingle || inDouble) {
                int close = scanClose(t, 0, inSingle);
                if (close < 0) continue; // still inside: drop line
                inSingle = inDouble = false;
                i = close + 1;
                out.add(l);
            } else {
                out.add(l);
            }
            // Scan the rest of the line for unbalanced value-start opens.
            while (i < t.length()) {
                if (!inSingle && !inDouble && t.charAt(i) == '#') break; // comment: rest inert
                if (!inSingle && !inDouble && (t.charAt(i) == '"' || t.charAt(i) == '\'')) {
                    if (isValueStart(t, i)) {
                        int close = scanClose(t, i + 1, t.charAt(i) == '\'');
                        if (close < 0) {
                            if (t.charAt(i) == '\'') inSingle = true;
                            else inDouble = true;
                            break;
                        }
                        i = close + 1;
                        continue;
                    }
                }
                if (inSingle || inDouble) {
                    int close = scanClose(t, i, inSingle);
                    if (close < 0) break;
                    inSingle = inDouble = false;
                    i = close + 1;
                    continue;
                }
                i++;
            }
        }
        return out;
    }

    /** Find the closing quote from index i (exclusive of the opener); -1 if none. */
    private static int scanClose(String t, int i, boolean single) {
        while (i < t.length()) {
            char c = t.charAt(i);
            if (single) {
                if (c == '\'') {
                    if (i + 1 < t.length() && t.charAt(i + 1) == '\'') { i += 2; continue; }
                    return i;
                }
            } else {
                if (c == '\\') { i += 2; continue; }
                if (c == '"') return i;
            }
            i++;
        }
        return -1;
    }

    /** True when a quote at index q opens a quoted scalar (value-start position). */
    private static boolean isValueStart(String t, int q) {
        int i = q - 1;
        while (i >= 0 && (t.charAt(i) == ' ' || t.charAt(i) == '\t')) i--;
        if (i < 0) return true;
        char p = t.charAt(i);
        return p == ':' || p == '-' || p == '[' || p == '{' || p == ',';
    }

    /** Annotate lines with flow-bracket depth at line start (quote-aware). */
    private static List<Line> withFlowDepth(List<Line> lines) {
        List<Line> out = new ArrayList<>(lines.size());
        int depth = 0;
        for (Line l : lines) {
            out.add(new Line(l.text, l.num, depth));
            depth = Math.max(0, depth + lineBracketDelta(l.text));
        }
        return out;
    }

    /** Net `[{` minus `]}` outside quotes and comments on one line. */
    private static int lineBracketDelta(String t) {
        int d = 0;
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    if (i + 1 < t.length() && t.charAt(i + 1) == '\'') i++;
                    else inSingle = false;
                }
            } else if (inDouble) {
                if (c == '\\') i++;
                else if (c == '"') inDouble = false;
            } else if (c == '\'') inSingle = true;
            else if (c == '"') inDouble = true;
            else if (c == '#') break;
            else if (c == '[' || c == '{') d++;
            else if (c == ']' || c == '}') d--;
        }
        return d;
    }

    /**
     * Resolve segments[si..] within scope (lines of one container).
     * Returns the 1-based line of the addressed node, or -1.
     */
    private static int resolve(List<Object> segs, int si, int parentIndent,
                               boolean allowSeq, List<Line> scope) {
        Object seg = segs.get(si);
        boolean last = si == segs.size() - 1;
        if (seg instanceof Integer idx) {
            return resolveIndex(segs, si, idx, parentIndent, allowSeq, scope);
        }
        String key = (String) seg;
        // Direct children: minimal indent above the parent. Flow continuations
        // (depth > 0) are never structural children.
        int childIndent = -1;
        for (Line l : scope) {
            int indent = indentOf(l.text);
            if (indent < 0 || l.flowDepth > 0) continue;
            if (indent <= parentIndent) continue;
            if (childIndent < 0 || indent < childIndent) childIndent = indent;
        }
        if (childIndent < 0) return -1;
        int match = -1;
        for (Line l : scope) {
            int indent = indentOf(l.text);
            if (indent != childIndent || l.flowDepth > 0) continue;
            String[] split = splitKey(stripComment(l.text.substring(indent)));
            if (split != null && split[0].equals(key)) match = l.num; // dupes: last wins
        }
        if (match < 0) return -1;
        if (last) return match;
        return descendIntoValue(segs, si, scope, match);
    }

    /** Resolve an Integer segment: the idx-th `- ` item in scope. */
    private static int resolveIndex(List<Object> segs, int si, int idx, int parentIndent,
                                    boolean allowSeq, List<Line> scope) {
        if (idx < 0) return -1;
        boolean last = si == segs.size() - 1;
        int dashIndent = -1;
        for (Line l : scope) {
            int indent = indentOf(l.text);
            if (indent < 0 || l.flowDepth > 0) continue;
            if (indent < parentIndent || (indent == parentIndent && !allowSeq)) break;
            if (isDash(l.text.substring(indent))) {
                dashIndent = indent;
                break;
            }
            if (indent == parentIndent) break;
        }
        if (dashIndent < 0) return -1;
        int seen = -1;
        for (int i = 0; i < scope.size(); i++) {
            Line l = scope.get(i);
            int indent = indentOf(l.text);
            if (indent < 0 || l.flowDepth > 0) continue;
            if (indent < dashIndent) break;
            if (indent > dashIndent) continue;
            if (!isDash(l.text.substring(indent))) break;
            if (++seen == idx) {
                if (last) return l.num;
                return descendIntoItem(segs, si, scope, i, dashIndent);
            }
        }
        return -1;
    }

    private static boolean isDash(String content) {
        return content.startsWith("- ") || content.equals("-") || content.startsWith("-\t");
    }

    /**
     * Descend into a matched sequence item: its inline remainder (if any)
     * becomes a pseudo-line at dashIndent+2, followed by its deeper lines.
     */
    private static int descendIntoItem(List<Object> segs, int si, List<Line> scope,
                                       int itemIdx, int dashIndent) {
        Line item = scope.get(itemIdx);
        List<Line> children = new ArrayList<>();
        String rest = item.text.substring(indentOf(item.text) + 1).strip();
        if (!rest.isEmpty()) {
            children.add(new Line(" ".repeat(dashIndent + 2) + rest, item.num, 0));
        }
        for (int i = itemIdx + 1; i < scope.size(); i++) {
            Line l = scope.get(i);
            int indent = indentOf(l.text);
            if (indent < 0) continue;
            if (indent <= dashIndent) break;
            children.add(l);
        }
        if (children.isEmpty()) return -1;
        return resolve(segs, si + 1, dashIndent, false, children);
    }

    /**
     * Descend past a matched mapping key (by line number) into its value.
     * Same-line flow or scalar completes on the key line; blank remainder
     * continues on following lines; aliases, tags and block scalars have no
     * addressable children.
     */
    private static int descendIntoValue(List<Object> segs, int si, List<Line> scope, int keyNum) {
        int ki = -1;
        for (int i = 0; i < scope.size(); i++) {
            if (scope.get(i).num == keyNum) { ki = i; break; }
        }
        if (ki < 0) return -1;
        Line keyLine = scope.get(ki);
        String content = stripComment(keyLine.text.substring(indentOf(keyLine.text)));
        String[] split = splitKey(content);
        if (split == null) return -1;
        String after = split[1].strip();
        Object next = segs.get(si + 1);
        if (!after.isEmpty()) {
            char c = after.charAt(0);
            if (c == '*' || c == '&' || c == '!' || c == '|' || c == '>') return -1;
            if (c == '{' || c == '[') {
                // Multiline flow continues below: attribute to the key line.
                if (lineBracketDelta(after) > 0) return keyLine.num;
                if (next instanceof String s && !containsKey(after, s)) return -1;
                return keyLine.num;
            }
            return -1; // scalar remainder: no children possible
        }
        int keyIndent = indentOf(keyLine.text);
        List<Line> children = new ArrayList<>();
        for (int i = ki + 1; i < scope.size(); i++) {
            Line l = scope.get(i);
            int indent = indentOf(l.text);
            if (indent < 0) continue;
            if (indent < keyIndent) break;
            // Same-indent dashes serve as this mapping's sequence value.
            if (indent == keyIndent && !isDash(l.text.substring(indent))) break;
            children.add(l);
        }
        if (children.isEmpty()) return -1;
        return resolve(segs, si + 1, keyIndent, true, children);
    }

    /** Quote-aware `#` comment strip (single/double quotes, backslash escapes). */
    private static String stripComment(String line) {
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '\'') i++;
                    else inSingle = false;
                }
            } else if (inDouble) {
                if (c == '\\') i++;
                else if (c == '"') inDouble = false;
            } else if (c == '\'') inSingle = true;
            else if (c == '"') inDouble = true;
            else if (c == '#') return line.substring(0, i);
        }
        return line;
    }

    /** Indent width, or -1 for blank/comment lines. */
    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') i++;
        if (i >= line.length()) return -1;
        if (line.charAt(i) == '#') return -1;
        return i;
    }

    /**
     * Split line content into {@code [key, rest]} at the mapping separator:
     * the first colon outside quotes and flow brackets followed by
     * space/tab/EOL (so values like {@code http://x} or {@code {a: b}}
     * don't split early). Null when no plain key is present (quoted keys,
     * explicit {@code ?} keys, dashes, flow openers, tags/anchors).
     */
    private static String[] splitKey(String content) {
        if (content.isEmpty()) return null;
        char first = content.charAt(0);
        if (first == '"' || first == '\'' || first == '?' || first == '-' || first == '{'
                || first == '[' || first == '&' || first == '*' || first == '!' || first == '<') {
            return null;
        }
        int end = -1;
        boolean inSingle = false, inDouble = false;
        int depth = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '\'') i++;
                    else inSingle = false;
                }
            } else if (inDouble) {
                if (c == '\\') i++;
                else if (c == '"') inDouble = false;
            } else if (c == '\'') inSingle = true;
            else if (c == '"') inDouble = true;
            else if (c == '[' || c == '{') depth++;
            else if (c == ']' || c == '}') depth--;
            else if (c == ':' && depth == 0) {
                int j = i + 1;
                if (j >= content.length() || content.charAt(j) == ' ' || content.charAt(j) == '\t') {
                    end = i;
                    break;
                }
            }
        }
        if (end <= 0) return null;
        String key = content.substring(0, end).stripTrailing();
        if (key.isEmpty()) return null;
        return new String[]{key, content.substring(end + 1)};
    }

    /** Quote-aware check that a single-line flow fragment defines key. */
    private static boolean containsKey(String flow, String key) {
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < flow.length(); i++) {
            char c = flow.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    if (i + 1 < flow.length() && flow.charAt(i + 1) == '\'') i++;
                    else inSingle = false;
                }
            } else if (inDouble) {
                if (c == '\\') i++;
                else if (c == '"') inDouble = false;
            } else if (c == '\'') inSingle = true;
            else if (c == '"') inDouble = true;
            else if (flow.startsWith(key, i)
                    && (i == 0 || !isKeyChar(flow.charAt(i - 1)))) {
                int j = i + key.length();
                while (j < flow.length() && flow.charAt(j) == ' ') j++;
                if (j < flow.length() && flow.charAt(j) == ':') return true;
            }
        }
        return false;
    }

    private static boolean isKeyChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.';
    }
}

package io.hyperfoil.tools.jjq.yaml;

import io.hyperfoil.tools.jjq.value.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Native YAML parser: {@code byte[]} straight to {@link JqValue}, no intermediate
 * node tree (unlike SnakeYAML's scan → parse → compose pipeline).
 *
 * <p>Covers: block mappings/sequences (with lenient same-map joining of deeper
 * keys), plain and quoted scalars (with folding), flow collections (including
 * multi-line), literal/folded block scalars, anchors/aliases (incl. {@code <<}
 * merges), tags, comments, multi-document streams. Directives are skipped;
 * explicit {@code ?} keys and complex (flow) mapping keys are rejected.</p>
 *
 * <p>Zero-copy techniques borrowed from the JSON byte parser: field-name
 * interning through the shared {@link JqValues#internFieldName} table
 * (enables reference-equality lookup in {@link JqObject#get}), direct digit
 * accumulation for numbers, no per-token position objects. String values are
 * materialized (deferred decoding is a measured follow-up).</p>
 */
final class YamlParser {

    private final byte[] d;
    private final int end;
    private final YamlOptions options;
    private int pos;
    private int line = 1;
    private Map<String, JqValue> anchors = new HashMap<>();
    /** `%TAG` handles defined by the current document's directives. */
    private Map<String, String> tagHandles = new HashMap<>();
    /** A `%YAML` directive was seen in this stream (duplicates are errors). */
    private boolean seenYamlDirective;
    /** Whether the last parsed document ended with an explicit `...`. */
    private boolean lastDocEnded;
    /**
     * An anchor from an `&x` + nested-block value is waiting for its node.
     * Another anchor on that same root node is a duplicate (suite 4JVG:
     * `&node2` + `&v2 val2`); anchors on deeper nodes are independent.
     * Set only by the anchor-empty branch; cleared on collection dispatch.
     */
    private boolean outerValueAnchorPending;

    private YamlParser(byte[] data, int offset, int length, YamlOptions options) {
        int start = offset;
        int end = offset + length;
        if (end - start >= 3
                && data[start] == (byte) 0xEF && data[start + 1] == (byte) 0xBB && data[start + 2] == (byte) 0xBF) {
            start += 3;
        }
        this.d = data;
        this.pos = start;
        this.end = end;
        this.options = options;
    }

    /** Parse a single document (first of a stream). Empty input yields {@link JqNull#NULL}. */
    static JqValue parse(byte[] data, int offset, int length, YamlOptions options) {
        YamlParser p = new YamlParser(data, offset, length, options);
        JqValue doc = p.parseDocument(true);
        return doc == null ? JqNull.NULL : doc;
    }

    /** Parse all documents of a {@code ---}-separated stream. */
    static List<JqValue> parseAll(byte[] data, int offset, int length, YamlOptions options) {
        YamlParser p = new YamlParser(data, offset, length, options);
        var docs = new ArrayList<JqValue>();
        boolean anyDoc = false;
        for (;;) {
            p.anchors = new HashMap<>();
            p.tagHandles = new HashMap<>();
            if (anyDoc) {
                p.skipTrailingEndMarkers();
            }
            JqValue doc = p.parseDocument(!anyDoc);
            if (doc == null) break;
            anyDoc = true;
            docs.add(doc);
            // An explicit `...` ends the directive scope (suite W4TN parity);
            // without it a repeated %YAML is a duplicate (suite SF5V).
            if (p.lastDocEnded) {
                p.seenYamlDirective = false;
            }
            // Content after a complete document needs a `---` marker to start
            // a new one (suite BS4K/KS4U parity; SnakeYAML agrees) — unless the
            // previous document ended with `...` (suite 7Z25 parity).
            // Directives are exempt here: their own placement rule reports them.
            if (!p.lastDocEnded && p.skipBlankAndComments()) {
                int c = p.d[p.pos];
                if (c != '%' && !p.matchMarker("---") && !p.isDocEndAtLineStart()) {
                    throw new YamlParseException("content after document without document marker",
                            p.line);
                }
            }
        }
        return docs;
    }

    // ========================================================================
    //  Documents
    // ========================================================================

    /** Parse one document. Returns null at clean EOF (no more documents). */
    private JqValue parseDocument(boolean firstDoc) {
        boolean sawDirectives = skipDocPreamble();
        // Directives mid-stream need a `...` footer on the previous document
        // (suite 9HCY/EB22 parity).
        if (sawDirectives && !firstDoc && !lastDocEnded) {
            throw new YamlParseException("directive without document end marker", line);
        }
        lastDocEnded = false;
        if (pos >= end) {
            // Directives with no document at all are malformed (suite 9MMA).
            if (sawDirectives) {
                throw new YamlParseException("directives without document", line);
            }
            return null;
        }
        if (isDocEndAtLineStart()) {
            // A bare `...` ends the stream with no document. Directives alone
            // do not start one, so `%YAML` + `...` is malformed (B63P parity).
            if (sawDirectives) {
                throw new YamlParseException("document end marker without document", line);
            }
            return null;
        }
        String rest = consumeDocStart();
        JqValue doc;
        if (rest != null) {
            boolean commented = hasTrailingComment(rest);
            String stripped = stripLine(rest);
            // Bare `---`: empty, tag/anchor-only prefixes (suite UGM3:
            // `--- !<tag:...>` attaches to the following block), or a `#`
            // comment (a `#` not followed by space is content, e.g. `--- #foo`
            // is the scalar "#foo").
            boolean bare = stripped.isEmpty() || anchorOrTagOnly(stripped) != null
                    || (stripped.charAt(0) == '#'
                        && (stripped.length() == 1 || stripped.charAt(1) == ' '
                            || stripped.charAt(1) == '\t'));
            if (bare) {
                // Content follows on later lines, an empty doc, an immediate
                // `...` end, or another `---` (empty doc, left for next document).
                if (!skipBlankAndComments()) return JqNull.NULL;
                if (consumeDocEnd()) {
                    lastDocEnded = true;
                    return JqNull.NULL;
                }
                if (matchMarker("---")) {
                    return JqNull.NULL;
                }
                doc = parseBlockNode(0, false, firstDoc);
            } else {
                // Inline content after `--- `: full dispatch on the first line.
                // Block mappings/sequences cannot open on the same line as the
                // marker (SnakeYAML parity). Doc-root context for scalars.
                int kind = classifyFirstLine(stripped);
                if (kind == LINE_SEQ || kind == LINE_MAP) {
                    throw new YamlParseException(
                            "block collections cannot follow '---' on the same line", line);
                }
                if (kind == LINE_EXPLICIT) {
                    throw new YamlParseException("explicit '? ' mapping keys are not supported", line);
                }
                doc = parseScalarDocument(stripped, 0, commented);
            }
        } else {
            doc = parseBlockNode(0, false, firstDoc);
        }
        skipBlankAndComments();
        // Consume a trailing end marker (validates it stands alone).
        // Content after `...` starts a new document (suite 7Z25 parity).
        if (consumeDocEnd()) {
            lastDocEnded = true;
        }
        return doc == null ? JqNull.NULL : doc;
    }

    /**
     * If a `...` marker opens the line, validate it stands alone (only spaces
     * or a comment may follow) and consume it. Returns whether one was found.
     */
    private boolean consumeDocEnd() {
        if (!isDocEndAtLineStart()) return false;
        int p = pos + 3;
        while (p < end && (d[p] == ' ' || d[p] == '\t')) p++;
        if (p < end && d[p] != '\n' && d[p] != '\r' && d[p] != '#') {
            throw new YamlParseException("content after document end marker", line);
        }
        advanceLine();
        return true;
    }

    /** Skip consecutive `...`-only lines (legal trailing markers after a completed document). */
    private void skipTrailingEndMarkers() {
        for (;;) {
            int saved = pos;
            int savedLine = line;
            if (!skipBlankAndComments()) return;
            int le = lineEnd(pos);
            int cs = contentStart(d, pos, le);
            // End-marker line: exactly `...` plus optional trailing comment
            boolean marker = le - cs >= 3 && d[cs] == '.' && d[cs + 1] == '.' && d[cs + 2] == '.'
                    && (cs + 3 == le || d[cs + 3] == ' ' || d[cs + 3] == '\t');
            if (marker) {
                int q = cs + 3;
                while (q < le && (d[q] == ' ' || d[q] == '\t')) q++;
                if (q < le && d[q] != '#') marker = false;
            }
            if (!marker) {
                pos = saved;
                line = savedLine;
                return;
            }
            advanceLine();
        }
    }

    /**
     * Skip and validate `%`-directive lines at the current position.
     * `%YAML n.m` must be exact (suite H7TQ/MUS6/00: no extra words, `#` needs
     * separation space) and appear at most once per stream (suite SF5V/MUS6/01).
     * `%TAG handle uri` records the handle for this document (suite QLJ7:
     * handles do not cross `---` boundaries; the table resets per document).
     */
    private void skipDirectives() {
        while (pos < end && d[pos] == '%') {
            int le = lineEnd(pos);
            String directive = new String(d, pos, le - pos, StandardCharsets.UTF_8);
            if (isDirective(directive, "%YAML")) {
                // Separation whitespace required (`%YAML 1.1#...` is malformed,
                // suite MUS6/00); trailing comment allowed (suite BEC7).
                if (!directive.matches("%YAML\\s+[0-9]+\\.[0-9]+(\\s+(#.*)?)?")) {
                    throw new YamlParseException("invalid %YAML directive", line);
                }
                if (seenYamlDirective) {
                    throw new YamlParseException("duplicate %YAML directive", line);
                }
                seenYamlDirective = true;
            } else if (isDirective(directive, "%TAG")) {
                String[] parts = directive.split("\\s+");
                if (parts.length != 3 || !parts[1].startsWith("!")) {
                    throw new YamlParseException("invalid %TAG directive", line);
                }
                tagHandles.put(parts[1], parts[2]);
            }
            // Any other %-directive is reserved and ignored (suite 2LFX/6LVF).
            advanceLine();
            skipBlankLinesOnly();
        }
    }

    /**
     * True when the directive line is exactly `%NAME` or `%NAME` + separation
     * whitespace (`%YAMLL` is not `%YAML`, suite MUS6/06 parity).
     */
    private static boolean isDirective(String directive, String name) {
        if (directive.equals(name)) return true;
        return directive.startsWith(name)
                && directive.length() > name.length()
                && (directive.charAt(name.length()) == ' '
                    || directive.charAt(name.length()) == '\t');
    }

    /**
     * Skip the document preamble: blank lines, comment lines and `%`-directives
     * in any interleaving (comments may separate `...` from a following `%TAG`).
     * Returns true when at least one directive was consumed. On return, either
     * pos is at EOF or content (non-blank, non-comment) is ahead.
     */
    private boolean skipDocPreamble() {
        boolean sawDirectives = false;
        for (;;) {
            int before = pos;
            skipDirectives();
            if (pos != before) sawDirectives = true;
            if (!skipBlankAndComments()) return sawDirectives;
            if (d[pos] != '%') return sawDirectives;
        }
    }

    /** If a `---` marker opens the line, consume it and return the raw rest of
     * the line (comment intact: callers need it for comment detection). */
    private String consumeDocStart() {
        if (matchMarker("---")) {
            pos += 3;
            // Skip the separation space after the marker, keep the rest
            while (pos < end && (d[pos] == ' ' || d[pos] == '\t')) pos++;
            return readLine();
        }
        return null;
    }

    private boolean isDocEndAtLineStart() {
        return matchMarker("...");
    }

    /** True when `---`/`...` opens the line (followed by space, tab, or EOL). */
    private boolean matchMarker(String marker) {
        int m = marker.length();
        if (pos + m > end) return false;
        for (int i = 0; i < m; i++) {
            if (d[pos + i] != marker.charAt(i)) return false;
        }
        if (pos + m == end) return true;
        byte c = d[pos + m];
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    // ========================================================================
    //  Lines
    // ========================================================================

    /** Skip blank lines (whitespace only). Comment lines are NOT skipped. */
    private void skipBlankLinesOnly() {
        for (;;) {
            int p = pos;
            while (p < end && (d[p] == ' ' || d[p] == '\t')) p++;
            if (p < end && (d[p] == '\n' || d[p] == '\r')) {
                advanceLine();
            } else {
                return;
            }
        }
    }

    /**
     * Skip blank lines and comment-only lines. Returns false at EOF.
     * Leaves {@code pos} at the START of the next content line (leading
     * spaces included) — every block parser relies on this invariant.
     */
    private boolean skipBlankAndComments() {
        for (;;) {
            if (pos >= end) return false;
            int p = pos;
            while (p < end && (d[p] == ' ' || d[p] == '\t')) p++;
            if (p >= end) {
                pos = end;
                return false;
            }
            byte c = d[p];
            if (c == '\n' || c == '\r' || c == '#') {
                advanceLine();
                continue;
            }
            return true;
        }
    }

    /** Advance past the current line (any of \n, \r\n, \r). Counts the line. */
    private void advanceLine() {
        while (pos < end && d[pos] != '\n' && d[pos] != '\r') pos++;
        if (pos < end) {
            if (d[pos] == '\r' && pos + 1 < end && d[pos + 1] == '\n') pos++;
            pos++;
            line++;
        }
    }

    /**
     * Read from pos to end of line (excluding the break, one trailing \r).
     * Advances past the break. Returns the raw line INCLUDING leading spaces.
     */
    private String readLine() {
        int s = pos;
        int e = s;
        while (e < end && d[e] != '\n' && d[e] != '\r') e++;
        int trim = e;
        if (trim > s && d[trim - 1] == '\r') trim--;
        pos = e;
        if (pos < end) {
            if (d[pos] == '\r' && pos + 1 < end && d[pos + 1] == '\n') pos++;
            pos++;
            line++;
        }
        return new String(d, s, trim - s, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Look at the current line without consuming it. */
    // Line classification codes for block-structure decisions. Computed on byte
    // ranges (no String allocation on the peek path); quoted regions tracked
    // since quote characters are single bytes even in UTF-8 content.
    private static final int LINE_BLANK = 0;    // whitespace only
    private static final int LINE_COMMENT = 1;  // comment-only line
    private static final int LINE_SEQ = 2;      // '- ' entry
    private static final int LINE_EXPLICIT = 3; // '? ' key
    private static final int LINE_MARKER = 4;   // '---' / '...'
    private static final int LINE_MAP = 5;      // contains a mapping indicator
    private static final int LINE_PLAIN = 6;    // anything else

    /** End offset (excl. break, excl. one trailing \r) of the line starting at p. No allocation. */
    private int lineEnd(int p) {
        int e = p;
        while (e < end && d[e] != '\n' && d[e] != '\r') e++;
        return (e > p && d[e - 1] == '\r') ? e - 1 : e;
    }

    /** First non-space/tab offset in [s, e), or e. Tabs are NOT skipped (indent errors surface elsewhere). */
    private static int contentStart(byte[] d, int s, int e) {
        int p = s;
        while (p < e && (d[p] == ' ' || d[p] == '\t')) p++;
        return p;
    }

    /** Classify the line [s, e) for block-structure decisions. */
    private int classifyLine(int s, int e) {
        int p = contentStart(d, s, e);
        if (p >= e) return LINE_BLANK;
        byte c0 = d[p];
        if (c0 == '#') return LINE_COMMENT;
        boolean quoted = c0 == '\'' || c0 == '"';
        if (!quoted) {
            if (c0 == '-' && (p + 1 >= e || d[p + 1] == ' ' || d[p + 1] == '\t')) return LINE_SEQ;
            if (c0 == '?' && (p + 1 >= e || d[p + 1] == ' ' || d[p + 1] == '\t')) return LINE_EXPLICIT;
            if (isMarkerAt(p, e)) return LINE_MARKER;
        }
        // Fast path: no colon means no mapping (single vectorizable scan);
        // the quote-aware scan runs only when a colon exists.
        boolean hasColon = false;
        for (int i = s; i < e; i++) {
            if (d[i] == ':') {
                hasColon = true;
                break;
            }
        }
        if (!hasColon) return LINE_PLAIN;
        return findMappingColon(s, e) >= 0 ? LINE_MAP : LINE_PLAIN;
    }

    /**
     * Classify a first-line String (doc-start content) with the same rules as
     * {@link #classifyLine(int, int)}.
     */
    private static int classifyFirstLine(String first) {
        if (first.isEmpty()) return LINE_BLANK;
        char c0 = first.charAt(0);
        if (c0 == '\'' || c0 == '"') return LINE_PLAIN;
        if (c0 == '-' && (first.length() == 1 || first.charAt(1) == ' ' || first.charAt(1) == '\t')) {
            return LINE_SEQ;
        }
        if (c0 == '?' && (first.length() == 1 || first.charAt(1) == ' ' || first.charAt(1) == '\t')) {
            return LINE_EXPLICIT;
        }
        if (first.equals("---") || first.equals("...")
                || first.startsWith("--- ") || first.startsWith("---\t")
                || first.startsWith("... ") || first.startsWith("...\t")) {
            return LINE_MARKER;
        }
        boolean sq = false, dq = false;
        int depth = 0;
        for (int i = 0; i < first.length(); i++) {
            char c = first.charAt(i);
            if (c == '\'' && !dq) sq = !sq;
            else if (c == '"' && !sq) dq = !dq;
            else if ((c == '[' || c == '{') && !sq && !dq) depth++;
            else if ((c == ']' || c == '}') && !sq && !dq && depth > 0) depth--;
            else if (c == ':' && !sq && !dq && depth == 0) {
                if (i + 1 >= first.length() || first.charAt(i + 1) == ' '
                        || first.charAt(i + 1) == '\t') {
                    return LINE_MAP;
                }
            }
        }
        return LINE_PLAIN;
    }

    /** True when `---`/`...` opens at p with a space/tab/EOL boundary at e. */
    private boolean isMarkerAt(int p, int e) {
        return matchMarkerBytes(p, e, '-') || matchMarkerBytes(p, e, '.');
    }

    private boolean matchMarkerBytes(int p, int e, char c) {
        if (p + 3 > e || d[p] != c || d[p + 1] != c || d[p + 2] != c) return false;
        return p + 3 == e || d[p + 3] == ' ' || d[p + 3] == '\t';
    }

    /**
     * Indentation width in columns (tabs count one). Lenient by itself: block
     * map/sequence dispatch rejects tab-indented structure lines (suite
     * 4EJS/DK95/06), while flow/scalar lines stay tab-tolerant (suite
     * 6CA3/Q5MG/DK95/00).
     */
    private int peekIndent() {
        int n = 0;
        while (pos + n < end && (d[pos + n] == ' ' || d[pos + n] == '\t')) n++;
        return n;
    }

    /** True when the indent region [pos, pos+n) holds no tab. */
    private boolean indentClean(int n) {
        for (int k = 0; k < n; k++) {
            if (d[pos + k] == '\t') return false;
        }
        return true;
    }

    /**
     * Strip a trailing ` #comment` (outside quotes). Idempotent: safe to apply twice.
     * A `#` without preceding space/tab is content (e.g. `a#b`).
     */
    private static String stripComment(String line) {
        // Fast path: quoteless lines skip quote tracking (intrinsic scans).
        if (line.indexOf('\'') < 0 && line.indexOf('"') < 0) {
            int idx = line.indexOf('#');
            while (idx > 0) {
                char p = line.charAt(idx - 1);
                if (p == ' ' || p == '\t') return line.substring(0, idx).stripTrailing();
                idx = line.indexOf('#', idx + 1);
            }
            return line;
        }
        boolean sq = false, dq = false, esc = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (esc) esc = false;
            else if (c == '\\' && dq) esc = true;
            else if (c == '\'' && !dq) sq = !sq;
            else if (c == '"' && !sq) dq = !dq;
            else if (c == '#' && !sq && !dq && i > 0
                    && (line.charAt(i - 1) == ' ' || line.charAt(i - 1) == '\t')) {
                return line.substring(0, i).stripTrailing();
            }
        }
        return line;
    }

    // ========================================================================
    //  Block structure
    // =====================================================================///

    /**
     * Parse a block node (mapping, sequence, flow or scalar document).
     * {@code indent} is the caller's indent context; the effective indent
     * adopts from the first content line when {@code adoptRoot} (stream-start
     * content like ` - a`, suite 2AUY/93JH/F2C7 parity; later documents keep
     * the strict throw, suite 2CMS parity) or always when nested.
     * {@code nested} selects value-position sequence termination: a non-entry
     * line ends the sequence for the outer map instead of throwing
     * (suite 57H4/AZ63/RLU9/S9E8 parity).
     *
     * <p>Anchor/tag-only lines (`&a`, `!foo`) prefix the following node: the base
     * adopts the anchor line's indent, so same-indent content nests
     * (suite U3XV/SKE5/M5C3 parity) while dedented content ends the node
     * (probe: `key:` + `  &a` + `sub: 1` yields `{key: null, sub: 1}`).
     */
    private JqValue parseBlockNode(int indent, boolean nested, boolean adoptRoot) {
        int eff = indent;
        boolean first = true;
        String anchorName = null;
        int anchorInd = -1;
        for (;;) {
            if (!skipBlankAndComments()) return JqNull.NULL;
            // A `%` line here is a misplaced directive: directives end at `---`
            // (suite MUS6/01 parity; `%` cannot start a plain scalar either).
            if (pos < end && d[pos] == '%') {
                throw new YamlParseException("directive after document start marker", line);
            }
            int ind = peekIndent();
            if (ind < eff) return attachAnchor(JqNull.NULL, anchorName);
            // Capture tab-cleanliness now: later branches consume the line.
            boolean indClean = indentClean(ind);
            if (isSeqEntry()) {
                // Tab-indented block structure is rejected (suite 4EJS);
                // flow/scalar lines stay tab-tolerant (suite 6CA3/DK95/00).
                if (!indClean) {
                    throw new YamlParseException("tabs are not allowed for indentation", line);
                }
                // Adopt the sequence's own indent on first content (or throw
                // for later documents, 2CMS parity).
                if (first) {
                    if (ind > eff && !nested && !adoptRoot) {
                        throw new YamlParseException("unexpected indentation", line);
                    }
                    eff = ind;
                }
                first = false;
                outerValueAnchorPending = false;
                return attachAnchor(parseBlockSeq(eff, nested), anchorName);
            }
            if (classifyLine(pos, lineEnd(pos)) == LINE_MARKER) {
                pos += ind;
                break;
            }
            int le = lineEnd(pos);
            int kind = classifyLine(pos, le);
            if (kind == LINE_SEQ) {
                // A `- ` the spaces-only entry check rejected is tab-indented.
                throw new YamlParseException("tabs are not allowed for indentation", line);
            }
            int linePos = pos;
            int lineSaved = line;
            String rawFirst = readLine();
            String stripped = stripLine(rawFirst);
            String onlyAnchor = anchorOrTagOnly(stripped);
            if (onlyAnchor != null) {
                // Anchor/tag-only lines prefix the following node without
                // adopting indent themselves (suite M5C3 parity: `!foo` at 3
                // then `>1` at 2 still nests under the caller base).
                if (!onlyAnchor.isEmpty()) {
                    if (anchorName != null || outerValueAnchorPending) {
                        throw new YamlParseException("duplicate anchor", line);
                    }
                    anchorName = onlyAnchor;
                    anchorInd = ind;
                }
                continue;
            }
            if (anchorName != null && nested && ind < anchorInd) {
                // Dropped below the anchor prefix in value position: the anchor
                // attaches to nothing (probe: `key:` + `  &a` + `sub: 1`
                // yields `{key: null, sub: 1}`). Restore the sibling line.
                pos = linePos;
                line = lineSaved;
                return JqNull.NULL;
            }
            if (kind == LINE_MAP || kind == LINE_EXPLICIT) {
                if (!indClean) {
                    throw new YamlParseException("tabs are not allowed for indentation", line);
                }
                if (first) {
                    if (ind > eff && !nested && !adoptRoot) {
                        pos = linePos;
                        line = lineSaved;
                        throw new YamlParseException("unexpected indentation", line);
                    }
                    eff = ind;
                }
                first = false;
                // Rewind: the map loop reads its first entry itself.
                pos = linePos;
                line = lineSaved;
                outerValueAnchorPending = false;
                return attachAnchor(parseBlockMap(ind), anchorName);
            }
            if (first) {
                if (ind > eff && !nested && !adoptRoot) {
                    pos = linePos;
                    line = lineSaved;
                    throw new YamlParseException("unexpected indentation", line);
                }
                eff = ind;
            }
            first = false;
            return attachAnchor(
                    parseScalarDocument(stripped, ind, hasTrailingComment(rawFirst)), anchorName);
        }
        return attachAnchor(JqNull.NULL, anchorName);
    }

    /** Attach a block-level anchor to a parsed node (no-op when absent). */
    private JqValue attachAnchor(JqValue node, String anchorName) {
        if (anchorName != null && node != null && node != JqNull.NULL) {
            anchors.put(anchorName, node);
        }
        return node;
    }

    /**
     * If the stripped line is only anchor/tag prefixes (`&a`, `!foo`,
     * `&a !b`), return the anchor name (or "" for tags alone); else null.
     */
    private String anchorOrTagOnly(String stripped) {
        String t = stripped;
        String anchorName = null;
        boolean any = false;
        for (;;) {
            if (t.startsWith("&") || t.startsWith("!")) {
                int sp = -1;
                for (int i = 0; i < t.length(); i++) {
                    char c = t.charAt(i);
                    if (c == ' ' || c == '\t') {
                        sp = i;
                        break;
                    }
                }
                if (sp < 0) {
                    // Whole line is one token: anchor/tag only if named
                    if (t.length() > 1) {
                        any = true;
                        if (t.startsWith("&")) anchorName = t.substring(1);
                        else validateTag(t);
                        return anchorName == null ? "" : anchorName;
                    }
                    return null;
                }
                String token = t.substring(0, sp);
                if (token.length() <= 1) return null;
                any = true;
                if (token.startsWith("&")) anchorName = token.substring(1);
                else validateTag(token);
                t = t.substring(sp).strip();
                if (t.isEmpty()) return anchorName == null ? "" : anchorName;
            } else {
                return null;
            }
        }
    }

    /** A scalar-starting node whose first line is known. Dispatches quoted/flow/
     * standalone-block-header/prefixed lines; anything else folds as a plain
     * scalar ({@code level} gates continuation indent). */
    private JqValue parseScalarDocument(String first, int level, boolean commented) {
        if (first.isEmpty()) return JqNull.NULL;
        char c0 = first.charAt(0);
        if (c0 == '"' || c0 == '\'') {
            return parseInlineValue(gatherValueText(first, level, false), level, true, false);
        }
        if (c0 == '{' || c0 == '[') {
            return parseInlineValue(gatherValueText(first, level, false), level, true, false);
        }
        if (isBlockScalarHeader(first)) {
            // Standalone header: content indent auto-detects (doc-root-like rules)
            return parseInlineValue(first, -1, true, false);
        }
        if (c0 == '&' || c0 == '*' || c0 == '!') {
            return parseInlineValue(first, level, true, false);
        }
        // A trailing comment completes the scalar: no folding past it (BF9H parity)
        if (commented) {
            return convertScalar(first, null, line);
        }
        // Plain scalar document: fold following lines (entry rules terminate).
        // Blank lines are paragraph breaks when deeper plain content follows
        // (suite HS5T/NB6Z + probe scalar-blank parity); deeper `- ` lines
        // fold as literal text (AB8U parity).
        StringBuilder sb = new StringBuilder(first);
        boolean pendingBlank = false;
        for (;;) {
            if (pos >= end) break;
            int le = lineEnd(pos);
            int kind = classifyLine(pos, le);
            if (kind == LINE_BLANK) {
                int q = le;
                if (q < end && d[q] == '\r') q++;
                if (q < end && d[q] == '\n') q++;
                int qi = 0;
                while (q + qi < end && d[q + qi] == ' ') qi++;
                if (q + qi < end && d[q + qi] != '\n' && d[q + qi] != '\r'
                        && classifyLine(q, lineEnd(q)) == LINE_PLAIN) {
                    int csq = contentStart(d, q, lineEnd(q));
                    if (csq - q >= level) {
                        pendingBlank = true;
                        advanceTo(q);
                        continue;
                    }
                }
                break;
            }
            int cs;
            if (kind == LINE_SEQ) {
                cs = contentStart(d, pos, le);
                if (cs - pos <= level) break;
            } else {
                if (kind != LINE_PLAIN) break;
                cs = contentStart(d, pos, le);
                if (cs - pos < level) break;
            }
            // The line is plain content: consume it (comment already excluded by kind)
            if (pendingBlank) {
                sb.append('\n');
                pendingBlank = false;
            } else {
                sb.append(' ');
            }
            pos = cs;
            sb.append(stripComment(readLine()).strip());
        }
        return convertScalar(sb.toString(), null, line);
    }

    /**
     * True when the text is a block scalar header (`|`, `>`, optional
     * chomping/indent, then end/space/tab/comment). Anything else starting
     * with `|`/`>` (e.g. `>foo`) is a plain scalar.
     */
    private static boolean isBlockScalarHeader(String text) {
        if (text.isEmpty()) return false;
        char c0 = text.charAt(0);
        if (c0 != '|' && c0 != '>') return false;
        if (text.length() == 1) return true;
        int i = 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '+' || c == '-' || (c >= '0' && c <= '9')) i++;
            else break;
        }
        return i >= text.length() || text.charAt(i) == ' ' || text.charAt(i) == '\t'
                || text.charAt(i) == '#';
    }
    private boolean isSeqEntryAt(int p) {
        if (p >= end || d[p] != '-') return false;
        if (p + 1 >= end) return true;
        byte c = d[p + 1];
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    /** True when the line at pos opens a `- ` sequence entry (pos at line start). */
    private boolean isSeqEntry() {
        int p = pos;
        while (p < end && d[p] == ' ') p++;
        return isSeqEntryAt(p);
    }

    /**
     * Offset of the first top-level `: ` separator (colon + space/tab/EOL
     * outside quotes and flow brackets), or -1. Quote tracking falls back to
     * quote-blind scanning when quotes never close (e.g. apostrophes in
     * {@code don't} or stray quotes in plain scalars): an unbalanced quote
     * must not hide a real mapping separator.
     */
    private int findMappingColon(int s, int e) {
        int found = scanMappingColon(s, e, true);
        // Blind rescan only when quotes never closed (unbalanced); a clean
        // miss stays a miss without paying for a second scan.
        return found == -2 ? scanMappingColon(s, e, false) : found;
    }

    /** Byte-range variant: -1 = none, -2 = unbalanced quotes (caller retries blind). */
    private int scanMappingColon(int s, int e, boolean trackQuotes) {
        boolean sq = false, dq = false, esc = false;
        int depth = 0;
        int start = s;
        // A leading `&anchor:` token keeps its colon (suite 2SXE); a leading
        // `*alias:` does too, even at end of line (`*a:` is one alias).
        // Leading indentation is skipped first (`  *a:`, suite 2SXE).
        int cs = s;
        while (cs < e && (d[cs] == ' ' || d[cs] == '\t')) cs++;
        if (trackQuotes && e - cs > 1 && (d[cs] == '&' || d[cs] == '*')) {
            int j = cs + 1;
            while (j < e && d[j] != ' ' && d[j] != '\t' && d[j] != '\n' && d[j] != '\r') j++;
            if (j > cs + 1 && d[j - 1] == ':' && (j < e || d[cs] == '*')) {
                start = j;
            }
        }
        for (int i = start; i < e; i++) {
            byte c = d[i];
            if (trackQuotes) {
                if (esc) esc = false;
                else if (c == '\\' && dq) esc = true;
                else if (c == '\'' && !dq) sq = !sq;
                else if (c == '"' && !sq) dq = !dq;
            }
            if (!sq && !dq) {
                if (c == '[' || c == '{') depth++;
                else if ((c == ']' || c == '}') && depth > 0) depth--;
                else if (c == ':' && depth == 0) {
                    if (i + 1 >= e || d[i + 1] == ' ' || d[i + 1] == '\t') {
                        return i;
                    }
                }
            }
        }
        if (trackQuotes && (sq || dq)) return -2;
        return -1;
    }

    /** String variant with the same two-pass fallback. Returns index or -1. */
    private static int findMappingColon(String text) {
        int found = scanMappingColon(text, true);
        return found == -2 ? scanMappingColon(text, false) : found;
    }

    /** String variant: -1 = none, -2 = unbalanced quotes. */
    private static int scanMappingColon(String text, boolean trackQuotes) {
        boolean sq = false, dq = false, esc = false;
        int depth = 0;
        int start = 0;
        // A leading `&anchor:` token keeps its colon (suite 2SXE); a leading
        // `*alias:` does too, even at end of line (`*a:` is one alias).
        // Leading indentation is skipped first (`  *a:`, suite 2SXE).
        int cs = 0;
        while (cs < text.length() && (text.charAt(cs) == ' ' || text.charAt(cs) == '\t')) cs++;
        if (trackQuotes && text.length() - cs > 1
                && (text.charAt(cs) == '&' || text.charAt(cs) == '*')) {
            int j = cs + 1;
            while (j < text.length() && text.charAt(j) != ' ' && text.charAt(j) != '\t') j++;
            if (j > cs + 1 && text.charAt(j - 1) == ':'
                    && (j < text.length() || text.charAt(cs) == '*')) {
                start = j;
            }
        }
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (trackQuotes) {
                if (esc) esc = false;
                else if (c == '\\' && dq) esc = true;
                else if (c == '\'' && !dq) sq = !sq;
                else if (c == '"' && !sq) dq = !dq;
            }
            if (!sq && !dq) {
                if (c == '[' || c == '{') depth++;
                else if ((c == ']' || c == '}') && depth > 0) depth--;
                else if (c == ':' && depth == 0) {
                    if (i + 1 >= text.length() || text.charAt(i + 1) == ' '
                            || text.charAt(i + 1) == '\t') {
                        return i;
                    }
                }
            }
        }
        if (trackQuotes && (sq || dq)) return -2;
        return -1;
    }

    private JqArray parseBlockSeq(int indent, boolean nested) {
        var elems = new ArrayList<JqValue>();
        for (;;) {
            if (!skipBlankAndComments()) break;
            int lineStart = pos;
            int ind = peekIndent();
            if (ind < indent) break;
            // Tab-indented entries are rejected (block-structure strictness).
            if (!indentClean(ind)) {
                throw new YamlParseException("tabs are not allowed for indentation", line);
            }
            int sle = lineEnd(pos);
            if (isMarkerAt(contentStart(d, pos, sle), sle)) break;
            if (ind > indent || !isSeqEntry()) {
                // In value position a non-entry line ends the sequence for the
                // outer block (suite 57H4/AZ63/RLU9/S9E8 parity); at statement
                // level it is an error. Nothing consumed yet (pos at line start).
                if (nested) break;
                throw new YamlParseException("expected '-' sequence entry", line);
            }
            pos += ind; // reach '-'
            pos++; // consume '-'
            if (pos < end && (d[pos] == ' ' || d[pos] == '\t')) {
                boolean tabSeparated = false;
                while (pos < end && (d[pos] == ' ' || d[pos] == '\t')) {
                    if (d[pos] == '\t') tabSeparated = true;
                    pos++;
                }
                // Column (not absolute offset) of the item content, for nested indent
                int dashCol = pos - lineStart;
                String rawRest = readLine();
                String rest = stripLine(rawRest);
                // A tab between `-` and a nested `- ` entry is illegal
                // separation (suite Y79Y/004 `/-\t-` and /005; `-` + tab +
                // content stays legal, suite 6BCT parity).
                if (tabSeparated && (rest.equals("-") || rest.startsWith("- ")
                        || rest.startsWith("-\t"))) {
                    throw new YamlParseException("tabs cannot separate nested sequence entries",
                            line - 1);
                }
                elems.add(parseSeqItemContent(rest, indent, dashCol, hasTrailingComment(rawRest), indent));
            } else {
                // Bare '-' (EOL right after): null, or nested block on following lines.
                // Same-indent following entries are siblings, never nested.
                advanceLinePastEol();
                elems.add(nestedBlockOrNull(indent, false));
            }
        }
        return JqArray.of(elems.toArray(new JqValue[0]));
    }

    /**
     * Nested block on following lines, or null when absent (pos restored).
     * When {@code sameIndentSeq}, a sequence entry at exactly {@code indent}
     * counts as nested (mapping-value position: `key:` + `- x`); otherwise only
     * deeper blocks nest (sequence items: bare `-` + `- x` are siblings).
     */
    private JqValue nestedBlockOrNull(int indent, boolean sameIndentSeq) {
        int saved = pos;
        int savedLine = line;
        if (skipBlankAndComments()) {
            int li = peekIndent();
            if (li > indent || (sameIndentSeq && li == indent && isSeqEntry())) {
                // Caller indent is passed through: anchor-only lines prefix the
                // following node without establishing indent (U3XV/SKE5 parity).
                return parseBlockNode(indent, true, true);
            }
        }
        pos = saved;
        line = savedLine;
        return JqNull.NULL;
    }

    /** Consume an expected EOL (pos is at end of line content). */
    private void advanceLinePastEol() {
        if (pos < end && d[pos] == '\r') pos++;
        if (pos < end && d[pos] == '\n') {
            pos++;
            line++;
        } else if (pos < end) {
            throw new YamlParseException("expected end of line", line);
        }
    }

    /**
     * Parse one sequence item whose same-line remainder is {@code rest}.
     * {@code dashCol} is the column just after "- " (for nested indent).
     * {@code foldSeqIndent}: `- ` lines at or below this indent terminate
     * (owned by an enclosing sequence loop); deeper ones fold as text.
     */
    private JqValue parseSeqItemContent(String rest, int indent, int dashCol, boolean commented,
            int foldSeqIndent) {
        // A leading `#` starts a comment: `- # Empty` is an empty (null) item.
        // (Quoted `"#..."` never reaches here starting with `#`.)
        if (!rest.isEmpty() && rest.charAt(0) == '#') {
            rest = "";
        }
        if (rest.isEmpty()) {
            return nestedBlockOrNull(indent, false);
        }
        // `- key: value` opens an inline mapping at the key's column
        if (inlineMapStart(rest)) {
            return parseInlineMap(rest, dashCol, indent, commented);
        }
        // `- - a` nested sequence: the inner dash sits at our content column.
        // Tab separation counts too (suite A2M4: `-` TAB `c` nests).
        if (rest.equals("-") || rest.startsWith("- ") || rest.startsWith("-\t")) {
            return parseInlineSeq(rest.substring(1).stripLeading(), indent, dashCol, commented);
        }
        return parseValueText(rest, indent, false, commented, foldSeqIndent);
    }

    /**
     * True when {@code rest} opens an inline `key:` mapping (a `:` outside quotes
     * and flow brackets, with a non-empty non-flow key).
     */
    private boolean inlineMapStart(String rest) {
        int idx = findMappingColon(rest);
        if (idx < 0) return false;
        String key = rest.substring(0, idx).strip();
        return !key.isEmpty() && key.charAt(0) != '[' && key.charAt(0) != '{'
                && !key.equals("-") && !key.startsWith("? ");
    }

    /** Parse `- - a` style nested sequences (rare but legal).
     * {@code dashCol} is the column of this level's `-` dashes; item content
     * sits two further (single `- ` prefix). `- ` lines at {@code dashCol}
     * belong to this loop; only deeper ones may fold as item text. */
    private JqValue parseInlineSeq(String rest, int indent, int dashCol, boolean firstCommented) {
        var elems = new ArrayList<JqValue>();
        elems.add(parseSeqItemContent(rest, indent, dashCol + 2, firstCommented, dashCol));
        // Following `- ...` lines at the same dash column continue the sequence
        for (;;) {
            int saved = pos;
            int savedLine = line;
            if (!skipBlankAndComments() || peekIndent() != dashCol
                    || classifyLine(pos, lineEnd(pos)) == LINE_MARKER || !isSeqEntry()) {
                pos = saved;
                line = savedLine;
                break;
            }
            int lineStart = pos;
            int p = pos;
            while (p < end && d[p] == ' ') p++;
            pos = p + 1;
            if (pos < end && (d[pos] == ' ' || d[pos] == '\t')) {
                boolean tabSeparated = false;
                while (pos < end && (d[pos] == ' ' || d[pos] == '\t')) {
                    if (d[pos] == '\t') tabSeparated = true;
                    pos++;
                }
                int col = pos - lineStart;
                String rawNext = readLine();
                String next = stripLine(rawNext);
                if (tabSeparated && (next.equals("-") || next.startsWith("- ")
                        || next.startsWith("-\t"))) {
                    throw new YamlParseException("tabs cannot separate nested sequence entries",
                            line - 1);
                }
                elems.add(parseSeqItemContent(next, indent, col, hasTrailingComment(rawNext), dashCol));
            } else {
                advanceLinePastEol();
                elems.add(JqNull.NULL);
            }
        }
        return JqArray.of(elems.toArray(new JqValue[0]));
    }

    private JqObject parseBlockMap(int indent) {
        var builder = JqObject.builder(8);
        Set<String> seen = options.allowDuplicateKeys() ? null : new HashSet<>();
        for (;;) {
            if (!skipBlankAndComments()) break;
            int ind = peekIndent();
            if (ind < indent) break;
            // Tab-indented keys are rejected (suite 4EJS/DK95/06).
            if (!indentClean(ind)) {
                throw new YamlParseException("tabs are not allowed for indentation", line);
            }
            // Deeper keys are rejected (SnakeYAML parity: same-indent alignment
            // is required; cf. "mapping values are not allowed here")
            if (ind > indent) {
                throw new YamlParseException("unexpected indentation in mapping", line);
            }
            if (isSeqEntry()) {
                throw new YamlParseException("expected mapping key, found sequence entry", line);
            }
            pos += ind;
            // Marker check only (a full classify would rescan the line that
            // splitKeyValue scans right after).
            int mle = lineEnd(pos);
            if (isMarkerAt(contentStart(d, pos, mle), mle)) break;
            // Byte-range fast path for plain keys (jjq#96, no key substring).
            int cs2 = contentStart(d, pos, mle);
            byte c0 = d[cs2];
            if (c0 != '?' && c0 != '!' && c0 != '&' && c0 != '*' && c0 != '\''
                    && c0 != '"' && c0 != '{' && c0 != '[' && c0 != ':'
                    && parseBlockMapEntryFast(builder, seen, cs2, mle, indent)) {
                continue;
            }
            String rawRest = readLine();
            String rawLine = stripLine(rawRest);
            if (rawLine.isEmpty()) continue;
            if (rawLine.equals("?") || rawLine.startsWith("? ") || rawLine.startsWith("?\t")) {
                parseExplicitEntry(builder, seen, rawLine, rawRest, indent);
                continue;
            }
            if (rawLine.equals(":") || rawLine.startsWith(": ")) {
                throw new YamlParseException("':' without '?' explicit key", line);
            }
            MapEntry entry = splitKeyValue(rawLine);
            String key = unquoteKey(entry.key());
            if ("<<".equals(key)) {
                applyMerge(builder, entry.value(), indent);
                continue;
            }
            if (seen != null && !seen.add(key)) {
                throw new JqYamlException(key, line - 1);
            }
            builder.put(key, parseMapValue(entry.value(), indent, hasTrailingComment(rawRest)));
        }
        return (JqObject) builder.build();
    }

    /**
     * Parse an inline `- key: value...` mapping: first pair on this line,
     * deeper-or-equal lines join the same mapping.
     */
    private JqValue parseInlineMap(String firstLine, int keyCol, int parentIndent, boolean firstCommented) {
        var builder = JqObject.builder(4);
        Set<String> seen = options.allowDuplicateKeys() ? null : new HashSet<>();
        MapEntry first = splitKeyValue(firstLine);
        String key = unquoteKey(first.key());
        if ("<<".equals(key)) {
            applyMerge(builder, first.value(), parentIndent);
        } else {
            if (seen != null) seen.add(key);
            builder.put(key, parseInlineMapValue(first.value(), keyCol, parentIndent, firstCommented));
        }
        for (;;) {
            int saved = pos;
            int savedLine = line;
            if (!skipBlankAndComments() || peekIndent() < keyCol || isSeqEntry()) {
                pos = saved;
                line = savedLine;
                break;
            }
            int li = peekIndent();
            // Deeper entries are rejected (SnakeYAML parity, like parseBlockMap)
            if (li > keyCol) {
                throw new YamlParseException("unexpected indentation in mapping", line);
            }
            pos += li;
            int mle2 = lineEnd(pos);
            if (isMarkerAt(contentStart(d, pos, mle2), mle2)) {
                pos = saved;
                line = savedLine;
                break;
            }
            String rawRest2 = readLine();
            String rawLine = stripLine(rawRest2);
            if (rawLine.isEmpty()) continue;
            MapEntry entry = splitKeyValue(rawLine);
            String k = unquoteKey(entry.key());
            if ("<<".equals(k)) {
                applyMerge(builder, entry.value(), parentIndent);
                continue;
            }
            if (seen != null && !seen.add(k)) {
                throw new JqYamlException(k, line - 1);
            }
            builder.put(k, parseInlineMapValue(entry.value(), keyCol, parentIndent,
                    hasTrailingComment(rawRest2)));
        }
        return builder.build();
    }

    /**
     * A value inside an inline (`- key: ...`) mapping. Block scalar headers use
     * the key column as their indent base (explicit `|2` counts from the keys,
     * suite 4WA9 parity); everything else uses the outer indent.
     */
    private JqValue parseInlineMapValue(String inline, int keyCol, int parentIndent, boolean commented) {
        if (!inline.isEmpty() && isBlockScalarHeader(inline)) {
            return parseInlineValue(inline, keyCol, true, true);
        }
        return parseMapValue(inline, parentIndent, commented);
    }

    /**
     * Parse an explicit `? key` entry (suite #97: 2XXW/35KP/5WE3/X8DW/S9E8 and
     * siblings). The key fragment folds like a value and must resolve to a
     * string (block scalars unfolded, tags/anchors honored); anything else is
     * a complex key. A following `:` line at the same indent (blank and
     * comment lines skipped) supplies the value, else null.
     */
    private void parseExplicitEntry(JqObject.Builder builder, Set<String> seen, String rawLine,
            String rawRest, int indent) {
        String keyFirst = rawLine.length() == 1 ? "" : rawLine.substring(1).strip();
        // A `- ` entry cannot be an explicit key (suite Y79Y/007).
        if (keyFirst.equals("-") || keyFirst.startsWith("- ") || keyFirst.startsWith("-\t")) {
            throw new YamlParseException("complex mapping keys are not supported", line - 1);
        }
        JqValue keyNode = keyFirst.isEmpty() ? JqString.of("")
                : parseValueText(keyFirst, indent, true, hasTrailingComment(rawRest),
                        Integer.MAX_VALUE);
        if (!keyNode.isString()) {
            throw new YamlParseException("complex mapping keys are not supported", line - 1);
        }
        String key = keyNode.stringValue();
        String vtext = null;
        boolean hasValue = false;
        boolean commentedValue = false;
        boolean valueIsSeq = false;
        int valueDashCol = 0;
        int saved = pos;
        int savedLine = line;
        if (skipBlankAndComments() && peekIndent() == indent) {
            int lineStart = pos;
            int p = lineStart + indent;
            if (p < end && d[p] == ':'
                    && (p + 1 >= end || d[p + 1] == ' ' || d[p + 1] == '\t'
                        || d[p + 1] == '\n' || d[p + 1] == '\r')) {
                pos = p + 1;
                while (pos < end && (d[pos] == ' ' || d[pos] == '\t')) pos++;
                if (pos < end && d[pos] == '-' && (pos + 1 >= end || d[pos + 1] == ' '
                        || d[pos + 1] == '\t' || d[pos + 1] == '\n' || d[pos + 1] == '\r')) {
                    // An explicit value opening with `- ` is a block sequence
                    // (probe: `? k` + `: - one` nests, while implicit
                    // `key: - a` throws in SnakeYAML — deliberate asymmetry).
                    valueDashCol = pos - lineStart;
                    valueIsSeq = true;
                }
                String vraw = readLine();
                vtext = stripLine(vraw);
                commentedValue = hasTrailingComment(vraw);
                hasValue = true;
            } else {
                pos = saved;
                line = savedLine;
            }
        } else {
            pos = saved;
            line = savedLine;
        }
        if ("<<".equals(key)) {
            if (hasValue) {
                applyMerge(builder, vtext, indent);
            }
            return;
        }
        if (seen != null && !seen.add(key)) {
            throw new JqYamlException(key, line - 1);
        }
        JqValue value;
        if (!hasValue) {
            value = JqNull.NULL;
        } else if (valueIsSeq) {
            value = parseExplicitSeqValue(vtext, indent, valueDashCol, commentedValue);
        } else {
            value = parseMapValue(vtext, indent, commentedValue);
        }
        builder.put(key, value);
    }

    /**
     * An explicit `: - ...` value: first item inline, following `- ` lines at
     * the dash column continue (suite 5WE3/A2M4 parity). A tab between the
     * value dash and a nested dash is illegal separation (Y79Y/004 parity).
     */
    private JqValue parseExplicitSeqValue(String vtext, int indent, int dashCol, boolean commented) {
        String rest;
        if (vtext.length() == 1) {
            rest = "";
        } else {
            char sep = vtext.charAt(1);
            rest = vtext.substring(1).stripLeading();
            if (sep == '\t' && (rest.equals("-") || rest.startsWith("- ") || rest.startsWith("-\t"))) {
                throw new YamlParseException("tabs cannot separate nested sequence entries", line - 1);
            }
        }
        return parseInlineSeq(rest, indent, dashCol, commented);
    }

    /**
     * Offset of a ` #`-style comment opener in [s, e), or e. Byte mirror of
     * {@link #stripComment(String)} (quote- and escape-aware, no allocation).
     */
    private static int commentStart(byte[] d, int s, int e) {
        boolean sq = false, dq = false, esc = false;
        for (int i = s; i < e; i++) {
            byte c = d[i];
            if (esc) esc = false;
            else if (c == '\\' && dq) esc = true;
            else if (c == '\'' && !dq) sq = !sq;
            else if (c == '"' && !sq) dq = !dq;
            else if (c == '#' && !sq && !dq && i > s
                    && (d[i - 1] == ' ' || d[i - 1] == '\t')) {
                return i;
            }
        }
        return e;
    }

    /** True when a trailing odd-backslash + tab gap needs the String path (DE56). */
    private static boolean hasTrailingGap(byte[] d, int s, int e) {
        int ge = e;
        while (ge > s && (d[ge - 1] == ' ' || d[ge - 1] == '\t')) ge--;
        int bs = 0;
        while (bs < ge - s && d[ge - 1 - bs] == '\\') bs++;
        if (bs % 2 == 0) return false;
        for (int k = ge; k < e; k++) {
            if (d[k] == '\t') return true;
        }
        return false;
    }

    /**
     * Fast path for plain `key: value` map lines on byte ranges (jjq#96): the
     * key interns from the source range (no key substring), the value takes
     * one substring, and no line String materializes. Returns false (caller
     * runs the String path) for quoted/tagged/anchor/flow/explicit/colon
     * lines, gap-tailed values, and missing separators — all with identical
     * errors. Fully consumes the line on success.
     */
    private boolean parseBlockMapEntryFast(JqObject.Builder builder, Set<String> seen,
            int cs, int le, int indent) {
        int ce = commentStart(d, cs, le);
        int i = findMappingColon(cs, ce);
        if (i < 0) {
            // Same error as splitKeyValue (which reports line-1 post-readLine;
            // nothing is consumed yet here, so plain `line`).
            throw new YamlParseException("expected ':' in mapping entry", line);
        }
        int ks = cs, ke = i;
        while (ke > ks && (d[ke - 1] == ' ' || d[ke - 1] == '\t')) ke--;
        if (ke <= ks) return false;
        int vs = i + 1, ve = ce;
        while (vs < ve && (d[vs] == ' ' || d[vs] == '\t')) vs++;
        while (ve > vs && (d[ve - 1] == ' ' || d[ve - 1] == '\t')) ve--;
        if (hasTrailingGap(d, vs, ve)) return false;
        String key = JqValues.internFieldName(d, ks, ke);
        boolean commented = ce < le;
        // Consume the entry line BEFORE parsing the value (nested parsing
        // reports absolute lines, mirroring readLine).
        pos = le;
        if (pos < end) {
            if (d[pos] == '\r' && pos + 1 < end && d[pos + 1] == '\n') pos++;
            pos++;
            line++;
        }
        if ("<<".equals(key)) {
            applyMerge(builder, new String(d, vs, ve - vs, java.nio.charset.StandardCharsets.UTF_8),
                    indent);
        } else {
            if (seen != null && !seen.add(key)) {
                throw new JqYamlException(key, line - 1);
            }
            JqValue deferred = tryDeferPlainString(vs, ve, indent);
            builder.put(key, deferred != null ? deferred
                    : parseMapValue(new String(d, vs, ve - vs, java.nio.charset.StandardCharsets.UTF_8),
                            indent, commented));
        }
        return true;
    }

    /**
     * A plain single-line value that needs no processing decodes lazily from
     * the source range (jjq#96): no value substring, no conversion scans.
     * Deferral is conservative — anything ambiguous falls through (null return)
     * to the materializing path with identical results:
     * <ul>
     *   <li>first byte must be an ASCII letter outside bool initials
     *       ({@code t,f,y,n,o} case-insensitive go the converting route),
     *       {@code _}, {@code $}, or non-ASCII (never a keyword/number);</li>
     *   <li>a top-level {@code : } still rejects (ZCZ6 parity);</li>
     *   <li>single-quoted doubling and backslash escapes never reach here
     *       (only plain values qualify — the deferred path cannot unescape
     *       YAML doubling).</li>
     * </ul>
     */
    private JqValue tryDeferPlainString(int vs, int ve, int indent) {
        if (vs >= ve) return null;
        byte c0 = d[vs];
        if (c0 >= 0x80 || c0 == '_' || c0 == '$') {
            // Cannot be a keyword, number, or indicator: defer.
        } else if ((c0 >= 'a' && c0 <= 'z') || (c0 >= 'A' && c0 <= 'Z')) {
            switch (c0 | 0x20) {
                case 't', 'f', 'y', 'n', 'o':
                    return null;
                default:
                    break;
            }
        } else {
            return null;
        }
        // A top-level separator inside still rejects (never silently kept).
        boolean hasColon = false;
        for (int k = vs; k < ve; k++) {
            if (d[k] == ':') {
                hasColon = true;
                break;
            }
        }
        if (hasColon && findMappingColon(vs, ve) >= 0) return null;
        // A value that continues on following lines cannot defer (folding
        // would join them): only single-line values qualify.
        if (valueContinues(indent)) return null;
        return JqString.deferredBytes(d, vs, ve, false);
    }

    /**
     * True when a plain value at {@code indent} would fold following lines
     * (same rules as the materializing path, read-only): a deeper plain line,
     * paragraph or direct. Sequence lines never continue map values.
     */
    private boolean valueContinues(int indent) {
        int saved = pos;
        int savedLine = line;
        try {
            if (!skipBlankAndComments()) return false;
            int li = peekIndent();
            if (li <= indent) return false;
            int le = lineEnd(pos);
            return classifyLine(pos, le) == LINE_PLAIN;
        } finally {
            pos = saved;
            line = savedLine;
        }
    }

    /** Split a `key: value` line at the first top-level `: ` (flow-aware). */
    private MapEntry splitKeyValue(String rawLine) {
        int i = findMappingColon(rawLine);
        if (i < 0) {
            throw new YamlParseException("expected ':' in mapping entry", line - 1);
        }
        String key = rawLine.substring(0, i).strip();
        if (key.startsWith("{") || key.startsWith("[")) {
            throw new YamlParseException("complex mapping keys are not supported", line - 1);
        }
        return new MapEntry(key, rawLine.substring(i + 1).strip());
    }

    private record MapEntry(String key, String value) {}

    /** Strip quotes from a mapping key (single/double), else resolve prefixes. */
    private String unquoteKey(String key) {
        String t = key.strip();
        if (t.length() >= 2) {
            char q = t.charAt(0);
            if ((q == '\'' || q == '"') && t.charAt(t.length() - 1) == q) {
                return unquoteScalar(t.substring(1, t.length() - 1), q);
            }
        }
        return resolveKeyPrefixes(t);
    }

    /**
     * Strip leading tag (`!...`) and anchor (`&name`) prefixes from a mapping key.
     * Anchors on keys register the final key string (suite 7BMT/ZH7C/E76Z parity);
     * alias keys (`*name`) resolve to the anchored scalar (suite 26DV parity).
     * Quoted keys never reach here (handled above).
     */
    private String resolveKeyPrefixes(String key) {
        // Fast path: ordinary keys intern directly (single comparison).
        if (!key.isEmpty()) {
            char c0 = key.charAt(0);
            if (c0 != '!' && c0 != '&' && c0 != '*') {
                return internKey(key);
            }
        }
        String t = key;
        String anchorName = null;
        for (;;) {
            if (t.startsWith("!")) {
                int sp = indexOfWs(t);
                // A lone tag coerces its empty to the tagged type (str -> "",
                // suite WZ62 parity with LE5A values).
                if (sp < 0) {
                    return "str".equals(normalizeTag(t)) ? internKey("") : internKey(t);
                }
                t = t.substring(sp).strip();
            } else if (t.startsWith("&")) {
                int sp = indexOfWs(t);
                if (sp < 0) return internKey(t);
                anchorName = t.substring(1, sp);
                t = t.substring(sp).strip();
            } else if (t.startsWith("*")) {
                // Anchors cannot sit on alias keys either (suite SU74: `&b *alias`).
                if (anchorName != null) {
                    throw new YamlParseException("anchor on alias", line);
                }
                int sp = indexOfWs(t);
                String alias = sp < 0 ? t.substring(1) : t.substring(1, sp);
                JqValue v = anchors.get(alias);
                if (v != null && v.isString()) {
                    String rest = sp < 0 ? "" : t.substring(sp).strip();
                    if (rest.isEmpty()) {
                        return internKey(v.stringValue());
                    }
                }
                return internKey(t);
            } else {
                break;
            }
        }
        // Bare remainder, possibly quoted (`&a6 'key6'`): unquote if needed
        String resolved = t;
        if (t.length() >= 2) {
            char q = t.charAt(0);
            if ((q == '\'' || q == '"') && t.charAt(t.length() - 1) == q) {
                resolved = unquoteScalar(t.substring(1, t.length() - 1), q);
            }
        }
        String interned = internKey(resolved);
        if (anchorName != null) {
            anchors.put(anchorName, JqString.of(resolved));
        }
        return interned;
    }

    /** Index of first space/tab, or -1. */
    private static int indexOfWs(String t) {
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == ' ' || c == '\t') return i;
        }
        return -1;
    }

    /** Intern a mapping key through the shared table (reference equality downstream). */
    private static String internKey(String key) {
        return JqValues.internFieldName(key);
    }

    /**
     * Parse a mapping value's first-line text: nested block, gathered
     * multi-line scalar, or single-line value. {@code indent} is the enclosing map's.
     * A same-indent sequence entry nests (indentless `key:` + `- x`).
     */
    private JqValue parseMapValue(String inline, int indent, boolean commented) {
        if (inline.isEmpty()) {
            return nestedBlockOrNull(indent, true);
        }
        // Map values never fold `- ` lines (conservative: same-or-deeper
        // entries terminate for the outer loops to judge).
        return parseValueText(inline, indent, true, commented, Integer.MAX_VALUE);
    }

    /**
     * Parse a first-line value fragment: gather quoted/flow continuations,
     * fold plain continuations, then dispatch through prefixes.
     * {@code sameIndentSeq} selects the nested-block rule (mapping values nest
     * same-indent sequences; sequence items treat them as siblings).
     */
    private JqValue parseValueText(String first, int indent, boolean sameIndentSeq, boolean firstCommented,
            int foldSeqIndent) {
        char c0 = first.charAt(0);
        if (c0 == '"' || c0 == '\'' || c0 == '{' || c0 == '[') {
            return parseInlineValue(gatherValueText(first, indent, true), indent, sameIndentSeq, true);
        }
        if (c0 == '|' || c0 == '>' || c0 == '&' || c0 == '*' || c0 == '!') {
            return parseInlineValue(first, indent, sameIndentSeq, true);
        }
        // A trailing comment completes the scalar: no folding past it (BF9H parity)
        String folded = firstCommented ? null : foldPlainContinuation(first, indent, foldSeqIndent);
        if (folded != null) {
            return convertScalar(folded, null, line);
        }
        return convertScalar(first, null, line);
    }

    /**
     * Fold following deeper-indented lines into a plain scalar. Returns null when
     * there is no continuation. Comment lines terminate (SnakeYAML parity);
     * markers, explicit keys and new mapping entries do too. A deeper `- ` line
     * folds as literal text (suite AB8U + probe parity); same-or-less indented
     * entries terminate. A trailing comment on a consumed line completes the
     * scalar (suite BF9H: nothing may follow a comment-terminated plain line).
     */
    private String foldPlainContinuation(String first, int indent, int foldSeqIndent) {
        StringBuilder sb = null;
        boolean pendingBlank = false;
        for (;;) {
            if (pos >= end) break;
            int le = lineEnd(pos);
            int kind = classifyLine(pos, le);
            if (kind == LINE_BLANK) {
                // Paragraph break only if deeper content follows (look past the break)
                int q = le;
                if (q < end && d[q] == '\r') q++;
                if (q < end && d[q] == '\n') q++;
                int qi = 0;
                while (q + qi < end && d[q + qi] == ' ') qi++;
                if (q + qi < end && d[q + qi] != '\n' && d[q + qi] != '\r' && qi > indent) {
                    pendingBlank = true;
                    advanceTo(q);
                    continue;
                }
                break;
            }
            int cs;
            if (kind == LINE_SEQ) {
                // Deeper entries fold as text (kept with their `- ` prefix);
                // entries at or below the fold indent terminate instead.
                cs = contentStart(d, pos, le);
                if (cs - pos <= foldSeqIndent) break;
            } else {
                if (kind != LINE_PLAIN) break;
                cs = contentStart(d, pos, le);
                if (cs - pos <= indent) break;
            }
            if (sb == null) {
                sb = new StringBuilder(first);
            }
            if (pendingBlank) {
                sb.append('\n');
                pendingBlank = false;
            } else {
                sb.append(' ');
            }
            pos = cs;
            String line = readLine();
            sb.append(stripComment(line).strip());
            if (hasTrailingComment(line)) {
                break;
            }
        }
        return sb == null ? null : sb.toString();
    }

    /**
     * Strip a value first-line: comment + surrounding whitespace, except a
     * trailing odd-backslash + tab-bearing gap is restored as a `\t` escape
     * (suite DE56/02,03: `\` + TAB at line end is literal, and downstream
     * escape handling decodes it; plain values are unaffected since the rule
     * only fires for quote-starting lines and backslash-bearing tails).
     */
    private static String stripLine(String raw) {
        String noComment = stripComment(raw);
        String s = noComment.strip();
        if (s.isEmpty() || (s.charAt(0) != '"' && s.charAt(0) != '\'')) return s;
        int e = noComment.length();
        while (e > 0 && (noComment.charAt(e - 1) == ' ' || noComment.charAt(e - 1) == '\t')) e--;
        int bs = 0;
        while (bs < e && noComment.charAt(e - 1 - bs) == '\\') bs++;
        String ws = noComment.substring(e);
        if (bs % 2 == 1 && ws.indexOf('\t') >= 0) {
            int st = 0;
            while (st < e - bs
                    && (noComment.charAt(st) == ' ' || noComment.charAt(st) == '\t')) st++;
            return noComment.substring(st, e - bs) + "\\t";
        }
        return s;
    }

    /** True when the line carries a trailing ` #comment` (outside quotes). */
    private static boolean hasTrailingComment(String line) {
        // Fast path: most lines hold no '#' at all (single intrinsic scan).
        if (line.indexOf('#') < 0) return false;
        boolean sq = false, dq = false, esc = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (esc) esc = false;
            else if (c == '\\' && dq) esc = true;
            else if (c == '\'' && !dq) sq = !sq;
            else if (c == '"' && !sq) dq = !dq;
            else if (c == '#' && !sq && !dq && i > 0
                    && (line.charAt(i - 1) == ' ' || line.charAt(i - 1) == '\t')) {
                return true;
            }
        }
        return false;
    }

    /** Move pos to q, counting any line breaks crossed. */
    private void advanceTo(int q) {
        while (pos < q) {
            if (d[pos] == '\n') line++;
            else if (d[pos] == '\r') {
                line++;
                if (pos + 1 < q && d[pos + 1] == '\n') pos++;
            }
            pos++;
        }
    }

    /** Apply a `<<` merge value (alias or sequence of aliases to mappings). */
    private void applyMerge(JqObject.Builder builder, String inline, int indent) {
        JqValue mergeValue = inline.isEmpty() ? parseNestedMerge(indent) : parseInlineValue(inline, indent, true, true);
        if (mergeValue instanceof JqObject obj) {
            obj.forEach(builder::put);
        } else if (mergeValue instanceof JqArray arr) {
            for (JqValue item : arr) {
                if (item instanceof JqObject obj) {
                    obj.forEach(builder::put);
                }
            }
        }
    }

    /** A `<<` with no inline value takes the nested block on following lines. */
    private JqValue parseNestedMerge(int indent) {
        int saved = pos;
        int savedLine = line;
        if (skipBlankAndComments() && peekIndent() > indent) {
            return parseBlockNode(indent, true, true);
        }
        pos = saved;
        line = savedLine;
        return JqNull.NULL;
    }

    // ========================================================================
    //  Inline values and multi-line gathering
    // ========================================================================

    /**
     * Complete a first-line fragment that may continue on following lines:
     * quoted scalars (until the closing quote), flow collections (until balanced),
     * anything else returned as-is. Joins flow lines with a space (YAML folding).
     */
    private String gatherValueText(String first, int indent, boolean quotedGate) {
        if (first.isEmpty()) return first;
        char c0 = first.charAt(0);
        if (c0 == '"' || c0 == '\'') {
            checkQuotedTrailing(first, c0);
            return gatherQuoted(first, c0, indent, quotedGate);
        }
        if (c0 == '{' || c0 == '[') {
            return gatherFlow(first, indent);
        }
        return first;
    }

    /**
     * Reject trailing content after a quoted scalar closed on its first line
     * (suite Q4CL/JY7Z/ZL4Z parity: `"quoted2" trailing`, `}in: valid`-class
     * junk). Callers pre-strip comments, so any remainder is real junk —
     * including `#...` without separation space (suite SU5Z parity).
     */
    private static void checkQuotedTrailing(String first, char quote) {
        int close = closingQuote(first, quote, 1);
        if (close >= 0 && !first.substring(close + 1).isBlank()) {
            throw new YamlParseException("trailing content after quoted scalar", -1);
        }
    }

    /** Index of the closing quote (escape-aware, `''`-aware), or -1. */
    private static int closingQuote(String text, char quote, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote == '"' && c == '\\') {
                i++;
            } else if (c == quote) {
                if (quote == '\'' && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Gather a possibly multi-line quoted scalar; folding like plain scalars.
     * Quotes delimit unambiguously: consume until close (blank and comment
     * lines inside quotes are content), erroring only at EOF. A trailing
     * backslash escapes the line break itself (double quotes only), joining
     * the next line directly; other breaks fold to a space. A document marker
     * line aborts gathering (suite 5TRB: markers recognized mid-quote). */
    /**
     * Gather continuations. When {@code gate} (map/sequence values only — bare
     * document scalars are exempt, suite 7A4E/NP9H/Q8AD/PRH3/TL85/6WPF/9MQT/KSS4
     * vs QB6E/JKF3/DK95 parity), non-blank continuations must stay deeper than
     * the enclosing indent (spaces only; suite DK95/01), except a lone closing
     * quote line (suite 6WPF).
     */
    private String gatherQuoted(String first, char quote, int indent, boolean gate) {
        if (isClosedQuote(first, quote)) return first;
        String continued = quote == '"' ? stripContinuation(first) : null;
        StringBuilder sb = new StringBuilder(continued != null ? continued : first);
        boolean prevContinued = continued != null;
        boolean prevBreak = false;
        for (;;) {
            if (pos >= end) {
                throw new YamlParseException("unterminated quoted scalar", line);
            }
            if (classifyLine(pos, lineEnd(pos)) == LINE_MARKER) {
                throw new YamlParseException("document marker inside quoted scalar", line);
            }
            int le = lineEnd(pos);
            int kind = classifyLine(pos, le);
            String rawLine = readLine();
            String content = rawLine.strip();
            // A trailing backslash followed by a tab is a literal gap
            // (`\<TAB>` escape at line end, suite DE56/02,03 — SnakeYAML-load
            // throws here, suite oracle wins): drop the backslash, keep the
            // tab, and fold normally (no continuation). Plain trailing
            // backslashes still continue (565N parity).
            boolean literalGap = false;
            {
                int re = rawLine.length();
                while (re > 0 && rawLine.charAt(re - 1) == ' ') re--;
                if (re >= 2 && rawLine.charAt(re - 1) == '\t'
                        && rawLine.charAt(re - 2) == '\\'
                        && (re < 3 || rawLine.charAt(re - 3) != '\\')) {
                    int gs = 0;
                    while (gs < re - 2 && (rawLine.charAt(gs) == ' ' || rawLine.charAt(gs) == '\t')) {
                        gs++;
                    }
                    content = rawLine.substring(gs, re - 2) + '\t';
                    literalGap = true;
                }
            }
            // A zero-indent continuation at root indent ends the quote region
            // (suite QB6E/JKF3 parity: `"a` + `b` at base is an error; tabs do
            // not count toward indent, suite DK95/01 parity). Blanks and
            // comment lines inside quotes are content, as is a lone
            // closing-quote line (suite 6WPF parity).
            if (gate && kind != LINE_BLANK && kind != LINE_COMMENT
                    && !content.equals("\"") && !content.equals("'")) {
                int ci = 0;
                while (ci < rawLine.length() && rawLine.charAt(ci) == ' ') ci++;
                if (ci <= indent) {
                    throw new YamlParseException("under-indented quoted continuation", line - 1);
                }
            }
            continued = literalGap ? null : quote == '"' ? stripContinuation(content) : null;
            if (continued != null) {
                sb.append(continued);
                prevBreak = false;
            } else if (content.isEmpty()) {
                sb.append('\n');
                prevBreak = true;
            } else if (prevContinued || prevBreak) {
                sb.append(content);
                prevBreak = false;
            } else {
                sb.append(' ').append(content);
                prevBreak = false;
            }
            prevContinued = continued != null;
            String joined = sb.toString();
            if (isClosedQuote(joined, quote)) return joined;
            // Keep the raw text for the next iteration (re-scan from the joined form)
            first = joined;
            sb = new StringBuilder(joined);
        }
    }

    /**
     * Strip one trailing backslash for line continuation, or return null when
     * the line does not continue (even backslash runs are literals).
     */
    private static String stripContinuation(String content) {
        int bs = 0;
        while (bs < content.length() && content.charAt(content.length() - 1 - bs) == '\\') {
            bs++;
        }
        if (bs % 2 == 0) return null;
        return content.substring(0, content.length() - 1);
    }

    /** True when the text holds a complete quoted scalar (closing quote present). */
    private static boolean isClosedQuote(String text, char quote) {
        if (text.length() < 2 || text.charAt(0) != quote) return false;
        if (quote == '\'') {
            // Closed when a lone trailing ' exists ('' is an escaped quote)
            int i = 1;
            while (i < text.length()) {
                if (text.charAt(i) == '\'') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                        i += 2;
                        continue;
                    }
                    return i == text.length() - 1 || text.charAt(i + 1) != '\'';
                }
                i++;
            }
            return false;
        }
        boolean esc = false;
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (esc) esc = false;
            else if (c == '\\') esc = true;
            else if (c == '"') return true;
        }
        return false;
    }

    /** Gather a possibly multi-line flow collection until brackets balance.
     * Blank lines inside flow are skipped; comment-only lines are rejected
     * (suite CML9 parity; suite 7TMG's comma-following comment stays legal
     * only when the flow remains valid — see below). A bare `:` must not open
     * a continuation line at or below the enclosing indent (suite VJP3/00 vs
     * VJP3/01 parity). Continuation indent itself is lenient (SnakeYAML parity;
     * suite 9C9N stays documented-lenient since UT92/C2DT need the leniency).
     * Tab-indented content lines are rejected, while bracket-only tab lines
     * stay tolerated (suite Y79Y/003 vs 6CA3 parity). */
    private String gatherFlow(String first, int indent) {
        if (flowDepth(first) == 0) return first;
        StringBuilder sb = new StringBuilder(first);
        for (;;) {
            if (pos >= end) {
                throw new YamlParseException("unbalanced flow collection", line);
            }
            int le = lineEnd(pos);
            int kind = classifyLine(pos, le);
            if (kind == LINE_BLANK) {
                advanceLine();
                continue;
            }
            if (kind == LINE_COMMENT) {
                // Comment lines are skipped only at a comma/bracket boundary
                // (probe: `[ word1` + `# c` + `, word2]` parses but
                // `[ word1` + `# c` + `word2 ]` throws, suite CML9 vs 7TMG).
                String soFar = sb.toString().stripTrailing();
                int nl = le;
                if (nl < end && d[nl] == '\r') nl++;
                if (nl < end && d[nl] == '\n') nl++;
                int ne = nl;
                while (ne < end && d[ne] != '\n' && d[ne] != '\r') ne++;
                String nextLine = new String(d, nl, ne - nl, StandardCharsets.UTF_8).strip();
                boolean prevOk = !soFar.isEmpty() && ",[{".indexOf(soFar.charAt(soFar.length() - 1)) >= 0;
                boolean nextOk = !nextLine.isEmpty() && ",]}".indexOf(nextLine.charAt(0)) >= 0;
                if (!prevOk && !nextOk) {
                    throw new YamlParseException("comments are not allowed inside flow collections",
                            line);
                }
                advanceLine();
                continue;
            }
            if (kind == LINE_MARKER) {
                throw new YamlParseException("unbalanced flow collection", line);
            }
            String rawLine = readLine();
            String content = rawLine.strip();
            // Tab-indented content is rejected (suite Y79Y/003); lines holding
            // only brackets/commas stay tab-tolerant (suite 6CA3).
            if (!content.isEmpty() && rawLine.charAt(0) == '\t' && !isFlowPunctOnly(content)) {
                throw new YamlParseException("tabs are not allowed for indentation in flow", line);
            }
            String joined = stripComment(rawLine).strip();
            // A `:`-opening continuation re-splits key and separator. Pending
            // keys (since the last top-level comma or the opener) that are
            // quoted rejoin unless glued without space in sequences (suite 4MUZ
            // vs ZXT5/5MUD); plain keys never split in sequences (suite DK4H)
            // but rejoin in mappings (suite 4MUZ/02); a bare `:` needs depth
            // (suite VJP3/00 vs /01).
            if (joined.startsWith(":")) {
                boolean bare = joined.length() == 1;
                boolean seqOuter = sb.length() > 0 && sb.charAt(0) == '[';
                boolean quotedKey = isPendingKeyQuoted(sb);
                if (quotedKey) {
                    if (!bare && seqOuter
                            && joined.charAt(1) != ' ' && joined.charAt(1) != '\t') {
                        throw new YamlParseException(
                                "flow mapping separator glued to value", line);
                    }
                } else if (!bare && seqOuter) {
                    throw new YamlParseException(
                            "flow mapping separator on its own line", line);
                } else if (bare) {
                    int li = 0;
                    while (li < rawLine.length()
                            && (rawLine.charAt(li) == ' ' || rawLine.charAt(li) == '\t')) li++;
                    if (li <= indent) {
                        throw new YamlParseException(
                                "flow mapping separator on its own line", line);
                    }
                }
            }
            if (joined.endsWith("\\")) {
                sb.append(joined, 0, joined.length() - 1);
            } else {
                sb.append(' ').append(joined);
            }
            if (flowDepth(sb.toString()) == 0) return sb.toString();
        }
    }

    /** True when the text holds only flow brackets/commas (tab-tolerant lines). */
    private static boolean isFlowPunctOnly(String content) {
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c != '[' && c != ']' && c != '{' && c != '}' && c != ',') return false;
        }
        return !content.isEmpty();
    }

    /** True when the pending flow key (since the last top-level comma or the
     * opener) starts with a quote (suite 4MUZ vs DK4H). */
    private static boolean isPendingKeyQuoted(StringBuilder sb) {
        int depth = 0;
        boolean sq = false, dq = false, esc = false;
        int fragStart = 0;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (esc) {
                esc = false;
            } else if (c == '\\' && dq) {
                esc = true;
            } else if (c == '\'' && !dq) {
                sq = !sq;
            } else if (c == '"' && !sq) {
                dq = !dq;
            } else if (!sq && !dq) {
                if (c == '[' || c == '{') {
                    if (depth == 0) fragStart = i + 1;
                    depth++;
                } else if (c == ']' || c == '}') {
                    depth--;
                } else if (c == ',' && depth == 1) {
                    fragStart = i + 1;
                }
            }
        }
        int k = fragStart;
        while (k < sb.length() && (sb.charAt(k) == ' ' || sb.charAt(k) == '\t')) k++;
        return k < sb.length() && (sb.charAt(k) == '\'' || sb.charAt(k) == '"');
    }

    /** Net bracket depth of flow characters, quote-aware. Negative clamps to -1 (over-close). */
    private static int flowDepth(String text) {
        boolean sq = false, dq = false, esc = false;
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (esc) {
                esc = false;
                continue;
            }
            if (c == '\\' && dq) {
                esc = true;
                continue;
            }
            if (c == '\'' && !dq) sq = !sq;
            else if (c == '"' && !sq) dq = !dq;
            else if (!sq && !dq) {
                if (c == '[' || c == '{') depth++;
                else if (c == ']' || c == '}') depth--;
            }
        }
        return depth;
    }

    /** Parse a same-line value with tag/anchor/alias prefixes. {@code indent} is the enclosing context.
     * {@code quotedGate} enables the quoted-continuation indent gate (map/sequence
     * values only; bare scalars exempt). */
    private JqValue parseInlineValue(String text, int indent, boolean sameIndentSeq, boolean quotedGate) {
        String t = text.strip();
        String tag = null;
        String anchorName = null;
        while (t.startsWith("!") || t.startsWith("&") || t.startsWith("*")) {
            if (t.startsWith("*")) {
                // Anchors cannot sit on aliases (suite SR86: `&b *a`).
                if (anchorName != null) {
                    throw new YamlParseException("anchor on alias", line);
                }
                String name = t.substring(1).split("\\s")[0];
                JqValue aliased = anchors.get(name);
                if (aliased == null) {
                    throw new YamlParseException("unknown anchor '" + name + "'", line);
                }
                return aliased;
            }
            int sp = indexOfSplitBlind(t);
            String token = sp < 0 ? t : t.substring(0, sp);
            String rest = sp < 0 ? "" : stripLine(t.substring(sp));
            if (token.startsWith("&")) {
                if (anchorName != null || outerValueAnchorPending) {
                    throw new YamlParseException("duplicate anchor", line);
                }
                anchorName = token.substring(1);
                if (rest.isEmpty()) {
                    boolean savedPending = outerValueAnchorPending;
                    outerValueAnchorPending = true;
                    try {
                        JqValue value = parseAnchoredNested(indent, sameIndentSeq);
                        anchors.put(anchorName, value);
                        return value;
                    } finally {
                        outerValueAnchorPending = savedPending;
                    }
                }
                t = rest;
                continue;
            }
            tag = token;
            validateTag(token);
            t = rest;
            if (t.isEmpty()) {
                JqValue value = parseAnchoredNested(indent, sameIndentSeq);
                if (anchorName != null) anchors.put(anchorName, value);
                // An explicit `!!str` with no value is an empty string
                // (suite LE5A); other tags fall through to null.
                if (value.isNull() && "str".equals(normalizeTag(tag))) {
                    return JqString.of("");
                }
                return value;
            }
        }
        // A block sequence cannot open on the same line after an anchor/tag
        // (suite SY6V: `&anchor - sequence entry`).
        if ((anchorName != null || tag != null) && (t.equals("-") || t.startsWith("- ")
                || t.startsWith("-\t"))) {
            throw new YamlParseException("block sequence cannot follow anchor/tag on the same line", line);
        }
        t = gatherValueText(t, indent, quotedGate);
        JqValue value;
        if (startsBlockScalar(t)) {
            value = parseBlockScalar(t, indent);
        } else if (t.startsWith("{")) {
            value = parseFlowMap(t);
        } else if (t.startsWith("[")) {
            value = parseFlowSeq(t);
        } else if (t.length() >= 2 && ((t.startsWith("'") && t.endsWith("'"))
                || (t.startsWith("\"") && t.endsWith("\"")))) {
            value = JqString.of(unquoteScalar(t.substring(1, t.length() - 1), t.charAt(0)));
        } else {
            value = convertScalar(t, tag, line);
        }
        if (anchorName != null) anchors.put(anchorName, value);
        return value;
    }

    /** Index of the first space/tab outside quotes, or -1. */
    private int indexOfSplit(String t) {
        boolean sq = false, dq = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\'' && !dq) sq = !sq;
            else if (c == '"' && !sq) dq = !dq;
            else if ((c == ' ' || c == '\t') && !sq && !dq) return i;
        }
        return -1;
    }

    /**
     * Index of the first space/tab, quotes ignored: anchor/tag names may
     * contain quotes and colons (suite W5VH: `&:@*!$"<foo>:`).
     */
    private static int indexOfSplitBlind(String t) {
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == ' ' || c == '\t') return i;
        }
        return -1;
    }

    /** An anchored/tagged value whose content is the nested block on following lines. */
    private JqValue parseAnchoredNested(int indent, boolean sameIndentSeq) {
        return nestedBlockOrNull(indent, sameIndentSeq);
    }

    private boolean startsBlockScalar(String t) {
        return t.startsWith("|") || t.startsWith(">");
    }

    /**
     * Parse a literal (`|`) or folded (`>`) block scalar header plus content lines.
     * Header: `|`/`>` + optional chomping (`+`/`-`) + optional indent digit.
     */
    private JqValue parseBlockScalar(String header, int indent) {
        char style = header.charAt(0);
        char chomp = ' '; // ' ' = clip, '-' = strip, '+' = keep
        int explicitIndent = 0;
        int i = 1;
        while (i < header.length()) {
            char c = header.charAt(i);
            if (c == '+' || c == '-') chomp = c;
            else if (c >= '1' && c <= '9') explicitIndent = c - '0';
            else if (c == ' ' || c == '\t' || c == '#') break;
            else throw new YamlParseException("invalid block scalar header", line);
            i++;
        }
        // After the indicators only separation space (then an optional comment)
        // may follow: `> first line` is content on the header line (suite S4GJ)
        // and `># comment` lacks the separation space (suite X4QW). A `#` only
        // starts a comment after space/tab; anything else is junk.
        if (i < header.length()) {
            char c = header.charAt(i);
            if (c == '#') {
                throw new YamlParseException("invalid block scalar header", line);
            }
            int j = i;
            while (j < header.length() && (header.charAt(j) == ' ' || header.charAt(j) == '\t')) j++;
            if (j < header.length() && header.charAt(j) != '#') {
                throw new YamlParseException("content on block scalar header line", line);
            }
        }
        var content = new ArrayList<String>();
        var blanks = new HashSet<Integer>();
        var keepLines = new HashSet<Integer>();
        var leadingBlankLis = new ArrayList<Integer>();
        int contentIndent = -1;
        // Content must exceed the enclosing indent (doc-root passes -1, so
        // column-0 content is accepted there); explicit digits pin it exactly
        // (absolute columns at doc root, parent-relative when nested).
        int floor = explicitIndent > 0
                ? (indent < 0 ? explicitIndent : indent + explicitIndent)
                : indent + 1;
        for (;;) {
            if (pos >= end) break;
            int saved = pos;
            int savedLine = line;
            // Document markers always terminate block scalar content
            if (classifyLine(pos, lineEnd(pos)) == LINE_MARKER) break;
            int ls = pos;
            while (ls < end && (d[ls] == ' ' || d[ls] == '\t')) ls++;
            boolean blank = ls >= end || d[ls] == '\n' || d[ls] == '\r';
            int fullLi = ls - pos;
            // Content indent counts spaces only: tabs are content (suite 96NN:
            // ` \tbar` strips one space, keeping `\tbar`); termination and
            // keep-lines use the full measure (suite MJS9: `\t bar` stays).
            int li = 0;
            while (li < fullLi && d[pos + li] == ' ') li++;
            if (!blank) {
                if (fullLi < floor) {
                    pos = saved;
                    line = savedLine;
                    break;
                }
                if (contentIndent < 0) {
                    contentIndent = explicitIndent > 0
                            ? (indent < 0 ? explicitIndent : indent + explicitIndent)
                            : li;
                    // Leading blanks must not exceed the first content indent
                    // (suite S98Z/5LLU/W9L4 parity; spaces-only measure keeps
                    // the R4YG tab remainder passing).
                    for (int bli : leadingBlankLis) {
                        if (bli > contentIndent) {
                            throw new YamlParseException(
                                    "block scalar leading blank line over-indented", line);
                        }
                    }
                }
                if (fullLi < contentIndent) {
                    pos = saved;
                    line = savedLine;
                    break;
                }
            }
            String rawLine = readLine();
            // A tab in first position is illegal indentation (suite Y79Y/000
            // parity); tabs after spaces are content (suite R4YG parity).
            if (!rawLine.isEmpty() && rawLine.charAt(0) == '\t') {
                throw new YamlParseException("tabs are not allowed for indentation", line - 1);
            }
            if (blank) {
                blanks.add(content.size());
                // Store raw; the remainder past the (possibly not yet known)
                // content indent is significant (suite L24T/R4YG parity).
                // Stripped in post-processing below.
                content.add(rawLine);
                if (contentIndent < 0) {
                    int bli = 0;
                    while (bli < rawLine.length() && rawLine.charAt(bli) == ' ') bli++;
                    leadingBlankLis.add(bli);
                }
            } else {
                // rawLine holds leading spaces (pos was at line start):
                // strip up to contentIndent of them (extra indent is
                // significant; tab-first lines keep their tab, MJS9 parity).
                int cut = 0;
                while (cut < contentIndent && cut < rawLine.length()
                        && rawLine.charAt(cut) == ' ') cut++;
                content.add(rawLine.substring(cut));
                // In folded scalars, more-indented lines break folding and are
                // kept with line breaks (suite 6VJK/7T8X/F6MC/MJS9 parity).
                if (style == '>' && fullLi > contentIndent) {
                    keepLines.add(content.size() - 1);
                }
            }
        }
        if (contentIndent < 0) {
            // No content lines at all: indent from the first blank line's
            // leading spaces (tabs stop the scan, suite Y79Y/001 parity),
            // so whitespace-only lines keep their remainder; JEF9's
            // spaces-only blanks strip to empty. Rendering below + chomping
            // decide what survives (JEF9/K858 parity).
            String firstRaw = content.isEmpty() ? "" : content.get(0);
            int li = 0;
            while (li < firstRaw.length() && firstRaw.charAt(li) == ' ') li++;
            contentIndent = li;
        }
        if (content.isEmpty()) {
            return JqString.of("");
        }
        // Strip the content indent from the stored raw blank lines
        for (int b : blanks) {
            String raw = content.get(b);
            int cut = 0;
            while (cut < contentIndent && cut < raw.length() && raw.charAt(cut) == ' ') cut++;
            content.set(b, raw.substring(cut));
        }
        if (chomp != '+') {
            // Trailing blanks with empty remainders are dropped (suite K858
            // clip + `foo: |` + `  x` + `  ` probe parity); non-empty ones
            // survive chomping (L24T/Y79Y parity).
            while (!content.isEmpty()) {
                int last = content.size() - 1;
                if (!blanks.contains(last) || !content.get(last).isEmpty()) break;
                content.remove(last);
                blanks.remove(last);
                keepLines.remove(last);
            }
            if (content.isEmpty()) {
                return JqString.of("");
            }
        }
        StringBuilder sb = new StringBuilder();
        if (style == '|') {
            for (int k = 0; k < content.size(); k++) {
                sb.append(content.get(k)).append('\n');
            }
        } else {
            // Folded (suite 6VJK/7T8X/F6MC/MJS9/R4YG + probes): every line is
            // emitted; the separator folds to a space only between two plain
            // lines, otherwise a break. A middle blank run whose remainders are
            // all empty and which touches no keep line on either side collapses
            // to a single forced break (7T8X `line\nnext` parity); all other
            // blank runs are preserved line by line (with remainders, R4YG).
            // Build runs of content/blank lines first.
            var runBlank = new ArrayList<Boolean>();
            var runStart = new ArrayList<Integer>();
            for (int k = 0; k < content.size(); k++) {
                boolean b = blanks.contains(k);
                if (k == 0 || b != runBlank.get(runBlank.size() - 1)) {
                    runBlank.add(b);
                    runStart.add(k);
                }
            }
            runStart.add(content.size());
            int runs = runBlank.size();
            boolean[] collapse = new boolean[runs];
            int[] empties = new int[runs];
            for (int r = 0; r < runs; r++) {
                if (!runBlank.get(r)) continue;
                int len = runStart.get(r + 1) - runStart.get(r);
                if (r == 0 || r == runs - 1) {
                    // Leading/trailing runs: every blank is an empty line.
                    empties[r] = len;
                    continue;
                }
                boolean allEmpty = true;
                for (int k = runStart.get(r); k < runStart.get(r + 1); k++) {
                    if (!content.get(k).isEmpty()) {
                        allEmpty = false;
                        break;
                    }
                }
                if (!allEmpty) {
                    // Non-empty remainders are always preserved (R4YG probe).
                    empties[r] = len;
                    continue;
                }
                // Empty-remainder middle runs (probes + suite): a lone blank
                // with no keep neighbor collapses to just the break;
                // multi-blank runs keep n-1 empties; keep-adjacency adds one.
                int prevLine = runStart.get(r) - 1;
                int nextLine = runStart.get(r + 1);
                boolean keepAdj = keepLines.contains(prevLine) || keepLines.contains(nextLine);
                if (len == 1 && !keepAdj) {
                    collapse[r] = true;
                } else {
                    empties[r] = len - 1 + (keepAdj ? 1 : 0);
                }
            }
            boolean needSep = false;
            boolean prevNormal = false;
            for (int r = 0; r < runs; r++) {
                if (collapse[r]) {
                    sb.append('\n');
                    needSep = false;
                    prevNormal = false;
                    continue;
                }
                if (runBlank.get(r)) {
                    for (int j = 0; j < empties[r]; j++) {
                        int k = runStart.get(r) + j;
                        if (needSep) sb.append('\n');
                        sb.append(content.get(k));
                        needSep = true;
                        prevNormal = false;
                    }
                    continue;
                }
                for (int k = runStart.get(r); k < runStart.get(r + 1); k++) {
                    boolean keep = keepLines.contains(k);
                    if (needSep) {
                        sb.append(prevNormal && !keep ? ' ' : '\n');
                    }
                    sb.append(content.get(k));
                    needSep = true;
                    prevNormal = !keep;
                }
            }
            sb.append('\n');
        }
        String body = sb.toString();
        if (chomp == '-') {
            int e = body.length();
            while (e > 0 && body.charAt(e - 1) == '\n') e--;
            body = body.substring(0, e);
        } else if (chomp == ' ') {
            int e = body.length();
            while (e > 0 && body.charAt(e - 1) == '\n') e--;
            body = body.substring(0, e) + "\n";
        }
        return JqString.of(body);
    }

    /** Parse a `{k: v, ...}` flow mapping (complete text, possibly multi-line joined). */
    private JqValue parseFlowMap(String text) {
        checkFlowText(text);
        FlowCursor c = new FlowCursor(text, 1);
        var builder = JqObject.builder(4);
        Set<String> seen = options.allowDuplicateKeys() ? null : new HashSet<>();
        c.skipWs();
        if (c.peek() == '}') return builder.build();
        for (;;) {
            c.skipWs();
            String key = c.flowKey();
            c.skipWs();
            JqValue value;
            if (c.peek() == ',' || c.peek() == '}') {
                // Valueless entry (suite 8KB6/9BXH).
                value = JqNull.NULL;
            } else {
                if (c.next() != ':') {
                    throw new YamlParseException("expected ':' in flow mapping", line);
                }
                c.skipWs();
                value = c.flowValue();
            }
            if ("<<".equals(key)) {
                mergeFlowValue(builder, value);
            } else {
                if (seen != null && !seen.add(key)) {
                    throw new JqYamlException(key, line);
                }
                builder.put(key, value);
            }
            c.skipWs();
            char ch = c.next();
            if (ch == '}') break;
            if (ch != ',') {
                throw new YamlParseException("expected ',' or '}' in flow mapping", line);
            }
            // Trailing comma before close is legal (suite 5KJE/UDR7 parity)
            c.skipWs();
            if (c.peek() == '}') {
                c.next();
                break;
            }
        }
        return builder.build();
    }

    private void mergeFlowValue(JqObject.Builder builder, JqValue value) {
        if (value instanceof JqObject obj) {
            obj.forEach(builder::put);
        } else if (value instanceof JqArray arr) {
            for (JqValue item : arr) {
                if (item instanceof JqObject obj) {
                    obj.forEach(builder::put);
                }
            }
        }
    }

    /** Parse a `[a, b]` flow sequence (complete text, possibly multi-line joined). */
    private JqValue parseFlowSeq(String text) {
        checkFlowText(text);
        FlowCursor c = new FlowCursor(text, 1);
        var elems = new ArrayList<JqValue>();
        c.skipWs();
        if (c.peek() == ']') return JqArray.of(elems.toArray(new JqValue[0]));
        if (c.peek() == ',') {
            throw new YamlParseException("empty flow element", line);
        }
        for (;;) {
            c.skipWs();
            elems.add(c.flowSeqElement());
            c.skipWs();
            char ch = c.next();
            if (ch == ']') break;
            if (ch != ',') {
                throw new YamlParseException("expected ',' or ']' in flow sequence", line);
            }
            // Trailing comma before close is legal (suite 5KJE/UDR7 parity);
            // any other comma adjacency is an empty element (9MAG/CTN5).
            c.skipWs();
            if (c.peek() == ']') {
                c.next();
                break;
            }
            if (c.peek() == ',') {
                throw new YamlParseException("empty flow element", line);
            }
        }
        return JqArray.of(elems.toArray(new JqValue[0]));
    }

    /**
     * Validate complete flow text before parsing: nothing may trail the
     * closing bracket (suite 62EZ/P2EQ/C2SP: `{ y: z }in: valid`), and an
     * unquoted `#` right after `,`, `[`, `{` or `:` is an invalid comment
     * (suite CVW2: `[ a, b, c,#invalid`). Callers pre-strip separated
     * comments, so any remainder is real junk (suite 9JBA: `]#invalid`).
     */
    private void checkFlowText(String text) {
        int close = findFlowClose(text);
        if (close >= 0 && !text.substring(close + 1).isBlank()) {
            throw new YamlParseException("trailing content after flow collection", line);
        }
        boolean sq = false, dq = false, esc = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (esc) {
                esc = false;
            } else if (c == '\\' && dq) {
                esc = true;
            } else if (c == '\'' && !dq) {
                sq = !sq;
            } else if (c == '"' && !sq) {
                dq = !dq;
            } else if (c == '#' && !sq && !dq && i > 0) {
                char p = text.charAt(i - 1);
                if (p == ',' || p == '[' || p == '{' || p == ':') {
                    throw new YamlParseException("invalid comment in flow collection", line);
                }
            }
        }
    }

    /** Index of the bracket closing the opener at 0 (quote-aware), or -1. */
    private static int findFlowClose(String text) {
        boolean sq = false, dq = false, esc = false;
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (esc) {
                esc = false;
            } else if (c == '\\' && dq) {
                esc = true;
            } else if (c == '\'' && !dq) {
                sq = !sq;
            } else if (c == '"' && !sq) {
                dq = !dq;
            } else if (!sq && !dq) {
                if (c == '[' || c == '{') depth++;
                else if (c == ']' || c == '}') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }

    /** Cursor over a flow scalar/collection. */
    private final class FlowCursor {
        private final String s;
        private int i;

        FlowCursor(String s, int i) {
            this.s = s;
            this.i = i;
        }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        char peek() {
            return i < s.length() ? s.charAt(i) : 0;
        }

        char next() {
            if (i >= s.length()) throw new YamlParseException("unexpected end of flow", line);
            return s.charAt(i++);
        }

        /** A flow mapping key: quoted, or plain up to the top-level `:`. */
        String flowKey() {
            skipWs();
            char c = peek();
            if (c == '\'' || c == '"') {
                char q = next();
                return internKey(quoted(q));
            }
            // A leading `? ` is the explicit-key marker (suite CT4Q).
            String key = resolveKeyPrefixes(plain());
            if (key.startsWith("? ")) {
                key = internKey(key.substring(2));
            }
            return key;
        }

        String quoted(char q) {
            StringBuilder sb = new StringBuilder();
            for (;;) {
                if (i >= s.length()) throw new YamlParseException("unterminated quote", line);
                char c = s.charAt(i++);
                if (c == q) {
                    if (q == '\'' && i < s.length() && s.charAt(i) == '\'') {
                        sb.append('\'');
                        i++;
                    } else {
                        return sb.toString();
                    }
                } else if (c == '\\' && q == '"') {
                    sb.append(flowEscape());
                } else {
                    sb.append(c);
                }
            }
        }

        private char flowEscape() {
            if (i >= s.length()) throw new YamlParseException("unterminated escape", line);
            char c = s.charAt(i++);
            return switch (c) {
                case 'n' -> '\n';
                case 't' -> '\t';
                case 'r' -> '\r';
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'a' -> '\u0007';
                case 'v' -> '\u000B';
                case 'e' -> '\u001B';
                case ' ' -> ' ';
                case '\t' -> '\t';
                case '"' -> '"';
                // No \' arm: invalid in double quotes (suite HRE5)
                case '\\' -> '\\';
                case '/' -> '/';
                case 'N' -> '\u0085';
                case '_' -> '\u00A0';
                case '0' -> '\0';
                default -> throw new YamlParseException("invalid escape '\\" + c + "'", line);
            };
        }

        /**
         * Plain scalar up to a top-level `,`, `]`, `}` or the `:` that ends a key.
         * Nested flow brackets are crossed; quoted sections are skipped whole.
         */
        String plain() {
            int start = i;
            int depth = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '\'' || c == '"') {
                    char q = c;
                    i++;
                    boolean esc = false;
                    while (i < s.length()) {
                        char d = s.charAt(i);
                        if (esc) esc = false;
                        else if (d == '\\' && q == '"') esc = true;
                        else if (d == q) {
                            if (q == '\'' && i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                                i++;
                            } else {
                                break;
                            }
                        }
                        i++;
                    }
                    i++;
                    continue;
                }
                if (c == '[' || c == '{') depth++;
                else if (c == ']' || c == '}') {
                    if (depth == 0) break;
                    depth--;
                } else if (c == ':' && depth == 0
                        && (i + 1 >= s.length() || s.charAt(i + 1) == ' ' || s.charAt(i + 1) == '\t'
                            || s.charAt(i + 1) == ',' || s.charAt(i + 1) == ']'
                            || s.charAt(i + 1) == '}')) {
                    break;
                } else if (depth == 0 && (c == ',')) {
                    break;
                }
                i++;
            }
            return s.substring(start, i).strip();
        }

        /**
         * A flow-sequence element: a value, or an implicit single-pair mapping
         * when `:` follows (suite 87E4: `['implicit flow key' : value]`).
         */
        JqValue flowSeqElement() {
            JqValue first = flowValue();
            skipWs();
            if (peek() != ':') return first;
            if (!first.isString()) {
                throw new YamlParseException("complex mapping keys are not supported", line);
            }
            next();
            skipWs();
            JqValue value = flowValue();
            // A leading `? ` is the explicit-key marker (suite CT4Q).
            String key = first.stringValue();
            if (key.startsWith("? ")) {
                key = key.substring(2);
            }
            return JqObject.builder(1).put(internKey(key), value).build();
        }

        JqValue flowValue() {
            skipWs();
            char c = peek();
            String anchor = null;
            String tag = null;
            while (c == '&' || c == '!' || c == '*') {
                if (c == '*') {
                    i++;
                    String name = plain();
                    JqValue aliased = anchors.get(name);
                    if (aliased == null) throw new YamlParseException("unknown anchor '" + name + "'", line);
                    return aliased;
                }
                int start = i;
                i++;
                if (s.charAt(start) == '!' && i < s.length() && s.charAt(i) == '<') {
                    while (i < s.length() && s.charAt(i) != '>') i++;
                    if (i < s.length()) i++;
                } else {
                    while (i < s.length() && s.charAt(i) != ' ' && s.charAt(i) != '\t'
                            && s.charAt(i) != '\n' && s.charAt(i) != '\r' && s.charAt(i) != ','
                            && s.charAt(i) != '[' && s.charAt(i) != ']' && s.charAt(i) != '{'
                            && s.charAt(i) != '}') i++;
                }
                String token = s.substring(start, i);
                if (token.startsWith("&")) anchor = token.substring(1);
                else {
                    tag = token;
                    validateTag(token);
                }
                skipWs();
                c = peek();
            }
            JqValue value;
            if (c == '{') {
                i++;
                value = parseFlowMapRest();
            } else if (c == '[') {
                i++;
                value = parseFlowSeqRest();
            } else if (c == '\'' || c == '"') {
                char q = c;
                i++;
                value = JqString.of(unquoteScalar(quoted(q), q));
            } else {
                String plainText = plain();
                // A bare `-` is not a value in flow (suite G5U8/YJV2 parity;
                // SnakeYAML-load is lenient here, suite oracle wins).
                if (plainText.equals("-")) {
                    throw new YamlParseException("dashes are not allowed as flow values", line);
                }
                value = convertScalar(plainText, tag, line);
            }
            if (anchor != null) anchors.put(anchor, value);
            return value;
        }

        JqValue parseFlowMapRest() {
            var builder = JqObject.builder(4);
            Set<String> seen = options.allowDuplicateKeys() ? null : new HashSet<>();
            skipWs();
            if (peek() == '}') {
                i++;
                return builder.build();
            }
            for (;;) {
                skipWs();
                String key = flowKey();
                skipWs();
                JqValue value;
                if (peek() == ',' || peek() == '}') {
                    // Valueless entry (suite 8KB6/9BXH).
                    value = JqNull.NULL;
                } else {
                    if (next() != ':') {
                        throw new YamlParseException("expected ':' in flow mapping", line);
                    }
                    skipWs();
                    value = flowValue();
                }
                if ("<<".equals(key)) {
                    mergeFlowValue(builder, value);
                } else {
                    if (seen != null && !seen.add(key)) {
                        throw new JqYamlException(key, line);
                    }
                    builder.put(key, value);
                }
                skipWs();
                char ch = next();
                if (ch == '}') break;
                if (ch != ',') {
                    throw new YamlParseException("expected ',' or '}' in flow mapping", line);
                }
                // Trailing comma before close is legal (suite 5KJE/UDR7 parity)
                skipWs();
                if (peek() == '}') {
                    next();
                    break;
                }
            }
            return builder.build();
        }

        JqValue parseFlowSeqRest() {
            var elems = new ArrayList<JqValue>();
            skipWs();
            if (peek() == ']') {
                i++;
                return JqArray.of(elems.toArray(new JqValue[0]));
            }
            for (;;) {
                skipWs();
                elems.add(flowSeqElement());
                skipWs();
                char ch = next();
                if (ch == ']') break;
                if (ch != ',') {
                    throw new YamlParseException("expected ',' or ']' in flow sequence", line);
                }
                // Trailing comma before close is legal (suite 5KJE/UDR7 parity)
                skipWs();
                if (peek() == ']') {
                    next();
                    break;
                }
            }
            return JqArray.of(elems.toArray(new JqValue[0]));
        }
    }

    // ========================================================================
    //  Scalars
    // ========================================================================

    /** Unquote an already-extracted inner scalar (no surrounding quotes). */
    private static String unquoteScalar(String inner, char quote) {
        if (quote == '\'') return inner.replace("''", "'");
        if (inner.indexOf('\\') < 0) return inner;
        StringBuilder sb = new StringBuilder(inner.length());
        for (int k = 0; k < inner.length(); k++) {
            char c = inner.charAt(k);
            if (c == '\\') {
                if (k + 1 >= inner.length()) throw new YamlParseException("unterminated escape", -1);
                char e = inner.charAt(++k);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'a' -> sb.append('\u0007');
                    case 'v' -> sb.append('\u000B');
                    case 'e' -> sb.append('\u001B');
                    case ' ' -> sb.append(' ');
                    case '\t' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    // No \' arm: \' is not a valid double-quote escape (suite HRE5)
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'N' -> sb.append('\u0085');
                    case '_' -> sb.append('\u00A0');
                    case '0' -> sb.append('\0');
                    case 'x' -> {
                        sb.append((char) Integer.parseInt(inner.substring(k + 1, k + 3), 16));
                        k += 2;
                    }
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(inner.substring(k + 1, k + 5), 16));
                        k += 4;
                    }
                    case 'U' -> {
                        sb.append((char) Integer.parseInt(inner.substring(k + 1, k + 9), 16));
                        k += 8;
                    }
                    default -> throw new YamlParseException("invalid escape '\\" + e + "'", -1);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * YAML 1.1 boolean words (mirrors SnakeYAML's implicit resolution, which
     * follows the core schema here: single-letter y/n are STRINGS, unlike
     * older YAML 1.1 drafts).
     */
    private static boolean isTrueWord(String v) {
        return v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("on");
    }

    private static boolean isBoolWord(String v) {
        return isTrueWord(v) || v.equalsIgnoreCase("no")
                || v.equalsIgnoreCase("false") || v.equalsIgnoreCase("off");
    }

    /** Normalize `!foo` / `!!str` / `!<uri>` to a short tag name. */
    private static String normalizeTag(String token) {
        String t = token;
        while (t.startsWith("!")) t = t.substring(1);
        if (t.startsWith("<") && t.endsWith(">") && t.length() > 2) {
            String uri = t.substring(1, t.length() - 1);
            int cut = Math.max(uri.lastIndexOf(':'), uri.lastIndexOf('/'));
            t = cut >= 0 ? uri.substring(cut + 1) : uri;
        }
        return t;
    }

    /**
     * Validate a tag token: verbatim `!<uri>` must close; shorthand bodies
     * must not contain flow/map characters (suite LHL4: `!invalid{}tag`,
     * U99R: `!!str,`). A `!handle!` prefix must be `%TAG`-defined in this
     * document (`!!` and bare `!x` are always allowed; suite QLJ7 parity).
     */
    private void validateTag(String token) {
        if (token.startsWith("!<")) {
            if (!token.endsWith(">")) {
                throw new YamlParseException("invalid verbatim tag", line);
            }
            return;
        }
        String body = token;
        while (body.startsWith("!")) body = body.substring(1);
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{' || c == '}' || c == '[' || c == ']' || c == ',' || c == ' ') {
                throw new YamlParseException("invalid tag", line);
            }
        }
        int second = token.indexOf('!', 1);
        if (second > 0) {
            String handle = token.substring(0, second + 1);
            if (!handle.equals("!!") && !tagHandles.containsKey(handle)) {
                throw new YamlParseException("unknown tag handle '" + handle + "'", line);
            }
        }
    }
    /**
     * True when the parser would read {@code value} back as a plain string:
     * no surrounding whitespace/newlines, no leading indicator, no `#`, and
     * scalar conversion yields a string. The emitter uses this to decide
     * bare-vs-quoted (round-trip safety by construction).
     */
    static boolean parsesAsPlainString(String value) {
        if (value.isEmpty()) return false;
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if (first == ' ' || first == '\t' || last == ' ' || last == '\t') return false;
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) return false;
        if ("-?:,[]{}#&*!|>'\"%@`".indexOf(first) >= 0 || first == '<') return false;
        if (value.indexOf('#') >= 0) return false;
        try {
            return convertScalar(value, null, -1) instanceof JqString;
        } catch (YamlParseException e) {
            return false;
        }
    }

    /**
     * Convert a plain scalar to a value. {@code tag} is an explicit `!`/`!!` tag
     * or null. Known tags coerce (with lenient fallbacks); unknown tags and
     * untagged values go through core-schema auto-detect.
     */
    private static JqValue convertScalar(String value, String tag, int line) {
        if (tag != null) {
            String norm = normalizeTag(tag);
            // Bare `!` is the non-specific tag: plain scalars resolve as strings
            // (suite S4JQ; SnakeYAML-load resolves to int here, suite oracle wins).
            if (norm.isEmpty()) {
                return JqString.of(value);
            }
            switch (norm) {
                case "null" -> {
                    return JqNull.NULL;
                }
                case "bool" -> {
                    return JqBoolean.of(isTrueWord(value));
                }
                case "int" -> {
                    return convertInteger(value);
                }
                case "float" -> {
                    return convertFloat(value);
                }
                case "str" -> {
                    return JqString.of(value);
                }
                default -> {
                    // Unknown tags (incl. seq/map on scalars): fall through to auto-detect
                }
            }
        }
        // A top-level `: ` inside a single-line plain value is a nested mapping
        // (suite ZCZ6: `a: b: c: d`); quoted/flow values never reach here.
        // Tagged values are exempt (the tag disambiguates). The indexOf gate
        // keeps colon-less values (the common case) on a single intrinsic scan.
        if (tag == null && value.indexOf(':') >= 0 && findMappingColon(value) >= 0) {
            throw new YamlParseException("nested mapping in single-line value", line);
        }
        // Untagged or unknown-tag auto-detect
        if (value.isEmpty() || value.equals("null") || value.equals("Null") || value.equals("NULL")
                || value.equals("~")) {
            return JqNull.NULL;
        }
        if (isBoolWord(value)) {
            return JqBoolean.of(isTrueWord(value));
        }
        if (isIntegerShape(value)) {
            return convertInteger(value);
        }
        if (isFloatShape(value) || isSpecialFloat(value)) {
            return convertFloat(value);
        }
        return JqString.of(value);
    }

    private static boolean isSpecialFloat(String value) {
        return value.equals(".inf") || value.equals(".Inf") || value.equals(".INF")
                || value.equals("-.inf") || value.equals("-.Inf") || value.equals("-.INF")
                || value.equals(".nan") || value.equals(".NaN") || value.equals(".NAN");
    }

    /**
     * Integer shapes accepted for conversion, mirroring SnakeYAML's implicit
     * int regexp: optional sign, binary/hex/octal prefixes, plain decimals
     * (a leading zero allows only 0-7, so `09` stays a string), underscores
     * anywhere in digit runs, and base-60 `H:M[:S]` sexagesimal. Notably there
     * is NO `0o` prefix support (SnakeYAML leaves `0o17` a string).
     */
    private static boolean isIntegerShape(String value) {
        if (value.isEmpty()) return false;
        int i = (value.charAt(0) == '-' || value.charAt(0) == '+') ? 1 : 0;
        if (i >= value.length()) return false;
        // Work on offsets into value throughout: no substring allocation
        if (startsWithAt(value, i, "0b") || startsWithAt(value, i, "0B")) {
            return allIn(value, i + 2, "01_");
        }
        if (startsWithAt(value, i, "0x") || startsWithAt(value, i, "0X")) {
            return allIn(value, i + 2, "0123456789abcdefABCDEF_");
        }
        // indexOf is allocation-free; plain and sexagesimal have different
        // leading-zero rules, so branch before validating
        int colon = value.indexOf(':', i);
        if (colon < 0) {
            if (!isDigitRun(value, i, value.length())) return false;
            if (value.length() - i > 1 && value.charAt(i) == '0') {
                // Leading zero: only 0-7 and underscores (09 stays a string)
                return allIn(value, i + 1, "01234567_");
            }
            return true;
        }
        // Sexagesimal H:M[:S]: hours are a digit run with no leading zero
        int partStart = i;
        int parts = 0;
        for (int k = i; k <= value.length(); k++) {
            if (k == value.length() || value.charAt(k) == ':') {
                parts++;
                if (parts > 3) return false;
                if (parts == 1) {
                    if (!isDigitRun(value, partStart, k) || value.charAt(partStart) == '0') return false;
                } else {
                    // Minutes/seconds: 1-2 plain digits each (no underscores), tens <= 5
                    int len = k - partStart;
                    if (len < 1 || len > 2) return false;
                    for (int j = partStart; j < k; j++) {
                        if (value.charAt(j) < '0' || value.charAt(j) > '9') return false;
                    }
                    if (len == 2 && value.charAt(partStart) > '5') return false;
                }
                partStart = k + 1;
            }
        }
        return true;
    }

    private static boolean startsWithAt(String s, int from, String prefix) {
        if (s.length() - from < prefix.length()) return false;
        for (int k = 0; k < prefix.length(); k++) {
            if (s.charAt(from + k) != prefix.charAt(k)) return false;
        }
        return true;
    }

    private static boolean allIn(String s, int from, String alphabet) {
        if (s.length() <= from) return false;
        for (int k = from; k < s.length(); k++) {
            if (alphabet.indexOf(s.charAt(k)) < 0) return false;
        }
        return true;
    }

    private static boolean isDigitRun(String s, int from, int to) {
        if (from >= to || s.charAt(from) < '0' || s.charAt(from) > '9') return false;
        for (int k = from + 1; k < to; k++) {
            char c = s.charAt(k);
            if (!(c >= '0' && c <= '9') && c != '_') return false;
        }
        return true;
    }

    private static boolean isFloatShape(String value) {
        if (value.isEmpty()) return false;
        int i = (value.charAt(0) == '-' || value.charAt(0) == '+') ? 1 : 0;
        boolean dot = false, digit = false, exp = false;
        for (; i < value.length(); i++) {
            char c = value.charAt(i);
            // Underscores are visual separators (stripped before parsing, like SnakeYAML)
            if (c == '_' && digit) continue;
            if (c >= '0' && c <= '9') digit = true;
            else if (c == '.' && !dot && !exp) dot = true;
            else if ((c == 'e' || c == 'E') && digit && !exp) {
                exp = true;
                digit = false;
                if (i + 1 < value.length()
                        && (value.charAt(i + 1) == '+' || value.charAt(i + 1) == '-')) {
                    i++;
                }
            } else {
                return false;
            }
        }
        return digit && (dot || exp);
    }

    private static JqValue convertInteger(String value) {
        try {
            String v = value;
            boolean neg = false;
            if (v.startsWith("-") || v.startsWith("+")) {
                neg = v.startsWith("-");
                v = v.substring(1);
            }
            // Underscores are visual separators (SnakeYAML strips them before parsing)
            v = v.replace("_", "");
            if (v.contains(":")) {
                // Sexagesimal H:M[:S]
                long total = 0;
                for (String part : v.split(":", -1)) {
                    total = total * 60 + Long.parseLong(part);
                }
                return JqNumber.of(neg ? -total : total);
            }
            long parsed;
            if (v.startsWith("0x") || v.startsWith("0X")) {
                parsed = Long.parseLong(v.substring(2), 16);
            } else if (v.startsWith("0b") || v.startsWith("0B")) {
                parsed = Long.parseLong(v.substring(2), 2);
            } else if (v.startsWith("0") && v.length() > 1) {
                // YAML 1.1 octal: leading 0 (e.g., 077 = 63)
                parsed = Long.parseLong(v.substring(1), 8);
            } else {
                parsed = Long.parseLong(v);
            }
            return JqNumber.of(neg ? -parsed : parsed);
        } catch (NumberFormatException e) {
            // Overflow — use BigDecimal
            try {
                return JqNumber.of(new BigDecimal(value.replace("_", "")));
            } catch (NumberFormatException e2) {
                return JqString.of(value); // fallback
            }
        }
    }

    private static JqValue convertFloat(String value) {
        if (value.equals(".inf") || value.equals(".Inf") || value.equals(".INF")) {
            return JqNumber.of(Double.POSITIVE_INFINITY);
        }
        if (value.equals("-.inf") || value.equals("-.Inf") || value.equals("-.INF")) {
            return JqNumber.of(Double.NEGATIVE_INFINITY);
        }
        if (value.equals(".nan") || value.equals(".NaN") || value.equals(".NAN")) {
            return JqNumber.of(Double.NaN);
        }
        try {
            return JqNumber.of(Double.parseDouble(value.replace("_", "")));
        } catch (NumberFormatException e) {
            return JqString.of(value); // fallback
        }
    }
}

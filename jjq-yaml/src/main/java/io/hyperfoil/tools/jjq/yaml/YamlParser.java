package io.hyperfoil.tools.jjq.yaml;

import io.hyperfoil.tools.jjq.value.*;

import java.math.BigDecimal;
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
        JqValue doc = p.parseDocument();
        return doc == null ? JqNull.NULL : doc;
    }

    /** Parse all documents of a {@code ---}-separated stream. */
    static List<JqValue> parseAll(byte[] data, int offset, int length, YamlOptions options) {
        YamlParser p = new YamlParser(data, offset, length, options);
        var docs = new ArrayList<JqValue>();
        for (;;) {
            p.anchors = new HashMap<>();
            JqValue doc = p.parseDocument();
            if (doc == null) break;
            docs.add(doc);
        }
        return docs;
    }

    // ========================================================================
    //  Documents
    // ========================================================================

    /** Parse one document. Returns null at clean EOF (no more documents). */
    private JqValue parseDocument() {
        skipDirectives();
        if (!skipBlankAndComments()) return null;
        if (isDocEndAtLineStart()) {
            advanceLine();
            skipBlankAndComments();
            return null;
        }
        String rest = consumeDocStart();
        JqValue doc;
        if (rest != null) {
            if (rest.isEmpty()) {
                // Bare `---`: content follows on later lines (or an empty doc)
                if (!skipBlankAndComments()) return JqNull.NULL;
                doc = parseBlockNode(0);
            } else {
                doc = parseInlineValue(gatherValueText(stripComment(rest), 0), 0, true);
            }
        } else {
            doc = parseBlockNode(0);
        }
        skipBlankAndComments();
        if (isDocEndAtLineStart()) {
            advanceLine();
        }
        return doc == null ? JqNull.NULL : doc;
    }

    /** Skip `%`-directive lines at the current position. */
    private void skipDirectives() {
        while (pos < end && d[pos] == '%') {
            advanceLine();
            skipBlankLinesOnly();
        }
    }

    /** If a `---` marker opens the line, consume it and return the rest of the line (may be blank). */
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
        return hasMappingIndicator(s, e) ? LINE_MAP : LINE_PLAIN;
    }

    /** True when `---`/`...` opens at p with a space/tab/EOL boundary at e. */
    private boolean isMarkerAt(int p, int e) {
        return matchMarkerBytes(p, e, '-') || matchMarkerBytes(p, e, '.');
    }

    private boolean matchMarkerBytes(int p, int e, char c) {
        if (p + 3 > e || d[p] != c || d[p + 1] != c || d[p + 2] != c) return false;
        return p + 3 == e || d[p + 3] == ' ' || d[p + 3] == '\t';
    }

    /** Spaces (not tabs) from pos, which must be at a line start. Tabs are a syntax error. */
    private int peekIndent() {
        int n = 0;
        while (pos + n < end && d[pos + n] == ' ') n++;
        if (pos + n < end && d[pos + n] == '\t') {
            throw new YamlParseException("tabs are not allowed for indentation", line);
        }
        return n;
    }

    /**
     * Strip a trailing ` #comment` (outside quotes). Idempotent: safe to apply twice.
     * A `#` without preceding space/tab is content (e.g. `a#b`).
     */
    private static String stripComment(String line) {
        boolean sq = false, dq = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !dq) sq = !sq;
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
     * Parse a block node (mapping, sequence, flow or scalar document) whose
     * content starts at {@code indent}.
     */
    private JqValue parseBlockNode(int indent) {
        if (!skipBlankAndComments()) return JqNull.NULL;
        int ind = peekIndent();
        if (ind < indent) return JqNull.NULL;
        if (ind > indent) {
            throw new YamlParseException("unexpected indentation", line);
        }
        if (isSeqEntry()) {
            return parseBlockSeq(indent);
        }
        int le = lineEnd(pos);
        int cs = contentStart(d, pos, le);
        if (cs >= le) return JqNull.NULL;
        byte c0 = d[cs];
        if (c0 == '{' || c0 == '[') {
            return parseInlineValue(gatherValueText(stripComment(readLine()).strip(), indent), indent, true);
        }
        if (c0 == '?' && (cs + 1 >= le || d[cs + 1] == ' ' || d[cs + 1] == '\t')) {
            throw new YamlParseException("explicit '? ' mapping keys are not supported", line);
        }
        if (!hasMappingIndicator(pos, le)) {
            return parseScalarDocument(indent);
        }
        return parseBlockMap(indent);
    }

    /** A top-level (or nested) scalar document, with folding across lines. */
    private JqValue parseScalarDocument(int indent) {
        String first = stripComment(readLine()).strip();
        char c0 = first.charAt(0);
        if (c0 == '"' || c0 == '\'') {
            return parseInlineValue(gatherValueText(first, indent), indent, true);
        }
        if (c0 == '{' || c0 == '[') {
            return parseInlineValue(gatherValueText(first, indent), indent, true);
        }
        // Plain scalar document: fold following lines (entry rules terminate)
        StringBuilder sb = new StringBuilder(first);
        for (;;) {
            if (pos >= end) break;
            int le = lineEnd(pos);
            int kind = classifyLine(pos, le);
            if (kind != LINE_PLAIN) break;
            int cs = contentStart(d, pos, le);
            // The line is plain content: consume it (comment already excluded by kind)
            pos = cs;
            sb.append(' ').append(stripComment(readLine()).strip());
        }
        return convertScalar(sb.toString(), null);
    }

    /** Consume the current line (pos is at its start). */
    /** True when `- ` (or bare `-`) opens at absolute offset p. */
    private boolean isSeqEntryAt(int p) {
        if (p >= end || d[p] != '-') return false;
        if (p + 1 >= end) return true;
        byte c = d[p + 1];
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    /** True when `? ` opens at absolute offset p. */
    /** True when the line at pos opens a `- ` sequence entry (pos at line start). */
    private boolean isSeqEntry() {
        int p = pos;
        while (p < end && d[p] == ' ') p++;
        return isSeqEntryAt(p);
    }

    /**
     * True when the text contains an unquoted `:` followed by space/tab/EOL
     * outside flow brackets — i.e. it can open a block mapping entry.
     */

    /** Byte-range variant: unquoted `:` + space/tab/EOL outside flow brackets. */
    private boolean hasMappingIndicator(int s, int e) {
        boolean sq = false, dq = false;
        int depth = 0;
        for (int i = s; i < e; i++) {
            byte c = d[i];
            if (c == '\'' && !dq) sq = !sq;
            else if (c == '"' && !sq) dq = !dq;
            else if ((c == '[' || c == '{') && !sq && !dq) depth++;
            else if ((c == ']' || c == '}') && !sq && !dq && depth > 0) depth--;
            else if (c == ':' && !sq && !dq && depth == 0) {
                if (i + 1 >= e || d[i + 1] == ' ' || d[i + 1] == '\t') {
                    return true;
                }
            }
        }
        return false;
    }

    private JqArray parseBlockSeq(int indent) {
        var elems = new ArrayList<JqValue>();
        for (;;) {
            if (!skipBlankAndComments()) break;
            int lineStart = pos;
            int ind = peekIndent();
            if (ind < indent) break;
            if (ind > indent || !isSeqEntry()) {
                throw new YamlParseException("expected '-' sequence entry", line);
            }
            pos += ind; // reach '-'
            pos++; // consume '-'
            if (pos < end && (d[pos] == ' ' || d[pos] == '\t')) {
                while (pos < end && (d[pos] == ' ' || d[pos] == '\t')) pos++;
                // Column (not absolute offset) of the item content, for nested indent
                int dashCol = pos - lineStart;
                String rest = stripComment(readLine()).strip();
                elems.add(parseSeqItemContent(rest, indent, dashCol));
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
                return parseBlockNode(li);
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
     */
    private JqValue parseSeqItemContent(String rest, int indent, int dashCol) {
        if (rest.isEmpty()) {
            return nestedBlockOrNull(indent, false);
        }
        // `- key: value` opens an inline mapping at the key's column
        if (inlineMapStart(rest)) {
            return parseInlineMap(rest, dashCol, indent);
        }
        // `- - a` nested sequence
        if (rest.equals("-") || rest.startsWith("- ")) {
            return parseInlineSeq(rest.substring(1).stripLeading(), indent, dashCol + 1);
        }
        return parseValueText(rest, indent, false);
    }

    /**
     * True when {@code rest} opens an inline `key:` mapping (a `:` outside quotes
     * and flow brackets, with a non-empty non-flow key).
     */
    private boolean inlineMapStart(String rest) {
        boolean sq = false, dq = false;
        int depth = 0;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '\'' && !dq) sq = !sq;
            else if (c == '"' && !sq) dq = !dq;
            else if ((c == '[' || c == '{') && !sq && !dq) depth++;
            else if ((c == ']' || c == '}') && !sq && !dq && depth > 0) depth--;
            else if (c == ':' && !sq && !dq && depth == 0) {
                if (i + 1 >= rest.length() || rest.charAt(i + 1) == ' ' || rest.charAt(i + 1) == '\t') {
                    String key = rest.substring(0, i).strip();
                    return !key.isEmpty() && key.charAt(0) != '[' && key.charAt(0) != '{'
                            && !key.equals("-") && !key.startsWith("? ");
                }
                return false;
            }
        }
        return false;
    }

    /** Parse `- - a` style nested sequences (rare but legal). */
    private JqValue parseInlineSeq(String rest, int indent, int dashCol) {
        var elems = new ArrayList<JqValue>();
        elems.add(parseSeqItemContent(rest, indent, dashCol));
        // Following `- ...` lines at the same dash column continue the sequence
        for (;;) {
            int saved = pos;
            int savedLine = line;
            if (!skipBlankAndComments() || peekIndent() != indent) {
                pos = saved;
                line = savedLine;
                break;
            }
            if (!isSeqEntry()) {
                pos = saved;
                line = savedLine;
                break;
            }
            int p = pos;
            while (p < end && d[p] == ' ') p++;
            pos = p + 1;
            if (pos < end && (d[pos] == ' ' || d[pos] == '\t')) {
                while (pos < end && (d[pos] == ' ' || d[pos] == '\t')) pos++;
                int col = pos;
                String next = stripComment(readLine()).strip();
                elems.add(parseSeqItemContent(next, indent, col));
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
            // Deeper keys are rejected (SnakeYAML parity: same-indent alignment
            // is required; cf. "mapping values are not allowed here")
            if (ind > indent) {
                throw new YamlParseException("unexpected indentation in mapping", line);
            }
            if (isSeqEntry()) {
                throw new YamlParseException("expected mapping key, found sequence entry", line);
            }
            pos += ind;
            if (classifyLine(pos, lineEnd(pos)) == LINE_MARKER) break;
            String rawLine = stripComment(readLine()).strip();
            if (rawLine.isEmpty()) continue;
            if (rawLine.equals("?") || rawLine.startsWith("? ")) {
                throw new YamlParseException("explicit '? ' mapping keys are not supported", line);
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
            builder.put(key, parseMapValue(entry.value(), indent));
        }
        return (JqObject) builder.build();
    }

    /**
     * Parse an inline `- key: value...` mapping: first pair on this line,
     * deeper-or-equal lines join the same mapping.
     */
    private JqValue parseInlineMap(String firstLine, int keyCol, int parentIndent) {
        var builder = JqObject.builder(4);
        Set<String> seen = options.allowDuplicateKeys() ? null : new HashSet<>();
        MapEntry first = splitKeyValue(firstLine);
        String key = unquoteKey(first.key());
        if ("<<".equals(key)) {
            applyMerge(builder, first.value(), parentIndent);
        } else {
            if (seen != null) seen.add(key);
            builder.put(key, parseMapValue(first.value(), parentIndent));
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
            if (classifyLine(pos, lineEnd(pos)) == LINE_MARKER) {
                pos = saved;
                line = savedLine;
                break;
            }
            String rawLine = stripComment(readLine()).strip();
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
            builder.put(k, parseMapValue(entry.value(), parentIndent));
        }
        return builder.build();
    }

    /** Split a `key: value` line at the first top-level `: ` (flow-aware). */
    private MapEntry splitKeyValue(String rawLine) {
        boolean sq = false, dq = false;
        int depth = 0;
        for (int i = 0; i < rawLine.length(); i++) {
            char c = rawLine.charAt(i);
            if (c == '\'' && !dq) sq = !sq;
            else if (c == '"' && !sq) dq = !dq;
            else if ((c == '[' || c == '{') && !sq && !dq) depth++;
            else if ((c == ']' || c == '}') && !sq && !dq && depth > 0) depth--;
            else if (c == ':' && !sq && !dq && depth == 0) {
                if (i + 1 >= rawLine.length() || rawLine.charAt(i + 1) == ' '
                        || rawLine.charAt(i + 1) == '\t') {
                    String key = rawLine.substring(0, i).strip();
                    if (key.startsWith("{") || key.startsWith("[")) {
                        throw new YamlParseException("complex mapping keys are not supported", line - 1);
                    }
                    return new MapEntry(key, rawLine.substring(i + 1).strip());
                }
            }
        }
        throw new YamlParseException("expected ':' in mapping entry", line - 1);
    }

    private record MapEntry(String key, String value) {}

    /** Strip quotes from a mapping key (single/double), else intern as-is. */
    private String unquoteKey(String key) {
        if (key.length() >= 2) {
            char q = key.charAt(0);
            if ((q == '\'' || q == '"') && key.charAt(key.length() - 1) == q) {
                return unquoteScalar(key.substring(1, key.length() - 1), q);
            }
        }
        return internKey(key);
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
    private JqValue parseMapValue(String inline, int indent) {
        if (inline.isEmpty()) {
            return nestedBlockOrNull(indent, true);
        }
        return parseValueText(inline, indent, true);
    }

    /**
     * Parse a first-line value fragment: gather quoted/flow continuations,
     * fold plain continuations, then dispatch through prefixes.
     * {@code sameIndentSeq} selects the nested-block rule (mapping values nest
     * same-indent sequences; sequence items treat them as siblings).
     */
    private JqValue parseValueText(String first, int indent, boolean sameIndentSeq) {
        char c0 = first.charAt(0);
        if (c0 == '"' || c0 == '\'' || c0 == '{' || c0 == '[') {
            return parseInlineValue(gatherValueText(first, indent), indent, sameIndentSeq);
        }
        if (c0 == '|' || c0 == '>' || c0 == '&' || c0 == '*' || c0 == '!') {
            return parseInlineValue(first, indent, sameIndentSeq);
        }
        String folded = foldPlainContinuation(first, indent);
        if (folded != null) {
            return convertScalar(folded, null);
        }
        return convertScalar(first, null);
    }

    /**
     * Fold following deeper-indented lines into a plain scalar. Returns null when
     * there is no continuation. Comment lines terminate (SnakeYAML parity);
     * markers, sequence entries, explicit keys and new mapping entries do too.
     */
    private String foldPlainContinuation(String first, int indent) {
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
            if (kind != LINE_PLAIN) break;
            int cs = contentStart(d, pos, le);
            if (cs - pos <= indent) break;
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
            sb.append(stripComment(readLine()).strip());
        }
        return sb == null ? null : sb.toString();
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
        JqValue mergeValue = inline.isEmpty() ? parseNestedMerge(indent) : parseInlineValue(inline, indent, true);
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
            return parseBlockNode(peekIndent());
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
    private String gatherValueText(String first, int indent) {
        if (first.isEmpty()) return first;
        char c0 = first.charAt(0);
        if (c0 == '"' || c0 == '\'') {
            return gatherQuoted(first, c0, indent);
        }
        if (c0 == '{' || c0 == '[') {
            return gatherFlow(first, indent);
        }
        return first;
    }

    /** Gather a possibly multi-line quoted scalar; folding like plain scalars. */
    private String gatherQuoted(String first, char quote, int indent) {
        if (isClosedQuote(first, quote)) return first;
        StringBuilder sb = new StringBuilder(first);
        for (;;) {
            if (pos >= end) {
                throw new YamlParseException("unterminated quoted scalar", line);
            }
            int p = pos;
            while (p < end && (d[p] == ' ' || d[p] == '\t')) p++;
            if (p >= end) throw new YamlParseException("unterminated quoted scalar", line);
            byte c = d[p];
            if (c == '\n' || c == '\r' || c == '#') {
                throw new YamlParseException("unterminated quoted scalar", line);
            }
            int li = p - pos;
            if (li <= indent) {
                throw new YamlParseException("unterminated quoted scalar", line);
            }
            String rawLine = readLine();
            String content = rawLine.strip();
            if (quote == '"' && content.endsWith("\\") && !content.endsWith("\\\\")) {
                sb.append(content, 0, content.length() - 1);
            } else if (content.isEmpty()) {
                sb.append('\n');
            } else {
                sb.append(' ').append(content);
            }
            String joined = sb.toString();
            if (isClosedQuote(joined, quote)) return joined;
            // Keep the raw text for the next iteration (re-scan from the joined form)
            first = joined;
            sb = new StringBuilder(joined);
        }
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

    /** Gather a possibly multi-line flow collection until brackets balance. */
    private String gatherFlow(String first, int indent) {
        if (flowDepth(first) == 0) return first;
        StringBuilder sb = new StringBuilder(first);
        for (;;) {
            if (pos >= end) {
                throw new YamlParseException("unbalanced flow collection", line);
            }
            int p = pos;
            while (p < end && (d[p] == ' ' || d[p] == '\t')) p++;
            if (p >= end) throw new YamlParseException("unbalanced flow collection", line);
            byte c = d[p];
            if (c == '\n' || c == '\r' || c == '#') {
                throw new YamlParseException("unbalanced flow collection", line);
            }
            int li = p - pos;
            if (li <= indent) {
                throw new YamlParseException("unbalanced flow collection", line);
            }
            String rawLine = stripComment(readLine()).strip();
            if (rawLine.endsWith("\\")) {
                sb.append(rawLine, 0, rawLine.length() - 1);
            } else {
                sb.append(' ').append(rawLine);
            }
            if (flowDepth(sb.toString()) == 0) return sb.toString();
        }
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

    /** Parse a same-line value with tag/anchor/alias prefixes. {@code indent} is the enclosing context. */
    private JqValue parseInlineValue(String text, int indent, boolean sameIndentSeq) {
        String t = text.strip();
        String tag = null;
        String anchorName = null;
        while (t.startsWith("!") || t.startsWith("&") || t.startsWith("*")) {
            if (t.startsWith("*")) {
                String name = t.substring(1).split("\\s")[0];
                JqValue aliased = anchors.get(name);
                if (aliased == null) {
                    throw new YamlParseException("unknown anchor '" + name + "'", line);
                }
                return aliased;
            }
            int sp = indexOfSplit(t);
            String token = sp < 0 ? t : t.substring(0, sp);
            String rest = sp < 0 ? "" : t.substring(sp).strip();
            if (token.startsWith("&")) {
                anchorName = token.substring(1);
                if (rest.isEmpty()) {
                    JqValue value = parseAnchoredNested(indent, sameIndentSeq);
                    anchors.put(anchorName, value);
                    return value;
                }
                t = rest;
                continue;
            }
            tag = token;
            t = rest;
            if (t.isEmpty()) {
                JqValue value = parseAnchoredNested(indent, sameIndentSeq);
                if (anchorName != null) anchors.put(anchorName, value);
                return value;
            }
        }
        t = gatherValueText(t, indent);
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
            value = convertScalar(t, tag);
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
        var content = new ArrayList<String>();
        var blanks = new HashSet<Integer>();
        int contentIndent = -1;
        for (;;) {
            if (pos >= end) break;
            int saved = pos;
            int savedLine = line;
            int ls = pos;
            while (ls < end && (d[ls] == ' ' || d[ls] == '\t')) ls++;
            boolean blank = ls >= end || d[ls] == '\n' || d[ls] == '\r';
            int li = ls - pos;
            if (!blank) {
                if (li <= indent && !(explicitIndent > 0 && li == indent + explicitIndent)) {
                    pos = saved;
                    line = savedLine;
                    break;
                }
                if (contentIndent < 0) {
                    contentIndent = explicitIndent > 0 ? indent + explicitIndent : li;
                }
                if (li < contentIndent) {
                    pos = saved;
                    line = savedLine;
                    break;
                }
            }
            String rawLine = readLine();
            if (blank) {
                blanks.add(content.size());
                content.add("");
            } else {
                // rawLine holds leading spaces (pos was at line start):
                // strip exactly contentIndent of them (extra indent is significant)
                content.add(rawLine.length() > contentIndent
                        ? rawLine.substring(contentIndent) : "");
            }
        }
        if (contentIndent < 0) {
            return JqString.of("");
        }
        StringBuilder sb = new StringBuilder();
        if (style == '|') {
            for (int k = 0; k < content.size(); k++) {
                sb.append(content.get(k)).append('\n');
            }
        } else {
            // Folded: line breaks become spaces, blank lines become newlines
            boolean prevBlank = true;
            for (int k = 0; k < content.size(); k++) {
                if (blanks.contains(k)) {
                    sb.append('\n');
                    prevBlank = true;
                } else {
                    if (!prevBlank) sb.append(' ');
                    sb.append(content.get(k));
                    prevBlank = false;
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
        FlowCursor c = new FlowCursor(text, 1);
        var builder = JqObject.builder(4);
        Set<String> seen = options.allowDuplicateKeys() ? null : new HashSet<>();
        c.skipWs();
        if (c.peek() == '}') return builder.build();
        for (;;) {
            c.skipWs();
            String key = c.flowKey();
            c.skipWs();
            if (c.next() != ':') {
                throw new YamlParseException("expected ':' in flow mapping", line);
            }
            c.skipWs();
            JqValue value = c.flowValue();
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
        FlowCursor c = new FlowCursor(text, 1);
        var elems = new ArrayList<JqValue>();
        c.skipWs();
        if (c.peek() == ']') return JqArray.of(elems.toArray(new JqValue[0]));
        for (;;) {
            c.skipWs();
            elems.add(c.flowValue());
            c.skipWs();
            char ch = c.next();
            if (ch == ']') break;
            if (ch != ',') {
                throw new YamlParseException("expected ',' or ']' in flow sequence", line);
            }
        }
        return JqArray.of(elems.toArray(new JqValue[0]));
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
            return internKey(plain());
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
                case '"' -> '"';
                case '\'' -> '\'';
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
                while (i < s.length() && s.charAt(i) != ' ' && s.charAt(i) != '\t'
                        && s.charAt(i) != '\n' && s.charAt(i) != '\r') i++;
                String token = s.substring(start, i);
                if (token.startsWith("&")) anchor = token.substring(1);
                else tag = token;
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
                value = convertScalar(plain(), tag);
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
                if (next() != ':') {
                    throw new YamlParseException("expected ':' in flow mapping", line);
                }
                skipWs();
                JqValue value = flowValue();
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
                elems.add(flowValue());
                skipWs();
                char ch = next();
                if (ch == ']') break;
                if (ch != ',') {
                    throw new YamlParseException("expected ',' or ']' in flow sequence", line);
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
                    case '"' -> sb.append('"');
                    case '\'' -> sb.append('\'');
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
     * Convert a plain scalar to a value. {@code tag} is an explicit `!`/`!!` tag
     * or null. Mirrors the SnakeYAML-based conversion semantics exactly:
     * known tags coerce (with lenient fallbacks), unknown tags and untagged
     * values go through core-schema auto-detect.
     */
    private JqValue convertScalar(String value, String tag) {
        if (tag != null) {
            switch (normalizeTag(tag)) {
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

    private JqValue convertInteger(String value) {
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

    private JqValue convertFloat(String value) {
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

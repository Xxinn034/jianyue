package com.jianyue.reader.rule;

import java.util.ArrayList;
import java.util.List;

/**
 * A small HTML parser plus CSS selector engine, for Android 1.0.
 *
 * Why not use the platform: jsoup does not exist on API 1 and dragging it in would
 * pull its own API requirements. javax.xml.parsers exists at API 1 but its DOM parser
 * rejects most real-world HTML (unclosed tags, stray ampersands), which is exactly what
 * novel sites emit, so a purpose-built lenient parser is both smaller and more reliable.
 *
 * Supported selectors (the subset book sources use):
 *   tag.xxx              tag with class
 *   .xxx                 any tag with class
 *   #xxx                 id
 *   tag                  tag name
 *   tag[attr=value]      attribute equals
 *   tag[attr*=value]     attribute contains
 *   a b                  descendant
 *   a > b                direct child
 *
 * Extraction: @text, @html (innerHTML), @textNodes, @href, @src, or any @attr.
 */
public final class Html {

    // ------------------------------------------------------------- element

    public static final class El {
        public String tag;
        public List<String> classes = new ArrayList<String>();
        public String id;
        /** attributes in insertion order */
        public List<String> attrNames = new ArrayList<String>();
        public List<String> attrValues = new ArrayList<String>();
        public El parent;
        /** child ELEMENTS, in document order (the selector and XPath engines walk this) */
        public List<El> children = new ArrayList<El>();
        /**
         * Every child node in DOCUMENT ORDER: a String for a text node, an {@link El} for an
         * element.
         *
         * <p>Keeping the text in one blob per element is not enough: in {@code <p>a<br>b</p>}
         * both pieces of text belong to {@code <p>}, so "ab" plus a trailing {@code <br>} would
         * move the line break behind "b" ("ab\n" instead of "a\nb") and glue two paragraphs
         * together - the "正文没有分段" report.
         */
        public List<Object> nodes = new ArrayList<Object>();

        public String attr(String name) {
            if (name == null) {
                return null;
            }
            for (int i = 0; i < attrNames.size(); i++) {
                if (name.equalsIgnoreCase(attrNames.get(i))) {
                    return attrValues.get(i);
                }
            }
            return null;
        }

        public boolean hasClass(String c) {
            for (int i = 0; i < classes.size(); i++) {
                if (classes.get(i).equalsIgnoreCase(c)) {
                    return true;
                }
            }
            return false;
        }

        /** Concatenated direct text. */
        public String text() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < nodes.size(); i++) {
                Object n = nodes.get(i);
                if (n instanceof String) {
                    sb.append((String) n);
                }
            }
            return sb.toString();
        }

        /** This element's own text nodes, in document order (阅读's Element.textNodes()). */
        public List<String> textNodes() {
            List<String> out = new ArrayList<String>();
            for (int i = 0; i < nodes.size(); i++) {
                Object n = nodes.get(i);
                if (n instanceof String) {
                    out.add((String) n);
                }
            }
            return out;
        }

        /**
         * 阅读's {@code @textNodes}: the element's own text nodes, each trimmed, joined with
         * "\n".
         *
         * <p>This is how a site that separates paragraphs with {@code <br>} keeps them apart
         * (新笔趣阁's {@code tag.article@textNodes}). Treating the suffix as a synonym of
         * {@code @text} collapsed the whole chapter onto one line.
         */
        public String textNodesJoined() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < nodes.size(); i++) {
                Object n = nodes.get(i);
                if (!(n instanceof String)) {
                    continue;
                }
                String t = ((String) n).trim();
                if (t.length() == 0) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(t);
            }
            return sb.toString();
        }

        /** All descendant text, whitespace-collapsed. */
        public String allText() {
            StringBuilder sb = new StringBuilder();
            collectText(this, sb);
            return collapse(sb.toString());
        }

        /** innerHTML, reconstructed in document order. */
        public String innerHtml() {
            StringBuilder sb = new StringBuilder();
            appendInnerHtml(this, sb);
            return sb.toString();
        }

        public String outerHtml() {
            StringBuilder sb = new StringBuilder();
            sb.append('<').append(tag);
            for (int i = 0; i < attrNames.size(); i++) {
                sb.append(' ').append(attrNames.get(i)).append("=\"")
                  .append(attrValues.get(i)).append('"');
            }
            sb.append('>');
            sb.append(innerHtml());
            sb.append("</").append(tag).append('>');
            return sb.toString();
        }

        public String describe() {
            return tag + (id != null ? "#" + id : "")
                    + (classes.size() > 0 ? "." + classes.get(0) : "");
        }
    }

    private static void collectText(El e, StringBuilder sb) {
        for (int i = 0; i < e.nodes.size(); i++) {
            Object n = e.nodes.get(i);
            if (n instanceof String) {
                sb.append((String) n);
            } else {
                collectText((El) n, sb);
            }
        }
    }

    private static void appendInnerHtml(El e, StringBuilder sb) {
        for (int i = 0; i < e.nodes.size(); i++) {
            Object n = e.nodes.get(i);
            if (n instanceof String) {
                sb.append(escapeText((String) n));
            } else {
                sb.append(((El) n).outerHtml());
            }
        }
    }

    private static String escapeText(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Collapse runs of whitespace, keeping single spaces. */
    public static String collapse(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        boolean prevSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\u00A0') {
                if (!prevSpace) {
                    sb.append(' ');
                    prevSpace = true;
                }
            } else {
                sb.append(c);
                prevSpace = false;
            }
        }
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- parse

    /** Tags that never have a closing tag. */
    private static final String[] VOID_TAGS = {
            "area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr"
    };

    private static boolean isVoid(String tag) {
        for (int i = 0; i < VOID_TAGS.length; i++) {
            if (VOID_TAGS[i].equalsIgnoreCase(tag)) {
                return true;
            }
        }
        return false;
    }

    /** Tags whose content is raw text and must not be parsed as markup. */
    private static final String[] RAW_TAGS = {"script", "style", "textarea", "title"};

    private static boolean isRaw(String tag) {
        for (int i = 0; i < RAW_TAGS.length; i++) {
            if (RAW_TAGS[i].equalsIgnoreCase(tag)) {
                return true;
            }
        }
        return false;
    }

    /** Parse a document; returns the synthetic root element. */
    public static El parse(String html) {
        El root = new El();
        root.tag = "#root";
        if (html == null) {
            return root;
        }
        El cur = root;
        int i = 0;
        int n = html.length();
        while (i < n) {
            int lt = html.indexOf('<', i);
            if (lt < 0) {
                appendText(cur, html.substring(i));
                break;
            }
            if (lt > i) {
                appendText(cur, html.substring(i, lt));
            }
            // comment / doctype / CDATA
            if (html.startsWith("<!--", lt)) {
                int end = html.indexOf("-->", lt + 4);
                i = (end < 0) ? n : end + 3;
                continue;
            }
            if (html.startsWith("<!", lt) || html.startsWith("<?", lt)) {
                int end = html.indexOf('>', lt);
                i = (end < 0) ? n : end + 1;
                continue;
            }
            // closing tag
            if (lt + 1 < n && html.charAt(lt + 1) == '/') {
                int end = html.indexOf('>', lt);
                if (end < 0) {
                    break;
                }
                String name = html.substring(lt + 2, end).trim().toLowerCase();
                int sp = name.indexOf(' ');
                if (sp > 0) {
                    name = name.substring(0, sp);
                }
                // pop to the matching ancestor
                El p = cur;
                while (p != null && p != root && !name.equalsIgnoreCase(p.tag)) {
                    p = p.parent;
                }
                cur = (p != null && p != root) ? p.parent : cur;
                if (cur == null) {
                    cur = root;
                }
                i = end + 1;
                continue;
            }
            // opening tag
            int end = findTagEnd(html, lt);
            if (end < 0) {
                appendText(cur, html.substring(lt));
                break;
            }
            String raw = html.substring(lt + 1, end);
            boolean selfClose = raw.endsWith("/");
            if (selfClose) {
                raw = raw.substring(0, raw.length() - 1);
            }
            El el = parseOpenTag(raw);
            i = end + 1;
            if (el == null) {
                continue;
            }
            el.parent = cur;
            cur.children.add(el);
            cur.nodes.add(el);

            if (isVoid(el.tag) || selfClose) {
                continue;
            }
            if (isRaw(el.tag)) {
                // consume raw text up to the matching close tag
                String closeTag = "</" + el.tag;
                int close = indexOfIgnoreCase(html, closeTag, i);
                if (close < 0) {
                    el.nodes.add(html.substring(i));
                    i = n;
                } else {
                    el.nodes.add(html.substring(i, close));
                    int gt = html.indexOf('>', close);
                    i = (gt < 0) ? n : gt + 1;
                }
                continue;
            }
            cur = el;
        }
        return root;
    }

    /** Find the '>' that ends a tag, skipping quoted attribute values. */
    private static int findTagEnd(String html, int lt) {
        char quote = 0;
        for (int i = lt + 1; i < html.length(); i++) {
            char c = html.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    private static El parseOpenTag(String raw) {
        int i = 0;
        int n = raw.length();
        while (i < n && Character.isWhitespace(raw.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < n && !Character.isWhitespace(raw.charAt(i)) && raw.charAt(i) != '/') {
            i++;
        }
        if (i == start) {
            return null;
        }
        El el = new El();
        el.tag = raw.substring(start, i).toLowerCase();

        // attributes
        while (i < n) {
            while (i < n && Character.isWhitespace(raw.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            int ns = i;
            while (i < n && !Character.isWhitespace(raw.charAt(i)) && raw.charAt(i) != '=') {
                i++;
            }
            String name = raw.substring(ns, i);
            while (i < n && Character.isWhitespace(raw.charAt(i))) {
                i++;
            }
            String value = "";
            if (i < n && raw.charAt(i) == '=') {
                i++;
                while (i < n && Character.isWhitespace(raw.charAt(i))) {
                    i++;
                }
                if (i < n && (raw.charAt(i) == '"' || raw.charAt(i) == '\'')) {
                    char q = raw.charAt(i);
                    i++;
                    int vs = i;
                    while (i < n && raw.charAt(i) != q) {
                        i++;
                    }
                    value = raw.substring(vs, Math.min(i, n));
                    if (i < n) {
                        i++;
                    }
                } else {
                    int vs = i;
                    while (i < n && !Character.isWhitespace(raw.charAt(i))) {
                        i++;
                    }
                    value = raw.substring(vs, i);
                }
            }
            if (name.length() == 0) {
                continue;
            }
            String lname = name.toLowerCase();
            if (lname.equals("id")) {
                el.id = value;
            } else if (lname.equals("class")) {
                String[] parts = value.split("\\s+");
                for (int k = 0; k < parts.length; k++) {
                    if (parts[k].length() > 0) {
                        el.classes.add(parts[k]);
                    }
                }
            }
            el.attrNames.add(lname);
            el.attrValues.add(decodeEntities(value));
        }
        return el;
    }

    private static void appendText(El cur, String text) {
        if (text == null || text.length() == 0) {
            return;
        }
        // Kept as its own node, in place: the position of a text run relative to the elements
        // around it is what makes <p>a<br>b</p> come out as "a\nb" instead of "ab\n".
        cur.nodes.add(decodeEntities(text));
    }

    private static int indexOfIgnoreCase(String haystack, String needle, int from) {
        if (haystack == null || needle == null) {
            return -1;
        }
        int hl = haystack.length();
        int nl = needle.length();
        for (int i = Math.max(0, from); i + nl <= hl; i++) {
            if (haystack.regionMatches(true, i, needle, 0, nl)) {
                return i;
            }
        }
        return -1;
    }

    /** Decode the entities that actually show up in novel pages. */
    public static String decodeEntities(String s) {
        if (s == null || s.indexOf('&') < 0) {
            return s == null ? "" : s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c != '&') {
                sb.append(c);
                i++;
                continue;
            }
            int semi = s.indexOf(';', i);
            if (semi < 0 || semi - i > 12) {
                sb.append(c);
                i++;
                continue;
            }
            String ent = s.substring(i + 1, semi);
            String rep = entityValue(ent);
            if (rep == null) {
                sb.append(c);
                i++;
            } else {
                sb.append(rep);
                i = semi + 1;
            }
        }
        return sb.toString();
    }

    private static String entityValue(String ent) {
        if (ent.length() == 0) {
            return null;
        }
        if (ent.charAt(0) == '#') {
            try {
                int cp = (ent.length() > 1 && (ent.charAt(1) == 'x' || ent.charAt(1) == 'X'))
                        ? Integer.parseInt(ent.substring(2), 16)
                        : Integer.parseInt(ent.substring(1));
                if (cp <= 0 || cp > 0x10FFFF) {
                    return null;
                }
                if (cp <= 0xFFFF) {
                    return String.valueOf((char) cp);
                }
                int v = cp - 0x10000;
                return new String(new char[]{
                        (char) (0xD800 + (v >> 10)), (char) (0xDC00 + (v & 0x3FF))});
            } catch (Throwable t) {
                return null;
            }
        }
        String e = ent.toLowerCase();
        if (e.equals("amp")) return "&";
        if (e.equals("lt")) return "<";
        if (e.equals("gt")) return ">";
        if (e.equals("quot")) return "\"";
        if (e.equals("apos")) return "'";
        if (e.equals("nbsp")) return "\u00A0";
        if (e.equals("copy")) return "\u00A9";
        if (e.equals("reg")) return "\u00AE";
        if (e.equals("hellip")) return "\u2026";
        if (e.equals("mdash")) return "\u2014";
        if (e.equals("ndash")) return "\u2013";
        if (e.equals("ldquo")) return "\u201C";
        if (e.equals("rdquo")) return "\u201D";
        if (e.equals("lsquo")) return "\u2018";
        if (e.equals("rsquo")) return "\u2019";
        if (e.equals("middot")) return "\u00B7";
        if (e.equals("bull")) return "\u2022";
        if (e.equals("times")) return "\u00D7";
        if (e.equals("divide")) return "\u00F7";
        if (e.equals("laquo")) return "\u00AB";
        if (e.equals("raquo")) return "\u00BB";
        return null;
    }

    // ------------------------------------------------------------- selector

    /** One simple selector step: tag.cls#id[attr op value]. */
    private static final class Simple {
        String tag;
        String id;
        List<String> classes = new ArrayList<String>();
        String attrName;
        String attrValue;
        int op;                 // 0 none, 1 =, 2 *=, 3 ^=, 4 $=
        int combinator;         // 0 descendant, 1 child
    }

    /**
     * Select all matching elements, in document order.
     * selector may contain descendant (' ') and child ('>') combinators.
     */
    public static List<El> select(El scope, String selector) {
        List<El> out = new ArrayList<El>();
        if (scope == null || selector == null) {
            return out;
        }
        String sel = selector.trim();
        if (sel.length() == 0) {
            return out;
        }
        // multiple selectors separated by comma
        List<String> parts = splitTop(sel, ',');
        for (int p = 0; p < parts.size(); p++) {
            List<Simple> chain = parseChain(parts.get(p).trim());
            if (chain.size() == 0) {
                continue;
            }
            List<El> candidates = new ArrayList<El>();
            collectAll(scope, candidates);
            for (int i = 0; i < candidates.size(); i++) {
                El e = candidates.get(i);
                if (matchesChain(e, chain, chain.size() - 1)) {
                    if (!out.contains(e)) {
                        out.add(e);
                    }
                }
            }
        }
        return out;
    }

    /** First match, or null. */
    public static El selectFirst(El scope, String selector) {
        List<El> r = select(scope, selector);
        return r.size() > 0 ? r.get(0) : null;
    }

    private static void collectAll(El e, List<El> out) {
        for (int i = 0; i < e.children.size(); i++) {
            El c = e.children.get(i);
            out.add(c);
            collectAll(c, out);
        }
    }

    private static boolean matchesChain(El e, List<Simple> chain, int idx) {
        if (idx < 0) {
            return true;
        }
        Simple s = chain.get(idx);
        if (!matchesSimple(e, s)) {
            return false;
        }
        if (idx == 0) {
            return true;
        }
        int comb = s.combinator;
        if (comb == 1) {
            return e.parent != null && matchesChain(e.parent, chain, idx - 1);
        }
        El p = e.parent;
        while (p != null && !"#root".equals(p.tag)) {
            if (matchesChain(p, chain, idx - 1)) {
                return true;
            }
            p = p.parent;
        }
        return false;
    }

    private static boolean matchesSimple(El e, Simple s) {
        if (s.tag != null && !s.tag.equalsIgnoreCase(e.tag)) {
            return false;
        }
        if (s.id != null && !s.id.equals(e.id)) {
            return false;
        }
        for (int i = 0; i < s.classes.size(); i++) {
            if (!e.hasClass(s.classes.get(i))) {
                return false;
            }
        }
        if (s.attrName != null) {
            String v = e.attr(s.attrName);
            if (v == null) {
                return false;
            }
            if (s.op == 0) {
                return true;
            }
            String want = s.attrValue == null ? "" : s.attrValue;
            if (s.op == 1) {
                return v.equals(want);
            }
            if (s.op == 2) {
                return v.indexOf(want) >= 0;
            }
            if (s.op == 3) {
                return v.startsWith(want);
            }
            if (s.op == 4) {
                return v.endsWith(want);
            }
        }
        return true;
    }

    private static List<Simple> parseChain(String sel) {
        List<Simple> chain = new ArrayList<Simple>();
        int i = 0;
        int n = sel.length();
        int pendingCombinator = 0;
        while (i < n) {
            char c = sel.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n') {
                i++;
                // look ahead for '>'
                int j = i;
                while (j < n && Character.isWhitespace(sel.charAt(j))) {
                    j++;
                }
                if (j < n && sel.charAt(j) == '>') {
                    pendingCombinator = 1;
                    i = j + 1;
                } else {
                    pendingCombinator = 0;
                }
                continue;
            }
            if (c == '>') {
                pendingCombinator = 1;
                i++;
                continue;
            }
            // read one simple selector
            int start = i;
            while (i < n) {
                char d = sel.charAt(i);
                // '@' is accepted as a step separator: 阅读 sources write descendant
                // relationships as "class.pages@tag.a" (and then "@href" for the attribute),
                // so it has to terminate a step here or the whole selector matches nothing.
                if (d == ' ' || d == '\t' || d == '\n' || d == '>' || d == '@') {
                    break;
                }
                i++;
            }
            if (i == start && sel.charAt(start) == '@') {
                i++;                            // skip the separator itself
                continue;
            }
            if (i == start) {
                i++;
                continue;
            }
            Simple s = parseSimple(sel.substring(start, i));
            if (s != null) {
                s.combinator = pendingCombinator;
                chain.add(s);
            }
            pendingCombinator = 0;
        }
        return chain;
    }

    private static Simple parseSimple(String token) {
        if (token == null || token.length() == 0) {
            return null;
        }
        Simple s = new Simple();
        int i = 0;
        int n = token.length();
        // tag name
        int ts = i;
        while (i < n && token.charAt(i) != '.' && token.charAt(i) != '#' && token.charAt(i) != '[') {
            i++;
        }
        if (i > ts) {
            s.tag = token.substring(ts, i);
            if (s.tag.equals("*")) {
                s.tag = null;
            }
        }
        while (i < n) {
            char c = token.charAt(i);
            if (c == '.') {
                i++;
                int cs = i;
                while (i < n && token.charAt(i) != '.' && token.charAt(i) != '#'
                        && token.charAt(i) != '[') {
                    i++;
                }
                if (i > cs) {
                    s.classes.add(token.substring(cs, i));
                }
            } else if (c == '#') {
                i++;
                int cs = i;
                while (i < n && token.charAt(i) != '.' && token.charAt(i) != '#'
                        && token.charAt(i) != '[') {
                    i++;
                }
                if (i > cs) {
                    s.id = token.substring(cs, i);
                }
            } else if (c == '[') {
                int close = token.indexOf(']', i);
                if (close < 0) {
                    break;
                }
                parseAttrSelector(token.substring(i + 1, close), s);
                i = close + 1;
            } else {
                i++;
            }
        }
        return s;
    }

    private static void parseAttrSelector(String inner, Simple s) {
        if (inner == null) {
            return;
        }
        String body = inner.trim();
        int op = 0;
        int pos = -1;
        if ((pos = body.indexOf("*=")) >= 0) {
            op = 2;
        } else if ((pos = body.indexOf("^=")) >= 0) {
            op = 3;
        } else if ((pos = body.indexOf("$=")) >= 0) {
            op = 4;
        } else if ((pos = body.indexOf('=')) >= 0) {
            op = 1;
        }
        if (op == 0) {
            s.attrName = body;
            s.op = 0;
            return;
        }
        s.attrName = body.substring(0, pos).trim();
        String v = body.substring(pos + 2).trim();
        if (v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'')
                && v.charAt(v.length() - 1) == v.charAt(0)) {
            v = v.substring(1, v.length() - 1);
        }
        s.attrValue = v;
        s.op = op;
    }

    private static List<String> splitTop(String s, char sep) {
        List<String> out = new ArrayList<String>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
            } else if (c == sep && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    // ------------------------------------------------------------ extraction

    /**
     * Apply an extraction suffix such as "@text", "@html", "@href".
     * Returns null when no suffix is present (caller decides the default).
     */
    public static String extract(El e, String suffix) {
        if (e == null || suffix == null) {
            return null;
        }
        String s = suffix.trim().toLowerCase();
        if (s.equals("text")) {
            return collapse(e.allText());
        }
        if (s.equals("owntext")) {
            return collapse(e.text());
        }
        if (s.equals("textnodes")) {
            // 阅读 reads only the element's OWN text nodes and joins them with "\n"; pages that
            // separate paragraphs with <br> rely on exactly that. allText() would collapse the
            // line breaks into spaces and glue the chapter into one paragraph.
            return e.textNodesJoined();
        }
        if (s.equals("html") || s.equals("innerhtml")) {
            return e.innerHtml();
        }
        if (s.equals("outerhtml")) {
            return e.outerHtml();
        }
        if (s.equals("href") || s.equals("src") || s.equals("value")
                || s.equals("title") || s.equals("alt") || s.equals("content")) {
            String v = e.attr(s);
            return v == null ? "" : v;
        }
        String v = e.attr(s);
        return v == null ? "" : v;
    }
}

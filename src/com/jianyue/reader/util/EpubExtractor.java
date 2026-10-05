package com.jianyue.reader.util;

import com.jianyue.reader.rule.Html;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Minimal EPUB (2.0 and 3.0) reader for Android 1.0.
 *
 * An EPUB is a ZIP holding XHTML documents plus an OPF package file that lists them in
 * reading order. That means it needs only {@code java.util.zip} (present at API 1) and this
 * project's own tolerant HTML parser -- no XML library, no third-party dependency, and no
 * DOM Level 2/3 (which API 1 lacks).
 *
 * What is skipped: images, CSS, fonts, the <head>, and the nav/TOC document (its links are
 * not chapter text). One XHTML document becomes one chapter, which is how the format is
 * authored in practice; a document that is itself huge is split further by the caller using
 * the same chapter-heading rules as a .txt.
 *
 * API-1 notes: no try-with-resources, no String.isEmpty(), no java.nio.
 */
public final class EpubExtractor {

    /** One spine document, flattened to plain text. */
    public static class Section {
        public String name = "";
        public String text = "";
    }

    /** Everything worth keeping out of one EPUB. */
    public static class Epub {
        public String title = "";
        public String author = "";
        public List<Section> sections = new ArrayList<Section>();
    }

    /** Refuse to inflate a single entry larger than this (guards against a zip bomb). */
    private static final int MAX_ENTRY_BYTES = 4 * 1024 * 1024;

    private EpubExtractor() {
    }

    public static Epub extract(File epubFile) {
        Map<String, byte[]> entries = readTextEntries(epubFile);
        if (entries == null || entries.size() == 0) {
            return null;
        }
        Epub out = new Epub();

        String opfPath = findOpfPath(entries);
        List<String> order = new ArrayList<String>();
        if (opfPath != null) {
            byte[] opfBytes = lookup(entries, opfPath);
            if (opfBytes != null) {
                String opf = TextCodec.decodeAuto(opfBytes, 0, opfBytes.length);
                out.title = tagText(opf, "dc:title");
                out.author = tagText(opf, "dc:creator");
                order = spineHrefs(opf, dirOf(opfPath));
            }
        }
        if (order.size() == 0) {
            // no usable package document: every XHTML document, in archive order
            List<String> keys = keysSorted(entries);
            for (int i = 0; i < keys.size(); i++) {
                if (isMarkup(keys.get(i))) {
                    order.add(keys.get(i));
                }
            }
        }

        for (int i = 0; i < order.size(); i++) {
            byte[] b = lookup(entries, order.get(i));
            if (b == null) {
                continue;
            }
            String xhtml = TextCodec.decodeAuto(b, 0, b.length);
            String body = htmlToText(xhtml);
            if (body.length() < 20) {
                continue;                  // cover image page, nav, copyright stub
            }
            Section s = new Section();
            s.name = firstHeading(xhtml);
            s.text = body;
            out.sections.add(s);
        }
        if (out.sections.size() == 0) {
            return null;
        }
        return out;
    }

    // --------------------------------------------------------------- HTML -> text

    private static final String[] BLOCK_TAGS = {
            "p", "div", "br", "h1", "h2", "h3", "h4", "h5", "h6", "li", "ul", "ol",
            "tr", "td", "th", "table", "section", "article", "blockquote", "pre",
            "hr", "dd", "dt", "dl", "figure", "figcaption", "header", "footer"
    };

    /**
     * Flatten an XHTML document to readable text.
     *
     * Block-level elements become line breaks, so paragraphs survive; <script>/<style> and
     * the <head> are dropped. Html.parse is the project's own tolerant parser, which matters
     * because real-world EPUB documents are frequently not well-formed XML.
     */
    public static String htmlToText(String xhtml) {
        if (xhtml == null) {
            return "";
        }
        Html.El root = Html.parse(xhtml);
        Html.El scope = root;
        try {
            Html.El body = Html.selectFirst(root, "body");
            if (body != null) {
                scope = body;
            }
        } catch (Throwable ignored) {
        }
        StringBuilder sb = new StringBuilder(xhtml.length() / 2);
        walk(scope, sb);
        return tidy(sb.toString());
    }

    private static void walk(Html.El e, StringBuilder sb) {
        String tag = e.tag == null ? "" : e.tag.toLowerCase();
        if ("script".equals(tag) || "style".equals(tag) || "head".equals(tag)
                || "svg".equals(tag) || "nav".equals(tag) || "title".equals(tag)) {
            return;
        }
        boolean block = isBlock(tag);
        if (block && sb.length() > 0) {
            sb.append('\n');
        }
        // Document order: a text run and the elements around it must be visited as they appear,
        // otherwise "<p>a<br>b</p>" flattens to "ab" with the break behind it.
        for (int i = 0; i < e.nodes.size(); i++) {
            Object n = e.nodes.get(i);
            if (n instanceof String) {
                sb.append((String) n);
            } else {
                walk((Html.El) n, sb);
            }
        }
        if (block) {
            sb.append('\n');
        }
    }

    private static boolean isBlock(String tag) {
        for (int i = 0; i < BLOCK_TAGS.length; i++) {
            if (BLOCK_TAGS[i].equals(tag)) {
                return true;
            }
        }
        return false;
    }

    /** Normalise whitespace: trim every line, collapse runs of blanks to one. */
    static String tidy(String s) {
        if (s == null || s.length() == 0) {
            return "";
        }
        String norm = s.replace('\r', '\n');
        String[] lines = norm.split("\n");
        StringBuilder sb = new StringBuilder(norm.length());
        int blanks = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = squash(lines[i]);
            if (line.length() == 0) {
                blanks++;
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
                if (blanks > 0) {
                    sb.append('\n');
                }
            }
            blanks = 0;
            sb.append(line);
        }
        return sb.toString();
    }

    private static String squash(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean prevSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\u00A0' || c == '\u3000' || c == '\u000B') {
                if (sb.length() > 0 && !prevSpace) {
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

    /** Chapter name: the first heading in the document, else its <title>. */
    static String firstHeading(String xhtml) {
        for (int lvl = 1; lvl <= 6; lvl++) {
            String t = tagText(xhtml, "h" + lvl);
            if (t.length() > 0 && t.length() <= 60) {
                return t;
            }
        }
        String t = tagText(xhtml, "title");
        if (t.length() > 0 && t.length() <= 60) {
            return t;
        }
        return "";
    }

    /** Text of the first {@code <tag>...</tag>} in the raw markup (attribute-safe). */
    public static String tagText(String html, String tag) {
        if (html == null || html.length() == 0) {
            return "";
        }
        int a = indexOfIgnoreCase(html, "<" + tag, 0);
        while (a >= 0) {
            int after = a + 1 + tag.length();
            if (after < html.length()) {
                char c = html.charAt(after);
                if (c == '>' || c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '/') {
                    break;
                }
            }
            a = indexOfIgnoreCase(html, "<" + tag, a + 1);
        }
        if (a < 0) {
            return "";
        }
        int open = html.indexOf('>', a);
        if (open < 0) {
            return "";
        }
        int close = indexOfIgnoreCase(html, "</" + tag, open);
        if (close < 0) {
            return "";
        }
        String inner = html.substring(open + 1, close);
        String txt = htmlToText(inner).replace('\n', ' ');
        return squash(txt);
    }

    // ------------------------------------------------------------------- zip

    /**
     * Read every markup entry into memory. Images and fonts are skipped WITHOUT being
     * inflated, which is what keeps a picture-heavy EPUB cheap.
     */
    private static Map<String, byte[]> readTextEntries(File f) {
        Map<String, byte[]> map = new HashMap<String, byte[]>();
        ZipInputStream zin = null;
        try {
            zin = new ZipInputStream(new FileInputStream(f));
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                String n = e.getName();
                if (n == null || n.length() == 0) {
                    continue;
                }
                if (!isMarkup(n) && !n.toLowerCase().endsWith(".opf")
                        && !n.toLowerCase().endsWith(".ncx")) {
                    continue;
                }
                byte[] data = readEntry(zin, MAX_ENTRY_BYTES);
                if (data == null) {
                    continue;
                }
                putOnce(map, n, data);
                putOnce(map, baseName(n), data);      // href/basename fallback
            }
        } catch (Throwable t) {
            if (map.size() == 0) {
                return null;
            }
        } finally {
            close(zin);
        }
        return map;
    }

    private static void putOnce(Map<String, byte[]> m, String key, byte[] v) {
        if (key == null || key.length() == 0 || m.containsKey(key)) {
            return;
        }
        m.put(key, v);
    }

    private static byte[] readEntry(InputStream in, int max) {
        ByteArrayOutputStream out = null;
        try {
            out = new ByteArrayOutputStream(16384);
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > max) {
                    return null;
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            close(out);
        }
    }

    private static void close(Object c) {
        try {
            if (c instanceof ZipInputStream) {
                ((ZipInputStream) c).close();
            } else if (c instanceof java.io.OutputStream) {
                ((java.io.OutputStream) c).close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isMarkup(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm");
    }

    private static String baseName(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    private static String dirOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i + 1);
    }

    /** Case-insensitive lookup: exact name, percent-decoded name, then basename. */
    private static byte[] lookup(Map<String, byte[]> m, String name) {
        if (name == null) {
            return null;
        }
        byte[] v = m.get(name);
        if (v != null) {
            return v;
        }
        String dec = percentDecode(name);
        v = m.get(dec);
        if (v != null) {
            return v;
        }
        v = m.get(baseName(dec));
        if (v != null) {
            return v;
        }
        java.util.Iterator<String> it = m.keySet().iterator();
        while (it.hasNext()) {
            String k = it.next();
            if (k.equalsIgnoreCase(dec) || k.equalsIgnoreCase(baseName(dec))) {
                return m.get(k);
            }
        }
        return null;
    }

    private static List<String> keysSorted(Map<String, byte[]> m) {
        List<String> keys = new ArrayList<String>();
        java.util.Iterator<String> it = m.keySet().iterator();
        while (it.hasNext()) {
            String k = it.next();
            if (k.indexOf('/') >= 0) {          // only full paths, not the basename aliases
                keys.add(k);
            }
        }
        for (int i = 1; i < keys.size(); i++) {
            String cur = keys.get(i);
            int j = i - 1;
            while (j >= 0 && keys.get(j).compareToIgnoreCase(cur) > 0) {
                keys.set(j + 1, keys.get(j));
                j--;
            }
            keys.set(j + 1, cur);
        }
        return keys;
    }

    private static String percentDecode(String s) {
        if (s == null || s.indexOf('%') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = hex(s.charAt(i + 1));
                int lo = hex(s.charAt(i + 2));
                if (hi >= 0 && lo >= 0) {
                    sb.append((char) (hi * 16 + lo));
                    i += 3;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    // ------------------------------------------------------------- container/OPF

    private static String findOpfPath(Map<String, byte[]> entries) {
        byte[] c = lookup(entries, "META-INF/container.xml");
        if (c != null) {
            String xml = TextCodec.decodeAuto(c, 0, c.length);
            String p = attrValue(xml, "full-path");
            if (p != null && p.length() > 0) {
                String norm = p.replace('\\', '/');
                if (lookup(entries, norm) != null) {
                    return norm;
                }
            }
        }
        List<String> keys = keysSorted(entries);
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).toLowerCase().endsWith(".opf")) {
                return keys.get(i);
            }
        }
        return null;
    }

    /**
     * Reading order from the OPF: the manifest maps id -> href, the spine lists the ids.
     * Scanned by hand because API 1 has no usable XML parser for namespaced documents and
     * the markup here is machine-generated and regular.
     */
    private static List<String> spineHrefs(String opf, String dir) {
        Map<String, String> idToHref = new HashMap<String, String>();
        int i = 0;
        while (true) {
            int a = indexOfIgnoreCase(opf, "<item", i);
            if (a < 0) {
                break;
            }
            int b = opf.indexOf('>', a);
            if (b < 0) {
                break;
            }
            String tag = opf.substring(a, b);
            i = b + 1;
            if (startsWithIgnoreCase(tag, "<itemref")) {
                continue;
            }
            String id = attrValue(tag, "id");
            String href = attrValue(tag, "href");
            String mt = attrValue(tag, "media-type");
            if (id == null || href == null) {
                continue;
            }
            if (mt == null || mt.indexOf("xhtml") >= 0 || mt.indexOf("html") >= 0) {
                idToHref.put(id, href);
            }
        }

        List<String> order = new ArrayList<String>();
        i = 0;
        while (true) {
            int a = indexOfIgnoreCase(opf, "<itemref", i);
            if (a < 0) {
                break;
            }
            int b = opf.indexOf('>', a);
            if (b < 0) {
                break;
            }
            String tag = opf.substring(a, b);
            i = b + 1;
            String ref = attrValue(tag, "idref");
            if (ref == null) {
                continue;
            }
            String href = idToHref.get(ref);
            if (href == null) {
                continue;
            }
            order.add(joinPath(dir, percentDecode(href)));
        }
        return order;
    }

    /** Value of an attribute inside a raw tag, quote-aware. */
    static String attrValue(String tag, String name) {
        if (tag == null) {
            return null;
        }
        int i = 0;
        while (true) {
            int p = indexOfIgnoreCase(tag, name, i);
            if (p < 0) {
                return null;
            }
            int after = p + name.length();
            if (p > 0 && !Character.isWhitespace(tag.charAt(p - 1))) {
                i = after;
                continue;
            }
            int q = after;
            while (q < tag.length() && Character.isWhitespace(tag.charAt(q))) {
                q++;
            }
            if (q >= tag.length() || tag.charAt(q) != '=') {
                i = after;
                continue;
            }
            q++;
            while (q < tag.length() && Character.isWhitespace(tag.charAt(q))) {
                q++;
            }
            if (q >= tag.length()) {
                return null;
            }
            char quote = tag.charAt(q);
            if (quote == '"' || quote == '\'') {
                int end = tag.indexOf(quote, q + 1);
                if (end < 0) {
                    return null;
                }
                return Html.decodeEntities(tag.substring(q + 1, end));
            }
            int end = q;
            while (end < tag.length() && !Character.isWhitespace(tag.charAt(end))
                    && tag.charAt(end) != '>') {
                end++;
            }
            return tag.substring(q, end);
        }
    }

    private static String joinPath(String dir, String href) {
        if (href == null) {
            return "";
        }
        if (href.startsWith("/")) {
            return href.substring(1);
        }
        if (dir == null || dir.length() == 0) {
            return href;
        }
        return dir + href;
    }

    private static boolean startsWithIgnoreCase(String s, String prefix) {
        return s != null && s.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static int indexOfIgnoreCase(String s, String needle, int from) {
        if (s == null || needle == null) {
            return -1;
        }
        int limit = s.length() - needle.length();
        for (int i = Math.max(0, from); i <= limit; i++) {
            if (s.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }
}

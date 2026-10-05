package com.jianyue.reader.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A book source, as imported from a JSON file.
 *
 * Field names follow the 阅读/Legado convention so existing book sources can be pasted
 * in unchanged. See 书源JSON格式说明.md for the full format and the API-1 availability
 * notes.
 *
 * Plain bean, no annotations: API 1 has no Room and no support library.
 */
public class BookSource {

    public String name = "";
    public String url = "";            // site root, used to build the search URL and to absolutise links
    public String group = "";
    public boolean enabled = true;
    public int order = 0;              // higher wins when several sources are enabled

    /**
     * 0 = 文本, 1 = 音频, 2 = 图片, 3 = 文件 (same numbering as 阅读).
     * Only text sources are implemented; the field is kept so an imported audio/image source
     * can be reported as unsupported instead of silently misbehaving.
     */
    public int type = 0;

    /** A regex the book URL must match; used to recognise which source owns a URL. */
    public String bookUrlPattern = "";

    /** Raw header block, e.g. "User-Agent@xxx\nReferer@yyy" - see headers(). */
    public String header = "";

    /** Free-text note from the source author (bookSourceComment). */
    public String comment = "";

    /** 发现 (explore) entry: the second browsing path besides search. */
    public String exploreUrl = "";
    public Rules exploreRules = new Rules();

    // search
    public String searchUrl = "";
    public Rules searchRules = new Rules();

    // book detail
    public Rules bookInfoRules = new Rules();

    // table of contents
    public Rules tocRules = new Rules();

    // chapter content
    public Rules contentRules = new Rules();

    /**
     * Parse the header block into individual headers.
     *
     * 阅读 stores headers as "Name@value" lines separated by a literal "\n". They matter a
     * lot in practice: most Chinese novel aggregators reject requests without the right
     * Referer or User-Agent.
     */
    public java.util.Map<String, String> headers() {
        java.util.Map<String, String> out = new java.util.HashMap<String, String>();
        if (header == null || header.trim().length() == 0) {
            return out;
        }
        String h = header.replace("\\n", "\n");
        String[] lines = h.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0) {
                continue;
            }
            int at = line.indexOf('@');
            if (at <= 0) {
                continue;
            }
            String k = line.substring(0, at).trim();
            String v = line.substring(at + 1).trim();
            if (k.length() > 0) {
                out.put(k, v);
            }
        }
        return out;
    }

    /** True when this source can actually be read by this app (text sources only). */
    public boolean isSupported() {
        return type == 0;
    }

    public String typeName() {
        switch (type) {
            case 1: return "音频";
            case 2: return "图片";
            case 3: return "文件";
            default: return "文本";
        }
    }

    /** Rule group, matching the JSON object shape. */
    public static class Rules {
        // shared
        public String bookList = "";

        // search / bookInfo fields
        public String name = "";
        public String author = "";
        public String kind = "";
        public String intro = "";
        public String wordCount = "";
        public String lastChapter = "";
        public String coverUrl = "";
        public String bookUrl = "";

        // toc
        public String tocUrl = "";
        public String chapterList = "";
        public String chapterName = "";
        public String chapterUrl = "";
        public String isVip = "";
        /** selector (or "next page" URL) for a 目录 that spans several pages */
        public String nextTocUrl = "";

        // content
        public String content = "";
        public String nextContentUrl = "";

        public boolean isEmpty() {
            return bookList.length() == 0 && name.length() == 0 && chapterList.length() == 0
                    && content.length() == 0 && bookUrl.length() == 0;
        }
    }

    /** Parse one source object. Returns null when it has no usable name/url. */
    public static BookSource fromJson(JSONObject o) {
        if (o == null) {
            return null;
        }
        BookSource s = new BookSource();
        s.name = o.optString("bookSourceName", "").trim();
        s.url = o.optString("bookSourceUrl", "").trim();
        s.group = o.optString("bookSourceGroup", "").trim();
        s.enabled = o.optBoolean("enabled", true);
        s.order = o.optInt("customOrder", 0);
        s.searchUrl = o.optString("searchUrl", "").trim();

        // Fields added for 阅读 compatibility. Only the ones that are unambiguous are read:
        // type/pattern/header/explore. Login, concurrency and weight are intentionally NOT
        // parsed, because this app cannot honour them and silently pretending to would be
        // worse than ignoring them.
        s.type = parseType(o);
        s.bookUrlPattern = o.optString("bookUrlPattern", "").trim();
        s.header = o.optString("header", "");
        s.comment = o.optString("bookSourceComment", "");
        s.exploreUrl = o.optString("exploreUrl", "").trim();

        s.searchRules = parseRules(o.optJSONObject("ruleSearch"));
        s.bookInfoRules = parseRules(o.optJSONObject("ruleBookInfo"));
        s.tocRules = parseRules(o.optJSONObject("ruleToc"));
        s.contentRules = parseRules(o.optJSONObject("ruleContent"));
        s.exploreRules = parseRules(o.optJSONObject("ruleExplore"));

        // a source with no URL cannot resolve relative links
        if (s.url.length() == 0) {
            return null;
        }
        if (s.name.length() == 0) {
            s.name = s.url;
        }
        return s;
    }

    /**
     * Read bookSourceType, which 阅读 writes as a STRING ("text", "audio", "image", "file")
     * in exported sources even though the database column is an INTEGER.
     *
     * This matters: reading it with optInt() silently yields 0 (= text) for every exported
     * source, so an audio book source would be imported and then fail confusingly instead of
     * being reported as unsupported. Both spellings are accepted.
     */
    private static int parseType(JSONObject o) {
        Object raw = o.opt("bookSourceType");
        if (raw == null) {
            return 0;
        }
        if (raw instanceof Number) {
            return ((Number) raw).intValue();
        }
        String v = String.valueOf(raw).trim().toLowerCase();
        if (v.length() == 0) {
            return 0;
        }
        if (v.indexOf("audio") >= 0 || v.indexOf("听") >= 0) {
            return 1;
        }
        if (v.indexOf("image") >= 0 || v.indexOf("图片") >= 0) {
            return 2;
        }
        if (v.indexOf("file") >= 0 || v.indexOf("文件") >= 0) {
            return 3;
        }
        return 0;   // "text" and anything unrecognised
    }

    private static Rules parseRules(JSONObject o) {
        Rules r = new Rules();
        if (o == null) {
            return r;
        }
        r.bookList = o.optString("bookList", "");
        r.name = o.optString("name", "");
        r.author = o.optString("author", "");
        r.kind = o.optString("kind", "");
        r.intro = o.optString("intro", "");
        r.wordCount = o.optString("wordCount", "");
        r.lastChapter = o.optString("lastChapter", "");
        r.coverUrl = o.optString("coverUrl", "");
        r.bookUrl = o.optString("bookUrl", "");
        r.tocUrl = o.optString("tocUrl", "");
        r.chapterList = o.optString("chapterList", "");
        r.chapterName = o.optString("chapterName", "");
        r.chapterUrl = o.optString("chapterUrl", "");
        r.isVip = o.optString("isVip", "");
        r.nextTocUrl = o.optString("nextTocUrl", "");
        r.content = o.optString("content", "");
        r.nextContentUrl = o.optString("nextContentUrl", "");
        return r;
    }

    /**
     * Parse a whole book-source file. Accepts either a single object or an array,
     * which is what 阅读 exports.
     */
    public static List<BookSource> parseFile(String json) {
        List<BookSource> out = new ArrayList<BookSource>();
        if (json == null) {
            return out;
        }
        String t = json.trim();
        if (t.length() == 0) {
            return out;
        }
        try {
            if (t.charAt(0) == '[') {
                JSONArray arr = new JSONArray(t);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    BookSource s = fromJson(o);
                    if (s != null) {
                        out.add(s);
                    }
                }
            } else {
                BookSource s = fromJson(new JSONObject(t));
                if (s != null) {
                    out.add(s);
                }
            }
        } catch (Throwable e) {
            // caller reports the parse failure
        }
        return out;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("bookSourceName", name);
            o.put("bookSourceUrl", url);
            o.put("bookSourceGroup", group);
            o.put("enabled", enabled);
            o.put("customOrder", order);
            o.put("bookSourceType", type);
            o.put("bookSourceComment", comment);
            if (bookUrlPattern.length() > 0) {
                o.put("bookUrlPattern", bookUrlPattern);
            }
            if (header.length() > 0) {
                o.put("header", header);
            }
            if (exploreUrl.length() > 0) {
                o.put("exploreUrl", exploreUrl);
                o.put("ruleExplore", rulesToJson(exploreRules));
            }
            o.put("searchUrl", searchUrl);
            o.put("ruleSearch", rulesToJson(searchRules));
            o.put("ruleBookInfo", rulesToJson(bookInfoRules));
            o.put("ruleToc", rulesToJson(tocRules));
            o.put("ruleContent", rulesToJson(contentRules));
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static JSONObject rulesToJson(Rules r) {
        JSONObject o = new JSONObject();
        try {
            if (r.bookList.length() > 0) o.put("bookList", r.bookList);
            if (r.name.length() > 0) o.put("name", r.name);
            if (r.author.length() > 0) o.put("author", r.author);
            if (r.kind.length() > 0) o.put("kind", r.kind);
            if (r.intro.length() > 0) o.put("intro", r.intro);
            if (r.wordCount.length() > 0) o.put("wordCount", r.wordCount);
            if (r.lastChapter.length() > 0) o.put("lastChapter", r.lastChapter);
            if (r.coverUrl.length() > 0) o.put("coverUrl", r.coverUrl);
            if (r.bookUrl.length() > 0) o.put("bookUrl", r.bookUrl);
            if (r.tocUrl.length() > 0) o.put("tocUrl", r.tocUrl);
            if (r.chapterList.length() > 0) o.put("chapterList", r.chapterList);
            if (r.chapterName.length() > 0) o.put("chapterName", r.chapterName);
            if (r.chapterUrl.length() > 0) o.put("chapterUrl", r.chapterUrl);
            if (r.isVip.length() > 0) o.put("isVip", r.isVip);
            if (r.nextTocUrl.length() > 0) o.put("nextTocUrl", r.nextTocUrl);
            if (r.content.length() > 0) o.put("content", r.content);
            if (r.nextContentUrl.length() > 0) o.put("nextContentUrl", r.nextContentUrl);
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** Short description for the source list. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        if (group.length() > 0) {
            sb.append(group).append("  ");
        }
        sb.append(url);
        if (!enabled) {
            sb.append("  [已停用]");
        }
        return sb.toString();
    }
}

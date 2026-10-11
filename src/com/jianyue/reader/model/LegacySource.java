package com.jianyue.reader.model;

import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Import support for the OLD flat book-source format (阅读 2.x and early 3.x exports).
 *
 * WHY THIS IS WORTH A FILE
 * ------------------------
 * Most book sources in circulation are not the modern nested JSON. They are one flat object
 * per site:
 *
 *   { "bookSourceName":"笔趣阁", "ruleSearchUrl":"/search?q=searchKey",
 *     "ruleSearchList":"class.result-list@tag.li", "ruleSearchName":"class.name@text",
 *     "ruleChapterList":"id.chapterList@tag.a", "ruleBookContent":"id.content@html", ... }
 *
 * 阅读 still imports these (SourceAnalyzer.jsonToBookSource), and a dump of 4466 sources
 * contains 2978 of them. Without this converter 简阅 could not import two thirds of what a
 * user is likely to hand it, so every source would look "broken" for no good reason.
 *
 * Two jobs, both copied from 阅读's SourceAnalyzer:
 *   1. field mapping  - ruleSearchList -> ruleSearch.bookList, ruleBookContent ->
 *                       ruleContent.content, httpUserAgent -> header, ...
 *   2. dialect fixes  - the old CSS rules use single '#', '|' and '&' where 3.0 uses '##',
 *                       '||' and '&&', and URLs use "searchKey"/"searchPage" instead of
 *                       "{{key}}"/"{{page}}"; '#id.' style selectors and '@Header:' options
 *                       are rewritten too.
 *
 * The rewrite deliberately refuses to touch anything that is not a plain CSS rule
 * (@js:, <js>, ##, :regex, //xpath, @CSS:, @XPath:) - mangling a JS rule is worse than
 * leaving it alone.
 */
public final class LegacySource {

    private static final String TAG = "JianYue";

    private static final Pattern HEADER_OPT = Pattern.compile("@Header:\\{.+?\\}");
    private static final Pattern JS_OPT = Pattern.compile("\\{\\{.+?\\}\\}");

    private LegacySource() {
    }

    /**
     * True when this object is the old flat format.
     *
     * 阅读 uses exactly one signal: the presence of a ruleToc OBJECT. Every old export has
     * the flat ruleChapterList/ruleChapterUrl keys and no ruleToc, so this is reliable.
     */
    public static boolean isLegacy(JSONObject o) {
        if (o == null) {
            return false;
        }
        if (o.optJSONObject("ruleToc") != null) {
            return false;
        }
        // It must at least look like a flat source, otherwise a modern source that happens to
        // omit ruleToc would be run through the converter for nothing.
        return o.has("ruleSearchUrl") || o.has("ruleSearchList") || o.has("ruleChapterList")
                || o.has("ruleBookContent") || o.has("ruleSearchNoteUrl");
    }

    /**
     * Convert a flat object into the modern shape and parse it with the normal path, so
     * there is only ONE place that knows how a BookSource is built.
     */
    public static BookSource convert(JSONObject o) {
        try {
            JSONObject n = new JSONObject();
            n.put("bookSourceName", o.optString("bookSourceName", "").trim());
            n.put("bookSourceUrl", o.optString("bookSourceUrl", "").trim());
            n.put("bookSourceGroup", o.optString("bookSourceGroup", "").trim());
            n.put("bookSourceType", typeOf(o));
            n.put("enabled", o.has("enable") ? o.optBoolean("enable", true) : true);
            n.put("customOrder", o.optInt("serialNumber", o.optInt("customOrder", 0)));
            n.put("bookUrlPattern", o.optString("ruleBookUrlPattern", ""));
            n.put("bookSourceComment", o.optString("bookSourceComment", ""));
            String login = o.optString("loginUrl", "");
            if (login.length() > 0) {
                n.put("loginUrl", login);
            }
            String ua = o.optString("httpUserAgent", "");
            if (ua.length() > 0) {
                n.put("header", "User-Agent@" + ua);
            }
            String search = o.optString("ruleSearchUrl", "");
            if (search.length() > 0) {
                n.put("searchUrl", toNewUrl(search));
            }
            String find = o.optString("ruleFindUrl", "");
            if (find.length() > 0) {
                n.put("exploreUrl", toNewUrls(find));
            }

            // ---- search
            JSONObject rs = new JSONObject();
            put(rs, "bookList", toNewRule(o.optString("ruleSearchList", "")));
            put(rs, "name", toNewRule(o.optString("ruleSearchName", "")));
            put(rs, "author", toNewRule(o.optString("ruleSearchAuthor", "")));
            put(rs, "kind", toNewRule(o.optString("ruleSearchKind", "")));
            put(rs, "intro", toNewRule(o.optString("ruleSearchIntroduce", "")));
            put(rs, "coverUrl", toNewRule(o.optString("ruleSearchCoverUrl", "")));
            put(rs, "lastChapter", toNewRule(o.optString("ruleSearchLastChapter", "")));
            put(rs, "bookUrl", toNewRule(o.optString("ruleSearchNoteUrl", "")));
            n.put("ruleSearch", rs);

            // ---- explore (发现页未实现，但字段照搬，导入后信息不丢)
            JSONObject re = new JSONObject();
            put(re, "bookList", toNewRule(o.optString("ruleFindList", "")));
            put(re, "name", toNewRule(o.optString("ruleFindName", "")));
            put(re, "author", toNewRule(o.optString("ruleFindAuthor", "")));
            put(re, "kind", toNewRule(o.optString("ruleFindKind", "")));
            put(re, "intro", toNewRule(o.optString("ruleFindIntroduce", "")));
            put(re, "coverUrl", toNewRule(o.optString("ruleFindCoverUrl", "")));
            put(re, "lastChapter", toNewRule(o.optString("ruleFindLastChapter", "")));
            put(re, "bookUrl", toNewRule(o.optString("ruleFindNoteUrl", "")));
            n.put("ruleExplore", re);

            // ---- detail
            JSONObject ri = new JSONObject();
            put(ri, "init", toNewRule(o.optString("ruleBookInfoInit", "")));
            put(ri, "name", toNewRule(o.optString("ruleBookName", "")));
            put(ri, "author", toNewRule(o.optString("ruleBookAuthor", "")));
            put(ri, "kind", toNewRule(o.optString("ruleBookKind", "")));
            put(ri, "intro", toNewRule(o.optString("ruleIntroduce", "")));
            put(ri, "coverUrl", toNewRule(o.optString("ruleCoverUrl", "")));
            put(ri, "lastChapter", toNewRule(o.optString("ruleBookLastChapter", "")));
            // 旧格式的 ruleChapterUrl 是"目录页地址"，在现代结构里属于详情规则
            put(ri, "tocUrl", toNewRule(o.optString("ruleChapterUrl", "")));
            n.put("ruleBookInfo", ri);

            // ---- toc
            JSONObject rt = new JSONObject();
            put(rt, "chapterList", toNewRule(o.optString("ruleChapterList", "")));
            put(rt, "chapterName", toNewRule(o.optString("ruleChapterName", "")));
            put(rt, "chapterUrl", toNewRule(o.optString("ruleContentUrl", "")));
            put(rt, "nextTocUrl", toNewRule(o.optString("ruleChapterUrlNext", "")));
            n.put("ruleToc", rt);

            // ---- content
            JSONObject rc = new JSONObject();
            String content = toNewRule(o.optString("ruleBookContent", ""));
            // 旧格式里 "$..." 表示"用正则分组"，不是 JSONPath；'$.'/'$[' 才是 JSONPath
            if (content.startsWith("$") && !content.startsWith("$.") && !content.startsWith("$[")) {
                content = content.substring(1);
            }
            put(rc, "content", content);
            put(rc, "nextContentUrl", toNewRule(o.optString("ruleContentUrlNext", "")));
            n.put("ruleContent", rc);

            BookSource s = BookSource.fromJson(n);
            if (s != null) {
                Log.i(TAG, "legacy: converted " + s.name + " (" + s.url + ")");
            }
            return s;
        } catch (Throwable t) {
            Log.i(TAG, "legacy: convert failed " + t.getClass().getName() + ": " + t.getMessage());
            return null;
        }
    }

    private static int typeOf(JSONObject o) {
        Object raw = o.opt("bookSourceType");
        if (raw == null) {
            return 0;
        }
        if (raw instanceof Number) {
            return ((Number) raw).intValue();
        }
        String v = String.valueOf(raw).trim();
        if (v.length() == 0) {
            return 0;
        }
        return "AUDIO".equalsIgnoreCase(v) || v.indexOf("听") >= 0 ? 1 : 0;
    }

    private static void put(JSONObject o, String k, String v) {
        try {
            if (v != null && v.length() > 0) {
                o.put(k, v);
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ rule rewrite

    /**
     * Old flat sources write single '#', '|' and '&' where 3.0 wants '##', '||' and '&&'.
     *
     * Only plain CSS rules are rewritten - a rule that starts with @js:/<js>/##/://@CSS:/
     * @XPath: is left exactly as it was found, because those symbols mean something else
     * there.
     */
    public static String toNewRule(String old) {
        if (old == null || old.trim().length() == 0) {
            return "";
        }
        String r = old.trim();
        boolean reverse = r.startsWith("-");
        boolean allInOne = r.startsWith("+");
        String body = (reverse || allInOne) ? r.substring(1) : r;

        boolean plainCss = !body.startsWith("@CSS:") && !body.startsWith("@XPath:")
                && !body.startsWith("//") && !body.startsWith("##") && !body.startsWith(":")
                && body.indexOf("@js:") < 0 && body.indexOf("<js>") < 0;
        if (plainCss) {
            if (body.indexOf('#') >= 0 && body.indexOf("##") < 0) {
                body = body.replace("#", "##");
            }
            int cut = body.indexOf("##");
            String head = (cut >= 0) ? body.substring(0, cut) : body;
            String tail = (cut >= 0) ? body.substring(cut) : "";
            if (head.indexOf('|') >= 0 && head.indexOf("||") < 0) {
                head = head.replace("|", "||");
            }
            body = head + tail;
            if (body.indexOf('&') >= 0 && body.indexOf("&&") < 0
                    && body.indexOf("http") < 0 && !body.startsWith("/")) {
                body = body.replace("&", "&&");
            }
        }
        return (reverse ? "-" : (allInOne ? "+" : "")) + body;
    }

    // ------------------------------------------------------------- url rewrite

    /** A 发现 page may hold several URLs separated by && or newlines. */
    public static String toNewUrls(String old) {
        if (old == null || old.trim().length() == 0) {
            return "";
        }
        String v = old.trim();
        if (v.startsWith("@js:") || v.startsWith("<js>")) {
            return v;
        }
        if (v.indexOf('\n') < 0 && v.indexOf("&&") < 0) {
            return toNewUrl(v);
        }
        String[] parts = v.split("(&&|\\r?\\n)+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.length() == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(toNewUrl(p));
        }
        return sb.toString();
    }

    /**
     * Rewrite one old-format URL into the modern form: "searchKey" -> "{{key}}",
     * "searchPage+N" -> "{{page+N}}", "@Header:{...}" and "|charset=xxx" into the
     * ", {json}" options tail, and a trailing "@body" into a POST.
     */
    public static String toNewUrl(String old) {
        if (old == null || old.trim().length() == 0) {
            return "";
        }
        String url = old.trim();
        if (url.startsWith("<js>")) {
            return url.replace("=searchKey", "={{key}}").replace("=searchPage", "={{page}}");
        }
        StringBuilder opts = new StringBuilder();
        try {
            Matcher h = HEADER_OPT.matcher(url);
            if (h.find()) {
                addOpt(opts, "headers", quoteHeaderBlock(h.group().substring(8)));
                url = url.replace(h.group(), "");
            }
        } catch (Throwable ignored) {
        }
        // "|charset=gbk" (the '|' form predates the options tail)
        int bar = url.indexOf('|');
        if (bar > 0) {
            String rest = url.substring(bar + 1).trim();
            if (rest.startsWith("charset=")) {
                addOpt(opts, "charset", quote(rest.substring(8).trim()));
                url = url.substring(0, bar);
            }
        }
        // Protect {{...}} JS expressions from the { } -> < > conversion below.
        List<String> js = new ArrayList<String>();
        try {
            Matcher m = JS_OPT.matcher(url);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                js.add(m.group());
                m.appendReplacement(sb, Matcher.quoteReplacement("$" + (js.size() - 1)));
            }
            m.appendTail(sb);
            url = sb.toString();
        } catch (Throwable ignored) {
        }
        url = url.replace("{", "<").replace("}", ">");
        url = url.replace("searchKey", "{{key}}");
        url = url.replace("<searchPage+1>", "{{page+1}}")
                 .replace("<searchPage-1>", "{{page-1}}")
                 .replace("<searchPage>", "{{page}}")
                 .replace("searchPage+1", "{{page+1}}")
                 .replace("searchPage-1", "{{page-1}}")
                 .replace("searchPage", "{{page}}");
        for (int i = 0; i < js.size(); i++) {
            String expr = js.get(i);
            // inside {{ }} these are JS expressions, so the old names become key/page
            String inner = expr.substring(2, expr.length() - 2)
                    .replace("searchKey", "key").replace("searchPage", "page");
            url = url.replace("$" + i, "{{" + inner + "}}");
        }
        // "@" separates the POST body in the old format
        int at = url.indexOf('@');
        if (at > 0 && at < url.length() - 1) {
            String body = url.substring(at + 1);
            url = url.substring(0, at);
            addOpt(opts, "method", "\"POST\"");
            addOpt(opts, "body", quote(body));
        }
        if (opts.length() == 0) {
            return url;
        }
        return url + "," + "{" + opts + "}";
    }

    private static void addOpt(StringBuilder sb, String key, String value) {
        if (sb.length() > 0) {
            sb.append(',');
        }
        sb.append(quote(key)).append(':').append(value);
    }

    /**
     * Make an old "@Header:{Referer:http://a/}" block into valid JSON for the options tail.
     *
     * The old format wrote the keys without quotes, and the text is embedded in a JSON object
     * we build by hand, so an unquoted key would produce a document that cannot be parsed back
     * (and the whole request would silently lose its headers).
     */
    private static String quoteHeaderBlock(String text) {
        if (text == null) {
            return "\"\"";
        }
        String t = text.trim();
        try {
            return new JSONObject(t).toString();
        } catch (Throwable ignored) {
        }
        try {
            // bare keys -> "keys"
            String fixed = t.replaceAll("([{,]\\s*)([A-Za-z_][A-Za-z0-9_\\-]*)(\\s*:)",
                    "$1\"$2\"$3");
            return new JSONObject(fixed).toString();
        } catch (Throwable ignored) {
        }
        return quote(t);
    }

    private static String quote(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.append('"').toString();
    }
}

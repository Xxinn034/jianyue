package com.jianyue.reader.engine;

import android.util.Log;

import com.jianyue.reader.model.BookSource;
import com.jianyue.reader.model.Books;
import com.jianyue.reader.net.Http;
import com.jianyue.reader.rule.Html;
import com.jianyue.reader.rule.Rules;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * The book-source business flow: search -> detail -> table of contents -> content.
 *
 * Rule semantics implemented here (matching 阅读/Legado so existing sources work):
 *   searchUrl        {{key}} and {{page}} are substituted, key URL-encoded
 *   ruleSearch.bookList   yields either JSON nodes or HTML elements; each element is
 *                         then queried with ruleSearch.name / author / bookUrl ...
 *   bookUrl          may embed {$.field} to pull a value out of the current JSON item
 *   ruleToc.chapterList   may be a selector ("class.x@tag.a"), an XPath or JSONPath
 *   ruleContent.content   HTML selector or JSONPath; nextContentUrl handles pagination
 *
 * Everything is synchronous and must be called off the UI thread.
 */
public final class Engine {

    private static final String TAG = "JianYue";

    private Engine() {
    }

    // ---------------------------------------------------------------- search

    /**
     * Search every enabled source and return the merged result.
     *
     * The work itself lives in {@link SearchJob}: bounded-but-wide concurrency, per-source
     * streaming, health ordering, dead-host skipping and a whole-search budget. This method
     * stays as the plain "give me the answer" entry point used by the verifiers and by
     * {@link #fillFromSearch}.
     */
    public static List<Books.Book> searchAll(List<BookSource> sources, String keyword,
                                             int page, List<String> failuresOut) {
        return SearchJob.run(sources, keyword, page, failuresOut);
    }

    /**
     * The search URL for one source, with {{key}} / {{page}} / {{keyword}} substituted and
     * relative to bookSourceUrl. Returns "" when the source has no usable search URL.
     *
     * Split out of {@link #search} so the concurrent runner can fetch (many at once) and
     * parse (a few at a time) as separate steps.
     */
    public static String searchUrlFor(BookSource src, String keyword, int page) {
        if (src == null || src.searchUrl == null || src.searchUrl.length() == 0) {
            return "";
        }
        try {
            // The options tail (", {json}") must survive template expansion untouched: it is
            // consumed by Http.fetch(), and letting {{ }} expansion see it would corrupt the
            // JSON when a body happens to contain braces.
            com.jianyue.reader.net.UrlOptions uo =
                    com.jianyue.reader.net.UrlOptions.parse(src.searchUrl);
            String expanded = expandTemplate(uo.url, src, keyword, page);
            String abs = Rules.absUrl(src.url, expanded);
            return abs + uo.tail;
        } catch (Throwable t) {
            Log.i(TAG, "engine.searchUrl failed " + t.getClass().getName() + ": " + t.getMessage());
            return "";
        }
    }

    /**
     * Expand the {{...}} placeholders 阅读 allows inside a URL.
     *
     * {{key}} is the URL-encoded keyword and {{page}} the page number - the two that predate
     * the JS engine and are still what almost every source uses. Anything else is a JS
     * expression ({{page+1}}, {{book.name}}, {{java.ajax(...)}}) evaluated with the same
     * bindings a rule script sees.
     */
    public static String expandTemplate(String tpl, BookSource src, String keyword, int page) {
        if (tpl == null || tpl.indexOf("{{") < 0) {
            return tpl == null ? "" : tpl;
        }
        String encKey;
        try {
            encKey = URLEncoder.encode(keyword == null ? "" : keyword, "UTF-8");
        } catch (Throwable t) {
            encKey = keyword == null ? "" : keyword;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = tpl.length();
        while (i < n) {
            int open = tpl.indexOf("{{", i);
            if (open < 0) {
                out.append(tpl.substring(i));
                break;
            }
            out.append(tpl.substring(i, open));
            int close = tpl.indexOf("}}", open + 2);
            if (close < 0) {
                out.append(tpl.substring(open));
                break;
            }
            String expr = tpl.substring(open + 2, close).trim();
            if (expr.equals("key") || expr.equals("keyword")) {
                out.append(encKey);
            } else if (expr.equals("page")) {
                out.append(page);
            } else {
                String v = com.jianyue.reader.rule.Js.runUrlExpr(expr, keyword, page,
                        src == null ? "" : src.url);
                out.append(v == null ? "" : v);
            }
            i = close + 2;
        }
        return out.toString();
    }

    /**
     * Run a source's ruleSearch against an already-fetched body.
     *
     * JSON vs HTML is decided by the bookList rule itself (a "$" rule means JSONPath),
     * which is the same rule style dispatch the rest of the engine uses.
     */
    public static List<Books.Book> parseSearchBody(BookSource src, String body) {
        List<Books.Book> books = new ArrayList<Books.Book>();
        if (src == null || body == null) {
            return books;
        }
        String listRule = src.searchRules == null ? null : src.searchRules.bookList;
        if (listRule == null || listRule.length() == 0) {
            return books;
        }
        try {
            // The body decides JSON vs HTML, not just the rule: a 阅读 source may write a
            // JSON list rule as a bare "data.list" (百度小说 does), which is otherwise
            // indistinguishable from a CSS selector. See Rules.looksLikeJson.
            if (listRule.startsWith("$") || com.jianyue.reader.rule.Rules.looksLikeJson(body)) {
                JSONObject root = new JSONObject(body);
                List<Object> items = com.jianyue.reader.rule.JsonPath.eval(root, listRule);
                for (int i = 0; i < items.size(); i++) {
                    Object o = items.get(i);
                    if (o instanceof JSONArray) {
                        JSONArray a = (JSONArray) o;
                        for (int k = 0; k < a.length(); k++) {
                            Object e = a.opt(k);
                            if (e instanceof JSONObject) {
                                books.add(fromJson(src, (JSONObject) e));
                            }
                        }
                    } else if (o instanceof JSONObject) {
                        books.add(fromJson(src, (JSONObject) o));
                    }
                }
            } else {
                Html.El doc = Html.parse(body);
                List<Object> nodes = Rules.getNodes(doc, listRule, Rules.CTX_HTML);
                for (int i = 0; i < nodes.size(); i++) {
                    Object node = nodes.get(i);
                    if (node instanceof Html.El) {
                        books.add(fromHtml(src, (Html.El) node));
                    }
                }
            }
        } catch (org.json.JSONException e) {
            throw new RuntimeException("响应不是合法 JSON（bookList 规则以 $ 开头）: "
                    + e.getMessage());
        }
        return books;
    }

    public static Books.SearchResult search(BookSource src, String keyword, int page) {
        // @put/@get variables live for one analysis pass only; leaking them across
        // books or chapters would make a rule read another book's value.
        Rules.resetPutVars();
        Books.SearchResult res = new Books.SearchResult();
        res.sourceName = src == null ? "" : src.name;
        long t0 = System.currentTimeMillis();
        try {
            if (src == null) {
                res.error = "书源为空";
                return res;
            }
            if (src.searchUrl == null || src.searchUrl.length() == 0) {
                res.error = "该书源未配置搜索地址";
                return res;
            }
            String url = searchUrlFor(src, keyword, page);
            Http.Resp r = Http.fetch(url, src.url, src.headers());
            if (!r.ok() || r.code >= 400) {
                res.error = (r.error != null) ? r.error : ("HTTP " + r.code);
                res.unreachable = r.unreachable();
                return res;
            }
            String body = r.text();
            Log.i(TAG, "engine.search " + src.name + " url=" + url
                    + " bytes=" + (r.body == null ? 0 : r.body.length));

            String listRule = src.searchRules == null ? null : src.searchRules.bookList;
            if (listRule == null || listRule.length() == 0) {
                res.error = "该书源未配置 bookList 规则";
                return res;
            }
            res.books = parseSearchBody(src, body);
            Log.i(TAG, "engine.search " + src.name + " -> " + res.books.size() + " books");
            if (res.books.size() == 0) {
                res.error = "没有解析到结果（检查 bookList 规则）";
            }
            return res;
        } catch (Throwable t) {
            res.error = t.getClass().getName() + ": " + t.getMessage();
            Log.i(TAG, "engine.search failed " + res.error);
            return res;
        } finally {
            res.elapsedMs = System.currentTimeMillis() - t0;
        }
    }

    private static Books.Book fromJson(BookSource src, JSONObject item) {
        Books.Book b = new Books.Book();
        b.sourceName = src.name;
        b.sourceUrl = src.url;
        b.name = clean(Rules.getString(item, src.searchRules.name, Rules.CTX_JSON, src.url));
        b.author = Rules.getString(item, src.searchRules.author, Rules.CTX_JSON, src.url);
        b.kind = Rules.getString(item, src.searchRules.kind, Rules.CTX_JSON, src.url);
        b.intro = Rules.getString(item, src.searchRules.intro, Rules.CTX_JSON, src.url);
        b.wordCount = Rules.getString(item, src.searchRules.wordCount, Rules.CTX_JSON, src.url);
        b.coverUrl = Rules.getString(item, src.searchRules.coverUrl, Rules.CTX_JSON, src.url);
        b.bookUrl = resolveBookUrl(src, item, null);
        return b;
    }

    private static Books.Book fromHtml(BookSource src, Html.El node) {
        Books.Book b = new Books.Book();
        b.sourceName = src.name;
        b.sourceUrl = src.url;
        b.name = clean(Rules.getString(node, src.searchRules.name, Rules.CTX_HTML, src.url));
        b.author = Rules.getString(node, src.searchRules.author, Rules.CTX_HTML, src.url);
        b.kind = Rules.getString(node, src.searchRules.kind, Rules.CTX_HTML, src.url);
        b.intro = Rules.getString(node, src.searchRules.intro, Rules.CTX_HTML, src.url);
        b.wordCount = Rules.getString(node, src.searchRules.wordCount, Rules.CTX_HTML, src.url);
        b.coverUrl = Rules.getString(node, src.searchRules.coverUrl, Rules.CTX_HTML, src.url);
        b.bookUrl = resolveBookUrl(src, null, node);
        return b;
    }

    /**
     * Build the detail-page URL. The rule may be a plain path, may embed {$.field} for
     * JSON items, or may be a CSS/JSONPath rule that yields the URL.
     */
    private static String resolveBookUrl(BookSource src, JSONObject jsonItem, Html.El node) {
        String rule = src.searchRules.bookUrl;
        if (rule == null || rule.length() == 0) {
            return "";
        }
        String out;
        if (jsonItem != null && rule.indexOf("{") >= 0) {
            out = substituteJson(rule, jsonItem);
        } else if (jsonItem != null) {
            out = Rules.getString(jsonItem, rule, Rules.CTX_JSON, src.url);
        } else {
            out = Rules.getString(node, rule, Rules.CTX_HTML, src.url);
        }
        return Rules.absUrl(src.url, out);
    }

    /**
     * Replace {$.a.b} / {$.a} placeholders with values from a JSON item.
     *
     * Delegates to the rule engine so there is one implementation of the substitution
     * (see {@link com.jianyue.reader.rule.Rules#substituteJsonFields}).
     */
    public static String substituteJson(String template, JSONObject item) {
        return com.jianyue.reader.rule.Rules.substituteJsonFields(template, item);
    }

    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        // book sites often wrap titles in highlight markup even inside JSON
        return Rules.cleanText(s);
    }

    /**
     * Evaluate one rule against a fetched body, choosing JSON or HTML by the rule's syntax.
     *
     * A "$"-prefixed rule used to be handed straight to JsonPath.eval()/firstString(), which
     * silently dropped everything else the rule engine supports. 榛戝博's content rule is
     *
     *   $.chapter.htmlContent<js>result.replace(/<.*?>/g,'')</js>
     *
     * so the <js> tail was never seen by Rules, the rule resolved to nothing, and every
     * chapter came back as the placeholder "锛堟鏂囦负绌猴級" - exactly 6 characters, which is
     * what the device log kept reporting while the rules themselves looked correct.
     *
     * Routing through Rules.getString() keeps plain JSONPath behaviour (getString dispatches
     * "$..." to the same JsonPath code) and adds <js>, ##replace and the rule combinators.
     */
    /**
     * Resolve one rule against a whole response body.
     *
     * JSON vs HTML is decided by the BODY (see {@link com.jianyue.reader.rule.Rules#looksLikeJson}),
     * falling back to the rule's own syntax, so a JSON content rule written without the
     * leading "$" still works.
     */
    private static String evalRule(String body, String rule, String baseUrl) {
        if (rule == null || rule.trim().length() == 0) {
            return "";
        }
        try {
            if (rule.trim().startsWith("$")
                    || com.jianyue.reader.rule.Rules.looksLikeJson(body)) {
                return Rules.getString(new JSONObject(body), rule, Rules.CTX_JSON, baseUrl);
            }
            return Rules.getString(Html.parse(body), rule, Rules.CTX_HTML, baseUrl);
        } catch (Throwable t) {
            Log.i(TAG, "engine.rule failed [" + rule + "] " + t.getClass().getName()
                    + ": " + t.getMessage());
            return "";
        }
    }

    /**
     * Key used to group "the same book" across sources.
     *
     * Titles differ by source in punctuation and decoration - 《斗罗大陆》、斗罗大陆（全本）、
     * 斗罗大陆 免费阅读 - so a byte comparison would never merge anything. Everything that is
     * not a CJK character or a letter/digit is dropped, and the result is lower-cased.
     */
    public static String normalizeTitle(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** Grouping key for the same book found on several sources (see normalizeTitle). */
    public static String mergeKey(Books.Book b) {
        if (b == null) {
            return "";
        }
        return normalizeTitle(b.name);
    }


    // ------------------------------------------------------------ book info

    /** Load the detail page and run ruleBookInfo. Returns the same book with fields filled. */
    public static Books.Book loadBookInfo(BookSource src, Books.Book b) {
        // @put/@get variables live for one analysis pass only; leaking them across
        // books or chapters would make a rule read another book's value.
        Rules.resetPutVars();
        if (src == null || b == null) {
            return b;
        }
        try {
            String url = (b.tocUrl != null && b.tocUrl.length() > 0)
                    ? b.tocUrl
                    : ((b.bookUrl != null && b.bookUrl.length() > 0) ? b.bookUrl : b.tocUrl);
            if (url == null || url.length() == 0) {
                return b;
            }
            Http.Resp r = Http.fetch(url, src.url, src.headers());
            if (!r.ok() || r.code >= 400) {
                Log.i(TAG, "engine.info failed " + url + " " + (r.error != null ? r.error
                        : ("HTTP " + r.code)));
                fillFromSearch(src, b, "detail page unreachable");
                return b;
            }
            String body = r.text();
            BookSource.Rules rr = src.bookInfoRules;
            if (rr == null || rr.isEmpty()) {
                return b;
            }
            if (rr.name != null && rr.name.startsWith("$")) {
                JSONObject o = new JSONObject(body);
                fillJson(b, rr, o, src);
            } else {
                Html.El doc = Html.parse(body);
                fillHtml(b, rr, doc, src);
            }
            // If the detail page gave nothing usable (deleted book id, anti-bot shell page),
            // the search endpoint still knows the metadata. See fillFromSearch().
            if (b.intro.length() == 0 && b.kind.length() == 0 && b.coverUrl.length() == 0) {
                fillFromSearch(src, b, "detail page empty");
            }
            Log.i(TAG, "engine.info " + b.name + " / " + b.author);
        } catch (Throwable t) {
            Log.i(TAG, "engine.info error " + t.getClass().getName() + ": " + t.getMessage());
            try {
                fillFromSearch(src, b, "detail page error");
            } catch (Throwable ignored) {
            }
        }
        return b;
    }

    /**
     * Fill book metadata by re-running the source's SEARCH for the book's name.
     *
     * Why this exists: some sites put the detail page on a host that API 1 cannot reach
     * (黑岩's w.heiyan.com only negotiates TLS 1.2) while their search API is plain HTTP and
     * returns the full record. Without this the detail screen shows an empty 简介 and the
     * user sees a book that "loaded but knows nothing about itself".
     *
     * Conservative on purpose: only fields that are still empty are filled, and an exact
     * name match is preferred over simply taking the first hit.
     */
    private static void fillFromSearch(BookSource src, Books.Book b, String why) {
        if (src == null || b == null || b.name == null || b.name.length() == 0) {
            return;
        }
        if (src.searchUrl == null || src.searchUrl.length() == 0) {
            return;
        }
        BookSource.Rules sr = src.searchRules;
        if (sr == null || sr.bookList == null || sr.bookList.length() == 0) {
            return;
        }
        Log.i(TAG, "engine.info fallback via search (" + why + ") for " + b.name);
        Books.SearchResult res = search(src, b.name, 1);
        if (!res.ok() || res.books.size() == 0) {
            return;
        }
        Books.Book pick = null;
        String want = normalizeTitle(b.name);
        for (int i = 0; i < res.books.size(); i++) {
            Books.Book cand = res.books.get(i);
            if (cand != null && cand.name != null && normalizeTitle(cand.name).equals(want)) {
                pick = cand;
                break;
            }
        }
        if (pick == null) {
            pick = res.books.get(0);
        }
        if (b.author.length() == 0) b.author = pick.author;
        if (b.intro.length() == 0) b.intro = pick.intro;
        if (b.kind.length() == 0) b.kind = pick.kind;
        if (b.wordCount.length() == 0) b.wordCount = pick.wordCount;
        if (b.coverUrl.length() == 0) b.coverUrl = pick.coverUrl;
        if (b.lastChapter.length() == 0) b.lastChapter = pick.lastChapter;
        Log.i(TAG, "engine.info fallback -> author=" + b.author + " kind=" + b.kind
                + " introLen=" + b.intro.length());
    }

    private static void fillJson(Books.Book b, BookSource.Rules rr, JSONObject o, BookSource src) {
        if (b.name.length() == 0) b.name = clean(Rules.getString(o, rr.name, Rules.CTX_JSON, src.url));
        if (b.author.length() == 0) b.author = Rules.getString(o, rr.author, Rules.CTX_JSON, src.url);
        if (b.intro.length() == 0) b.intro = Rules.getString(o, rr.intro, Rules.CTX_JSON, src.url);
        if (b.kind.length() == 0) b.kind = Rules.getString(o, rr.kind, Rules.CTX_JSON, src.url);
        if (b.wordCount.length() == 0) b.wordCount = Rules.getString(o, rr.wordCount, Rules.CTX_JSON, src.url);
        if (b.coverUrl.length() == 0) b.coverUrl = Rules.getString(o, rr.coverUrl, Rules.CTX_JSON, src.url);
    }

    private static void fillHtml(Books.Book b, BookSource.Rules rr, Html.El doc, BookSource src) {
        if (b.name.length() == 0) b.name = clean(Rules.getString(doc, rr.name, Rules.CTX_HTML, src.url));
        if (b.author.length() == 0) b.author = Rules.getString(doc, rr.author, Rules.CTX_HTML, src.url);
        if (b.intro.length() == 0) b.intro = Rules.getString(doc, rr.intro, Rules.CTX_HTML, src.url);
        if (b.kind.length() == 0) b.kind = Rules.getString(doc, rr.kind, Rules.CTX_HTML, src.url);
        if (b.wordCount.length() == 0) b.wordCount = Rules.getString(doc, rr.wordCount, Rules.CTX_HTML, src.url);
        if (b.coverUrl.length() == 0) b.coverUrl = Rules.getString(doc, rr.coverUrl, Rules.CTX_HTML, src.url);
    }

    // ------------------------------------------------------------------- toc

    /** Load the table of contents. */
    public static List<Books.Chapter> loadToc(BookSource src, Books.Book b) {
        // @put/@get variables live for one analysis pass only; leaking them across
        // books or chapters would make a rule read another book's value.
        Rules.resetPutVars();
        List<Books.Chapter> out = new ArrayList<Books.Chapter>();
        if (src == null || b == null) {
            return out;
        }
        try {
            String url = pickTocUrl(src, b);
            if (url == null || url.length() == 0) {
                Log.i(TAG, "engine.toc no url");
                return out;
            }
            // A 目录 can span several pages. ruleToc.nextTocUrl names the next one (usually a
            // "下一页" link), and each page is appended in order; without this a source like
            // 新笔趣阁 returns only its first 100 chapters, which looks like a truncated book.
            //
            // A page can yield SEVERAL next URLs at once (a JS rule that computes every
            // remaining page from the total chapter count, as 百度小说 does: 433 chapters at
            // 50 per page), so the loop works off a queue rather than one chained URL.
            List<String> pending = new ArrayList<String>();
            List<String> seen = new ArrayList<String>();
            String cur = url;
            int guard = 0;
            while ((cur != null && cur.length() > 0 || pending.size() > 0) && guard < 20) {
                if (cur == null || cur.length() == 0) {
                    cur = pending.remove(0);
                }
                guard++;
                if (seen.contains(cur)) {
                    cur = "";
                    continue;
                }
                seen.add(cur);
                String nextRaw = loadOneTocPage(src, b, cur, out);
                if (out.size() == 0) {
                    break;
                }
                String from = cur;
                cur = "";
                if (nextRaw != null && nextRaw.length() > 0) {
                    // Several URLs may come back at once (see loadOneTocPage).
                    String[] parts = nextRaw.split("\n");
                    for (int i = 0; i < parts.length; i++) {
                        String n = parts[i].trim();
                        if (n.length() == 0) {
                            continue;
                        }
                        String abs = (n.indexOf("://") > 0) ? n : Rules.absUrl(from, n);
                        if (pending.contains(abs) || seen.contains(abs)) {
                            continue;
                        }
                        if (cur.length() == 0) {
                            cur = abs;
                        } else {
                            pending.add(abs);
                        }
                    }
                }
            }
            Log.i(TAG, "engine.toc " + b.name + " -> " + out.size() + " chapters ("
                    + seen.size() + " page(s))");
            return out;
        } catch (Throwable t) {
            Log.i(TAG, "engine.toc error " + t.getClass().getName() + ": " + t.getMessage());
            return out;
        }
    }

    /**
     * Load ONE 目录 page, appending its chapters to {@code out}.
     *
     * @return the next page URL(s), newline separated ("" when this was the last page).
     *         More than one URL happens with a JS rule that derives every remaining page from
     *         the total chapter count instead of linking to one "next page".
     */
    private static String loadOneTocPage(BookSource src, Books.Book b, String url,
                                         List<Books.Chapter> out) {
        try {
            Http.Resp r = Http.fetch(url, b.bookUrl, src.headers());
            if (!r.ok() || r.code >= 400) {
                Log.i(TAG, "engine.toc failed " + (r.error != null ? r.error : ("HTTP " + r.code))
                        + " url=" + url);
                return "";
            }
            String body = r.text();
            BookSource.Rules rr = src.tocRules;
            String listRule = (rr == null) ? "" : rr.chapterList;
            if (listRule == null || listRule.length() == 0) {
                Log.i(TAG, "engine.toc no chapterList rule");
                return "";
            }
            // 阅读 states the list direction with a leading "-" (newest first) or "+".
            // Sites that paginate a 目录 newest-first (most 笔趣阁 clones) need the reversal,
            // otherwise chapter 1 looks like the last chapter and "next chapter" walks
            // backwards through the book.
            boolean reverse = false;
            if (listRule.startsWith("-")) {
                reverse = true;
                listRule = listRule.substring(1);
            } else if (listRule.startsWith("+")) {
                listRule = listRule.substring(1);
            }
            String nextToc = "";
            List<String> names;
            List<String> urls;
            List<Boolean> vips = new ArrayList<Boolean>();

            if (listRule.startsWith("$") || Rules.looksLikeJson(body)) {
                JSONObject root = new JSONObject(body);
                List<Object> items = Rules.getNodes(root, listRule, Rules.CTX_JSON);
                names = pickField(items, rr.chapterName, src.url);
                urls = pickField(items, rr.chapterUrl, src.url);
                logTocNodes(listRule, items.size(), body, true);
            } else {
                Html.El doc = Html.parse(body);
                List<Object> items = Rules.getNodes(doc, listRule, Rules.CTX_HTML);
                names = new ArrayList<String>();
                urls = new ArrayList<String>();
                for (int i = 0; i < items.size(); i++) {
                    Object node = items.get(i);
                    String nm = Rules.getString(node, rr.chapterName, Rules.CTX_HTML, src.url);
                    String u = Rules.getString(node, rr.chapterUrl, Rules.CTX_HTML, src.url);
                    names.add(nm == null ? "" : nm.trim());
                    urls.add(Rules.absUrl(url, u));
                    if (rr.isVip != null && rr.isVip.length() > 0) {
                        String vip = Rules.getString(node, rr.isVip, Rules.CTX_HTML, src.url);
                        vips.add(Boolean.valueOf(vip != null && vip.length() > 0));
                    }
                }
                logTocNodes(listRule, items.size(), body, false);
            }

            int n = Math.min(names.size(), urls.size());
            for (int i = 0; i < n; i++) {
                // "reverse" means the site served the list newest-first; walk it backwards
                // so out.get(0) is always the first chapter of the book.
                int src_i = reverse ? (n - 1 - i) : i;
                String nm = names.get(src_i);
                String u = urls.get(src_i);
                if (nm == null || nm.length() == 0 || u == null || u.length() == 0) {
                    continue;
                }
                Books.Chapter c = new Books.Chapter();
                c.name = nm;
                c.url = u;
                c.index = out.size();
                c.vip = (src_i < vips.size()) && vips.get(src_i).booleanValue();
                out.add(c);
            }

            // resolve the "next 目录 page" rule against THIS page
            if (rr.nextTocUrl != null && rr.nextTocUrl.length() > 0) {
                try {
                    String rule = rr.nextTocUrl.trim();
                    String nx;
                    if (rule.startsWith("@js:") || rule.startsWith("<js>")) {
                        // A JS next-page rule computes the remaining URLs from the WHOLE
                        // response - 百度小说 derives all 8 remaining pages from
                        // JSON.parse(result).data.chapter.chapterCount - so `result` has to be
                        // the raw body. Running it through the rule evaluator handed the script
                        // an HTML-parsed fragment instead, it threw, and the 目录 stopped at the
                        // first 50 of 433 chapters.
                        //
                        // The "js:"/"@js:"/"<js>" marker itself is NOT JavaScript: passing the
                        // rule through with its prefix made Rhino fail on the very first token
                        // and return "", which looked exactly like "this source has one page".
                        String script = rule;
                        if (script.startsWith("@js:")) {
                            script = script.substring(4);
                        } else if (script.startsWith("js:")) {
                            script = script.substring(3);
                        } else if (script.startsWith("<js>")) {
                            script = script.substring(4);
                            int close = script.lastIndexOf("</js>");
                            if (close >= 0) {
                                script = script.substring(0, close);
                            }
                        }
                        nx = com.jianyue.reader.rule.Js.run(script, body, url, null);
                    } else {
                        nx = evalRule(body, rule, url);
                    }
                    // "1/2" and a link back to the current page both mean "no next page"
                    if (nx != null && nx.length() > 0 && !nx.equals("下一页")
                            && !nx.equals("下页") && !nx.equals(url)
                            && !nx.equals(Rules.absUrl(url, nx))) {
                        // A JS rule that returns an ARRAY comes back as "[\"u1\", \"u2\"]" or as
                        // Rhino's plain "u1,u2"; reduce either to newline-separated URLs so the
                        // caller can queue them.
                        nextToc = normalizeNextToc(nx, url);
                    }
                    if (nextToc.length() == 0) {
                        Log.i(TAG, "engine.toc nextTocUrl -> none (raw="
                                + (nx == null ? -1 : nx.length()) + ") rule=["
                                + preview(rule) + "]");
                    }
                } catch (Throwable t) {
                    Log.i(TAG, "engine.toc nextTocUrl failed: " + t.getMessage());
                }
            }
            Log.i(TAG, "engine.toc page " + url + " -> " + names.size() + " rows"
                    + (reverse ? " (reversed)" : "")
                    + (nextToc.length() > 0 ? " next=" + nextToc : ""));
            return nextToc;
        } catch (Throwable t) {
            Log.i(TAG, "engine.toc page error " + t.getClass().getName() + ": " + t.getMessage());
            return "";
        }
    }

    /**
     * Turn a next-page rule result into newline-separated URLs.
     *
     * A JS rule may return a single URL, an array (["u1","u2"]), or an array joined by Rhino's
     * default toString ("u1,u2"). All three mean "these are the pages after this one", so they
     * are reduced to one form here rather than in the caller.
     */
    private static String normalizeNextToc(String nx, String currentUrl) {
        if (nx == null) {
            return "";
        }
        String t = nx.trim();
        if (t.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (t.charAt(0) == '[') {
            try {
                org.json.JSONArray a = new org.json.JSONArray(t);
                for (int i = 0; i < a.length(); i++) {
                    String v = a.optString(i, "").trim();
                    if (v.length() > 0) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(v);
                    }
                }
                return sb.toString();
            } catch (Throwable ignored) {
                // not strict JSON: fall through to the comma form
            }
            t = t.substring(1);
            if (t.length() > 0 && t.charAt(t.length() - 1) == ']') {
                t = t.substring(0, t.length() - 1);
            }
        }
        if (t.indexOf(',') < 0) {
            return t;
        }
        // Only treat a comma as a URL separator when the pieces look like URLs or paths;
        // a query string of its own ("?page=2,3") must survive intact.
        String[] parts = t.split(",");
        if (parts.length < 2) {
            return t;
        }
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim().replace("\"", "").replace("'", "");
            if (p.length() == 0) {
                continue;
            }
            if (p.indexOf("://") < 0 && p.indexOf('/') < 0 && p.indexOf('.') < 0) {
                return t;               // not URL-shaped: leave it alone
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(p);
        }
        return sb.toString();
    }

    /**
     * Work out which URL holds the table of contents.
     *
     * ruleToc.tocUrl may be:
     *   - empty            -> the TOC lives on the detail page itself
     *   - a template       -> "/showchapter/{$.bookId}.html" (bookId parsed from bookUrl)
     *   - a CSS/JSONPath   -> evaluated against the detail page, whose result is the URL
     *
     * 纵横 needs the template form: the detail page is www.zongheng.com/detail/{id} while
     * the chapter list lives on book.zongheng.com/showchapter/{id}.html.
     */
    private static String pickTocUrl(BookSource src, Books.Book b) {
        String rule = (src.tocRules == null) ? "" : src.tocRules.tocUrl;
        String bookUrl = (b.bookUrl != null && b.bookUrl.length() > 0) ? b.bookUrl : b.tocUrl;
        if (rule == null || rule.trim().length() == 0) {
            return bookUrl;
        }
        String r = rule.trim();
        // A "{...}" template is resolved against the book we already have, so a source can
        // build the 目录 URL without ever loading the detail page:
        //   http://a.heiyan.com/m/ajax/book/{$.id}/chapter?volumeIndex=0
        //   http://a.heiyan.com/m/ajax/book/{@js:result.match(/\d+$/)[0]}/chapter
        // This matters on API 1 because 黑岩's detail page lives on a host that only speaks
        // TLS 1.2, so the bookId can only come from the search hit.
        if (r.indexOf('{') >= 0) {
            String expanded = expandTemplate(r, src, b, bookUrl);
            if (expanded.length() > 0 && !expanded.equals(r)) {
                return Rules.absUrl(bookUrl, expanded);
            }
        }
        // template form
        if (r.indexOf("{$.bookId}") >= 0) {
            String id = lastNumericSegment(bookUrl);
            if (id.length() > 0) {
                return Rules.absUrl(bookUrl, r.replace("{$.bookId}", id));
            }
        }
        // a full URL or path
        if (r.startsWith("http://") || r.startsWith("https://") || r.startsWith("/")) {
            return Rules.absUrl(bookUrl, r);
        }
        // otherwise evaluate against the detail page
        if (bookUrl != null && bookUrl.length() > 0) {
            try {
                Http.Resp d = Http.fetch(bookUrl, src.url, src.headers());
                if (d.ok() && d.code < 400) {
                    String page = d.text();
                    String found = evalRule(page, r, bookUrl);
                    if (found != null && found.length() > 0) {
                        return Rules.absUrl(bookUrl, found);
                    }
                }
            } catch (Throwable t) {
                Log.i(TAG, "engine.toc rule eval failed: " + t.getMessage());
            }
        }
        return bookUrl;
    }

    /**
     * Expand a "{...}" template in a URL rule.
     *
     * Supported placeholders, matching the two forms real 阅读 sources use:
     *   {$.field}        a field of the current book (bookUrl / name / author / kind ...)
     *   {$.b.c}          dotted paths are resolved against the book as JSON
     *   {@js:expr}       JavaScript with `result` set to bookUrl
     *
     * Returns "" when nothing was substituted, so the caller can fall back to its old
     * behaviour instead of turning a rule into a broken URL.
     */
    private static String expandTemplate(String tpl, BookSource src, Books.Book b, String baseUrl) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int n = tpl.length();
        boolean changed = false;
        while (i < n) {
            int open = tpl.indexOf('{', i);
            if (open < 0) {
                sb.append(tpl.substring(i));
                break;
            }
            int close = tpl.indexOf('}', open);
            if (close < 0) {
                sb.append(tpl.substring(i));
                break;
            }
            sb.append(tpl.substring(i, open));
            String expr = tpl.substring(open + 1, close).trim();
            String val = "";
            try {
                if (expr.startsWith("$.") || expr.startsWith("$[")) {
                    org.json.JSONObject o = bookJson(b);
                    val = com.jianyue.reader.rule.JsonPath.firstString(o, expr);
                } else if (expr.startsWith("@js:") || expr.startsWith("<js>")
                        || expr.startsWith("{{")) {
                    String script = expr;
                    if (script.startsWith("@js:")) {
                        script = script.substring(4);
                    } else if (script.startsWith("<js>")) {
                        script = script.substring(4);
                        int e = script.lastIndexOf("</js>");
                        if (e > 0) {
                            script = script.substring(0, e);
                        }
                    }
                    val = com.jianyue.reader.rule.Js.run(script, baseUrl, baseUrl, null);
                }
            } catch (Throwable t) {
                Log.i(TAG, "engine.toc template failed: " + t.getMessage());
            }
            if (val == null) {
                val = "";
            }
            sb.append(val);
            changed = true;
            i = close + 1;
        }
        return changed ? sb.toString() : "";
    }

    /** The current book as JSON, so a template can address its fields uniformly. */
    private static org.json.JSONObject bookJson(Books.Book b) {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("name", b.name);
            o.put("author", b.author);
            o.put("bookUrl", b.bookUrl);
            o.put("tocUrl", b.tocUrl);
            o.put("intro", b.intro);
            o.put("kind", b.kind);
            // "$.id" is a very common spelling in 阅读 sources for "the site's book id"
            o.put("id", lastNumericSegment(b.bookUrl));
            o.put("bookId", lastNumericSegment(b.bookUrl));
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** Pull the trailing number out of a URL, e.g. /detail/1217041 -> 1217041. */
    private static String lastNumericSegment(String url) {
        if (url == null) {
            return "";
        }
        int end = url.length();
        // strip a trailing ".html" or "/"
        while (end > 0) {
            char c = url.charAt(end - 1);
            if (c == '/' || c == '?' || c == '#') {
                end--;
                continue;
            }
            break;
        }
        String tail = url.substring(0, end);
        int dot = tail.lastIndexOf(".html");
        if (dot > 0) {
            tail = tail.substring(0, dot);
        }
        int slash = tail.lastIndexOf('/');
        String seg = (slash >= 0) ? tail.substring(slash + 1) : tail;
        StringBuilder num = new StringBuilder();
        for (int i = 0; i < seg.length(); i++) {
            char c = seg.charAt(i);
            if (c >= '0' && c <= '9') {
                num.append(c);
            } else if (num.length() > 0) {
                break;
            }
        }
        return num.toString();
    }

    private static List<String> pickField(List<Object> items, String rule, String baseUrl) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < items.size(); i++) {
            Object node = items.get(i);
            String v = Rules.getString(node, rule, Rules.CTX_JSON, baseUrl);
            out.add(v == null ? "" : v.trim());
        }
        return out;
    }

    // --------------------------------------------------------------- content

    /** Fetch and clean one chapter's text. */
    public static Books.ChapterContent loadContent(BookSource src, Books.Chapter chapter) {
        // @put/@get variables live for one analysis pass only; leaking them across
        // books or chapters would make a rule read another book's value.
        Rules.resetPutVars();
        Books.ChapterContent out = new Books.ChapterContent();
        if (src == null || chapter == null) {
            return out;
        }
        out.title = chapter.name;
        String url = chapter.url;
        StringBuilder text = new StringBuilder();
        String lastError = null;
        try {
            int guard = 0;
            while (guard < 8) {
                guard++;
                if (url == null || url.length() == 0) {
                    break;
                }
                Http.Resp r = fetchContentPage(url, src);
                if (r == null || !r.ok() || r.code >= 400) {
                    lastError = (r == null || r.error == null) ? "HTTP -" : r.error;
                    break;
                }
                String body = r.text();
                BookSource.Rules rr = src.contentRules;
                String contentRule = (rr == null) ? null : rr.content;
                if (contentRule == null || contentRule.length() == 0) {
                    lastError = "该书源未配置正文规则";
                    break;
                }
                String piece = evalRule(body, contentRule, url);
                if (piece == null || piece.length() == 0) {
                    // Say WHY a 正文 came back empty: an unreachable-looking page, the wrong
                    // rule style, or a rule that simply does not match this response.
                    Log.i(TAG, "engine.content empty: rule=[" + contentRule + "] url=" + url
                            + " bodyType=" + (Rules.looksLikeJson(body) ? "json" : "html")
                            + " len=" + body.length() + " head=[" + preview(body) + "]");
                }
                if (piece != null && piece.length() > 0) {
                    text.append(piece).append('\n');
                }

                // pagination
                String nextRule = rr.nextContentUrl;
                if (nextRule == null || nextRule.length() == 0) {
                    break;
                }
                String next = evalRule(body, nextRule, url);
                next = Rules.absUrl(url, next);
                if (next == null || next.length() == 0 || next.equals(url)) {
                    break;
                }
                url = next;
            }
        } catch (Throwable t) {
            lastError = t.getClass().getName() + ": " + t.getMessage();
            Log.i(TAG, "engine.content error " + lastError);
        }
        out.content = Rules.cleanText(text.toString());
        if (out.content.length() == 0) {
            out.error = (lastError != null) ? lastError : "正文规则没有匹配到内容";
            out.content = "（正文为空：" + out.error + "）";
        }
        Log.i(TAG, "engine.content " + chapter.name + " -> " + out.content.length() + " chars"
                + " head=[" + preview(out.content) + "]");
        return out;
    }

    /**
     * Log the chapter-list rule and what it matched.
     *
     * "-> 0 rows" alone cannot distinguish the four ways a 目录 comes back empty: the page
     * was not what the rule expects, the rule was evaluated in the wrong mode, the JSON key
     * moved, or the request never returned the body at all. This prints enough to tell them
     * apart (the rule, the body's first bytes, and - for JSON - the top-level keys) without
     * dumping a whole page into logcat.
     */
    private static void logTocNodes(String listRule, int count, String body, boolean json) {
        if (count > 0) {
            return;
        }
        StringBuilder sb = new StringBuilder("engine.toc 0 rows: rule=[");
        sb.append(listRule).append("] json=").append(json);
        sb.append(" len=").append(body == null ? 0 : body.length());
        if (body != null && body.length() > 0) {
            sb.append(" head=[").append(preview(body)).append("]");
        }
        if (json && body != null && body.length() > 0) {
            try {
                JSONObject o = new JSONObject(body);
                StringBuilder keys = new StringBuilder();
                java.util.Iterator<?> it = o.keys();
                while (it.hasNext() && keys.length() < 200) {
                    if (keys.length() > 0) {
                        keys.append(',');
                    }
                    keys.append(String.valueOf(it.next()));
                }
                sb.append(" topKeys=[").append(keys).append("]");
            } catch (Throwable t) {
                sb.append(" not-json=").append(t.getClass().getSimpleName());
            }
        }
        Log.i(TAG, sb.toString());
    }

    /**
     * Fetch one 正文 page, retrying once.
     *
     * A chapter is normally split over 2-3 pages by nextContentUrl, and ONE slow page used to
     * truncate the chapter silently: the first page's text stayed and the loop just stopped,
     * so the reader showed a chapter that ended mid-sentence (新笔趣阁 returned 2199 of 5564
     * characters on a timeout). The retry is confined to content pages on purpose -- search
     * deliberately keeps its short timeout so dead sites do not stall the whole search.
     */
    private static Http.Resp fetchContentPage(String url, BookSource src) {
        Http.Resp r = Http.fetch(url, src.url, src.headers());
        if (r == null || !r.ok() || r.code >= 400) {
            Log.i(TAG, "engine.content retry page " + url
                    + (r == null ? " (null)" : (" (" + r.error + ")")));
            Http.Resp again = Http.fetch(url, src.url, src.headers());
            if (again != null && again.ok() && again.code < 400) {
                return again;
            }
        }
        return r;
    }

    /**
     * First ~60 characters of a chapter, with line breaks made visible.
     *
     * Only used in the log: a char count cannot distinguish "the rule returned text" from
     * "the rule returned mojibake", and it cannot show whether paragraph breaks survived.
     * Both break characters are escaped, because a bare '\r' inside the log line used to move
     * the rest of the line to column 0 and hide the paragraph structure completely.
     */
    private static String preview(String s) {
        if (s == null || s.length() == 0) {
            return "";
        }
        String t = (s.length() > 60) ? s.substring(0, 60) : s;
        return t.replace("\r", "\\r").replace("\n", "\\n");
    }
}

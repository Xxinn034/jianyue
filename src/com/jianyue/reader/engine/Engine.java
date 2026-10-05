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
     * Search every source in parallel and return the merged result.
     *
     * Why parallel: the sources are independent HTTP requests, and a serial loop made a
     * multi-source search take as long as the SUM of every site's latency. On API 1 the
     * dominant cost is waiting, not CPU, so a small pool cuts wall time roughly by the
     * thread count. Results are reassembled in source order so the list does not shuffle
     * between runs.
     *
     * The pool is deliberately small. An API-1 device has ~94 MB of RAM and each in-flight
     * search holds a response body plus a parsed document, so more threads buy little and
     * risk an OutOfMemoryError.
     */
    public static List<Books.Book> searchAll(List<BookSource> sources, String keyword,
                                             int page, List<String> failuresOut) {
        final List<Books.Book> out = new ArrayList<Books.Book>();
        if (sources == null || sources.size() == 0) {
            return out;
        }
        int threads = Math.min(4, sources.size());
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        final List<List<Books.Book>> slots = new ArrayList<List<Books.Book>>();
        final List<String> errors = new ArrayList<String>();
        for (int i = 0; i < sources.size(); i++) {
            slots.add(null);
            errors.add(null);
        }

        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(sources.size());
        for (int i = 0; i < sources.size(); i++) {
            final int idx = i;
            final BookSource src = sources.get(i);
            pool.execute(new Runnable() {
                public void run() {
                    try {
                        Books.SearchResult r = search(src, keyword, 1);
                        if (r.ok() && r.books.size() > 0) {
                            slots.set(idx, r.books);
                        } else if (!r.ok()) {
                            errors.set(idx, src.name + ": " + r.error);
                        } else {
                            errors.set(idx, src.name + ": 无结果");
                        }
                    } catch (Throwable t) {
                        errors.set(idx, src.name + ": " + t.getClass().getSimpleName());
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        try {
            latch.await();
        } catch (Throwable ignored) {
        }
        pool.shutdown();

        // reassemble in the original source order
        for (int i = 0; i < sources.size(); i++) {
            List<Books.Book> got = slots.get(i);
            if (got != null) {
                out.addAll(got);
            }
            if (failuresOut != null && errors.get(i) != null) {
                failuresOut.add(errors.get(i));
            }
        }
        Log.i(TAG, "engine.searchAll sources=" + sources.size()
                + " threads=" + threads + " books=" + out.size());
        return out;
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
            String key = URLEncoder.encode(keyword, "UTF-8");
            String url = src.searchUrl.replace("{{key}}", key)
                    .replace("{{page}}", String.valueOf(page))
                    .replace("{{keyword}}", key);
            url = Rules.absUrl(src.url, url);

            Http.Resp r = Http.getWithHttpFallback(url, src.url, null, src.headers());
            if (!r.ok() || r.code >= 400) {
                res.error = (r.error != null) ? r.error : ("HTTP " + r.code);
                return res;
            }
            String body = r.text();
            Log.i(TAG, "engine.search " + src.name + " url=" + url
                    + " bytes=" + (r.body == null ? 0 : r.body.length));

            String listRule = src.searchRules.bookList;
            if (listRule == null || listRule.length() == 0) {
                res.error = "该书源未配置 bookList 规则";
                return res;
            }

            // Decide JSON vs HTML from the rule itself, then parse accordingly.
            if (listRule.startsWith("$")) {
                JSONObject root = new JSONObject(body);
                List<Object> items = com.jianyue.reader.rule.JsonPath.eval(root, listRule);
                for (int i = 0; i < items.size(); i++) {
                    Object o = items.get(i);
                    if (o instanceof JSONArray) {
                        JSONArray a = (JSONArray) o;
                        for (int k = 0; k < a.length(); k++) {
                            Object e = a.opt(k);
                            if (e instanceof JSONObject) {
                                res.books.add(fromJson(src, (JSONObject) e));
                            }
                        }
                    } else if (o instanceof JSONObject) {
                        res.books.add(fromJson(src, (JSONObject) o));
                    }
                }
            } else {
                Html.El doc = Html.parse(body);
                List<Object> nodes = Rules.getNodes(doc, listRule, Rules.CTX_HTML);
                for (int i = 0; i < nodes.size(); i++) {
                    Object node = nodes.get(i);
                    if (node instanceof Html.El) {
                        res.books.add(fromHtml(src, (Html.El) node));
                    }
                }
            }
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

    /** Replace {$.a.b} / {$.a} placeholders with values from a JSON item. */
    public static String substituteJson(String template, JSONObject item) {
        if (template == null || item == null) {
            return template == null ? "" : template;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int n = template.length();
        while (i < n) {
            int open = template.indexOf("{$.", i);
            if (open < 0) {
                sb.append(template.substring(i));
                break;
            }
            sb.append(template.substring(i, open));
            int close = template.indexOf('}', open);
            if (close < 0) {
                sb.append(template.substring(open));
                break;
            }
            String path = template.substring(open + 1, close);   // "$.a.b"
            String v = com.jianyue.reader.rule.JsonPath.firstString(item, path);
            sb.append(v);
            i = close + 1;
        }
        return sb.toString();
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
    private static String evalRule(String body, String rule, String baseUrl) {
        if (rule == null || rule.trim().length() == 0) {
            return "";
        }
        try {
            if (rule.trim().startsWith("$")) {
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
            Http.Resp r = Http.getWithHttpFallback(url, src.url, null, src.headers());
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
            List<String> seen = new ArrayList<String>();
            int guard = 0;
            while (url != null && url.length() > 0 && guard < 20) {
                guard++;
                if (seen.contains(url)) {
                    break;
                }
                seen.add(url);
                String next = loadOneTocPage(src, b, url, out);
                if (out.size() > 0 && next != null && next.length() > 0
                        && !next.equals(url)) {
                    url = Rules.absUrl(url, next);
                } else {
                    break;
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
     * @return the URL of the next page, or "" when this was the last one.
     */
    private static String loadOneTocPage(BookSource src, Books.Book b, String url,
                                         List<Books.Chapter> out) {
        try {
            Http.Resp r = Http.getWithHttpFallback(url, b.bookUrl, null, src.headers());
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

            if (listRule.startsWith("$")) {
                JSONObject root = new JSONObject(body);
                List<Object> items = Rules.getNodes(root, listRule, Rules.CTX_JSON);
                names = pickField(items, rr.chapterName, src.url);
                urls = pickField(items, rr.chapterUrl, src.url);
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
                    String nx = evalRule(body, rr.nextTocUrl, url);
                    // "1/2" and a link back to the current page both mean "no next page"
                    if (nx != null && nx.length() > 0 && !nx.equals("下一页")
                            && !nx.equals("下页") && !nx.equals(url)
                            && !nx.equals(Rules.absUrl(url, nx))) {
                        nextToc = nx;
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
                Http.Resp d = Http.getWithHttpFallback(bookUrl, src.url, null, src.headers());
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
            out.content = "（正文为空" + (lastError != null ? "：" + lastError : "") + "）";
        }
        Log.i(TAG, "engine.content " + chapter.name + " -> " + out.content.length() + " chars"
                + " head=[" + preview(out.content) + "]");
        return out;
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
        Http.Resp r = Http.getWithHttpFallback(url, src.url, null, src.headers());
        if (r == null || !r.ok() || r.code >= 400) {
            Log.i(TAG, "engine.content retry page " + url
                    + (r == null ? " (null)" : (" (" + r.error + ")")));
            Http.Resp again = Http.getWithHttpFallback(url, src.url, null, src.headers());
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

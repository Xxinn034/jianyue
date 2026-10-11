package com.jianyue.reader.ui;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import java.util.List;

import com.jianyue.reader.rule.Rules;

/**
 * 规则语法自检 (rule-syntax self check).
 *
 * Verifies the 阅读 rule combinators that were added for compatibility:
 *   @CSS: / @@ / @XPath: / @Json:   explicit mode prefixes
 *   a && b                          concatenation
 *   a || b                          first non-empty alternative
 *   a %% b                          join a list with a separator
 *   {{ expr }}                      inline JavaScript
 *   @put:name ... @get:name         captured variables
 *
 * These cannot be checked from the host (API 1 has no input tap and the syntax is
 * evaluated in-process), so the assertions run here and land in logcat.
 *
 * Host-driven:
 *   am start -n com.jianyue.reader/.ui.RuleCheckActivity
 *
 * Log lines (tag JianYue):
 *   rulecheck: <name> => <result>   [PASS|FAIL]
 *   rulecheck: SUMMARY pass=<n> fail=<n>
 */
public class RuleCheckActivity extends Activity {

    private static final String TAG = "JianYue";

    private int pass;
    private int fail;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.setup(this, 0xFFF7F7F7);

        Thread t = new Thread(new Runnable() {
            public void run() {
                runChecks();
                Log.i(TAG, "rulecheck: SUMMARY pass=" + pass + " fail=" + fail);
            }
        });
        t.start();
    }

    private void runChecks() {
        // ---- a JSON node, as a search result row would be ----
        final String json = "{\"data\":{\"list\":[{\"name\":\"<b>斗罗大陆</b>\","
                + "\"authorName\":\"唐家三少\",\"bookId\":12345,"
                + "\"tags\":[\"玄幻\",\"热血\"]}]}}";

        Object root = null;
        try {
            root = new org.json.JSONObject(json);
        } catch (Throwable e) {
            Log.i(TAG, "rulecheck: cannot build json fixture " + e);
            fail++;
            return;
        }

        // ---- HTML node, as a detail page would be ----
        final String html = "<html><body>"
                + "<div class=\"book-info--title\"><h1>斗罗大陆V重生唐三</h1></div>"
                + "<span class=\"author\">唐家三少</span>"
                + "<div id=\"Jcontent\"><p>正文第一段。</p><p>正文第二段。</p></div>"
                + "</body></html>";
        com.jianyue.reader.rule.Html.El doc = null;
        try {
            doc = com.jianyue.reader.rule.Html.parse(html);
        } catch (Throwable e) {
            Log.i(TAG, "rulecheck: cannot build html fixture " + e);
            fail++;
            return;
        }

        // ---- HTML with REPEATED nodes, as a chapter split over several containers ----
        final String htmlMulti = "<html><body>"
                + "<div class=\"con\">第一段</div>"
                + "<div class=\"con\">第二段</div>"
                + "<div class=\"con\">第三段</div>"
                + "</body></html>";
        com.jianyue.reader.rule.Html.El docMulti = null;
        try {
            docMulti = com.jianyue.reader.rule.Html.parse(htmlMulti);
        } catch (Throwable e) {
            Log.i(TAG, "rulecheck: cannot build html fixture 2 " + e);
            fail++;
            return;
        }

        // ---- JSONPath (baseline) ----
        expect("jsonpath leaf", Rules.getString(root, "$.data.list[0].bookId",
                Rules.CTX_JSON, null), "12345");

        // ---- @Json: explicit prefix ----
        expect("prefix @Json:", Rules.getString(root, "@Json:$.data.list[0].authorName",
                Rules.CTX_JSON, null), "唐家三少");

        // ---- && concatenation ----
        expect("combinator &&",
                Rules.getString(root, "$.data.list[0].authorName&&$.data.list[0].bookId",
                        Rules.CTX_JSON, null), "唐家三少12345");

        // ---- || fallback: first branch misses, second hits ----
        expect("combinator || (fallback)",
                Rules.getString(root, "$.data.list[0].missing||$.data.list[0].authorName",
                        Rules.CTX_JSON, null), "唐家三少");

        // ---- %% join ----
        expect("combinator %% (join)",
                Rules.getString(root, "$.data.list[0].tags[*]%%/",
                        Rules.CTX_JSON, null), "玄幻/热血");

        // ---- {{ }} inline JS over the node ----
        expect("inline {{ }} js",
                Rules.getString(root, "{{result.substring(0,1)}}",
                        Rules.CTX_JSON, null), "{");

        // ---- @@ escape: the "@" in the string must not be treated as a prefix ----
        expect("escape @@",
                Rules.getString(root, "@@$.data.list[0].authorName",
                        Rules.CTX_JSON, null), "唐家三少");

        // ---- CSS alias + suffix (baseline) ----
        expect("css class. + @text",
                Rules.getString(doc, "class.book-info--title@text", Rules.CTX_HTML, null),
                "斗罗大陆V重生唐三");

        // ---- @CSS: explicit prefix, with the id. alias ----
        expect("prefix @CSS: with id.",
                Rules.getString(doc, "@CSS:id.Jcontent@text", Rules.CTX_HTML, null),
                "正文第一段。正文第二段。");

        // ---- CSS with a replace tail ----
        expect("css + ##replace##",
                Rules.getString(doc, "class.author@text##唐##宋", Rules.CTX_HTML, null),
                "宋家三少");

        // ---- CSS WITHOUT an index joins every match, exactly like 阅读's
        // AnalyzeByJSoup.getString() (getStringList().joinToString("\n")).
        // Taking only the first match silently truncated any chapter that a site splits
        // across several containers: 阅友小说 serves 5 .con blocks per chapter, so the
        // reader showed 600 of 3004 characters and stopped at "（本章未完，请翻页）".
        expect("css multi-match joins all",
                Rules.getString(docMulti, "class.con@text", Rules.CTX_HTML, null),
                "第一段\n第二段\n第三段");

        // an explicit index still selects exactly one element
        expect("css multi-match honours .-1",
                Rules.getString(docMulti, "class.con.-1@text", Rules.CTX_HTML, null),
                "第三段");

        // a joined value must never leak into a URL
        expect("url keeps only the first line",
                Rules.absUrl("http://a.com/x/", "http://b.com/1\nhttp://c.com/2"),
                "http://b.com/1");

        // ---- XPath -, @XPath: prefix ----
        expect("prefix @XPath:",
                Rules.getString(doc, "@XPath://span[@class='author']@text",
                        Rules.CTX_HTML, null), "唐家三少");

        // ---- @put / @get across two rules ----
        Rules.resetPutVars();
        Rules.getString(root, "$.data.list[0].authorName@put:who", Rules.CTX_JSON, null);
        expect("put/get variable",
                Rules.getString(root, "@get:who", Rules.CTX_JSON, null), "唐家三少");

        // ---- && between a JSON and a literal-looking branch ----
        expect("&& keeps order",
                Rules.getString(root, "$.data.list[0].bookId&&$.data.list[0].authorName",
                        Rules.CTX_JSON, null), "12345唐家三少");

        // ---------------------------------------------------------------
        // 阅读 index syntax: "!N:N:N" excludes those indices from a node set.
        //
        // This is how a source skips a duplicated "latest chapters" block. The real case is
        // 鬼吹灯: "class.listmain@dd!0:1:2:3:4:5:6:7:8:9:10:11" - the first 12 <dd> entries
        // repeat the newest chapters, so they are excluded. Before this was implemented the
        // whole suffix was treated as a tag name and the chapter list came back EMPTY.
        // ---------------------------------------------------------------
        final String toc = "<html><body><div class=\"listmain\"><dl>"
                + "<dt>最新章节</dt>"
                + "<dd><a href=\"/a/1.html\">新章一</a></dd>"
                + "<dd><a href=\"/a/2.html\">新章二</a></dd>"
                + "<dt>正文</dt>"
                + "<dd><a href=\"/a/3.html\">第一章</a></dd>"
                + "<dd><a href=\"/a/4.html\">第二章</a></dd>"
                + "<dd><a href=\"/a/5.html\">第三章</a></dd>"
                + "</dl></div></body></html>";
        com.jianyue.reader.rule.Html.El tocDoc = com.jianyue.reader.rule.Html.parse(toc);

        List<Object> allDd = Rules.getNodes(tocDoc, "class.listmain@dd", Rules.CTX_HTML);
        expect("toc plain @dd count", String.valueOf(allDd.size()), "5");

        List<Object> keptDd = Rules.getNodes(tocDoc, "class.listmain@dd!0:1",
                Rules.CTX_HTML);
        expect("toc @dd!0:1 excludes 2", String.valueOf(keptDd.size()), "3");
        expect("toc @dd!0:1 first name",
                Rules.getString(keptDd.get(0), "tag.a@text", Rules.CTX_HTML, null), "第一章");

        List<Object> noneKept = Rules.getNodes(tocDoc, "class.listmain@dd!0:1:2:3:4",
                Rules.CTX_HTML);
        expect("toc @dd!all excludes all", String.valueOf(noneKept.size()), "0");

        // malformed exclusion list must exclude nothing, not everything
        List<Object> badSpec = Rules.getNodes(tocDoc, "class.listmain@dd!x:y",
                Rules.CTX_HTML);
        expect("toc @dd!bad keeps all", String.valueOf(badSpec.size()), "5");

        // "tag." prefix must still work, with and without an exclusion list
        List<Object> viaTag = Rules.getNodes(tocDoc, "class.listmain@tag.a!0", Rules.CTX_HTML);
        expect("toc @tag.a!0 excludes 1", String.valueOf(viaTag.size()), "4");

        // ---------------------------------------------------------------
        // ":"-regex chapterList (阅读's regex mode).
        //
        // A rule starting with ":" is a java.util.regex pattern run over the whole body;
        // every match becomes one list element and the capture groups are addressable as
        // "$1", "$2", ... That is how 黑岩小说① writes its 目录, and getting it wrong was
        // the "搜索有结果但目录为空" case.
        // ---------------------------------------------------------------
        final String regexToc = "<html><body><div class=\"dir\">"
                + "<li><a href=\"/book/1/1001.html\"class=\"isvip name\">第一章 开始</a></li>"
                + "<li><a href=\"/book/1/1002.html\"class=\"name\">第二章 继续</a></li>"
                + "</div></body></html>";
        com.jianyue.reader.rule.Html.El regexDoc = com.jianyue.reader.rule.Html.parse(regexToc);
        // The haystack is the DOM serialised back to HTML, so attribute spacing is
        // normalised to a single space; patterns must tolerate that. This shape mirrors the
        // real 黑岩 source: an id group, an optional vip group and a name group, addressed
        // as $1/$2/$3.
        final String regexRule = ":(\\d+)\\.html\"\\s+class=\"(isvip)?\\s*[^\"]*name[^\"]*\"[^>]*>([^<]*)";
        List<Object> regexNodes = Rules.getNodes(regexDoc, regexRule, Rules.CTX_HTML);
        expect("regex toc match count", String.valueOf(regexNodes.size()), "2");
        if (regexNodes.size() == 2) {
            expect("regex toc $3 name",
                    Rules.getString(regexNodes.get(0), "$3", Rules.CTX_HTML, null), "第一章 开始");
            expect("regex toc $1 id",
                    Rules.getString(regexNodes.get(0), "$1", Rules.CTX_HTML, null), "1001");
            expect("regex toc $2 vip",
                    Rules.getString(regexNodes.get(0), "$2", Rules.CTX_HTML, null), "isvip");
            expect("regex toc $2 empty when not vip",
                    Rules.getString(regexNodes.get(1), "$2", Rules.CTX_HTML, null), "");
            expect("regex toc url template",
                    Rules.getString(regexNodes.get(1),
                            "http://a.example.com/ajax/chapter/content/$1", Rules.CTX_HTML, null),
                    "http://a.example.com/ajax/chapter/content/1002");
        }

        // A url rule that does NOT end with "$" must keep its literal tail. This guards the
        // bug where group substitution ate trailing text (e.g. "##.*：" losing its colon).
        expect("plain text keeps colon",
                Rules.getString(doc, "class.author@text##.*：", Rules.CTX_HTML, null), "唐家三少");
        expect("replace keeps trailing char",
                Rules.getString(doc, "class.author@text##三少##三少！", Rules.CTX_HTML, null),
                "唐家三少！");

        // ---------------------------------------------------------------
        // CSS index suffixes: "class.x.0" / "class.x.-1" / "class.x@tag.a.-1".
        //
        // 阅读 writes these everywhere (face-info span.1:3, .book_other.0, .pages a.-1).
        // Without index support the selector matched nothing and the field came back empty
        // rather than wrong - the "silent empty" failure mode.
        // ---------------------------------------------------------------
        final String idxHtml = "<html><body><div class=\"pages\"><a href=\"/p1\">1</a>"
                + "<a href=\"/p2\">2</a><a href=\"/p3\">3</a></div>"
                + "<div class=\"face-info\"><span>作者：甲</span><span>状态：乙</span>"
                + "<span>字数：丙</span></div></body></html>";
        com.jianyue.reader.rule.Html.El idxDoc = com.jianyue.reader.rule.Html.parse(idxHtml);
        expect("css index .0",
                Rules.getString(idxDoc, "class.pages@tag.a.0@text", Rules.CTX_HTML, null), "1");
        expect("css index .-1",
                Rules.getString(idxDoc, "class.pages@tag.a.-1@text", Rules.CTX_HTML, null), "3");
        expect("css index .-1 href",
                Rules.getString(idxDoc, "class.pages@tag.a.-1@href", Rules.CTX_HTML, null), "/p3");
        expect("css nested index",
                Rules.getString(idxDoc, "class.face-info@tag.span.1@text##.*：",
                        Rules.CTX_HTML, null), "乙");
        List<Object> idxNodes = Rules.getNodes(idxDoc, "class.pages@tag.a.-1", Rules.CTX_HTML);
        expect("getNodes index .-1 count", String.valueOf(idxNodes.size()), "1");
        if (idxNodes.size() == 1) {
            expect("getNodes index .-1 text",
                    Rules.getString(idxNodes.get(0), "text", Rules.CTX_HTML, null), "3");
        }

        // ---------------------------------------------------------------
        // 正文分段 (paragraph structure) + 排版 (标题置顶, 每段空两格).
        //
        // 阅读's HtmlFormatter turns block-level tags into line breaks
        // (wrapHtmlRegex, "&lt;/?(?:div|p|br|hr|h\d|article|dd|dl)...&gt;" -&gt; "\n") before the
        // remaining tags are stripped, and its ContentProcessor then puts the chapter name on
        // its own first line and prefixes every other paragraph with 　　
        // (ReadBookConfig.paragraphIndent). Without the first half, every @html content rule
        // (纵横 id.Jcontent, 阅友 .con, 猫九 id.xs-content) delivered a whole chapter as ONE
        // line - the "正文没有分段" report; without the second, the page had no 首行缩进 at all.
        // ---------------------------------------------------------------
        final String paraHtml = "<html><body><div id=\"Jcontent\">"
                + "<p>第一段。</p><p>第二段。</p>"
                + "<div>第三段<br>还是第三段。</div></div>"
                + "<article>甲<br>乙</article></body></html>";
        com.jianyue.reader.rule.Html.El paraDoc = com.jianyue.reader.rule.Html.parse(paraHtml);

        expect("block tags become line breaks",
                Rules.cleanText("<p>甲</p><p>乙</p>"), "甲\n乙");
        expect("br becomes a line break", Rules.cleanText("甲<br>乙"), "甲\n乙");
        expect("site indent normalised",
                Rules.cleanText("\u3000\u3000甲。\n\u3000\u3000乙。"), "甲。\n乙。");
        expect("html keeps paragraph order",
                Rules.cleanText(Rules.getString(paraDoc, "id.Jcontent@html",
                        Rules.CTX_HTML, null)),
                "第一段。\n第二段。\n第三段\n还是第三段。");
        expect("textNodes joins with \\n",
                Rules.getString(paraDoc, "tag.article@textNodes", Rules.CTX_HTML, null),
                "甲\n乙");
        expect("paragraphs: title on top, rest indented",
                com.jianyue.reader.util.Paragraphs.format(
                        Rules.cleanText(Rules.getString(paraDoc, "id.Jcontent@html",
                                Rules.CTX_HTML, null)),
                        "第一章 起点", "书名"),
                "第一章 起点\n\u3000\u3000第一段。\n\u3000\u3000第二段。"
                        + "\n\u3000\u3000第三段\n\u3000\u3000还是第三段。");
        expect("paragraphs: repeated title dropped",
                com.jianyue.reader.util.Paragraphs.format("第一章 起点\n正文。",
                        "第一章 起点", "书名"),
                "第一章 起点\n\u3000\u3000正文。");
        expect("paragraphs: blank lines dropped",
                com.jianyue.reader.util.Paragraphs.format("甲。\n\n\n乙。", "第1章", null),
                "第1章\n\u3000\u3000甲。\n\u3000\u3000乙。");

        // ---------------------------------------------------------------
        // 引擎兼容层 (2026-10)：为了能直接吃「阅读 3.0」的书源而补的四块
        //   1. @ownText 取值后缀
        //   2. URL 末尾 ", {json}" 请求选项（POST / headers / charset）
        //   3. 旧版扁平格式书源 → 现代结构的字段与方言转换
        //   4. JS 宿主 java.* / Packages / source / book（书源脚本直接调用宿主）
        // ---------------------------------------------------------------

        // ---- 1. @ownText: 只要元素自身的直接文本，不含子元素文本
        final String ownHtml = "<html><body>"
                + "<div class=\"row\">甲<span>乙</span>丙</div>"
                + "<div class=\"row\">只有我</div></body></html>";
        com.jianyue.reader.rule.Html.El ownDoc = com.jianyue.reader.rule.Html.parse(ownHtml);
        expect("ownText skips child text",
                Rules.getString(ownDoc, "class.row.0@ownText", Rules.CTX_HTML, null), "甲丙");
        expect("text includes child text",
                Rules.getString(ownDoc, "class.row.0@text", Rules.CTX_HTML, null), "甲乙丙");

        // ---- 2. ", {json}" options tail (阅读: AnalyzeUrl 的 options)
        com.jianyue.reader.net.UrlOptions uo = com.jianyue.reader.net.UrlOptions.parse(
                "http://a.example.com/search, {\"method\":\"POST\",\"body\":\"k={{key}}\","
                        + "\"charset\":\"gbk\",\"headers\":{\"Referer\":\"http://a.example.com/\"}}");
        expect("url option: url split", uo.url, "http://a.example.com/search");
        expect("url option: method", uo.method, "POST");
        expect("url option: body", uo.body, "k={{key}}");
        expect("url option: charset", uo.charset, "gbk");
        expect("url option: header", uo.headers.get("Referer"), "http://a.example.com/");
        expect("url option: no tail when absent",
                com.jianyue.reader.net.UrlOptions.parse("http://a/b?x=1,2").url, "http://a/b?x=1,2");

        // ---- 3. 旧版扁平格式 (阅读 2.x) 转换
        try {
            org.json.JSONObject flat = new org.json.JSONObject();
            flat.put("bookSourceName", "旧格式站");
            flat.put("bookSourceUrl", "http://old.example.com");
            flat.put("ruleSearchUrl", "/search?q=searchKey&p=searchPage");
            flat.put("ruleSearchList", "class.result-list@tag.li");
            flat.put("ruleSearchName", "class.name|class.title");
            flat.put("ruleSearchNoteUrl", "tag.a@href");
            flat.put("ruleChapterList", "id.chapterList@tag.a");
            flat.put("ruleBookContent", "id.content#<[^>]+>#");
            flat.put("httpUserAgent", "Mozilla/5.0 old");
            com.jianyue.reader.model.BookSource conv =
                    com.jianyue.reader.model.BookSource.fromJson(flat);
            expect("legacy: detected and converted", conv == null ? "null" : "ok", "ok");
            if (conv != null) {
                expect("legacy: searchUrl placeholders",
                        conv.searchUrl, "/search?q={{key}}&p={{page}}");
                expect("legacy: ruleSearchList -> bookList",
                        conv.searchRules.bookList, "class.result-list@tag.li");
                expect("legacy: single | becomes ||",
                        conv.searchRules.name, "class.name||class.title");
                expect("legacy: single # becomes ##",
                        conv.contentRules.content, "id.content##<[^>]+>##");
                expect("legacy: chapterList", conv.tocRules.chapterList,
                        "id.chapterList@tag.a");
                expect("legacy: httpUserAgent -> header", conv.header,
                        "User-Agent@Mozilla/5.0 old");
            }
            expect("legacy: @Header: -> options",
                    com.jianyue.reader.model.LegacySource.toNewUrl(
                            "http://a/b@Header:{Referer:\"http://a/\"}"),
                    "http://a/b,{\"headers\":{\"Referer\":\"http://a/\"}}");
            expect("legacy: charset option",
                    com.jianyue.reader.model.LegacySource.toNewUrl("/s?q=searchKey|charset=gbk"),
                    "/s?q={{key}},{\"charset\":\"gbk\"}");
            expect("legacy: post body option",
                    com.jianyue.reader.model.LegacySource.toNewUrl("/api@k=searchKey"),
                    "/api,{\"method\":\"POST\",\"body\":\"k={{key}}\"}");
            expect("legacy: js rule untouched",
                    com.jianyue.reader.model.LegacySource.toNewRule("@js:result|split('&')"),
                    "@js:result|split('&')");
        } catch (Throwable e) {
            Log.i(TAG, "rulecheck: legacy fixture failed " + e);
            fail++;
        }

        // ---- 4. {{page+N}} 模板（旧格式的 searchPage+1 会变成这个样子）
        expect("url template page+1",
                com.jianyue.reader.engine.Engine.expandTemplate(
                        "http://a/b?p={{page+1}}", null, "x", 2),
                "http://a/b?p=3");
        expect("url template key is url-encoded",
                com.jianyue.reader.engine.Engine.expandTemplate(
                        "http://a/b?q={{key}}", null, "斗罗", 1),
                "http://a/b?q=%E6%96%97%E7%BD%97");

        // ---- 5. JS 宿主：java.* / Packages / source / book
        expect("js java.base64Encode",
                com.jianyue.reader.rule.Js.run("java.base64Encode('abc')", (com.jianyue.reader.rule.Js.Bind) null),
                "YWJj");
        expect("js java.base64Decode",
                com.jianyue.reader.rule.Js.run("java.base64Decode('YWJj')", (com.jianyue.reader.rule.Js.Bind) null),
                "abc");
        expect("js java.md5Encode",
                com.jianyue.reader.rule.Js.run("java.md5Encode('abc')", (com.jianyue.reader.rule.Js.Bind) null),
                "900150983cd24fb0d6963f7d28e17f72");
        expect("js Packages available",
                com.jianyue.reader.rule.Js.run(
                        "new Packages.java.lang.StringBuilder('ab').append('c').toString()",
                        (com.jianyue.reader.rule.Js.Bind) null),
                "abc");
        com.jianyue.reader.rule.Js.Bind bind = new com.jianyue.reader.rule.Js.Bind();
        bind.result = "<div class=\"n\">书名甲</div>";
        bind.book = new String[]{"斗罗大陆", "唐家三少", "http://a/book/1", "", "", ""};
        bind.sourceName = "测试源";
        bind.sourceUrl = "http://a.example.com";
        expect("js book.name bound",
                com.jianyue.reader.rule.Js.run("book.name + '/' + book.author", bind),
                "斗罗大陆/唐家三少");
        expect("js source.name bound",
                com.jianyue.reader.rule.Js.run("source.name", bind), "测试源");
        expect("js java.getString runs a rule",
                com.jianyue.reader.rule.Js.run("java.getString('class.n@text')", bind), "书名甲");
        com.jianyue.reader.rule.Js.run("java.put('ck','7')", bind);
        expect("js java.put visible to @get:",
                Rules.getString(root, "@get:ck", Rules.CTX_JSON, null), "7");

        // Which java.* calls actually resolve, one line in the log. A missing method shows up
        // as "not a function" here; without this the only symptom is an empty field.
        com.jianyue.reader.rule.Js.Bind probeBind = new com.jianyue.reader.rule.Js.Bind();
        probeBind.result = "<div class=\"n\">书名甲</div>";
        Log.i(TAG, "rulecheck: js probe => " + com.jianyue.reader.rule.Js.run(
                "var o=[];"
                        + "try{o.push('b64='+java.base64Encode('x'))}catch(e){o.push('b64=ERR')}"
                        + "try{o.push('uri='+java.encodeURI('a b'))}catch(e){o.push('uri=ERR')}"
                        + "try{o.push('get='+java.get('zz'))}catch(e){o.push('get=ERR')}"
                        + "try{o.push('put='+java.put('k','v'))}catch(e){o.push('put=ERR')}"
                        + "try{o.push('gs='+java.getString('class.n@text'))}"
                        + "catch(e){o.push('gs=ERR')}"
                        + "o.join('|')", probeBind));
    }

    /** Compare against expectation and log a verdict. */
    private void expect(String name, String actual, String want) {
        boolean ok = want.equals(actual);
        if (ok) {
            pass++;
        } else {
            fail++;
        }
        // Newlines are escaped in the LOG only: a bare '\n' inside a logcat line breaks it in
        // two, and the second half looks like an unrelated message.
        Log.i(TAG, "rulecheck: " + name + " => [" + show(actual) + "]  "
                + (ok ? "PASS" : ("FAIL want=[" + show(want) + "]")));
    }

    private static String show(String s) {
        return (s == null) ? "null" : s.replace("\n", "\\n");
    }
}

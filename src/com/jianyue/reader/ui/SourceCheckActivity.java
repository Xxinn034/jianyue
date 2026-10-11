package com.jianyue.reader.ui;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import com.jianyue.reader.model.BookSource;
import com.jianyue.reader.rule.Rules;

/**
 * 书源兼容性体检 (book-source compatibility report).
 *
 * WHAT IT IS FOR
 * --------------
 * 阅读 3.0 has a large rule syntax. This engine implements a subset, so importing a real
 * source raises one question: what exactly will not work? Guessing is useless - the answer
 * is per-rule and per-source.
 *
 * This screen loads a 阅读-format source file, parses it, and walks every rule, classifying
 * each one as supported or not, with the reason. The result is a concrete list of gaps
 * rather than a claim of "mostly compatible".
 *
 * Host-driven, because API 1 cannot synthesise a tap:
 *   am start -n com.jianyue.reader/.ui.SourceCheckActivity -e listPath /sdcard/sources.json
 *
 * Log lines (tag JianYue):
 *   check: file=<path> sources=<n>
 *   check: [<i>] <name> type=<t> supported=<y/n>
 *   check:   UNSUPPORTED <field>: <rule>  (<reason>)
 *   check:   note <note>
 *   check: SUMMARY sources=<n> supported=<n> rules=<n> unsupportedRules=<n>
 */
public class SourceCheckActivity extends Activity {

    private static final String TAG = "JianYue";

    private int ruleCount;
    private int badRuleCount;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.setup(this, 0xFFF7F7F7);

        final String path = getIntent().getStringExtra("listPath");
        if (path == null || path.length() == 0) {
            Log.i(TAG, "check: no listPath extra, nothing to do");
            finish();
            return;
        }
        final String json = readAll(path);
        Log.i(TAG, "check: file=" + path + " bytes=" + json.length());

        Thread t = new Thread(new Runnable() {
            public void run() {
                List<BookSource> list = BookSource.parseFile(json);
                Log.i(TAG, "check: parsed=" + list.size() + " sources");
                resetReport();
                int supported = 0;
                for (int i = 0; i < list.size(); i++) {
                    BookSource s = list.get(i);
                    ruleCount = 0;
                    badRuleCount = 0;
                    boolean typeOk = s.isSupported();
                    Log.i(TAG, "check: [" + i + "] " + s.name
                            + " type=" + s.typeName()
                            + " supported=" + (s.isSupported() ? "yes" : "no"));
                    if (s.type != 0) {
                        Log.i(TAG, "check:   UNSUPPORTED whole-source: type=" + s.typeName()
                                + " (this app reads text sources only)");
                    }
                    if (s.header.length() > 0) {
                        Log.i(TAG, "check:   note headers=" + s.headers().size()
                                + " (applied to every request)");
                    }
                    if (s.exploreUrl.length() > 0) {
                        Log.i(TAG, "check:   note has exploreUrl (发现 not implemented)");
                    }
                    if (s.bookUrlPattern.length() > 0) {
                        Log.i(TAG, "check:   note has bookUrlPattern (accepted, used for matching)");
                    }
                    check(s.name, "search.url", s.searchUrl);
                    checkRules(s.name, "search", s.searchRules);
                    checkRules(s.name, "bookInfo", s.bookInfoRules);
                    checkRules(s.name, "toc", s.tocRules);
                    checkRules(s.name, "content", s.contentRules);
                    boolean ok = typeOk && badRuleCount == 0;
                    if (ok) {
                        supported++;
                    }
                    Log.i(TAG, "check:   -> rules=" + ruleCount
                            + " unsupported=" + badRuleCount
                            + " verdict=" + (ok ? "COMPATIBLE" : "PARTIAL"));
                    report("VERDICT\t" + i + "\t" + (ok ? "COMPATIBLE" : "PARTIAL")
                            + "\trules=" + ruleCount + "\tunsupported=" + badRuleCount);
                }
                Log.i(TAG, "check: SUMMARY sources=" + list.size()
                        + " fullyCompatible=" + supported);
                report("SUMMARY\tsources=" + list.size()
                        + "\tfullyCompatible=" + supported);
                report("END");
            }
        });
        t.start();
    }

    /** Where the machine-readable report is written, so logcat loss cannot hide it. */
    static final String REPORT_PATH = "/sdcard/source-check.txt";

    private static void resetReport() {
        java.io.FileOutputStream out = null;
        try {
            out = new java.io.FileOutputStream(REPORT_PATH, false);
            out.write("".getBytes("UTF-8"));
            out.flush();
        } catch (Throwable t) {
            Log.i(TAG, "check: cannot reset report: " + t.getMessage());
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) { }
        }
    }

    /** Append one machine-readable line to the report on /sdcard. */
    static synchronized void report(String line) {
        java.io.FileOutputStream out = null;
        try {
            out = new java.io.FileOutputStream(REPORT_PATH, true);
            out.write((line + "\n").getBytes("UTF-8"));
            out.flush();
        } catch (Throwable t) {
            Log.i(TAG, "check: cannot write report: " + t.getMessage());
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) { }
        }
    }

    private void checkRules(String src, String group, BookSource.Rules r) {
        if (r == null) {
            return;
        }
        check(src, group + ".bookList", r.bookList);
        check(src, group + ".name", r.name);
        check(src, group + ".author", r.author);
        check(src, group + ".kind", r.kind);
        check(src, group + ".intro", r.intro);
        check(src, group + ".wordCount", r.wordCount);
        check(src, group + ".lastChapter", r.lastChapter);
        check(src, group + ".coverUrl", r.coverUrl);
        check(src, group + ".bookUrl", r.bookUrl);
        check(src, group + ".tocUrl", r.tocUrl);
        check(src, group + ".chapterList", r.chapterList);
        check(src, group + ".chapterName", r.chapterName);
        check(src, group + ".chapterUrl", r.chapterUrl);
        check(src, group + ".isVip", r.isVip);
        check(src, group + ".content", r.content);
        check(src, group + ".nextContentUrl", r.nextContentUrl);
    }

    /** Classify one rule and log it when it is not understood. */
    private void check(String src, String field, String rule) {
        if (rule == null || rule.length() == 0) {
            return;
        }
        ruleCount++;
        String reason = unsupportedReason(rule);
        if (reason == null && rule.indexOf(",{") > 0) {
            // ", {json}" request options: method/body/headers/charset are honoured, but
            // these need a browser or a JS request pipeline.
            com.jianyue.reader.net.UrlOptions uo = com.jianyue.reader.net.UrlOptions.parse(rule);
            if (uo.unsupported != null) {
                reason = uo.unsupported;
            } else if (rule.indexOf("\"type\"") >= 0) {
                reason = "request option type= (hex response body) not implemented";
            }
        }
        if (reason != null) {
            badRuleCount++;
            Log.i(TAG, "check:   UNSUPPORTED " + field + ": " + rule + "  (" + reason + ")");
            report("UNSUPPORTED\t" + src + "\t" + field + "\t" + reason + "\t" + rule);
        }
    }

    /**
     * Return null when the engine handles the construct, else a short reason.
     *
     * Deliberately conservative: it looks for syntax this engine does NOT implement rather
     * than trying to prove a rule is valid, because a rule can be syntactically fine and
     * still fail against the live site.
     */
    static String unsupportedReason(String rule) {
        String r = Rules.stripReplace(rule.trim());
        if (r.length() == 0) {
            return null;
        }

        // JS rules: Rhino covers most of it, and since the 2026-10 engine work the host
        // objects a 阅读 source expects are provided: java.* (base64/md5/time/ajax/put/get/
        // getString...), Packages.* / JavaImporter (arbitrary Java, with java.io.File and
        // Runtime blocked), source.*, book.*, baseUrl, result, cookie (read-only).
        // What is still missing is listed below instead.
        if (r.startsWith("@js:") || r.startsWith("<js>") || r.startsWith("{{js")) {
            String body = r;
            if (r.startsWith("@js:")) {
                body = r.substring(4);
            }
            if (body.indexOf("webView") >= 0 || body.indexOf("java.webView") >= 0) {
                return "JS needs a WebView, which API 1 cannot provide";
            }
            if (body.indexOf("cache.") >= 0 || body.indexOf("cache.put") >= 0) {
                return "JS cache.* host object not provided";
            }
            if (body.indexOf("getLoginInfo") >= 0 || body.indexOf("getCookie") >= 0) {
                return "JS login/cookie host API not provided";
            }
            if (body.indexOf("queryTTF") >= 0 || body.indexOf("replaceFont") >= 0) {
                return "JS font de-obfuscation not implemented";
            }
            return null;
        }

        // XPath: only a subset is implemented.
        if (r.startsWith("//") || r.startsWith("/")) {
            if (r.indexOf("following-sibling") >= 0 || r.indexOf("preceding-sibling") >= 0) {
                return "XPath axis not implemented";
            }
            if (r.indexOf("ancestor") >= 0 || r.indexOf("descendant-or-self") >= 0) {
                return "XPath axis not implemented";
            }
            if (r.indexOf("position()") >= 0 || r.indexOf("last()") >= 0) {
                return "XPath function not implemented";
            }
            if (r.indexOf("normalize-space") >= 0 || r.indexOf("string(") >= 0
                    || r.indexOf("substring") >= 0 || r.indexOf("concat(") >= 0) {
                return "XPath function not implemented";
            }
            if (r.indexOf("|") >= 0) {
                return "XPath union not implemented";
            }
            return null;
        }

        // JSONPath: $.. recursive descent and [*] are implemented; filters and functions are not.
        if (r.startsWith("$")) {
            if (r.indexOf("?(") >= 0) {
                return "JSONPath filter expression not implemented";
            }
            if (r.indexOf("length()") >= 0) {
                return "JSONPath function not implemented";
            }
            return null;
        }

        // CSS: the selector engine handles tag/class/id/attribute, index suffixes (.0/.-1),
        // exclusion lists (!0:1) and multi-class names (class.a.b).
        if (r.startsWith("class.") || r.startsWith("id.") || r.startsWith("tag.")
                || r.startsWith(".") || r.startsWith("#")) {
            if (r.indexOf(":eq(") >= 0 || r.indexOf(":gt(") >= 0 || r.indexOf(":lt(") >= 0) {
                // 阅读 itself has no :eq/:gt/:lt either (jsoup 1.14 has no parser branch for
                // them), so a source using them is broken upstream too.
                return "CSS index pseudo-class not implemented (also unsupported by 阅读)";
            }
            if (r.indexOf(":contains(") >= 0) {
                return "CSS :contains not implemented";
            }
            if (r.indexOf(":not(") >= 0) {
                return "CSS :not not implemented";
            }
            return null;
        }

        return null;
    }

    private static String readAll(String path) {
        StringBuilder sb = new StringBuilder();
        FileInputStream in = null;
        try {
            in = new FileInputStream(new File(path));
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            br.close();
        } catch (Throwable t) {
            Log.i(TAG, "check: cannot read " + path + " : " + t.getMessage());
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
        }
        return sb.toString();
    }
}

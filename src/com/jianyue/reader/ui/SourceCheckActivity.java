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
                int supported = 0;
                for (int i = 0; i < list.size(); i++) {
                    BookSource s = list.get(i);
                    ruleCount = 0;
                    badRuleCount = 0;
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
                    boolean ok = s.isSupported() && badRuleCount == 0;
                    if (ok) {
                        supported++;
                    }
                    Log.i(TAG, "check:   -> rules=" + ruleCount
                            + " unsupported=" + badRuleCount
                            + " verdict=" + (ok ? "COMPATIBLE" : "PARTIAL"));
                }
                Log.i(TAG, "check: SUMMARY sources=" + list.size()
                        + " fullyCompatible=" + supported);
            }
        });
        t.start();
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
        if (reason != null) {
            badRuleCount++;
            Log.i(TAG, "check:   UNSUPPORTED " + field + ": " + rule + "  (" + reason + ")");
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

        // JS rules: Rhino covers most of it, but these constructs need host objects or Java
        // access that the sandboxed interpreter does not expose.
        if (r.startsWith("@js:") || r.startsWith("<js>") || r.startsWith("{{js")) {
            String body = r;
            if (r.startsWith("@js:")) {
                body = r.substring(4);
            }
            if (body.indexOf("java.") >= 0 || body.indexOf("Packages.") >= 0
                    || body.indexOf("importClass") >= 0 || body.indexOf("importPackage") >= 0) {
                return "JS uses Java class access, which is not exposed";
            }
            if (body.indexOf("source.") >= 0 || body.indexOf("book.") >= 0
                    || body.indexOf("baseUrl") >= 0 || body.indexOf("result") >= 0
                    || body.indexOf("cookie") >= 0 || body.indexOf("cache") >= 0) {
                return null;   // these variables are provided
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

        // JSONPath: only the common forms are implemented.
        if (r.startsWith("$")) {
            if (r.indexOf("?(") >= 0) {
                return "JSONPath filter expression not implemented";
            }
            if (r.indexOf("..") >= 0 && r.indexOf("..") == r.indexOf("..")) {
                // ".." recursive descent: supported for simple leaf names only
                String after = r.substring(r.indexOf("..") + 2);
                if (after.indexOf('.') >= 0 || after.indexOf('[') >= 0) {
                    return "JSONPath recursive descent supports a bare leaf name only";
                }
            }
            if (r.indexOf("length()") >= 0) {
                return "JSONPath function not implemented";
            }
            return null;
        }

        // CSS: the selector engine handles tag/class/id/attribute and simple combinators.
        if (r.startsWith("class.") || r.startsWith("id.") || r.startsWith("tag.")
                || r.startsWith(".") || r.startsWith("#")) {
            if (r.indexOf(":eq(") >= 0 || r.indexOf(":gt(") >= 0 || r.indexOf(":lt(") >= 0) {
                return "CSS index pseudo-class not implemented";
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

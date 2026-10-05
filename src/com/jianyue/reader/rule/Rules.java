package com.jianyue.reader.rule;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import com.jianyue.reader.util.Paragraphs;

/**
 * The book-source rule engine.
 *
 * A rule string can be any of these, and the engine picks by syntax:
 *
 *   class.book-info--title@text      CSS selector (+ extraction suffix)
 *   id.Jcontent@html                 CSS by id
 *   $.data.datas.list                JSONPath
 *   name                             bare JSON field (used inside a list context)
 *   //div[@id='x']                   XPath (against the parsed DOM)
 *   @js:result.replace(...)          JavaScript (Rhino, already ported to API 1)
 *
 * followed optionally by a replacement:   ##regex##replacement
 *
 * Measured facts this leans on (see API1-能力实测记录.md): org.json works on API 1,
 * java.util.regex works, and Rhino was rebuilt against the API-1 android.jar so it now
 * loads and executes.
 */
public final class Rules {

    private static final String TAG = "JianYue";

    /** Replacement delimiters, longest first so "##" wins over "#". */
    private static final String[] REPLACE_SEPS = {"##", "#@#", "#&#", "$$"};

    private Rules() {
    }

    /** Where a rule is being applied, which disambiguates bare names. */
    public static final int CTX_JSON = 1;
    public static final int CTX_HTML = 2;

    // ------------------------------------------------------------ entry point

    /**
     * Resolve one rule against one source node.
     *
     * @param node   JSONObject/JSONArray for JSON context, Html.El for HTML context
     * @param rule   the rule string
     * @param ctx    CTX_JSON or CTX_HTML
     * @param baseUrl used to absolutise links and available to JS as `baseUrl`
     * @return resolved string ("" when nothing matched)
     */
    public static String getString(Object node, String rule, int ctx, String baseUrl) {
        if (rule == null) {
            return "";
        }
        String r = rule.trim();
        if (r.length() == 0) {
            return "";
        }

        // "$1".."$99": a reference to a capture group of the regex that produced the
        // enclosing list. See regexGroups()/getNodes() for the list side. This has to be
        // checked before anything else, otherwise "$1" would be routed to JSONPath (it
        // starts with '$') and quietly return nothing - which is exactly the shape of the
        // bug where a regex-based 目录 came back empty.
        int grp = groupRef(r);
        if (grp >= 0) {
            List<String> groups = (node instanceof List) ? regexGroups(node) : null;
            if (groups != null) {
                return (grp < groups.size()) ? nz(groups.get(grp)) : "";
            }
            // No group list in hand: fall through so a literal "$1" in a URL template is
            // still produced rather than lost.
        }

        // Split off an optional "@put:name" tail FIRST.
        //
        // It has to go before the replace tail and before evaluation, because otherwise the
        // marker is treated as a value suffix of the selector: "$.book.name@put:who" became
        // the field lookup "$.book.name@put" (which matches nothing) instead of capturing
        // $.book.name into "who". That is why @get: returned empty.
        String putName = null;
        String r0 = r;
        int putAt = r.indexOf("@put:");
        if (putAt > 0) {
            String tail = r.substring(putAt + 5).trim();
            int sp = tail.indexOf(' ');
            putName = (sp > 0) ? tail.substring(0, sp) : tail;
            r0 = r.substring(0, putAt).trim();
            if (r0.length() == 0) {
                return "";
            }
        }

        // Split off an optional "##pattern##replacement" tail.
        // The delimiter must be searched from index 1: a rule that STARTS with "##"
        // would otherwise treat that leading delimiter as the split point and silently
        // mangle the pattern (this exact bug stripped tags incorrectly).
        String replacement = null;
        String body = r0;
        String pattern = null;
        for (int i = 0; i < REPLACE_SEPS.length; i++) {
            String sep = REPLACE_SEPS[i];
            int idx = r0.indexOf(sep, 1);
            if (idx > 0) {
                body = r0.substring(0, idx);
                String rest = r0.substring(idx + sep.length());
                int idx2 = rest.indexOf(sep);
                if (idx2 >= 0) {
                    pattern = rest.substring(0, idx2);
                    replacement = rest.substring(idx2 + sep.length());
                } else {
                    pattern = rest;
                    replacement = null;
                }
                break;
            }
        }

        String out = evalCombinators(node, body.trim(), ctx, baseUrl);

        // A rule that merely CONTAINS "$1" (a URL template such as
        // "http://a.heiyan.com/ajax/chapter/content/$1") also has to read the capture groups
        // of the enclosing regex list. A rule that IS "$1" was already handled above.
        if (grp < 0 && out != null && out.indexOf('$') >= 0 && node instanceof List) {
            List<String> groups = regexGroups(node);
            if (groups != null) {
                out = substituteRegexGroups(out, groups);
            }
        }

        if (pattern != null && pattern.length() > 0) {
            out = applyReplace(out, pattern, replacement);
        }
        if (putName != null && putName.length() > 0) {
            PUT_VARS.put(putName, out);
        }
        return out == null ? "" : out;
    }

    // ---------------------------------------------------- rule combinators

    /**
     * 阅读's rule combinators, applied before any single rule is evaluated:
     *
     *   a && b     run a then b against the ORIGINAL node, concatenate the results
     *              (used to build strings such as "作者：{author}")
     *   a || b     first operand that yields something wins (used for fallback selectors)
     *   a %% b     take the LIST from a and join it with b (used for multi-author fields)
     *   {{expr}}   inline JavaScript, evaluated and substituted into the rule string
     *   @get:x     take the value of a previously put() variable
     *
     * These are a real part of the format and their absence silently produced empty fields,
     * so they are handled here rather than left to the single-rule evaluator.
     *
     * Splitting is done at depth 0 only, so separators inside brackets, parentheses or
     * quotes (JSONPath filters, XPath predicates, JS strings) are left alone.
     */
    private static String evalCombinators(Object node, String rule, int ctx, String baseUrl) {
        if (rule == null || rule.length() == 0) {
            return "";
        }

        // "||" binds loosest: try each alternative until one produces something.
        List<String> alts = splitTopLevel(rule, "||");
        if (alts.size() > 1) {
            for (int i = 0; i < alts.size(); i++) {
                String v = evalCombinators(node, alts.get(i), ctx, baseUrl);
                if (v != null && v.trim().length() > 0) {
                    return v;
                }
            }
            return "";
        }

        // "%%" joins a list with a separator.
        //
        // The right-hand side is usually a LITERAL separator ("/", "、", ", ") rather than a
        // rule. It has to be treated as literal text, because a bare "/" would otherwise be
        // routed to the XPath branch, which returns "" for a JSON node and silently drops
        // the separator ("玄幻热血" instead of "玄幻/热血").
        List<String> join = splitTopLevel(rule, "%%");
        if (join.size() > 1) {
            String sep = separatorFor(node, nz(join.get(1)).trim(), ctx, baseUrl);
            List<String> parts = getStringList(node, join.get(0), ctx, baseUrl);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) {
                    sb.append(sep);
                }
                sb.append(parts.get(i));
            }
            return sb.toString();
        }

        // "&&" concatenates the results of several rules run against the same node.
        List<String> ands = splitTopLevel(rule, "&&");
        if (ands.size() > 1) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ands.size(); i++) {
                String v = evalCombinators(node, ands.get(i), ctx, baseUrl);
                if (v != null) {
                    sb.append(v);
                }
            }
            return sb.toString();
        }

        // "{{ expr }}" inline JavaScript, substituted into the string.
        //
        // @put: is handled here too, because in "{{...}}@put:name" the marker is a tail on
        // an expression rather than a value suffix of a selector.
        if (rule.indexOf("{{") >= 0) {
            String putName = null;
            int putAt = rule.indexOf("@put:");
            String expr = rule;
            if (putAt > 0) {
                putName = rule.substring(putAt + 5).trim();
                expr = rule.substring(0, putAt);
                int sp = putName.indexOf(' ');
                if (sp > 0) {
                    putName = putName.substring(0, sp);
                }
            }
            String v = evalInlineJs(node, expr, ctx, baseUrl);
            if (putName != null && putName.length() > 0) {
                PUT_VARS.put(putName, v);
            }
            return v;
        }

        // "@get:name" reads a value captured by a previous "@put:name".
        if (rule.startsWith("@get:")) {
            String v = PUT_VARS.get(rule.substring(5).trim());
            return v == null ? "" : v;
        }

        String out = evaluate(node, rule, ctx, baseUrl);
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------- regex rule mode

    /**
     * 阅读's "regex" rule mode, the one form of chapterList this engine did not implement.
     *
     * A rule that starts with ":" is not a selector at all: the rest is a java.util.regex
     * pattern run against the WHOLE body, and every match becomes one list element whose
     * capture groups are then referenced as "$1", "$2", ... That is how a source such as
     * 黑岩小说① writes its 目录:
     *
     *   chapterList = :(?s)(\d+)" class="(isvip)?[^"]*name[^>]*>([^<]*)
     *   chapterName = $3
     *   chapterUrl  = https://a.heiyan.com/ajax/chapter/content/$1
     *
     * Without this the ":" form fell into the CSS branch, matched nothing, and the 目录 came
     * back empty while搜索 still worked - the exact symptom "搜索能出结果但目录为空".
     */
    public static boolean isRegexRule(String rule) {
        if (rule == null) {
            return false;
        }
        String r = rule.trim();
        return r.length() > 1 && r.charAt(0) == ':';
    }

    /** Parse "$N" (1..99) into N, or -1 when the string is not a bare group reference. */
    private static int groupRef(String r) {
        if (r == null || r.length() < 2 || r.charAt(0) != '$') {
            return -1;
        }
        for (int i = 1; i < r.length(); i++) {
            if (!Character.isDigit(r.charAt(i))) {
                return -1;
            }
        }
        try {
            return Integer.parseInt(r.substring(1));
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Run a ":"-prefixed regex against a node's text and return one List<String> per match
     * (index 0 = the whole match, 1..n = the capture groups), mirroring AnalyzeByRegex.
     */
    public static List<List<String>> regexMatches(Object node, String rule) {
        List<List<String>> out = new ArrayList<List<String>>();
        String body = rawText(node);
        if (body.length() == 0) {
            return out;
        }
        String r = rule.trim();
        if (r.startsWith(":")) {
            r = r.substring(1);
        }
        // "##" at the tail is 阅读's replace syntax, not part of the pattern
        int rep = r.indexOf("##", 1);
        if (rep > 0) {
            r = r.substring(0, rep);
        }
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(r,
                    java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher m = p.matcher(body);
            int guard = 0;
            while (m.find() && guard < 5000) {
                guard++;
                List<String> groups = new ArrayList<String>();
                for (int g = 0; g <= m.groupCount(); g++) {
                    String v = m.group(g);
                    groups.add(v == null ? "" : v);
                }
                out.add(groups);
            }
        } catch (Throwable t) {
            Log.i(TAG, "rule: bad regex rule [" + r + "] " + t.getClass().getName()
                    + ": " + t.getMessage());
        }
        return out;
    }

    /** Raw text of a node, used as the haystack for a ":"-regex rule. */
    private static String rawText(Object node) {
        if (node == null) {
            return "";
        }
        if (node instanceof Html.El) {
            return ((Html.El) node).outerHtml();
        }
        return String.valueOf(node);
    }

    /** Read a List<String> capture-group row out of an opaque node. */
    @SuppressWarnings("unchecked")
    private static List<String> regexGroups(Object node) {
        if (!(node instanceof List)) {
            return null;
        }
        List<Object> l = (List<Object>) node;
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < l.size(); i++) {
            Object o = l.get(i);
            if (o instanceof List) {
                // a list of matches: a group reference without an index is ambiguous, take
                // the first row so the rule degrades to something usable
                return regexGroups(o);
            }
            out.add(o == null ? "" : String.valueOf(o));
        }
        return out;
    }

    /**
     * Replace every "$N" in a rule's output with capture group N of the enclosing regex
     * match. This is what makes 阅读's
     *   chapterUrl = https://a.heiyan.com/ajax/chapter/content/$1
     * work: the "$1" is a group reference, not literal text.
     */
    public static String substituteRegexGroups(String s, List<String> groups) {
        if (s == null || s.length() == 0 || groups == null || s.indexOf('$') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '$' && i + 1 < n && Character.isDigit(s.charAt(i + 1))) {
                int j = i + 1;
                while (j < n && Character.isDigit(s.charAt(j)) && j - i <= 2) {
                    j++;
                }
                int idx;
                try {
                    idx = Integer.parseInt(s.substring(i + 1, j));
                } catch (Throwable t) {
                    idx = -1;
                }
                if (idx >= 0 && idx < groups.size()) {
                    sb.append(nz(groups.get(idx)));
                    i = j;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /**
     * Resolve the right-hand side of "%%" into a separator string.
     *
     * It is literal text in the overwhelmingly common case ("/", "、", ", ", " - "), and a
     * rule only in the rarer case where it starts with a recognised selector prefix. Deciding
     * by syntax matters: "%%/" must produce "/", not an empty XPath result.
     */
    private static String separatorFor(Object node, String rhs, int ctx, String baseUrl) {
        if (rhs.length() == 0) {
            return "";
        }
        if (rhs.startsWith("{{")) {
            return nz(evalInlineJs(node, rhs, ctx, baseUrl));
        }
        boolean looksLikeRule =
                rhs.startsWith("$") || rhs.startsWith("@") || rhs.startsWith("/")
                        || rhs.startsWith("class.") || rhs.startsWith("id.")
                        || rhs.startsWith("tag.") || rhs.startsWith("//");
        if (!looksLikeRule) {
            return rhs;
        }
        // A bare "/" is far more likely to be a separator than an XPath expression.
        if (rhs.length() == 1 && !Character.isLetterOrDigit(rhs.charAt(0))) {
            return rhs;
        }
        String v = evaluate(node, rhs, ctx, baseUrl);
        // If the rule produced nothing, fall back to the literal text rather than dropping it.
        return (v == null || v.length() == 0) ? rhs : v;
    }

    /**
     * Values captured by "@put:name" and read back by "@get:name".
     *
     * 阅读 keeps these on the rule object, so they live for one analysis pass. This is a
     * static map, which is safe here because analysis is single-threaded per source and the
     * map is cleared at the start of each search/detail pass by Engine.
     */
    private static final java.util.Map<String, String> PUT_VARS =
            new java.util.HashMap<String, String>();

    /** Clear @put/@get variables. Call once per analysis pass. */
    public static void resetPutVars() {
        PUT_VARS.clear();
    }

    /**
     * Substitute "{{ expr }}" with the result of running expr as JavaScript.
     *
     * Non-expression text around the placeholders is kept verbatim, so
     * "作者：{{book.author}}" works as expected.
     */
    private static String evalInlineJs(Object node, String rule, int ctx, String baseUrl) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = rule.length();
        while (i < n) {
            int open = rule.indexOf("{{", i);
            if (open < 0) {
                out.append(rule.substring(i));
                break;
            }
            out.append(rule.substring(i, open));
            int close = rule.indexOf("}}", open + 2);
            if (close < 0) {
                // unbalanced: treat the rest as literal rather than dropping it
                out.append(rule.substring(open));
                break;
            }
            String expr = rule.substring(open + 2, close).trim();
            String val;
            if (expr.length() == 0) {
                val = "";
            } else if (expr.startsWith("@@")) {
                // "@@expr" is the documented way to use a nested rule inside {{ }}
                val = evalCombinators(node, expr.substring(2).trim(), ctx, baseUrl);
            } else {
                val = Js.run(expr, currentValue(node), baseUrl, null);
            }
            out.append(val == null ? "" : val);
            i = close + 2;
        }
        return out.toString();
    }

    /** Best-effort current value for JS `result`, so {{result}} style rules work. */
    private static String currentValue(Object node) {
        if (node instanceof Html.El) {
            return Html.collapse(((Html.El) node).allText());
        }
        if (node == null) {
            return "";
        }
        return String.valueOf(node);
    }

    /**
     * Split on a separator, but only where it sits outside brackets, parentheses and quotes.
     *
     * Without the depth check, a JSONPath filter such as $.a[?(@.x=='||')] or an XPath
     * predicate would be split in the middle and produce nonsense.
     */
    public static List<String> splitTopLevel(String s, String sep) {
        List<String> out = new ArrayList<String>();
        if (s == null || s.length() == 0) {
            out.add("");
            return out;
        }
        int depth = 0;
        char quote = 0;
        int start = 0;
        int i = 0;
        int n = s.length();
        int sl = sep.length();
        while (i < n) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == quote) {
                    quote = 0;
                }
                i++;
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                i++;
                continue;
            }
            if (c == '[' || c == '(') {
                depth++;
            } else if (c == ']' || c == ')') {
                if (depth > 0) {
                    depth--;
                }
            } else if (depth == 0 && c == sep.charAt(0)) {
                if (sl == 1 || s.regionMatches(i, sep, 0, sl)) {
                    out.add(s.substring(start, i));
                    i += sl;
                    start = i;
                    continue;
                }
            }
            i++;
        }
        out.add(s.substring(start));
        return out;
    }

    /**
     * Resolve a rule to a list (used for chapter lists and search rows).
     *
     * The rule STYLE is decided by syntax, never by ctx. Mixing ctx in here caused a
     * real bug: "class.chapter-list@tag.a" with ctx=JSON was routed to JSONPath and
     * silently returned nothing. ctx only decides what a BARE name means.
     */
    public static List<String> getStringList(Object node, String rule, int ctx, String baseUrl) {
        List<String> out = new ArrayList<String>();
        if (rule == null || rule.trim().length() == 0) {
            return out;
        }
        String r = stripReplace(rule.trim());

        // ":"-prefixed regex rule: one element per match, capture groups carried along.
        // (getStringList() is a flat-string view; getNodes() keeps the groups.)
        if (isRegexRule(r)) {
            List<List<String>> ms = regexMatches(node, r);
            for (int i = 0; i < ms.size(); i++) {
                out.add(String.valueOf(ms.get(i)));
            }
            return out;
        }

        if (isJsonStyle(r, node, ctx)) {
            List<Object> hits = JsonPath.eval(node, r);
            for (int i = 0; i < hits.size(); i++) {
                Object o = hits.get(i);
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
            return out;
        }

        // HTML: select then extract
        if (node instanceof Html.El) {
            String sel = r;
            String suffix = null;
            int at = sel.lastIndexOf('@');
            if (at > 0 && !sel.startsWith("@js:")) {
                suffix = sel.substring(at + 1).trim();
                sel = sel.substring(0, at).trim();
            }
            if (sel.startsWith("class.")) {
                sel = "." + sel.substring(6);
            } else if (sel.startsWith("tag.")) {
                sel = sel.substring(4);
            }
            List<Html.El> els = Html.select((Html.El) node, sel);
            for (int i = 0; i < els.size(); i++) {
                String v = (suffix == null) ? Html.collapse(els.get(i).allText())
                        : Html.extract(els.get(i), suffix);
                out.add(v == null ? "" : v);
            }
        }
        return out;
    }

    /**
     * Decide whether a rule should be evaluated as JSON or as HTML.
     *
     * Explicit syntax wins outright. Only a bare name falls back to ctx.
     */
    private static boolean isJsonStyle(String rule, Object node, int ctx) {
        if (rule == null || rule.length() == 0) {
            return false;
        }
        if (rule.charAt(0) == '$') {
            return true;
        }
        if (rule.startsWith("class.") || rule.startsWith("id.")
                || rule.startsWith("tag.") || rule.startsWith("//")
                || rule.startsWith("@js:")) {
            return false;
        }
        if (rule.charAt(0) == '.' || rule.charAt(0) == '#') {
            return false;
        }
        // a bare name: JSON when the node is JSON, HTML when it is an element
        if (node instanceof Html.El) {
            return false;
        }
        return node instanceof JSONObject || node instanceof JSONArray || ctx == CTX_JSON;
    }

    /**
     * Resolve a chapterList rule to the actual element nodes (not strings), so each
     * element can then be queried for name/url separately.
     */
    public static List<Object> getNodes(Object node, String rule, int ctx) {
        List<Object> out = new ArrayList<Object>();
        if (rule == null || rule.trim().length() == 0) {
            return out;
        }
        String r = stripReplace(rule.trim());

        // ":"-prefixed regex rule: one node per match; each node is the capture-group row
        // that "$1"/"$2"/... rules then read (see getString()).
        if (isRegexRule(r)) {
            List<List<String>> ms = regexMatches(node, r);
            for (int i = 0; i < ms.size(); i++) {
                out.add(ms.get(i));
            }
            return out;
        }

        if (isJsonStyle(r, node, ctx)) {
            List<Object> hits = JsonPath.eval(node, r);
            for (int i = 0; i < hits.size(); i++) {
                Object o = hits.get(i);
                if (o instanceof JSONArray) {
                    JSONArray a = (JSONArray) o;
                    for (int k = 0; k < a.length(); k++) {
                        Object e = a.opt(k);
                        if (e != null && e != JSONObject.NULL) {
                            out.add(e);
                        }
                    }
                } else if (o != null && o != JSONObject.NULL) {
                    out.add(o);
                }
            }
            return out;
        }

        if (node instanceof Html.El) {
            // "class.chapter-list@tag.a"  -> the <a> children of each container
            // "class.listmain@dd"         -> the <dd> descendants of the container
            // "class.listmain@dd!0:1:2"   -> the same, minus indices 0, 1 and 2
            //
            // The "!N:N:N" exclusion list is how 阅读 skips a duplicated "latest chapters"
            // block: without it a source such as 鬼吹灯 returns its newest 12 chapters twice
            // at the head of the list. This was previously ignored entirely, so the whole
            // suffix "@dd!0:1:..." was treated as a tag name and matched nothing - the
            // chapter list came back empty.
            String sel = r;
            String joinTag = null;
            int joinIndex = -9999;
            List<Integer> exclude = null;
            int at = sel.lastIndexOf('@');
            if (at > 0 && !sel.startsWith("@js:")) {
                String suffix = sel.substring(at + 1).trim();
                sel = sel.substring(0, at).trim();
                int bang = suffix.indexOf('!');
                if (bang >= 0) {
                    exclude = parseIndexList(suffix.substring(bang + 1));
                    suffix = suffix.substring(0, bang).trim();
                }
                if (suffix.startsWith("tag.")) {
                    suffix = suffix.substring(4).trim();
                } else if (suffix.startsWith("tag")) {
                    suffix = suffix.substring(3).trim();
                }
                // "tag.a.1" / "tag.span.-1": pick one match out of the join set.
                int[] idxBox = new int[1];
                String noIdx = takeIndexSuffix(suffix, idxBox);
                if (idxBox[0] != -9999) {
                    suffix = noIdx;
                    joinIndex = idxBox[0];
                }
                if (suffix.length() > 0) {
                    joinTag = suffix;
                }
            }
            if (sel.startsWith("class.")) {
                sel = "." + sel.substring(6);
            } else if (sel.startsWith("tag.")) {
                sel = sel.substring(4);
            }
            List<Html.El> containers = Html.select((Html.El) node, sel);
            if (containers.size() == 0) {
                return out;
            }
            for (int i = 0; i < containers.size(); i++) {
                Html.El c = containers.get(i);
                if (joinTag == null) {
                    out.add(c);
                    continue;
                }
                // "class.chapter-list@tag.a" means: an <a> inside each container.
                // A selector matches anywhere in the subtree, so if it already matched
                // the <a> elements we must not descend again - and if it matched the
                // container we must. Prefer children, fall back to the element itself.
                List<Html.El> kids = Html.select(c, joinTag);
                if (kids.size() > 0) {
                    if (joinIndex != -9999) {
                        int pick = (joinIndex < 0) ? (kids.size() + joinIndex) : joinIndex;
                        if (pick >= 0 && pick < kids.size()) {
                            out.add(kids.get(pick));
                        }
                    } else {
                        for (int k = 0; k < kids.size(); k++) {
                            out.add(kids.get(k));
                        }
                    }
                } else if (c.tag != null && c.tag.equalsIgnoreCase(joinTag)) {
                    out.add(c);
                }
            }
            if (exclude != null && exclude.size() > 0) {
                List<Object> kept = new ArrayList<Object>();
                for (int i = 0; i < out.size(); i++) {
                    if (!exclude.contains(Integer.valueOf(i))) {
                        kept.add(out.get(i));
                    }
                }
                return kept;
            }
        }
        return out;
    }

    /**
     * Parse a 阅读 index list such as "0:1:2:3" into integers.
     *
     * Returns an empty list when nothing parses, so a malformed list excludes nothing rather
     * than everything.
     */
    private static List<Integer> parseIndexList(String spec) {
        List<Integer> out = new ArrayList<Integer>();
        if (spec == null) {
            return out;
        }
        String[] parts = spec.trim().split(":");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.length() == 0) {
                continue;
            }
            try {
                out.add(Integer.valueOf(Integer.parseInt(p)));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    // ------------------------------------------------------------- dispatch

    private static String evaluate(Object node, String rule, int ctx, String baseUrl) {
        if (rule == null || rule.length() == 0) {
            return "";
        }

        // 阅读 embeds JavaScript in the MIDDLE of a rule:
        //   $.chapter.htmlContent<js>result.replace(/<.*?>/g,'')</js>
        // meaning "take this field, then run this script on it". This must be handled FIRST,
        // before any selector branch, because the rule *starts* with a selector. Missing it
        // meant Rhino was never even called: the JSONPath lookup returned the raw HTML and
        // the whole chapter came back as the 5-character word "false" (Rhino's evaluation of
        // the leftover "(x < js) > (... < /js)" text) - or, worse, as nothing at all.
        int jsOpen = rule.indexOf("<js>");
        if (jsOpen > 0) {
            int jsClose = rule.indexOf("</js>", jsOpen + 4);
            String prefix = rule.substring(0, jsOpen).trim();
            String script = (jsClose > 0)
                    ? rule.substring(jsOpen + 4, jsClose)
                    : rule.substring(jsOpen + 4);
            String seed = (prefix.length() > 0) ? getString(node, prefix, ctx, baseUrl)
                    : currentValue(node);
            String jsOut = Js.run(script, seed, baseUrl, null);
            if (jsOut == null) {
                jsOut = "";
            }
            // Kept deliberately: when a content rule comes back empty, this one line says
            // whether the SEED was empty (a selector/JSONPath problem) or the script ran and
            // produced nothing (a JS problem). Chasing 榛戝博's empty chapters without it was
            // pure guesswork.
            Log.i(TAG, "rule: <js> seed=" + (seed == null ? 0 : seed.length())
                    + " -> out=" + jsOut.length());
            if (jsClose > 0) {
                String tail = rule.substring(jsClose + 5).trim();
                if (tail.length() > 0) {
                    jsOut = jsOut + getString(node, tail, ctx, baseUrl);
                }
            }
            if (jsOut == null) {
                jsOut = "";
            }
            return jsOut;
        }

        // Explicit mode prefixes. 阅读 uses these to remove the ambiguity of a bare rule;
        // without them a rule such as "@CSS:.bookname@text" was parsed as neither CSS nor
        // JSONPath and quietly returned nothing.
        //
        // NOTE: "@" must be checked before the JSONPath branch, and "@@" (escape, meaning
        // "treat the rest as a plain rule") before everything else.
        if (rule.startsWith("@@")) {
            return evaluate(node, rule.substring(2).trim(), ctx, baseUrl);
        }
        String lower = rule.toLowerCase();
        if (lower.startsWith("@css:")) {
            return viaCss(node, applyCssAliases(rule.substring(5).trim()));
        }
        if (lower.startsWith("@xpath:")) {
            return viaXPath(node, rule.substring(7).trim());
        }
        if (lower.startsWith("@json:")) {
            if (node == null) {
                return "";
            }
            return JsonPath.firstString(node, rule.substring(6).trim());
        }

        // 阅读 embeds JavaScript in the MIDDLE of a rule:
        //   $.chapter.htmlContent<js>result.replace(/<.*?>/g,'')</js>
        // (handled at the top of evaluate(), before any selector branch)

        // JavaScript
        if (rule.startsWith("@js:")) {
            return Js.run(rule.substring(4), currentValue(node), baseUrl, null);
        }
        if (rule.startsWith("<js>") || rule.startsWith("{{js")) {
            return Js.run(rule, currentValue(node), baseUrl, null);
        }
        // XPath
        if (rule.startsWith("//") || rule.startsWith("/")) {
            return viaXPath(node, rule);
        }
        // CSS selector
        if (rule.startsWith("class.") || rule.startsWith("id.")
                || rule.startsWith("tag.") || rule.startsWith(".")
                || rule.startsWith("#")) {
            return viaCss(node, rule);
        }
        // JSONPath
        if (rule.startsWith("$")) {
            if (node == null) {
                return "";
            }
            return JsonPath.firstString(node, rule);
        }
        // A capture-group row produced by a ":"-regex chapterList. Anything that reaches here
        // is not a selector at all (no class./id./tag.//path/$path prefix), so the rule text
        // is returned as-is; rules that CONTAIN a "$N" placeholder (a URL template such as
        // ".../content/$1") are substituted in getString() before this point.
        if (node instanceof List) {
            return rule;
        }
        // bare name: JSON field when the node is JSON, CSS tag when it is HTML
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            if (o.has(rule)) {
                Object v = o.opt(rule);
                if (v == null || v == JSONObject.NULL) {
                    return "";
                }
                if (v instanceof JSONObject || v instanceof JSONArray) {
                    return String.valueOf(v);
                }
                return String.valueOf(v);
            }
            return "";
        }
        if (node instanceof Html.El) {
            // on an element, a bare name is often the extraction the caller wants
            String v = Html.extract((Html.El) node, rule);
            if (v != null) {
                return v;
            }
            return Html.collapse(((Html.El) node).allText());
        }
        // Unmatched rule on a JSON node.
        //
        // Returning the rule text itself (rather than "") is what makes a literal separator
        // usable: in "a%%/", the "/" is not a selector at all, it is the join string. It
        // also matches the reference behaviour, where a rule that resolves to nothing is
        // treated as a literal.
        return rule;
    }

    /**
     * Translate 阅读's CSS shorthands into real selectors:
     *   class.x -> .x      id.x -> #x      tag.x -> x
     *
     * The "id." case is easy to forget. Without it "id.Jcontent" was treated as a tag name
     * and silently matched nothing, which produced empty chapters - a bug that cost real
     * debugging time.
     */
    private static String applyCssAliases(String sel) {
        if (sel == null) {
            return "";
        }
        if (sel.startsWith("class.")) {
            return "." + sel.substring(6);
        }
        if (sel.startsWith("id.")) {
            return "#" + sel.substring(3);
        }
        if (sel.startsWith("tag.")) {
            return sel.substring(4);
        }
        return sel;
    }

    private static String viaCss(Object node, String rule) {
        if (!(node instanceof Html.El)) {
            return "";
        }
        String sel = rule;
        String suffix = null;
        int at = sel.lastIndexOf('@');
        if (at > 0) {
            suffix = sel.substring(at + 1).trim();
            sel = sel.substring(0, at).trim();
        }
        // class.x -> .x ; id.x -> #x ; tag.x -> x
        // NOTE: the id. case is easy to forget. Without it "id.Jcontent" was treated as
        // a tag name and silently matched nothing, which produced empty chapters.
        if (sel.startsWith("class.")) {
            sel = "." + sel.substring(6);
        } else if (sel.startsWith("id.")) {
            sel = "#" + sel.substring(3);
        } else if (sel.startsWith("tag.")) {
            sel = sel.substring(4);
        }
        if (sel.startsWith("@")) {
            sel = sel.substring(1);
        }
        // 阅读 uses "@" both to separate selector steps ("class.pages@tag.a") and to introduce
        // the attribute suffix. The suffix has already been split off above, so any remaining
        // "@" is a step separator; "tag." prefixes can appear on any step, not just the first.
        if (sel.indexOf('@') >= 0) {
            sel = sel.replace('@', ' ');
        }
        if (sel.indexOf("tag.") >= 0) {
            sel = sel.replace("tag.", "");
        }
        Html.El scope = (Html.El) node;
        int[] box = new int[1];
        String bare = takeIndexSuffix(sel, box);
        if (box[0] != -9999) {
            // an explicit index ("class.pages@tag.a.-1") means exactly one element
            Html.El e = selectByIndex(scope, sel);
            if (e == null) {
                return "";
            }
            if (suffix == null) {
                return Html.collapse(e.allText());
            }
            String one = Html.extract(e, suffix);
            return one == null ? "" : one;
        }

        // No explicit index: JOIN every match with "\n".
        //
        // This is 阅读's semantics -- AnalyzeByJSoup.getString() is
        // getStringList().joinToString("\n") -- and taking only the first match instead
        // silently truncated whole chapters. 阅友小说 splits one chapter across five
        // .con blocks (one per page section), so `content: .con@html` returned the first
        // fifth and the reader stopped at "（本章未完，请翻页）"; the chapter looked like it
        // loaded, which is why it survived several rounds of debugging.
        List<Html.El> all = Html.select(scope, bare);
        if (all.size() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.size(); i++) {
            String v = (suffix == null) ? Html.collapse(all.get(i).allText())
                    : Html.extract(all.get(i), suffix);
            if (v == null || v.length() == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(v);
        }
        return sb.toString();
    }

    /**
     * Peel a trailing numeric index off a selector step: "a.1" -> "a" (index 1),
     * "span.-1" -> "span" (index -1). Sets box[0] to -9999 when there is no index.
     */
    private static String takeIndexSuffix(String s, int[] box) {
        box[0] = -9999;
        if (s == null || s.length() == 0) {
            return "";
        }
        int dot = s.lastIndexOf('.');
        if (dot <= 0 || dot >= s.length() - 1) {
            return s;
        }
        String tail = s.substring(dot + 1);
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (!Character.isDigit(c) && !(i == 0 && c == '-')) {
                return s;
            }
        }
        try {
            box[0] = Integer.parseInt(tail);
        } catch (Throwable t) {
            return s;
        }
        return s.substring(0, dot);
    }

    /**
     * CSS select with an optional trailing index: "class.pages@tag.a.-1" (last match) or
     * "class.face-info@tag.span.0" (first).
     *
     * 阅读 writes these constantly, and without support the whole selector silently matches
     * nothing - the failure mode is an empty field rather than an error, which is exactly the
     * kind of bug this project has been chasing.
     */
    private static Html.El selectByIndex(Html.El scope, String sel) {
        if (scope == null || sel == null) {
            return null;
        }
        String s = sel.trim();
        int[] box = new int[1];
        s = takeIndexSuffix(s, box);
        int idx = box[0];
        List<Html.El> all = Html.select(scope, s);
        if (all.size() == 0) {
            return null;
        }
        if (idx == -9999) {
            return all.get(0);
        }
        int pick = (idx < 0) ? (all.size() + idx) : idx;
        if (pick < 0 || pick >= all.size()) {
            return null;
        }
        return all.get(pick);
    }

    private static String viaXPath(Object node, String rule) {
        if (!(node instanceof Html.El)) {
            return "";
        }
        String xp = rule;
        String suffix = null;
        int at = xp.lastIndexOf('@');
        if (at > 0 && xp.lastIndexOf('@') > xp.lastIndexOf('/')) {
            suffix = xp.substring(at + 1).trim();
            xp = xp.substring(0, at).trim();
        }
        Html.El e = XPathLite.first((Html.El) node, xp);
        if (e == null) {
            return "";
        }
        if (suffix == null) {
            return Html.collapse(e.allText());
        }
        String v = Html.extract(e, suffix);
        return v == null ? "" : v;
    }

    // ------------------------------------------------------------ regex part

    /** Remove the trailing ##regex##replacement part. */
    public static String stripReplace(String rule) {
        if (rule == null) {
            return "";
        }
        for (int i = 0; i < REPLACE_SEPS.length; i++) {
            // from index 1, for the same reason as in getString()
            int idx = rule.indexOf(REPLACE_SEPS[i], 1);
            if (idx > 0) {
                return rule.substring(0, idx);
            }
        }
        return rule;
    }

    /** Apply a java.util.regex replacement; $1..$9 refer to groups. */
    public static String applyReplace(String input, String pattern, String replacement) {
        if (input == null || input.length() == 0 || pattern == null || pattern.length() == 0) {
            return input;
        }
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern,
                    java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher m = p.matcher(input);
            String rep = (replacement == null) ? "" : replacement;
            return m.replaceAll(rep);
        } catch (Throwable t) {
            Log.i(TAG, "rule: bad regex [" + pattern + "] " + t.getClass().getName()
                    + ": " + t.getMessage());
            return input;
        }
    }

    /**
     * Block-level tags, which END a paragraph - 阅读's HtmlFormatter.wrapHtmlRegex
     * ("&lt;/?(?:div|p|br|hr|h\d|article|dd|dl)[^&gt;]*&gt;"). The lookahead only makes the match
     * stricter than 阅读's version: without it "&lt;p" also matches "&lt;pre&gt;" and "&lt;param&gt;".
     */
    private static final java.util.regex.Pattern BLOCK_TAG =
            java.util.regex.Pattern.compile(
                    "</?(?:div|p|br|hr|h[1-6]|article|dd|dl)(?=[\\s/>])[^>]*>",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Clean extracted novel text: 正文 -> plain text.
     *
     * <p>This is the project's ONLY html-to-text step (Engine.loadContent and Engine.clean both
     * come through here), so the paragraph structure decided here is the paragraph structure the
     * reader shows.
     *
     * <p>Block-level tags become LINE BREAKS before the generic tag strip below runs - 阅读's
     * semantic (BookContent.analyzeContent -> HtmlFormatter.formatKeepImg -> format). Deleting
     * every tag outright is what produced "正文没有分段": {@code <p>一</p><p>二</p>} came back as
     * "一二", and a chapter fetched with {@code @html} (纵横 id.Jcontent, 阅友 .con, 猫九
     * id.xs-content) arrived as one endless line.
     *
     * <p>Indentation is deliberately NOT added here: this same function cleans search-result
     * fields (book name, author), which must stay single-line. The 标题置顶 + 每段空两格 layout
     * lives in {@link com.jianyue.reader.util.Paragraphs}.
     */
    public static String cleanText(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace("\r\n", "\n").replace("\r", "\n");
        try {
            t = BLOCK_TAG.matcher(t).replaceAll("\n");
        } catch (Throwable ignore) {
            // a catastrophic regex failure must not lose the chapter
        }
        t = applyReplace(t, "<!--.*?-->", "");
        t = applyReplace(t, "<[^>]+>", "");
        t = Html.decodeEntities(t);
        StringBuilder sb = new StringBuilder(t.length());
        String[] lines = t.split("\n");
        for (int i = 0; i < lines.length; i++) {
            // '　' (U+3000) counts as blank too: sites that indent their own paragraphs would
            // otherwise produce a paragraph of nothing but ideographic spaces. The definition
            // lives in Paragraphs so the HTML cleaner and the page layout cannot disagree.
            String line = Paragraphs.trimBlank(lines[i].replace('\u00A0', ' '));
            if (line.length() == 0) {
                // collapse consecutive blanks
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                    sb.append('\n');
                }
            } else {
                sb.append(line).append('\n');
            }
        }
        return sb.toString().trim();
    }

    /** Turn a possibly-relative URL into an absolute one against a base. */
    public static String absUrl(String base, String url) {
        if (url == null || url.length() == 0) {
            return "";
        }
        // A value rule may have joined several matches with "\n" (see viaCss). A URL has to
        // be one line, so only the first non-empty line is used -- otherwise a selector that
        // happens to match twice would produce "url1\nurl2" and every request from it would
        // fail with a confusing socket error.
        String u = firstLine(url);
        if (u.length() == 0) {
            return "";
        }
        if (u.startsWith("http://") || u.startsWith("https://")) {
            return u;
        }
        if (u.startsWith("//")) {
            return "http:" + u;
        }
        if (base == null || base.length() == 0) {
            return u;
        }
        if (u.startsWith("/")) {
            // scheme + host from base
            int schemeEnd = base.indexOf("://");
            if (schemeEnd < 0) {
                return u;
            }
            int hostEnd = base.indexOf('/', schemeEnd + 3);
            String origin = (hostEnd < 0) ? base : base.substring(0, hostEnd);
            return origin + u;
        }
        int lastSlash = base.lastIndexOf('/');
        if (lastSlash < 0) {
            return u;
        }
        return base.substring(0, lastSlash + 1) + u;
    }

    /** The first non-empty line of a value (values may be multi-line after a joined rule). */
    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int start = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || s.charAt(i) == '\n' || s.charAt(i) == '\r') {
                if (i > start) {
                    String line = s.substring(start, i).trim();
                    if (line.length() > 0) {
                        return line;
                    }
                }
                start = i + 1;
            }
        }
        return s.trim();
    }
}

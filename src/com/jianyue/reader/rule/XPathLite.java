package com.jianyue.reader.rule;

import java.util.ArrayList;
import java.util.List;

/**
 * A very small XPath subset over the project's own HTML tree.
 *
 * API 1 does have org.w3c.dom and DocumentBuilderFactory, but feeding real novel-site
 * HTML to a strict XML parser fails often, and the tree here is already parsed. So only
 * the XPath shapes that book sources actually use are supported:
 *
 *   //div                      any div, anywhere
 *   //div[@id='x']             attribute equals
 *   //div[@class='x']          class equals
 *   //a[@href]                 attribute present
 *   //div[@id='x']/p           a direct child step
 *   /html/body/div             absolute path
 *   //div[1]                   1-based index (XPath convention)
 *
 * Anything else returns no match rather than throwing, so a rule that uses unsupported
 * XPath degrades to "empty" instead of breaking the whole book source.
 */
public final class XPathLite {

    private XPathLite() {
    }

    public static Html.El first(Html.El scope, String xpath) {
        List<Html.El> r = all(scope, xpath);
        return r.size() > 0 ? r.get(0) : null;
    }

    public static List<Html.El> all(Html.El scope, String xpath) {
        List<Html.El> out = new ArrayList<Html.El>();
        if (scope == null || xpath == null) {
            return out;
        }
        String xp = xpath.trim();
        if (xp.length() == 0) {
            return out;
        }

        // split into steps on '/', keeping the '//' marker
        List<String> steps = new ArrayList<String>();
        List<Boolean> deep = new ArrayList<Boolean>();
        int i = 0;
        boolean descendant = false;
        if (xp.startsWith("//")) {
            descendant = true;
            i = 2;
        } else if (xp.startsWith("/")) {
            descendant = false;
            i = 1;
        } else {
            descendant = true;   // a bare "div" is treated as //div
        }
        StringBuilder cur = new StringBuilder();
        while (i < xp.length()) {
            char c = xp.charAt(i);
            if (c == '/') {
                steps.add(cur.toString());
                deep.add(Boolean.valueOf(descendant));
                cur.setLength(0);
                descendant = false;
                if (i + 1 < xp.length() && xp.charAt(i + 1) == '/') {
                    descendant = true;
                    i++;
                }
                i++;
                continue;
            }
            cur.append(c);
            i++;
        }
        if (cur.length() > 0) {
            steps.add(cur.toString());
            deep.add(Boolean.valueOf(descendant));
        }

        List<Html.El> current = new ArrayList<Html.El>();
        current.add(scope);
        for (int s = 0; s < steps.size(); s++) {
            String step = steps.get(s).trim();
            if (step.length() == 0) {
                continue;
            }
            boolean isDeep = deep.get(s).booleanValue() || s == 0;
            List<Html.El> next = new ArrayList<Html.El>();
            for (int k = 0; k < current.size(); k++) {
                Html.El base = current.get(k);
                if (isDeep) {
                    List<Html.El> pool = new ArrayList<Html.El>();
                    collect(base, pool);
                    applyStep(pool, step, next);
                } else {
                    // direct children only
                    applyStep(base.children, step, next);
                }
            }
            current = next;
            if (current.isEmpty()) {
                break;
            }
        }
        out.addAll(current);
        return out;
    }

    private static void collect(Html.El e, List<Html.El> out) {
        for (int i = 0; i < e.children.size(); i++) {
            Html.El c = e.children.get(i);
            out.add(c);
            collect(c, out);
        }
    }

    /** Apply "div", "div[@id='x']", "div[1]" to a pool of candidates. */
    private static void applyStep(List<Html.El> pool, String step, List<Html.El> out) {
        String tag = step;
        String predicate = null;
        int br = step.indexOf('[');
        if (br >= 0) {
            int close = step.lastIndexOf(']');
            tag = step.substring(0, br).trim();
            predicate = (close > br) ? step.substring(br + 1, close).trim() : null;
        }
        boolean anyTag = tag.length() == 0 || tag.equals("*");
        List<Html.El> matched = new ArrayList<Html.El>();
        for (int i = 0; i < pool.size(); i++) {
            Html.El e = pool.get(i);
            if (!anyTag && !tag.equalsIgnoreCase(e.tag)) {
                continue;
            }
            if (predicate != null && !matchPredicate(e, predicate)) {
                continue;
            }
            matched.add(e);
        }
        if (predicate != null && predicate.length() > 0
                && Character.isDigit(predicate.charAt(0))) {
            // 1-based index
            try {
                int idx = Integer.parseInt(predicate);
                if (idx >= 1 && idx <= matched.size()) {
                    out.add(matched.get(idx - 1));
                }
                return;
            } catch (Throwable ignored) {
            }
        }
        out.addAll(matched);
    }

    private static boolean matchPredicate(Html.El e, String pred) {
        String p = pred.trim();
        if (p.length() == 0) {
            return true;
        }
        if (p.startsWith("@")) {
            String body = p.substring(1);
            int eq = body.indexOf('=');
            if (eq < 0) {
                return e.attr(body.trim()) != null;
            }
            String name = body.substring(0, eq).trim();
            String val = body.substring(eq + 1).trim();
            if (val.length() >= 2 && (val.charAt(0) == '\'' || val.charAt(0) == '"')
                    && val.charAt(val.length() - 1) == val.charAt(0)) {
                val = val.substring(1, val.length() - 1);
            }
            String v = e.attr(name);
            if (v == null) {
                return false;
            }
            if (name.equalsIgnoreCase("class")) {
                String[] parts = val.split("\\s+");
                for (int i = 0; i < parts.length; i++) {
                    if (parts[i].length() > 0 && !e.hasClass(parts[i])) {
                        return false;
                    }
                }
                return true;
            }
            return v.equals(val);
        }
        if (p.startsWith("contains(")) {
            int comma = p.indexOf(',');
            if (comma < 0) {
                return false;
            }
            String name = p.substring(9, comma).trim();
            if (name.startsWith("@")) {
                name = name.substring(1);
            }
            String val = p.substring(comma + 1, p.lastIndexOf(')')).trim();
            if (val.length() >= 2 && (val.charAt(0) == '\'' || val.charAt(0) == '"')) {
                val = val.substring(1, val.length() - 1);
            }
            String v = e.attr(name);
            return v != null && v.indexOf(val) >= 0;
        }
        if (p.startsWith("text()")) {
            return e.allText().indexOf(stripQuotes(p)) >= 0;
        }
        return true;
    }

    private static String stripQuotes(String s) {
        int eq = s.indexOf('=');
        if (eq < 0) {
            return "";
        }
        String v = s.substring(eq + 1).trim();
        if (v.length() >= 2 && (v.charAt(0) == '\'' || v.charAt(0) == '"')) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }
}

package com.jianyue.reader.rule;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Minimal JSONPath for book-source rules, over org.json.
 *
 * org.json exists at API 1 (verified on device) but json-path does not, and pulling a
 * library in would drag its own API requirements along, so this implements the subset
 * book sources actually use:
 *
 *   $.data.datas.list          member access
 *   $.data['datas']['list']    bracket member access
 *   $.data.datas.list[*]       wildcard over an array
 *   $.data.datas.list[0]       index
 *   $.data.datas.list[*].name  member access after a wildcard
 *   $.data..name               recursive descent
 *   $..name                    recursive descent from the root
 *
 * Evaluation returns a flat List of matches. A rule that names a list yields the
 * elements; a rule that names a scalar yields that scalar.
 */
public final class JsonPath {

    private JsonPath() {
    }

    /**
     * Evaluate a path. Accepts both "$.a.b" and a bare "a.b" (book sources write the
     * bare form inside a chapterList context).
     */
    public static List<Object> eval(Object root, String path) {
        List<Object> out = new ArrayList<Object>();
        if (root == null || path == null) {
            return out;
        }
        String p = path.trim();
        if (p.length() == 0) {
            out.add(root);
            return out;
        }
        if (p.startsWith("$")) {
            p = p.substring(1);
        }
        // a leading dot is optional ("$.a" vs "$a")
        if (p.startsWith(".") && !p.startsWith("..")) {
            p = p.substring(1);
        }

        List<Token> tokens = tokenize(p);
        List<Object> current = new ArrayList<Object>();
        current.add(root);
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            List<Object> next = new ArrayList<Object>();
            for (int k = 0; k < current.size(); k++) {
                applyToken(current.get(k), t, next, out);
            }
            current = next;
            if (current.isEmpty() && t.kind != Token.DESCEND) {
                break;
            }
        }
        // A path ending exactly on an array means "the elements of that array", which is
        // what book sources expect from "$.data.datas.list". Returning the JSONArray
        // itself makes callers cast-fail, so expand it here.
        for (int i = 0; i < current.size(); i++) {
            Object o = current.get(i);
            if (o instanceof JSONArray) {
                JSONArray a = (JSONArray) o;
                for (int k = 0; k < a.length(); k++) {
                    Object e = a.opt(k);
                    if (e != null && e != JSONObject.NULL) {
                        out.add(e);
                    }
                }
            } else if (o != null) {
                out.add(o);
            }
        }
        return dedupe(out);
    }

    /** Convenience: first match as a string, or "" when nothing matched. */
    public static String firstString(Object root, String path) {
        List<Object> r = eval(root, path);
        for (int i = 0; i < r.size(); i++) {
            Object o = r.get(i);
            if (o != null && !(o instanceof JSONObject) && !(o instanceof JSONArray)) {
                return String.valueOf(o);
            }
        }
        return "";
    }

    /** Convenience: all matches, as a list of strings. */
    public static List<String> strings(Object root, String path) {
        List<Object> r = eval(root, path);
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < r.size(); i++) {
            Object o = r.get(i);
            if (o != null && !(o instanceof JSONObject) && !(o instanceof JSONArray)) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    // ------------------------------------------------------------- internals

    private static final int MAX_RESULTS = 5000;

    private static class Token {
        static final int FIELD = 1;
        static final int WILDCARD = 2;
        static final int INDEX = 3;
        static final int DESCEND = 4;
        int kind;
        String name;
        int index;
    }

    private static void applyToken(Object node, Token t, List<Object> next, List<Object> sink) {
        if (node == null) {
            return;
        }
        switch (t.kind) {
            case Token.FIELD: {
                if (node instanceof JSONObject) {
                    JSONObject o = (JSONObject) node;
                    if (o.has(t.name)) {
                        Object v = o.opt(t.name);
                        if (v != null && v != JSONObject.NULL) {
                            next.add(v);
                        }
                    }
                } else if (node instanceof JSONArray) {
                    // applying a field to an array maps over its elements
                    JSONArray a = (JSONArray) node;
                    for (int i = 0; i < a.length(); i++) {
                        Object e = a.opt(i);
                        if (e instanceof JSONObject && ((JSONObject) e).has(t.name)) {
                            Object v = ((JSONObject) e).opt(t.name);
                            if (v != null && v != JSONObject.NULL) {
                                next.add(v);
                            }
                        }
                    }
                }
                break;
            }
            case Token.WILDCARD: {
                if (node instanceof JSONArray) {
                    JSONArray a = (JSONArray) node;
                    for (int i = 0; i < a.length(); i++) {
                        Object v = a.opt(i);
                        if (v != null && v != JSONObject.NULL) {
                            next.add(v);
                        }
                    }
                } else if (node instanceof JSONObject) {
                    JSONObject o = (JSONObject) node;
                    for (Iterator it = o.keys(); it.hasNext(); ) {
                        Object v = o.opt(String.valueOf(it.next()));
                        if (v != null && v != JSONObject.NULL) {
                            next.add(v);
                        }
                    }
                }
                break;
            }
            case Token.INDEX: {
                if (node instanceof JSONArray) {
                    JSONArray a = (JSONArray) node;
                    int idx = t.index < 0 ? a.length() + t.index : t.index;
                    if (idx >= 0 && idx < a.length()) {
                        Object v = a.opt(idx);
                        if (v != null && v != JSONObject.NULL) {
                            next.add(v);
                        }
                    }
                }
                break;
            }
            case Token.DESCEND: {
                // collect every value under this node whose key is t.name
                List<Object> found = new ArrayList<Object>();
                descend(node, t.name, found, 0);
                next.addAll(found);
                break;
            }
            default:
                break;
        }
    }

    private static void descend(Object node, String name, List<Object> out, int depth) {
        if (node == null || depth > 12 || out.size() > MAX_RESULTS) {
            return;
        }
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            if (name == null) {
                for (Iterator it = o.keys(); it.hasNext(); ) {
                    Object v = o.opt(String.valueOf(it.next()));
                    if (v != null && v != JSONObject.NULL) {
                        out.add(v);
                    }
                }
            } else if (o.has(name)) {
                Object v = o.opt(name);
                if (v != null && v != JSONObject.NULL) {
                    out.add(v);
                }
            }
            for (Iterator it = o.keys(); it.hasNext(); ) {
                descend(o.opt(String.valueOf(it.next())), name, out, depth + 1);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                descend(a.opt(i), name, out, depth + 1);
            }
        }
    }

    private static List<Token> tokenize(String p) {
        List<Token> tokens = new ArrayList<Token>();
        int i = 0;
        int n = p.length();
        while (i < n && tokens.size() < 64) {
            char c = p.charAt(i);
            if (c == '.') {
                if (i + 1 < n && p.charAt(i + 1) == '.') {
                    Token t = new Token();
                    t.kind = Token.DESCEND;
                    i += 2;
                    // optional field name following ".."
                    int start = i;
                    while (i < n && p.charAt(i) != '.' && p.charAt(i) != '[') {
                        i++;
                    }
                    t.name = (i > start) ? p.substring(start, i) : null;
                    tokens.add(t);
                } else {
                    i++;
                }
                continue;
            }
            if (c == '[') {
                int close = p.indexOf(']', i);
                if (close < 0) {
                    break;
                }
                String inner = p.substring(i + 1, close).trim();
                Token t = new Token();
                if (inner.length() == 0 || inner.equals("*")) {
                    t.kind = Token.WILDCARD;
                } else if ((inner.charAt(0) == '\'' || inner.charAt(0) == '"')
                        && inner.length() >= 2) {
                    t.kind = Token.FIELD;
                    t.name = inner.substring(1, inner.length() - 1);
                } else {
                    try {
                        t.kind = Token.INDEX;
                        t.index = Integer.parseInt(inner);
                    } catch (Throwable ex) {
                        t.kind = Token.FIELD;
                        t.name = inner;
                    }
                }
                tokens.add(t);
                i = close + 1;
                continue;
            }
            // bare field name
            int start = i;
            while (i < n && p.charAt(i) != '.' && p.charAt(i) != '[') {
                i++;
            }
            String name = p.substring(start, i).trim();
            if (name.length() > 0) {
                Token t = new Token();
                t.kind = Token.FIELD;
                t.name = name;
                tokens.add(t);
            }
        }
        return tokens;
    }

    /**
     * Split "a.b[*].c" into the container path and the trailing field name.
     * Book sources often give a list path and expect the engine to pull one field per
     * element, so knowing where the list ends is useful.
     */
    public static String parentPath(String path) {
        if (path == null) {
            return null;
        }
        int cut = -1;
        for (int i = path.length() - 1; i >= 0; i--) {
            char c = path.charAt(i);
            if (c == '.' || c == ']') {
                cut = i;
                break;
            }
        }
        if (cut <= 0) {
            return null;
        }
        String parent = path.substring(0, cut);
        if (parent.endsWith("]")) {
            return parent;
        }
        if (parent.endsWith(".")) {
            parent = parent.substring(0, parent.length() - 1);
        }
        return parent;
    }

    public static String leafName(String path) {
        if (path == null) {
            return null;
        }
        int cut = -1;
        for (int i = path.length() - 1; i >= 0; i--) {
            char c = path.charAt(i);
            if (c == '.' || c == ']') {
                cut = i;
                break;
            }
        }
        if (cut < 0 || cut + 1 >= path.length()) {
            return null;
        }
        return path.substring(cut + 1);
    }

    private static List<Object> dedupe(List<Object> in) {
        List<Object> out = new ArrayList<Object>();
        List<Integer> seen = new ArrayList<Integer>();
        for (int i = 0; i < in.size() && out.size() < MAX_RESULTS; i++) {
            Object o = in.get(i);
            int h = System.identityHashCode(o);
            boolean dup = false;
            for (int k = 0; k < seen.size(); k++) {
                if (seen.get(k).intValue() == h && out.get(k) == o) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                out.add(o);
                seen.add(Integer.valueOf(h));
            }
        }
        return out;
    }
}

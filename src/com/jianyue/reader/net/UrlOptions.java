package com.jianyue.reader.net;

import android.util.Log;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The optional request options 阅读 appends to a URL.
 *
 * 阅读 lets a book source put per-request instructions after a comma:
 *
 *   searchUrl: "/search, {\"method\":\"POST\",\"body\":\"key={{key}}\",\"charset\":\"gbk\"}"
 *   tocUrl:    "http://api.site/toc, {\"headers\":{\"Referer\":\"http://site/\"}}"
 *
 * Without this a whole class of sources could not be used at all: their search endpoint only
 * answers POST, or it needs a per-request Referer/Authorization that the source-level header
 * block cannot express. The field is parsed here ONCE for every place an engine URL is
 * fetched (search, detail, 目录, 正文), because a second implementation would drift.
 *
 * Deliberately ignored: "webView" (API 1 has no usable WebView), "js" and "type" (they need a
 * JS pipeline this reader does not have). A source that depends on them fails on the request
 * itself rather than silently returning the wrong page.
 */
public final class UrlOptions {

    private static final String TAG = "JianYue";

    /** The URL with the options tail removed. */
    public String url = "";
    /** The raw "," + json tail, or "" - kept so a caller can rebuild the URL unchanged. */
    public String tail = "";
    /** "GET" (default) or "POST". */
    public String method = "GET";
    /** Request body for POST. */
    public String body;
    /** Charset used to decode the response and to encode the body ("gbk", "utf-8"). */
    public String charset;
    /** Per-request headers, merged over the source-level ones. */
    public final Map<String, String> headers = new HashMap<String, String>();
    /** Non-null when the source asked for something this reader cannot do. */
    public String unsupported;

    public boolean isPost() {
        return "POST".equalsIgnoreCase(method);
    }

    /**
     * Split "url, {json}" into the URL and its options.
     *
     * Scans from the END for the last ",{" that parses as a JSON object, because the URL
     * itself may contain commas and braces (query strings do).
     */
    public static UrlOptions parse(String value) {
        UrlOptions o = new UrlOptions();
        if (value == null) {
            o.url = "";
            return o;
        }
        String v = value.trim();
        int idx = v.length();
        while (true) {
            idx = v.lastIndexOf(",", idx - 1);
            if (idx < 0) {
                o.url = v;
                return o;
            }
            String tail = v.substring(idx + 1).trim();
            if (tail.length() < 2 || tail.charAt(0) != '{') {
                continue;
            }
            JSONObject obj = null;
            try {
                obj = new JSONObject(tail);
            } catch (Throwable t) {
                obj = null;
            }
            if (obj == null) {
                continue;
            }
            o.url = v.substring(0, idx).trim();
            o.tail = v.substring(idx).trim();
            apply(o, obj);
            return o;
        }
    }

    private static void apply(UrlOptions o, JSONObject obj) {
        try {
            String m = obj.optString("method", "");
            if (m.length() > 0) {
                o.method = m.trim().toUpperCase();
            }
            if (obj.has("body")) {
                o.body = obj.optString("body", "");
            }
            String cs = obj.optString("charset", "");
            if (cs.length() > 0) {
                o.charset = cs;
            }
            Object h = obj.opt("headers");
            if (h instanceof JSONObject) {
                JSONObject hj = (JSONObject) h;
                Iterator<?> it = hj.keys();
                while (it.hasNext()) {
                    String k = String.valueOf(it.next());
                    String v = hj.optString(k, "");
                    if (k.length() > 0) {
                        o.headers.put(k, v);
                    }
                }
            } else if (h instanceof String && ((String) h).trim().length() > 0) {
                // some sources write the header block as a single string
                String[] lines = ((String) h).replace("\\n", "\n").split("\n");
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i].trim();
                    int at = line.indexOf('@');
                    if (at > 0) {
                        o.headers.put(line.substring(0, at).trim(), line.substring(at + 1).trim());
                    }
                }
            }
            if (obj.optBoolean("webView", false)) {
                o.unsupported = "需要 WebView";
            }
        } catch (Throwable t) {
            Log.i(TAG, "urlOpts: " + t.getClass().getName() + ": " + t.getMessage());
        }
    }
}

package com.jianyue.reader.net;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * HTTP client for Android 1.0.
 *
 * Platform constraints this exists to absorb (all measured on a real API-1 device,
 * see API1-能力实测记录.md):
 *
 *   - TLS ceiling is TLS 1.0. Sites that require TLS 1.2+ can never be reached, so
 *     callers must be able to fall back to plain HTTP. requestWithFallback() does that.
 *   - Most 2008-era sites serve GBK, and Content-Type is frequently missing or wrong,
 *     so the body is decoded with the project's own TextCodec (BOM / UTF-8 validity /
 *     GB2312 table) rather than trusting the header. The header is only a hint.
 *   - java.util.zip.GZIPInputStream exists at API 1.
 *   - No okhttp, no HttpURLConnection helpers from newer APIs.
 *
 * Deliberately avoids: try-with-resources, lambdas, String.isEmpty(), Arrays.copyOfRange.
 */
public final class Http {

    private static final String TAG = "JianYue";

    /** Mozilla/5.0 keeps most aggregation sites from returning 403. */
    public static final String DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private Http() {
    }

    /** A completed response. */
    public static class Resp {
        public int code;
        public byte[] body;
        public String contentType;
        public String charsetHint;
        public String finalUrl;
        public Map<String, String> headers = new HashMap<String, String>();
        public String error;

        /**
         * Class name of the throwable that produced {@link #error}, or null for an HTTP
         * status failure. Callers that need to tell "the host is unreachable" from "the
         * page answered with 404" read this instead of parsing error.
         */
        public String errorClass;

        /**
         * True when the request never got an HTTP answer because the host could not be
         * reached (DNS, refused connection, TLS handshake, connect timeout). This is the
         * class of failure that says something about the SITE rather than about the rules.
         */
        public boolean unreachable() {
            if (error == null || errorClass == null) {
                return false;
            }
            return errorClass.indexOf("UnknownHostException") >= 0
                    || errorClass.indexOf("ConnectException") >= 0
                    || errorClass.indexOf("SocketTimeoutException") >= 0
                    || errorClass.indexOf("SSL") >= 0
                    || errorClass.indexOf("NoRouteToHost") >= 0
                    || errorClass.indexOf("SocketException") >= 0;
        }

        /**
         * True when the call actually produced a usable response.
         *
         * code must be a real HTTP status: getResponseCode() returns -1 when the
         * connection itself failed, and treating that as success caused the reader to
         * silently produce empty chapters instead of reporting the network error.
         */
        public boolean ok() {
            return error == null && code > 0 && code < 400 && body != null;
        }

        /** True when we have any body to parse, even from a 4xx/5xx response. */
        public boolean hasBody() {
            return body != null && body.length > 0;
        }

        /**
         * Decode the body to text using the project's own detector.
         * The Content-Type charset is treated as a hint only: on API-1-era sites it is
         * wrong often enough that trusting it produces mojibake.
         */
        public String text() {
            if (body == null || body.length == 0) {
                return "";
            }
            if (charsetHint != null && charsetHint.length() > 0) {
                // Trust an explicit UTF-8 declaration, and also trust GBK/GB2312.
                String h = charsetHint.toLowerCase();
                if (h.indexOf("gb") >= 0) {
                    return com.jianyue.reader.util.TextCodec.decode(
                            body, 0, body.length, com.jianyue.reader.util.TextCodec.ENC_GBK);
                }
                if (h.indexOf("utf") >= 0) {
                    return com.jianyue.reader.util.TextCodec.decode(
                            body, 0, body.length, com.jianyue.reader.util.TextCodec.ENC_UTF8);
                }
            }
            return com.jianyue.reader.util.TextCodec.decodeAuto(body, 0, body.length);
        }

        public String header(String name) {
            if (name == null) {
                return null;
            }
            return headers.get(name.toLowerCase());
        }
    }

    /**
     * Simple GET.
     *
     * @param referer may be null
     * @param ua      may be null (DEFAULT_UA is used)
     */
    public static Resp get(String url, String referer, String ua) {
        return request(url, "GET", null, referer, ua, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    /**
     * Timeouts.
     *
     * These were 20s connect / 25s read, which made a multi-source search feel broken: with
     * a handful of dead hosts in the list, the search waited out the full timeout for each
     * one. 8s is still generous for a text page on a slow link - a site that has not
     * answered a TCP connect in 8 seconds is not going to be usable anyway - and it caps the
     * worst case at roughly a third of what it was.
     */
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 12000;

    public static Resp get(String url) {
        return get(url, null, null);
    }

    /**
     * Force a DNS lookup for a host so a stale/failed resolve does not get misread as an
     * unreachable site.
     *
     * The emulator's slirp resolver degrades over long uptimes; without this, a batch probe
     * reports sites as dead when only the resolver was. InetAddress.getAllByName is present
     * at API 1, and the result lands in the JVM's DNS cache for the subsequent request.
     */
    public static void warmDns(String urlOrHost) {
        try {
            String host = urlOrHost;
            int s = host.indexOf("://");
            if (s >= 0) {
                host = host.substring(s + 3);
            }
            int e = host.indexOf('/');
            if (e >= 0) {
                host = host.substring(0, e);
            }
            int c = host.indexOf(':');
            if (c >= 0) {
                host = host.substring(0, c);
            }
            if (host.length() > 0) {
                java.net.InetAddress.getAllByName(host);
            }
        } catch (Throwable t) {
            Log.i(TAG, "net: warmDns " + urlOrHost + " failed: " + t.getMessage());
        }
    }

    /**
     * GET with the extra headers a book source declared.
     *
     * 阅读 sources carry a `header` block (usually Referer and a specific User-Agent)
     * because most novel aggregators reject requests without them. These are applied after
     * the defaults, so a source can override the User-Agent but cannot accidentally drop
     * the Accept headers.
     */
    public static Resp get(String url, String referer, String ua,
                           java.util.Map<String, String> extra) {
        return request(url, "GET", null, referer, ua, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS,
                extra);
    }

    /**
     * Hosts that failed over HTTPS and proved reachable over HTTP.
     *
     * The https -> http fallback costs a full TLS timeout every time, and a search hits the
     * SAME host once per source (several sources often share one site). Remembering the
     * downgrade means the fallback is paid once per host per process instead of once per
     * request, which is the difference between one timeout and five for a site like
     * 鬼吹灯 that only serves plain HTTP.
     */
    private static final java.util.Set<String> HTTP_ONLY =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    static boolean isKnownHttpOnly(String url) {
        String h = hostKey(url);
        return h.length() > 0 && HTTP_ONLY.contains(h);
    }

    private static void markHttpOnly(String url) {
        String h = hostKey(url);
        if (h.length() > 0) {
            HTTP_ONLY.add(h);
        }
    }

    private static String hostKey(String url) {
        if (url == null) {
            return "";
        }
        try {
            return new URL(url).getHost();
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------- negative host cache
    //
    // A search with a few hundred imported sources spends nearly all of its time on hosts
    // that will never answer: the connect attempt has to run out its timeout for each one.
    // Worse, several sources usually point at the SAME dead host, and without a cache every
    // one of them pays the timeout again.
    //
    // So a failure that is a property of the HOST - the name does not resolve, the
    // connection is refused, the TLS handshake fails, the connect times out - is remembered
    // for a while and everything on that host is skipped immediately afterwards. Read
    // timeouts and HTTP error codes are deliberately NOT cached: those say something about
    // one page, not about the site.
    //
    // TTLs are short enough that a site which comes back is retried within the same session
    // (the user only has to wait for the next search to start, not restart the app).

    private static final long DEAD_TTL_MS = 5 * 60 * 1000L;       // refused / unreachable
    private static final long SLOW_TTL_MS = 90 * 1000L;           // connect timed out
    private static final long HTTPS_BROKEN_TTL_MS = 30 * 60 * 1000L;

    private static final Map<String, Long> DEAD_HOSTS =
            java.util.Collections.synchronizedMap(new HashMap<String, Long>());
    private static final Map<String, Long> HTTPS_BROKEN =
            java.util.Collections.synchronizedMap(new HashMap<String, Long>());

    /** Human-readable reason a host is currently being skipped, or null when it is not. */
    public static String deadReason(String url) {
        String h = hostKey(url);
        if (h.length() == 0) {
            return null;
        }
        Long until = DEAD_HOSTS.get(h);
        if (until == null) {
            return null;
        }
        if (until.longValue() <= System.currentTimeMillis()) {
            DEAD_HOSTS.remove(h);
            return null;
        }
        return DEAD_HOST_REASONS.get(h);
    }

    private static final Map<String, String> DEAD_HOST_REASONS =
            java.util.Collections.synchronizedMap(new HashMap<String, String>());

    /** Forget every remembered host failure (the "重搜全部" path). */
    public static void clearHostCache() {
        DEAD_HOSTS.clear();
        DEAD_HOST_REASONS.clear();
        HTTPS_BROKEN.clear();
    }

    public static int hostCacheSize() {
        return DEAD_HOSTS.size();
    }

    /** Called from request()'s failure path. See the block comment above for the rules. */
    private static void noteHostFailure(String url, Throwable t) {
        try {
            String h = hostKey(url);
            if (h.length() == 0 || t == null) {
                return;
            }
            String cls = t.getClass().getName();
            String msg = String.valueOf(t.getMessage());
            long now = System.currentTimeMillis();
            // unwrap one level: HttpURLConnection usually reports the real cause directly,
            // but some platforms wrap it.
            Throwable cause = t.getCause();
            String causeCls = cause == null ? "" : cause.getClass().getName();

            if (cls.indexOf("UnknownHost") >= 0 || causeCls.indexOf("UnknownHost") >= 0) {
                // Deliberately NOT cached. A missing name fails in milliseconds, so caching
                // buys nothing, and a resolver hiccup (the API-1 emulator's slirp DNS degrades
                // over uptime; a phone switching networks does the same) would otherwise blank
                // out every source on that host for minutes. Measured: caching it turned a
                // 120-source search into "101 sources skipped" while the DNS was fine.
                Log.i(TAG, "net: host unresolved (not cached): " + h);
            } else if (cls.indexOf("ConnectException") >= 0
                    || causeCls.indexOf("ConnectException") >= 0) {
                markDead(h, "连接被拒绝", DEAD_TTL_MS, now);
            } else if (cls.indexOf("NoRouteToHost") >= 0 || cls.indexOf("SocketException") >= 0) {
                markDead(h, "网络不可达", DEAD_TTL_MS, now);
            } else if (cls.indexOf("SSL") >= 0 || msg.indexOf("SSL") >= 0 || msg.indexOf("Certificate") >= 0) {
                // TLS 1.0 ceiling, NOT a dead site: the same host usually answers over plain
                // http, and marking it dead here would skip every other source on it even
                // though their http URL works. Only remember "use http for this host".
                HTTPS_BROKEN.put(h, Long.valueOf(now + HTTPS_BROKEN_TTL_MS));
            } else if (cls.indexOf("SocketTimeout") >= 0) {
                markDead(h, "连接超时", SLOW_TTL_MS, now);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void markDead(String host, String why, long ttl, long now) {
        if (host == null || host.length() == 0) {
            return;
        }
        DEAD_HOSTS.put(host, Long.valueOf(now + ttl));
        DEAD_HOST_REASONS.put(host, why == null ? "" : why);
    }

    private static boolean httpsBroken(String url) {
        String h = hostKey(url);
        if (h.length() == 0) {
            return false;
        }
        Long until = HTTPS_BROKEN.get(h);
        if (until == null) {
            return false;
        }
        if (until.longValue() <= System.currentTimeMillis()) {
            HTTPS_BROKEN.remove(h);
            return false;
        }
        return true;
    }

    /**
     * Try the URL as given; if it is HTTPS and the handshake fails for a reason that
     * looks like the TLS-1.0 ceiling, retry the same host over plain HTTP.
     *
     * This matters because the target sites are reachable over HTTP but the URLs
     * copied out of a browser are almost always https://.
     */
    public static Resp getWithHttpFallback(String url, String referer, String ua) {
        return getWithHttpFallback(url, referer, ua, null);
    }

    /** As above, carrying a book source's extra headers through both attempts. */
    public static Resp getWithHttpFallback(String url, String referer, String ua,
                                           java.util.Map<String, String> extra) {
        if (url == null) {
            return request(null, "GET", null, referer, ua, CONNECT_TIMEOUT_MS,
                    READ_TIMEOUT_MS, extra);
        }
        // If this host already proved to be HTTP-only, skip the doomed HTTPS attempt
        // entirely. Without this, every request to such a site pays a TLS timeout first.
        if (isKnownHttpOnly(url) && url.startsWith("https://")) {
            String httpUrl = "http://" + url.substring("https://".length());
            Resp r = get(httpUrl, referer, ua, extra);
            if (r.ok() && r.code < 400) {
                return r;
            }
        }
        // Same for a host whose TLS handshake already failed once (TLS-1.0 ceiling): going
        // straight to http turns a timeout into one failed request.
        if (httpsBroken(url) && url.startsWith("https://")) {
            String httpUrl = "http://" + url.substring("https://".length());
            Resp r = get(httpUrl, referer, ua, extra);
            if (r.ok() && r.code < 400) {
                markHttpOnly(url);
                return r;
            }
        }
        Resp r = get(url, referer, ua, extra);
        if (r.ok() && r.code < 400) {
            return r;
        }
        if (url.startsWith("https://")) {
            String httpUrl = "http://" + url.substring("https://".length());
            Log.i(TAG, "net: https failed (" + describeFailure(r) + "), retrying over http: " + httpUrl);
            Resp r2 = get(httpUrl, referer, ua, extra);
            if (r2.ok() && r2.code < 400) {
                markHttpOnly(url);
                return r2;
            }
            // report the first failure, it is the more informative one
            return r;
        }
        return r;
    }

    private static String describeFailure(Resp r) {
        if (r == null) {
            return "null";
        }
        if (r.error != null) {
            return r.error;
        }
        return "HTTP " + r.code;
    }

    /**
     * Fetch a book-source URL that may carry the 阅读 options tail (", {json}").
     *
     * THE single entry point for every engine request: search, detail, 目录 and 正文 all call
     * this, so the option semantics exist in exactly one place. Callers pass the raw rule
     * result; nothing else has to know about POST bodies or per-request headers.
     *
     * The https -> http fallback is kept for POST as well: on API 1 the TLS 1.0 ceiling is
     * the single most common reason a site is unreachable, and its plain-HTTP endpoint is
     * usually the same service.
     */
    public static Resp fetch(String urlWithOptions, String referer,
                             java.util.Map<String, String> sourceHeaders) {
        UrlOptions o = UrlOptions.parse(urlWithOptions);
        if (o.url.length() == 0) {
            Resp bad = new Resp();
            bad.finalUrl = "";
            bad.error = "URL 为空";
            bad.errorClass = "IllegalArgumentException";
            return bad;
        }
        java.util.Map<String, String> headers = new java.util.HashMap<String, String>();
        if (sourceHeaders != null) {
            headers.putAll(sourceHeaders);
        }
        headers.putAll(o.headers);
        if (o.unsupported != null) {
            Log.i(TAG, "net: " + o.url + " needs something unsupported: " + o.unsupported);
        }
        Resp r;
        if (o.isPost()) {
            r = requestWithFallback(o, referer, headers);
        } else {
            r = getWithHttpFallback(o.url, referer, null, headers);
        }
        // A source-declared charset beats both the Content-Type header and our detector:
        // 阅读 sources write it precisely because the site lies about its encoding.
        if (r != null && o.charset != null && o.charset.length() > 0) {
            r.charsetHint = o.charset;
        }
        return r;
    }

    /** POST (or any other verb) with the same https -> http retry the GET path has. */
    private static Resp requestWithFallback(UrlOptions o, String referer,
                                            java.util.Map<String, String> headers) {
        byte[] body = encodeBody(o.body, o.charset);
        Resp r = requestBytes(o.url, o.method, body, referer, null,
                CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, headers);
        if (r.ok() && r.code < 400) {
            return r;
        }
        if (o.url.startsWith("https://")) {
            String httpUrl = "http://" + o.url.substring("https://".length());
            Log.i(TAG, "net: " + o.method + " https failed (" + describeFailure(r)
                    + "), retrying over http: " + httpUrl);
            Resp r2 = requestBytes(httpUrl, o.method, body, referer, null,
                    CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, headers);
            if (r2.ok() && r2.code < 400) {
                markHttpOnly(o.url);
                return r2;
            }
        }
        return r;
    }

    /**
     * POST bodies are bytes on the wire. Most sources send UTF-8, but a 阅读 source may
     * declare "charset":"gbk" - sending the body as UTF-8 then would hand the server
     * mojibake and its search would come back empty.
     */
    private static byte[] encodeBody(String body, String charset) {
        if (body == null) {
            return null;
        }
        if (charset != null && charset.trim().length() > 0
                && charset.toLowerCase().indexOf("utf") < 0) {
            try {
                return body.getBytes(charset.trim());
            } catch (Throwable t) {
                Log.i(TAG, "net: cannot encode the POST body as " + charset + ", using UTF-8");
            }
        }
        try {
            return body.getBytes("UTF-8");
        } catch (Throwable t) {
            return body.getBytes();
        }
    }

    /** Full control variant. */
    public static Resp request(String url, String method, String postBody,
                               String referer, String ua,
                               int connectTimeoutMs, int readTimeoutMs) {
        return request(url, method, postBody, referer, ua, connectTimeoutMs, readTimeoutMs, null);
    }

    /** Full control variant, with the book source's extra headers. */
    public static Resp request(String url, String method, String postBody,
                               String referer, String ua,
                               int connectTimeoutMs, int readTimeoutMs,
                               java.util.Map<String, String> extra) {
        byte[] body = null;
        if (postBody != null) {
            try {
                body = postBody.getBytes("UTF-8");
            } catch (Throwable t) {
                body = postBody.getBytes();
            }
        }
        return requestBytes(url, method, body, referer, ua, connectTimeoutMs, readTimeoutMs, extra);
    }

    /**
     * As above but with the body already encoded.
     *
     * A POST body's bytes are decided by the source's "charset" option, so the encoder has to
     * happen before this call - re-encoding a String here as UTF-8 would corrupt a GBK body.
     */
    public static Resp requestBytes(String url, String method, byte[] postBody,
                                    String referer, String ua,
                                    int connectTimeoutMs, int readTimeoutMs,
                                    java.util.Map<String, String> extra) {
        Resp out = new Resp();
        out.finalUrl = url;
        HttpURLConnection c = null;
        try {
            NetKit.ensureTlsConfigured();
            URL u = new URL(url);
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod(method == null ? "GET" : method);
            c.setConnectTimeout(connectTimeoutMs);
            c.setReadTimeout(readTimeoutMs);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", ua == null ? DEFAULT_UA : ua);
            c.setRequestProperty("Accept", "text/html,application/json,application/xhtml+xml,*/*;q=0.8");
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            if (referer != null) {
                c.setRequestProperty("Referer", referer);
            }
            // Source-declared headers go last so they win over the defaults above.
            boolean contentTypeSet = false;
            if (extra != null) {
                for (java.util.Map.Entry<String, String> e : extra.entrySet()) {
                    String k = e.getKey();
                    String v = e.getValue();
                    if (k != null && v != null && k.length() > 0) {
                        c.setRequestProperty(k, v);
                        if (k.equalsIgnoreCase("Content-Type")) {
                            contentTypeSet = true;
                        }
                    }
                }
            }
            if (postBody != null) {
                c.setDoOutput(true);
                if (!contentTypeSet) {
                    c.setRequestProperty("Content-Type",
                            "application/x-www-form-urlencoded; charset=UTF-8");
                }
                c.setFixedLengthStreamingMode(postBody.length);
                c.getOutputStream().write(postBody);
                c.getOutputStream().flush();
            }

            out.code = c.getResponseCode();
            out.contentType = c.getContentType();
            out.charsetHint = extractCharset(out.contentType);
            out.finalUrl = c.getURL() == null ? url : c.getURL().toString();
            // The host answered, so whatever we remembered about it is wrong now.
            String hk = hostKey(url);
            if (hk.length() > 0) {
                DEAD_HOSTS.remove(hk);
                DEAD_HOST_REASONS.remove(hk);
            }

            // collect a few useful headers
            collectHeader(c, out, "Set-Cookie");
            collectHeader(c, out, "Location");
            collectHeader(c, out, "Content-Type");

            InputStream in = null;
            try {
                in = (out.code >= 400) ? c.getErrorStream() : c.getInputStream();
            } catch (Throwable t) {
                in = c.getErrorStream();
            }
            if (in != null) {
                String enc = c.getContentEncoding();
                if (enc != null && enc.toLowerCase().indexOf("gzip") >= 0) {
                    try {
                        in = new GZIPInputStream(in);
                    } catch (Throwable t) {
                        Log.i(TAG, "net: gzip wrap failed, reading raw: " + t.getMessage());
                    }
                }
                out.body = readAll(in, 8 * 1024 * 1024);
            } else {
                out.body = new byte[0];
            }
        } catch (Throwable t) {
            out.error = t.getClass().getName() + ": " + t.getMessage();
            out.errorClass = t.getClass().getName();
            // Remember host-level failures so the other sources on the same dead site are
            // not paid for one timeout at a time (see the negative-cache block above).
            noteHostFailure(url, t);
            Log.i(TAG, "net: " + url + " failed -> " + out.error);
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
        Log.i(TAG, "net: " + method + " " + url + " -> " + (out.error != null ? out.error
                : ("HTTP " + out.code + " " + (out.body == null ? 0 : out.body.length) + "B"
                   + " charset=" + out.charsetHint)));
        return out;
    }

    private static void collectHeader(HttpURLConnection c, Resp out, String name) {
        try {
            List<String> vals = new ArrayList<String>();
            Map<String, List<String>> all = c.getHeaderFields();
            if (all == null) {
                return;
            }
            for (Map.Entry<String, List<String>> e : all.entrySet()) {
                String k = e.getKey();
                if (k != null && k.equalsIgnoreCase(name) && e.getValue() != null) {
                    vals.addAll(e.getValue());
                }
            }
            if (vals.size() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < vals.size(); i++) {
                    if (i > 0) {
                        sb.append("; ");
                    }
                    sb.append(vals.get(i));
                }
                out.headers.put(name.toLowerCase(), sb.toString());
            }
        } catch (Throwable ignored) {
        }
    }

    public static String extractCharset(String contentType) {
        if (contentType == null) {
            return null;
        }
        String lower = contentType.toLowerCase();
        int i = lower.indexOf("charset=");
        if (i < 0) {
            return null;
        }
        String v = contentType.substring(i + 8).trim();
        int semi = v.indexOf(';');
        if (semi >= 0) {
            v = v.substring(0, semi).trim();
        }
        if (v.length() > 1 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static byte[] readAll(InputStream in, int cap) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(16384);
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            total += n;
            if (total >= cap) {
                Log.i(TAG, "net: body exceeded " + cap + " bytes, truncating");
                break;
            }
        }
        try {
            in.close();
        } catch (Throwable ignored) {
        }
        return bos.toByteArray();
    }
}

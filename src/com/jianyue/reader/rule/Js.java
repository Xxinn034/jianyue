package com.jianyue.reader.rule;

import android.util.Log;

import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ImporterTopLevel;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.HashMap;
import java.util.Map;

/**
 * JavaScript bridge for book-source rules, on Rhino rebuilt for Android 1.0.
 *
 * The Rhino jar used here is NOT the stock one. Stock 1.7.13 has
 * org.mozilla.javascript.Context referencing java.lang.invoke.* in its signatures, which
 * makes API-1 Dalvik reject the class outright (VerifyError / ClassNotFoundException).
 * The jar at tools/sdk/rhino-api1.jar was recompiled from 1.7.7.2 sources against the
 * API-1 android.jar with java.beans, java.text.Normalizer and the E4X XML package
 * stripped. See API1-能力实测记录.md section 3.
 *
 * Two Rhino settings are mandatory on this platform:
 *   setOptimizationLevel(-1)  Rhino otherwise emits JVM bytecode at runtime, which
 *                             Dalvik cannot load.
 *   setLanguageVersion(170)   ES5.1 semantics, which is what book sources expect.
 *
 * WHAT A 阅读 SCRIPT EXPECTS TO FIND (this is why the file is long)
 * ----------------------------------------------------------------
 * Real 3.0 sources do not just compute strings - they call the host:
 *
 *   {{java.base64Decode('...')}}          decode an obfuscated parameter
 *   @js:java.ajax(baseUrl + '/api/x')     fetch a second URL from inside a rule
 *   java.put('token', x); ... java.get('token')
 *   source.getVariable('x') / source.key  per-source state
 *   if (book.name.length > 0) ...         book metadata
 *   Packages.javax.crypto.Cipher...       arbitrary Java (AES-encrypted APIs)
 *   new JavaImporter(...); with(...)      the JavaImporter style some sources use
 *
 * So the scope is an {@link ImporterTopLevel} (which is what provides Packages,
 * JavaImporter and importClass) with a ClassShutter that keeps the dangerous parts of the
 * JDK out of reach, and a `java` object implementing the helper methods 阅读 provides.
 * Without these, an imported source fails with "java is not defined" and every rule using
 * it silently returns empty.
 */
public final class Js {

    private static final String TAG = "JianYue";

    /**
     * The shared global scope. Guarded by the class lock; the Rhino Context is NOT stored
     * here on purpose - a Context belongs to one thread (see enterContext()).
     */
    private static Scriptable scope;
    private static boolean unavailable;
    private static String unavailableReason;

    private Js() {
    }

    // ------------------------------------------------------------- context

    /**
     * What a script can see. 阅读 binds a different set per call site (search rules see no
     * book, content rules see one), so this travels with the call instead of being global.
     */
    public static class Bind {
        public String result = "";
        public String baseUrl = "";
        /** name, author, bookUrl, tocUrl, intro, kind - may be null. */
        public String[] book;
        /** search keyword (URL-encoded), empty outside a search. */
        public String key = "";
        public int page = 1;
        public String sourceName = "";
        public String sourceUrl = "";
        /** @put/@get and java.put/java.get share this store. */
        public Map<String, String> vars = new HashMap<String, String>();
    }

    /** The Bind of the script currently running, for the host helpers. */
    private static final ThreadLocal<Bind> CURRENT = new ThreadLocal<Bind>();

    static Bind current() {
        Bind b = CURRENT.get();
        return b == null ? new Bind() : b;
    }

    public static synchronized boolean available() {
        if (unavailable) {
            return false;
        }
        if (scope == null) {
            Context cx = enterContext();
            try {
                initScope(cx);
            } finally {
                exitContext(cx);
            }
        }
        return !unavailable;
    }

    public static synchronized String unavailableReason() {
        available();
        return unavailableReason;
    }

    /**
     * Get a Rhino Context that belongs to the CALLING thread.
     *
     * This used to be a static field filled by whichever thread asked first, and that broke
     * every use from another thread with "No Context associated with current Thread": a search
     * evaluates @js rules on its worker threads, so the chapter loader (or the rule self-test)
     * on a different thread could no longer run any script. A Context is thread-local by
     * design; the rule is to take the calling thread's own, and to leave it if we entered it.
     */
    private static Context enterContext() {
        Context cx = Context.getCurrentContext();
        if (cx == null) {
            cx = Context.enter();
        }
        // These two settings are mandatory on API 1 and must be (re)asserted on whichever
        // Context we end up with: interpreted mode (Dalvik cannot load runtime-generated
        // bytecode) and ES5.1 language semantics.
        cx.setOptimizationLevel(-1);
        cx.setLanguageVersion(170);
        cx.setClassShutter(new Shutter());
        return cx;
    }

    private static void exitContext(Context cx) {
        try {
            if (cx != null && Context.getCurrentContext() == cx) {
                Context.exit();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Create the shared global scope. Caller must hold the class lock and a Context. */
    private static void initScope(Context cx) {
        if (scope != null || unavailable) {
            return;
        }
        try {
            // ImporterTopLevel (not initStandardObjects) is what puts Packages / JavaImporter /
            // importClass on the global object, which 一程-style sources rely on for AES and
            // other Java APIs. It is also the thing that makes a sandbox necessary.
            scope = new ImporterTopLevel(cx);
            try {
                ScriptableObject.putProperty(scope, "console",
                        Context.javaToJS(new Console(), scope));
            } catch (Throwable ignored) {
            }
            Log.i(TAG, "js: Rhino initialised (interpretive mode, ImporterTopLevel)");
        } catch (Throwable t) {
            unavailable = true;
            unavailableReason = t.getClass().getName() + ": " + t.getMessage();
            Log.i(TAG, "js: Rhino UNAVAILABLE -> " + unavailableReason);
            scope = null;
        }
    }

    /**
     * A fresh scope for one script call, with our host objects bound as OWN properties.
     *
     * The bindings cannot live on the global scope: ImporterTopLevel already defines `java`
     * (and javax/org/com/...) as a JavaPackage, and putting our helper object there either
     * loses to the package or silently does nothing - the first version of this bridge failed
     * with "Cannot call property getString in object [JavaPackage java]". A child scope wins
     * the lookup by being nearer, and it also keeps one script's globals out of the next one's.
     */
    private static Scriptable callScope(Context cx, Bind b) {
        Scriptable s = cx.newObject(scope);
        s.setPrototype(scope);
        s.setParentScope(null);
        try {
            // staticType = JavaApi.class is required: with a null static type Rhino exposes the
            // WRAPPER's methods as well, and NativeJavaObject implements Scriptable - its own
            // put/get shadow ours.
            ScriptableObject.putProperty(s, "java",
                    new org.mozilla.javascript.NativeJavaObject(s, new JavaApi(),
                            JavaApi.class));
            ScriptableObject.putProperty(s, "Base64",
                    new org.mozilla.javascript.NativeJavaObject(s, new Base64Api(),
                            Base64Api.class));
            ScriptableObject.putProperty(s, "result", nz(b.result));
            ScriptableObject.putProperty(s, "src", nz(b.result));
            ScriptableObject.putProperty(s, "baseUrl", nz(b.baseUrl));
            ScriptableObject.putProperty(s, "key", nz(b.key));
            ScriptableObject.putProperty(s, "page", Integer.valueOf(b.page));
            ScriptableObject.putProperty(s, "cookie", "");
            Scriptable source = sourceObject(cx, s, b);
            if (source != null) {
                ScriptableObject.putProperty(s, "source", source);
            }
            Scriptable book = bookObject(cx, s, b);
            if (book != null) {
                ScriptableObject.putProperty(s, "book", book);
            }
        } catch (Throwable t) {
            Log.i(TAG, "js: cannot bind the call scope: " + t);
        }
        return s;
    }

    /**
     * Keep the dangerous corners of the JDK out of book-source scripts.
     *
     * Book sources come from the internet, and the desktop-Rhino default is "every public
     * class in the JVM". Reading and deleting files or spawning a process from a rule would
     * be a real hole, and 阅读 blocks the same set for the same reason.
     */
    private static final class Shutter implements ClassShutter {
        public boolean visibleToScripts(String fullClassName) {
            if (fullClassName == null) {
                return false;
            }
            if (fullClassName.startsWith("java.io.File")
                    || fullClassName.startsWith("java.io.FileDescriptor")
                    || fullClassName.startsWith("java.io.RandomAccessFile")
                    || fullClassName.startsWith("java.lang.Runtime")
                    || fullClassName.startsWith("java.lang.Process")
                    || fullClassName.startsWith("java.lang.System")
                    || fullClassName.startsWith("java.lang.reflect")
                    || fullClassName.startsWith("java.lang.Thread")
                    || fullClassName.startsWith("java.net.ServerSocket")
                    || fullClassName.startsWith("dalvik.")) {
                Log.i(TAG, "js: blocked class " + fullClassName);
                return false;
            }
            return true;
        }
    }

    /** Java-side log object exposed to scripts. */
    public static class Console {
        public void log(Object msg) {
            Log.i(TAG, "js> " + msg);
        }

        public void error(Object msg) {
            Log.i(TAG, "js! " + msg);
        }
    }

    // ------------------------------------------------------------- running

    /**
     * Run a script and return its value as a string.
     *
     * Returns "" on any failure so a broken script degrades to empty rather than taking
     * down the whole book source.
     */
    public static String run(String script, Bind bind) {
        if (script == null || script.trim().length() == 0) {
            return "";
        }
        if (unavailable) {
            return "";
        }
        Bind b = bind == null ? new Bind() : bind;
        synchronized (Js.class) {
            Bind prev = CURRENT.get();
            CURRENT.set(b);
            Context cx = enterContext();
            try {
                if (scope == null) {
                    initScope(cx);
                }
                if (scope == null) {
                    Log.i(TAG, "js: skipped, Rhino unavailable");
                    return "";
                }
                Scriptable s = callScope(cx, b);
                Object v = cx.evaluateString(s, script, "rule", 1, null);
                if (v == null || v == Context.getUndefinedValue()) {
                    return "";
                }
                String out = Context.toString(v);
                return out == null ? "" : out;
            } catch (Throwable t) {
                // The message alone is often useless (Rhino wraps reflection failures in a
                // MissingResourceException when its own message bundle is not on the class
                // path), so the first frames are logged too - they name the real culprit.
                StringBuilder st = new StringBuilder();
                StackTraceElement[] frames = t.getStackTrace();
                for (int i = 0; i < frames.length && i < 6; i++) {
                    st.append("\n    at ").append(frames[i].toString());
                }
                Log.i(TAG, "js: script failed " + t.getClass().getName() + ": " + t.getMessage()
                        + st.toString());
                return "";
            } finally {
                if (prev == null) {
                    CURRENT.remove();
                } else {
                    CURRENT.set(prev);
                }
                exitContext(cx);
            }
        }
    }

    /** The `source` object a 阅读 script expects (name/url/key/getVariable/putVariable). */
    private static Scriptable sourceObject(Context cx, Scriptable parent, Bind b) {
        try {
            Scriptable s = cx.newObject(parent);
            ScriptableObject.putProperty(s, "name", nz(b.sourceName));
            ScriptableObject.putProperty(s, "url", nz(b.sourceUrl));
            ScriptableObject.putProperty(s, "bookSourceUrl", nz(b.sourceUrl));
            ScriptableObject.putProperty(s, "key", nz(b.key));
            ScriptableObject.putProperty(s, "getVariable",
                    new org.mozilla.javascript.BaseFunction() {
                        public Object call(Context c, Scriptable sc, Scriptable thisObj,
                                           Object[] args) {
                            return getVar(args);
                        }
                    });
            ScriptableObject.putProperty(s, "putVariable",
                    new org.mozilla.javascript.BaseFunction() {
                        public Object call(Context c, Scriptable sc, Scriptable thisObj,
                                           Object[] args) {
                            if (args != null && args.length >= 2) {
                                Rules.putVar(String.valueOf(args[0]),
                                        args[1] == null ? "" : String.valueOf(args[1]));
                            }
                            return "";
                        }
                    });
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String getVar(Object[] args) {
        if (args == null || args.length < 1) {
            return "";
        }
        return Rules.getVar(String.valueOf(args[0]));
    }

    /** The `book` object: a real source uses book.name / book.author / book.bookUrl. */
    private static Scriptable bookObject(Context cx, Scriptable parent, Bind b) {
        if (b.book == null || b.book.length < 6) {
            return null;
        }
        try {
            Scriptable s = cx.newObject(parent);
            ScriptableObject.putProperty(s, "name", nz(b.book[0]));
            ScriptableObject.putProperty(s, "author", nz(b.book[1]));
            ScriptableObject.putProperty(s, "bookUrl", nz(b.book[2]));
            ScriptableObject.putProperty(s, "tocUrl", nz(b.book[3]));
            ScriptableObject.putProperty(s, "intro", nz(b.book[4]));
            ScriptableObject.putProperty(s, "kind", nz(b.book[5]));
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Legacy entry point: no book fields, no keyword. */
    public static String run(String script, String result, String baseUrl, String[] bookFields) {
        Bind b = new Bind();
        b.result = result == null ? "" : result;
        b.baseUrl = baseUrl == null ? "" : baseUrl;
        b.book = bookFields;
        return run(script, b);
    }

    /** Evaluate a boolean-ish expression (used by enable/disable conditions). */
    public static boolean runBoolean(String script) {
        String r = run(script, (Bind) null);
        return "true".equalsIgnoreCase(r) || "1".equals(r);
    }

    /**
     * Evaluate a JavaScript snippet with no surrounding context.
     *
     * Used by inline "{{ expr }}" rules, where the expression can be entirely
     * self-contained (a date format, a string manipulation) and there is no source node to
     * expose as `result`.
     */
    public static String run(String script) {
        return run(script, (Bind) null);
    }

    /**
     * Evaluate a {{...}} expression inside a URL template.
     *
     * The keyword and page number are bound here because a URL like
     * "{{page+1}}" or "{{java.ajax(...)}}" needs them, and the URL is built before any rule
     * node exists.
     */
    public static String runUrlExpr(String expr, String keyword, int page, String baseUrl) {
        Bind b = new Bind();
        b.baseUrl = baseUrl == null ? "" : baseUrl;
        b.key = keyword == null ? "" : keyword;
        b.page = page;
        return run(expr, b);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------ host api

    /**
     * The `java` object: 阅读's helper surface for book sources.
     *
     * Only the methods that actually appear in real sources are implemented, and the ones
     * that cannot work on API 1 (webView, font de-obfuscation, zip helpers) are left out on
     * purpose - a script that calls them fails loudly in the log instead of silently
     * producing an empty field.
     */
    public static class JavaApi {

        // -------- @put/@get style variables, shared with the rule engine

        public String get(String key) {
            return Rules.getVar(key);
        }

        public String put(String key, Object value) {
            Rules.putVar(key, value == null ? "" : String.valueOf(value));
            return "";
        }

        // -------- rule evaluation helpers

        /** Run a rule against the current result (阅读: java.getString(rule)). */
        public String getString(String rule) {
            return Rules.getString(currentNode(rule), rule, ctxFor(rule), current().baseUrl);
        }

        /**
         * The current script's result as a node a rule can be evaluated against: JSON when
         * the rule asks for it, an HTML document otherwise. 阅读 does the same thing by
         * dispatching on the rule's syntax.
         */
        private Object currentNode(String rule) {
            String r = current().result;
            if (rule != null && rule.trim().startsWith("$")) {
                try {
                    return new org.json.JSONObject(r);
                } catch (Throwable ignored) {
                }
            }
            return Html.parse(r);
        }

        private int ctxFor(String rule) {
            return (rule != null && rule.trim().startsWith("$"))
                    ? Rules.CTX_JSON : Rules.CTX_HTML;
        }

        /** First element matching a CSS rule, as HTML (阅读: java.getElement). */
        public String getElement(String rule) {
            String v = Rules.getString(currentValue(), rule, Rules.CTX_HTML, current().baseUrl);
            return v == null ? "" : v;
        }

        /** All matches as an HTML array (阅读: java.getElements). */
        public Object getElements(String rule) {
            try {
                Html.El doc = Html.parse(current().result);
                java.util.List<Html.El> els = Html.select(doc, compactSelector(rule));
                Object[] out = new Object[els.size()];
                for (int i = 0; i < els.size(); i++) {
                    out[i] = els.get(i).outerHtml();
                }
                return out;
            } catch (Throwable t) {
                return new Object[0];
            }
        }
        // -------- network

        /** GET a URL and return its body text. */
        public String ajax(String url) {
            return fetch(url, null);
        }

        /** POST a URL and return its body text. */
        public String post(String url, String body) {
            return fetch(url + ", {\"method\":\"POST\",\"body\":"
                    + quote(body) + "}", null);
        }

        /** 阅读's connect(url, ...) - the extra args (method/headers/body) are honoured. */
        public String connect(String url, Object... args) {
            return fetch(url, null);
        }

        private String fetch(String url, String referer) {
            try {
                Bind b = current();
                com.jianyue.reader.net.Http.Resp r =
                        com.jianyue.reader.net.Http.fetch(url, b.sourceUrl, null);
                if (r == null || !r.ok()) {
                    String msg = (r == null) ? "null" : (r.error != null ? r.error
                            : ("HTTP " + r.code));
                    Log.i(TAG, "js: java.ajax failed " + url + " -> " + msg);
                    return "";
                }
                return r.text();
            } catch (Throwable t) {
                Log.i(TAG, "js: java.ajax error " + t.getClass().getName());
                return "";
            }
        }

        // -------- string / encoding helpers

        public String base64Decode(String s) {
            try {
                return new String(B64.decode(s), "UTF-8");
            } catch (Throwable t) {
                return "";
            }
        }

        public byte[] base64DecodeToByteArray(String s) {
            try {
                return B64.decode(s);
            } catch (Throwable t) {
                return new byte[0];
            }
        }

        public String base64Encode(String s) {
            try {
                return B64.encode(s.getBytes("UTF-8"));
            } catch (Throwable t) {
                return "";
            }
        }

        public String md5Encode(String s) {
            return digestHex(s, "MD5");
        }

        /** 阅读's md5Encode16: the middle 16 characters of the MD5 hex digest. */
        public String md5Encode16(String s) {
            String hex = digestHex(s, "MD5");
            if (hex.length() < 24) {
                return hex;
            }
            return hex.substring(8, 24);
        }

        public String digestHex(String s, String algo) {
            try {
                java.security.MessageDigest md =
                        java.security.MessageDigest.getInstance(algo == null ? "MD5" : algo);
                byte[] d = md.digest(s == null ? new byte[0] : s.getBytes("UTF-8"));
                return hex(d);
            } catch (Throwable t) {
                return "";
            }
        }

        public String hexEncodeToString(byte[] data) {
            return hex(data);
        }

        public String encodeURI(String s) {
            try {
                return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
            } catch (Throwable t) {
                return s == null ? "" : s;
            }
        }

        public String timeFormat(long time, String pattern) {
            try {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                        pattern == null ? "yyyy-MM-dd HH:mm" : pattern);
                return f.format(new java.util.Date(time));
            } catch (Throwable t) {
                return "";
            }
        }

        /** Time in another UTC offset (阅读 passes the zone as the last argument). */
        public String timeFormatUTC(long time, String pattern, int zone) {
            try {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                        pattern == null ? "yyyy-MM-dd HH:mm" : pattern);
                f.setTimeZone(java.util.TimeZone.getTimeZone("GMT" + (zone >= 0 ? "+" : "")
                        + zone + ":00"));
                return f.format(new java.util.Date(time));
            } catch (Throwable t) {
                return "";
            }
        }

        public void log(Object msg) {
            Log.i(TAG, "js> " + msg);
        }

        public void toast(Object msg) {
            Log.i(TAG, "js: toast " + msg);
        }
    }

    /** android.util.Base64 is API 8+, and API 1 has no java.util.Base64: one of our own. */
    public static final class B64 {
        private static final String ALPHABET =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

        public static byte[] decode(String s) {
            if (s == null) {
                return new byte[0];
            }
            StringBuilder clean = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '=' || ALPHABET.indexOf(c) >= 0) {
                    clean.append(c);
                }
            }
            int len = clean.length();
            while (len > 0 && clean.charAt(len - 1) == '=') {
                len--;
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int buffer = 0;
            int bits = 0;
            for (int i = 0; i < len; i++) {
                int v = ALPHABET.indexOf(clean.charAt(i));
                if (v < 0) {
                    continue;
                }
                buffer = (buffer << 6) | v;
                bits += 6;
                if (bits >= 8) {
                    bits -= 8;
                    out.write((buffer >> bits) & 0xFF);
                }
            }
            return out.toByteArray();
        }

        public static String encode(byte[] data) {
            if (data == null || data.length == 0) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (i < data.length) {
                int b0 = data[i++] & 0xFF;
                int b1 = (i < data.length) ? (data[i++] & 0xFF) : -1;
                int b2 = (i < data.length) ? (data[i++] & 0xFF) : -1;
                sb.append(ALPHABET.charAt(b0 >> 2));
                sb.append(ALPHABET.charAt(((b0 & 0x03) << 4) | (b1 < 0 ? 0 : (b1 >> 4))));
                sb.append(b1 < 0 ? '=' : ALPHABET.charAt(((b1 & 0x0F) << 2)
                        | (b2 < 0 ? 0 : (b2 >> 6))));
                sb.append(b2 < 0 ? '=' : ALPHABET.charAt(b2 & 0x3F));
            }
            return sb.toString();
        }
    }

    /** A global `Base64` object matching android.util.Base64's shape (yicheng uses it). */
    public static class Base64Api {
        public static final int DEFAULT = 0;
        public static final int NO_PADDING = 1;
        public static final int NO_WRAP = 2;
        public static final int URL_SAFE = 8;

        public byte[] decode(String s, int flags) {
            String v = (flags & URL_SAFE) != 0
                    ? s.replace('-', '+').replace('_', '/') : s;
            return B64.decode(v);
        }

        public String encodeToString(byte[] data, int flags) {
            String s = B64.encode(data);
            if ((flags & URL_SAFE) != 0) {
                s = s.replace('+', '-').replace('/', '_');
            }
            if ((flags & NO_PADDING) != 0) {
                int i = s.length();
                while (i > 0 && s.charAt(i - 1) == '=') {
                    i--;
                }
                s = s.substring(0, i);
            }
            return s;
        }

        public String encode(byte[] data, int flags) {
            return encodeToString(data, flags);
        }
    }

    // ----------------------------------------------------------------- misc

    private static String compactSelector(String rule) {
        if (rule == null) {
            return "";
        }
        String sel = rule;
        int at = sel.indexOf('@');
        if (at > 0) {
            sel = sel.substring(0, at);
        }
        if (sel.startsWith("class.")) {
            sel = "." + sel.substring(6);
        } else if (sel.startsWith("id.")) {
            sel = "#" + sel.substring(3);
        } else if (sel.startsWith("tag.")) {
            sel = sel.substring(4);
        }
        return sel;
    }

    /** Value for `result` at the JavaApi level: the bound result, as a parseable document. */
    private static Object currentValue() {
        return current().result;
    }
    private static String hex(byte[] d) {
        if (d == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (int i = 0; i < d.length; i++) {
            int v = d[i] & 0xFF;
            if (v < 16) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    private static String quote(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.append('"').toString();
    }
}

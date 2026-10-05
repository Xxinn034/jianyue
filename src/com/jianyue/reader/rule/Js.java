package com.jianyue.reader.rule;

import android.util.Log;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

/**
 * JavaScript bridge for book-source rules, on Rhino rebuilt for Android 1.0.
 *
 * The Rhino jar used here is NOT the stock one. Stock 1.7.13 has
 * org.mozilla.javascript.Context referencing java.lang.invoke.* in its signatures, which
 * makes API-1 Dalvik reject the class outright (VerifyError / ClassNotFoundException).
 * The jar at api1-probe2/lib/rhino-api1.jar was recompiled from 1.7.7.2 sources against
 * the API-1 android.jar with java.beans, java.text.Normalizer and the E4X XML package
 * stripped. See API1-能力实测记录.md section 3.
 *
 * Two Rhino settings are mandatory on this platform:
 *   setOptimizationLevel(-1)  Rhino otherwise emits JVM bytecode at runtime, which
 *                             Dalvik cannot load.
 *   setLanguageVersion(170)   ES5.1 semantics, which is what book sources expect.
 *
 * A single Context/Scriptable is created lazily and reused; creating them per call is
 * slow on a 528 MHz-era device.
 */
public final class Js {

    private static final String TAG = "JianYue";

    private static Context cx;
    private static Scriptable scope;
    private static boolean unavailable;
    private static String unavailableReason;

    private Js() {
    }

    public static synchronized boolean available() {
        ensureInit();
        return !unavailable;
    }

    public static synchronized String unavailableReason() {
        ensureInit();
        return unavailableReason;
    }

    private static void ensureInit() {
        if (scope != null || unavailable) {
            return;
        }
        try {
            cx = Context.enter();
            cx.setOptimizationLevel(-1);
            cx.setLanguageVersion(170);
            scope = cx.initStandardObjects();
            // a log() helper, handy when debugging a book source on the device
            try {
                ScriptableObject.putProperty(scope, "console",
                        Context.javaToJS(new Console(), scope));
            } catch (Throwable ignored) {
            }
            Log.i(TAG, "js: Rhino initialised (interpretive mode)");
        } catch (Throwable t) {
            unavailable = true;
            unavailableReason = t.getClass().getName() + ": " + t.getMessage();
            Log.i(TAG, "js: Rhino UNAVAILABLE -> " + unavailableReason);
            try {
                Context.exit();
            } catch (Throwable ignored) {
            }
            cx = null;
            scope = null;
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

    /**
     * Run a script and return its value as a string.
     *
     * Bindings visible to the script:
     *   result    the value being operated on (may be null)
     *   src       alias of result, as Legado scripts use
     *   baseUrl   the URL the content came from
     *   book      book metadata as a JS object (may be null)
     *
     * Returns "" on any failure so a broken script degrades to empty rather than
     * taking down the whole book source.
     */
    public static String run(String script, String result, String baseUrl, String[] bookFields) {
        if (script == null || script.trim().length() == 0) {
            return "";
        }
        ensureInit();
        if (unavailable || scope == null) {
            Log.i(TAG, "js: skipped, Rhino unavailable");
            return "";
        }
        synchronized (Js.class) {
            try {
                ScriptableObject.putProperty(scope, "result", result == null ? "" : result);
                ScriptableObject.putProperty(scope, "src", result == null ? "" : result);
                ScriptableObject.putProperty(scope, "baseUrl", baseUrl == null ? "" : baseUrl);
                ScriptableObject.putProperty(scope, "cookie", "");
                ScriptableObject.putProperty(scope, "key", "");
                ScriptableObject.putProperty(scope, "page", Integer.valueOf(1));

                if (bookFields != null && bookFields.length >= 6) {
                    Scriptable b = cx.newObject(scope);
                    ScriptableObject.putProperty(b, "name", nz(bookFields[0]));
                    ScriptableObject.putProperty(b, "author", nz(bookFields[1]));
                    ScriptableObject.putProperty(b, "bookUrl", nz(bookFields[2]));
                    ScriptableObject.putProperty(b, "tocUrl", nz(bookFields[3]));
                    ScriptableObject.putProperty(b, "intro", nz(bookFields[4]));
                    ScriptableObject.putProperty(b, "kind", nz(bookFields[5]));
                    ScriptableObject.putProperty(scope, "book", b);
                }

                Object v = cx.evaluateString(scope, script, "rule", 1, null);
                if (v == null || v == Context.getUndefinedValue()) {
                    return "";
                }
                String s = Context.toString(v);
                return s == null ? "" : s;
            } catch (Throwable t) {
                Log.i(TAG, "js: script failed " + t.getClass().getName() + ": " + t.getMessage());
                return "";
            }
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** Evaluate a boolean-ish expression (used by enable/disable conditions). */
    public static boolean runBoolean(String script) {
        String r = run(script, null, null, null);
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
        return run(script, null, null, null);
    }
}

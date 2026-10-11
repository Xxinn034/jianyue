package com.jianyue.reader.util;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * The only place in 简阅 that talks to platform APIs which do not exist on API 1.
 *
 * Why it is written with reflection instead of plain calls: the project is compiled
 * against the API-1 android.jar (that is what keeps minSdk=1 honest - javac fails the
 * build the moment a newer symbol leaks into the main flow), and the dex is fed to
 * Dalvik on Android 1.0, whose verifier blows up on a method its framework does not
 * have. Everything newer therefore goes through Class.forName/getMethod here, using
 * only java.lang.reflect and API-1 types, and callers reach it through methods that
 * are safe to load everywhere.
 *
 * What this covers today:
 *   - android.os.Build.VERSION.SDK_INT      (API 4; API 1 has only the "SDK" string)
 *   - Context.checkSelfPermission           (API 23)
 *   - Activity.requestPermissions           (API 23)
 *   - Activity.onRequestPermissionsResult   (API 23; overridden without super, since
 *     the API-1 android.jar has no such method to call super on)
 *   - Activity.overridePendingTransition    (API 5; the tab bar's one-shot, direction-aware
 *     page slide. Without it the window animation style still applies, it just cannot say
 *     forward or back)
 *
 * Storage is the one runtime permission the app needs: the shelf/sources/progress live
 * in the app's private directory, but importing a book-source .json or a local book
 * reads the SD card. Before API 23 the declared permissions are granted at install
 * time, so every method here degrades to "yes, go ahead".
 */
public final class Compat {

    private static final String TAG = "JianYue";

    /** requestCode of the storage dialog; only used to recognise the callback. */
    public static final int REQ_STORAGE = 0x5101;

    /** PackageManager.PERMISSION_GRANTED, spelled out so no API-23 constant is needed. */
    private static final int PERMISSION_GRANTED = 0;

    private static final String[] STORAGE_PERMISSIONS = {
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE" };

    private static int sdkLevel = -1;
    /** Set once the platform has said it has no overridePendingTransition (API 1..4). */
    private static boolean noTransitionOverride = false;

    private Compat() { }

    /** Android API level. API 1 reports "1" through the SDK string; SDK_INT starts at API 4. */
    public static int sdk() {
        if (sdkLevel < 0) {
            int level = 0;
            try {
                Field f = Class.forName("android.os.Build$VERSION").getField("SDK_INT");
                Object v = f.get(null);
                if (v instanceof Integer) {
                    level = ((Integer) v).intValue();
                }
            } catch (Throwable ignored) {
                // API 1..3: no SDK_INT field at all
            }
            if (level <= 0) {
                try {
                    level = Integer.parseInt(Build.VERSION.SDK);
                } catch (Throwable ignored) {
                    // unparsable (defensive): treat as the oldest platform
                }
            }
            sdkLevel = level <= 0 ? 1 : level;
        }
        return sdkLevel;
    }

    /** True from API 23 on, where permissions became a runtime dialog. */
    public static boolean runtimePermissions() {
        return sdk() >= 23;
    }

    /** True when the SD card may be read/written right now. */
    public static boolean hasStorage(Context ctx) {
        if (!runtimePermissions()) {
            return true;
        }
        try {
            Method check = Class.forName("android.content.Context")
                    .getMethod("checkSelfPermission", new Class[] { String.class });
            for (int i = 0; i < STORAGE_PERMISSIONS.length; i++) {
                Object granted = check.invoke(ctx, new Object[] { STORAGE_PERMISSIONS[i] });
                if (granted instanceof Integer && ((Integer) granted).intValue() != PERMISSION_GRANTED) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            // A platform that cannot answer has no runtime model - never block an import.
            return true;
        }
    }

    /**
     * Make sure the SD card is readable, asking the user once if needed.
     *
     * Returns true when the caller may go ahead now, false when a dialog was started:
     * the caller then has to remember what it wanted to do and run it again from
     * onRequestPermissionsResult. Returns false on API 1 never.
     */
    public static boolean ensureStorage(Activity act) {
        if (!runtimePermissions() || hasStorage(act)) {
            return true;
        }
        try {
            Method request = Class.forName("android.app.Activity")
                    .getMethod("requestPermissions", new Class[] { String[].class, Integer.TYPE });
            request.invoke(act, new Object[] { STORAGE_PERMISSIONS, Integer.valueOf(REQ_STORAGE) });
            Log.i(TAG, "compat: asking for the storage permission (sdk=" + sdk() + ")");
        } catch (Throwable t) {
            Log.i(TAG, "compat: cannot ask for the storage permission: " + t);
        }
        return false;
    }

    /**
     * Make the activity transition that follows use exactly these two animations.
     *
     * The tab bar needs this because a window animation style is fixed per window and
     * therefore cannot tell "tapped the page on the right" from "tapped the page on the
     * left"; a one-shot override can. Returns false when the platform has no
     * overridePendingTransition (API 1..4), where the window animation style set by Ui
     * stays in charge - horizontal either way there, just not direction aware.
     *
     * Call it right AFTER startActivity/finish: the platform records it as "the next
     * transition this app starts".
     */
    public static boolean overrideTransition(Activity act, int enterAnim, int exitAnim) {
        if (noTransitionOverride) {
            return false;
        }
        try {
            Method m = Class.forName("android.app.Activity")
                    .getMethod("overridePendingTransition", new Class[] { Integer.TYPE, Integer.TYPE });
            m.invoke(act, new Object[] { Integer.valueOf(enterAnim), Integer.valueOf(exitAnim) });
            return true;
        } catch (Throwable t) {
            // NoSuchMethodException on API 1..4; log once, not on every tab tap.
            noTransitionOverride = true;
            Log.i(TAG, "compat: no overridePendingTransition on sdk=" + sdk() + " (" + t + ")");
            return false;
        }
    }
}

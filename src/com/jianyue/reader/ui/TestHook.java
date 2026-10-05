package com.jianyue.reader.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Host-driven test hook.
 *
 * Why this exists: API 1 has no `am force-stop`, and its shell has no `kill`, `pidof`,
 * `grep`, `awk` or even `ps` filtering - so a running app cannot be stopped from the
 * host. That matters for verification: re-launching an activity that is already on top
 * does not re-run its onCreate, and several engine paths (the self-test, the auto-import
 * and the auto-search) only run in onCreate. Without a way to get a fresh process those
 * paths simply cannot be exercised twice, which made the acceptance test flaky.
 *
 * Action: com.jianyue.reader.TEST_EXIT
 *   Finishes all activities and exits the process, so the next `am start` produces a
 *   genuine onCreate.
 *
 * This is inert during normal use: nothing in the UI sends this action.
 */
public class TestHook extends BroadcastReceiver {

    private static final String TAG = "JianYue";

    public void onReceive(Context context, Intent intent) {
        String action = (intent == null) ? null : intent.getAction();
        if ("com.jianyue.reader.TEST_EXIT".equals(action)) {
            Log.i(TAG, "testhook: exiting process on request");
            // System.exit is the only reliable way to end the process on API 1 here.
            System.exit(0);
        }
    }
}

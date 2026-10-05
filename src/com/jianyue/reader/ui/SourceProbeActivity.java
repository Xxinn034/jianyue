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

import com.jianyue.reader.net.Http;

/**
 * 站点可达性批量探测 (batch reachability probe).
 *
 * WHY THIS EXISTS
 * ---------------
 * The single hard constraint of this project is that API 1 tops out at TLS 1.0. Any site
 * that requires TLS 1.2+ simply cannot be reached, no matter how the book-source rules are
 * written. Whether a given site still accepts TLS 1.0 cannot be inferred from
 * documentation, so it has to be probed one site at a time.
 *
 * This screen reads a newline-separated URL list from a file on the device, fetches each
 * URL, and logs a one-line verdict per site. Run it before writing a book source, so no
 * effort is spent on sites this device can never reach.
 *
 * Host-driven on purpose: API 1 has no `adb shell input tap`, so there is no way to drive a
 * button from the host. Pass the list with
 *   am start -n com.jianyue.reader/.ui.SourceProbeActivity -e listPath /sdcard/candidates.txt
 *
 * Log lines (tag JianYue):
 *   probe: <url> -> HTTP <code> <bytes>B  <host>          reachable
 *   probe: <url> -> FAIL <reason>                          not reachable
 *   probe: SUMMARY ok=<n> fail=<n> total=<n>
 */
public class SourceProbeActivity extends Activity {

    private static final String TAG = "JianYue";

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.setup(this, 0xFFF7F7F7);

        final String listPath = getIntent().getStringExtra("listPath");
        if (listPath == null || listPath.length() == 0) {
            Log.i(TAG, "probe: no listPath extra, nothing to do");
            finish();
            return;
        }
        try { new java.io.File("/sdcard/probe-result.txt").delete(); } catch (Throwable ignored) { }
        final List<String> urls = readList(listPath);
        Log.i(TAG, "probe: loaded " + urls.size() + " urls from " + listPath);

        // Several worker threads: with a 3000+ host collection a serial probe is not
        // practical (each unreachable host costs a full connect timeout). Four threads cut
        // the wall time roughly fourfold while staying gentle on the emulator.
        final int threads = 4;
        Thread[] pool = new Thread[threads];
        for (int w = 0; w < threads; w++) {
            final int worker = w;
            pool[w] = new Thread(new Runnable() {
                public void run() {
                    int ok = 0;
                    int fail = 0;
                    for (int i = worker; i < urls.size(); i += threads) {
                        // Re-resolve periodically: the emulator's slirp DNS degrades over a
                        // long run, and a stale failure would be reported as an unreachable
                        // site rather than a broken resolver.
                        if (i > worker && (i / threads) % 12 == 0) {
                            Http.warmDns(urls.get(i - threads));
                        }
                        String u = urls.get(i);
                        Http.warmDns(u);
                        Http.Resp r = Http.getWithHttpFallback(u, null, null);
                        if (r != null && r.ok() && r.code < 400) {
                            ok++;
                            Log.i(TAG, "probe: OK " + u + " -> HTTP " + r.code + " "
                                    + (r.body == null ? 0 : r.body.length) + "B "
                                    + host(r.finalUrl));
                            writeResult("OK\t" + u + "\t" + r.code + "\t"
                                    + (r.body == null ? 0 : r.body.length) + "\t"
                                    + nz(r.finalUrl));
                        } else {
                            fail++;
                            Log.i(TAG, "probe: FAIL " + u + " -> " + reason(r));
                            writeResult("FAIL\t" + u + "\t" + reason(r));
                        }
                        done++;
                    }
                    synchronized (SourceProbeActivity.this) {
                        okTotal += ok;
                        failTotal += fail;
                    }
                }
            });
            pool[w].start();
        }

        // One reporter thread waits for all workers, then prints the summary.
        new Thread(new Runnable() {
            public void run() {
                for (int w = 0; w < threads; w++) {
                    try {
                        pool[w].join();
                    } catch (Throwable ignored) {
                    }
                }
                Log.i(TAG, "probe: SUMMARY ok=" + okTotal + " fail=" + failTotal
                        + " total=" + urls.size());
            }
        }).start();
    }

    /** Append one result line to /sdcard/probe-result.txt, so results survive logcat churn. */
    private static synchronized void writeResult(String line) {
        java.io.FileOutputStream out = null;
        try {
            out = new java.io.FileOutputStream("/sdcard/probe-result.txt", true);
            out.write((line + "\n").getBytes("UTF-8"));
            out.flush();
        } catch (Throwable t) {
            Log.i(TAG, "probe: cannot write result: " + t.getMessage());
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) { }
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private volatile int done;
    private int okTotal;
    private int failTotal;

    private static String reason(Http.Resp r) {
        if (r == null) {
            return "null";
        }
        if (r.error != null) {
            return r.error;
        }
        return "HTTP " + r.code;
    }

    private static String host(String url) {
        if (url == null) {
            return "";
        }
        try {
            int s = url.indexOf("://");
            s = (s < 0) ? 0 : s + 3;
            int e = url.indexOf('/', s);
            return (e < 0) ? url.substring(s) : url.substring(s, e);
        } catch (Throwable t) {
            return "";
        }
    }

    private static List<String> readList(String path) {
        List<String> out = new ArrayList<String>();
        FileInputStream in = null;
        try {
            in = new FileInputStream(new File(path));
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                String s = line.trim();
                if (s.length() == 0 || s.startsWith("#")) {
                    continue;
                }
                out.add(s);
            }
            br.close();
        } catch (Throwable t) {
            Log.i(TAG, "probe: cannot read " + path + " : " + t.getMessage());
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }
}

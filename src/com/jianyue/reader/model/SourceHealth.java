package com.jianyue.reader.model;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.jianyue.reader.util.FileUtils;
import com.jianyue.reader.util.TextCodec;

/**
 * What we learned about each book source's search, remembered across searches.
 *
 * WHY THIS EXISTS
 * ---------------
 * With a couple of hundred imported sources, most of them point at sites that no longer
 * answer. A search that walks the list in the order the sources were imported therefore
 * spends its first seconds - often its first minute - on dead sites, and the user stares at
 * an empty screen even though the handful of working sources could have answered instantly.
 *
 * The fix is ordering, not filtering: sources that answered last time are searched FIRST, so
 * the first results arrive in about one round trip, and the long tail of dead sites runs
 * afterwards while the user is already reading. Nothing is skipped behind the user's back -
 * even a source that has never worked is still tried in the same search.
 *
 * The file is tiny (one short record per source), lives next to bookSources.json, and is
 * rewritten once per search from the worker thread.
 */
public final class SourceHealth {

    private static final String TAG = "JianYue";
    private static final String FILE_NAME = "search-health.json";

    /** Cap the file so a source list that keeps growing cannot balloon it. */
    private static final int MAX_ENTRIES = 3000;

    /** A source that failed at least this often without ever succeeding is "known bad". */
    private static final int BAD_AFTER = 2;

    private static final Map<String, Rec> MAP = new HashMap<String, Rec>();
    private static File file;
    private static boolean loaded;

    /** One source's search history. */
    public static class Rec {
        public int ok;
        public int fail;
        public long lastOkMs;
        public long lastTryMs;
        public long avgMs;
    }

    private SourceHealth() {
    }

    /** Point the store at the app's private directory and load it once. */
    public static synchronized void attach(Context ctx) {
        if (file == null) {
            File dir = null;
            try {
                dir = ctx.getFilesDir();
            } catch (Throwable ignored) {
            }
            if (dir == null) {
                dir = new File("/data/data/" + ctx.getPackageName() + "/files");
            }
            if (!dir.exists()) {
                try {
                    dir.mkdirs();
                } catch (Throwable ignored) {
                }
            }
            file = new File(dir, FILE_NAME);
        }
        if (!loaded) {
            loaded = true;
            load();
        }
    }

    public static synchronized Rec get(String sourceUrl) {
        if (sourceUrl == null) {
            return null;
        }
        return MAP.get(sourceUrl);
    }

    /**
     * Sort order for a source: 0 = has answered before, 1 = never tried, 2 = tried and never
     * answered. Within group 0 the most recent success comes first.
     *
     * This is deliberately coarse. A finished search is the only thing that proves a source
     * useful, so "answered once" is the strongest signal available before spending a request.
     */
    public static int rank(BookSource s) {
        if (s == null || s.url == null) {
            return 1;
        }
        Rec r = MAP.get(s.url);
        if (r == null) {
            return 1;
        }
        if (r.ok > 0) {
            return 0;
        }
        if (r.fail >= BAD_AFTER) {
            return 2;
        }
        return 1;
    }

    /** Stable ordering used by the search: best-known sources first, name as tiebreaker. */
    public static void sortByHealth(List<BookSource> sources) {
        if (sources == null || sources.size() < 2) {
            return;
        }
        try {
            Collections.sort(sources, new Comparator<BookSource>() {
                public int compare(BookSource a, BookSource b) {
                    int ra = rank(a);
                    int rb = rank(b);
                    if (ra != rb) {
                        return ra - rb;
                    }
                    long la = lastOk(a);
                    long lb = lastOk(b);
                    if (la != lb) {
                        return (lb > la) ? 1 : -1;   // most recent success first
                    }
                    String na = a == null || a.name == null ? "" : a.name;
                    String nb = b == null || b.name == null ? "" : b.name;
                    return na.compareTo(nb);
                }
            });
        } catch (Throwable t) {
            Log.i(TAG, "health: sort failed " + t.getClass().getName());
        }
    }

    private static long lastOk(BookSource s) {
        if (s == null || s.url == null) {
            return 0;
        }
        Rec r = MAP.get(s.url);
        return r == null ? 0 : r.lastOkMs;
    }

    /**
     * Record the outcome of one source's search.
     *
     * @param reachable   the site answered at all (false = network/DNS/TLS failure)
     * @param hadResults  the search rule parsed at least one book
     * @param ms          wall time of the attempt
     *
     * A site that answers but parses nothing is NOT counted as a failure: the rules may
     * simply not know this keyword, and demoting the source would be wrong.
     */
    public static synchronized void record(String sourceUrl, boolean reachable,
                                           boolean hadResults, long ms) {
        if (sourceUrl == null || sourceUrl.length() == 0) {
            return;
        }
        Rec r = MAP.get(sourceUrl);
        if (r == null) {
            r = new Rec();
            MAP.put(sourceUrl, r);
        }
        r.lastTryMs = System.currentTimeMillis();
        if (!reachable) {
            r.fail++;
            return;
        }
        if (hadResults) {
            r.ok++;
            r.lastOkMs = r.lastTryMs;
            r.avgMs = (r.avgMs <= 0) ? ms : ((r.avgMs * 3 + ms) / 4);
        }
    }

    // ------------------------------------------------------------- persistence

    private static void load() {
        if (file == null || !file.isFile()) {
            return;
        }
        try {
            byte[] data = FileUtils.readAll(file);
            if (data == null || data.length == 0) {
                return;
            }
            JSONObject root = new JSONObject(TextCodec.decodeAuto(data, 0, data.length));
            Iterator<?> it = root.keys();
            while (it.hasNext()) {
                String key = String.valueOf(it.next());
                JSONObject o = root.optJSONObject(key);
                if (o == null) {
                    continue;
                }
                Rec r = new Rec();
                r.ok = o.optInt("ok", 0);
                r.fail = o.optInt("fail", 0);
                r.lastOkMs = o.optLong("lastOk", 0);
                r.lastTryMs = o.optLong("lastTry", 0);
                r.avgMs = o.optLong("avg", 0);
                MAP.put(key, r);
            }
            Log.i(TAG, "health: loaded " + MAP.size() + " records");
        } catch (Throwable t) {
            Log.i(TAG, "health: load failed " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /** Write the store back. Cheap; called once at the end of a search. */
    public static synchronized void save() {
        if (file == null) {
            return;
        }
        try {
            prune();
            JSONObject root = new JSONObject();
            Iterator<Map.Entry<String, Rec>> it = MAP.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Rec> e = it.next();
                Rec r = e.getValue();
                JSONObject o = new JSONObject();
                o.put("ok", r.ok);
                o.put("fail", r.fail);
                o.put("lastOk", r.lastOkMs);
                o.put("lastTry", r.lastTryMs);
                o.put("avg", r.avgMs);
                root.put(e.getKey(), o);
            }
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(file);
                fos.write(root.toString().getBytes("UTF-8"));
                fos.flush();
                Log.i(TAG, "health: saved " + MAP.size() + " records");
            } finally {
                FileUtils.close(fos);
            }
        } catch (Throwable t) {
            Log.i(TAG, "health: save failed " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /** Drop the oldest records once the store grows past MAX_ENTRIES. */
    private static void prune() {
        if (MAP.size() <= MAX_ENTRIES) {
            return;
        }
        java.util.List<String> keys = new java.util.ArrayList<String>(MAP.keySet());
        Collections.sort(keys, new Comparator<String>() {
            public int compare(String a, String b) {
                Rec ra = MAP.get(a);
                Rec rb = MAP.get(b);
                long la = ra == null ? 0 : ra.lastTryMs;
                long lb = rb == null ? 0 : rb.lastTryMs;
                if (la == lb) {
                    return 0;
                }
                return (la < lb) ? -1 : 1;
            }
        });
        int drop = MAP.size() - MAX_ENTRIES;
        for (int i = 0; i < drop && i < keys.size(); i++) {
            MAP.remove(keys.get(i));
        }
        Log.i(TAG, "health: pruned " + drop + " old records");
    }

    /** Test/debug helper: how many sources have answered before. */
    public static synchronized int knownGoodCount() {
        int n = 0;
        Iterator<Rec> it = MAP.values().iterator();
        while (it.hasNext()) {
            if (it.next().ok > 0) {
                n++;
            }
        }
        return n;
    }
}

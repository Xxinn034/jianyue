package com.jianyue.reader.model;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import com.jianyue.reader.util.FileUtils;
import com.jianyue.reader.util.TextCodec;

/**
 * Book-source persistence.
 *
 * Sources are kept as one JSON array in the app's private directory. API 1 has SQLite
 * but it is not guaranteed to open (measured: sqlite3_open_v2 failed for one package on
 * the API-1 emulator), so a file store is both simpler and more reliable here.
 *
 * Import accepts a single object or an array, which is what 阅读 exports.
 */
public class SourceStore {

    private static final String TAG = "JianYue";
    private static final String FILE_NAME = "bookSources.json";

    private final File file;
    private final List<BookSource> sources = new ArrayList<BookSource>();

    public SourceStore(Context ctx) {
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
        load();
    }

    public File file() {
        return file;
    }

    public List<BookSource> all() {
        return sources;
    }

    public List<BookSource> enabled() {
        List<BookSource> out = new ArrayList<BookSource>();
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).enabled) {
                out.add(sources.get(i));
            }
        }
        return out;
    }

    public int size() {
        return sources.size();
    }

    public BookSource get(int index) {
        return (index >= 0 && index < sources.size()) ? sources.get(index) : null;
    }

    /** Look a source up by its bookSourceUrl (used when switching sources). */
    public BookSource findByUrl(String url) {
        if (url == null || url.length() == 0) {
            return null;
        }
        int i = indexOfUrl(url);
        return (i >= 0) ? sources.get(i) : null;
    }

    // ------------------------------------------------------------------ load

    private void load() {
        sources.clear();
        if (!file.isFile()) {
            Log.i(TAG, "sourceStore: no file yet (" + file.getAbsolutePath() + ")");
            return;
        }
        byte[] data = FileUtils.readAll(file);
        if (data == null || data.length == 0) {
            return;
        }
        String json = TextCodec.decodeAuto(data, 0, data.length);
        List<BookSource> parsed = BookSource.parseFile(json);
        sources.addAll(parsed);
        Log.i(TAG, "sourceStore: loaded " + sources.size() + " sources from "
                + file.getAbsolutePath());
    }

    // ------------------------------------------------------------------ save

    private boolean persist() {
        JSONArray arr = new JSONArray();
        try {
            for (int i = 0; i < sources.size(); i++) {
                arr.put(sources.get(i).toJson());
            }
        } catch (Throwable ignored) {
        }
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            fos.write(arr.toString().getBytes("UTF-8"));
            fos.flush();
            Log.i(TAG, "sourceStore: saved " + sources.size() + " sources");
            return true;
        } catch (Throwable t) {
            Log.i(TAG, "sourceStore: save FAILED " + t.getClass().getName() + ": " + t.getMessage());
            return false;
        } finally {
            FileUtils.close(fos);
        }
    }

    // ---------------------------------------------------------------- import

    /** Result of an import attempt, so the UI can report it meaningfully. */
    public static class ImportResult {
        public int added;
        public int skipped;
        public int failed;
        public String error;
    }

    /**
     * Import from a JSON string. Duplicate URLs are updated rather than duplicated, so
     * re-importing an updated source works as expected.
     */
    public ImportResult importJson(String json) {
        ImportResult res = new ImportResult();
        if (json == null || json.trim().length() == 0) {
            res.error = "内容为空";
            return res;
        }
        List<BookSource> parsed = BookSource.parseFile(json);
        if (parsed.size() == 0) {
            res.error = "不是有效的书源 JSON（需要单个对象或对象数组）";
            return res;
        }
        for (int i = 0; i < parsed.size(); i++) {
            BookSource s = parsed.get(i);
            if (s == null || s.url == null || s.url.length() == 0) {
                res.failed++;
                continue;
            }
            int existing = indexOfUrl(s.url);
            if (existing >= 0) {
                sources.set(existing, s);
                res.skipped++;
            } else {
                sources.add(s);
                res.added++;
            }
        }
        persist();
        Log.i(TAG, "sourceStore: import added=" + res.added + " updated=" + res.skipped
                + " failed=" + res.failed);
        return res;
    }

    /** Import from a file on disk (used by the "import from /sdcard" entry). */
    public ImportResult importFile(File f) {
        ImportResult res = new ImportResult();
        byte[] data = FileUtils.readAll(f);
        if (data == null) {
            res.error = "读不到文件：" + f;
            return res;
        }
        return importJson(TextCodec.decodeAuto(data, 0, data.length));
    }

    private int indexOfUrl(String url) {
        for (int i = 0; i < sources.size(); i++) {
            if (url.equals(sources.get(i).url)) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ edit

    public boolean remove(int index) {
        if (index < 0 || index >= sources.size()) {
            return false;
        }
        sources.remove(index);
        return persist();
    }

    public boolean setEnabled(int index, boolean on) {
        if (index < 0 || index >= sources.size()) {
            return false;
        }
        sources.get(index).enabled = on;
        return persist();
    }

    public boolean clear() {
        sources.clear();
        return persist();
    }

    /** Export everything, for backup or for editing on a PC. */
    public String exportJson() {
        JSONArray arr = new JSONArray();
        try {
            for (int i = 0; i < sources.size(); i++) {
                arr.put(sources.get(i).toJson());
            }
        } catch (Throwable ignored) {
        }
        return arr.toString();
    }
}

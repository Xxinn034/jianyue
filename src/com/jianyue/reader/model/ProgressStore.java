package com.jianyue.reader.model;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import com.jianyue.reader.util.FileUtils;
import com.jianyue.reader.util.TextCodec;

/**
 * Reading progress for online books.
 *
 * A book is identified by sourceUrl + bookUrl, which is stable across sessions and
 * matches how book sources address a book. Storing the whole Book (not just a pointer)
 * means the shelf still renders when the source is offline.
 *
 * API 1 has no SQLite guarantee and no Room, so this is a JSON file.
 */
public class ProgressStore {

    private static final String TAG = "JianYue";
    private static final String FILE_NAME = "progress.json";
    private static final int MAX_ENTRIES = 200;

    /** One saved book + its position. */
    public static class Entry {
        public Books.Book book = new Books.Book();
        public int chapterIndex;
        public String chapterName = "";
        public int charOffset;          // scroll position inside the chapter
        public long updatedAt;

        public String key() {
            return book.sourceUrl + "\u0001" + book.bookUrl;
        }
    }

    private final File file;
    private final List<Entry> entries = new ArrayList<Entry>();

    public ProgressStore(Context ctx) {
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

    /** Most recently read first. */
    public List<Entry> all() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    public Entry find(String sourceUrl, String bookUrl) {
        String k = sourceUrl + "\u0001" + bookUrl;
        for (int i = 0; i < entries.size(); i++) {
            if (k.equals(entries.get(i).key())) {
                return entries.get(i);
            }
        }
        return null;
    }

    public Entry findOrCreate(Books.Book b) {
        Entry e = find(b.sourceUrl, b.bookUrl);
        if (e == null) {
            e = new Entry();
            e.book = b;
            entries.add(0, e);
        } else {
            // refresh metadata in case the source returned more detail this time
            e.book = b;
        }
        return e;
    }

    /** Save the reading position and move the entry to the front. */
    public void save(Books.Book b, int chapterIndex, String chapterName, int charOffset) {
        if (b == null || b.bookUrl == null || b.bookUrl.length() == 0) {
            return;
        }
        Entry e = find(b.sourceUrl, b.bookUrl);
        if (e == null) {
            e = new Entry();
            e.book = b;
        } else {
            entries.remove(e);
            e.book = b;
        }
        e.chapterIndex = chapterIndex;
        e.chapterName = chapterName == null ? "" : chapterName;
        e.charOffset = charOffset;
        e.updatedAt = System.currentTimeMillis();
        entries.add(0, e);
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
        persist();
    }

    public boolean remove(Entry e) {
        boolean ok = entries.remove(e);
        if (ok) {
            persist();
        }
        return ok;
    }

    public boolean removeAt(int index) {
        if (index < 0 || index >= entries.size()) {
            return false;
        }
        entries.remove(index);
        return persist();
    }

    public boolean clear() {
        entries.clear();
        return persist();
    }

    // ------------------------------------------------------------------ io

    private void load() {
        entries.clear();
        if (!file.isFile()) {
            return;
        }
        byte[] data = FileUtils.readAll(file);
        if (data == null || data.length == 0) {
            return;
        }
        try {
            JSONArray arr = new JSONArray(TextCodec.decodeAuto(data, 0, data.length));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                Entry e = new Entry();
                e.book.name = o.optString("name", "");
                e.book.author = o.optString("author", "");
                e.book.kind = o.optString("kind", "");
                e.book.intro = o.optString("intro", "");
                e.book.wordCount = o.optString("wordCount", "");
                e.book.bookUrl = o.optString("bookUrl", "");
                e.book.tocUrl = o.optString("tocUrl", "");
                e.book.sourceName = o.optString("sourceName", "");
                e.book.sourceUrl = o.optString("sourceUrl", "");
                e.chapterIndex = o.optInt("chapterIndex", 0);
                e.chapterName = o.optString("chapterName", "");
                e.charOffset = o.optInt("charOffset", 0);
                e.updatedAt = o.optLong("updatedAt", 0L);
                if (e.book.bookUrl != null && e.book.bookUrl.length() > 0) {
                    entries.add(e);
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "progressStore: load failed " + t.getClass().getName() + ": " + t.getMessage());
        }
        Log.i(TAG, "progressStore: loaded " + entries.size() + " entries");
    }

    private boolean persist() {
        JSONArray arr = new JSONArray();
        try {
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                JSONObject o = new JSONObject();
                o.put("name", e.book.name);
                o.put("author", e.book.author);
                o.put("kind", e.book.kind);
                o.put("intro", e.book.intro);
                o.put("wordCount", e.book.wordCount);
                o.put("bookUrl", e.book.bookUrl);
                o.put("tocUrl", e.book.tocUrl);
                o.put("sourceName", e.book.sourceName);
                o.put("sourceUrl", e.book.sourceUrl);
                o.put("chapterIndex", e.chapterIndex);
                o.put("chapterName", e.chapterName);
                o.put("charOffset", e.charOffset);
                o.put("updatedAt", e.updatedAt);
                arr.put(o);
            }
        } catch (Throwable ignored) {
        }
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            fos.write(arr.toString().getBytes("UTF-8"));
            fos.flush();
            return true;
        } catch (Throwable t) {
            Log.i(TAG, "progressStore: save failed " + t.getClass().getName() + ": " + t.getMessage());
            return false;
        } finally {
            FileUtils.close(fos);
        }
    }
}

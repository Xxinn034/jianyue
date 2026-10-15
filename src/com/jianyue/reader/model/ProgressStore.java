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
 *
 * ONE INSTANCE PER PROCESS (see get): the shelf, the detail screen, the reader and the
 * local-import page all read and write this one file. While each activity built its own
 * copy, a write made on one screen was invisible to the others - 加入书架 from the detail
 * screen or the reader only appeared on the shelf after the app was killed and restarted,
 * which is the bug this replaced. Same reasoning as "one entry point per semantics": one
 * file, one in-memory copy, one place to look.
 */
public class ProgressStore {

    private static final String TAG = "JianYue";
    private static final String FILE_NAME = "progress.json";
    private static final int MAX_ENTRIES = 200;

    /** The single in-memory copy; see the class comment. */
    private static ProgressStore instance;

    /** One saved book + its position. */
    public static class Entry {
        public Books.Book book = new Books.Book();
        public int chapterIndex;
        public String chapterName = "";
        public int charOffset;          // scroll position inside the chapter
        public long updatedAt;
        /**
         * Whether the book is on the 书架 page.
         *
         * Reading a book is not the same thing as keeping it on the shelf: an imported local
         * file stays on the 本地导入 page (which is the list of files the user owns) until they
         * long-press it and pick 加入书架. Online books keep the old behaviour - reading one
         * puts it on the shelf - which is why the default is true.
         */
        public boolean shelf = true;

        public String key() {
            return keyOf(book.sourceUrl, book.bookUrl);
        }
    }

    /**
     * The identity of a book: one definition, used by the store and by every selection list
     * that has to name a row (the shelf, the local page). Two spellings of this key would mean
     * two different books for the same file.
     */
    public static String keyOf(String sourceUrl, String bookUrl) {
        return sourceUrl + "\u0001" + bookUrl;
    }

    private final File file;
    private final List<Entry> entries = new ArrayList<Entry>();

    /**
     * The shelf store of this process. Every caller must come through here.
     *
     * Deliberately not a public constructor any more: a second instance is a second
     * in-memory truth, and the visible symptom of that was a book that had just been added
     * to the shelf not showing up there until the next app start.
     */
    public static synchronized ProgressStore get(Context ctx) {
        if (instance == null) {
            instance = new ProgressStore(ctx);
        }
        return instance;
    }

    /** Only reads the Context's files dir, so no Activity is retained. */
    private ProgressStore(Context ctx) {
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

    /** Most recently read first: everything, on the shelf or not. */
    public List<Entry> all() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    /**
     * The books the 书架 page shows: the ones the user kept there.
     *
     * A fresh list each call (the shelf page rebuilds its adapter data on every reload), so
     * callers must not expect to mutate the store through it.
     */
    public List<Entry> shelf() {
        List<Entry> out = new ArrayList<Entry>();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).shelf) {
                out.add(entries.get(i));
            }
        }
        return out;
    }

    public int shelfSize() {
        int n = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).shelf) {
                n++;
            }
        }
        return n;
    }

    public Entry find(String sourceUrl, String bookUrl) {
        String k = keyOf(sourceUrl, bookUrl);
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

    /**
     * Save the reading position and move the entry to the front.
     *
     * A brand new entry is put on the shelf when the book is an ONLINE one (reading a
     * networked book is how it gets there, as before) and kept off it when the book is a local
     * import - that one lives on the 本地导入 page until the user asks for it here. An existing
     * entry keeps whatever the user decided about it.
     */
    public void save(Books.Book b, int chapterIndex, String chapterName, int charOffset) {
        if (b == null || b.bookUrl == null || b.bookUrl.length() == 0) {
            return;
        }
        Entry e = find(b.sourceUrl, b.bookUrl);
        if (e == null) {
            e = new Entry();
            e.book = b;
            e.shelf = !LocalStore.isLocalSource(b.sourceUrl);
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

    /**
     * Put a book on the shelf (the 加入书架 action), keeping its reading position.
     *
     * Returns the entry, so callers can report which book moved.
     */
    public Entry addToShelf(Books.Book b) {
        if (b == null || b.bookUrl == null || b.bookUrl.length() == 0) {
            return null;
        }
        Entry e = findOrCreate(b);
        e.shelf = true;
        e.updatedAt = System.currentTimeMillis();
        persist();
        return e;
    }

    /** Take a book off the 书架 page without losing where the user was in it. */
    public boolean setShelf(Entry e, boolean on) {
        if (e == null || e.shelf == on) {
            return false;
        }
        e.shelf = on;
        e.updatedAt = System.currentTimeMillis();
        return persist();
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
                // Entries written before the shelf flag existed have no "shelf" key. They were
                // all on the shelf back then, EXCEPT the local imports, which used to be added
                // by the import itself - those now belong to the 本地导入 page only, so the
                // default depends on the kind of book.
                e.shelf = o.optBoolean("shelf", !LocalStore.isLocalSource(e.book.sourceUrl));
                if (e.book.bookUrl != null && e.book.bookUrl.length() > 0) {
                    entries.add(e);
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "progressStore: load failed " + t.getClass().getName() + ": " + t.getMessage());
        }
        Log.i(TAG, "progressStore: loaded " + entries.size() + " entries ("
                + shelfSize() + " on the shelf)");
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
                o.put("shelf", e.shelf);
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

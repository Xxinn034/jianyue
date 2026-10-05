package com.jianyue.reader.model;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

import com.jianyue.reader.util.EpubExtractor;
import com.jianyue.reader.util.FileUtils;
import com.jianyue.reader.util.TextCodec;
import com.jianyue.reader.util.TextSplitter;

/**
 * 本地书架 (the local-import library).
 *
 * A book imported from the SD card is COPIED into the app's own directory and cut into
 * chapters once, at import time:
 *
 *   files/local/index.json   metadata + one (name, byte offset, byte length) row per chapter
 *   files/local/L123.dat     every chapter's text, UTF-8, back to back
 *
 * Reading chapter N is then a seek + one read, so a 5 MB novel opens instantly and the app
 * never decodes the whole file again. That matters on API 1: the heap is small, and
 * re-scanning megabytes of GBK text per page turn would be both slow and fragile.
 *
 * Copying rather than referencing the SD card also means the shelf keeps working after the
 * card is unmounted or the file is moved, which is the behaviour people expect from an
 * "imported" book.
 *
 * Supported: .txt (UTF-8 / GBK / UTF-16), .epub (2.0 and 3.0), .html / .htm.
 *
 * API-1 notes: no try-with-resources, no String.isEmpty(), no java.nio, no SQLite.
 */
public class LocalStore {

    public static final String SOURCE_URL = "local://local";
    public static final String SOURCE_NAME = "本地";

    private static final String TAG = "JianYue";
    private static final String DIR_NAME = "local";
    private static final String INDEX_NAME = "index.json";

    /**
     * Refuse to import anything bigger. A whole novel is 1-5 MB; the import holds the
     * decoded text in memory for the length of one pass, and an API-1 device has a very
     * small heap, so this is a guard rail rather than a real limit.
     */
    private static final long MAX_IMPORT_BYTES = 12L * 1024L * 1024L;

    /** An epub document longer than this is split further by the chapter-heading rules. */
    private static final int EPUB_SPLIT_ABOVE = 20000;

    /** One chapter of a local book: a byte range inside the book's .dat file. */
    public static class Chapter {
        public String name = "";
        public int offset;
        public int length;
    }

    /** An imported book. */
    public static class LocalBook {
        public String id = "";
        public String title = "";
        public String author = "";
        public String format = "";        // txt / epub / html
        public String origin = "";        // where it was imported from
        public long addedAt;
        public List<Chapter> chapters = new ArrayList<Chapter>();

        public int size() {
            return chapters.size();
        }
    }

    public static class ImportResult {
        public LocalBook book;
        public String error;

        public boolean ok() {
            return book != null;
        }
    }

    private final File dir;
    private final List<LocalBook> books = new ArrayList<LocalBook>();
    /** Byte cursor while one import writes its .dat file (single-threaded, reset per import). */
    private long writePos;

    public LocalStore(Context ctx) {
        File base = null;
        try {
            base = ctx.getFilesDir();
        } catch (Throwable ignored) {
        }
        if (base == null) {
            base = new File("/data/data/" + ctx.getPackageName() + "/files");
        }
        dir = new File(base, DIR_NAME);
        if (!dir.isDirectory()) {
            try {
                dir.mkdirs();
            } catch (Throwable ignored) {
            }
        }
        load();
    }

    public File dir() {
        return dir;
    }

    public static boolean isLocalSource(String sourceUrl) {
        return SOURCE_URL.equals(sourceUrl);
    }

    public List<LocalBook> all() {
        return books;
    }

    public int size() {
        return books.size();
    }

    public LocalBook find(String id) {
        if (id == null) {
            return null;
        }
        for (int i = 0; i < books.size(); i++) {
            if (id.equals(books.get(i).id)) {
                return books.get(i);
            }
        }
        return null;
    }

    // ------------------------------------------------------------ importing

    public ImportResult importFile(File src) {
        ImportResult r = new ImportResult();
        if (src == null || !src.isFile()) {
            r.error = "文件不存在";
            return r;
        }
        String fileName = src.getName();
        long bytes = src.length();
        if (bytes <= 0) {
            r.error = "文件是空的";
            return r;
        }
        if (bytes > MAX_IMPORT_BYTES) {
            r.error = "文件太大：" + FileUtils.sizeText(bytes)
                    + "（上限 " + FileUtils.sizeText(MAX_IMPORT_BYTES) + "）";
            return r;
        }

        LocalBook b = new LocalBook();
        b.origin = src.getAbsolutePath();
        b.addedAt = System.currentTimeMillis();
        b.id = newId();
        b.format = extensionOf(fileName);
        splitNameAuthor(b, fileName);
        File dat = datFile(b.id);
        Log.i(TAG, "local: import file=" + src.getAbsolutePath() + " size=" + bytes
                + " format=" + b.format + " id=" + b.id);

        long t0 = System.currentTimeMillis();
        boolean wrote = false;
        try {
            if (FileUtils.isEpub(fileName)) {
                wrote = writeEpub(src, b, dat);
            } else if (isHtml(fileName)) {
                wrote = writeHtml(src, b, dat);
            } else {
                wrote = writeText(src, b, dat);
            }
        } catch (OutOfMemoryError oom) {
            r.error = "内存不足，这个文件太大";
        } catch (Throwable t) {
            r.error = "导入失败：" + t.getClass().getSimpleName() + " " + t.getMessage();
        }
        if (!wrote || b.chapters.size() == 0) {
            FileUtils.deleteTree(dat);
            if (r.error == null) {
                r.error = "没能从这个文件里读出正文";
            }
            return r;
        }
        books.add(0, b);
        persist();
        Log.i(TAG, "local: imported " + b.id + " '" + b.title + "' format=" + b.format
                + " chapters=" + b.chapters.size() + " dat=" + dat.length()
                + " bytes in " + (System.currentTimeMillis() - t0) + "ms");
        r.book = b;
        return r;
    }

    /** Decode a plain text file and cut it into chapters. */
    private boolean writeText(File src, LocalBook b, File dat) throws IOException {
        byte[] data = FileUtils.readAll(src);
        if (data == null) {
            return false;
        }
        String text = normalize(TextCodec.decodeAuto(data, 0, data.length));
        data = null;
        List<TextSplitter.Seg> segs = TextSplitter.split(text);
        if (segs.size() == 0) {
            return false;
        }
        writePos = 0;
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(dat);
            int auto = 0;
            for (int i = 0; i < segs.size(); i++) {
                TextSplitter.Seg s = segs.get(i);
                auto = append(out, b, clampName(s.name), text.substring(s.start, s.end), auto);
            }
        } finally {
            FileUtils.close(out);
        }
        return true;
    }

    /** Flatten an .html file and cut it into chapters the same way as a .txt. */
    private boolean writeHtml(File src, LocalBook b, File dat) throws IOException {
        byte[] data = FileUtils.readAll(src);
        if (data == null) {
            return false;
        }
        String html = TextCodec.decodeAuto(data, 0, data.length);
        String title = EpubExtractor.tagText(html, "title");
        if (title.length() > 0 && title.length() <= 60) {
            b.title = title;
        }
        String text = normalize(EpubExtractor.htmlToText(html));
        List<TextSplitter.Seg> segs = TextSplitter.split(text);
        if (segs.size() == 0) {
            return false;
        }
        writePos = 0;
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(dat);
            int auto = 0;
            for (int i = 0; i < segs.size(); i++) {
                TextSplitter.Seg s = segs.get(i);
                auto = append(out, b, clampName(s.name), text.substring(s.start, s.end), auto);
            }
        } finally {
            FileUtils.close(out);
        }
        return true;
    }

    /** One chapter per spine document; a huge document is split further. */
    private boolean writeEpub(File src, LocalBook b, File dat) throws IOException {
        EpubExtractor.Epub ep = EpubExtractor.extract(src);
        if (ep == null || ep.sections.size() == 0) {
            return false;
        }
        if (ep.title.length() > 0) {
            b.title = ep.title;
        }
        if (ep.author.length() > 0) {
            b.author = ep.author;
        }
        writePos = 0;
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(dat);
            int auto = 0;
            for (int i = 0; i < ep.sections.size(); i++) {
                EpubExtractor.Section s = ep.sections.get(i);
                if (s.text.length() > EPUB_SPLIT_ABOVE) {
                    List<TextSplitter.Seg> segs = TextSplitter.split(s.text);
                    for (int j = 0; j < segs.size(); j++) {
                        TextSplitter.Seg seg = segs.get(j);
                        auto = append(out, b, clampName(seg.name),
                                s.text.substring(seg.start, seg.end), auto);
                    }
                } else {
                    auto = append(out, b, clampName(s.name), s.text, auto);
                }
            }
        } finally {
            FileUtils.close(out);
        }
        return true;
    }

    /**
     * Write one chapter and record its byte range. Chapter numbering for the fallback
     * (heading-less) case is done here, across the whole book, so the numbers do not restart
     * per epub document.
     */
    private int append(FileOutputStream out, LocalBook b, String name, String text, int auto)
            throws IOException {
        if (text == null || text.length() == 0) {
            return auto;
        }
        byte[] data = text.getBytes("UTF-8");
        Chapter c = new Chapter();
        if (name == null || name.length() == 0) {
            auto++;
            c.name = "第 " + auto + " 节";
        } else {
            c.name = name;
        }
        c.offset = (int) writePos;
        c.length = data.length;
        out.write(data);
        writePos += data.length;
        b.chapters.add(c);
        return auto;
    }

    // ------------------------------------------------------------- reading

    /** The chapter list, in the shape the reader already understands. */
    public List<Books.Chapter> toc(LocalBook b) {
        List<Books.Chapter> out = new ArrayList<Books.Chapter>();
        if (b == null) {
            return out;
        }
        for (int i = 0; i < b.chapters.size(); i++) {
            Books.Chapter c = new Books.Chapter();
            c.index = i;
            c.name = b.chapters.get(i).name;
            c.url = b.id + "#" + i;
            out.add(c);
        }
        return out;
    }

    /** Chapter text, straight out of the .dat file. */
    public String readChapter(LocalBook b, int index) {
        if (b == null || index < 0 || index >= b.chapters.size()) {
            return "";
        }
        Chapter c = b.chapters.get(index);
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(datFile(b.id), "r");
            raf.seek(c.offset);
            byte[] buf = new byte[c.length];
            raf.readFully(buf);
            return TextCodec.decodeUtf8(buf, 0, buf.length, false);
        } catch (Throwable t) {
            Log.i(TAG, "local: read chapter " + index + " failed " + t.getClass().getName()
                    + ": " + t.getMessage());
            return "读取本地正文失败：" + t.getMessage();
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Forget a book and delete its cached text. */
    public boolean remove(LocalBook b) {
        if (b == null) {
            return false;
        }
        books.remove(b);
        FileUtils.deleteTree(datFile(b.id));
        persist();
        Log.i(TAG, "local: removed " + b.id + " remaining=" + books.size());
        return true;
    }

    // ------------------------------------------------------------------- io

    private File datFile(String id) {
        return new File(dir, id + ".dat");
    }

    private String newId() {
        String id = "L" + System.currentTimeMillis();
        while (find(id) != null || datFile(id).exists()) {
            id = id + "x";
        }
        return id;
    }

    private void load() {
        books.clear();
        File idx = new File(dir, INDEX_NAME);
        if (!idx.isFile()) {
            return;
        }
        byte[] data = FileUtils.readAll(idx);
        if (data == null || data.length == 0) {
            return;
        }
        try {
            JSONArray arr = new JSONArray(TextCodec.decodeUtf8(data, 0, data.length, true));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                LocalBook b = new LocalBook();
                b.id = o.optString("id", "");
                b.title = o.optString("title", "");
                b.author = o.optString("author", "");
                b.format = o.optString("format", "");
                b.origin = o.optString("origin", "");
                b.addedAt = o.optLong("addedAt", 0L);
                JSONArray cs = o.optJSONArray("c");
                if (cs != null) {
                    for (int j = 0; j < cs.length(); j++) {
                        JSONArray one = cs.optJSONArray(j);
                        if (one == null) {
                            continue;
                        }
                        Chapter c = new Chapter();
                        c.name = one.optString(0, "");
                        c.offset = (int) one.optLong(1, 0L);
                        c.length = (int) one.optLong(2, 0L);
                        b.chapters.add(c);
                    }
                }
                if (b.id.length() > 0 && b.chapters.size() > 0) {
                    books.add(b);
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "local: index load failed " + t.getClass().getName() + ": " + t.getMessage());
        }
        Log.i(TAG, "local: loaded " + books.size() + " imported books");
    }

    private boolean persist() {
        JSONArray arr = new JSONArray();
        try {
            for (int i = 0; i < books.size(); i++) {
                LocalBook b = books.get(i);
                JSONObject o = new JSONObject();
                o.put("id", b.id);
                o.put("title", b.title);
                o.put("author", b.author);
                o.put("format", b.format);
                o.put("origin", b.origin);
                o.put("addedAt", b.addedAt);
                JSONArray cs = new JSONArray();
                for (int j = 0; j < b.chapters.size(); j++) {
                    Chapter c = b.chapters.get(j);
                    JSONArray one = new JSONArray();
                    one.put(c.name);
                    one.put(c.offset);
                    one.put(c.length);
                    cs.put(one);
                }
                o.put("c", cs);
                arr.put(o);
            }
        } catch (Throwable t) {
            Log.i(TAG, "local: index build failed " + t.getMessage());
        }
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(new File(dir, INDEX_NAME));
            fos.write(arr.toString().getBytes("UTF-8"));
            fos.flush();
            return true;
        } catch (Throwable t) {
            Log.i(TAG, "local: index save failed " + t.getClass().getName() + ": " + t.getMessage());
            return false;
        } finally {
            FileUtils.close(fos);
        }
    }

    // ------------------------------------------------------------- helpers

    /** Normalise line endings and drop a BOM; chapter splitting works on '\n'. */
    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        if (s.length() > 0 && s.charAt(0) == '\uFEFF') {
            s = s.substring(1);
        }
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String clampName(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim().replace('\n', ' ');
        if (t.length() > 60) {
            t = t.substring(0, 60);
        }
        return t;
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return "txt";
        }
        return fileName.substring(dot + 1).toLowerCase();
    }

    private static boolean isHtml(String fileName) {
        String n = fileName.toLowerCase();
        return n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".xhtml");
    }

    private static boolean hasDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                return true;
            }
        }
        return false;
    }

    /**
     * Guess the book title and author from the file name: "书名 - 作者.txt",
     * "《书名》作者.txt", "书名(www.site.com).txt".
     */
    private static void splitNameAuthor(LocalBook b, String fileName) {
        String base = fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        base = base.replace('《', ' ').replace('》', ' ').replace('_', ' ').trim();

        String title = base;
        String author = "";
        int dash = -1;
        for (int i = base.length() - 1; i > 0; i--) {
            char c = base.charAt(i);
            if (c == '-' || c == '－' || c == '—' || c == '–') {
                dash = i;
                break;
            }
        }
        if (dash > 0 && base.length() - dash <= 14 && !hasDigit(base.substring(dash + 1))) {
            title = base.substring(0, dash).trim();
            author = stripTail(base.substring(dash + 1).trim());
        }
        if (author.startsWith("作者")) {
            author = author.substring(2);
            if (author.startsWith(":") || author.startsWith("：")) {
                author = author.substring(1);
            }
            author = author.trim();
        }
        b.title = stripTail(title).trim();
        b.author = author;
        if (b.title.length() == 0) {
            b.title = base;
        }
    }

    /** Remove trailing bracketed groups, e.g. "(全本)" / "[www.xx.com]". */
    private static String stripTail(String s) {
        String out = s == null ? "" : s.trim();
        for (int guard = 0; guard < 5; guard++) {
            int len = out.length();
            if (len == 0) {
                break;
            }
            char last = out.charAt(len - 1);
            char open;
            if (last == ')' || last == '）') {
                open = (last == ')') ? '(' : '（';
            } else if (last == ']' || last == '】') {
                open = (last == ']') ? '[' : '【';
            } else {
                break;
            }
            int at = out.lastIndexOf(open);
            if (at <= 0) {
                break;
            }
            out = out.substring(0, at).trim();
        }
        return out;
    }
}

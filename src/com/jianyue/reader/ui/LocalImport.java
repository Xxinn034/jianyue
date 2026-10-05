package com.jianyue.reader.ui;

import android.app.Activity;
import android.util.Log;
import android.widget.Toast;

import java.io.File;

import com.jianyue.reader.model.Books;
import com.jianyue.reader.model.LocalStore;
import com.jianyue.reader.model.ProgressStore;

/**
 * Import one local book file (txt / epub / html): copy it into the app, cut it into chapters
 * ONCE, put it on the shelf and open it.
 *
 * One entry point on purpose. There are two callers - the 「本地导入」 page's + button and
 * MainActivity's host-driven `importBook` extra - and they must behave identically, otherwise
 * the host-driven verification would be testing a code path the user never takes.
 *
 * The work runs on a WORKER thread: decoding a whole novel takes a noticeable moment on an
 * API-1 device, and doing it inline would ANR the page. The UI is only touched again through
 * the callback, which is posted back to the UI thread.
 */
final class LocalImport {

    private static final String TAG = "JianYue";

    /** Import outcome, on the UI thread. */
    interface Done {
        /** The book is on the shelf; the caller decides whether to open it. */
        void onImported(Books.Book book);

        void onFailed(String error);
    }

    private LocalImport() {
    }

    static void start(final Activity a, final ProgressStore store, final LocalStore local,
                      final String path, final Done done) {
        if (path == null || path.length() == 0) {
            return;
        }
        toast(a, "正在导入…");
        Log.i(TAG, "import: request " + path + " exists=" + new File(path).isFile()
                + " size=" + new File(path).length());
        new Thread(new Runnable() {
            public void run() {
                final LocalStore.ImportResult r = local.importFile(new File(path));
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        if (!r.ok()) {
                            Log.i(TAG, "import: failed " + r.error);
                            done.onFailed(r.error);
                            return;
                        }
                        final LocalStore.LocalBook lb = r.book;
                        Books.Book b = new Books.Book();
                        b.name = lb.title;
                        b.author = lb.author;
                        b.sourceUrl = LocalStore.SOURCE_URL;
                        b.bookUrl = lb.id;
                        b.sourceName = LocalStore.SOURCE_NAME + " · " + lb.format;
                        store.save(b, 0, lb.chapters.get(0).name, 0);
                        Log.i(TAG, "import: added '" + lb.title + "' id=" + lb.id
                                + " format=" + lb.format + " chapters=" + lb.size());
                        done.onImported(b);
                    }
                });
            }
        }).start();
    }

    static void toast(Activity a, String s) {
        Toast.makeText(a, s, Toast.LENGTH_SHORT).show();
    }
}

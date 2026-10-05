package com.jianyue.reader.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import com.jianyue.reader.model.Books;
import com.jianyue.reader.model.LocalStore;
import com.jianyue.reader.model.ProgressStore;

/**
 * 书架 (shelf) - tab 1 of 3.
 *
 * Lists every book the user has started reading, most recent first, local imports included.
 * No cover art and no grid mode, by design.
 *
 * There is no in-app title bar: the system already renders the app label above the activity,
 * so an extra strip just wasted vertical space (and duplicated the name). The bottom strip is
 * the shared tab bar; importing a local file now lives on the 本地导入 page, so this screen is
 * search box + shelf + tab bar and nothing else.
 *
 * API-1 notes: ListView + BaseAdapter only (no RecyclerView), no runtime permissions,
 * long-press for the per-book menu.
 */
public class MainActivity extends Activity {

    private static final String TAG = "JianYue";

    private ProgressStore store;
    private LocalStore local;
    private ListView list;
    private TextView empty;
    private BookRowAdapter adapter;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // no title bar and no grey band above the content; see Ui
        Ui.setup(this, 0xFFF7F7F7);
        // no setTitle(): the system header would show it and the layout has no title strip
        setContentView(com.jianyue.reader.R.layout.act_shelf);

        store = new ProgressStore(this);
        local = new LocalStore(this);
        list = (ListView) findViewById(com.jianyue.reader.R.id.shelf_list);
        empty = (TextView) findViewById(com.jianyue.reader.R.id.shelf_empty);

        adapter = new BookRowAdapter();
        list.setAdapter(adapter);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView parent, View view, int position, long id) {
                openBook(store.all().get(position));
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView parent, View view, int position, long id) {
                itemMenu(position);
                return true;
            }
        });

        // bottom bar: 书架 (this page) / 书源 / 本地导入
        Ui.tabs(this, 0);

        // Search starts from the box at the top of the shelf rather than from a separate screen.
        // The trigger is the magnifier icon next to the box (an ImageView now, not the red
        // "搜索" pill) but the id and the click path are unchanged.
        // It hands the keyword to SearchActivity, which owns the source list and the parallel
        // search, so the shelf stays a shelf.
        final EditText searchInput = (EditText) findViewById(
                com.jianyue.reader.R.id.shelf_search_input);
        // NOTE: API 1's TextView has no setOnEditorActionListener, and the whole
        // android.view.inputmethod package does not exist (there is no soft-keyboard
        // framework), so Enter in the box cannot be intercepted and the keyboard cannot be
        // hidden programmatically. The magnifier is the only way to submit.
        findViewById(com.jianyue.reader.R.id.shelf_search_go).setOnClickListener(
                new View.OnClickListener() {
                    public void onClick(View v) {
                        doSearch(searchInput);
                    }
                });
        reload();

        // Host-driven import: `-e importBook /sdcard/book.txt` runs exactly the same code
        // path as the + button on the 本地导入 page. API 1 has no `adb shell input tap`, so
        // this is the only way to verify importing from the host.
        if (getIntent().hasExtra("importBook")) {
            importLocalFile(getIntent().getStringExtra("importBook"));
        }

        // Host-driven layout check: API 1 has no screencap, so the sheet cannot be
        // inspected visually. This logs each key view's real bounds after layout, which is
        // enough to catch the failure modes that matter (a zero-width search box, a bar
        // pushed off screen, the list given no height).
        if (getIntent().hasExtra("layoutDump")) {
            final View rootView = findViewById(com.jianyue.reader.R.id.shelf_list).getRootView();
            // postDelayed, not post: View.post() can run BEFORE the layout traversal, and a
            // pre-layout dump reports every view as 0x0 (which looks like a broken sheet).
            rootView.postDelayed(new Runnable() {
                public void run() {
                    dumpLayout(rootView);
                }
            }, 800L);
        }
    }

    /** Log the bounds of the shelf's key views. See the layoutDump extra above. */
    private void dumpLayout(View rootView) {
        Log.i(TAG, "layout: root=" + rootView.getWidth() + "x" + rootView.getHeight());
        Ui.dump(rootView, com.jianyue.reader.R.id.shelf_search_input, "search_input");
        Ui.dump(rootView, com.jianyue.reader.R.id.shelf_search_go, "search_go");
        Ui.dump(rootView, com.jianyue.reader.R.id.shelf_list, "list");
        Ui.dump(rootView, com.jianyue.reader.R.id.shelf_empty, "empty");
        Ui.dumpTabs(rootView, 0);
    }

    /** Launch the search screen with whatever is in the shelf's search box. */
    private void doSearch(EditText input) {
        String kw = input.getText().toString().trim();
        if (kw.length() == 0) {
            Toast.makeText(this, "请输入书名", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent i = new Intent(this, SearchActivity.class);
        i.putExtra("keyword", kw);
        startActivity(i);
    }

    protected void onResume() {
        super.onResume();
        if (store != null) {
            reload();
        }
    }

    private void reload() {
        List<ProgressStore.Entry> all = store.all();
        adapter.notifyDataSetChanged();
        if (all.size() == 0) {
            empty.setVisibility(View.VISIBLE);
            list.setVisibility(View.GONE);
        } else {
            empty.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
        }
        Log.i("JianYue", "shelf: " + all.size() + " books");
    }

    private void openBook(ProgressStore.Entry e) {
        if (e == null || e.book == null) {
            return;
        }
        Ui.openReader(this, e.book, e.chapterIndex);
    }

    // ------------------------------------------------------------ local import

    /**
     * Put a local file on the shelf (host-driven entry point; the UI entry is the + on the
     * 本地导入 page). The import itself lives in LocalImport so both callers agree.
     */
    private void importLocalFile(String path) {
        LocalImport.start(this, store, local, path, new LocalImport.Done() {
            public void onImported(Books.Book b) {
                reload();
                toast("已导入《" + b.name + "》");
                Ui.openReader(MainActivity.this, b, 0);
            }

            public void onFailed(String error) {
                toast("导入失败：" + error);
            }
        });
    }

    /** Removing a local book also frees its cached text; an online book has no local copy. */
    private void forget(ProgressStore.Entry e) {
        if (e != null && e.book != null && LocalStore.isLocalSource(e.book.sourceUrl)) {
            LocalStore.LocalBook lb = local.find(e.book.bookUrl);
            if (lb != null) {
                local.remove(lb);
            }
        }
        store.remove(e);
    }

    private void itemMenu(final int position) {
        final ProgressStore.Entry e = store.all().get(position);
        final boolean isLocal = LocalStore.isLocalSource(e.book.sourceUrl);
        final String[] items = isLocal
                ? new String[]{"查看目录", "删除本地书（含缓存）"}
                : new String[]{"查看目录", "从书架移除"};
        new AlertDialog.Builder(this)
                .setTitle(e.book.name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            openBook(e);
                        } else {
                            forget(e);
                            reload();
                            toast(isLocal ? "已删除本地书" : "已移除");
                        }
                    }
                })
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** Shelf row adapter. */
    private class BookRowAdapter extends BaseAdapter {
        public int getCount() {
            return store.all().size();
        }

        public Object getItem(int position) {
            return store.all().get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(com.jianyue.reader.R.layout.row_book, null);
            }
            TextView title = (TextView) v.findViewById(com.jianyue.reader.R.id.row_title);
            TextView sub = (TextView) v.findViewById(com.jianyue.reader.R.id.row_sub);
            ProgressStore.Entry e = store.all().get(position);
            title.setText(e.book.name);

            StringBuilder sb = new StringBuilder();
            if (e.chapterName != null && e.chapterName.length() > 0) {
                sb.append("读到 ").append(e.chapterName);
            } else {
                sb.append("第 ").append(e.chapterIndex + 1).append(" 章");
            }
            if (e.book.author != null && e.book.author.length() > 0) {
                sb.append("  |  ").append(e.book.author);
            }
            if (e.book.sourceName != null && e.book.sourceName.length() > 0) {
                sb.append("  |  ").append(e.book.sourceName);
            }
            sub.setText(sb.toString());
            return v;
        }
    }
}

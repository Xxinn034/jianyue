package com.jianyue.reader.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import com.jianyue.reader.R;
import com.jianyue.reader.model.Books;
import com.jianyue.reader.model.LocalStore;
import com.jianyue.reader.model.ProgressStore;

/**
 * 本地导入 (local imports) - tab 3 of 3.
 *
 * Only books whose sourceUrl is local://local are listed; online books stay on the 书架 page.
 * The + at the right of the top bar picks a txt / epub / html file off the card, and the
 * import itself is LocalImport - the same code the host-driven `importBook` entry uses.
 *
 * This page exists because a local file is a different kind of thing from a networked book:
 * it has no book source behind it, cannot be re-fetched, and is the part of the library the
 * user owns. 开源阅读 gives it its own entry, and so does this app.
 *
 * API-1 notes: ListView + BaseAdapter only, no runtime permissions, long-press for the
 * per-book menu.
 */
public class LocalActivity extends Activity {

    private static final String TAG = "JianYue";

    /** requestCode for the file picker behind the + button. */
    private static final int REQ_PICK = 3001;

    private ProgressStore store;
    private LocalStore local;
    private ListView list;
    private TextView empty;
    private TextView count;
    private LocalAdapter adapter;

    /** The local books currently shown; rebuilt from the shelf on every reload. */
    private final List<ProgressStore.Entry> shown = new ArrayList<ProgressStore.Entry>();

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.setup(this, 0xFFF7F7F7);
        setContentView(R.layout.act_local);

        store = new ProgressStore(this);
        local = new LocalStore(this);
        list = (ListView) findViewById(R.id.local_list);
        empty = (TextView) findViewById(R.id.local_empty);
        count = (TextView) findViewById(R.id.local_count);

        adapter = new LocalAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView parent, View view, int position, long id) {
                open(position);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView parent, View view, int position, long id) {
                itemMenu(position);
                return true;
            }
        });

        findViewById(R.id.local_add).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(LocalActivity.this, FilePickerActivity.class);
                i.putExtra(FilePickerActivity.EXTRA_FILTER, FilePickerActivity.FILTER_BOOK);
                startActivityForResult(i, REQ_PICK);
            }
        });

        // bottom bar: 书架 / 书源 / 本地导入 (this page)
        Ui.tabs(this, 2);

        reload();

        // Host-driven entry points. API 1 has no `adb shell input tap` and no way to dismiss a
        // system file picker from the host, so both the + action and the layout check need a
        // door: these run exactly the same handlers the UI calls.
        if (getIntent().hasExtra("importBook")) {
            importLocalFile(getIntent().getStringExtra("importBook"));
        }
        if (getIntent().hasExtra("layoutDump")) {
            final View rootView = findViewById(R.id.local_list).getRootView();
            rootView.postDelayed(new Runnable() {
                public void run() {
                    dumpLayout(rootView);
                }
            }, 800L);
        }
    }

    protected void onResume() {
        super.onResume();
        if (store != null) {
            reload();
        }
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            importLocalFile(data.getStringExtra("path"));
        }
    }

    // ------------------------------------------------------------------ list

    private void reload() {
        shown.clear();
        List<ProgressStore.Entry> all = store.all();
        for (int i = 0; i < all.size(); i++) {
            ProgressStore.Entry e = all.get(i);
            if (e.book != null && LocalStore.isLocalSource(e.book.sourceUrl)) {
                shown.add(e);
            }
        }
        adapter.notifyDataSetChanged();
        count.setText(shown.size() > 0 ? (shown.size() + " 本") : "");
        if (shown.size() == 0) {
            empty.setVisibility(View.VISIBLE);
            list.setVisibility(View.GONE);
        } else {
            empty.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
        }
        Log.i(TAG, "page: local books=" + shown.size() + " shelf=" + all.size());
    }

    private void open(int position) {
        ProgressStore.Entry e = shown.get(position);
        Ui.openReader(this, e.book, e.chapterIndex);
    }

    private void itemMenu(final int position) {
        final ProgressStore.Entry e = shown.get(position);
        final String[] items = new String[]{"查看目录", "删除本地书（含缓存）"};
        new AlertDialog.Builder(this)
                .setTitle(e.book.name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            open(position);
                        } else {
                            remove(e);
                            reload();
                            toast("已删除本地书");
                        }
                    }
                })
                .show();
    }

    /** Drop the shelf entry AND the cached text; an online book has no local copy. */
    private void remove(ProgressStore.Entry e) {
        LocalStore.LocalBook lb = local.find(e.book.bookUrl);
        if (lb != null) {
            local.remove(lb);
        }
        store.remove(e);
    }

    // ---------------------------------------------------------------- import

    private void importLocalFile(String path) {
        LocalImport.start(this, store, local, path, new LocalImport.Done() {
            public void onImported(Books.Book b) {
                reload();
                toast("已导入《" + b.name + "》");
                Ui.openReader(LocalActivity.this, b, 0);
            }

            public void onFailed(String error) {
                toast("导入失败：" + error);
            }
        });
    }

    /** Log the bounds of this page's key views. See the layoutDump extra above. */
    private void dumpLayout(View rootView) {
        Log.i(TAG, "layout: root=" + rootView.getWidth() + "x" + rootView.getHeight());
        Ui.dump(rootView, R.id.local_title, "local_title");
        Ui.dump(rootView, R.id.local_count, "local_count");
        Ui.dump(rootView, R.id.local_add, "local_add");
        Ui.dump(rootView, R.id.local_list, "local_list");
        Ui.dump(rootView, R.id.local_empty, "local_empty");
        Ui.dumpTabs(rootView, 2);
        // The wording on the empty state is a requirement, so it is logged the same way the
        // bounds are: the only way to check a screen on API 1 is through Logcat.
        TextView e = (TextView) rootView.findViewById(R.id.local_empty);
        if (e != null) {
            Log.i(TAG, "layout: local_empty text="
                    + e.getText().toString().replace('\n', '|'));
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** Row: title + "共 N 章 | 本地 · txt | 读到 第 N 章". */
    private class LocalAdapter extends BaseAdapter {
        public int getCount() {
            return shown.size();
        }

        public Object getItem(int position) {
            return shown.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.row_book, null);
            }
            TextView title = (TextView) v.findViewById(R.id.row_title);
            TextView sub = (TextView) v.findViewById(R.id.row_sub);
            ProgressStore.Entry e = shown.get(position);
            title.setText(e.book.name);

            StringBuilder sb = new StringBuilder();
            LocalStore.LocalBook lb = local.find(e.book.bookUrl);
            if (lb != null) {
                sb.append("共 ").append(lb.size()).append(" 章");
            }
            if (e.book.sourceName != null && e.book.sourceName.length() > 0) {
                sb.append(sb.length() > 0 ? "  |  " : "").append(e.book.sourceName);
            }
            sb.append(sb.length() > 0 ? "  |  " : "");
            if (e.chapterName != null && e.chapterName.length() > 0) {
                sb.append("读到 ").append(e.chapterName);
            } else {
                sb.append("读到 第 ").append(e.chapterIndex + 1).append(" 章");
            }
            sub.setText(sb.toString());
            return v;
        }
    }
}

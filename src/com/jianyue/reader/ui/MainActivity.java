package com.jianyue.reader.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.jianyue.reader.R;
import com.jianyue.reader.model.Books;
import com.jianyue.reader.model.LocalStore;
import com.jianyue.reader.model.ProgressStore;
import com.jianyue.reader.util.Compat;

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
 * BATCH EDITING (replaces the old long-press menu)
 * -----------------------------------------------
 * Long-pressing a row used to pop a two-item dialog (查看目录 / 从书架移除). Since local imports
 * sit on this shelf as well, the dialog had to explain a different second action for them, and
 * removing several books meant going through it once per book. Long press now does what it
 * does on the 书源 page: it enters a selection mode where every row shows a checkbox and the
 * bar under the search box offers 全选/全不选 and 删除, so N books are removed in one go. The
 * 书源 page is the model on purpose - the same gesture, the same bar, one thing to learn.
 *
 * Deletion goes through {@link #forget}: a local book also drops its cached text, an online
 * book only its shelf entry.
 *
 * API-1 notes: ListView + BaseAdapter only (no RecyclerView), no runtime permissions,
 * long-press enters selection mode.
 */
public class MainActivity extends Activity {

    private static final String TAG = "JianYue";

    /**
     * Selection state, kept outside the activity so it survives a restart (the shelf is the
     * task root and is recreated by CLEAR_TOP from the other tabs).
     *
     * Keys rather than indices: an index means something else the moment a row is deleted.
     */
    private static final class Edit {
        static boolean active;
        static final Set<String> selected = new HashSet<String>();
    }

    private ProgressStore store;
    private LocalStore local;
    private ListView list;
    private TextView empty;
    private TextView selInfo;
    private TextView selectAllButton;
    private TextView deleteButton;
    private View batchBar;
    private BookRowAdapter adapter;
    /** The books currently shown, i.e. the ones kept on the shelf. Rebuilt on every reload. */
    private final List<ProgressStore.Entry> shown = new ArrayList<ProgressStore.Entry>();
    /** importBook that is waiting for the API 23+ storage-permission dialog. */
    private String pendingImport;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // no title bar and no grey band above the content; see Ui
        Ui.setup(this, 0xFFF7F7F7);
        // no setTitle(): the system header would show it and the layout has no title strip
        setContentView(R.layout.act_shelf);

        // one process-wide store: a book added on another screen is visible here at once
        store = ProgressStore.get(this);
        local = LocalStore.get(this);
        list = (ListView) findViewById(R.id.shelf_list);
        empty = (TextView) findViewById(R.id.shelf_empty);
        batchBar = findViewById(R.id.shelf_batch_bar);
        selInfo = (TextView) findViewById(R.id.shelf_sel_info);
        selectAllButton = (TextView) findViewById(R.id.btn_shelf_select_all);
        deleteButton = (TextView) findViewById(R.id.btn_shelf_delete);

        adapter = new BookRowAdapter();
        list.setAdapter(adapter);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView parent, View view, int position, long id) {
                if (Edit.active) {
                    toggleSelection(position);
                } else {
                    openBook(shown.get(position));
                }
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView parent, View view, int position, long id) {
                if (!Edit.active) {
                    enterEditMode(position);
                    return true;
                }
                return false;
            }
        });

        findViewById(R.id.btn_shelf_done).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setEditMode(false);
            }
        });
        selectAllButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toggleSelectAll();
            }
        });
        deleteButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                confirmDeleteSelected();
            }
        });

        // bottom bar: 书架 (this page) / 书源 / 本地导入
        Ui.tabs(this, 0);

        // Search starts from the box at the top of the shelf rather than from a separate screen.
        // The trigger is the magnifier icon next to the box (an ImageView now, not the red
        // "搜索" pill) but the id and the click path are unchanged.
        // It hands the keyword to SearchActivity, which owns the source list and the parallel
        // search, so the shelf stays a shelf.
        final EditText searchInput = (EditText) findViewById(R.id.shelf_search_input);
        // NOTE: API 1's TextView has no setOnEditorActionListener, and the whole
        // android.view.inputmethod package does not exist (there is no soft-keyboard
        // framework), so Enter in the box cannot be intercepted and the keyboard cannot be
        // hidden programmatically. The magnifier is the only way to submit.
        findViewById(R.id.shelf_search_go).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                doSearch(searchInput);
            }
        });
        reload();

        // Host-driven import: `-e importBook /sdcard/book.txt` runs exactly the same code
        // path as the + button on the 本地导入 page. API 1 has no `adb shell input tap`, so
        // this is the only way to verify importing from the host.
        //
        // From API 23 on the SD card is behind a runtime permission; when it has not been
        // granted yet the import is parked and replayed from onRequestPermissionsResult.
        if (getIntent().hasExtra("importBook")) {
            pendingImport = getIntent().getStringExtra("importBook");
            if (Compat.ensureStorage(this)) {
                String path = pendingImport;
                pendingImport = null;
                importLocalFile(path);
            }
        }

        // Host-driven layout check: API 1 has no screencap, so the sheet cannot be
        // inspected visually. This logs each key view's real bounds after layout, which is
        // enough to catch the failure modes that matter (a zero-width search box, a bar
        // pushed off screen, the list given no height).
        if (getIntent().hasExtra("layoutDump")) {
            final View rootView = findViewById(R.id.shelf_list).getRootView();
            // postDelayed, not post: View.post() can run BEFORE the layout traversal, and a
            // pre-layout dump reports every view as 0x0 (which looks like a broken sheet).
            rootView.postDelayed(new Runnable() {
                public void run() {
                    dumpLayout(rootView);
                }
            }, 800L);
        }

        // Host-driven long press: the device this app was verified on (Coolpad 8056, Android
        // 2.3.5) has no `input tap` and its touchscreen ignores injected events, so the one
        // gesture that cannot be produced from the host needs a door. It calls exactly what
        // the long-press listener calls, so what it verifies is the real code path.
        if (getIntent().hasExtra("editMode")) {
            findViewById(R.id.shelf_list).post(new Runnable() {
                public void run() {
                    enterEditMode(0);
                }
            });
        }

        // Host-driven batch-delete self test, mirroring SourcesActivity's `testBatch`: it
        // exercises the descending delete loop and reports before/after, because deleting
        // while iterating is the part of batch editing that can be silently wrong.
        if (getIntent().hasExtra("testBatch")) {
            findViewById(R.id.shelf_list).post(new Runnable() {
                public void run() {
                    testBatchDelete();
                }
            });
        }
    }

    /**
     * Replays the import that was parked while the API 23+ storage dialog was up.
     *
     * Deliberately without @Override and without calling super: the API-1 android.jar
     * this project compiles against has neither the method nor anything to call, and on
     * API 23+ the framework dispatches here by signature. Do not "tidy" this up.
     */
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != Compat.REQ_STORAGE || pendingImport == null) {
            return;
        }
        String path = pendingImport;
        pendingImport = null;
        if (Compat.hasStorage(this)) {
            importLocalFile(path);
        } else {
            toast("没有存储权限，无法读取 " + path);
        }
    }

    /**
     * Back leaves selection mode first, and only then the shelf.
     *
     * Without this the only way out of selection mode would be the 完成 button, and the back
     * key - the reflex - would walk out of the app with rows still ticked.
     */
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && Edit.active) {
            setEditMode(false);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** Log the bounds of the shelf's key views. See the layoutDump extra above. */
    private void dumpLayout(View rootView) {
        Log.i(TAG, "layout: root=" + rootView.getWidth() + "x" + rootView.getHeight());
        Ui.dump(rootView, R.id.shelf_search_input, "search_input");
        Ui.dump(rootView, R.id.shelf_search_go, "search_go");
        Ui.dump(rootView, R.id.shelf_list, "list");
        Ui.dump(rootView, R.id.shelf_empty, "empty");
        Ui.dumpTabs(rootView, 0);
        // selection mode: the bar has to appear, the rows have to grow a checkbox, and the
        // count in the bar has to track the selection - all three only through real views
        Log.i(TAG, "layout: shelf editMode=" + Edit.active + " batchBarVis="
                + (batchBar.getVisibility() == View.VISIBLE ? "V" : "G"));
        Ui.dump(rootView, R.id.shelf_batch_bar, "batch_bar");
        setEditMode(true);
        // start the dump from a known selection state: setEditMode(true) keeps whatever was
        // ticked, and 全选 on an already-complete selection would UNSELECT everything instead
        Edit.selected.clear();
        applyEditMode();
        toggleSelectAll();
        Log.i(TAG, "layout: shelf afterSelectAll selected=" + Edit.selected.size()
                + " info=" + selInfo.getText() + " del=" + deleteButton.getText());
        if (adapter.getCount() > 0) {
            // getView is what binds the checkbox, so ask it for a row the way the ListView does
            View row = adapter.getView(0, null, list);
            CheckBox box = row == null ? null : (CheckBox) row.findViewById(R.id.row_check);
            Log.i(TAG, "layout: shelf row0 check="
                    + (box == null ? "MISSING" : (box.getVisibility() == View.VISIBLE ? "V" : "G"))
                    + " checked=" + (box != null && box.isChecked()));
        }
        setEditMode(false);
        Log.i(TAG, "layout: shelf editModeOff=" + Edit.active + " batchBarVis="
                + (batchBar.getVisibility() == View.VISIBLE ? "V" : "G")
                + " selected=" + Edit.selected.size());
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
        shown.clear();
        shown.addAll(store.shelf());
        adapter.notifyDataSetChanged();
        if (shown.size() == 0) {
            empty.setVisibility(View.VISIBLE);
            list.setVisibility(View.GONE);
        } else {
            empty.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
        }
        applyEditMode();
        Log.i(TAG, "shelf: " + shown.size() + " books (" + store.size() + " entries known)");
    }

    private void openBook(ProgressStore.Entry e) {
        if (e == null || e.book == null) {
            return;
        }
        Ui.openReader(this, e.book, e.chapterIndex);
    }

    // ------------------------------------------------------------ local import

    /**
     * Put a local file in the local library (host-driven entry point; the UI entry is the +
     * on the 本地导入 page). The import itself lives in LocalImport so both callers agree, and
     * it does NOT add the book to this shelf - see LocalImport.
     */
    private void importLocalFile(String path) {
        LocalImport.start(this, local, path, new LocalImport.Done() {
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

    /**
     * Take a book off the shelf.
     *
     * A local book also loses its imported file and cached text: this shelf owns no copy of it,
     * so leaving the file behind would mean a book the user cannot read from anywhere they
     * removed it. (To only take it off the shelf and keep the file, use 移出书架 on the
     * 本地导入 page.)
     */
    private boolean forget(ProgressStore.Entry e) {
        if (e != null && e.book != null && LocalStore.isLocalSource(e.book.sourceUrl)) {
            LocalStore.LocalBook lb = local.find(e.book.bookUrl);
            if (lb != null) {
                local.remove(lb);
            }
        }
        return store.remove(e);
    }

    // --------------------------------------------------------- batch editing

    /** What a long press does: selection mode, with that row already ticked. */
    private void enterEditMode(int position) {
        setEditMode(true);
        toggleSelection(position);
    }

    private void setEditMode(boolean on) {
        Edit.active = on;
        if (!on) {
            Edit.selected.clear();
        }
        applyEditMode();
    }

    private void applyEditMode() {
        batchBar.setVisibility(Edit.active ? View.VISIBLE : View.GONE);
        int n = Edit.selected.size();
        selInfo.setText("已选 " + n + " 本");
        deleteButton.setText(n > 0 ? ("删除(" + n + ")") : "删除");
        deleteButton.setTextColor(n > 0 ? 0xFFC62828 : 0xFFBBBBBB);
        boolean all = shown.size() > 0 && n == shown.size();
        selectAllButton.setText(all ? "全不选" : "全选");
        adapter.notifyDataSetChanged();
        Log.i(TAG, "shelf: edit active=" + Edit.active + " selected=" + n + "/" + shown.size());
    }

    private String keyOf(int position) {
        if (position < 0 || position >= shown.size()) {
            return null;
        }
        ProgressStore.Entry e = shown.get(position);
        return (e == null) ? null : e.key();
    }

    private void toggleSelection(int position) {
        String key = keyOf(position);
        if (key == null) {
            return;
        }
        if (Edit.selected.contains(key)) {
            Edit.selected.remove(key);
        } else {
            Edit.selected.add(key);
        }
        applyEditMode();
    }

    private void toggleSelectAll() {
        boolean all = shown.size() > 0 && Edit.selected.size() == shown.size();
        Edit.selected.clear();
        if (!all) {
            for (int i = 0; i < shown.size(); i++) {
                String k = keyOf(i);
                if (k != null) {
                    Edit.selected.add(k);
                }
            }
        }
        applyEditMode();
    }

    private void confirmDeleteSelected() {
        final int n = Edit.selected.size();
        if (n == 0) {
            toast("请先勾选要删除的书");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("从书架删除")
                .setMessage("确定删除选中的 " + n + " 本？\n\n本地导入的书会同时删掉缓存的正文。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        doDeleteSelected();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * Delete every selected book.
     *
     * The doomed entries are collected FIRST and removed back to front: ProgressStore.remove
     * splices the live list, so removing while walking it forward skips every other hit.
     */
    private void doDeleteSelected() {
        List<ProgressStore.Entry> doomed = new ArrayList<ProgressStore.Entry>();
        for (int i = 0; i < shown.size(); i++) {
            String k = keyOf(i);
            if (k != null && Edit.selected.contains(k)) {
                ProgressStore.Entry e = shown.get(i);
                if (e != null) {
                    doomed.add(e);
                }
            }
        }
        int removed = 0;
        for (int i = doomed.size() - 1; i >= 0; i--) {
            if (forget(doomed.get(i))) {
                removed++;
            }
        }
        Log.i(TAG, "shelf: batch deleted " + removed + " of " + doomed.size()
                + " left=" + store.shelfSize());
        Edit.selected.clear();
        setEditMode(false);
        reload();
        toast("已删除 " + removed + " 本");
    }

    /**
     * Exercise the batch-delete path and report before/after, the way SourcesActivity does
     * for 书源. The count that matters is whether the live store really shrank by N.
     */
    private void testBatchDelete() {
        int before = shown.size();
        if (before == 0) {
            Log.i(TAG, "batch: no books to test");
            return;
        }
        int want = Math.min(3, before);
        Edit.active = true;
        Edit.selected.clear();
        for (int i = 0; i < want; i++) {
            String k = keyOf(i);
            if (k != null) {
                Edit.selected.add(k);
            }
        }
        Log.i(TAG, "batch: before=" + before + " selected=" + Edit.selected.size());
        doDeleteSelected();
        Log.i(TAG, "batch: after=" + shown.size() + " expected=" + (before - want));
        Log.i(TAG, "batch: RESULT=" + ((shown.size() == before - want) ? "PASS" : "FAIL"));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** Shelf row adapter: title + progress line, plus the batch-edit checkbox. */
    private class BookRowAdapter extends BaseAdapter {
        public int getCount() {
            return shown.size();
        }

        public Object getItem(int position) {
            return shown.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.row_book, null);
            }
            CheckBox check = (CheckBox) v.findViewById(R.id.row_check);
            TextView title = (TextView) v.findViewById(R.id.row_title);
            TextView sub = (TextView) v.findViewById(R.id.row_sub);
            ProgressStore.Entry e = shown.get(position);
            title.setText(e.book.name);

            String key = e.key();
            if (check != null) {
                check.setVisibility(Edit.active ? View.VISIBLE : View.GONE);
                check.setChecked(Edit.selected.contains(key));
            }

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

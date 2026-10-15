package com.jianyue.reader.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
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

/**
 * 本地导入 (local imports) - tab 3 of 3.
 *
 * This page lists the FILES the user imported - LocalStore.all(), not the shelf. The two are
 * different lists on purpose: importing a book does not put it on the 书架, and reading it does
 * not either. To move one across, long press it and pick 加入书架 in the bar (or 移出书架 to take
 * it back off). The + at the right of the top bar picks a txt / epub / html file off the card,
 * and the import itself is LocalImport - the same code the host-driven `importBook` entry uses.
 *
 * Reading state is still one thing: a book that has been opened has a ProgressStore entry, and
 * this page reads the position out of it ("读到 …"). The only difference from an online book is
 * that the entry's shelf flag starts off.
 *
 * Long press = selection mode, the same gesture as the shelf and the 书源 page: checkboxes on
 * the rows, a bar with 加入书架/移出书架, 全选 and 删除. 删除 removes the imported file and its
 * cached text (this is the page that owns them).
 *
 * API-1 notes: ListView + BaseAdapter only, no runtime permissions, long-press enters
 * selection mode.
 */
public class LocalActivity extends Activity {

    private static final String TAG = "JianYue";

    /** requestCode for the file picker behind the + button. */
    private static final int REQ_PICK = 3001;

    /** Selection state, outside the activity so it survives a restart (see MainActivity). */
    private static final class Edit {
        static boolean active;
        static final Set<String> selected = new HashSet<String>();
    }

    /** One row: an imported file, plus its reading state when it has ever been opened. */
    private static final class Row {
        LocalStore.LocalBook file;
        ProgressStore.Entry entry;      // null until the book has been opened

        String key() {
            return ProgressStore.keyOf(LocalStore.SOURCE_URL, file.id);
        }

        boolean onShelf() {
            return entry != null && entry.shelf;
        }
    }

    private ProgressStore store;
    private LocalStore local;
    private ListView list;
    private TextView empty;
    private TextView count;
    private TextView doneButton;
    private TextView selInfo;
    private TextView shelfButton;
    private TextView selectAllButton;
    private TextView deleteButton;
    private View batchBar;
    private LocalAdapter adapter;

    /** The rows currently shown, rebuilt from LocalStore on every reload. */
    private final List<Row> shown = new ArrayList<Row>();

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.setup(this, 0xFFF7F7F7);
        setContentView(R.layout.act_local);

        // process-wide stores: the shelf and this page read the same file, so 加入书架 here is
        // visible there at once
        store = ProgressStore.get(this);
        local = LocalStore.get(this);
        list = (ListView) findViewById(R.id.local_list);
        empty = (TextView) findViewById(R.id.local_empty);
        count = (TextView) findViewById(R.id.local_count);
        doneButton = (TextView) findViewById(R.id.local_done);
        batchBar = findViewById(R.id.local_batch_bar);
        selInfo = (TextView) findViewById(R.id.local_sel_info);
        shelfButton = (TextView) findViewById(R.id.btn_local_shelf);
        selectAllButton = (TextView) findViewById(R.id.btn_local_select_all);
        deleteButton = (TextView) findViewById(R.id.btn_local_delete);

        adapter = new LocalAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView parent, View view, int position, long id) {
                if (Edit.active) {
                    toggleSelection(position);
                } else {
                    open(position);
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

        doneButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setEditMode(false);
            }
        });
        shelfButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toggleShelfForSelection();
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
        // The long press cannot be produced from the host (this device's touchscreen ignores
        // injected events), and the shelf toggle / delete loop are the parts that are easy to get
        // silently wrong. Both doors call what the buttons call.
        if (getIntent().hasExtra("editMode")) {
            list.post(new Runnable() {
                public void run() {
                    enterEditMode(0);
                }
            });
        }
        if (getIntent().hasExtra("testShelf")) {
            list.post(new Runnable() {
                public void run() {
                    testShelfToggle();
                }
            });
        }
        if (getIntent().hasExtra("testBatch")) {
            list.post(new Runnable() {
                public void run() {
                    testBatchDelete();
                }
            });
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

    /** Back leaves selection mode first; see MainActivity. */
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && Edit.active) {
            setEditMode(false);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------ list

    private void reload() {
        shown.clear();
        List<LocalStore.LocalBook> files = local.all();
        for (int i = 0; i < files.size(); i++) {
            LocalStore.LocalBook lb = files.get(i);
            Row r = new Row();
            r.file = lb;
            // the reading state of this file, wherever the user opened it from
            r.entry = store.find(LocalStore.SOURCE_URL, lb.id);
            shown.add(r);
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
        applyEditMode();
        Log.i(TAG, "page: local files=" + shown.size() + " shelf=" + store.shelfSize()
                + " entries=" + store.size());
    }

    private void open(int position) {
        Row r = shown.get(position);
        int at = (r.entry != null) ? r.entry.chapterIndex : 0;
        Ui.openReader(this, r.file.toBook(), at);
    }

    /** Forget the imported file AND its cached text, and any shelf entry pointing at it. */
    private boolean remove(Row r) {
        boolean ok = local.remove(r.file);
        if (r.entry != null) {
            store.remove(r.entry);
        }
        return ok;
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
        doneButton.setVisibility(Edit.active ? View.VISIBLE : View.GONE);
        int n = Edit.selected.size();
        selInfo.setText("已选 " + n + " 本");
        deleteButton.setText(n > 0 ? ("删除(" + n + ")") : "删除");
        deleteButton.setTextColor(n > 0 ? 0xFFC62828 : 0xFFBBBBBB);
        boolean all = shown.size() > 0 && n == shown.size();
        selectAllButton.setText(all ? "全不选" : "全选");
        // the shelf button says what it would do to the current selection: everything already on
        // the shelf means the useful action is the way back
        shelfButton.setText(allSelectedOnShelf() ? "移出书架" : "加入书架");
        adapter.notifyDataSetChanged();
        Log.i(TAG, "local: edit active=" + Edit.active + " selected=" + n + "/" + shown.size()
                + " shelfAction=" + shelfButton.getText());
    }

    private String keyOf(int position) {
        if (position < 0 || position >= shown.size()) {
            return null;
        }
        return shown.get(position).key();
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

    /** The ticked rows, in list order. */
    private List<Row> selectedRows() {
        List<Row> out = new ArrayList<Row>();
        for (int i = 0; i < shown.size(); i++) {
            Row r = shown.get(i);
            if (Edit.selected.contains(r.key())) {
                out.add(r);
            }
        }
        return out;
    }

    private boolean allSelectedOnShelf() {
        List<Row> rows = selectedRows();
        if (rows.size() == 0) {
            return false;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).onShelf()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 加入书架 / 移出书架 for the ticked rows.
     *
     * Off the shelf is the default state of an imported book, so this is the only way across;
     * taking one off keeps its reading position (only the shelf flag changes), which is why the
     * entry is not deleted here.
     */
    private void toggleShelfForSelection() {
        List<Row> rows = selectedRows();
        if (rows.size() == 0) {
            toast("请先勾选书");
            return;
        }
        boolean off = allSelectedOnShelf();
        int moved = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (off) {
                if (store.setShelf(r.entry, false)) {
                    moved++;
                }
                continue;
            }
            if (r.onShelf()) {
                continue;                       // already there
            }
            Books.Book b = r.file.toBook();
            ProgressStore.Entry e = r.entry;
            if (e == null) {
                store.addToShelf(b);
                moved++;
            } else if (store.setShelf(e, true)) {
                moved++;
            }
        }
        Log.i(TAG, "local: shelf toggle off=" + off + " moved=" + moved + "/" + rows.size()
                + " shelfNow=" + store.shelfSize());
        reload();
        toast(off ? ("已移出书架 " + moved + " 本") : ("已加入书架 " + moved + " 本"));
    }

    private void confirmDeleteSelected() {
        final int n = Edit.selected.size();
        if (n == 0) {
            toast("请先勾选要删除的书");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除本地书")
                .setMessage("确定删除选中的 " + n + " 本？\n\n缓存的正文章节会一起删除，"
                        + "书架上的对应条目也会去掉。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        doDeleteSelected();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doDeleteSelected() {
        List<Row> doomed = selectedRows();
        int removed = 0;
        // back to front, because LocalStore.remove splices its live list
        for (int i = doomed.size() - 1; i >= 0; i--) {
            if (remove(doomed.get(i))) {
                removed++;
            }
        }
        Log.i(TAG, "local: batch deleted " + removed + " of " + doomed.size()
                + " left=" + local.size());
        Edit.selected.clear();
        setEditMode(false);
        reload();
        toast("已删除 " + removed + " 本");
    }

    /** Host-driven 加入书架 on whatever is selected (see the testShelf extra). */
    private void testShelfToggle() {
        Log.i(TAG, "batch: shelf toggle selected=" + Edit.selected.size()
                + " shelfBefore=" + store.shelfSize());
        toggleShelfForSelection();
        Log.i(TAG, "batch: shelf after=" + store.shelfSize());
    }

    /** Exercise the batch-delete path and report before/after (see MainActivity). */
    private void testBatchDelete() {
        int before = shown.size();
        if (before == 0) {
            Log.i(TAG, "batch: no local books to test");
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

    // ---------------------------------------------------------------- import

    private void importLocalFile(String path) {
        LocalImport.start(this, local, path, new LocalImport.Done() {
            public void onImported(Books.Book b) {
                reload();
                toast("已导入《" + b.name + "》，长按可加入书架");
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
        // selection mode, the same three things the shelf checks plus the shelf action's label.
        // The selection is cleared first so that 全选 really selects everything here: with the
        // row from the long press already ticked, 全选 is 全不选 and the dump would prove nothing.
        setEditMode(true);
        Edit.selected.clear();
        applyEditMode();
        Ui.dump(rootView, R.id.local_done, "local_done");
        Ui.dump(rootView, R.id.local_batch_bar, "batch_bar");
        toggleSelectAll();
        Log.i(TAG, "layout: local afterSelectAll selected=" + Edit.selected.size()
                + " info=" + selInfo.getText() + " shelf=" + shelfButton.getText()
                + " del=" + deleteButton.getText());
        if (adapter.getCount() > 0) {
            View row = adapter.getView(0, null, list);
            CheckBox box = row == null ? null : (CheckBox) row.findViewById(R.id.row_check);
            Log.i(TAG, "layout: local row0 check="
                    + (box == null ? "MISSING" : (box.getVisibility() == View.VISIBLE ? "V" : "G"))
                    + " checked=" + (box != null && box.isChecked()));
        }
        setEditMode(false);
        Log.i(TAG, "layout: local editModeOff=" + Edit.active + " selected=" + Edit.selected.size());
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** Row: title + "共 N 章 | 本地 · txt | 已在书架 | 读到 …", plus the checkbox. */
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
            CheckBox check = (CheckBox) v.findViewById(R.id.row_check);
            TextView title = (TextView) v.findViewById(R.id.row_title);
            TextView sub = (TextView) v.findViewById(R.id.row_sub);
            Row r = shown.get(position);
            title.setText(r.file.title);

            if (check != null) {
                check.setVisibility(Edit.active ? View.VISIBLE : View.GONE);
                check.setChecked(Edit.selected.contains(r.key()));
            }

            StringBuilder sb = new StringBuilder();
            sb.append("共 ").append(r.file.size()).append(" 章");
            sb.append("  |  本地 ").append(r.file.format);
            sb.append(r.onShelf() ? "  |  已在书架" : "  |  长按加入书架");
            if (r.entry != null) {
                if (r.entry.chapterName != null && r.entry.chapterName.length() > 0) {
                    sb.append("  |  读到 ").append(r.entry.chapterName);
                } else {
                    sb.append("  |  读到 第 ").append(r.entry.chapterIndex + 1).append(" 章");
                }
            }
            sub.setText(sb.toString());
            return v;
        }
    }
}

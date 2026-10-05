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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.jianyue.reader.R;
import com.jianyue.reader.model.BookSource;
import com.jianyue.reader.model.SourceStore;

/**
 * 书源管理 (book-source management).
 *
 * Import by pasting JSON or by picking a .json file off the card. Both paths go through
 * SourceStore.importJson, which accepts a single object or an array (the 阅读 export shape).
 * No built-in sources are shipped: the user supplies them.
 *
 * Batch editing
 * -------------
 * "批量" enters a selection mode where every row shows a checkbox and a bar offers
 * 全选/全不选 and 删除. The reason this matters in practice: a book-source collection can
 * hold thousands of entries and most of them are dead, so deleting them one at a time via
 * the per-source menu is not workable.
 *
 * Deletion goes through the indices in DESCENDING order, because SourceStore.remove(i)
 * shifts everything after i down by one - deleting ascending would skip every other entry.
 */
public class SourcesActivity extends Activity {

    private static final String TAG = "JianYue";
    private static final int REQ_PICK = 2001;

    /**
     * Selection state, kept outside the activity so it survives a restart.
     *
     * It stores source URLs rather than indices on purpose: indices become meaningless the
     * moment anything is deleted or the list is reloaded.
     */
    private static final class Edit {
        static boolean active;
        static final Set<String> selected = new HashSet<String>();
    }

    private SourceStore store;
    private ListView list;
    private TextView empty;
    private TextView count;
    private TextView editButton;
    private View batchBar;
    private TextView selInfo;
    private TextView selectAllButton;
    private TextView deleteButton;
    private SourceAdapter adapter;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.setup(this, 0xFFF7F7F7);
        setContentView(R.layout.act_sources);

        store = new SourceStore(this);
        list = (ListView) findViewById(R.id.src_list);
        empty = (TextView) findViewById(R.id.src_empty);
        count = (TextView) findViewById(R.id.src_count);
        editButton = (TextView) findViewById(R.id.btn_edit);
        batchBar = findViewById(R.id.src_batch_bar);
        selInfo = (TextView) findViewById(R.id.src_sel_info);
        selectAllButton = (TextView) findViewById(R.id.btn_select_all);
        deleteButton = (TextView) findViewById(R.id.btn_delete);

        adapter = new SourceAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView parent, View view, int position, long id) {
                if (Edit.active) {
                    toggleSelection(position);
                } else {
                    sourceMenu(position);
                }
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView parent, View view, int position, long id) {
                if (!Edit.active) {
                    setEditMode(true);
                    toggleSelection(position);
                    return true;
                }
                return false;
            }
        });

        editButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setEditMode(!Edit.active);
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

        findViewById(R.id.btn_paste).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pasteImport();
            }
        });
        findViewById(R.id.btn_file).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivityForResult(
                        new Intent(SourcesActivity.this, FilePickerActivity.class), REQ_PICK);
            }
        });
        findViewById(R.id.btn_src_help).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(SourcesActivity.this, HelpActivity.class));
            }
        });

        // bottom bar: 书架 / 书源 (this page) / 本地导入
        Ui.tabs(this, 1);

        reload();

        // Host-driven layout check (API 1 has no screencap) and a batch-delete self test.
        // They run through the SAME code path the buttons use, so the log reflects real
        // behaviour rather than a parallel implementation.
        if (getIntent().hasExtra("layoutDump")) {
            final View rootView = findViewById(R.id.src_list).getRootView();
            // postDelayed, not post: View.post() can run BEFORE the first layout traversal,
            // and a pre-layout dump reports every view as 0x0 (which looks like a broken page).
            rootView.postDelayed(new Runnable() {
                public void run() {
                    dumpLayout(rootView);
                }
            }, 800L);
        }
        if (getIntent().hasExtra("testBatch")) {
            final View rootView = findViewById(R.id.src_list).getRootView();
            rootView.post(new Runnable() {
                public void run() {
                    testBatchDelete();
                }
            });
        }

        // Host-driven entry point: import a source file directly, no dialog.
        // API 1 has no `adb shell input tap`, so pasting JSON or walking the file picker
        // cannot be driven from the host (a dialog cannot be dismissed either). This runs
        // the exact same SourceStore.importFile path the UI uses.
        final String importPath = getIntent().getStringExtra("importPath");
        if (importPath != null && importPath.length() > 0) {
            File f = new File(importPath);
            Log.i(TAG, "importPath requested: " + importPath + " exists=" + f.isFile());
            SourceStore.ImportResult r = store.importFile(f);
            Log.i(TAG, "importPath result: added=" + r.added + " updated=" + r.skipped
                    + " failed=" + r.failed + " error=" + r.error);
            reload();
            count.setText("导入 " + r.added + " 个，共 " + store.size() + " 个");
        }
    }

    protected void onResume() {
        super.onResume();
        if (store != null) {
            // re-read from disk: a paste import writes the file directly
            store = new SourceStore(this);
            reload();
        }
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            String path = data.getStringExtra("path");
            if (path != null) {
                SourceStore.ImportResult r = store.importFile(new File(path));
                report(r);
            }
        }
    }

    private void reload() {
        adapter.notifyDataSetChanged();
        int n = store.size();
        count.setText(n > 0 ? (n + " 个") : "");
        if (n == 0) {
            empty.setText("还没有书源\n\n点上面「粘贴导入」或「从文件导入」\n"
                    + "不清楚格式可以看「格式说明」");
            empty.setVisibility(View.VISIBLE);
            list.setVisibility(View.GONE);
        } else {
            empty.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
        }
        applyEditMode();
        Log.i(TAG, "page: sources n=" + n);
    }

    // --------------------------------------------------------- batch editing

    private void setEditMode(boolean on) {
        Edit.active = on;
        if (!on) {
            Edit.selected.clear();
        }
        applyEditMode();
    }

    private void applyEditMode() {
        batchBar.setVisibility(Edit.active ? View.VISIBLE : View.GONE);
        editButton.setText(Edit.active ? "完成" : "批量");
        int n = Edit.selected.size();
        selInfo.setText("已选 " + n + " 个");
        deleteButton.setText(n > 0 ? ("删除(" + n + ")") : "删除");
        deleteButton.setTextColor(n > 0 ? 0xFFC62828 : 0xFFBBBBBB);
        boolean all = store.size() > 0 && n == store.size();
        selectAllButton.setText(all ? "全不选" : "全选");
        adapter.notifyDataSetChanged();
    }

    private String keyOf(int position) {
        BookSource s = store.get(position);
        return (s == null || s.url == null) ? null : s.url;
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
        boolean all = store.size() > 0 && Edit.selected.size() == store.size();
        Edit.selected.clear();
        if (!all) {
            for (int i = 0; i < store.size(); i++) {
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
            toast("请先勾选要删除的书源");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除书源")
                .setMessage("确定删除选中的 " + n + " 个书源？\n\n此操作不可撤销。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        doDeleteSelected();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * Delete every selected source.
     *
     * Iterates DESCENDING: SourceStore.remove(i) splices the list, so removing from the front
     * would shift the remaining targets down and skip half of them.
     */
    private void doDeleteSelected() {
        List<Integer> doomed = new ArrayList<Integer>();
        for (int i = 0; i < store.size(); i++) {
            String k = keyOf(i);
            if (k != null && Edit.selected.contains(k)) {
                doomed.add(Integer.valueOf(i));
            }
        }
        int removed = 0;
        for (int i = doomed.size() - 1; i >= 0; i--) {
            if (store.remove(doomed.get(i).intValue())) {
                removed++;
            }
        }
        Log.i(TAG, "sources: batch deleted " + removed + " of " + doomed.size());
        Edit.selected.clear();
        setEditMode(false);
        reload();
        toast("已删除 " + removed + " 个书源");
    }

    // --------------------------------------------------------------- import

    private void pasteImport() {
        final EditText input = new EditText(this);
        input.setTextSize(12f);
        input.setMinLines(6);
        input.setHint("在此粘贴书源 JSON");
        new AlertDialog.Builder(this)
                .setTitle("粘贴导入")
                .setView(input)
                .setPositiveButton("导入", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        SourceStore.ImportResult r =
                                store.importJson(input.getText().toString());
                        report(r);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void report(SourceStore.ImportResult r) {
        StringBuilder sb = new StringBuilder();
        if (r.error != null) {
            sb.append("导入失败：").append(r.error);
        } else {
            sb.append("新增 ").append(r.added).append(" 个");
            if (r.skipped > 0) {
                sb.append("，更新 ").append(r.skipped).append(" 个");
            }
            if (r.failed > 0) {
                sb.append("，跳过 ").append(r.failed).append(" 个（缺少 bookSourceUrl）");
            }
        }
        toast(sb.toString());
        Log.i(TAG, "import: " + sb);
        reload();
    }

    private void sourceMenu(final int position) {
        final BookSource s = store.get(position);
        if (s == null) {
            return;
        }
        final String[] items = new String[]{
                s.enabled ? "停用" : "启用",
                "查看 JSON",
                "删除"
        };
        new AlertDialog.Builder(this)
                .setTitle(s.name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            store.setEnabled(position, !s.enabled);
                            reload();
                        } else if (which == 1) {
                            new AlertDialog.Builder(SourcesActivity.this)
                                    .setTitle(s.name)
                                    .setMessage(s.toJson().toString())
                                    .setPositiveButton("关闭", null)
                                    .show();
                        } else {
                            store.remove(position);
                            reload();
                            toast("已删除");
                        }
                    }
                })
                .show();
    }

    /** Log the bounds of the sources screen's key views. */
    private void dumpLayout(View rootView) {
        Log.i(TAG, "layout: root=" + rootView.getWidth() + "x" + rootView.getHeight()
                + " sources=" + store.size());
        Ui.dump(rootView, R.id.src_title, "title");
        Ui.dump(rootView, R.id.src_count, "count");
        Ui.dump(rootView, R.id.btn_edit, "btn_edit");
        Ui.dump(rootView, R.id.btn_paste, "btn_paste");
        Ui.dump(rootView, R.id.btn_file, "btn_file");
        Ui.dump(rootView, R.id.btn_src_help, "btn_src_help");
        Ui.dump(rootView, R.id.src_batch_bar, "batch_bar");
        Ui.dump(rootView, R.id.src_list, "list");
        Ui.dumpTabs(rootView, 1);
        // entering edit mode must reveal the batch bar without breaking the rest
        setEditMode(true);
        Log.i(TAG, "layout: editMode=" + Edit.active
                + " batchBarVis=" + (batchBar.getVisibility() == View.VISIBLE ? "V" : "G"));
        toggleSelectAll();
        Log.i(TAG, "layout: afterSelectAll selected=" + Edit.selected.size()
                + " info=" + selInfo.getText() + " del=" + deleteButton.getText());
        setEditMode(false);
        Log.i(TAG, "layout: editModeOff=" + Edit.active
                + " batchBarVis=" + (batchBar.getVisibility() == View.VISIBLE ? "V" : "G"));
    }

    /**
     * Exercise the batch-delete path and report the before/after counts.
     *
     * Deletion is the part most likely to be silently wrong: SourceStore.remove(i) splices
     * the list, so removing indices in ASCENDING order skips every other entry. This proves
     * the descending iteration actually deletes the expected number.
     */
    private void testBatchDelete() {
        int before = store.size();
        if (before == 0) {
            Log.i(TAG, "batch: no sources to test");
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
        Log.i(TAG, "batch: after=" + store.size() + " expected=" + (before - want));
        Log.i(TAG, "batch: RESULT=" + ((store.size() == before - want) ? "PASS" : "FAIL"));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private class SourceAdapter extends BaseAdapter {
        public int getCount() {
            return store.size();
        }

        public Object getItem(int position) {
            return store.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.row_source, null);
            }
            CheckBox check = (CheckBox) v.findViewById(R.id.row_check);
            TextView title = (TextView) v.findViewById(R.id.row_title);
            TextView sub = (TextView) v.findViewById(R.id.row_sub);

            BookSource s = store.get(position);
            String key = (s == null || s.url == null) ? null : s.url;

            check.setVisibility(Edit.active ? View.VISIBLE : View.GONE);
            check.setChecked(key != null && Edit.selected.contains(key));

            if (s == null) {
                title.setText("?");
                sub.setText("");
                return v;
            }
            title.setText(s.name);
            title.setTextColor(s.enabled ? 0xFF222222 : 0xFFAAAAAA);
            // typeName() matters here: an audio/image source is imported but cannot be read,
            // and saying so on the row is the only place the user would find out.
            String extra = s.isSupported() ? "" : ("  · " + s.typeName() + "（不支持）");
            sub.setText(s.summary() + extra);
            return v;
        }
    }
}

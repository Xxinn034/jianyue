package com.jianyue.reader.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.jianyue.reader.util.FileUtils;

/**
 * Minimal file picker, used for two jobs:
 *   - importing a book-source .json  (default)
 *   - importing a local book          (extra "filter" = "book")
 *
 * API 1 has no Storage Access Framework and no DocumentFile, so this walks the
 * filesystem directly. Directories first, then the files this caller accepts; only one
 * level is shown at a time so the list stays short.
 */
public class FilePickerActivity extends Activity {

    /** Extra: "book" lists book files instead of book sources. */
    public static final String EXTRA_FILTER = "filter";
    public static final String FILTER_BOOK = "book";

    private boolean bookMode;
    private File current;
    private final List<File> entries = new ArrayList<File>();
    private TextView pathView;
    private PickerAdapter adapter;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // no title bar and no grey band above the content; see Ui
        Ui.setup(this, 0xFFF7F7F7);
        setContentView(com.jianyue.reader.R.layout.act_browser);

        bookMode = FILTER_BOOK.equals(getIntent().getStringExtra(EXTRA_FILTER));

        pathView = (TextView) findViewById(com.jianyue.reader.R.id.browser_path);
        ListView list = (ListView) findViewById(com.jianyue.reader.R.id.browser_list);
        adapter = new PickerAdapter();
        list.setAdapter(adapter);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView parent, View view, int position, long id) {
                File f = entries.get(position);
                if (f.isDirectory()) {
                    open(f);
                } else {
                    Intent data = new Intent();
                    data.putExtra("path", f.getAbsolutePath());
                    setResult(RESULT_OK, data);
                    finish();
                }
            }
        });

        findViewById(com.jianyue.reader.R.id.browser_up).setOnClickListener(
                new View.OnClickListener() {
                    public void onClick(View v) {
                        File p = current == null ? null : current.getParentFile();
                        if (p != null) {
                            open(p);
                        } else {
                            showRoots();
                        }
                    }
                });
        findViewById(com.jianyue.reader.R.id.browser_roots).setOnClickListener(
                new View.OnClickListener() {
                    public void onClick(View v) {
                        showRoots();
                    }
                });
        findViewById(com.jianyue.reader.R.id.browser_cancel).setOnClickListener(
                new View.OnClickListener() {
                    public void onClick(View v) {
                        setResult(RESULT_CANCELED);
                        finish();
                    }
                });

        // the search button is not meaningful here; relabel it as a shortcut to /sdcard
        TextView searchBtn = (TextView) findViewById(com.jianyue.reader.R.id.browser_search);
        searchBtn.setText("存储卡");
        searchBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                File sd = null;
                try {
                    sd = Environment.getExternalStorageDirectory();
                } catch (Throwable ignored) {
                }
                if (sd == null) {
                    sd = new File("/sdcard");
                }
                if (sd.isDirectory()) {
                    open(sd);
                } else {
                    toast("找不到存储卡");
                }
            }
        });

        showRoots();
    }

    private void showRoots() {
        final List<File> roots = new ArrayList<File>();
        addRoot(roots, new File("/sdcard"));
        try {
            File ext = Environment.getExternalStorageDirectory();
            addRoot(roots, ext);
        } catch (Throwable ignored) {
        }
        addRoot(roots, new File("/data/local"));
        addRoot(roots, new File("/"));

        if (roots.size() == 0) {
            toast("没有可访问的位置");
            return;
        }
        // prefer /sdcard when it exists, that is where a user would put the json
        for (int i = 0; i < roots.size(); i++) {
            if ("/sdcard".equals(roots.get(i).getAbsolutePath())) {
                open(roots.get(i));
                return;
            }
        }
        open(roots.get(0));
    }

    private void addRoot(List<File> out, File f) {
        if (f == null || !f.isDirectory()) {
            return;
        }
        String p = f.getAbsolutePath();
        for (int i = 0; i < out.size(); i++) {
            if (p.equals(out.get(i).getAbsolutePath())) {
                return;
            }
        }
        out.add(f);
    }

    private void open(File dir) {
        current = dir;
        entries.clear();
        File[] kids = null;
        try {
            kids = dir.listFiles();
        } catch (Throwable ignored) {
        }
        List<File> dirs = new ArrayList<File>();
        List<File> files = new ArrayList<File>();
        if (kids != null) {
            for (int i = 0; i < kids.length; i++) {
                File k = kids[i];
                String n = k.getName();
                if (n == null || n.length() == 0 || n.charAt(0) == '.') {
                    continue;
                }
                try {
                    if (k.isDirectory()) {
                        dirs.add(k);
                    } else if (accept(n)) {
                        files.add(k);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        sortBy(dirs);
        sortBy(files);
        entries.addAll(dirs);
        entries.addAll(files);

        adapter.notifyDataSetChanged();
        pathView.setText(dir.getAbsolutePath() + (bookMode ? "   （选 txt / epub / html）" : ""));
        if (entries.size() == 0) {
            toast(bookMode ? "此目录没有 txt / epub / html" : "此目录没有 .json 文件");
        }
    }

    /** What this picker is looking for: book files, or book sources. */
    private boolean accept(String name) {
        String n = name.toLowerCase();
        if (bookMode) {
            return n.endsWith(".txt") || n.endsWith(".epub")
                    || n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".xhtml");
        }
        return n.endsWith(".json") || n.endsWith(".txt");
    }

    private void sortBy(List<File> l) {
        for (int i = 1; i < l.size(); i++) {
            File cur = l.get(i);
            int j = i - 1;
            while (j >= 0 && l.get(j).getName().compareToIgnoreCase(cur.getName()) > 0) {
                l.set(j + 1, l.get(j));
                j--;
            }
            l.set(j + 1, cur);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private class PickerAdapter extends BaseAdapter {
        public int getCount() {
            return entries.size();
        }

        public Object getItem(int position) {
            return entries.get(position);
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
            File f = entries.get(position);
            boolean isDir = f.isDirectory();
            title.setText((isDir ? "[目录] " : "") + f.getName());
            sub.setText(isDir ? "文件夹" : FileUtils.sizeText(f.length()));
            return v;
        }
    }
}

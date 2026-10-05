package com.jianyue.reader.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.jianyue.reader.R;
import com.jianyue.reader.engine.Engine;
import com.jianyue.reader.model.BookSource;
import com.jianyue.reader.model.Books;
import com.jianyue.reader.model.SourceStore;
import com.jianyue.reader.model.SourceSwitcher;

/**
 * 搜索 (search).
 *
 * Sources are queried in PARALLEL by {@link Engine#searchAll}. That is the main speed fix:
 * a serial loop made the total wait equal the SUM of every site's latency, while almost all
 * of that time is spent blocked on the network rather than on the CPU.
 *
 * MERGING (同名书合并)
 * -------------------
 * The same novel is usually found on five or six sources at once, which used to produce one
 * identical-looking row per source. Results are now grouped by a normalised title
 * ({@link Engine#normalizeTitle}), one row per book, with the source name shown in the
 * subtitle and the candidate sources kept in {@link SourceSwitcher} so the detail and reader
 * screens can offer 换源.
 *
 * Which source a group opens on is chosen at random, because there is no way to know up
 * front which one is readable - the user switches if the first pick is bad.
 *
 * Rotation safety
 * ---------------
 * A search used to be lost whenever the device rotated. Two things caused it:
 *   - the activity was destroyed and recreated, taking the state (a plain field plus a
 *     running thread) with it, while the worker kept posting into a dead handler;
 *   - the worker thread was never told to stop, so it also kept the dead activity alive.
 *
 * The fix has two parts:
 *   - the manifest declares android:configChanges for orientation, so this activity is NOT
 *     recreated on rotation and the search is simply never interrupted;
 *   - the search state lives in a static holder, so even if the activity is recreated
 *     (process death, or a configuration change the manifest does not cover) the results and
 *     the running flag survive and the new instance re-attaches to them.
 */
public class SearchActivity extends Activity {

    private static final String TAG = "JianYue";

    /**
     * Search state that deliberately outlives the activity, so a restart does not lose an
     * in-flight search. Only touched from the UI thread, so no synchronisation is needed.
     */
    private static final class State {
        static boolean running;
        static String keyword = "";
        static final List<Books.Book> books = new ArrayList<Books.Book>();
        static final List<SourceSwitcher.Group> groups = new ArrayList<SourceSwitcher.Group>();
        static final List<String> failures = new ArrayList<String>();
        static int total;
    }

    private SourceStore store;
    private EditText input;
    private TextView status;
    private TextView empty;
    private ListView list;
    private ResultAdapter adapter;
    private final Handler handler = new Handler();

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.setup(this, 0xFFF7F7F7);
        setContentView(R.layout.act_search);

        store = new SourceStore(this);
        input = (EditText) findViewById(R.id.search_input);
        status = (TextView) findViewById(R.id.search_status);
        empty = (TextView) findViewById(R.id.search_empty);
        list = (ListView) findViewById(R.id.search_list);

        adapter = new ResultAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView parent, View view, int position, long id) {
                Object o = adapter.getItem(position);
                if (o instanceof SourceSwitcher.Group) {
                    openGroup((SourceSwitcher.Group) o);
                } else if (o instanceof Books.Book) {
                    openBook((Books.Book) o);
                }
            }
        });
        // Long press on a merged row lists every source the book was found on, so the user
        // can go straight to a specific one instead of opening and then switching.
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView parent, View view, int position, long id) {
                Object o = adapter.getItem(position);
                if (o instanceof SourceSwitcher.Group) {
                    pickSource((SourceSwitcher.Group) o, null);
                    return true;
                }
                return false;
            }
        });

        findViewById(R.id.search_go).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startSearch();
            }
        });

        // Re-attach to a search that is running or already finished: this is what keeps the
        // results on screen across a rotation.
        if (State.running || State.groups.size() > 0) {
            input.setText(State.keyword);
            if (State.running) {
                status.setText("搜索中…（" + State.total + " 个书源，并行）");
                empty.setVisibility(View.GONE);
                list.setVisibility(View.VISIBLE);
            }
        }
        applyResults();

        // Host-driven entry point: API 1 cannot synthesise a tap, so a search cannot be
        // started from the host otherwise. Runs the exact same startSearch() path.
        String kw = getIntent().getStringExtra("keyword");
        if (kw != null && kw.length() > 0 && !State.running) {
            input.setText(kw);
            Log.i(TAG, "search: auto-start for keyword=" + kw);
            startSearch();
        }
    }

    protected void onResume() {
        super.onResume();
        // a search may have completed while this activity was in the background, or a source
        // may have been switched downstream (which moves the group's current source)
        if (!State.running && State.groups.size() > 0) {
            applyResults();
        }
    }

    // ------------------------------------------------------------- searching

    private void startSearch() {
        if (State.running) {
            toast("正在搜索中");
            return;
        }
        final String kw = input.getText().toString().trim();
        if (kw.length() == 0) {
            toast("请输入书名");
            return;
        }
        List<BookSource> sources = store.enabled();
        if (sources.size() == 0) {
            new AlertDialog.Builder(this)
                    .setTitle("还没有可用的书源")
                    .setMessage("请先到「书源」页导入书源 JSON。\n\n"
                            + "格式说明：点「书源」→「格式说明」。")
                    .setPositiveButton("去导入", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            startActivity(new Intent(SearchActivity.this, SourcesActivity.class));
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        State.running = true;
        State.keyword = kw;
        State.books.clear();
        State.groups.clear();
        State.failures.clear();
        State.total = sources.size();

        adapter.setItems(new ArrayList<SourceSwitcher.Group>());
        status.setText("搜索中…（" + sources.size() + " 个书源，并行）");
        empty.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
        Log.i(TAG, "search: kw=" + kw + " sources=" + sources.size());

        final List<BookSource> snapshot = new ArrayList<BookSource>(sources);
        Thread t = new Thread(new Runnable() {
            public void run() {
                final List<String> failures = new ArrayList<String>();
                final List<Books.Book> found = Engine.searchAll(snapshot, kw, 1, failures);
                final List<SourceSwitcher.Group> merged = merge(found);
                handler.post(new Runnable() {
                    public void run() {
                        State.books.clear();
                        State.books.addAll(found);
                        State.groups.clear();
                        State.groups.addAll(merged);
                        State.failures.clear();
                        State.failures.addAll(failures);
                        State.running = false;
                        SourceSwitcher.register(merged);
                        applyResults();
                        Log.i(TAG, "search done: " + found.size() + " hits, "
                                + merged.size() + " books after merge, failures="
                                + failures.size());
                    }
                });
            }
        });
        t.start();
    }

    // --------------------------------------------------------------- merging

    /**
     * Group hits by normalised title, preserving the order in which each book first appeared.
     *
     * The current source of each group is picked at random. There is no ranking signal that
     * survives the TLS-1.0 filtering (a site being reachable says nothing about whether its
     * 目录 parses), so a random pick is honest about what we know, and 换源 is one tap away.
     */
    private static List<SourceSwitcher.Group> merge(List<Books.Book> hits) {
        List<SourceSwitcher.Group> groups = new ArrayList<SourceSwitcher.Group>();
        Map<String, SourceSwitcher.Group> byKey = new HashMap<String, SourceSwitcher.Group>();
        if (hits == null) {
            return groups;
        }
        for (int i = 0; i < hits.size(); i++) {
            Books.Book b = hits.get(i);
            if (b == null || b.name == null || b.name.length() == 0) {
                continue;
            }
            if (b.bookUrl == null || b.bookUrl.length() == 0) {
                continue;
            }
            String key = Engine.mergeKey(b);
            if (key.length() == 0) {
                continue;
            }
            SourceSwitcher.Group g = byKey.get(key);
            if (g == null) {
                g = SourceSwitcher.newGroup(key, b.name);
                byKey.put(key, g);
                groups.add(g);
            }
            g.add(new SourceSwitcher.SrcBook(b.sourceUrl, b.sourceName, b));
        }
        Random rnd = new Random();
        for (int i = 0; i < groups.size(); i++) {
            SourceSwitcher.Group g = groups.get(i);
            g.select(g.size() > 1 ? rnd.nextInt(g.size()) : 0);
        }
        return groups;
    }

    private void applyResults() {
        adapter.setItems(new ArrayList<SourceSwitcher.Group>(State.groups));
        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(State.groups.size()).append(" 本");
        if (State.books.size() > State.groups.size()) {
            sb.append("（").append(State.books.size()).append(" 个来源）");
        }
        if (State.failures.size() > 0) {
            sb.append("，").append(State.failures.size()).append(" 个书源无结果");
        }
        status.setText(sb.toString());
        if (State.groups.size() > 0) {
            empty.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
        } else if (!State.running && State.total > 0) {
            StringBuilder m = new StringBuilder("没有找到结果\n\n");
            for (int i = 0; i < State.failures.size() && i < 5; i++) {
                m.append(State.failures.get(i)).append('\n');
            }
            setEmpty(m.toString());
        } else if (!State.running) {
            setEmpty("输入书名后点「搜索」\n会在所有已启用的书源里查找\n\n"
                    + "同一本书在多个源都有时只显示一行，\n"
                    + "点进去可以换源，长按可以直接选源。");
        }
    }

    private void setEmpty(String text) {
        empty.setText(text);
        empty.setVisibility(View.VISIBLE);
    }

    /** Open the group's current source in the detail screen. */
    private void openGroup(SourceSwitcher.Group g) {
        SourceSwitcher.SrcBook s = g.current();
        if (s == null) {
            toast("没有可用的来源");
            return;
        }
        Intent i = new Intent(this, BookDetailActivity.class);
        i.putExtra("mergeKey", g.key);
        i.putExtra("sourceUrl", s.sourceUrl);
        i.putExtra("bookUrl", s.book.bookUrl);
        i.putExtra("name", s.book.name);
        i.putExtra("author", s.book.author);
        startActivity(i);
    }

    /** Source picker used by the long-press and by 换源 downstream. */
    private void pickSource(final SourceSwitcher.Group g, final Books.Book ignore) {
        final String[] labels = new String[g.size()];
        final int cur = g.indexOfCurrent();
        for (int i = 0; i < g.size(); i++) {
            SourceSwitcher.SrcBook s = g.items.get(i);
            labels[i] = (i == cur ? "● " : "○ ") + s.sourceName;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择书源：" + g.name)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        g.select(which);
                        applyResults();
                        openGroup(g);
                    }
                })
                .show();
    }

    private void openBook(Books.Book b) {
        Intent i = new Intent(this, BookDetailActivity.class);
        i.putExtra("sourceUrl", b.sourceUrl);
        i.putExtra("bookUrl", b.bookUrl);
        i.putExtra("name", b.name);
        i.putExtra("author", b.author);
        startActivity(i);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /**
     * Renders one merged book: "【来源名 · N 源】作者 | 类型 | 字数".
     *
     * The source comes FIRST, inside brackets, on purpose. The row is a single line with
     * ellipsize=end, and with the old order (author | kind | wordCount | source) the source was
     * pushed past the right edge on any book with an author and a category -- i.e. every row
     * silently lost the one field that says where the book is coming from ("搜索后要每本书都
     * 显示书源"). Leading with it makes the source impossible to ellipsize away, and putting the
     * candidate count next to it keeps the "there is more than one source, tap to switch" hint
     * visible too.
     */
    private class ResultAdapter extends BaseAdapter {
        private final List<SourceSwitcher.Group> items = new ArrayList<SourceSwitcher.Group>();

        void setItems(List<SourceSwitcher.Group> list) {
            items.clear();
            if (list != null) {
                items.addAll(list);
            }
            notifyDataSetChanged();
        }

        public int getCount() {
            return items.size();
        }

        public Object getItem(int position) {
            return (position >= 0 && position < items.size()) ? items.get(position) : null;
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.row_book, null);
            }
            SourceSwitcher.Group g = items.get(position);
            SourceSwitcher.SrcBook cur = g.current();
            Books.Book b = (cur == null) ? null : cur.book;
            TextView title = (TextView) v.findViewById(R.id.row_title);
            TextView sub = (TextView) v.findViewById(R.id.row_sub);
            title.setText(g.name);
            StringBuilder sb = new StringBuilder();
            if (cur != null && cur.sourceName.length() > 0) {
                sb.append("【").append(cur.sourceName);
                if (g.size() > 1) {
                    sb.append(" · ").append(g.size()).append(" 源");
                }
                sb.append("】");
            }
            if (b != null && b.author != null && b.author.length() > 0) {
                if (sb.length() > 0) {
                    sb.append(" ");
                }
                sb.append(b.author);
            }
            if (b != null && b.kind != null && b.kind.length() > 0) {
                if (sb.length() > 0) {
                    sb.append("  |  ");
                }
                sb.append(b.kind);
            }
            if (b != null && b.wordCount != null && b.wordCount.length() > 0) {
                if (sb.length() > 0) {
                    sb.append("  |  ");
                }
                sb.append(b.wordCount).append("字");
            }
            sub.setText(sb.toString());
            return v;
        }
    }
}

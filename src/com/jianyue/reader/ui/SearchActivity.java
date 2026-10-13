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

import com.jianyue.reader.R;
import com.jianyue.reader.engine.Engine;
import com.jianyue.reader.engine.SearchJob;
import com.jianyue.reader.model.BookSource;
import com.jianyue.reader.model.Books;
import com.jianyue.reader.model.SourceHealth;
import com.jianyue.reader.model.SourceStore;
import com.jianyue.reader.model.SourceSwitcher;

/**
 * 搜索 (search).
 *
 * HOW A SEARCH RUNS NOW
 * ---------------------
 * Sources are handed to {@link SearchJob}, which keeps a wide set of requests in flight,
 * skips hosts that already failed in this process, and reports each source the moment it
 * finishes. This screen shows those reports as they arrive:
 *
 *   - the first hits appear in about one round trip instead of after the whole list;
 *   - the status line counts progress ("搜索中 37/231 · 找到 12 本 · 跳过 9");
 *   - the search button turns into 停止, and stopping keeps everything found so far.
 *
 * That last point is the real answer to "200 sources take minutes": the sources are ordered
 * by {@link SourceHealth} so the sites that answered last time are searched first, which
 * means stopping after a few seconds gives the same books as waiting for the tail of dead
 * sites to time out.
 *
 * MERGING (同名书合并)
 * -------------------
 * The same novel is usually found on several sources, which used to produce one identical
 * row per source. Results are grouped by normalised title ({@link Engine#mergeKey}) as they
 * stream in, one row per book, with the source shown in the subtitle and the candidates kept
 * in {@link SourceSwitcher} for 换源.
 *
 * Rotation safety
 * ---------------
 * The search state lives in a static holder, so an activity restart does not lose an
 * in-flight search, and the manifest declares configChanges for orientation anyway.
 */
public class SearchActivity extends Activity {

    private static final String TAG = "JianYue";

    /** Repaint the list at most this often while results stream in. */
    private static final long UI_THROTTLE_MS = 250L;

    /**
     * Search state that deliberately outlives the activity. Touched from the UI thread and
     * (for the counters only) from one worker thread, so the mutable fields are volatile.
     */
    private static final class State {
        static volatile boolean running;
        /** Set by the 停止 button; polled by every worker before it starts a source. */
        static volatile boolean stop;
        static volatile int done;
        static volatile int total;
        static volatile int skipped;
        static volatile int timedOut;
        static String keyword = "";
        static boolean stopped;
        static final List<Books.Book> books = new ArrayList<Books.Book>();
        /** Every merged book, in no particular order. {@link #results()} decides display order. */
        static final List<SourceSwitcher.Group> groups = new ArrayList<SourceSwitcher.Group>();
        static final List<String> failures = new ArrayList<String>();
        /** title key -> group, for incremental merging while results stream in. */
        static final Map<String, SourceSwitcher.Group> byKey =
                new HashMap<String, SourceSwitcher.Group>();

        /**
         * Every mutation of {@link #groups} happens under this lock (the worker thread
         * appends, the UI thread copies), so a streaming search cannot make the list view
         * iterate a list that is being modified.
         */
        static List<SourceSwitcher.Group> snapshotGroups() {
            synchronized (books) {
                return new ArrayList<SourceSwitcher.Group>(groups);
            }
        }

        static int groupCount() {
            synchronized (books) {
                return groups.size();
            }
        }

        /**
         * What the list shows, in the order it should be read.
         *
         * Ordering by RELEVANCE, not by arrival. Results stream in from whichever source
         * answers first, so raw order means "the first site that came back" - which is how a
         * search for 诡舍 ends up with 都市神王录 on the first row (a source that dumps its
         * whole catalogue) while the book the user typed sits third. The searched title
         * always comes first; the rest follow from the best source that offered them.
         */
        static List<SourceSwitcher.Group> results() {
            List<SourceSwitcher.Group> all;
            synchronized (books) {
                all = new ArrayList<SourceSwitcher.Group>(groups);
            }
            List<SourceSwitcher.Group> exact = new ArrayList<SourceSwitcher.Group>();
            List<SourceSwitcher.Group> rest = new ArrayList<SourceSwitcher.Group>();
            for (int i = 0; i < all.size(); i++) {
                SourceSwitcher.Group g = all.get(i);
                if (SourceSwitcher.titleMatches(g.name, keyword)) {
                    exact.add(g);
                } else {
                    rest.add(g);
                }
            }
            sortByQuality(exact);
            sortByQuality(rest);
            exact.addAll(rest);
            return exact;
        }

        /** Best-placed source first; then the book offered by more sources; then by title. */
        private static void sortByQuality(List<SourceSwitcher.Group> list) {
            try {
                java.util.Collections.sort(list, new java.util.Comparator<SourceSwitcher.Group>() {
                    public int compare(SourceSwitcher.Group a, SourceSwitcher.Group b) {
                        int ra = a.bestRank();
                        int rb = b.bestRank();
                        if (ra != rb) {
                            return ra - rb;
                        }
                        if (a.size() != b.size()) {
                            return b.size() - a.size();
                        }
                        return a.name.compareTo(b.name);
                    }
                });
            } catch (Throwable ignored) {
            }
        }

        /**
         * Sources that have actually served content move to the front of their book's
         * candidate list. A book found on twenty sources is only as good as the source the
         * row opens with, and searching says nothing about serving.
         */
        private static void pickBestSource(SourceSwitcher.Group g) {
            List<SourceSwitcher.SrcBook> ranked = SourceSwitcher.ranked(g);
            for (int i = 0; i < ranked.size(); i++) {
                if (ranked.get(i) != g.current()) {
                    g.select(g.items.indexOf(ranked.get(i)));
                    return;
                }
            }
        }

        static void reset() {
            synchronized (books) {
                books.clear();
                groups.clear();
                byKey.clear();
            }
            failures.clear();
            done = 0;
            skipped = 0;
            timedOut = 0;
            stopped = false;
        }
    }

    private SourceStore store;
    private EditText input;
    private TextView status;
    private TextView empty;
    private ListView list;
    private ResultAdapter adapter;
    /** The 搜索 / 停止 control (a TextView in the layout, so it can change label in place). */
    private TextView go;
    private final Handler handler = new Handler();
    private long lastUiUpdate;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.setup(this, 0xFFF7F7F7);
        setContentView(R.layout.act_search);

        store = new SourceStore(this);
        SourceHealth.attach(this);
        input = (EditText) findViewById(R.id.search_input);
        status = (TextView) findViewById(R.id.search_status);
        empty = (TextView) findViewById(R.id.search_empty);
        list = (ListView) findViewById(R.id.search_list);
        go = (TextView) findViewById(R.id.search_go);

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

        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (State.running) {
                    stopSearch();
                } else {
                    startSearch();
                }
            }
        });

        // Re-attach to a search that is running or already finished: this is what keeps the
        // results on screen across a rotation.
        if (State.running || State.groupCount() > 0) {
            input.setText(State.keyword);
            if (State.running) {
                go.setText("停止");
                empty.setVisibility(View.GONE);
                list.setVisibility(View.VISIBLE);
                updateStatus();
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
        if (!State.running && State.groupCount() > 0) {
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
        State.stop = false;
        State.keyword = kw;
        State.reset();
        State.total = sources.size();

        adapter.setItems(new ArrayList<SourceSwitcher.Group>());
        go.setText("停止");
        status.setText("搜索中…（" + sources.size() + " 个书源）");
        empty.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
        Log.i(TAG, "search: kw=" + kw + " sources=" + sources.size()
                + " knownGood=" + SourceHealth.knownGoodCount());

        final List<BookSource> snapshot = new ArrayList<BookSource>(sources);
        Thread t = new Thread(new Runnable() {
            public void run() {
                final long t0 = System.currentTimeMillis();
                final List<String> failures = new ArrayList<String>();
                final List<Books.Book> found = SearchJob.run(snapshot, kw, 1, failures,
                        new SearchJob.Listener() {
                            public void onSourceDone(int done, int total, BookSource src,
                                                     List<Books.Book> books, String note) {
                                State.done = done;
                                deliver(books, note);
                            }

                            public boolean cancelled() {
                                return State.stop;
                            }
                        }, SearchJob.budgetMs());
                handler.post(new Runnable() {
                    public void run() {
                        State.failures.clear();
                        State.failures.addAll(failures);
                        State.books.clear();
                        State.books.addAll(found);
                        State.running = false;
                        // The search is over, so the ordering can change without moving a row
                        // the user is about to tap: promote the sources known to serve
                        // content, then let the list settle into relevance order.
                        List<SourceSwitcher.Group> all = State.snapshotGroups();
                        for (int i = 0; i < all.size(); i++) {
                            State.pickBestSource(all.get(i));
                        }
                        SourceSwitcher.register(all);
                        go.setText("搜索");
                        go.setEnabled(true);
                        applyResults();
                        Log.i(TAG, "search done: " + found.size() + " hits, "
                                + State.groupCount() + " books after merge, failures="
                                + failures.size() + " done=" + State.done + "/" + State.total
                                + " skippedHost=" + State.skipped + " stopped=" + State.stopped
                                + " ms=" + (System.currentTimeMillis() - t0));
                        Log.i(TAG, "search top: " + topTitles());
                    }
                });
            }
        });
        t.start();
    }

    /**
     * Called on a worker thread for every finished source. The books go straight into the
     * shared lists (so nothing is lost if the activity is recreated) and the list is
     * repainted on a throttle, because a 200-source search would otherwise post hundreds of
     * refreshes a second.
     */
    private void deliver(List<Books.Book> books, String note) {
        synchronized (State.books) {
            if (books != null && books.size() > 0) {
                for (int i = 0; i < books.size(); i++) {
                    Books.Book b = books.get(i);
                    State.books.add(b);
                    addToGroup(b);
                }
            }
            if (note != null) {
                State.skipped++;
                if ("超时未查".equals(note)) {
                    State.timedOut++;
                }
            }        }
        long now = System.currentTimeMillis();
        if (now - lastUiUpdate < UI_THROTTLE_MS) {
            return;
        }
        lastUiUpdate = now;
        handler.post(new Runnable() {
            public void run() {
                if (State.running) {
                    updateStatus();
                }
                adapter.setItems(State.results());
            }
        });
    }

    /**
     * First few rows as the user sees them, with the source each row would open with.
     * Written once per search so a verification run can assert on the ORDER, which is the
     * part of this screen that is otherwise invisible to a script.
     */
    private static String topTitles() {
        List<SourceSwitcher.Group> shown = State.results();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shown.size() && i < 6; i++) {
            SourceSwitcher.Group g = shown.get(i);
            SourceSwitcher.SrcBook cur = g.current();
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(i + 1).append('.').append(g.name);
            if (cur != null) {
                sb.append('[').append(cur.sourceName).append(']');
            }
            sb.append('/').append(g.size()).append("源");
        }
        return sb.toString();
    }

    /** Merge one book into the group list, creating the group on first sight. */
    private static void addToGroup(Books.Book b) {
        if (b == null || b.name == null || b.name.length() == 0) {
            return;
        }
        if (b.bookUrl == null || b.bookUrl.length() == 0) {
            return;
        }
        String key = Engine.mergeKey(b);
        if (key.length() == 0) {
            return;
        }
        SourceSwitcher.Group g = State.byKey.get(key);
        if (g == null) {
            g = SourceSwitcher.newGroup(key, b.name);
            State.byKey.put(key, g);
            State.groups.add(g);
        }
        g.add(new SourceSwitcher.SrcBook(b.sourceUrl, b.sourceName, b));
        // The first source that found the book stays selected: with results streaming in,
        // re-picking on every arrival would move the row the user is about to tap.
        if (g.current() == null) {
            g.select(0);
        }
    }

    private void stopSearch() {
        State.stop = true;
        State.stopped = true;
        go.setEnabled(false);
        status.setText("正在停止…已找到 " + State.groupCount() + " 本");
        Log.i(TAG, "search: stop requested at " + State.done + "/" + State.total);
    }

    private void updateStatus() {
        StringBuilder sb = new StringBuilder("搜索中 ");
        sb.append(State.done).append('/').append(State.total);
        sb.append(" · 找到 ").append(State.groupCount()).append(" 本");
        if (State.skipped > 0) {
            sb.append(" · 跳过 ").append(State.skipped).append(" 个不可达源");
        }
        status.setText(sb.toString());
    }

    private void applyResults() {
        adapter.setItems(State.results());
        int groups = State.groupCount();
        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(groups).append(" 本");
        if (State.books.size() > groups) {
            sb.append("（").append(State.books.size()).append(" 个来源）");
        }
        if (State.stopped) {
            sb.append("，已停止（查了 ").append(State.done).append('/').append(State.total)
              .append(" 个书源）");
        } else if (State.timedOut > 0) {
            sb.append("，").append(State.timedOut).append(" 个书源超时未查");
        } else if (State.failures.size() > 0) {
            sb.append("，").append(State.failures.size()).append(" 个书源无结果");
        }
        if (State.skipped > 0) {
            sb.append("，跳过 ").append(State.skipped).append(" 个不可达源");
        }
        status.setText(sb.toString());
        if (groups > 0) {
            empty.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
        } else if (!State.running && State.total > 0 && State.done > 0) {
            StringBuilder m = new StringBuilder("没有找到结果\n\n");
            for (int i = 0; i < State.failures.size() && i < 5; i++) {
                m.append(State.failures.get(i)).append('\n');
            }
            if (State.skipped > 0) {
                m.append("\n跳过 ").append(State.skipped)
                 .append(" 个这次已确认不可达的书源。\n再搜一次会重新尝试它们。");
            }
            setEmpty(m.toString());
        } else if (!State.running) {
            setEmpty("输入书名后点「搜索」\n会在所有已启用的书源里查找\n\n"
                    + "同一本书在多个源都有时只显示一行，\n"
                    + "点进去可以换源，长按可以直接选源。\n\n"
                    + "结果会边搜边出现，随时可以「停止」，\n"
                    + "已找到的书会保留。");
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
        final String[] labels;
        final int cur;
        synchronized (State.books) {
            labels = new String[g.size()];
            cur = g.indexOfCurrent();
            for (int i = 0; i < g.size(); i++) {
                SourceSwitcher.SrcBook s = g.items.get(i);
                labels[i] = (i == cur ? "● " : "○ ") + s.sourceName;
            }
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
            String titleText;
            String subText;
            // A streaming search appends to the group while the list is being painted, so
            // every read of it happens under the same lock the worker uses.
            synchronized (State.books) {
                SourceSwitcher.SrcBook cur = g.current();
                Books.Book b = (cur == null) ? null : cur.book;
                titleText = g.name;
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
                subText = sb.toString();
            }
            TextView title = (TextView) v.findViewById(R.id.row_title);
            TextView sub = (TextView) v.findViewById(R.id.row_sub);
            title.setText(titleText);
            sub.setText(subText);
            return v;
        }
    }
}

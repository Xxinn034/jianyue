package com.jianyue.reader.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import com.jianyue.reader.engine.Engine;
import com.jianyue.reader.model.BookSource;
import com.jianyue.reader.model.Books;
import com.jianyue.reader.model.ProgressStore;
import com.jianyue.reader.model.SourceStore;
import com.jianyue.reader.model.SourceSwitcher;

/**
 * 书籍详情 + 目录 (book detail with the table of contents below it).
 *
 * A single ListView with a header view: the header holds the book info and its actions,
 * the rows are chapters. That keeps one scroll container, which matters because nested
 * ScrollView + ListView does not behave on API 1.
 *
 * 换源 (switching source)
 * -----------------------
 * When the search screen found this book on more than one source the group is registered in
 * {@link SourceSwitcher} and this screen offers a 换源 button. Switching:
 *   1. remembers the chapter the user is on as a FRACTION of the old 目录 (chapter numbering
 *      is not comparable between sources, the fraction is),
 *   2. loads the new source's 目录,
 *   3. moves the reading position onto the chapter at the same fraction.
 *
 * If the new source's 目录 turns out to be empty the switch is taken back, because "another
 * source with no chapters" is worse than the one that at least worked.
 */
public class BookDetailActivity extends Activity {

    private static final String TAG = "JianYue";

    private SourceStore sourceStore;
    private ProgressStore progressStore;
    private BookSource source;
    private Books.Book book;
    private final List<Books.Chapter> toc = new ArrayList<Books.Chapter>();
    private ChapterAdapter adapter;
    private final Handler handler = new Handler();

    private TextView tocCount;
    private TextView infoNameView;
    private TextView infoMetaView;
    private TextView infoIntroView;
    private View switchButton;

    /** merged-source group, may be null (book reached from the shelf) */
    private SourceSwitcher.Group group;
    /** chapter fraction to restore after a 换源, -1 when unknown */
    private float restoreRatio = -1f;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // no title bar and no grey band above the content; see Ui
        Ui.setup(this, 0xFFF7F7F7);
        setContentView(com.jianyue.reader.R.layout.act_detail);

        sourceStore = new SourceStore(this);
        progressStore = new ProgressStore(this);

        String sourceUrl = getIntent().getStringExtra("sourceUrl");
        book = new Books.Book();
        book.sourceUrl = sourceUrl == null ? "" : sourceUrl;
        book.bookUrl = nz(getIntent().getStringExtra("bookUrl"));
        book.name = nz(getIntent().getStringExtra("name"));
        book.author = nz(getIntent().getStringExtra("author"));
        book.intro = nz(getIntent().getStringExtra("intro"));
        book.kind = nz(getIntent().getStringExtra("kind"));
        book.wordCount = nz(getIntent().getStringExtra("wordCount"));

        // The merged-source group, by key when the search screen passed one, otherwise by
        // title (which is what happens when a source is switched and the activity restarts).
        String mergeKey = getIntent().getStringExtra("mergeKey");
        group = SourceSwitcher.find(mergeKey);
        if (group == null) {
            group = SourceSwitcher.findByName(book.name);
        }

        source = findSource(book.sourceUrl);
        if (source != null) {
            book.sourceName = source.name;
        }

        ListView list = (ListView) findViewById(com.jianyue.reader.R.id.detail_list);
        View header = getLayoutInflater().inflate(
                com.jianyue.reader.R.layout.view_book_header, null);
        list.addHeaderView(header, null, false);
        adapter = new ChapterAdapter();
        list.setAdapter(adapter);

        tocCount = (TextView) findViewById(com.jianyue.reader.R.id.detail_toc_count);
        ((TextView) findViewById(com.jianyue.reader.R.id.detail_title)).setText(book.name);

        infoNameView = (TextView) header.findViewById(com.jianyue.reader.R.id.info_name);
        infoMetaView = (TextView) header.findViewById(com.jianyue.reader.R.id.info_meta);
        infoIntroView = (TextView) header.findViewById(com.jianyue.reader.R.id.info_intro);
        infoNameView.setText(book.name);
        updateInfoMeta();
        infoIntroView.setText(book.intro.length() > 0 ? book.intro : "（暂无简介）");

        header.findViewById(com.jianyue.reader.R.id.btn_read_first)
                .setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        openChapter(0);
                    }
                });
        // "加入书架" replaces the old "最新章" shortcut: putting the book on the shelf is the
        // more useful action from this screen, and the last chapter is one tap away in the
        // table of contents below.
        header.findViewById(com.jianyue.reader.R.id.btn_add_shelf)
                .setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        addToShelf();
                    }
                });
        switchButton = header.findViewById(com.jianyue.reader.R.id.btn_switch_source);
        switchButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showSourcePicker();
            }
        });
        updateSwitchButton();

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView parent, View view, int position, long id) {
                // position includes the header, which is why we subtract 1
                openChapter(position - 1);
            }
        });

        if (source == null) {
            toast("找不到对应的书源，可能已被删除");
            return;
        }
        loadAsync();
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * The header's info line: 书源 first, then the book's author / category / length.
     *
     * The source is written out here rather than being left to {@link Books.Book#subtitle()}
     * because the request was explicit - "点击看到简介的时候就显示书源且可以更换书源". In the old
     * order the source name was the LAST field of a wrapped line, so on a phone it was routinely
     * below the fold, and the 换源 button next to it only existed for books that had more than one
     * source. Now the source is the first thing on the line, the candidate count is spelled out,
     * and the 换源 button is always present (see updateSwitchButton).
     */
    private void updateInfoMeta() {
        if (infoMetaView == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("书源：").append(book.sourceName.length() > 0 ? book.sourceName : "未知");
        if (group != null && group.size() > 1) {
            sb.append("（共 ").append(group.size()).append(" 个源，可换源）");
        }
        StringBuilder sub = new StringBuilder();
        if (book.author.length() > 0) {
            sub.append(book.author);
        }
        if (book.kind.length() > 0) {
            if (sub.length() > 0) {
                sub.append("  |  ");
            }
            sub.append(book.kind);
        }
        if (book.wordCount.length() > 0) {
            if (sub.length() > 0) {
                sub.append("  |  ");
            }
            sub.append(book.wordCount);
        }
        if (sub.length() > 0) {
            sb.append("\n").append(sub);
        }
        infoMetaView.setText(sb.toString());
    }

    private BookSource findSource(String url) {
        return sourceStore.findByUrl(url);
    }

    private void updateSwitchButton() {
        if (switchButton == null) {
            return;
        }
        // Present whenever this book was reached through a search, so 换源 is always discoverable
        // from the detail screen; with a single candidate it explains that instead of hiding.
        boolean known = (group != null);
        switchButton.setVisibility(known ? View.VISIBLE : View.GONE);
        if (known) {
            ((TextView) switchButton).setText(
                    group.size() > 1 ? ("换源(" + group.size() + ")") : "换源");
        }
    }

    private void loadAsync() {
        tocCount.setText("加载中…");
        final BookSource src = source;
        final Books.Book b = book;
        Thread t = new Thread(new Runnable() {
            public void run() {
                // fill in anything the search row did not provide
                Engine.loadBookInfo(src, b);
                final List<Books.Chapter> got = Engine.loadToc(src, b);
                handler.post(new Runnable() {
                    public void run() {
                        toc.clear();
                        toc.addAll(got);
                        adapter.notifyDataSetChanged();
                        tocCount.setText(toc.size() + " 章");
                        updateInfoMeta();
                        if (infoIntroView != null && b.intro.length() > 0) {
                            infoIntroView.setText(b.intro);
                        }
                        if (infoNameView != null && b.name.length() > 0) {
                            infoNameView.setText(b.name);
                            ((TextView) findViewById(
                                    com.jianyue.reader.R.id.detail_title)).setText(b.name);
                        }
                        if (toc.size() == 0) {
                            toast("目录为空：请检查书源的 ruleToc 规则");
                        }
                        Log.i(TAG, "detail: toc=" + toc.size() + " book=" + b.name);
                    }
                });
            }
        });
        t.start();
    }

    // -------------------------------------------------------------- 换源

    /** Ask which of the book's sources to switch to. */
    private void showSourcePicker() {
        if (group == null || group.size() <= 1) {
            toast("这本书只在一个书源里找到");
            return;
        }
        final int cur = group.indexOfCurrent();
        String[] labels = new String[group.size()];
        for (int i = 0; i < group.size(); i++) {
            SourceSwitcher.SrcBook s = group.items.get(i);
            labels[i] = (i == cur ? "● " : "○ ") + s.sourceName;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("换源：" + group.name)
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which != cur) {
                            doSwitch(which);
                        }
                    }
                })
                .show();
    }

    /**
     * Switch to another source for the same book and keep the reading position.
     *
     * The position is carried as a fraction because chapter indices are not comparable: a
     * source that splits volumes returns 1200 chapters where another returns 900.
     */
    private void doSwitch(final int index) {
        if (group == null || index < 0 || index >= group.size()) {
            return;
        }
        // remember where we are in the CURRENT source
        if (toc.size() > 0) {
            ProgressStore.Entry e = progressStore.find(book.sourceUrl, book.bookUrl);
            int at = (e != null) ? e.chapterIndex : 0;
            restoreRatio = (at + 0.5f) / (float) toc.size();
            Log.i(TAG, "detail: switch ratio " + restoreRatio + " (chapter " + at
                    + "/" + toc.size() + ")");
        }
        final SourceSwitcher.SrcBook target = group.items.get(index);
        final BookSource ns = sourceStore.findByUrl(target.sourceUrl);
        if (ns == null) {
            toast("书源已不存在：" + target.sourceName);
            return;
        }
        tocCount.setText("换源中…");
        final Books.Book nb = target.book;
        Thread t = new Thread(new Runnable() {
            public void run() {
                Engine.loadBookInfo(ns, nb);
                final List<Books.Chapter> got = Engine.loadToc(ns, nb);
                handler.post(new Runnable() {
                    public void run() {
                        if (got.size() == 0) {
                            toast("「" + target.sourceName + "」目录为空，已取消换源");
                            tocCount.setText(toc.size() + " 章");
                            return;
                        }
                        group.select(index);
                        source = ns;
                        book = nb;
                        book.sourceName = ns.name;
                        toc.clear();
                        toc.addAll(got);
                        adapter.notifyDataSetChanged();
                        tocCount.setText(toc.size() + " 章");
                        updateSwitchButton();
                        if (infoNameView != null) {
                            infoNameView.setText(book.name);
                        }
                        updateInfoMeta();
                        if (infoIntroView != null) {
                            infoIntroView.setText(book.intro.length() > 0
                                    ? book.intro : "（暂无简介）");
                        }
                        ((TextView) findViewById(
                                com.jianyue.reader.R.id.detail_title)).setText(book.name);
                        saveRestoreProgress();
                        toast("已切换到：" + ns.name + "（" + got.size() + " 章）");
                        Log.i(TAG, "detail: switched to " + ns.name + " toc=" + got.size());
                    }
                });
            }
        });
        t.start();
    }

    /**
     * Persist the reading position on the NEW source so both the shelf and the reader resume
     * at the same point in the story.
     */
    private void saveRestoreProgress() {
        if (book == null || toc.size() == 0) {
            return;
        }
        int idx = 0;
        if (restoreRatio > 0f) {
            idx = (int) (restoreRatio * toc.size());
            if (idx < 0) {
                idx = 0;
            }
            if (idx >= toc.size()) {
                idx = toc.size() - 1;
            }
        }
        progressStore.save(book, idx, toc.get(idx).name, 0);
        Log.i(TAG, "detail: restore progress -> chapter " + idx + "/" + toc.size());
    }

    // -------------------------------------------------------------- actions

    private void openChapter(int index) {
        if (index < 0 || index >= toc.size()) {
            return;
        }
        // opening a chapter puts the book on the shelf as a side effect
        progressStore.save(book, index, toc.get(index).name, 0);
        Intent i = new Intent(this, ReaderActivity.class);
        if (group != null) {
            i.putExtra("mergeKey", group.key);
        }
        i.putExtra("sourceUrl", book.sourceUrl);
        i.putExtra("bookUrl", book.bookUrl);
        i.putExtra("name", book.name);
        i.putExtra("author", book.author);
        i.putExtra("chapterIndex", index);
        startActivity(i);
    }

    /**
     * Put the book on the shelf without starting to read it.
     *
     * This is what the header button does; it records the book at chapter 0 so the shelf
     * has something to show, and is idempotent because ProgressStore keys on
     * sourceUrl + bookUrl.
     */
    private void addToShelf() {
        String name = (toc.size() > 0) ? toc.get(0).name : "";
        progressStore.save(book, 0, name, 0);
        toast("已加入书架：" + book.name);
        Log.i(TAG, "detail: added to shelf " + book.name);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private class ChapterAdapter extends BaseAdapter {
        public int getCount() {
            return toc.size();
        }

        public Object getItem(int position) {
            return toc.get(position);
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
            Books.Chapter c = toc.get(position);
            title.setText(c.name);
            ProgressStore.Entry e = progressStore.find(book.sourceUrl, book.bookUrl);
            boolean here = (e != null && e.chapterIndex == position);
            if (here) {
                title.setTextColor(0xFFBF3B2E);
                sub.setText("上次读到");
                sub.setVisibility(View.VISIBLE);
            } else {
                title.setTextColor(0xFF222222);
                if (c.vip) {
                    sub.setText("VIP");
                    sub.setVisibility(View.VISIBLE);
                } else {
                    sub.setVisibility(View.GONE);
                }
            }
            return v;
        }
    }
}

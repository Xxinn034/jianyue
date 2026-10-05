package com.jianyue.reader.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;

import com.jianyue.reader.R;
import com.jianyue.reader.model.Books;

/**
 * Shared window + bottom-tab-bar setup for every screen.
 *
 * WINDOW
 * ------
 * Removes the grey strip the framework draws above the content. That strip is the window's
 * TITLE BAR (it shows the label passed to setTitle, or the app label). API 1 has no Material
 * toolbar and no themes beyond the framework default, so the reliable way to remove it is to
 * turn the FEATURE_NO_TITLE window feature on BEFORE setContentView. The window background is
 * set to the same colour as the content, so the strip's area is painted like the rest of the
 * screen instead of showing the default grey.
 *
 * TABS
 * ----
 * The app is three pages behind one bar: 书架 / 书源 / 本地导入 (no "我的" page - there is
 * nothing to put in it). API 1 has no fragments and no ViewPager, so each page is its own
 * Activity and the bar is part of EVERY page layout, which is what makes it look like one
 * persistent control: the three layouts carry identical tab ids, and tabs() paints the
 * current one and wires the other two.
 *
 * A tab switch starts the sibling activity and finishes the page it left (the shelf is the
 * task root and is never finished), so the stack stays at most 书架 + one page: back from
 * 书源/本地导入 returns to the shelf, and tapping the launcher icon after Home resumes the
 * shelf instead of creating a second task.
 */
final class Ui {

    private static final String TAG = "JianYue";

    /** The bar's pages, in bar order. Index == the `current` argument of tabs(). */
    private static final Class<?>[] TAB_ACTIVITY = new Class<?>[]{
            MainActivity.class, SourcesActivity.class, LocalActivity.class
    };
    private static final String[] TAB_NAME = new String[]{"shelf", "source", "local"};
    private static final String[] TAB_LABEL = new String[]{"书架", "书源", "本地导入"};

    /** Accent, mirroring @color/accent (see res/values/colors.xml). */
    static final int ACCENT = 0xFFBF3B2E;
    /** Inactive tab tint, mirroring @color/text_hint. */
    static final int TAB_INACTIVE = 0xFFAAAAAA;

    private Ui() {
    }

    /**
     * Call this as the FIRST thing in onCreate, before setContentView.
     *
     * @param bgColor the activity's content background, so no grey band is left behind
     */
    static void setup(Activity a, int bgColor) {
        try {
            a.requestWindowFeature(Window.FEATURE_NO_TITLE);
        } catch (Throwable t) {
            Log.i(TAG, "ui: FEATURE_NO_TITLE failed " + t.getMessage());
        }
        try {
            a.getWindow().setBackgroundDrawable(new ColorDrawable(bgColor));
        } catch (Throwable ignored) {
        }
    }

    /**
     * Paint and wire the bottom tab bar. Call after setContentView.
     *
     * @param current index of the page that owns this activity: 0 书架, 1 书源, 2 本地导入
     */
    static void tabs(final Activity a, final int current) {
        final int[] item = new int[]{R.id.tab_shelf, R.id.tab_source, R.id.tab_local};
        final int[] icon = new int[]{
                R.id.tab_shelf_icon, R.id.tab_source_icon, R.id.tab_local_icon};
        final int[] label = new int[]{
                R.id.tab_shelf_label, R.id.tab_source_label, R.id.tab_local_label};
        final int[] mark = new int[]{
                R.id.tab_shelf_mark, R.id.tab_source_mark, R.id.tab_local_mark};
        final int[] iconOn = new int[]{
                R.drawable.tab_shelf_on, R.drawable.tab_source_on, R.drawable.tab_local_on};
        final int[] iconOff = new int[]{
                R.drawable.tab_shelf_off, R.drawable.tab_source_off, R.drawable.tab_local_off};

        for (int i = 0; i < item.length; i++) {
            final int index = i;
            boolean on = (i == current);
            View box = a.findViewById(item[i]);
            if (box == null) {
                Log.i(TAG, "tabs: MISSING " + TAB_NAME[i]);
                continue;
            }
            ImageView iv = (ImageView) a.findViewById(icon[i]);
            if (iv != null) {
                iv.setImageResource(on ? iconOn[i] : iconOff[i]);
            }
            TextView tv = (TextView) a.findViewById(label[i]);
            if (tv != null) {
                tv.setTextColor(on ? ACCENT : TAB_INACTIVE);
            }
            View bar = a.findViewById(mark[i]);
            if (bar != null) {
                bar.setBackgroundColor(on ? ACCENT : 0x00000000);
            }
            if (!on) {
                box.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        switchTo(a, current, index);
                    }
                });
            }
        }

        hostTap(a, item, current);
    }

    /** Start the page for a tab the user tapped, and drop the page they left. */
    private static void switchTo(Activity a, int from, int to) {
        Log.i(TAG, "tab: switch " + TAB_NAME[from] + " -> " + TAB_NAME[to]);
        Intent i = new Intent(a, TAB_ACTIVITY[to]);
        if (to == 0) {
            // 书架 is the task's root: bring the existing instance back to the front instead
            // of starting a second copy. Without SINGLE_TOP the system would destroy and
            // recreate it, and without CLEAR_TOP the page being left would linger under it.
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        }
        a.startActivity(i);
        // The root is never finished, so the task keeps exactly one root and an app icon tap
        // after Home resumes the shelf we already have. Leaving it alive is also what makes
        // back from 书源/本地导入 return to the shelf rather than leave the app.
        if (from != 0) {
            a.finish();
        }
    }

    /**
     * Host-driven tab tap: `am start ... -e tabTap 1` clicks a real tab.
     *
     * API 1 has no `adb shell input tap`, so a tap cannot be injected from the host; this
     * runs the exact listener a finger would hit (performClick dispatches through the view),
     * which is what lets the tab switch be verified end to end.
     */
    private static void hostTap(final Activity a, final int[] item, final int current) {
        Intent intent = a.getIntent();
        final int index = (intent == null) ? -1 : intent.getIntExtra("tabTap", -1);
        if (index < 0 || index >= item.length) {
            return;
        }
        final View box = a.findViewById(item[index]);
        if (box == null) {
            Log.i(TAG, "tabtap: MISSING index=" + index);
            return;
        }
        box.post(new Runnable() {
            public void run() {
                Log.i(TAG, "tabtap: index=" + index + " from=" + TAB_NAME[current]
                        + " clickable=" + box.isClickable());
                box.performClick();
            }
        });
    }

    /** Open the reader on a book, at a chapter. Shared by the shelf and the local page. */
    static void openReader(Activity a, Books.Book b, int chapterIndex) {
        if (b == null) {
            return;
        }
        Intent i = new Intent(a, ReaderActivity.class);
        i.putExtra("sourceUrl", b.sourceUrl);
        i.putExtra("bookUrl", b.bookUrl);
        i.putExtra("name", b.name);
        i.putExtra("author", b.author);
        i.putExtra("chapterIndex", chapterIndex);
        a.startActivity(i);
    }

    /**
     * Log the tab bar's geometry and state.
     *
     * API 1 has no screencap, so a screen can only be checked through its real bounds. This
     * is the part that catches a bar that was pushed off screen, an icon whose bitmap never
     * loaded (0x0) or a page that failed to mark itself as current.
     */
    static void dumpTabs(View root, int current) {
        int[] item = new int[]{R.id.tab_shelf, R.id.tab_source, R.id.tab_local};
        int[] icon = new int[]{
                R.id.tab_shelf_icon, R.id.tab_source_icon, R.id.tab_local_icon};
        int[] label = new int[]{
                R.id.tab_shelf_label, R.id.tab_source_label, R.id.tab_local_label};
        for (int i = 0; i < item.length; i++) {
            View box = root.findViewById(item[i]);
            if (box == null) {
                Log.i(TAG, "layout: tab_" + TAB_NAME[i] + " MISSING");
                continue;
            }
            View iv = root.findViewById(icon[i]);
            TextView tv = (TextView) root.findViewById(label[i]);
            int[] loc = new int[2];
            box.getLocationInWindow(loc);
            Log.i(TAG, "layout: tab_" + TAB_NAME[i] + " top=" + loc[1] + " left=" + loc[0]
                    + " w=" + box.getWidth() + " h=" + box.getHeight()
                    + " icon=" + (iv == null ? "?" : (iv.getWidth() + "x" + iv.getHeight()))
                    + " label=" + (tv == null ? "?" : tv.getText())
                    + " on=" + (i == current));
        }
    }

    /** Bounds of one view, for the layout dumps: "name top=.. w=.. h=.. vis=V|G". */
    static void dump(View root, int id, String name) {
        View v = root.findViewById(id);
        if (v == null) {
            Log.i(TAG, "layout: " + name + " MISSING");
            return;
        }
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        Log.i(TAG, "layout: " + name + " top=" + loc[1] + " left=" + loc[0]
                + " w=" + v.getWidth() + " h=" + v.getHeight()
                + " vis=" + (v.getVisibility() == View.VISIBLE ? "V" : "G"));
    }
}

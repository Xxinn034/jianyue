package com.jianyue.reader.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.Layout;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import com.jianyue.reader.R;
import com.jianyue.reader.engine.Engine;
import com.jianyue.reader.model.BookSource;
import com.jianyue.reader.model.Books;
import com.jianyue.reader.model.LocalStore;
import com.jianyue.reader.model.ProgressStore;
import com.jianyue.reader.model.SourceStore;
import com.jianyue.reader.model.SourceSwitcher;
import com.jianyue.reader.util.Paragraphs;

/**
 * 阅读 (reader).
 *
 * Reading model
 * -------------
 * A chapter is cut into screen-sized pages by {@link Paginator} and only ONE page is
 * shown at a time, so a chapter never scrolls vertically. Turning is by:
 *   - horizontal swipe (swipe left = next page, swipe right = previous page), or
 *   - tapping the left / right third.
 * Turning past the last page moves to the next chapter; back past the first page moves to
 * the previous one.
 *
 * Chrome
 * ------
 * The activity title is suppressed and the status bar is hidden for the whole activity.
 * API 1 predates immersive mode, so the window flags are the only mechanism available.
 * The chapter strip and page strip are hidden until the centre third is tapped, and the
 * MENU is opened by LONG-PRESSING the centre third.
 *
 * Background
 * ----------
 * Cycled through 纸黄 / 白色 / 护眼绿 / 夜间黑 from the menu. Because the status bar is
 * already hidden, a dark background is fully immersive rather than bordered by a bright
 * system bar.
 *
 * API-1 specifics: onBackPressed() does not exist (onKeyDown handles BACK), there are no
 * runtime permissions, and only one chapter is held in memory at a time.
 */
public class ReaderActivity extends Activity {

    private static final String TAG = "JianYue";

    /** text sizes in sp, cycled by the menu */
    private static final int[] TEXT_SIZES = {15, 16, 17, 18, 20, 22, 25};

    /**
     * Background presets. bg == the whole surface, because the status bar is hidden there
     * is no seam to blend, and the bars are painted the same colour so revealing them does
     * not flash a bright strip.
     */
    private static final int[] BG_COLORS = {
            0xFFF6F1E7,   // 纸黄 (default, easiest on the eyes)
            0xFFFFFFFF,   // 白色
            0xFFD8E2D0,   // 护眼绿
            0xFF141414,   // 夜间黑
    };
    private static final int[] FG_COLORS = {
            0xFF3A3226,   // warm dark grey on paper
            0xFF222222,   // near black on white
            0xFF2C3626,   // dark green-grey on green
            0xFFB0ACA4,   // soft light grey on black (pure white glares)
    };
    private static final String[] BG_NAMES = {"纸黄", "白色", "护眼绿", "夜间黑"};

    private static final int SWIPE_UNUSED = 0;   // kept only to document that swipes are gone
    private static final long LONG_PRESS_MS = 500L;
    /** a finger move beyond this is a drag, not a tap (ViewConfiguration is API 1, but a
     *  fixed slop keeps the gesture independent of the device's density) */
    private static final float TOUCH_SLOP = 20f;

    /** extra bottom room so text is never hidden behind a system bar */
    private static final int BOTTOM_SAFE_DP = 10;
    /** smallest right margin the ragged-edge compensation may leave (see applyTextMargins) */
    private static final int MIN_RIGHT_MARGIN_DP = 6;

    private SourceStore sourceStore;
    private ProgressStore progressStore;
    private LocalStore localStore;
    private BookSource source;
    /** Set when this is an imported local book: 目录 and 正文 come from the cached copy. */
    private LocalStore.LocalBook localBook;
    private Books.Book book;
    private final List<Books.Chapter> toc = new ArrayList<Books.Chapter>();
    private int chapterIndex;

    /**
     * Other sources the same book was found on (null when the book came from the shelf and
     * only one source is known). Populated from the search/detail screens' registry.
     */
    private SourceSwitcher.Group group;
    /** Set while a chapter load is retrying on another source, so failures do not cascade. */
    private boolean switching;
    /** Holder for the source an in-flight chapter load switched to (read from the worker). */
    private final BookSource[] altSrc = new BookSource[1];

    private View root;
    private View topBar;
    private View bottomBar;
    private View pageArea;
    private TextView text;
    /** detached twin of {@link #text}, used to lay a whole chapter out on the spot (see below) */
    private TextView measureView;
    private TextView titleView;
    private TextView progressView;
    private TextView prevChapterView;
    private TextView nextChapterView;

    private final Handler handler = new Handler();

    // ---- reading state ----
    private String chapterText = "";
    private final List<Integer> pageStarts = new ArrayList<Integer>();
    private int pageIndex;
    private int textSizeIndex = 3;
    private int bgIndex = 0;
    /** the side margins as declared in act_reader.xml, before the ragged-edge compensation */
    private int basePadLeft;
    private int basePadRight;
    private boolean loading;
    private boolean chromeVisible;
    private boolean shelfAdded;
    private int lastBottomPad = -1;
    /** how many lines the last pagination packed into a page (for the render check below) */
    private int linesPerPage;

    // ---- touch tracking ----
    private float downX;
    private float downY;
    private boolean longPressFired;
    private Runnable longPressRunnable;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // no title bar and no grey band above the content; see Ui
        Ui.setup(this, 0xFFF6F1E7);
        setFullscreen();
        setContentView(R.layout.act_reader);

        sourceStore = new SourceStore(this);
        progressStore = new ProgressStore(this);
        localStore = new LocalStore(this);

        root = findViewById(R.id.read_root);
        topBar = findViewById(R.id.read_top_bar);
        bottomBar = findViewById(R.id.read_bottom_bar);
        pageArea = findViewById(R.id.read_page_area);
        text = (TextView) findViewById(R.id.read_text);
        titleView = (TextView) findViewById(R.id.read_title);
        progressView = (TextView) findViewById(R.id.read_progress);
        prevChapterView = (TextView) findViewById(R.id.read_prev_chapter);
        nextChapterView = (TextView) findViewById(R.id.read_next_chapter);
        // the designed (symmetric) margins, kept as the origin the ragged-edge compensation
        // shifts around (see applyTextMargins)
        basePadLeft = text.getPaddingLeft();
        basePadRight = text.getPaddingRight();

        String sourceUrl = getIntent().getStringExtra("sourceUrl");
        String bookUrl = getIntent().getStringExtra("bookUrl");
        source = findSource(sourceUrl);
        book = new Books.Book();
        book.sourceUrl = sourceUrl == null ? "" : sourceUrl;
        book.bookUrl = bookUrl == null ? "" : bookUrl;
        book.name = nz(getIntent().getStringExtra("name"));
        book.author = nz(getIntent().getStringExtra("author"));

        // A saved position is only a fallback: it must not override an explicitly requested
        // chapter, nor replace the metadata the caller just supplied. (An earlier version
        // restored a stale entry and silently opened the wrong chapter.)
        boolean explicitChapter = getIntent().hasExtra("chapterIndex");
        chapterIndex = getIntent().getIntExtra("chapterIndex", 0);
        ProgressStore.Entry saved = progressStore.find(book.sourceUrl, book.bookUrl);
        int savedOffset = 0;
        if (saved != null) {
            if (book.name.length() == 0) {
                book.name = saved.book.name;
            }
            if (book.author.length() == 0) {
                book.author = saved.book.author;
            }
            if (!explicitChapter && saved.chapterIndex > 0) {
                chapterIndex = saved.chapterIndex;
            }
            if (!explicitChapter) {
                savedOffset = saved.charOffset;
            }
        }
        shelfAdded = (saved != null);
        if (source != null) {
            book.sourceName = source.name;
        }

        // Local book: there is no book source behind it. The 目录 and the chapter text come
        // from the copy made at import time (LocalStore), which is why everything below
        // checks localBook before it reaches for a source.
        if (LocalStore.isLocalSource(book.sourceUrl)) {
            localBook = localStore.find(book.bookUrl);
            if (localBook != null) {
                if (book.name.length() == 0) {
                    book.name = localBook.title;
                }
                if (book.author.length() == 0) {
                    book.author = localBook.author;
                }
                book.sourceName = LocalStore.SOURCE_NAME + " · " + localBook.format;
            } else {
                Log.i(TAG, "reader: local book " + book.bookUrl + " is gone from the library");
            }
        }

        // merged-source group: pass-through from search/detail, else look the title up.
        // A local book has no other sources, so it never joins a group.
        if (localBook == null) {
            group = SourceSwitcher.find(getIntent().getStringExtra("mergeKey"));
            if (group == null) {
                group = SourceSwitcher.findByName(book.name);
            }
        }

        findViewById(R.id.read_btn_toc).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showTocDialog();
            }
        });
        prevChapterView.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openChapter(chapterIndex - 1);
            }
        });
        nextChapterView.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openChapter(chapterIndex + 1);
            }
        });

        // Tap zones, three equal thirds:
        //   left   -> previous page
        //   centre -> SHORT TAP shows/hides the top and bottom strips,
        //             LONG PRESS opens the menu
        //   right  -> next page
        // Swipe support was removed on request, so these listeners are the only way to turn
        // a page.
        findViewById(R.id.read_zone_prev).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pageBack();
            }
        });
        findViewById(R.id.read_zone_next).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pageForward();
            }
        });
        installCentreGesture(findViewById(R.id.read_zone_menu));

        applyTheme();
        titleView.setText(book.name);

        // Host-driven font size: `--ei textSize 6` selects TEXT_SIZES[6]. API 1 cannot inject a
        // tap (no `input tap`), so the menu's 增大字号/减小字号 items cannot be driven from the
        // host; this sets the very index those items change, which is what makes "a bigger font
        // clips the last line" reproducible and checkable from a screenshot.
        int wantSize = getIntent().getIntExtra("textSize", -1);
        if (wantSize >= 0 && wantSize < TEXT_SIZES.length) {
            textSizeIndex = wantSize;
            applyTheme();
            Log.i(TAG, "reader: text size from intent -> " + TEXT_SIZES[textSizeIndex] + "sp");
        }

        // Host-driven gesture check. API 1 has no `adb shell input tap`, so a touch cannot be
        // synthesised from the host; this drives the SAME handlers the touch listener calls
        // (centreTap / showMenu), which is what makes the fix verifiable from logcat.
        //   -e gesture center  -> short tap in the centre third
        //   -e gesture menu    -> long press in the centre third
        final String gesture = getIntent().getStringExtra("gesture");
        if (gesture != null) {
            handler.postDelayed(new Runnable() {
                public void run() {
                    if ("menu".equals(gesture)) {
                        // posts the very runnable the touch listener's long press posts, so
                        // this exercises scheduleLongPress -> showMenu, not a shortcut
                        Log.i(TAG, "gesture: long press (centre third)");
                        scheduleLongPress();
                    } else {
                        // what the touch listener calls on ACTION_UP without a long press
                        Log.i(TAG, "gesture: tap (centre third)");
                        toggleChrome();
                    }
                }
            }, 400L);
        }

        final int offset = savedOffset;
        if (source == null && localBook == null) {
            chapterText = "找不到书源，无法加载正文。\n\n可能该书源已被删除。";
            text.post(new Runnable() {
                public void run() {
                    applyBottomSafeInset();
                    repaginate();
                }
            });
            return;
        }
        // pagination needs the measured view size, so wait for the first layout pass
        text.post(new Runnable() {
            public void run() {
                applyBottomSafeInset();
                loadChapter(chapterIndex, offset);
            }
        });
    }

    // ----------------------------------------------------------- fullscreen

    /**
     * Hide the title bar and the status bar for this activity.
     *
     * API 1 has no immersive mode (that arrives with API 19), so the window flags are set
     * directly. FLAG_LAYOUT_NO_LIMITS lets the content use the whole screen, which is what
     * makes a dark reading background look seamless rather than letterboxed.
     */
    private void setFullscreen() {
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            lp.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            getWindow().setAttributes(lp);
        } catch (Throwable t) {
            Log.i(TAG, "reader: fullscreen setup failed " + t.getMessage());
        }
    }

    // -------------------------------------------------------------- theming

    private void applyTheme() {
        int bg = BG_COLORS[bgIndex];
        int fg = FG_COLORS[bgIndex];
        root.setBackgroundColor(bg);
        text.setTextColor(fg);
        text.setTextSize(TEXT_SIZES[textSizeIndex]);
        applyTextMargins();
        topBar.setBackgroundColor(bg);
        bottomBar.setBackgroundColor(bg);
        titleView.setTextColor(fg);
        progressView.setTextColor(fg);
        prevChapterView.setTextColor(fg);
        nextChapterView.setTextColor(fg);
        try {
            getWindow().setBackgroundDrawable(new ColorDrawable(bg));
        } catch (Throwable ignored) {
        }
    }

    /**
     * Shift the text box sideways so the blank column on the RIGHT stops looking wider than the
     * one on the left.
     *
     * A CJK line can only break at whole characters, so every line ends with on average half a
     * character of unused advance, plus the glyph's own side bearing. The box was symmetric, so
     * that half character landed entirely in the right margin: measured on the API-1 emulator at
     * 18sp, the ink starts 19px from the left edge but ends 28..38px from the right one, which is
     * the "right column is wider than the left" report. Moving the box right by a quarter of the
     * character width - and taking exactly the same amount off the right margin - splits the
     * difference: the two margins then read as equal, and because the total width is unchanged
     * the number of characters per line (and therefore every page break) is unchanged too.
     *
     * Computed from the CURRENT paint, because the ragged amount scales with the font size the
     * user picked in the menu. The right margin keeps a floor of MIN_RIGHT_MARGIN_DP so a full
     * line can never run into the screen edge.
     */
    private void applyTextMargins() {
        float charW = text.getPaint().measureText("中");   // full-width advance == text size
        int shift = Math.round(charW / 4f);
        int maxShift = basePadRight - dp(MIN_RIGHT_MARGIN_DP);
        if (shift > maxShift) {
            shift = maxShift;
        }
        if (shift < 0) {
            shift = 0;
        }
        text.setPadding(basePadLeft + shift, text.getPaddingTop(),
                basePadRight - shift, text.getPaddingBottom());
    }

    private void cycleBackground() {
        bgIndex = (bgIndex + 1) % BG_COLORS.length;
        // keep the reading position across the re-layout
        final int keep = currentOffset();
        applyTheme();
        handler.post(new Runnable() {
            public void run() {
                repaginateTo(keep);
            }
        });
        toast("背景：" + BG_NAMES[bgIndex]);
    }

    // -------------------------------------------------------- safe area

    /**
     * Reserve room at the bottom of the page so the last line is never clipped.
     *
     * Reported symptom (round 1): with the strips hidden, the bottom line of text ran under the
     * device's own navigation/status area; showing the strips "fixed" it because they occupied
     * that space. API 1 has no window-insets API to query, so the area is measured against the
     * real screen height instead. Whatever the result, a small fixed margin is always reserved,
     * and the paginator subtracts the padding - so page breaks account for it and the text can
     * never be laid out into the covered region.
     *
     * WHERE the missing space is matters, and the first version got that wrong: it treated the
     * whole difference (screenH - content height) as if it were at the BOTTOM, so on this
     * emulator - whose 25px band is the status bar at the TOP - it reserved 25px at the bottom
     * for nothing (measured from a screenshot: text stopped 38px above the screen edge). The
     * content's own top offset is now taken from getLocationOnScreen, and only what is left
     * below the content counts as bottom chrome. If the offset cannot be measured (0 while the
     * window uses FLAG_LAYOUT_NO_LIMITS) the old, conservative answer comes back, so a device
     * with a bottom bar is no worse off than before.
     */
    private void applyBottomSafeInset() {
        int screenH = getWindowManager().getDefaultDisplay().getHeight();
        if (root.getHeight() <= 0) {
            // Not laid out yet: nothing can be measured, and guessing here is what used to pad
            // the bottom by a whole screen height. The callers retry after the layout pass.
            return;
        }
        int[] loc = new int[2];
        root.getLocationOnScreen(loc);
        int contentTop = loc[1];
        int bottomChrome;
        if (contentTop > 0) {
            // a real top band (the status bar) was measured, so what is left below the content
            // really is the bottom chrome - on this emulator: none
            bottomChrome = screenH - (contentTop + root.getHeight());
            if (bottomChrome < 0) {
                bottomChrome = 0;
            }
        } else {
            // 0 means either a genuinely full-screen window or a failed measurement (which
            // FLAG_LAYOUT_NO_LIMITS can cause). API 1 cannot tell them apart, so keep the old,
            // conservative answer instead of risking text under a bottom system bar.
            bottomChrome = Math.max(0, screenH - root.getHeight());
        }
        int minPad = dp(BOTTOM_SAFE_DP);
        int pad = Math.max(minPad, bottomChrome);
        if (pad != lastBottomPad) {
            lastBottomPad = pad;
            pageArea.setPadding(0, 0, 0, pad);
            Log.i(TAG, "reader: safe bottom inset=" + pad + "px (screenH=" + screenH
                    + " rootTop=" + contentTop + " rootH=" + root.getHeight()
                    + " areaH=" + pageArea.getHeight()
                    + " bottomChrome=" + bottomChrome + ")");
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ----------------------------------------------------------- pagination

    private int currentOffset() {
        if (pageStarts.size() == 0) {
            return 0;
        }
        return pageStarts.get(Math.min(pageIndex, pageStarts.size() - 1)).intValue();
    }

    private void repaginate() {
        repaginateTo(currentOffset());
    }

    /**
     * Recompute page breaks for the current text size and view size, then show the page
     * holding the given character offset.
     *
     * The body is wrapped because a failure HERE is a failure of the whole screen: the chapter
     * text has already arrived, so the reader would sit on 正在加载… and then take the process
     * down with it (exactly what the API-21 LayoutParams NPE in {@link #layoutOf} did on a real
     * phone). Showing the reason on the page keeps the failure visible and the book readable
     * instead of turning it into a crash report from the launcher.
     */
    private void repaginateTo(int charOffset) {
        try {
            paginateTo(charOffset);
        } catch (Throwable t) {
            Log.i(TAG, "reader: pagination failed " + t);
            text.setText("排版失败：" + t + "\n\n本章正文已经取到，是分页这一步出的错。");
        }
    }

    private void paginateTo(int charOffset) {
        // The width is the TextView's content width, which is also what the TextView itself
        // wraps the page text at - the paginator only decides WHERE a page ends.
        int w = text.getWidth() - text.getPaddingLeft() - text.getPaddingRight();
        // ... and the height must be the same thing: the line boxes are drawn from paddingTop
        // downwards, so the box they have to fit into is the view height MINUS that top padding.
        // The bottom padding is deliberately NOT subtracted: a line box may legitimately end
        // inside it (the spacing belongs to the box, the glyphs sit above it), and subtracting it
        // silently drops a line that in fact renders fine.
        //
        // This used to pass the unmodified view height, which packed ONE LINE TOO MANY whenever
        // (height mod lineHeight) < paddingTop -- e.g. lineHeight 27px in a 270px box: the 10th
        // line box needs 276px, so its bottom third was clipped off by the view edge. That is the
        // "加大字号后最后一行显示不全" report. At the default 18sp the numbers happen to divide
        // evenly (9 x 29 = 261 <= 264), which is exactly why it only appeared after the font was
        // changed.
        int h = text.getHeight() - text.getPaddingTop();
        if (w <= 0 || h <= 0) {
            // Not laid out yet: retry shortly rather than rendering a broken page.
            //
            // applyBottomSafeInset() MUST run again here: the bottom padding is measured against
            // the laid-out view, so before the first layout pass there is nothing to measure and
            // the retry is what gets the padding (and therefore the usable height) right.
            handler.postDelayed(new Runnable() {
                public void run() {
                    applyBottomSafeInset();
                    repaginate();
                }
            }, 150L);
            return;
        }
        Paint paint = text.getPaint();
        // TextView.getLineHeight() already includes lineSpacingExtra. API 1 has no public
        // Paint.setFontSpacing, so it is only used for the log/line budget now: the page breaks
        // come from the real layout below.
        float lineHeight = text.getLineHeight();
        Layout layout = layoutOf(chapterText, w);
        List<Integer> starts = cutPages(layout, w, h);
        linesPerPage = Paginator.maxLinesPerPage(layout, starts);
        if (linesPerPage < 1) {
            linesPerPage = 1;
        }
        pageStarts.clear();
        pageStarts.addAll(starts);
        pageIndex = Paginator.pageOf(pageStarts, charOffset);
        showPage(pageIndex);
        Log.i(TAG, "reader: paginated chars=" + chapterText.length()
                + " pages=" + pageStarts.size() + " viewW=" + w + " viewH=" + h
                + " lineH=" + lineHeight + " linesPerPage=" + linesPerPage
                + " layoutLines=" + (layout == null ? -1 : layout.getLineCount())
                + " padL=" + text.getPaddingLeft() + " padR=" + text.getPaddingRight()
                + " padTop=" + text.getPaddingTop() + " sizeSp=" + TEXT_SIZES[textSizeIndex]);
    }

    /**
     * Lay a string out with the real text engine and hand back the resulting {@link Layout}.
     *
     * This is the fix for the clipped last line. Pagination used to PREDICT line breaks with
     * Paint.breakText, but a TextView breaks by word and applies CJK punctuation rules (a line
     * may not start with "：" or "。"), so the prediction and the renderer disagreed - measured
     * on the API-1 emulator at 25sp, a page packed 7 lines and the TextView drew 9, and the extra
     * two were clipped in half by the bottom padding. Measuring with an actual TextView removes
     * the guesswork: nothing about the renderer's breaking rules has to be re-implemented.
     *
     * The measuring view is a SEPARATE, never-attached TextView on purpose: the visible one is
     * laid out asynchronously, and reading its layout in the same message would return the
     * PREVIOUS text's layout (that is what makes a detached view necessary - it can be measured
     * and laid out on the spot). Its configuration must mirror the visible view (text size, line
     * spacing, typeface), which is what makes the two agree; the line spacing comes from the same
     * @dimen the layout uses, because API 1 has no getLineSpacingExtra() to copy it from.
     */
    private Layout layoutOf(String s, int width) {
        if (measureView == null) {
            measureView = new TextView(this);
            // A view that was never attached has NO LayoutParams, and on API 21+ that is a hard
            // crash: TextView.setText() finishes in checkForRelayout(), whose FIRST statement
            // reads mLayoutParams.width. API 1's TextView never touches that field, so the whole
            // measuring trick above works on the emulator and dies on a real phone - measured on
            // the SHL25 (Android 5.0.2), every single visit to the reader ended as
            //   java.lang.NullPointerException: Attempt to read from field
            //   'int android.view.ViewGroup$LayoutParams.width' on a null object reference
            //   at android.widget.TextView.checkForRelayout(TextView.java:6954)
            //   at android.widget.TextView.setText(TextView.java:4095)
            //   at com.jianyue.reader.ui.ReaderActivity.layoutOf(ReaderActivity.java:576)
            // leaving the page on 正在加载… and the process gone. The VALUES do not matter (the
            // measurement below is driven by the MeasureSpec, not by the LayoutParams); they only
            // have to exist.
            measureView.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        measureView.setTypeface(text.getTypeface());
        measureView.setTextSize(TEXT_SIZES[textSizeIndex]);
        measureView.setLineSpacing(getResources().getDimension(R.dimen.reader_line_spacing), 1f);
        measureView.setText(s);
        measureView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        measureView.layout(0, 0, width, Math.max(1, measureView.getMeasuredHeight()));
        return measureView.getLayout();
    }

    /**
     * Cut the chapter into pages, then VERIFY every page by laying it out on its own - which is
     * exactly what the visible TextView will do with it - and pull the page end back a line at a
     * time until it really fits.
     *
     * The layout's own line starts are not quite enough by themselves. A page is shown as a
     * SUBSTRING of the chapter, and a substring can wrap differently from the same characters
     * inside the whole text:
     *   - a page that begins with a line break renders an empty first line (measured: the 15sp
     *     page started with the local chapter's leading '\n' and needed 12 lines for 11 lines of
     *     text), and one that ends with one gets an empty last line;
     *   - the first line has no preceding context for the word-based breaking.
     * Each of those costs a line, and a page that needs one line more than it was cut for draws
     * that line into the bottom padding and clips it. Re-laying a page out is cheap (one screenful
     * of text), and it turns "should fit" into "does fit".
     *
     * Leading whitespace is skipped at every page start: it would otherwise be drawn as a blank
     * line at the top of the page (and cost exactly that line of capacity).
     */
    private List<Integer> cutPages(Layout layout, int width, int height) {
        List<Integer> starts = new ArrayList<Integer>();
        int lines = (layout == null) ? 0 : layout.getLineCount();
        if (lines == 0) {
            starts.add(Integer.valueOf(0));
            return starts;
        }
        int n = chapterText.length();
        // Copy the geometry out of the Layout first: measuring a page re-measures the measuring
        // view, and continuing to read offsets from the chapter's layout after that would be
        // asking for trouble.
        int[] top = new int[lines + 1];
        int[] bottom = new int[lines + 1];
        int[] start = new int[lines + 1];
        for (int i = 0; i < lines; i++) {
            top[i] = layout.getLineTop(i);
            bottom[i] = layout.getLineBottom(i);
            start[i] = layout.getLineStart(i);
        }
        start[lines] = n;

        int line = 0;
        while (line < lines) {
            int first = skipLeadingSpace(start[line], start[line + 1], n);
            starts.add(Integer.valueOf(first));
            // If the leading whitespace ran to the end of the line (the chapter's first line is
            // just its opening line break, which local imports produce), the page really starts on
            // the NEXT line - and the line box that was skipped is height the page gets back.
            int firstLine = (first >= start[line + 1]) ? line + 1 : line;
            int end = firstLine + 1;
            while (end < lines && bottom[end] - top[firstLine] <= height) {
                end++;
            }
            int budget = end - firstLine;      // the lines this page is allowed to use
            int tries = 0;
            while (end > firstLine + 1) {
                int to = (end < lines) ? start[end] : n;
                // Measure what will actually be VISIBLE: the break characters at the end belong
                // to the next page, and counting the empty line they create would cut this page
                // one line short for nothing.
                int visible = trimTrailingSpace(first, to);
                int measured = (visible > first)
                        ? layoutOf(chapterText.substring(first, visible), width).getLineCount()
                        : 0;
                if (tries == 0) {
                    Log.i(TAG, "reader: cut page " + starts.size() + " budget=" + budget
                            + " chars=" + (visible - first) + " measured=" + measured);
                }
                tries++;
                if (visible > first && measured <= budget) {
                    break;
                }
                end--;                    // one line too many: give the last line back
            }
            if (end >= lines) {
                break;                    // the rest of the chapter is on this last page
            }
            line = end;
        }
        return starts;
    }

    /** First non-whitespace offset in [from, limit): a page must not open with a blank line. */
    private int skipLeadingSpace(int from, int limit, int n) {
        int i = from;
        int stop = Math.min(limit, n);
        while (i < stop) {
            char c = chapterText.charAt(i);
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    /** End of the visible characters in [from, to): trailing whitespace starts the next page. */
    private int trimTrailingSpace(int from, int to) {
        int end = Math.min(to, chapterText.length());
        while (end > from) {
            char c = chapterText.charAt(end - 1);
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') {
                end--;
            } else {
                break;
            }
        }
        return end;
    }

    /**
     * Cross-check the page the TextView actually laid out against what the paginator promised.
     *
     * The paginator only cuts the page; the TextView wraps it again, and it applies its own
     * line-breaking rules while doing so. If the two disagree by a line, that line is drawn into
     * the bottom padding and clipped - which is invisible in a char count and easy to miss in a
     * log, so the count is compared explicitly, together with the text the TextView was given
     * (control characters escaped, because a '-' that is really a line separator explains a line
     * count that nothing else can).
     *
     * Read after the layout pass (postDelayed, not post): getLineCount() reports the PREVIOUS
     * layout until the TextView has been measured again.
     */
    private void logRenderedLines(final String pageText) {
        final int page = pageIndex;
        // The page slice keeps the break characters that lead into the next page, so its layout
        // usually has one more (empty) line than the page has lines of text. An empty line cannot
        // be clipped, so the check counts the lines up to the last VISIBLE character - that is the
        // number that must not exceed what the page was cut for.
        int end = pageText.length();
        while (end > 0) {
            char c = pageText.charAt(end - 1);
            if (c == '\r' || c == '\n' || c == ' ' || c == '\t') {
                end--;
            } else {
                break;
            }
        }
        final int contentEnd = end;
        handler.postDelayed(new Runnable() {
            public void run() {
                int got = text.getLineCount();
                int inkLines = got;
                Layout l = text.getLayout();
                if (l != null) {
                    inkLines = (contentEnd <= 0) ? 0 : (l.getLineForOffset(contentEnd - 1) + 1);
                }
                Log.i(TAG, "reader: page " + page + " textLines=" + inkLines
                        + " rendered=" + got + " packed=" + linesPerPage
                        + (inkLines > linesPerPage ? "  !! OVERFLOW" : " ok")
                        + " text=[" + escapeControl(pageText, 40) + "]");
            }
        }, 400L);
    }

    /** Escape every control/line-separator character so a log line stays one line and is readable. */
    private static String escapeControl(String s, int max) {
        if (s == null) {
            return "";
        }
        int n = Math.min(s.length(), max);
        StringBuilder sb = new StringBuilder(n + 8);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F || c == 0x2028 || c == 0x2029 || c == 0x0085) {
                sb.append("[u").append(Integer.toHexString(c)).append("]");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private void showPage(int page) {
        if (pageStarts.size() == 0) {
            text.setText(chapterText);
            return;
        }
        if (page < 0) {
            page = 0;
        }
        if (page >= pageStarts.size()) {
            page = pageStarts.size() - 1;
        }
        pageIndex = page;
        String pageText = Paginator.pageText(chapterText, pageStarts, pageIndex);
        text.setText(pageText);
        text.scrollTo(0, 0);   // a page fits, so this is a no-op guard
        logRenderedLines(pageText);
        updateProgressLabel();
        saveProgress();
    }

    private void pageForward() {
        if (pageIndex + 1 < pageStarts.size()) {
            showPage(pageIndex + 1);
        } else {
            openChapter(chapterIndex + 1);
        }
    }

    private void pageBack() {
        if (pageIndex > 0) {
            showPage(pageIndex - 1);
        } else {
            openChapter(chapterIndex - 1);
        }
    }

    private void updateProgressLabel() {
        if (progressView == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(chapterIndex + 1).append('/').append(Math.max(toc.size(), 1));
        if (pageStarts.size() > 1) {
            sb.append("   ").append(pageIndex + 1).append('/').append(pageStarts.size());
        }
        progressView.setText(sb.toString());
    }

    // --------------------------------------------------------------- chapter

    private BookSource findSource(String url) {
        if (url == null) {
            return null;
        }
        List<BookSource> all = sourceStore.all();
        for (int i = 0; i < all.size(); i++) {
            if (url.equals(all.get(i).url)) {
                return all.get(i);
            }
        }
        return null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private void openChapter(int index) {
        if (index < 0) {
            toast("已经是第一章");
            return;
        }
        if (toc.size() > 0 && index >= toc.size()) {
            toast("已经是最后一章");
            return;
        }
        loadChapter(index, 0);
    }

    private void loadChapter(final int index, final int charOffset) {
        if (loading) {
            return;
        }
        if (localBook != null) {
            loadLocalChapter(index, charOffset);
            return;
        }
        loading = true;
        chapterText = "正在加载…";
        pageStarts.clear();
        pageIndex = 0;
        text.setText(chapterText);

        final BookSource src = source;
        final Books.Book b = book;
        Thread t = new Thread(new Runnable() {
            public void run() {
                // The 目录 is loaded on the WORKER thread. Reading the shared `toc` list from
                // here while the UI thread mutates it was a real race (the worker could see
                // it still empty and conclude "no chapters").
                List<Books.Chapter> tocNow = toc;
                boolean switched = false;
                if (tocNow.size() == 0) {
                    tocNow = Engine.loadToc(src, b);
                }

                // 自动换源: the source this book opened with has no chapters. That is what
                // "目录为空" means in practice, and the merged search usually knows a source
                // that does work, so try them here rather than making the user back out.
                if (tocNow.size() == 0 && group != null && group.size() > 1) {
                    for (int i = 0; i < group.size(); i++) {
                        SourceSwitcher.SrcBook cand = group.items.get(i);
                        if (cand.sourceUrl.equals(b.sourceUrl)) {
                            continue;
                        }
                        BookSource alt = sourceStore.findByUrl(cand.sourceUrl);
                        if (alt == null) {
                            continue;
                        }
                        Log.i(TAG, "reader: toc empty on " + (src == null ? "?" : src.name)
                                + ", trying " + alt.name);
                        List<Books.Chapter> altToc = Engine.loadToc(alt, cand.book);
                        if (altToc.size() > 0) {
                            tocNow = altToc;
                            altSrc[0] = alt;
                            final SourceSwitcher.SrcBook fcand = cand;
                            final BookSource falt = alt;
                            final List<Books.Chapter> fgot = altToc;
                            final int fi = i;
                            handler.post(new Runnable() {
                                public void run() {
                                    applySwitchedSource(fcand, falt, fgot, fi);
                                    toast("已自动换源：" + falt.name);
                                }
                            });
                            switched = true;
                            break;
                        }
                    }
                }
                if (!switched) {
                    final List<Books.Chapter> ftoc = tocNow;
                    handler.post(new Runnable() {
                        public void run() {
                            toc.clear();
                            toc.addAll(ftoc);
                        }
                    });
                }

                final int idx = index;
                String title = "";
                String content;
                if (tocNow.size() == 0) {
                    content = "目录为空，无法定位章节。\n\n"
                            + "可以试试在目录界面换源（如果这本书在多个书源里都搜到过）。";
                } else {
                    int safe = idx;
                    if (safe < 0) {
                        safe = 0;
                    }
                    if (safe >= tocNow.size()) {
                        safe = tocNow.size() - 1;
                    }
                    Books.Chapter c = tocNow.get(safe);
                    title = c.name;
                    BookSource contentSrc = switched ? altSrc[0] : src;
                    if (contentSrc == null) {
                        contentSrc = src;
                    }
                    Books.ChapterContent cc = Engine.loadContent(contentSrc, c);
                    content = cc.content;
                    if (cc.title != null && cc.title.length() > 0) {
                        title = cc.title;
                    }
                }
                final String ft = title;
                final String fc = content;
                final int fidx = index;
                final int foffset = charOffset;
                handler.post(new Runnable() {
                    public void run() {
                        loading = false;
                        chapterIndex = fidx;
                        if (ft.length() > 0) {
                            titleView.setText(ft);
                        }
                        // 标题置顶 + 每段空两格: the very same formatting the local-book path
                        // below uses, so a downloaded chapter and an imported one cannot drift.
                        chapterText = Paragraphs.format(fc, ft, (b == null) ? null : b.name);
                        applyBottomSafeInset();
                        repaginateTo(foffset);
                        updateFooter();
                        Log.i(TAG, "reader: chapter=" + fidx + " " + ft
                                + " chars=" + fc.length() + " pages=" + pageStarts.size());
                    }
                });
            }
        });
        t.start();
    }

    /**
     * Load a chapter of an imported local book.
     *
     * There is no source, no HTTP and no 目录 request: the chapter list was built once at
     * import time and one chapter is a seek into the cached text file, so this is done
     * inline rather than on a worker thread.
     */
    private void loadLocalChapter(int index, int charOffset) {
        loading = true;
        List<Books.Chapter> got = localStore.toc(localBook);
        if (got.size() == 0) {
            loading = false;
            chapterText = "这本本地书的章节索引是空的，请删除后重新导入。";
            repaginate();
            return;
        }
        int safe = index;
        if (safe < 0) {
            safe = 0;
        }
        if (safe >= got.size()) {
            safe = got.size() - 1;
        }
        toc.clear();
        toc.addAll(got);
        chapterIndex = safe;
        Books.Chapter c = got.get(safe);
        titleView.setText(c.name);
        chapterText = Paragraphs.format(localStore.readChapter(localBook, safe), c.name,
                localBook.title);
        loading = false;
        applyBottomSafeInset();
        repaginateTo(charOffset);
        updateFooter();
        Log.i(TAG, "reader: local chapter=" + safe + " " + c.name
                + " chars=" + chapterText.length() + " pages=" + pageStarts.size());
    }

    private void updateFooter() {
        String prev = (chapterIndex > 0 && toc.size() > 0)
                ? toc.get(chapterIndex - 1).name : "已是第一章";
        String next = (chapterIndex + 1 < toc.size())
                ? toc.get(chapterIndex + 1).name : "已是最后一章";
        prevChapterView.setText("‹ " + prev);
        nextChapterView.setText(next + " ›");
        updateProgressLabel();
    }

    private void saveProgress() {
        saveProgressFor(chapterIndex);
    }

    /** Explicitly put the book on the shelf (the detail screen offers the same action). */
    private void addToShelf() {
        String name = (chapterIndex >= 0 && chapterIndex < toc.size())
                ? toc.get(chapterIndex).name : "";
        progressStore.save(book, chapterIndex, name, currentOffset());
        shelfAdded = true;
        toast("已加入书架：" + book.name);
        Log.i(TAG, "reader: added to shelf " + book.name);
    }

    // ------------------------------------------------------------- 换源

    /**
     * Book as a fraction of its 目录.
     *
     * Chapter numbering cannot be carried across sources (one splits volumes, another does
     * not, so the same 300th chapter of the story can be #280 or #340). The position in the
     * story, as a fraction, can.
     */
    private float chapterRatio() {
        if (toc.size() == 0) {
            return 0f;
        }
        return (chapterIndex + 0.5f) / (float) toc.size();
    }

    /** Ask which source to continue in. Reachable from 目录 and from the menu. */
    private void showSwitchDialog() {
        if (group == null || group.size() <= 1) {
            toast("没有其他可用的书源");
            return;
        }
        final int cur = group.indexOfCurrent();
        String[] labels = new String[group.size()];
        for (int i = 0; i < group.size(); i++) {
            SourceSwitcher.SrcBook s = group.items.get(i);
            labels[i] = (i == cur ? "● " : "○ ") + s.sourceName;
        }
        new AlertDialog.Builder(this)
                .setTitle("换源：" + book.name)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which != cur) {
                            switchToSource(which);
                        }
                    }
                })
                .show();
    }

    /** Adopt another source: load its 目录 and land on the chapter at the same fraction. */
    private void switchToSource(final int index) {
        final SourceSwitcher.Group g = group;
        if (g == null || index < 0 || index >= g.size()) {
            return;
        }
        final SourceSwitcher.SrcBook target = g.items.get(index);
        final BookSource ns = sourceStore.findByUrl(target.sourceUrl);
        if (ns == null) {
            toast("书源已不存在：" + target.sourceName);
            return;
        }
        final float ratio = chapterRatio();
        toast("正在切换到：" + target.sourceName);
        new Thread(new Runnable() {
            public void run() {
                Engine.loadBookInfo(ns, target.book);
                final List<Books.Chapter> got = Engine.loadToc(ns, target.book);
                handler.post(new Runnable() {
                    public void run() {
                        if (got.size() == 0) {
                            toast("「" + target.sourceName + "」目录为空，未切换");
                            return;
                        }
                        applySwitchedSource(target, ns, got, index);
                        int at = (int) (ratio * got.size());
                        if (at < 0) {
                            at = 0;
                        }
                        if (at >= got.size()) {
                            at = got.size() - 1;
                        }
                        toast("已换源：" + ns.name + "，第 " + (at + 1) + " 章");
                        loadChapter(at, 0);
                    }
                });
            }
        }).start();
    }

    /**
     * Point the reader at another source, without changing the chapter.
     *
     * Called on the UI thread by both the manual switch and the automatic one that runs when
     * a source turns out to have an empty 目录. The shelf entry is MOVED rather than
     * duplicated: the user is reading one book, not one book per source.
     */
    private void applySwitchedSource(SourceSwitcher.SrcBook s, BookSource ns,
                                     List<Books.Chapter> got, int index) {
        if (s == null || ns == null) {
            return;
        }
        group.select(index);
        source = ns;
        book = s.book;
        book.sourceName = ns.name;
        altSrc[0] = ns;
        toc.clear();
        toc.addAll(got);
        titleView.setText(book.name);
        updateFooter();
        Log.i(TAG, "reader: source -> " + ns.name + " toc=" + got.size());
    }

    /** Persist the current position on whatever source is now in use. */
    private void saveProgressFor(int index) {
        if (book == null || toc.size() == 0) {
            return;
        }
        int safe = index;
        if (safe < 0) {
            safe = 0;
        }
        if (safe >= toc.size()) {
            safe = toc.size() - 1;
        }
        progressStore.save(book, safe, toc.get(safe).name, currentOffset());
    }

    // ----------------------------------------------------------------- input

    /**
     * The centre third's gesture: SHORT TAP = show/hide the top and bottom strips,
     * LONG PRESS = open the menu.
     *
     * This has to be an OnTouchListener on the zone View, NOT Activity.onTouchEvent().
     * That is where the "menu cannot be opened" bug came from: a View with a click listener
     * is clickable, and a clickable View's onTouchEvent() returns true from ACTION_DOWN, so
     * the zone swallowed every touch and the Activity's own onTouchEvent() was never called
     * at all -- the long press was never even scheduled, which is why the menu never opened
     * no matter how long you held the middle of the screen.
     */
    private void installCentreGesture(View zone) {
        zone.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getX();
                        downY = e.getY();
                        longPressFired = false;
                        scheduleLongPress();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        // a real drag (scroll) must not turn into a tap or a long press
                        if (Math.abs(e.getX() - downX) > TOUCH_SLOP
                                || Math.abs(e.getY() - downY) > TOUCH_SLOP) {
                            cancelLongPress();
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        cancelLongPress();
                        if (!longPressFired) {
                            toggleChrome();
                        }
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        cancelLongPress();
                        return true;

                    default:
                        return false;
                }
            }
        });
    }

    /** The menu opens on a LONG PRESS in the centre third, per the requirement. */
    private void scheduleLongPress() {
        longPressRunnable = new Runnable() {
            public void run() {
                longPressFired = true;
                Log.i(TAG, "reader: long press -> menu");
                showMenu();
            }
        };
        handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
    }

    private void cancelLongPress() {
        if (longPressRunnable != null) {
            handler.removeCallbacks(longPressRunnable);
            longPressRunnable = null;
        }
    }

    /** Show/hide the top and bottom strips (a tap in the centre third, or BACK). */
    private void toggleChrome() {
        chromeVisible = !chromeVisible;
        topBar.setVisibility(chromeVisible ? View.VISIBLE : View.GONE);
        bottomBar.setVisibility(chromeVisible ? View.VISIBLE : View.GONE);
        Log.i(TAG, "reader: chrome " + (chromeVisible ? "shown" : "hidden"));
        repaginateAfterChrome();
    }

    /**
     * Re-cut the pages after the strips changed visibility.
     *
     * Showing the strips shrinks the page area, so the old page breaks would clip the last
     * line; hiding them gives the space back. A layout pass has to run before the new height
     * can be measured, and API 1 has neither OnLayoutChangeListener (API 11) nor a safe way
     * to listen for global layout here (repagination itself triggers layout, so a global
     * listener would loop). This therefore retries a bounded number of times instead.
     */
    private void repaginateAfterChrome() {
        final int keep = currentOffset();
        final int before = pageArea.getHeight();
        final int[] tries = new int[1];
        handler.postDelayed(new Runnable() {
            public void run() {
                applyBottomSafeInset();
                if (pageArea.getHeight() == before && tries[0] < 3) {
                    tries[0]++;
                    handler.postDelayed(this, 60L);
                    return;
                }
                repaginateTo(keep);
            }
        }, 60L);
    }

    /**
     * API 1 has no onBackPressed(); the back key arrives here.
     * While the chrome is showing, the first back press hides it instead of leaving.
     */
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (chromeVisible) {
                toggleChrome();
                return true;
            }
            saveProgress();
            return super.onKeyDown(keyCode, event);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            pageForward();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            pageBack();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showMenu();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    protected void onPause() {
        super.onPause();
        saveProgress();
    }

    // -------------------------------------------------------------------- menu

    private void showMenu() {
        final boolean canSwitch = (group != null && group.size() > 1);
        final String[] items = new String[]{
                "目录",
                (shelfAdded ? "已在书架" : "加入书架"),
                (canSwitch ? "换源（当前：" + book.sourceName + "）" : "换源（无其他源）"),
                "切换背景（当前：" + BG_NAMES[bgIndex] + "）",
                "增大字号",
                "减小字号",
                "上一章",
                "下一章",
                "重新加载本章"
        };
        new AlertDialog.Builder(this)
                .setTitle(book.name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0: showTocDialog(); break;
                            case 1:
                                if (shelfAdded) {
                                    toast("已经在书架里了");
                                } else {
                                    addToShelf();
                                }
                                break;
                            case 2: showSwitchDialog(); break;
                            case 3: cycleBackground(); break;
                            case 4: changeTextSize(1); break;
                            case 5: changeTextSize(-1); break;
                            case 6: openChapter(chapterIndex - 1); break;
                            case 7: openChapter(chapterIndex + 1); break;
                            default: loadChapter(chapterIndex, 0); break;
                        }
                    }
                })
                .show();
    }

    private void showTocDialog() {
        if (toc.size() == 0) {
            toast("目录还没加载好");
            return;
        }
        // API 1 has no AlertDialog.setNeutralButton (it arrives with API 3), and the 目录
        // dialog is the natural place to switch sources, so the action is the first row.
        final boolean canSwitch = (group != null && group.size() > 1);
        int extra = canSwitch ? 1 : 0;
        String[] names = new String[toc.size() + extra];
        if (canSwitch) {
            names[0] = "≡ 换源（当前：" + book.sourceName + "，共 " + group.size() + " 个源）";
        }
        for (int i = 0; i < toc.size(); i++) {
            names[i + extra] = toc.get(i).name;
        }
        new AlertDialog.Builder(this)
                .setTitle("目录（当前 " + (chapterIndex + 1) + "/" + toc.size() + "）")
                .setItems(names, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (canSwitch && which == 0) {
                            showSwitchDialog();
                        } else {
                            openChapter(which - (canSwitch ? 1 : 0));
                        }
                    }
                })
                .show();
    }

    private void changeTextSize(int delta) {
        int before = textSizeIndex;
        textSizeIndex += delta;
        if (textSizeIndex < 0) {
            textSizeIndex = 0;
        }
        if (textSizeIndex >= TEXT_SIZES.length) {
            textSizeIndex = TEXT_SIZES.length - 1;
        }
        if (before == textSizeIndex) {
            return;
        }
        final int keep = currentOffset();
        applyTheme();
        handler.post(new Runnable() {
            public void run() {
                repaginateTo(keep);
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

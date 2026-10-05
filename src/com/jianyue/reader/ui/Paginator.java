package com.jianyue.reader.ui;

import android.text.Layout;

import java.util.List;

/**
 * Page bookkeeping for the reader: which character each page starts at, which page an offset
 * falls on, and the text of a page.
 *
 * The CUTTING itself lives in {@link ReaderActivity#cutPages}, because it needs a real
 * {@link Layout} - and on API 1 the only way to get one is to measure a TextView. That is not a
 * detail: the first version of this class predicted the line breaks with Paint.breakText, and a
 * TextView breaks lines by word and follows CJK punctuation rules, so the prediction and the
 * renderer disagreed (measured: 9 rendered lines for a page cut for 7). The extra lines were
 * drawn into the bottom padding and clipped - the "最后一行显示不全" report. Line breaking is the
 * renderer's business; this class only does the arithmetic on the offsets it produces.
 */
final class Paginator {

    private Paginator() {
    }

    /** The most lines any single page ended up with (logged, and used by the render check). */
    static int maxLinesPerPage(Layout layout, List<Integer> starts) {
        if (layout == null || layout.getLineCount() == 0) {
            return 0;
        }
        if (starts == null || starts.size() < 2) {
            return layout.getLineCount();
        }
        int best = 0;
        for (int i = 0; i < starts.size(); i++) {
            int from = layout.getLineForOffset(starts.get(i).intValue());
            int to = (i + 1 < starts.size())
                    ? layout.getLineForOffset(starts.get(i + 1).intValue())
                    : layout.getLineCount();
            int lines = to - from;
            if (lines > best) {
                best = lines;
            }
        }
        return best;
    }

    /** Page index for a character offset (used when restoring saved progress). */
    static int pageOf(List<Integer> starts, int charOffset) {
        if (starts == null || starts.size() == 0) {
            return 0;
        }
        int lo = 0;
        int hi = starts.size() - 1;
        int best = 0;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            if (starts.get(mid).intValue() <= charOffset) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    /** The text of one page. */
    static String pageText(String text, List<Integer> starts, int page) {
        if (text == null || starts == null || page < 0 || page >= starts.size()) {
            return "";
        }
        int from = starts.get(page).intValue();
        int to = (page + 1 < starts.size()) ? starts.get(page + 1).intValue() : text.length();
        if (from < 0) {
            from = 0;
        }
        if (to > text.length()) {
            to = text.length();
        }
        if (to <= from) {
            return "";
        }
        return text.substring(from, to);
    }
}

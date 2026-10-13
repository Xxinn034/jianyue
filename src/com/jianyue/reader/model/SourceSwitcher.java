package com.jianyue.reader.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Cross-source book switching (换源).
 *
 * WHY THIS IS A STATIC REGISTRY
 * -----------------------------
 * API 1 has no Parcelable and the reader/detail screens are separate activities, so the
 * candidate list cannot simply be an Intent extra. The search screen registers the group
 * it just built here and the other screens look it up by key.
 *
 * It also doubles as the "which source is this book being read from" record, because the
 * Book object the screens carry already names its own source. What the registry adds is:
 *   - the OTHER sources the same book was found on,
 *   - the address of the shelf entry (primary), so switching sources does not litter the
 *     shelf with one row per source.
 *
 * Lifecycle: process-local and deliberately not persisted. If the reader is opened from the
 * shelf after a restart the group is gone and the 换源 entry simply does not appear, which
 * is the correct degradation (there is nothing to switch to).
 */
public final class SourceSwitcher {

    /** One book as offered by one source. */
    public static class SrcBook {
        public final String sourceUrl;
        public final String sourceName;
        public final Books.Book book;

        public SrcBook(String sourceUrl, String sourceName, Books.Book book) {
            this.sourceUrl = sourceUrl == null ? "" : sourceUrl;
            this.sourceName = sourceName == null ? "" : sourceName;
            this.book = book;
        }
    }

    /** All sources of one book, plus which one is currently in use. */
    public static class Group {
        public final String key;
        public final String name;
        public final List<SrcBook> items = new ArrayList<SrcBook>();
        public String primarySourceUrl = "";
        public String primaryBookUrl = "";

        Group(String key, String name) {
            this.key = key == null ? "" : key;
            this.name = name == null ? "" : name;
        }

        public int size() {
            return items.size();
        }

        public void add(SrcBook s) {
            if (s != null) {
                items.add(s);
            }
        }

        public SrcBook current() {
            for (int i = 0; i < items.size(); i++) {
                SrcBook s = items.get(i);
                if (s.sourceUrl.equals(primarySourceUrl)
                        && s.book != null && s.book.bookUrl.equals(primaryBookUrl)) {
                    return s;
                }
            }
            return items.size() > 0 ? items.get(0) : null;
        }

        public int indexOfCurrent() {
            for (int i = 0; i < items.size(); i++) {
                SrcBook s = items.get(i);
                if (s.sourceUrl.equals(primarySourceUrl)
                        && s.book != null && s.book.bookUrl.equals(primaryBookUrl)) {
                    return i;
                }
            }
            return -1;
        }

        /** Move to the source at {@code index}; returns it, or null when out of range. */
        public SrcBook select(int index) {
            if (index < 0 || index >= items.size()) {
                return null;
            }
            SrcBook s = items.get(index);
            primarySourceUrl = s.sourceUrl;
            primaryBookUrl = s.book == null ? "" : s.book.bookUrl;
            return s;
        }

        /**
         * How good the best source behind this book looks, lower being better. Used to rank
         * books in the result list: a book offered only by sources that never produce
         * chapters should not outrank one offered by a source that does.
         */
        public int bestRank() {
            int best = 99;
            for (int i = 0; i < items.size(); i++) {
                int r = SourceHealth.rankForReading(items.get(i).sourceUrl);
                if (r < best) {
                    best = r;
                }
            }
            return best;
        }
    }

    private static final List<Group> GROUPS = new ArrayList<Group>();

    private SourceSwitcher() {
    }

    /** Create a group for one merged book. */
    public static Group newGroup(String key, String name) {
        return new Group(key, name);
    }

    /**
     * Register the groups produced by one search. Replaces everything, because a new search
     * invalidates the previous result set.
     */
    public static synchronized void register(List<Group> groups) {
        GROUPS.clear();
        if (groups != null) {
            GROUPS.addAll(groups);
        }
    }

    /** Append one group (used by the reader when it had to search on the fly). */
    public static synchronized void add(Group g) {
        if (g != null && g.size() > 0) {
            GROUPS.add(g);
        }
    }

    public static synchronized Group find(String key) {
        if (key == null || key.length() == 0) {
            return null;
        }
        for (int i = 0; i < GROUPS.size(); i++) {
            if (key.equals(GROUPS.get(i).key)) {
                return GROUPS.get(i);
            }
        }
        return null;
    }

    /**
     * Find a group by title. Used by screens that were opened without a key (the shelf
     * path) but can still see the book name.
     */
    public static synchronized Group findByName(String name) {
        String k = com.jianyue.reader.engine.Engine.normalizeTitle(name);
        if (k.length() == 0) {
            return null;
        }
        for (int i = 0; i < GROUPS.size(); i++) {
            if (k.equals(GROUPS.get(i).key)) {
                return GROUPS.get(i);
            }
        }
        return null;
    }

    public static synchronized int groupCount() {
        return GROUPS.size();
    }

    // ------------------------------------------------- candidate ordering

    /**
     * The group's sources ordered by how likely each is to actually SERVE the book.
     *
     * Every source did answer a search, so search health says nothing about whether its
     * 目录 and 正文 work. {@link SourceHealth#rankForReading} carries the one signal that
     * does - "this source has produced chapters before" - so the detail screen and the
     * reader pick the same candidate for the same book (this method is the single ordering
     * both of them use).
     */
    public static List<SrcBook> ranked(Group g) {
        List<SrcBook> out = new ArrayList<SrcBook>();
        if (g == null) {
            return out;
        }
        for (int i = 0; i < g.items.size(); i++) {
            out.add(g.items.get(i));
        }
        final String cur = g.primarySourceUrl;
        java.util.Collections.sort(out, new java.util.Comparator<SrcBook>() {
            public int compare(SrcBook a, SrcBook b) {
                int ra = SourceHealth.rankForReading(a.sourceUrl);
                int rb = SourceHealth.rankForReading(b.sourceUrl);
                if (ra != rb) {
                    return ra - rb;
                }
                // keep the currently selected source first inside its rank
                boolean ca = a.sourceUrl.equals(cur);
                boolean cb = b.sourceUrl.equals(cur);
                if (ca != cb) {
                    return ca ? -1 : 1;
                }
                return 0;
            }
        });
        return out;
    }

    /** True when a book's title IS the searched title (not merely contains it). */
    public static boolean titleMatches(Books.Book b, String keyword) {
        return (b == null) ? false : titleMatches(b.name, keyword);
    }

    /**
     * Same test on a bare title. Merging groups by {@link Engine#normalizeTitle} is what
     * makes this the right comparison: 《诡舍》 and 诡舍（全本） are the same book to the
     * engine, and both are the book the user typed.
     */
    public static boolean titleMatches(String title, String keyword) {
        String a = com.jianyue.reader.engine.Engine.normalizeTitle(title);
        String k = com.jianyue.reader.engine.Engine.normalizeTitle(keyword);
        return a.length() > 0 && a.equals(k);
    }
}

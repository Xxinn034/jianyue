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
}

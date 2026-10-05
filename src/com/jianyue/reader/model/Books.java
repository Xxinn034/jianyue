package com.jianyue.reader.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Data classes for the online-reading flow.
 *
 * Plain beans: API 1 has no Room and no annotations processing.
 */
public final class Books {

    private Books() {
    }

    /** A book as returned by a source's search or detail rule. */
    public static class Book {
        public String name = "";
        public String author = "";
        public String kind = "";
        public String intro = "";
        public String wordCount = "";
        public String lastChapter = "";
        public String coverUrl = "";
        public String bookUrl = "";
        public String tocUrl = "";
        public String sourceName = "";
        public String sourceUrl = "";
        /** One-line description for list rows. */
        public String subtitle() {
            StringBuilder sb = new StringBuilder();
            if (author.length() > 0) {
                sb.append(author);
            }
            if (kind.length() > 0) {
                if (sb.length() > 0) {
                    sb.append("  |  ");
                }
                sb.append(kind);
            }
            if (wordCount.length() > 0) {
                if (sb.length() > 0) {
                    sb.append("  |  ");
                }
                sb.append(wordCount).append("字");
            }
            if (sourceName.length() > 0) {
                if (sb.length() > 0) {
                    sb.append("  |  ");
                }
                sb.append(sourceName);
            }
            return sb.toString();
        }
    }

    /** One entry in a table of contents. */
    public static class Chapter {
        public String name = "";
        public String url = "";
        public boolean vip;
        public int index;

        public String toString() {
            return name;
        }
    }

    /** Convenience holder for a fetched chapter. */
    public static class ChapterContent {
        public String title = "";
        public String content = "";
        public String nextUrl = "";
    }

    /** Result of one source's search. */
    public static class SearchResult {
        public String sourceName = "";
        public List<Book> books = new ArrayList<Book>();
        public String error;
        public long elapsedMs;

        public boolean ok() {
            return error == null;
        }
    }
}

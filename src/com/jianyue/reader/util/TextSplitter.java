package com.jianyue.reader.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cut a whole book (a .txt, or the flattened text of an .html / .epub document) into
 * chapters.
 *
 * Two strategies, in this order:
 *   1. Chapter heading LINES. A line is a heading when it is short and starts with a
 *      Chinese chapter marker (第一章 / 第 12 回 / 楔子 / 番外 ...) or "Chapter N".
 *      This is what essentially every downloaded novel uses.
 *   2. Fixed-size chunks, if fewer than two headings were found (a book with no chapter
 *      marks at all). Chunking happens at a line break so a paragraph is never cut in half.
 *
 * The result is a list of (name, start, end) ranges into the ORIGINAL string: the caller
 * turns those into a byte index over a cached UTF-8 file, which is what makes reading a
 * chapter from a multi-megabyte book a seek instead of a scan.
 *
 * API-1 notes: java.util.regex works on API 1 (the rule engine already relies on it),
 * String.isEmpty() does not exist (Java 6), and neither do try-with-resources.
 */
public final class TextSplitter {

    /** One chapter as a character range inside the text it was split from. */
    public static class Seg {
        public String name;      // empty when the caller should number it
        public int start;
        public int end;
    }

    /** Chunk size for a book with no recognisable chapter headings. */
    private static final int FALLBACK_CHUNK = 6000;

    /**
     * A heading line: optional 正文 prefix, then 第N章/节/回/卷... or a bare 楔子/序章/番外,
     * or "Chapter N", with at most a short title after it and nothing else on the line.
     */
    private static final String HEADING =
            "^[\\s\\u3000]*("
            + "(?:正文[\\s\\u3000]*)?第[\\s\\u3000]*[0-9０-９零一二三四五六七八九十百千万两〇]{1,10}"
            + "[\\s\\u3000]*[章节節回卷集部篇话幕折]"
            + "|[Cc]hapter[\\s\\u3000]+[0-9]{1,4}"
            + "|(?:楔子|序章|序言|序曲|引子|前言|后记|尾声|终章|完本感言)"
            + "|番外[\\s\\u3000]*[0-9一二三四五六七八九十]{0,4}"
            + ")[\\s\\u3000]*[^\\n]{0,25}[\\s\\u3000]*$";

    private static final Pattern HEADING_PATTERN = Pattern.compile(HEADING, Pattern.MULTILINE);

    private TextSplitter() {
    }

    /**
     * Split into chapters. Names are filled in when real headings were found; when the
     * fallback chunking is used the names are left EMPTY so the caller can number them
     * across the whole book (an epub splits per document, so per-document numbering would
     * repeat).
     */
    public static List<Seg> split(String text) {
        List<Seg> out = new ArrayList<Seg>();
        if (text == null || text.length() == 0) {
            return out;
        }
        List<Seg> byHeading = byHeading(text);
        if (byHeading.size() >= 2) {
            return byHeading;
        }
        return byChunk(text);
    }

    private static List<Seg> byHeading(String text) {
        List<Seg> out = new ArrayList<Seg>();
        Matcher m = HEADING_PATTERN.matcher(text);
        List<Integer> starts = new ArrayList<Integer>();
        List<String> names = new ArrayList<String>();
        while (m.find()) {
            starts.add(Integer.valueOf(m.start()));
            names.add(m.group().trim());
        }
        if (starts.size() == 0) {
            return out;
        }
        // text before the first heading: a real preface if it is long enough, otherwise it
        // is front matter (title / author / source site) and is folded into chapter one
        int first = starts.get(0).intValue();
        int bodyStart = first;
        if (first > 200) {
            Seg pre = new Seg();
            pre.name = "开始";
            pre.start = 0;
            pre.end = first;
            out.add(pre);
        }
        for (int i = 0; i < starts.size(); i++) {
            Seg s = new Seg();
            s.name = names.get(i);
            s.start = (i == 0) ? bodyStart : starts.get(i).intValue();
            s.end = (i + 1 < starts.size()) ? starts.get(i + 1).intValue() : text.length();
            if (s.end > s.start) {
                out.add(s);
            }
        }
        return out;
    }

    private static List<Seg> byChunk(String text) {
        List<Seg> out = new ArrayList<Seg>();
        int pos = 0;
        int n = text.length();
        while (pos < n) {
            int end = pos + FALLBACK_CHUNK;
            if (end >= n) {
                end = n;
            } else {
                int nl = text.indexOf('\n', end);
                if (nl > 0 && nl - end < 1500) {
                    end = nl + 1;
                }
            }
            if (end <= pos) {
                end = n;
            }
            Seg s = new Seg();
            s.name = "";
            s.start = pos;
            s.end = end;
            out.add(s);
            pos = end;
        }
        return out;
    }
}

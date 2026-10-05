package com.jianyue.reader.util;

import java.util.regex.Pattern;

/**
 * 正文排版 (chapter text -&gt; the text the reading page shows).
 *
 * <p>This is the project's ONLY entry point for the layout of a chapter on the page, and it is
 * deliberately the only place that knows about it: the reader calls it for a chapter fetched from
 * a book source AND for a chapter read out of an imported local book, so the two cannot drift
 * apart (同一语义只能有一个入口 - the rule that the pagination and rule-engine work already
 * taught this project).
 *
 * <p>Semantics come from 阅读 3.0 (ContentProcessor.getContent with includeTitle=true, plus
 * ReadBookConfig.paragraphIndent = "　　"):
 *
 * <ol>
 *   <li>drop the chapter name when the source repeats it at the very start of the body
 *       (阅读: {@code ^(\s|\p{P}|书名)*标题(\s)+} -&gt; "");</li>
 *   <li>the chapter name gets its own FIRST line, so the title sits at the top of the page
 *       (标题置顶) and is never indented;</li>
 *   <li>every other paragraph starts with two ideographic spaces (其余每段开头空两个中文字符).
 *       The indent is real characters, not a drawing trick, so the paginator measures it with the
 *       very layout that will draw it.</li>
 * </ol>
 */
public final class Paragraphs {

    /** 段落缩进, 阅读's default ReadBookConfig.paragraphIndent. */
    public static final String INDENT = "\u3000\u3000";

    /**
     * What may sit in front of a repeated chapter name: whitespace and punctuation.
     *
     * <p>Spelled out instead of 阅读's {@code \p{P}} because API 1's regex engine does not know
     * the Unicode category escapes - {@code \p{IsPunctuation}} compiles on the JDK but throws on
     * Android 1.0, and a throw here would silently skip the de-duplication. {@code \p{Punct}}
     * (POSIX, ASCII) is portable, so the CJK marks are listed explicitly.
     */
    private static final String TITLE_LEAD =
            "\\s\\u3000\\u00A0\\p{Punct}"
                    + "\u3001\u3002\uFF0C\uFF1A\uFF1B\uFF01\uFF1F\u2026\u2014\uFF5E\u00B7"
                    + "\u300C\u300D\u300E\u300F\u201C\u201D\u2018\u2019\uFF08\uFF09"
                    + "\u300A\u300B\u3008\u3009\u3010\u3011";

    private Paragraphs() {
    }

    /**
     * Build the text of one chapter page flow.
     *
     * @param content  chapter body as the source returned it (may still be html-ish; block tags
     *                 have already been turned into line breaks by Rules.cleanText)
     * @param title    chapter name, shown as the first line
     * @param bookName book name, used only to recognise a repeated title ("斗罗大陆 第1章 ...")
     */
    public static String format(String content, String title, String bookName) {
        String name = (title == null) ? "" : trimBlank(title);
        String body = (content == null) ? "" : content;
        if (name.length() > 0) {
            body = stripLeadingTitle(body, name, bookName);
        }
        StringBuilder sb = new StringBuilder(body.length() + 64);
        if (name.length() > 0) {
            sb.append(name);
        }
        String[] lines = body.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String p = trimBlank(lines[i]);
            if (p.length() == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(INDENT).append(p);
        }
        return sb.toString();
    }

    /**
     * Remove a chapter name repeated at the start of the body.
     *
     * <p>阅读's regex, kept in the same shape: optional whitespace / punctuation / book name, then
     * the chapter name, then whitespace. The trailing whitespace requirement is what keeps it from
     * biting a title that is a prefix of the first sentence ("引子" in "引子里的秘密").
     *
     * <p>U+3000 (　) and nbsp count as whitespace here on top of 阅读's \s: sites that indent with
     * 　　 put one in front of the repeated title, and Java's \s does not cover it.
     */
    static String stripLeadingTitle(String body, String title, String bookName) {
        if (body == null || title == null || title.length() == 0) {
            return body == null ? "" : body;
        }
        StringBuilder re = new StringBuilder("^[").append(TITLE_LEAD);
        if (bookName != null && bookName.trim().length() > 0) {
            re.append(Pattern.quote(bookName.trim()));
        }
        re.append("]*").append(Pattern.quote(title)).append("[\\s\\u3000\\u00A0]+");
        try {
            return Pattern.compile(re.toString()).matcher(body).replaceFirst("");
        } catch (Throwable t) {
            // An unsupported escape must leave the chapter readable, not empty.
            return body;
        }
    }

    /**
     * Trim the characters that count as blank in novel text: \s, nbsp and U+3000.
     *
     * <p>Public because it is the project's ONE definition of "空白" for novel text: the HTML
     * cleaner ({@link com.jianyue.reader.rule.Rules#cleanText}) trims lines with it and the
     * paragraph layout trims paragraphs with it. Two copies of this rule would eventually
     * disagree about 　.
     */
    public static String trimBlank(String line) {
        if (line == null) {
            return "";
        }
        int start = 0;
        int end = line.length();
        while (start < end && isBlank(line.charAt(start))) {
            start++;
        }
        while (end > start && isBlank(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(start, end);
    }

    private static boolean isBlank(char c) {
        return c == '\u3000' || c == '\u00A0' || c <= ' ';
    }
}

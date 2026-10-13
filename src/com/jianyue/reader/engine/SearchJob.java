package com.jianyue.reader.engine;

import android.util.Log;

import com.jianyue.reader.model.BookSource;
import com.jianyue.reader.model.Books;
import com.jianyue.reader.model.SourceHealth;
import com.jianyue.reader.net.Http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs one search across every enabled book source.
 *
 * WHAT WAS WRONG BEFORE
 * ---------------------
 * The first version used four threads and then waited for all of them. With five sources that
 * is fine. With two hundred imported 阅读 sources it is not: most of those sites are dead on
 * API 1 (TLS 1.2-only, parked domains, changed URLs), every dead one costs a connect timeout,
 * and four threads means the list is walked in batches of four. The search therefore took
 * minutes and showed NOTHING until the last source finished. Measured on the API-1 emulator
 * with 120 imported sources: the old engine got through 7 of them in 120 seconds.
 *
 * HOW THIS WORKS
 * --------------
 * One queue, N worker threads, and three rules that decide what happens to a source:
 *
 *   - The queue is ordered by {@link SourceHealth}: sources that answered last time go first,
 *     so a repeat search shows results within a round trip.
 *   - When a source's HOST is already known to be unreachable (refused, TLS ceiling, connect
 *     timeout - see {@link Http#deadReason}), that source is moved to the TAIL of the queue
 *     instead of being dropped. Everything not on a dead host is therefore attempted first,
 *     and the sources sharing a dead domain are still tried, once, at the end. A search of
 *     200 sources that live on 40 hosts then spends 40 timeouts before it spends the 200th.
 *   - Every source is reported the moment it finishes (streamed), so the list grows while the
 *     tail is still working, and the UI can stop at any time and keep what it has.
 *
 * Waiting, not computing, is what a search costs, so many requests are in flight at once (see
 * threadCount) while the number of pages being DECODED AND PARSED simultaneously is capped by
 * a small semaphore - sockets are cheap, DOMs are not, and an API-1 device has 16 MB of heap.
 *
 * A whole-search budget (default {@link #DEFAULT_BUDGET_MS}) guarantees the search ENDS;
 * sources not reached inside it are reported as timeout rather than silently dropped.
 */
public final class SearchJob {

    private static final String TAG = "JianYue";

    /**
     * The point at which a search gives up on the sources it has not reached yet.
     *
     * This is a safety net, not a target: it exists so that a resolver which ignores its own
     * connect timeout cannot leave a search spinning forever. The user experience does not
     * depend on it, because results stream in from the first second and the 停止 button ends
     * the search whenever the list already has what the user wanted. Measured on the API-1
     * emulator (where every dead host costs a full timeout), 120 sources need about two and a
     * half minutes to be attempted for real.
     */
    public static final int DEFAULT_BUDGET_MS = 180000;

    /**
     * Connect timeout for a search request, in ms.
     *
     * Shorter than the default 8s on purpose. A search spends its time on sites that are
     * never going to answer, and every one of them costs a full connect timeout (twice, when
     * the URL is https and the fallback tries http). Measured on a real 227-source list:
     * 208 sources failed, the search took 105s, and almost all of it was waiting. A live
     * novel site answers a search in well under two seconds; four seconds is generous for
     * the ones that are merely slow, and halves the price of the dead ones.
     *
     * 目录 and 正文 keep the full 8s: those are single requests the user is waiting on.
     */
    public static final int SEARCH_CONNECT_TIMEOUT_MS = 4000;

    /**
     * Whole-search budget actually used, in ms.
     *
     * {@link #DEFAULT_BUDGET_MS} is the value the engine is written against; this is what a
     * run uses. A device whose network is pathologically slow (the API-1 emulator speaks
     * only TLS 1.0, so every https site pays a handshake that fails) can raise it with the
     * system property "jianyue.searchBudget", which is how the API-1 acceptance scripts keep
     * their old 200s budget while a real phone stops wasting time on the tail.
     */
    public static int budgetMs() {
        int def = 60000;
        try {
            String v = System.getProperty("jianyue.searchBudget");
            if (v != null && v.trim().length() > 0) {
                int n = Integer.parseInt(v.trim());
                if (n >= 5000) {
                    return n;
                }
            }
        } catch (Throwable ignored) {
        }
        return def;
    }

    /**
     * How many pages may be decoded and parsed at once. Parsing is what allocates: a byte[]
     * body, its String, and the DOM built from it.
     */
    private static final int PARSE_SLOTS = 4;

    /** Progress callback. All methods are called on worker threads. */
    public interface Listener {
        /**
         * One source finished. Called exactly once per source, including the ones skipped
         * without a request (books is empty then and note says why).
         *
         * @param done  sources accounted for so far, for a progress counter
         * @param total sources in this search
         */
        void onSourceDone(int done, int total, BookSource src, List<Books.Book> books, String note);

        /** Polled before each source starts; return true to abandon the rest. */
        boolean cancelled();
    }

    /** One source to search, plus whether it has already been deferred once. */
    private static final class Task {
        final BookSource src;
        final boolean afterHostCache;

        Task(BookSource src, boolean afterHostCache) {
            this.src = src;
            this.afterHostCache = afterHostCache;
        }
    }

    private SearchJob() {
    }

    /**
     * Search every source and return everything that was found.
     *
     * Kept as the simple entry point (no streaming, no cancellation) for callers that just
     * want the answer: the rule checker, the source verifier, fillFromSearch.
     */
    public static List<Books.Book> run(List<BookSource> sources, String keyword, int page,
                                       List<String> failuresOut) {
        return run(sources, keyword, page, failuresOut, null, budgetMs());
    }

    public static List<Books.Book> run(List<BookSource> sources, String keyword, int page,
                                       List<String> failuresOut, Listener listener,
                                       int budgetMs) {
        final List<Books.Book> out = new ArrayList<Books.Book>();
        if (sources == null || sources.size() == 0) {
            return out;
        }
        final long t0 = System.currentTimeMillis();

        // 1. Drop sources that cannot possibly answer, without spending a request on them.
        final List<BookSource> usable = new ArrayList<BookSource>();
        int notText = 0;
        int noRule = 0;
        for (int i = 0; i < sources.size(); i++) {
            BookSource s = sources.get(i);
            if (s == null) {
                continue;
            }
            if (s.type != 0) {
                notText++;                       // audio/image/file sources: not readable here
                continue;
            }
            if (s.searchUrl == null || s.searchUrl.length() == 0
                    || s.searchRules == null || s.searchRules.bookList == null
                    || s.searchRules.bookList.length() == 0) {
                noRule++;
                if (failuresOut != null) {
                    failuresOut.add(s.name + ": 未配置搜索规则（searchUrl/bookList 为空）");
                }
                continue;
            }
            usable.add(s);
        }
        if (notText > 0) {
            Log.i(TAG, "searchAll: skipped " + notText + " non-text sources");
        }
        if (noRule > 0) {
            Log.i(TAG, "searchAll: skipped " + noRule + " sources without search rules");
        }
        if (usable.size() == 0) {
            Log.i(TAG, "engine.searchAll sources=0 threads=0 books=0");
            return out;
        }

        // 2. Best-known sources first.
        SourceHealth.sortByHealth(usable);

        final int total = usable.size();
        final int threads = threadCount(total);
        final long deadline = System.currentTimeMillis() + Math.max(5000, budgetMs);
        final LinkedList<Task> queue = new LinkedList<Task>();
        for (int i = 0; i < usable.size(); i++) {
            queue.add(new Task(usable.get(i), false));
        }
        final List<Books.Book> found = Collections.synchronizedList(new ArrayList<Books.Book>());
        final AtomicInteger accounted = new AtomicInteger();
        final AtomicInteger skipped = new AtomicInteger();
        final Semaphore parseSlots = new Semaphore(PARSE_SLOTS);
        final ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int w = 0; w < threads; w++) {
            pool.execute(new Runnable() {
                public void run() {
                    worker(queue, keyword, page, deadline, listener, failuresOut, parseSlots,
                            accounted, total, skipped, found);
                }
            });
        }
        pool.shutdown();
        try {
            // Every task checks the deadline before starting, so once it passes the pool
            // drains in milliseconds. The extra cap is the hard guarantee that a search always
            // ends, even if a resolver ignores the connect timeout.
            if (!pool.awaitTermination(Math.max(5000, budgetMs) + 30000L, TimeUnit.MILLISECONDS)) {
                Log.i(TAG, "searchAll: hard cap reached, abandoning the rest");
                pool.shutdownNow();
            }
        } catch (Throwable ignored) {
        }

        out.addAll(found);
        SourceHealth.save();
        Log.i(TAG, "engine.searchAll sources=" + total + " threads=" + threads
                + " books=" + out.size()
                + " skippedHost=" + skipped.get()
                + " ms=" + (System.currentTimeMillis() - t0));
        return out;
    }

    /** One worker: take the head of the queue, search it, repeat until the queue is empty. */
    private static void worker(LinkedList<Task> queue, String keyword, int page, long deadline,
                               Listener listener, List<String> failuresOut, Semaphore parseSlots,
                               AtomicInteger accounted, int total, AtomicInteger skipped,
                               List<Books.Book> found) {
        while (true) {
            Task task;
            synchronized (queue) {
                task = queue.poll();
            }
            if (task == null) {
                return;
            }
            BookSource src = task.src;
            try {
                if (listener != null && listener.cancelled()) {
                    finish(listener, accounted, total, src, null, "已停止");
                    continue;
                }
                if (System.currentTimeMillis() > deadline) {
                    skipped.incrementAndGet();
                    finish(listener, accounted, total, src, null, "超时未查");
                    continue;
                }
                if (!task.afterHostCache) {
                    String dead = Http.deadReason(src.url);
                    if (dead != null) {
                        // Another source on this host already failed in this process. Move it
                        // to the tail: the sources that still have a chance must not queue
                        // behind a domain that has just timed out.
                        synchronized (queue) {
                            queue.add(new Task(src, true));
                        }
                        continue;
                    }
                }
                List<Books.Book> books = null;
                String error = null;
                Books.SearchResult r = searchOne(src, keyword, page, parseSlots);
                if (r.ok() && r.books.size() > 0) {
                    books = r.books;
                    found.addAll(books);
                    SourceHealth.record(src.url, true, true, r.elapsedMs);
                } else if (!r.ok()) {
                    error = src.name + ": " + r.error;
                    // "reachable but empty" is a rule problem, not a network one, so only a
                    // real fetch failure is counted against the source.
                    SourceHealth.record(src.url, !r.unreachable, false, r.elapsedMs);
                } else {
                    error = src.name + ": 无结果";
                    SourceHealth.record(src.url, true, false, r.elapsedMs);
                }
                if (error != null && failuresOut != null) {
                    failuresOut.add(error);
                }
                finish(listener, accounted, total, src, books, null);
            } catch (Throwable t) {
                Log.i(TAG, "searchAll: " + src.name + " threw " + t);
                finish(listener, accounted, total, src, null, "出错");
            }
        }
    }

    private static void finish(Listener listener, AtomicInteger accounted, int total,
                               BookSource src, List<Books.Book> books, String note) {
        int d = accounted.incrementAndGet();
        if (listener != null) {
            try {
                listener.onSourceDone(d, total, src, books, note);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * One source: build the URL, fetch it, then parse under the parse semaphore.
     *
     * The split matters for memory on API 1: many bodies may be in flight (that is just
     * sockets), but only a few are decoded into Strings and DOMs at the same time.
     */
    private static Books.SearchResult searchOne(BookSource src, String keyword, int page,
                                                Semaphore parseSlots) {
        com.jianyue.reader.rule.Rules.resetPutVars();
        Books.SearchResult res = new Books.SearchResult();
        res.sourceName = src.name;
        long t0 = System.currentTimeMillis();
        try {
            String url = Engine.searchUrlFor(src, keyword, page);
            if (url == null || url.length() == 0) {
                res.error = "搜索地址为空";
                return res;
            }
            Http.Resp r = Http.fetch(url, src.url, src.headers(), SEARCH_CONNECT_TIMEOUT_MS);
            if (!r.ok() || r.code >= 400) {
                res.error = (r.error != null) ? r.error : ("HTTP " + r.code);
                res.unreachable = r.unreachable();
                return res;
            }
            Log.i(TAG, "engine.search " + src.name + " url=" + url
                    + " bytes=" + (r.body == null ? 0 : r.body.length));
            parseSlots.acquire();
            try {
                res.books = Engine.parseSearchBody(src, r.text());
            } finally {
                parseSlots.release();
            }
            Log.i(TAG, "engine.search " + src.name + " -> " + res.books.size() + " books");
            if (res.books.size() == 0) {
                res.error = "没有解析到结果（检查 bookList 规则）";
            }
            return res;
        } catch (Throwable t) {
            res.error = t.getClass().getName() + ": " + t.getMessage();
            Log.i(TAG, "engine.search failed " + res.error);
            return res;
        } finally {
            res.elapsedMs = System.currentTimeMillis() - t0;
        }
    }

    /**
     * How many requests to keep in flight.
     *
     * The old fixed value of 4 assumed threads were expensive. They are not: they are blocked
     * on the network. What IS expensive is the heap, because every finished response becomes a
     * String and a DOM - which is why the parse step is separately limited and this number can
     * be much higher. The heap check keeps an API-1 device (16-24 MB) from being handed 16
     * concurrent pages while a modern phone gets the full width.
     */
    public static int threadCount(int sources) {
        long heapMb = 0;
        try {
            heapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        } catch (Throwable ignored) {
        }
        int want;
        if (heapMb >= 64) {
            want = 16;
        } else if (heapMb >= 32) {
            want = 14;
        } else if (heapMb >= 20) {
            want = 10;
        } else {
            want = 8;
        }
        Log.i(TAG, "searchAll: heap=" + heapMb + "MB -> threads=" + want);
        return Math.max(1, Math.min(want, sources));
    }
}

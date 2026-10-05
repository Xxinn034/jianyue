package com.jianyue.reader.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Low-level file helpers, written for Android 1.0 (API level 1).
 *
 * Deliberately avoids:
 *   - try-with-resources  (Java 7; the Kotlin probe proved API-1 Dalvik's verifier
 *     rejects whole classes that reference missing methods)
 *   - java.util.Arrays.copyOfRange and friends (API 9+) -- this is exactly what broke
 *     kotlin.jvm.internal.Intrinsics
 *   - java.nio.charset / StandardCharsets (API 9+)
 *   - String.isEmpty() (Java 6)
 *   - lambdas / generics inference that needs Java 8
 */
public final class FileUtils {

    private FileUtils() {
    }

    public static final byte[] EMPTY = new byte[0];

    /** Read a whole file into a byte array. Returns null on failure. */
    public static byte[] readAll(File f) {
        if (f == null || !f.isFile()) {
            return null;
        }
        InputStream in = null;
        ByteArrayOutputStream out = null;
        try {
            in = new FileInputStream(f);
            out = new ByteArrayOutputStream((int) Math.max(1024, f.length()));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            close(in);
            close(out);
        }
    }

    public static void close(Object c) {
        if (c == null) {
            return;
        }
        try {
            if (c instanceof InputStream) {
                ((InputStream) c).close();
            } else if (c instanceof FileOutputStream) {
                ((FileOutputStream) c).close();
            } else if (c instanceof java.io.OutputStream) {
                ((java.io.OutputStream) c).close();
            }
        } catch (Throwable ignored) {
            // nothing useful to do on API 1
        }
    }

    public static String nameOf(File f) {
        if (f == null) {
            return "";
        }
        String n = f.getName();
        return n == null ? "" : n;
    }

    public static String parentOf(File f) {
        if (f == null) {
            return "";
        }
        File p = f.getParentFile();
        return p == null ? "" : p.getAbsolutePath();
    }

    /** Human-readable byte size, old-school (no String.format). */
    public static String sizeText(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        long kb = bytes / 1024L;
        if (kb < 1024L) {
            return kb + " KB";
        }
        long mb = kb / 1024L;
        long rem = (kb % 1024L) * 10L / 1024L;
        return mb + "." + rem + " MB";
    }

    /** True when the name ends with .txt (case-insensitive), avoiding regex. */
    public static boolean isTxt(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        return lower.endsWith(".txt");
    }

    public static boolean isEpub(String name) {
        if (name == null) {
            return false;
        }
        return name.toLowerCase().endsWith(".epub");
    }

    public static String join(String dir, String name) {
        if (dir == null) {
            dir = "";
        }
        if (dir.length() == 0) {
            return name;
        }
        if (dir.charAt(dir.length() - 1) == '/') {
            return dir + name;
        }
        return dir + "/" + name;
    }

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (Throwable ignored) {
        }
    }

    /** Recursive delete, keeps the code free of Java 7 NIO. */
    public static boolean deleteTree(File f) {
        if (f == null || !f.exists()) {
            return true;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (int i = 0; i < kids.length; i++) {
                    deleteTree(kids[i]);
                }
            }
        }
        return f.delete();
    }

    public static long lengthOf(File f) {
        return f == null ? 0L : f.length();
    }

    public static IOException ioError(String msg) {
        return new IOException(msg);
    }
}

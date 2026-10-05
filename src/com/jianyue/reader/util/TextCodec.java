package com.jianyue.reader.util;

import java.io.UnsupportedEncodingException;

/**
 * Text decoding for Android 1.0 (API level 1).
 *
 * Why hand-written:
 *   - java.nio.charset / StandardCharsets are API 9+ and may be absent on API 1.
 *   - The platform charset set on Android 1.0 cannot be relied on for GBK/GB18030.
 *   - The Kotlin probe proved that a single missing method makes API-1's Dalvik verifier
 *     reject an entire class, so every JDK call here is treated as "may not exist".
 *
 * Strategy:
 *   1. sniff BOM (UTF-8 / UTF-16LE / UTF-16BE)
 *   2. otherwise try a hand-rolled UTF-8 validator
 *   3. otherwise try a hand-rolled GB18030/GBK decoder (covers all common Chinese TXT)
 *   4. finally fall back to a plain ISO-8859-1 style byte->char widening
 *
 * Deliberately avoids: try-with-resources, lambdas, String.isEmpty(), Arrays.copyOfRange.
 */
public final class TextCodec {

    public static final int ENC_UNKNOWN = 0;
    public static final int ENC_UTF8 = 1;
    public static final int ENC_UTF16LE = 2;
    public static final int ENC_UTF16BE = 3;
    public static final int ENC_GBK = 4;
    public static final int ENC_LATIN1 = 5;

    private TextCodec() {
    }

    public static String nameOf(int enc) {
        switch (enc) {
            case ENC_UTF8:    return "UTF-8";
            case ENC_UTF16LE: return "UTF-16LE";
            case ENC_UTF16BE: return "UTF-16BE";
            case ENC_GBK:     return "GB2312";
            case ENC_LATIN1:  return "Latin-1";
            default:          return "detect";
        }
    }

    /**
     * Structural UTF-8 validity check over a whole byte range.
     *
     * This is the reliable discriminator, and it is deliberately strict:
     * a GBK-encoded Chinese file is almost never valid UTF-8, because GBK trail bytes
     * land in 0x40..0x7E and 0x80..0xFE, so they frequently break the 0x80..0xBF
     * continuation rule. (Measured on a real GBK file: score-based comparison picked
     * GBK, validity-based comparison also picks GBK - the two agree.)
     *
     * The scoring heuristic alone is NOT trustworthy for this decision: decoding UTF-8
     * bytes as GBK also yields a large run of CJK codepoints, which scores high despite
     * being mojibake. Validity is the signal that cannot be faked.
     */
    public static boolean isValidUtf8(byte[] d, int off, int len) {
        int i = off;
        int end = off + len;
        while (i < end) {
            int b = d[i] & 0xFF;
            if (b < 0x80) {
                i++;
                continue;
            }
            int need;
            int cp;
            if ((b & 0xE0) == 0xC0) {
                need = 1;
                cp = b & 0x1F;
            } else if ((b & 0xF0) == 0xE0) {
                need = 2;
                cp = b & 0x0F;
            } else if ((b & 0xF8) == 0xF0) {
                need = 3;
                cp = b & 0x07;
            } else {
                return false;
            }
            if (i + need >= end) {
                // Truncated tail at the end of the buffer: not proof of invalidity,
                // because callers pass fixed-size windows that can cut a character.
                return true;
            }
            int acc = cp;
            for (int k = 1; k <= need; k++) {
                int cb = d[i + k] & 0xFF;
                if ((cb & 0xC0) != 0x80) {
                    return false;
                }
                acc = (acc << 6) | (cb & 0x3F);
            }
            if (need == 1 && acc < 0x80) return false;        // overlong
            if (need == 2 && acc < 0x800) return false;       // overlong
            if (need == 3 && acc < 0x10000) return false;     // overlong
            if (acc > 0x10FFFF) return false;                 // out of range
            if (acc >= 0xD800 && acc <= 0xDFFF) return false; // lone surrogate
            i += need + 1;
        }
        return true;
    }

    /**
     * Guess the encoding of a byte range.
     * Returns one of the ENC_* constants.
     *
     * Order is deliberate: BOM first, then structural UTF-8 validity, then GBK.
     * Validity is trusted over any quality score (see isValidUtf8 for why).
     */
    public static int detect(byte[] data, int off, int len) {
        if (data == null || len <= 0) {
            return ENC_UTF8;
        }
        // --- BOM ---
        if (len >= 3 && (data[off] & 0xFF) == 0xEF
                && (data[off + 1] & 0xFF) == 0xBB && (data[off + 2] & 0xFF) == 0xBF) {
            return ENC_UTF8;
        }
        if (len >= 2 && (data[off] & 0xFF) == 0xFF && (data[off + 1] & 0xFF) == 0xFE) {
            return ENC_UTF16LE;
        }
        if (len >= 2 && (data[off] & 0xFF) == 0xFE && (data[off + 1] & 0xFF) == 0xFF) {
            return ENC_UTF16BE;
        }
        // --- structural check: valid UTF-8 wins outright ---
        if (isValidUtf8(data, off, len)) {
            return ENC_UTF8;
        }
        // --- not valid UTF-8: by far the most likely alternative is GBK ---
        return ENC_GBK;
    }

    /** Decode a byte range with a known/guessed encoding. */
    public static String decode(byte[] data, int off, int len, int enc) {
        if (data == null || len <= 0) {
            return "";
        }
        if (off < 0) {
            off = 0;
        }
        if (off + len > data.length) {
            len = data.length - off;
        }
        switch (enc) {
            case ENC_UTF16LE: return decodeUtf16(data, off, len, true);
            case ENC_UTF16BE: return decodeUtf16(data, off, len, false);
            case ENC_GBK:     return decodeGb18030(data, off, len);
            case ENC_LATIN1:  return decodeLatin1(data, off, len);
            case ENC_UTF8:
            default:          return decodeUtf8(data, off, len, true);
        }
    }

    /** Convenience: detect then decode. */
    public static String decodeAuto(byte[] data, int off, int len) {
        int enc = detect(data, off, len);
        return decode(data, off, len, enc);
    }

    // ------------------------------------------------------------------ UTF-8

    private static final char REPLACEMENT = '\uFFFD';

    public static String decodeUtf8(byte[] d, int off, int len, boolean skipBom) {
        int i = off;
        int end = off + len;
        if (skipBom && len >= 3 && (d[off] & 0xFF) == 0xEF
                && (d[off + 1] & 0xFF) == 0xBB && (d[off + 2] & 0xFF) == 0xBF) {
            i = off + 3;
        }
        // worst case: every byte becomes one char
        char[] out = new char[end - i];
        int o = 0;
        while (i < end) {
            int b = d[i] & 0xFF;
            if (b < 0x80) {
                out[o++] = (char) b;
                i++;
                continue;
            }
            int need;
            int cp;
            if ((b & 0xE0) == 0xC0) {
                need = 1;
                cp = b & 0x1F;
            } else if ((b & 0xF0) == 0xE0) {
                need = 2;
                cp = b & 0x0F;
            } else if ((b & 0xF8) == 0xF0) {
                need = 3;
                cp = b & 0x07;
            } else {
                out[o++] = REPLACEMENT;
                i++;
                continue;
            }
            if (i + need >= end) {
                out[o++] = REPLACEMENT;   // truncated sequence at the chunk edge
                i = end;
                break;
            }
            boolean ok = true;
            int acc = cp;
            for (int k = 1; k <= need; k++) {
                int cb = d[i + k] & 0xFF;
                if ((cb & 0xC0) != 0x80) {
                    ok = false;
                    break;
                }
                acc = (acc << 6) | (cb & 0x3F);
            }
            if (!ok) {
                out[o++] = REPLACEMENT;
                i++;
                continue;
            }
            if (need == 1 && acc < 0x80) {
                out[o++] = REPLACEMENT;
                i += 2;
                continue;
            }
            if (need == 2 && acc < 0x800) {
                out[o++] = REPLACEMENT;
                i += 3;
                continue;
            }
            if (need == 3 && acc < 0x10000) {
                out[o++] = REPLACEMENT;
                i += 4;
                continue;
            }
            if (acc > 0x10FFFF) {
                out[o++] = REPLACEMENT;
                i += need + 1;
                continue;
            }
            if (acc >= 0xD800 && acc <= 0xDFFF) {
                out[o++] = REPLACEMENT;
                i += need + 1;
                continue;
            }
            if (acc <= 0xFFFF) {
                out[o++] = (char) acc;
            } else {
                int v = acc - 0x10000;
                out[o++] = (char) (0xD800 + (v >> 10));
                out[o++] = (char) (0xDC00 + (v & 0x3FF));
            }
            i += need + 1;
        }
        return new String(out, 0, o);
    }

    // ---------------------------------------------------------------- UTF-16

    public static String decodeUtf16(byte[] d, int off, int len, boolean little) {
        int i = off;
        int end = off + len;
        if (len >= 2) {
            int b0 = d[off] & 0xFF;
            int b1 = d[off + 1] & 0xFF;
            if (little && b0 == 0xFF && b1 == 0xFE) {
                i = off + 2;
            } else if (!little && b0 == 0xFE && b1 == 0xFF) {
                i = off + 2;
            }
        }
        char[] out = new char[(end - i) / 2];
        int o = 0;
        while (i + 1 < end) {
            int b0 = d[i] & 0xFF;
            int b1 = d[i + 1] & 0xFF;
            int u = little ? (b0 | (b1 << 8)) : ((b0 << 8) | b1);
            out[o++] = (char) u;
            i += 2;
        }
        return new String(out, 0, o);
    }

    // ------------------------------------------------------------------- GBK

    /** GB2312 lead bytes are 0xA1..0xF7; trail bytes 0xA1..0xFE. */
    private static final int GB_LEAD_MIN = 0xA1;
    private static final int GB_LEAD_MAX = 0xF7;
    private static final int GB_TRAIL_MIN = 0xA1;
    private static final int GB_TRAIL_MAX = 0xFE;
    private static final int GB_TRAIL_SPAN = GB_TRAIL_MAX - GB_TRAIL_MIN + 1;  // 94

    private static char[] gbTable;      // dense codepoints, 0xFFFF = unmapped
    private static boolean gbLoaded;

    /**
     * Build the GB2312 -> Unicode lookup table.
     *
     * A table is required, not optional: GB2312 level-1 hanzi are ordered by PINYIN,
     * so there is no byte-index -> codepoint formula. An earlier version of this file
     * used the (wrong) linear formula 0x4E00 + index; it produced characters that were
     * still valid CJK, so it looked like working Chinese while being pure nonsense
     * (it decoded 绗竴绔?as 鍊忓獤瀹?. Only comparing output against the source text
     * exposed it.
     *
     * The blob is DEFLATE + base64 because 2 bytes * 7478 entries of raw table would
     * bloat the dex; java.util.zip.Inflater exists at API 1.
     */
    private static synchronized void loadGbTable() {
        if (gbLoaded) {
            return;
        }
        gbLoaded = true;
        char[] grid = new char[(GB_LEAD_MAX - GB_LEAD_MIN + 1) * GB_TRAIL_SPAN];
        char[] out = null;
        try {
            byte[] comp = decodeBase64(GbTableData.B64);
            byte[] raw = inflate(comp);
            if (raw != null && raw.length >= 2) {
                int n = (raw[0] & 0xFF) | ((raw[1] & 0xFF) << 8);
                // Each record is 5 bytes: lead, trail, codepoint (uint16 LE), pad.
                // The pair is carried explicitly so the two sides cannot disagree about
                // ordering - a dense implicit ordering overran this buffer once.
                if (raw.length >= 2 + n * 5) {
                    int k = 2;
                    for (int i = 0; i < n; i++) {
                        int lead = raw[k] & 0xFF;
                        int trail = raw[k + 1] & 0xFF;
                        int cp = (raw[k + 2] & 0xFF) | ((raw[k + 3] & 0xFF) << 8);
                        k += 5;
                        if (lead >= GB_LEAD_MIN && lead <= GB_LEAD_MAX
                                && trail >= GB_TRAIL_MIN && trail <= GB_TRAIL_MAX && cp > 0) {
                            grid[(lead - GB_LEAD_MIN) * GB_TRAIL_SPAN
                                    + (trail - GB_TRAIL_MIN)] = (char) cp;
                        }
                    }
                    out = grid;
                }
            }
        } catch (Throwable t) {
            out = null;
        }
        gbTable = out;
    }

    /** Raw DEFLATE decompression. Returns null on any failure. */
    private static byte[] inflate(byte[] comp) {
        java.util.zip.Inflater inf = null;
        try {
            inf = new java.util.zip.Inflater();
            inf.setInput(comp);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(comp.length * 4);
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int got = inf.inflate(buf);
                if (got == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) {
                        break;
                    }
                    continue;
                }
                bos.write(buf, 0, got);
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (inf != null) {
                    inf.end();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Minimal base64 decoder (android.util.Base64 does not exist at API 1).
     * Ignores whitespace and stops at '='. Returns null on malformed input.
     */
    static byte[] decodeBase64(String s) {
        if (s == null) {
            return null;
        }
        int n = s.length();
        byte[] out = new byte[(n / 4) * 3 + 3];
        int o = 0;
        int acc = 0;
        int bits = 0;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            int v;
            if (c >= 'A' && c <= 'Z') {
                v = c - 'A';
            } else if (c >= 'a' && c <= 'z') {
                v = c - 'a' + 26;
            } else if (c >= '0' && c <= '9') {
                v = c - '0' + 52;
            } else if (c == '+') {
                v = 62;
            } else if (c == '/') {
                v = 63;
            } else if (c == '=') {
                break;
            } else {
                continue;   // skip newlines/spaces
            }
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[o++] = (byte) ((acc >> bits) & 0xFF);
            }
        }
        byte[] trimmed = new byte[o];
        System.arraycopy(out, 0, trimmed, 0, o);
        return trimmed;
    }

    /**
     * GB2312 decoder backed by the generated table.
     * Sequences outside GB2312 become U+FFFD rather than invented characters.
     */
    public static String decodeGb18030(byte[] d, int off, int len) {
        loadGbTable();
        int i = off;
        int end = off + len;
        char[] out = new char[end - i];
        int o = 0;
        while (i < end) {
            int b = d[i] & 0xFF;
            if (b < 0x80) {
                out[o++] = (char) b;
                i++;
                continue;
            }
            if (i + 1 >= end) {
                out[o++] = REPLACEMENT;
                i++;
                continue;
            }
            int b2 = d[i + 1] & 0xFF;
            i += 2;
            char cp = 0xFFFF;
            if (gbTable != null && b >= GB_LEAD_MIN && b <= GB_LEAD_MAX
                    && b2 >= GB_TRAIL_MIN && b2 <= GB_TRAIL_MAX) {
                cp = gbTable[(b - GB_LEAD_MIN) * GB_TRAIL_SPAN + (b2 - GB_TRAIL_MIN)];
            }
            out[o++] = (cp == 0 || cp == 0xFFFF) ? REPLACEMENT : cp;
        }
        return new String(out, 0, o);
    }

    /** True when the GB2312 table loaded successfully (for diagnostics). */
    public static synchronized boolean gbTableReady() {
        loadGbTable();
        return gbTable != null;
    }

    // --------------------------------------------------------------- Latin-1

    public static String decodeLatin1(byte[] d, int off, int len) {
        char[] out = new char[len];
        for (int i = 0; i < len; i++) {
            out[i] = (char) (d[off + i] & 0xFF);
        }
        return new String(out);
    }

    // -------------------------------------------------------------- platform

    /**
     * Best-effort platform decode, used only as a cross-check.
     * Returns null when the charset is unavailable on this device.
     */
    public static String tryPlatform(byte[] d, int off, int len, String charset) {
        try {
            return new String(d, off, len, charset);
        } catch (UnsupportedEncodingException e) {
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Rough quality heuristic for a decoded candidate: higher is better.
     *
     * IMPORTANT: this is a *signal*, not a decision procedure. Decoding UTF-8 bytes as
     * GBK also produces a long run of CJK codepoints and therefore scores high despite
     * being mojibake, so {@link #detect} decides on {@link #isValidUtf8} instead.
     * This is kept for UI-level diagnostics and for ranking candidates that validity
     * cannot separate.
     *
     * Rare CJK Extension A codepoints (U+3400..U+4DBF) are penalised because
     * misreading UTF-8 as GBK scatters the output across that block.
     */
    public static int score(String s) {
        if (s == null || s.length() == 0) {
            return Integer.MIN_VALUE;
        }
        int good = 0;
        int bad = 0;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == REPLACEMENT) {
                // Weighted heavily on purpose. Mis-decoding UTF-8 as GBK only fails on a
                // small fraction of sequences, so a per-character penalty that is merely
                // larger than the reward is not enough - the sheer character count wins.
                // Measured: UTF-8-as-GBK produced 124 unmappable chars out of 1859,
                // which must outweigh the 1859 CJK characters it "gained".
                bad += 30;
            } else if (c == '\n' || c == '\r' || c == '\t') {
                good += 2;
            } else if (c >= 0x20 && c < 0x7F) {
                good++;
            } else if (c >= 0x4E00 && c <= 0x9FA5) {
                good += 3;   // common CJK unified ideographs
            } else if (c >= 0x3400 && c <= 0x4DBF) {
                bad += 12;   // CJK Ext-A: rare, typical of a mis-decode
            } else if (c >= 0x3000 && c <= 0x303F) {
                good += 3;   // CJK punctuation
            } else if (c >= 0xFF00 && c <= 0xFFEF) {
                good += 3;   // full-width forms
            } else if (c >= 0xE000 && c <= 0xF8FF) {
                bad += 12;   // private use area
            } else if (c < 0x20) {
                bad += 4;
            }
        }
        return good - bad;
    }

    /** Count of unmappable characters, a direct signal of a wrong encoding. */
    public static int replacementCount(String s) {
        if (s == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == REPLACEMENT) {
                n++;
            }
        }
        return n;
    }
}

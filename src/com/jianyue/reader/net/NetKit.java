package com.jianyue.reader.net;

import android.util.Log;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * TLS setup for Android 1.0.
 *
 * Measured on a real API-1 device:
 *   TLS.protocols = SSLv3 TLSv1
 *   https://www.baidu.com/  -> "Hostname <www.baidu.com> was not verified"
 *   https://www.qidian.com/ -> "Not trusted server certificate"
 *
 * So on API 1 two separate things break HTTPS by default:
 *   1. hostname verification, because the old stack is strict about CN/SAN,
 *   2. the trust store, which is the 2008 root set and cannot chain modern certs.
 *
 * Both are worked around here. This is what every Android app of that era did, and it
 * is the only way to reach HTTPS at all on API 1. Note this does NOT defeat the harder
 * limit: the protocol ceiling is TLS 1.0, so sites requiring TLS 1.2+ still fail at the
 * handshake no matter what trust policy is installed. Http.getWithHttpFallback() exists
 * for that case.
 *
 * The verification bypass is logged once, loudly, so it is never invisible.
 */
public final class NetKit {

    private static final String TAG = "JianYue";

    private static boolean configured;
    private static String protocolInfo = "unknown";

    private NetKit() {
    }

    /** Idempotent; safe to call before every request. */
    public static synchronized void ensureTlsConfigured() {
        if (configured) {
            return;
        }
        configured = true;
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                public boolean verify(String host, SSLSession session) {
                    return true;
                }
            });
            Log.i(TAG, "tls: installed permissive trust manager + hostname verifier "
                    + "(API 1 cannot chain modern certificates; this is the only way to "
                    + "reach HTTPS on this platform)");
        } catch (Throwable t) {
            Log.i(TAG, "tls: setup FAILED " + t.getClass().getName() + ": " + t.getMessage());
        }
        protocolInfo = queryProtocols();
        Log.i(TAG, "tls: supported protocols = " + protocolInfo);
    }

    /** Which handshake protocols this device's SSL stack can offer. */
    public static String queryProtocols() {
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, null, null);
            java.net.Socket s = sc.getSocketFactory().createSocket();
            String out = "?";
            if (s instanceof javax.net.ssl.SSLSocket) {
                String[] protos = ((javax.net.ssl.SSLSocket) s).getSupportedProtocols();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < protos.length; i++) {
                    if (i > 0) {
                        sb.append(' ');
                    }
                    sb.append(protos[i]);
                }
                out = sb.toString();
            }
            try {
                s.close();
            } catch (Throwable ignored) {
            }
            return out;
        } catch (Throwable t) {
            return "err:" + t.getClass().getName();
        }
    }

    public static String protocols() {
        ensureTlsConfigured();
        return protocolInfo;
    }

    /**
     * True when the device can only speak TLS 1.0 or older, i.e. a large fraction of
     * modern HTTPS sites are unreachable. Used to warn the user instead of silently
     * failing every search.
     */
    public static boolean isTls10Ceiling() {
        String p = protocols();
        return p.indexOf("TLSv1.1") < 0 && p.indexOf("TLSv1.2") < 0 && p.indexOf("TLSv1.3") < 0;
    }
}

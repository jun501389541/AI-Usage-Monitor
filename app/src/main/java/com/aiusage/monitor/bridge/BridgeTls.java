package com.aiusage.monitor.bridge;

import java.io.IOException;
import java.net.URL;
import java.security.SecureRandom;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

/**
 * The one place a pairing connection is assembled, so that "installed the trust
 * manager but forgot the verifier" is not a state the code can be in. Phase 7 A8/A10.
 *
 * <p>Both halves are needed and neither is enough alone: the trust manager decides
 * whether the certificate's key is the paired one, and the hostname verifier is what
 * {@code HttpsURLConnection} asks *after* that, on the URL's host — which the Bridge's
 * certificate cannot satisfy for a LAN address (docs/PHASE-7-REVIEW.md P1). Getting
 * them from one function means a caller cannot take one without the other.
 */
public final class BridgeTls {

    /** How long to wait for a computer that is powered off or asleep. */
    public static final int CONNECT_TIMEOUT_MS = 4000;
    public static final int READ_TIMEOUT_MS = 10000;

    private BridgeTls() {
    }

    /**
     * Opens a pinned connection to {@code baseUrl} + {@code path}.
     *
     * @param pin the digest recorded at pairing time; never null, because a
     *            connection without a pin is what pairing exists to avoid
     */
    public static HttpsURLConnection open(String baseUrl, String path, FingerprintPin pin)
            throws IOException {
        return openUrl(baseUrl + path, pin);
    }

    /**
     * The one assembly point, for callers that already hold a complete URL — which is
     * every *read* through a paired computer, since the path belongs to the provider
     * being read rather than to the pairing protocol.
     *
     * <p>Both halves go on here and nowhere else. Installing the trust manager without
     * the verifier is the half-config Phase 7 review P1 was about: the certificate
     * compares correctly and the connection still dies on a name the Bridge cannot
     * have in its SANs.
     */
    public static HttpsURLConnection openUrl(String url, FingerprintPin pin) throws IOException {
        if (pin == null) {
            throw new IllegalArgumentException("配对连接必须带指纹");
        }
        HttpsURLConnection connection = open_(url);
        SSLContext context = contextFor(pin);
        connection.setSSLSocketFactory(context.getSocketFactory());
        connection.setHostnameVerifier(new PinnedHostnameVerifier(pin));
        return connection;
    }

    /**
     * An *unverified* connection, for the one thing that cannot be verified yet:
     * reading {@code /v1/health} from an address the user typed, to learn which
     * certificate lives there (docs/PHASE-7-PLAN.md A11).
     *
     * <p>Two limits keep it narrow, and both are structural rather than advisory:
     * the method takes no headers and no body, so no credential can travel over it,
     * and its result is only ever a fingerprint to show a human. Everything that
     * actually sends the pairing code goes through {@link #open}.
     */
    public static HttpsURLConnection openProbe(String baseUrl, String path) throws IOException {
        HttpsURLConnection connection = open_(baseUrl + path);
        SSLContext context;
        try {
            context = SSLContext.getInstance("TLS");
            context.init(null, new javax.net.ssl.TrustManager[]{new UnverifiedProbeTrustManager()},
                    new SecureRandom());
        } catch (java.security.GeneralSecurityException exception) {
            throw new IOException("本机不支持 TLS 探测：" + exception.getMessage(), exception);
        }
        connection.setSSLSocketFactory(context.getSocketFactory());
        // A named class, not an anonymous one: PairingWiringTest asserts which verifier
        // each call site installs, and "new HostnameVerifier() { ... }" would let a
        // third accept-any policy appear without being noticed.
        connection.setHostnameVerifier(new ProbeHostnameVerifier());
        return connection;
    }

    /** The digest the certificate on a probe connection presented. */
    public static String fingerprintOf(HttpsURLConnection connection) throws IOException {
        try {
            java.security.cert.Certificate peer = connection.getServerCertificates()[0];
            return FingerprintPin.hexOf(peer);
        } catch (javax.net.ssl.SSLPeerUnverifiedException exception) {
            throw new IOException("电脑端没有出示证书", exception);
        }
    }

    private static HttpsURLConnection open_(String url) throws IOException {
        if (url == null || !url.toLowerCase(java.util.Locale.US).startsWith("https://")) {
            // Not a preference: the pairing secret and the device token that follows
            // it are worthless over plaintext, and the Bridge itself refuses to serve
            // pairing without TLS.
            throw new IOException("配对必须走 HTTPS：" + url);
        }
        URL target = new URL(url);
        java.net.URLConnection raw = target.openConnection();
        if (!(raw instanceof HttpsURLConnection)) {
            throw new IOException("这个地址不是 HTTPS 服务：" + url);
        }
        HttpsURLConnection connection = (HttpsURLConnection) raw;
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json");
        return connection;
    }

    private static SSLContext contextFor(FingerprintPin pin) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new javax.net.ssl.TrustManager[]{new PinnedTrustManager(pin)},
                    new SecureRandom());
            return context;
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("本机不支持配对所需的 TLS：" + exception.getMessage(),
                    exception);
        }
    }

    /**
     * Accepts any chain, for {@link #openProbe} only. Named for what it is so that a
     * search for a permissive trust manager finds this and nothing else.
     */
    private static final class UnverifiedProbeTrustManager
            implements javax.net.ssl.X509TrustManager {
        @Override
        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
        }

        @Override
        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return new java.security.cert.X509Certificate[0];
        }
    }

    /**
     * Accepts any name, for {@link #openProbe} only, and paired with
     * {@link UnverifiedProbeTrustManager} rather than on its own: the probe asks an
     * address it has no digest for, so there is nothing either half could compare.
     *
     * <p>It is an inner class of the one file that builds connections, never a global
     * default, and {@code PairingWiringTest} pins that the only place installing it is
     * {@code openProbe} — an accept-any verifier reaching a credential-bearing
     * connection is how pinning dies without any line of code looking wrong.
     */
    private static final class ProbeHostnameVerifier implements HostnameVerifier {
        @Override
        public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
            return true;
        }
    }
}

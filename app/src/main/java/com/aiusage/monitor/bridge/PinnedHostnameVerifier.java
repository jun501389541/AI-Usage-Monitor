package com.aiusage.monitor.bridge;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/**
 * The hostname question, answered by the digest instead of the name. Phase 7 review
 * P1 and docs/PHASE-7-PLAN.md A10 — read that decision before changing this class.
 *
 * <p>Why the default verifier cannot be used: the Bridge certificate carries SANs for
 * {@code localhost}, {@code 127.0.0.1} and {@code ::1} and nothing else, because a
 * user's LAN address is unknowable at key-generation time and a certificate that
 * omitted the address the client dialled would fail everywhere but one machine. So
 * {@code HttpsURLConnection}'s default verifier — which runs *after* the trust
 * manager, on the URL's host — rejects {@code https://192.168.1.20} even when the
 * certificate is exactly the one that was paired. Pinning alone is therefore not
 * enough on Android; this is the other half.
 *
 * <p>Why per-connection and never global: {@code
 * HttpsURLConnection.setDefaultHostnameVerifier} is process-wide. Setting it to
 * satisfy pairing would also switch off certificate name checking for the DeepSeek
 * API and every other HTTPS request the app makes, which is the whole of TLS's
 * authentication in one line of convenience. So each pairing connection calls
 * {@code setHostnameVerifier(new PinnedHostnameVerifier(pin))} and nothing else in
 * the process is affected. {@code PairingWiringTest} pins that no call to the global
 * setter exists.
 *
 * <p>What "the name does not matter" does not mean: the host is still ignored, but the
 * key is not. A different certificate on the same address fails here, and a
 * certificate whose key matches the pin is the machine that holds the private key the
 * pairing was made with — which is the identity Spec §22 asks for.
 */
public final class PinnedHostnameVerifier implements HostnameVerifier {

    private final FingerprintPin pin;

    public PinnedHostnameVerifier(FingerprintPin pin) {
        if (pin == null) {
            throw new IllegalArgumentException("没有指纹就不能放行主机名");
        }
        this.pin = pin;
    }

    @Override
    public boolean verify(String hostname, SSLSession session) {
        // Deliberately: the hostname is never consulted. Anything that starts reading
        // `hostname` here has re-opened the question A10 closed.
        Certificate peer;
        try {
            Certificate[] chain = session.getPeerCertificates();
            if (chain == null || chain.length == 0) {
                return false;
            }
            peer = chain[0];
        } catch (SSLPeerUnverifiedException exception) {
            return false;
        }
        if (!(peer instanceof X509Certificate)) {
            return false;
        }
        return pin.matches(peer);
    }
}

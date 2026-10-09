package com.aiusage.monitor.bridge;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.X509TrustManager;

/**
 * Accepts a certificate for one reason only: its public key hashes to the digest
 * this phone was paired with. Spec §21, docs/PHASE-7-PLAN.md A8.
 *
 * <p>The Bridge certificate is self-signed and its subject is a random id, so the
 * system's chain-of-trust rules have nothing to say about it — and adding the Bridge
 * to the device's trust store would make *every* app on the phone trust it, which is
 * a larger blast radius than the feature deserves. Pinning the key is the narrow
 * version of the same question: not "is this a certificate authority's fault" but
 * "is this the machine I paired with".
 *
 * <p>Client authentication is refused rather than implemented: pairing authorises
 * with a device token, and a server that asked for a client certificate would be
 * asking this phone for something it never stores.
 */
public final class PinnedTrustManager implements X509TrustManager {

    public static final class PinMismatchException extends CertificateException {
        public PinMismatchException(String message) { super(message); }
    }

    private final FingerprintPin pin;

    public PinnedTrustManager(FingerprintPin pin) {
        if (pin == null) {
            throw new IllegalArgumentException("没有指纹就不能建立配对连接");
        }
        this.pin = pin;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        throw new CertificateException("配对不使用客户端证书");
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        if (chain == null || chain.length == 0 || chain[0] == null) {
            throw new CertificateException("电脑端没有出示证书");
        }
        if (!pin.matches(chain[0])) {
            // Keep this distinct from a network failure without exposing certificate data.
            throw new PinMismatchException("证书指纹与配对时记录的不一致，请核对电脑并重新配对。");
        }
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }
}

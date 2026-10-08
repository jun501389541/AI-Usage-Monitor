package com.aiusage.monitor.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLSession;

/**
 * The two halves of "is this the computer I paired with", against certificates the
 * Go Bridge actually issued.
 *
 * <p>Phase 7 review P1 is the reason both halves are here: the trust manager decides
 * the key, and {@code HttpsURLConnection} then asks a hostname verifier about the URL
 * — and the Bridge certificate has no LAN address in its SANs, so a pinned connection
 * to {@code https://192.168.1.20} fails at the second question even when the first is
 * answered correctly.
 */
public class TlsPinningTest {

    private static X509Certificate certificate(String pem) throws Exception {
        byte[] der = java.util.Base64.getMimeDecoder().decode(
                pem.replaceAll("-----[A-Z ]+-----", ""));
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    @Test
    public void theDigestAndroidComputesIsTheOneGoAdvertised() throws Exception {
        X509Certificate alpha = certificate(GoVectors.ALPHA_CERT_PEM);

        assertEquals("SHA-256 over the certificate's SubjectPublicKeyInfo, in both "
                        + "languages, from the same bytes",
                GoVectors.ALPHA_FINGERPRINT, FingerprintPin.hexOf(alpha));
    }

    @Test
    public void aPinRefusesACertificateFromADifferentBridge() throws Exception {
        FingerprintPin alpha = new FingerprintPin(GoVectors.ALPHA_FINGERPRINT);
        assertTrue(alpha.matches(certificate(GoVectors.ALPHA_CERT_PEM)));
        assertFalse("a second Bridge's certificate must not satisfy the first pin",
                alpha.matches(certificate(GoVectors.BETA_CERT_PEM)));
    }

    @Test
    public void certificatePinsAreExplicitAndRefuseOtherCertificates() throws Exception {
        X509Certificate alpha = certificate(GoVectors.ALPHA_CERT_PEM);
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(alpha.getEncoded());
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format(java.util.Locale.US, "%02x", b & 255));
        FingerprintPin pin = new FingerprintPin("cert-sha256:" + hex);
        assertTrue(pin.matches(alpha));
        assertFalse(pin.matches(certificate(GoVectors.BETA_CERT_PEM)));
        assertFalse(new FingerprintPin(hex.toString()).matches(alpha));
        assertEquals(hex.substring(0, 8).toUpperCase(java.util.Locale.US), pin.tail());
    }

    @Test
    public void aPinThatIsNotADigestIsRefusedAtConstruction() {
        for (String bad : new String[]{null, "", "abc", "z".repeat(64), "0".repeat(63)}) {
            try {
                new FingerprintPin(bad);
                fail("accepted the fingerprint " + bad);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("SHA-256"));
            }
        }
    }

    @Test
    public void theTrustManagerAcceptsOnlyThePairedKey() throws Exception {
        X509Certificate alpha = certificate(GoVectors.ALPHA_CERT_PEM);
        X509Certificate beta = certificate(GoVectors.BETA_CERT_PEM);
        PinnedTrustManager manager = new PinnedTrustManager(new FingerprintPin(
                GoVectors.ALPHA_FINGERPRINT));

        manager.checkServerTrusted(new X509Certificate[]{alpha}, "RSA");

        try {
            manager.checkServerTrusted(new X509Certificate[]{beta}, "RSA");
            fail("a different Bridge's certificate was trusted");
        } catch (java.security.cert.CertificateException expected) {
            assertTrue("the message names the digests, not the secrets: "
                            + expected.getMessage(),
                    expected.getMessage().contains("不一致"));
            assertFalse("no token or key material in a message that reaches logs",
                    expected.getMessage().contains(GoVectors.PAIR_TOKEN));
        }
        try {
            manager.checkServerTrusted(new X509Certificate[0], "RSA");
            fail("an empty chain was trusted");
        } catch (java.security.cert.CertificateException expected) {
            assertTrue(expected.getMessage().contains("没有出示证书"));
        }
        try {
            manager.checkClientTrusted(new X509Certificate[]{alpha}, "RSA");
            fail("client certificates are not part of pairing");
        } catch (java.security.cert.CertificateException expected) {
            assertTrue(expected.getMessage().contains("客户端证书"));
        }
        assertEquals("no trust anchors are offered", 0, manager.getAcceptedIssuers().length);
    }

    /**
     * The verifier answers on the key, whatever name was dialled — which is what makes
     * a LAN address connectable at all — and refuses on a key that is not the paired
     * one even when the name looks right.
     */
    @Test
    public void theVerifierIgnoresTheNameAndJudgesTheKey() throws Exception {
        X509Certificate alpha = certificate(GoVectors.ALPHA_CERT_PEM);
        X509Certificate beta = certificate(GoVectors.BETA_CERT_PEM);
        PinnedHostnameVerifier verifier =
                new PinnedHostnameVerifier(new FingerprintPin(GoVectors.ALPHA_FINGERPRINT));

        assertTrue("https://192.168.1.20 is the address the certificate cannot name",
                verifier.verify("192.168.1.20", sessionWith(alpha)));
        assertTrue(verifier.verify("localhost", sessionWith(alpha)));
        assertFalse("a name that matches nothing must not rescue a wrong key",
                verifier.verify("localhost", sessionWith(beta)));
        assertFalse("an unverified peer is a refusal, not a retry",
                verifier.verify("192.168.1.20", sessionWith(null)));
    }

    @Test
    public void aMissingPinCannotBeSubstitutedForAnEmptyOne() {
        try {
            new PinnedTrustManager(null);
            fail("a trust manager without a pin accepts every certificate");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("指纹"));
        }
        try {
            new PinnedHostnameVerifier(null);
            fail("a verifier without a pin accepts every name");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("指纹"));
        }
    }

    /** A session that reports the given peer certificate, or none at all. */
    private static SSLSession sessionWith(final Certificate peer) {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if ("getPeerCertificates".equals(method.getName())) {
                    if (peer == null) {
                        // Exactly what a real session does when nothing was verified:
                        // the checked exception the method declares, not a wrapper, so
                        // that this test exercises the catch clause and not its own
                        // proxy plumbing.
                        throw new javax.net.ssl.SSLPeerUnverifiedException("no peer");
                    }
                    return new Certificate[]{peer};
                }
                Class<?> type = method.getReturnType();
                if (type.equals(boolean.class)) {
                    return false;
                }
                if (type.equals(int.class)) {
                    return 0;
                }
                if (type.equals(long.class)) {
                    return 0L;
                }
                if (byte[].class.equals(type)) {
                    return new byte[0];
                }
                return null;
            }
        };
        return (SSLSession) Proxy.newProxyInstance(SSLSession.class.getClassLoader(),
                new Class<?>[]{SSLSession.class}, handler);
    }

    @Test
    public void theProbeTrustManagerIsNotReachableFromThePinnedPath() throws Exception {
        // BridgeTls.open is the only way to get a pinned connection, and it refuses a
        // null pin rather than defaulting to the probe's permissive manager.
        //
        // The expected message is BridgeTls's own, not merely "some complaint about a
        // fingerprint": PinnedTrustManager refuses a null pin too, so an assertion that
        // accepted either message would stay green with the first guard deleted and
        // would only be proving the second one exists.
        try {
            BridgeTls.open("https://192.168.1.20:38411", "/v1/pair", null);
            fail("a pinned connection was opened without a fingerprint");
        } catch (IllegalArgumentException expected) {
            assertTrue("expected the refusal from the connection builder, got: "
                            + expected.getMessage(),
                    expected.getMessage().contains("配对连接必须带指纹"));
        }
        try {
            BridgeTls.open("http://192.168.1.20:38411", "/v1/pair",
                    new FingerprintPin(GoVectors.ALPHA_FINGERPRINT));
            fail("pairing over plaintext was allowed");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("配对必须走 HTTPS"));
        }
    }
}

package com.aiusage.monitor.util;

/**
 * Makes a transport that will only talk to the machine holding one certificate.
 *
 * <p>The interface lives beside {@link BridgeTransport} rather than with the pairing
 * code on purpose: a provider reads through a computer it paired with, and the
 * dependency should run provider → here ← pairing, not provider → pairing. The
 * implementation is in {@code bridge/}, which is where the digest, the trust manager
 * and the per-connection hostname verifier are assembled ({@code BridgeTls}).
 *
 * <p>Why this is a source of transports rather than one transport with a pin
 * parameter: the pin belongs to a computer, and a process reads from several. A
 * single transport holding "the" pin would be a per-account setting smuggled into a
 * provider that is registered once.
 */
public interface PinnedTransportSource {

    /**
     * @param fingerprintHex hex SHA-256 of the certificate's SubjectPublicKeyInfo,
     *                       as the pairing recorded it; never empty
     */
    BridgeTransport forPin(String fingerprintHex);
}

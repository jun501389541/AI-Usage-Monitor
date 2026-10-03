package com.aiusage.monitor.bridge;

/**
 * Where a pairing offer came from. Spec §20 asks for a QR code; the protocol asks
 * only for the string it carries, so the channels below are interchangeable and a
 * camera becomes one more implementation rather than a redesign
 * (docs/PHASE-7-PLAN.md A7, and D1 for why the camera is not in this phase).
 *
 * <p>Each source produces one of two things, never a mixture: a complete
 * {@link PairingPayload} — address list, one-time token and the certificate digest,
 * which is everything needed to connect pinned; or a {@link ManualTarget} — an
 * address and a typed code with no digest, which is the trust-on-first-use channel
 * and carries the human confirmation step that goes with it (A11).
 */
public interface PairingPayloadSource {

    /** What the pairing screen calls this channel, e.g. 「粘贴配对内容」. */
    String label();

    /**
     * Reads the offer.
     *
     * @return the offer, or null when the user backed out — a cancelled read is not
     *         an error, and inventing one would make the screen apologise for
     *         something that went fine
     * @throws PairingPayload.Invalid when what arrived cannot be read as an offer
     */
    PairingOffer read() throws PairingPayload.Invalid;
}

package com.aiusage.monitor.bridge;

/**
 * What a {@link PairingPayloadSource} produced: either a complete offer, or an
 * address plus a typed code with no digest attached.
 *
 * <p>Two variants in one type rather than {@code Object} because the caller has to
 * branch on it, and a branch on a cast is how the two channels end up treated the
 * same — which they are not: the payload path can pin immediately, the manual path
 * cannot until a person has confirmed the fingerprint the computer printed.
 */
public final class PairingOffer {

    private final PairingPayload payload;
    private final ManualTarget manual;

    private PairingOffer(PairingPayload payload, ManualTarget manual) {
        this.payload = payload;
        this.manual = manual;
    }

    public static PairingOffer fromPayload(PairingPayload value) {
        return new PairingOffer(value, null);
    }

    public static PairingOffer fromManualTarget(ManualTarget value) {
        return new PairingOffer(null, value);
    }

    public boolean hasPayload() {
        return payload != null;
    }

    public boolean isManual() {
        return manual != null;
    }

    /** Null unless {@link #hasPayload()}. */
    public PairingPayload payload() {
        return payload;
    }

    /** Null unless {@link #isManual()}. */
    public ManualTarget manual() {
        return manual;
    }

    /** The Bridge's own id, whichever channel brought it. */
    public String bridgeId() {
        return payload != null ? payload.getBridgeId() : "";
    }
}

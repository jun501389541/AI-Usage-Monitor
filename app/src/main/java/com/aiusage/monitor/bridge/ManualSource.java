package com.aiusage.monitor.bridge;

/**
 * The channel where a person types the computer's address and the short code the
 * terminal printed. Spec §21 lists it as the fallback when no camera or clipboard is
 * available, and Phase 7's D4 keeps it because it is the only route that works on a
 * real phone today.
 *
 * <p>It yields a {@link ManualTarget}, not a payload: nothing in what the user typed
 * says what certificate to expect. The client therefore probes first, shows the
 * fingerprint tail, and refuses to send the code until that tail has been confirmed
 * against the computer's own screen (docs/PHASE-7-PLAN.md A11).
 */
public final class ManualSource implements PairingPayloadSource {

    private final String host;
    private final int port;
    private final String code;

    public ManualSource(String host, int port, String code) {
        this.host = host;
        this.port = port;
        this.code = code;
    }

    @Override
    public String label() {
        return "手输地址与配对码";
    }

    @Override
    public PairingOffer read() throws PairingPayload.Invalid {
        return PairingOffer.fromManualTarget(new ManualTarget(host, port, code));
    }
}

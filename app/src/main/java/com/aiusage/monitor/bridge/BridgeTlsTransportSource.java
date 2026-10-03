package com.aiusage.monitor.bridge;

import com.aiusage.monitor.util.BridgeTransport;
import com.aiusage.monitor.util.PinnedTransportSource;

/**
 * The {@code bridge/} half of {@link PinnedTransportSource}: hands a provider the
 * transport for one digest, and knows nothing about which account is asking.
 *
 * <p>Kept stateless on purpose. A transport holds a pin, and pins belong to
 * computers, so caching them here would recreate the mistake of a process-wide
 * "current" fingerprint — which is how two paired computers end up reading as one.
 */
public final class BridgeTlsTransportSource implements PinnedTransportSource {

    @Override
    public BridgeTransport forPin(String fingerprintHex) {
        // FingerprintPin refuses anything that is not a 64-hex digest, so a corrupt
        // row cannot produce a transport that accepts "whatever answers".
        return new PinnedBridgeTransport(new FingerprintPin(fingerprintHex));
    }
}

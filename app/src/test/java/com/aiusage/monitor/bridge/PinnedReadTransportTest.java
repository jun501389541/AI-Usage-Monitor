package com.aiusage.monitor.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aiusage.monitor.util.BridgeTransport;

import org.junit.Test;

import java.io.IOException;

/**
 * The read-side half of pinning: the object that decides *which* transport a paired
 * account gets, and the refusal that stops it from quietly becoming a plaintext one.
 * Phase 7 step 9 (plan A5: everything after the pairing runs on the pinned TLS
 * channel).
 *
 * <p>{@code TlsPinningTest} already proves the pinning itself rejects a foreign
 * certificate. What is under test here is one level up: the factory must not hand out
 * a working transport for a digest that is not a digest, because the only transport
 * that would then be reachable is one that verifies nothing.
 */
public class PinnedReadTransportTest {

    private static final String DIGEST =
            "94cfeca524d68047296c70d220f62aa205184ff6beac1e8936e30caaab8b1be0";

    @Test
    public void aRealDigestBuysATransportThatCarriesExactlyThatDigest() {
        BridgeTransport transport = new BridgeTlsTransportSource().forPin(DIGEST);

        assertTrue("the read transport has to be the pinned one; anything else "
                        + "silently un-pins paired reads",
                transport instanceof PinnedBridgeTransport);
        assertEquals(DIGEST, ((PinnedBridgeTransport) transport).pinnedFingerprint());
    }

    @Test
    public void aCorruptDigestIsRefusedInsteadOfBecomingSomeOtherTransport() {
        for (String bad : new String[]{null, "", "   ", "not-a-digest", DIGEST.substring(1)}) {
            try {
                BridgeTransport transport = new BridgeTlsTransportSource().forPin(bad);
                fail("a transport was built for the digest " + bad + ": " + transport);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("指纹"));
            }
        }
    }

    @Test
    public void aPinnedTransportCannotBeBuiltWithoutSomethingToPin() {
        try {
            new PinnedBridgeTransport(null);
            fail("a pinned transport with no digest accepts every certificate");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("指纹"));
        }
    }

    @Test
    public void thePinnedTransportWillNotOpenAPlaintextUrl() {
        PinnedBridgeTransport transport =
                new PinnedBridgeTransport(new FingerprintPin(DIGEST));
        try {
            transport.get("http://10.0.2.2:38411/v1/accounts/codex/usage", null, 100, 100);
            fail("a pinned connection was opened to http://");
        } catch (IOException expected) {
            // BridgeTls refuses before any socket exists, which is the same rule the
            // pairing channel runs on (docs/PHASE-7-REVIEW.md P1, A10).
            assertTrue(expected.getMessage(), expected.getMessage().contains("HTTPS"));
        }
    }
}

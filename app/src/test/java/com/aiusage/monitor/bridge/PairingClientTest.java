package com.aiusage.monitor.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aiusage.monitor.util.Http;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What pairing does with each answer, and what it refuses to do at all.
 *
 * <p>The refusals matter more than the successes: a pairing screen that sends the
 * code before a human has confirmed the fingerprint has undone A11 no matter how
 * nicely it renders the digest, so those cases assert on the *request count* rather
 * than on a message.
 */
public class PairingClientTest {

    private static final String HOST_A = "192.168.1.20";
    private static final String HOST_B = "127.0.0.1";

    private FakeTransport transport;
    private PairingClient client;

    @Before
    public void setUp() {
        transport = new FakeTransport();
        client = client(AddressResolver.REAL_DEVICE);
    }

    private PairingClient client(AddressResolver addresses) {
        return new PairingClient(transport, new PairingClient.Clock() {
            @Override
            public long nowMs() {
                return 1_700_000_000_000L;
            }
        }, addresses);
    }

    private static PairingPayload offer() throws Exception {
        return PairingPayload.parse(GoVectors.ALPHA_PAYLOAD);
    }

    private static ManualTarget typed() throws Exception {
        return new ManualTarget(HOST_A, 38411, "7qaytwem");
    }

    // ------------------------------------------------------------ pinned path

    @Test
    public void aCompletePayloadPairsAgainstItsOwnAddressPinned() throws Exception {
        transport.answer = new Http.Response(200,
                "{\"deviceId\":\"dev_1\",\"deviceToken\":\"" + token() + "\","
                        + "\"bridgeId\":\"" + GoVectors.ALPHA_BRIDGE_ID + "\"}");

        PairingClient.Paired paired = client.pair(offer(), "Pixel");

        assertEquals("dev_1", paired.deviceId());
        assertEquals(token(), paired.deviceToken());
        assertEquals(GoVectors.ALPHA_BRIDGE_ID, paired.bridge().getId());
        assertEquals(GoVectors.ALPHA_FINGERPRINT, paired.bridge().getFingerprint());
        assertEquals("https://" + HOST_A + ":38411", paired.bridge().getBaseUrl());
        assertEquals(1_700_000_000_000L, paired.bridge().getAddedAt());
        assertEquals(1, transport.posts.size());
        assertEquals("the offer's first address", HOST_A + ":38411", transport.posts.get(0).host);
        assertEquals(GoVectors.ALPHA_FINGERPRINT, transport.posts.get(0).pin.value());
        assertTrue("the body carries the one-time secret and the name, nothing else",
                transport.posts.get(0).body.contains(GoVectors.PAIR_TOKEN)
                        && transport.posts.get(0).body.contains("Pixel"));
    }

    @Test
    public void anUnreachableAddressFallsToTheNextOneTheOfferNamed() throws Exception {
        transport.ioFailsForHosts.add(HOST_A);
        transport.answer = new Http.Response(200,
                "{\"deviceId\":\"dev_2\",\"deviceToken\":\"" + token() + "\"}");

        PairingClient.Paired paired = client.pair(offer(), "Pixel");

        assertEquals("dev_2", paired.deviceId());
        assertEquals("both addresses were tried, in the offer's order",
                Arrays.asList(HOST_A + ":38411", HOST_B + ":38411"), hostsPosted());
    }

    @Test
    public void everyAnswerThatIsNotAMatchOrAMissingHostSaysWhatTheScreenCanShow() throws Exception {
        transport.answer = new Http.Response(401, "{\"error\":\"CODEX_AUTH_REQUIRED\"}");
        assertFailure(PairingClient.Failure.CODE_REFUSED);

        transport.answer = new Http.Response(503, "{\"error\":\"the pairing could not be stored\"}");
        assertFailure(PairingClient.Failure.BRIDGE_BUSY);

        transport.answer = new Http.Response(200, "{\"deviceId\":\"dev_x\"}");
        assertFailure(PairingClient.Failure.BAD_RESPONSE);

        transport.answer = new Http.Response(200, "not json");
        assertFailure(PairingClient.Failure.BAD_RESPONSE);

        transport.handshakeFails = true;
        assertFailure(PairingClient.Failure.FINGERPRINT_MISMATCH);
    }

    /**
     * The server's deliberate vagueness is the client's too: which of "unknown",
     * "expired" or "used" it was is exactly what a guessing attack learns, and the
     * message must not invent one.
     */
    @Test
    public void aRefusalNeverGuessesWhichKindItWas() throws Exception {
        transport.answer = new Http.Response(401, "{\"error\":\"CODEX_AUTH_REQUIRED\"}");
        try {
            client.pair(offer(), "Pixel");
            fail("a 401 paired");
        } catch (PairingClient.PairingFailed expected) {
            assertEquals(PairingClient.Failure.CODE_REFUSED, expected.failure());
            assertTrue(expected.getMessage().contains("重新"));
            assertFalse(expected.getMessage().contains("已使用"));
            assertFalse(expected.getMessage().contains("过期，你输入的码"));
        }
    }

    // ------------------------------------------------------- manual (A11) path

    @Test
    public void aProbeReadsTheIdentityWithoutSendingAnyCredential() throws Exception {
        transport.health = "{\"ok\":true,\"bridgeId\":\"" + GoVectors.ALPHA_BRIDGE_ID
                + "\",\"fingerprint\":\"" + GoVectors.ALPHA_FINGERPRINT + "\",\"tls\":true}";

        PairingClient.Discovered discovered = client.discover(typed());

        assertEquals(GoVectors.ALPHA_BRIDGE_ID, discovered.bridgeId());
        assertEquals(GoVectors.ALPHA_FINGERPRINT, discovered.fingerprint());
        assertEquals(GoVectors.ALPHA_FINGERPRINT.substring(0, 8).toUpperCase(java.util.Locale.US),
                discovered.tail());
        assertEquals("the probe must be the only thing that runs unpinned",
                1, transport.probes.size());
        assertEquals(0, transport.posts.size());
    }

    @Test
    public void anOldBridgeWithoutADigestIsNotPairable() throws Exception {
        transport.health = "{\"ok\":true,\"provider\":\"codex\"}";
        try {
            client.discover(typed());
            fail("a Bridge that advertises no fingerprint was treated as pairable");
        } catch (PairingClient.PairingFailed expected) {
            assertEquals(PairingClient.Failure.BAD_RESPONSE, expected.failure());
        }
    }

    @Test
    public void theTypedCodeStaysHomeUntilAHumanConfirmsTheDigest() throws Exception {
        transport.health = health();
        try {
            client.exchangeManual(typed(), client.discover(typed()), false);
            fail("the code was sent without a confirmation");
        } catch (PairingClient.PairingFailed expected) {
            assertEquals(PairingClient.Failure.NOT_CONFIRMED, expected.failure());
        }
        assertEquals("no request carried the typed code", 0, transport.posts.size());
    }

    @Test
    public void theCodeOnlyGoesToTheDigestThatWasShown() throws Exception {
        transport.health = health();
        PairingClient.Discovered shown = client.discover(typed());
        transport.answer = new Http.Response(200,
                "{\"deviceId\":\"dev_3\",\"deviceToken\":\"" + token() + "\"}");

        PairingClient.Paired paired = client.exchangeManual(typed(), shown, true);
        assertEquals("dev_3", paired.deviceId());
        assertEquals(GoVectors.ALPHA_FINGERPRINT, paired.bridge().getFingerprint());

        // The address starts answering as a different Bridge: the confirmation the
        // user made no longer describes this connection, so the code must not move.
        transport.health = "{\"ok\":true,\"bridgeId\":\"br_other\",\"fingerprint\":\""
                + GoVectors.BETA_FINGERPRINT + "\"}";
        try {
            client.exchangeManual(typed(), shown, true);
            fail("the code was sent to an address that now shows a different key");
        } catch (PairingClient.PairingFailed expected) {
            assertEquals(PairingClient.Failure.NOT_CONFIRMED, expected.failure());
        }
        assertEquals("one exchange went out in total, before the address changed",
                1, transport.posts.size());
    }

    /**
     * The manual channel's two calls have to be about the same machine. The probe is
     * what a human confirms, so an exchange that resolves its address differently would
     * send the confirmed code somewhere the confirmation never covered.
     */
    @Test
    public void theManualChannelProbesAndExchangesThroughTheSameAddress() throws Exception {
        transport.health = health();
        transport.answer = new Http.Response(200,
                "{\"deviceId\":\"dev_9\",\"deviceToken\":\"" + token() + "\"}");
        ManualTarget loopback = new ManualTarget(HOST_B, 38411, "7qaytwem");
        PairingClient onEmulator = client(AddressResolver.EMULATOR);

        PairingClient.Paired paired = onEmulator.exchangeManual(
                loopback, onEmulator.discover(loopback), true, "Pixel 7");

        assertEquals("dev_9", paired.deviceId());
        assertEquals("the confirmation and the code went to the rewritten address",
                Arrays.asList("https://" + AddressResolver.EMULATOR_GATEWAY + ":38411/v1/health",
                        "https://" + AddressResolver.EMULATOR_GATEWAY + ":38411/v1/health"),
                transport.probes);
        assertEquals(AddressResolver.EMULATOR_GATEWAY + ":38411",
                transport.posts.get(0).host);
        assertEquals("https://" + AddressResolver.EMULATOR_GATEWAY + ":38411",
                paired.bridge().getBaseUrl());
        assertTrue("the name the computer's device list will show",
                transport.posts.get(0).body.contains("Pixel 7"));
        assertFalse("the code is the only secret on the wire",
                transport.posts.get(0).body.contains("deviceToken"));
    }

    @Test
    public void noFailureMessageCarriesTheSecretItWasRejecting() throws Exception {
        transport.answer = new Http.Response(401, "{\"error\":\"CODEX_AUTH_REQUIRED\"}");
        try {
            client.pair(offer(), "Pixel");
            fail("expected a refusal");
        } catch (PairingClient.PairingFailed expected) {
            assertFalse("a pairing secret must not appear in a message that reaches "
                            + "logcat or a crash report",
                    expected.getMessage().contains(GoVectors.PAIR_TOKEN));
            assertFalse(expected.getMessage().contains(token()));
        }
    }

    /**
     * The emulator's one deviation, pinned on both sides of it. Phase 7 plan §3.2.
     *
     * <p>A Bridge started without {@code --host} offers {@code 127.0.0.1}, which on a
     * phone loops back to the phone. On the emulator the host machine's loopback is
     * {@code 10.0.2.2}, so only there is the address swapped — and the swap must not
     * reach the digest: an address that changes while the pin stays is exactly the
     * pair this phase exists to keep together.
     */
    @Test
    public void onlyTheEmulatorDialsTheLoopbackAddressSomewhereElse() throws Exception {
        transport.answer = new Http.Response(200,
                "{\"deviceId\":\"dev_2\",\"deviceToken\":\"" + token() + "\","
                        + "\"bridgeId\":\"" + GoVectors.ALPHA_BRIDGE_ID + "\"}");
        // The LAN address is the one that is not there, so the loopback entry decides.
        transport.ioFailsForHosts.add(HOST_A);

        PairingClient.Paired onPhone = client(AddressResolver.REAL_DEVICE)
                .pair(offer(), "Pixel");
        List<String> phoneHosts = hostsPosted();

        transport.posts.clear();
        PairingClient.Paired onEmulator = client(AddressResolver.EMULATOR)
                .pair(offer(), "Emulator");
        List<String> emulatorHosts = hostsPosted();

        assertEquals("a phone dials the address the Bridge named",
                Arrays.asList(HOST_A + ":38411", HOST_B + ":38411"), phoneHosts);
        assertEquals("the emulator dials the same machine through its gateway",
                Arrays.asList(HOST_A + ":38411", AddressResolver.EMULATOR_GATEWAY + ":38411"),
                emulatorHosts);
        assertEquals("the row keeps the address that answered, on each device",
                "https://" + HOST_B + ":38411", onPhone.bridge().getBaseUrl());
        assertEquals("https://" + AddressResolver.EMULATOR_GATEWAY + ":38411",
                onEmulator.bridge().getBaseUrl());
        assertEquals("and the pin is the offer's digest on both, unchanged by the address",
                GoVectors.ALPHA_FINGERPRINT, onPhone.bridge().getFingerprint());
        assertEquals(GoVectors.ALPHA_FINGERPRINT, onEmulator.bridge().getFingerprint());
        assertEquals("every connection that left the phone was pinned, including the "
                        + "rewritten one",
                GoVectors.ALPHA_FINGERPRINT, transport.posts.get(1).pin.value());
    }

    /**
     * Asserts the mapping from an answer to the reason the screen shows. Only the
     * trail of posts is reset here: the caller has just configured what the transport
     * should do, and clearing that would make the case assert about whatever the
     * previous line happened to leave set.
     */
    private void assertFailure(PairingClient.Failure expected) throws Exception {
        transport.posts.clear();
        try {
            client.pair(offer(), "Pixel");
            fail("expected " + expected);
        } catch (PairingClient.PairingFailed actual) {
            assertEquals(expected, actual.failure());
        }
    }

    private static String token() {
        StringBuilder text = new StringBuilder("dt");
        for (int i = 0; i < 62; i++) {
            text.append((char) ('a' + (i % 26)));
        }
        return text.toString();
    }

    private String health() {
        return "{\"ok\":true,\"bridgeId\":\"" + GoVectors.ALPHA_BRIDGE_ID
                + "\",\"fingerprint\":\"" + GoVectors.ALPHA_FINGERPRINT + "\"}";
    }

    private List<String> hostsPosted() {
        List<String> hosts = new ArrayList<>();
        for (Post post : transport.posts) {
            hosts.add(post.host);
        }
        return hosts;
    }

    private static final class Post {
        final String host;
        final String body;
        final FingerprintPin pin;

        Post(String host, String body, FingerprintPin pin) {
            this.host = host;
            this.body = body;
            this.pin = pin;
        }
    }

    private static final class FakeTransport implements PairingClient.Transport {
        final List<Post> posts = new ArrayList<>();
        final List<String> probes = new ArrayList<>();
        final List<String> ioFailsForHosts = new ArrayList<>();
        Http.Response answer = new Http.Response(200, "{}");
        String health = "{\"ok\":true}";
        boolean handshakeFails;

        @Override
        public String probe(String baseUrl, String path) throws IOException {
            probes.add(baseUrl + path);
            return health;
        }

        @Override
        public Http.Response post(String baseUrl, String path, String jsonBody, FingerprintPin pin)
                throws IOException {
            String host = baseUrl.replace("https://", "");
            // Recorded before the answer is decided: "which addresses did the client
            // try" has to include the one it could not reach, which is the whole
            // content of the fallback test.
            posts.add(new Post(host, jsonBody, pin));
            if (handshakeFails) {
                throw new javax.net.ssl.SSLHandshakeException("pinned certificate rejected");
            }
            // Compared without the port: the list says "this machine is asleep", and
            // the offer spells the same machine as 192.168.1.20:38411.
            for (String unreachable : ioFailsForHosts) {
                if (host.equals(unreachable) || host.startsWith(unreachable + ":")) {
                    throw new IOException("connect failed for " + host);
                }
            }
            return answer;
        }
    }
}

package com.aiusage.monitor.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

/**
 * The pairing offer, read the way the Bridge writes it.
 *
 * <p>The first test is the cross-language one: the string below came out of
 * {@code pairing.BuildPayload} in the Go module, so if either side changes the
 * format the other stops understanding it — which is precisely the failure a
 * per-language test suite cannot see.
 */
public class PairingPayloadTest {

    private static PairingPayload parse(String text) throws PairingPayload.Invalid {
        return PairingPayload.parse(text);
    }

    private static void assertRejected(String text, String whatShouldBeNamed) {
        try {
            parse(text);
            fail("accepted " + text + ", expected a refusal mentioning " + whatShouldBeNamed);
        } catch (PairingPayload.Invalid expected) {
            assertTrue("the refusal should say what is wrong ("
                            + whatShouldBeNamed + "): " + expected.getMessage(),
                    expected.getMessage().contains(whatShouldBeNamed));
        }
    }

    @Test
    public void readsWhatTheGoBridgeWrote() throws Exception {
        PairingPayload payload = parse(GoVectors.ALPHA_PAYLOAD);

        assertEquals(GoVectors.ALPHA_BRIDGE_ID, payload.getBridgeId());
        assertEquals(Arrays.asList("192.168.1.20", "127.0.0.1"), payload.getHosts());
        assertEquals(38411, payload.getPort());
        assertEquals(GoVectors.PAIR_TOKEN, payload.getPairToken());
        assertEquals(GoVectors.ALPHA_FINGERPRINT, payload.getFingerprint());
        assertEquals("https://192.168.1.20:38411", payload.baseUrlFor(0));
        assertEquals("https://127.0.0.1:38411", payload.baseUrlFor(1));
    }

    @Test
    public void refusesAnOfferFromAVersionItDoesNotKnow() throws Exception {
        JSONObject body = new JSONObject();
        body.put("v", 2);
        body.put("bridgeId", GoVectors.ALPHA_BRIDGE_ID);
        body.put("hosts", new JSONArray(Arrays.asList("192.168.1.20")));
        body.put("port", 38411);
        body.put("pairToken", GoVectors.PAIR_TOKEN);
        body.put("fingerprint", GoVectors.ALPHA_FINGERPRINT);

        assertRejected(PairingPayload.PREFIX + encode(body), "版本");
    }

    /**
     * The rule the plan made machine-checkable: an unknown field is a refusal, not
     * something to ignore. A payload from a newer Bridge may be carrying a field this
     * build would then silently drop while still calling the pairing a success.
     */
    @Test
    public void refusesUnknownFieldsIncludingAFieldThatShouldNeverAppear() throws Exception {
        for (String key : Arrays.asList("deviceToken", "apiKey", "openaiToken", "path")) {
            JSONObject body = new JSONObject()
                    .put("v", 1)
                    .put("bridgeId", GoVectors.ALPHA_BRIDGE_ID)
                    .put("hosts", new JSONArray(Arrays.asList("192.168.1.20")))
                    .put("port", 38411)
                    .put("pairToken", GoVectors.PAIR_TOKEN)
                    .put("fingerprint", GoVectors.ALPHA_FINGERPRINT)
                    .put(key, "sk-somethingLongEnoughToBeAKey0123456");
            assertRejected(PairingPayload.PREFIX + encode(body), key);
        }
    }

    @Test
    public void refusesWhenARequiredFieldIsMissing() throws Exception {
        for (String key : Arrays.asList("bridgeId", "hosts", "port", "pairToken", "fingerprint")) {
            JSONObject body = new JSONObject()
                    .put("v", 1)
                    .put("bridgeId", GoVectors.ALPHA_BRIDGE_ID)
                    .put("hosts", new JSONArray(Arrays.asList("192.168.1.20")))
                    .put("port", 38411)
                    .put("pairToken", GoVectors.PAIR_TOKEN)
                    .put("fingerprint", GoVectors.ALPHA_FINGERPRINT);
            body.remove(key);
            assertRejected(PairingPayload.PREFIX + encode(body), key);
        }
    }

    @Test
    public void refusesShapesThatAreNotAnOffer() throws Exception {
        assertRejected("", "没有配对内容");
        assertRejected("https://example.com", "aiusage://pair#");
        assertRejected("aiusage://pair#", "缺少数据");
        assertRejected("aiusage://pair#!!!not base64!!!", "解码失败");
        assertRejected("aiusage://pair#" + encode("{\"v\":1,".getBytes("UTF-8")), "JSON");
    }

    @Test
    public void refusesValuesThatAreTheWrongShape() throws Exception {
        // A digest that is not 64 hex would be a truncated paste, and pinning a short
        // string is how "accepts anything" starts.
        assertRejected(offer("fingerprint", "abc123"), "指纹");
        // Same for the one-time secret: 64 hex or nothing.
        assertRejected(offer("pairToken", "bffd5b6c"), "配对令牌");
        assertRejected(offer("port", 0), "端口");
        assertRejected(offer("port", 70000), "端口");
        assertRejected(offer("bridgeId", "192.168.1.20"), "Bridge ID");
        // An address with a port or scheme in it means the two ends disagree about
        // who builds the URL.
        assertRejected(offerHosts("192.168.1.20:38411"), "主机名或 IP");
        assertRejected(offerHosts("https://elsewhere"), "主机名或 IP");
        assertRejected(offerHosts(""), "地址");
        // An empty list is its own shape: it parses, every field is present, and there
        // is nowhere to connect. Reading it as "one host, the empty string" would
        // produce a pairing attempt against https://:38411.
        assertRejected(offerHosts(), "地址");
    }

    @Test
    public void anOfferNamingAnIpv6AddressBuildsTheFormAUrlNeeds() throws Exception {
        // The Bridge writes net.IP.String(), so an IPv6 host arrives without brackets;
        // a URL built straight from it would put the port inside the address.
        PairingPayload payload = parse(offerHosts("2001:db8::1", "::1"));

        assertEquals(Arrays.asList("2001:db8::1", "::1"), payload.getHosts());
        assertEquals("https://[2001:db8::1]:38411", payload.baseUrlFor(0));
        assertEquals("https://[::1]:38411", payload.baseUrlFor(1));
    }

    @Test
    public void theManualChannelCarriesNoDigestAndRefusesPlaintext() throws Exception {
        ManualTarget target = new ManualTarget("192.168.1.20", 38411, "7qaytwem");
        assertEquals("192.168.1.20", target.getHost());
        assertEquals("https://192.168.1.20:38411", target.baseUrl());
        assertEquals("7qaytwem", target.getCode());
        assertTrue("the manual channel is the one without a fingerprint",
                ManualTarget.tailOf(GoVectors.ALPHA_FINGERPRINT).length() == 8);

        try {
            new ManualTarget("http://192.168.1.20", 38411, "7qaytwem");
            fail("a typed http:// address must not silently become a plaintext pairing");
        } catch (PairingPayload.Invalid expected) {
            assertTrue(expected.getMessage().contains("HTTPS"));
        }
        // The alphabet drops 0, 1, 2, o, l and i because they get mis-transcribed
        // aloud. Each case below names the reason it was refused: the two rules
        // overlap ("0o1il2z" is both the wrong length and full of banned characters),
        // and an assertion that only requires *a* complaint stays green when either
        // check is deleted. The length cases use characters that are legal, and the
        // alphabet cases are all exactly eight long.
        assertCodeRejected("abcdefg", "位");
        assertCodeRejected("abcdefghj", "位");
        assertCodeRejected("abcdefg0", "配对码里没有这个字符");
        assertCodeRejected("abcdefgi", "配对码里没有这个字符");
        assertCodeRejected("abcdefg-", "配对码里没有这个字符");
    }

    @Test
    public void aTypedAddressWithAPortInItIsRefusedRatherThanDialledTwice() throws Exception {
        // The screen has a port box, so an address box that also carries a port would
        // build https://10.0.2.2:38411:38411 — and a user who typed the whole thing
        // from a browser's address bar deserves to be told which box to fix.
        try {
            new ManualTarget("192.168.1.20:38411", 38411, "7qaytwem");
            fail("a host:port address was accepted into the address box");
        } catch (PairingPayload.Invalid expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("端口"));
        }
        try {
            new ManualTarget("[::1]:38411", 38411, "7qaytwem");
            fail("a bracketed IPv6 with a port was accepted");
        } catch (PairingPayload.Invalid expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("端口"));
        }
        assertEquals("https://[fe80::1]:38411",
                new ManualTarget("fe80::1", 38411, "7qaytwem").baseUrl());
        assertEquals("https://10.0.2.2:38411",
                new ManualTarget("10.0.2.2", 38411, "7qaytwem").baseUrl());
        assertEquals("an IPv6 literal already in brackets is kept as written",
                "https://[::1]:38411",
                new ManualTarget("[::1]", 38411, "7qaytwem").baseUrl());
        try {
            new ManualTarget("[::1", 38411, "7qaytwem");
            fail("an unclosed bracket was treated as an address");
        } catch (PairingPayload.Invalid expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("中括号"));
        }
    }

    /**
     * @param why     the substring the refusal must name
     */
    private static void assertCodeRejected(String code, String why) {
        try {
            new ManualSource("192.168.1.20", 38411, "7qaytwem").read();
        } catch (PairingPayload.Invalid unexpected) {
            fail("the control code stopped being accepted: " + unexpected.getMessage());
        }
        try {
            new ManualSource("192.168.1.20", 38411, code).read();
            fail("accepted the pairing code " + code);
        } catch (PairingPayload.Invalid expected) {
            assertTrue("refused " + code + ", but for the wrong reason: "
                            + expected.getMessage(),
                    expected.getMessage().contains(why));
        }
    }

    @Test
    public void anEmptyManualReadIsAnErrorRatherThanAnInventedOffer() {
        try {
            new ManualSource("  ", 38411, "7qaytwem").read();
            fail("an empty address was accepted");
        } catch (PairingPayload.Invalid expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("电脑地址"));
        }
    }

    @Test
    public void sourcesSayWhichChannelTheyAre() throws Exception {
        assertEquals("粘贴的配对内容",
                TextOfferSource.fromPaste(GoVectors.ALPHA_PAYLOAD).label());
        assertEquals("系统转来的配对链接",
                TextOfferSource.fromDeepLink(GoVectors.ALPHA_PAYLOAD).label());
        assertEquals("手输地址与配对码", new ManualSource("h", 1, "7qaytwem").label());

        PairingOffer pasted = TextOfferSource.fromPaste(GoVectors.ALPHA_PAYLOAD).read();
        assertTrue(pasted.hasPayload());
        assertFalse(pasted.isManual());
        PairingOffer typed = new ManualSource("192.168.1.20", 38411, "7qaytwem").read();
        assertTrue(typed.isManual());
        assertFalse(typed.hasPayload());
        assertEquals("", typed.bridgeId());
    }

    @Test
    public void aPastedOfferWithSurroundingWhitespaceStillReads() throws Exception {
        // Copying from a terminal usually brings a newline with it, and refusing that
        // would read as the user having pasted the wrong thing.
        PairingPayload payload = parse("\n  " + GoVectors.ALPHA_PAYLOAD + "  \n");
        assertEquals(GoVectors.ALPHA_BRIDGE_ID, payload.getBridgeId());
    }

    // ------------------------------------------------------------- helpers

    private static String encode(JSONObject body) {
        return encode(body.toString().getBytes());
    }

    private static String encode(byte[] raw) {
        // Standard base64 then the URL alphabet: the encoder side of the Go code uses
        // base64.RawURLEncoding, and this keeps the test able to build its own offers.
        String standard = java.util.Base64.getEncoder().withoutPadding().encodeToString(raw);
        return standard.replace('+', '-').replace('/', '_');
    }

    private static String offer(String key, Object value) throws Exception {
        JSONObject body = new JSONObject()
                .put("v", 1)
                .put("bridgeId", GoVectors.ALPHA_BRIDGE_ID)
                .put("hosts", new JSONArray(Arrays.asList("192.168.1.20")))
                .put("port", 38411)
                .put("pairToken", GoVectors.PAIR_TOKEN)
                .put("fingerprint", GoVectors.ALPHA_FINGERPRINT);
        body.put(key, value);
        return PairingPayload.PREFIX + encode(body);
    }

    private static String offerHosts(String... hosts) throws Exception {
        return offer("hosts", new JSONArray(Arrays.asList(hosts)));
    }
}

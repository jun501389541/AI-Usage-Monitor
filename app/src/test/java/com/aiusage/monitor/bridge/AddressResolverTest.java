package com.aiusage.monitor.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The device-shaped part of address handling, apart from the client that uses it.
 *
 * <p>{@code PairingClientTest} proves the swap happens on the emulator and not on a
 * phone while the pin stays; this pins the two helpers that decision rests on, so a
 * change to the loopback list or the emulator tags is visible on its own instead of
 * only through a pairing run.
 */
public class AddressResolverTest {

    private static final AddressResolver PHONE = AddressResolver.REAL_DEVICE;
    private static final AddressResolver EMULATOR = AddressResolver.EMULATOR;

    @Test
    public void aPhoneDialsExactlyWhatWasOffered() {
        for (String host : new String[]{"127.0.0.1", "localhost", "::1", "192.168.1.20"}) {
            assertEquals(host, PHONE.host(host));
        }
    }

    @Test
    public void theEmulatorFindsTheHostsLoopbackUnderAnotherName() {
        assertEquals("10.0.2.2", EMULATOR.host("127.0.0.1"));
        assertEquals("10.0.2.2", EMULATOR.host("localhost"));
        assertEquals("10.0.2.2", EMULATOR.host("::1"));
        assertEquals("10.0.2.2", EMULATOR.host("LOCALHOST"));
        assertEquals("a hand-typed address keeps its port", "10.0.2.2:38411",
                EMULATOR.host("127.0.0.1:38411"));
    }

    @Test
    public void theEmulatorRewritesNothingElse() {
        // The point of the limit: this is a documented property of the emulator's
        // loopback, not a general "fix up an address that failed" rule. A LAN address
        // the Bridge offered came from its own interfaces and must be dialled as-is.
        for (String host : new String[]{"192.168.1.20", "10.0.2.2", "fe80::1", "bridge.local"}) {
            assertEquals(host, EMULATOR.host(host));
        }
    }

    @Test
    public void aWholeUrlKeepsItsSchemeAndPort() {
        assertEquals("https://10.0.2.2:38411",
                EMULATOR.baseUrl("https://127.0.0.1:38411"));
        assertEquals("https://10.0.2.2:38411/v1/health",
                EMULATOR.baseUrl("https://localhost:38411/v1/health"));
        assertEquals("https://192.168.1.20:38411",
                EMULATOR.baseUrl("https://192.168.1.20:38411"));
        assertEquals("a phone never rewrites, whatever the address",
                "https://127.0.0.1:38411", PHONE.baseUrl("https://127.0.0.1:38411"));
    }

    @Test
    public void anIpv6LiteralIsBracketedForTheUrlAndNotOtherwise() {
        // The Bridge writes net.IP.String(), which is unbracketed, and an unbracketed
        // IPv6 inside https://…:38411 is not a host and a port — it is a URL that does
        // not parse. Bracketed input passes through unchanged so the two ends can
        // disagree about which form arrived without breaking either.
        assertEquals("[::1]", AddressResolver.authorityFor("::1"));
        assertEquals("[2001:db8::1]", AddressResolver.authorityFor("2001:db8::1"));
        assertEquals("[::1]", AddressResolver.authorityFor("[::1]"));
        assertEquals("127.0.0.1", AddressResolver.authorityFor("127.0.0.1"));
        assertEquals("bridge.local", AddressResolver.authorityFor("bridge.local"));
        assertTrue(AddressResolver.isIpv6Literal("fe80::1"));
        assertFalse("one colon is a port, not an IPv6 address",
                AddressResolver.isIpv6Literal("192.168.1.20:38411"));
    }

    @Test
    public void theEmulatorRewritesABracketedLoopbackToo() {
        assertEquals("10.0.2.2", EMULATOR.host("[::1]"));
        assertEquals("10.0.2.2:38411", EMULATOR.host("[::1]:38411"));
        assertEquals("https://10.0.2.2:38411",
                EMULATOR.baseUrl("https://[::1]:38411"));
        assertEquals("a non-loopback IPv6 keeps its brackets and its address",
                "https://[2001:db8::1]:38411",
                EMULATOR.baseUrl("https://[2001:db8::1]:38411"));
    }

    @Test
    public void theEmulatorIsRecognisedByItsOwnBuildTags() {
        assertTrue(AddressResolver.looksLikeEmulator(
                "google/sdk_gphone64_x86_64/emu64xa:15/...", "goldfish", "sdk_gphone64_x86_64"));
        assertTrue("the API 37 image this project is tested on",
                AddressResolver.looksLikeEmulator(
                        "generic/sdk_full_x86_64/generic:17/...", "ranchero", "sdk_full_x86_64"));
        assertFalse("a phone that happens to share a word with an image",
                AddressResolver.looksLikeEmulator(
                        "Pixel/shiba/shiba:16/CP1A.260101.001/release-keys",
                        "zuma", "shiba"));
        assertFalse("missing tags read as a real device, which is the safe side",
                AddressResolver.looksLikeEmulator(null, null, null));
    }
}

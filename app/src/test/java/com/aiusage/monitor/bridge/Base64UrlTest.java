package com.aiusage.monitor.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * The unpadded base64url decoder a pairing offer is read with.
 *
 * <p>This class exists because the two ends of pairing are written in two languages
 * and this is the one place where Android cannot use the standard library:
 * {@code java.util.Base64} is API 26 and the app supports 23. A hand-rolled decoder
 * needs its boundaries pinned by name, since the failure this one is written to avoid
 * is a payload that arrives cut short and still decodes into something JSON-shaped.
 */
public class Base64UrlTest {

    @Test
    public void readsWhatTheGoEncoderWrote() throws Exception {
        String fragment = GoVectors.ALPHA_PAYLOAD.substring(PairingPayload.PREFIX.length());

        byte[] decoded = Base64Url.decode(fragment);

        assertEquals("the offer is a UTF-8 JSON document",
                '{', decoded[0]);
        assertTrue(new String(decoded, StandardCharsets.UTF_8).contains("\"bridgeId\""));
    }

    @Test
    public void theAlphabetAndLengthsTheBridgeUses() {
        // Controls, so that the refusals below are not satisfied by a decoder that
        // rejects everything: 1, 2 and 3 bytes all encode without padding, and the two
        // URL characters (- and _) are indexes 62 and 63.
        assertArrayEquals(new byte[]{0x00}, Base64Url.decode("AA"));
        assertArrayEquals(new byte[]{0x00, 0x00}, Base64Url.decode("AAA"));
        assertArrayEquals(new byte[]{0x00, 0x00, 0x00}, Base64Url.decode("AAAA"));
        assertArrayEquals(new byte[]{(byte) 0xFF}, Base64Url.decode("_w"));
        for (byte[] sample : new byte[][]{
                {(byte) 0xFB, (byte) 0xEF, (byte) 0xBE},
                {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF},
                "a pairing offer with a comma, a slash and a plus: /,+".getBytes(
                        StandardCharsets.UTF_8),
        }) {
            assertArrayEquals("the inverse of the standard URL encoder, unpadded",
                    sample, Base64Url.decode(urlEncode(sample)));
        }
    }

    private static String urlEncode(byte[] raw) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    @Test
    public void paddingIsToleratedButNotRequired() {
        // The Bridge writes none. A paste that arrived with '=' on the end is still
        // the same bytes, and the strictness that matters is in the alphabet, not here.
        assertArrayEquals(new byte[]{0x00, 0x00}, Base64Url.decode("AAA="));
    }

    @Test
    public void refusesACharacterThatIsNotInTheUrlAlphabet() {
        // '+' and '/' belong to the standard alphabet. Accepting them would let a
        // string from a different encoder decode, and the two alphabets disagree on
        // exactly the two characters that appear in real payloads.
        for (String bad : new String[]{"A+", "/A", "a b", "AA=" + "?"}) {
            try {
                Base64Url.decode(bad);
                fail("decoded a string that is not base64url: " + bad);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("base64url"));
            }
        }
    }

    @Test
    public void refusesALengthNoEncoderCouldProduce() {
        // Five characters is 30 bits: three whole bytes and six left over. No padding
        // and no truncation explains it, so it is a cut string.
        try {
            Base64Url.decode("AAAAA");
            fail("accepted a remainder of one character");
        } catch (IllegalArgumentException expected) {
            assertTrue("the refusal should name the length, not the alphabet: "
                            + expected.getMessage(),
                    expected.getMessage().contains("完整"));
        }
    }

    @Test
    public void refusesNonZeroBitsLeftAtTheEnd() {
        // "_B" is 12 bits: one byte plus four that no encoding could have produced
        // from a partial input. Dropping them silently is how a payload that lost its
        // last characters still parses — as a shorter JSON, or as a truncated digest.
        try {
            Base64Url.decode("_B");
            fail("accepted a tail with non-zero leftover bits");
        } catch (IllegalArgumentException expected) {
            assertTrue("the refusal should name the truncation: " + expected.getMessage(),
                    expected.getMessage().contains("截断"));
        }
    }

    @Test
    public void refusesNothingAtAllExplicitly() {
        try {
            Base64Url.decode(null);
            fail("null decoded into an empty payload");
        } catch (IllegalArgumentException expected) {
            assertEquals("没有可解码的内容", expected.getMessage());
        }
        assertEquals("an empty string is a zero-length payload, which is legal here and "
                        + "refused one level up by the parser",
                0, Base64Url.decode("").length);
    }
}

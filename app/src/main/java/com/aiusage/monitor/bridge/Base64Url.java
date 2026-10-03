package com.aiusage.monitor.bridge;

/**
 * Base64 in the URL-and-filename alphabet, unpadded — the form the Bridge writes a
 * pairing offer in.
 *
 * <p>Hand-rolled on purpose, and small enough to be read in one go:
 * {@code java.util.Base64} is API 26 and this app supports 23, while
 * {@code android.util.Base64} cannot be exercised on a host JVM — and the part of
 * pairing this class serves is exactly the part where a silent substitution would
 * cost hours: a payload that decodes to mojibake reads as "the user pasted
 * something wrong".
 */
public final class Base64Url {

    private static final int[] DECODE = new int[128];

    static {
        for (int i = 0; i < DECODE.length; i++) {
            DECODE[i] = -1;
        }
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        for (int i = 0; i < alphabet.length(); i++) {
            DECODE[alphabet.charAt(i)] = i;
        }
    }

    private Base64Url() {
    }

    /**
     * @throws IllegalArgumentException if the text is not valid unpadded base64url
     */
    public static byte[] decode(String text) {
        if (text == null) {
            throw new IllegalArgumentException("没有可解码的内容");
        }
        // Padding is optional here, and only optional: a payload the Bridge wrote
        // has none, and accepting '=' would let a standard-alphabet string that
        // happens to decode arrive as a pairing offer.
        String body = text;
        while (body.endsWith("=")) {
            body = body.substring(0, body.length() - 1);
        }
        // A remainder of 1 cannot be produced by any encoding: it would be six bits
        // short of a byte. 0, 2 and 3 are all legal without padding.
        if (body.length() % 4 == 1) {
            throw new IllegalArgumentException("配对接续的长度不是一个完整 base64 组合");
        }
        byte[] out = new byte[body.length() * 3 / 4];
        int written = 0;
        int accumulator = 0;
        int bits = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            int value = c < DECODE.length ? DECODE[c] : -1;
            if (value < 0) {
                throw new IllegalArgumentException("配对接续包含非 base64url 字符：" + c);
            }
            accumulator = (accumulator << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[written++] = (byte) ((accumulator >> bits) & 0xFF);
            }
        }
        // Leftover bits must be zero bits, not a truncated character: "a" alone is
        // not a partial encoding of anything, and accepting it hides a cut string.
        if (bits > 0 && ((accumulator & ((1 << bits) - 1)) != 0)) {
            throw new IllegalArgumentException("配对接续在末尾被截断");
        }
        if (written != out.length) {
            byte[] trimmed = new byte[written];
            System.arraycopy(out, 0, trimmed, 0, written);
            return trimmed;
        }
        return out;
    }
}

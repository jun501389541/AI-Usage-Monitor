package com.aiusage.monitor.bridge;

import java.util.Locale;

/**
 * What the "type the address and the short code" channel yields: a place to call and
 * a secret to offer, and deliberately no fingerprint. Spec §21, docs/PHASE-7-PLAN.md
 * A7/A11.
 *
 * <p>The missing fingerprint is the channel's whole security question. A full payload
 * carries the digest, so the phone knows before it speaks what certificate it expects;
 * a typed code does not, and a connection made without that knowledge can be answered
 * by anyone on the path. The decision recorded in A11 is trust-on-first-use plus a
 * human comparison: the client probes the Bridge, shows the fingerprint tail the
 * computer's own terminal prints, and will not send the code until the caller hands
 * back the digest the user confirmed. That is enforced in
 * {@link PairingClient#exchangeManual}, not left to the screen.
 */
public final class ManualTarget {

    /**
     * The alphabet the Bridge issues codes from: the digits 0, 1 and 2 and the letters
     * o, l and i are left out, because they are what gets mistyped when a code is read
     * aloud. Keep it equal to {@code pairing.codeAlphabet} on the Bridge.
     */
    static final String CODE_ALPHABET = "abcdefghjkmnpqrstuvwxyz3456789";
    static final int CODE_LENGTH = 8;

    /**
     * The port a Bridge listens on unless told otherwise — the same default the
     * computer's {@code --port} flag carries, so an empty port box is not a guess.
     */
    public static final int DEFAULT_PORT = 38411;

    private final String host;
    private final int port;
    private final String code;

    public ManualTarget(String host, int port, String code) throws PairingPayload.Invalid {
        this.host = normaliseHost(host);
        this.port = port;
        this.code = normaliseCode(code);
        if (port < 1 || port > 65535) {
            throw new PairingPayload.Invalid("端口不在范围内：" + port);
        }
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getCode() {
        return code;
    }

    public String baseUrl() {
        return "https://" + AddressResolver.authorityFor(host) + ":" + port;
    }

    /**
     * The part a person can actually compare on a screen: the Bridge's own name plus
     * the leading digits of its digest. The full 64 characters are what the client
     * compares, so a tail match is a human step and not the security check itself.
     */
    public static String tailOf(String fingerprint) {
        if (fingerprint != null && fingerprint.startsWith(FingerprintPin.CERTIFICATE_PREFIX)) {
            fingerprint = fingerprint.substring(FingerprintPin.CERTIFICATE_PREFIX.length());
        }
        if (fingerprint == null || fingerprint.length() < 8) {
            return "";
        }
        return fingerprint.substring(0, 8).toUpperCase(Locale.US);
    }

    private static String normaliseHost(String value) throws PairingPayload.Invalid {
        if (value == null) {
            throw new PairingPayload.Invalid("没有填写电脑地址");
        }
        String cleaned = value.trim().toLowerCase(Locale.US);
        if (cleaned.startsWith("https://")) {
            cleaned = cleaned.substring("https://".length());
        } else if (cleaned.startsWith("http://")) {
            // Pairing carries a secret, so a typed http:// address is not a choice
            // the app will honour silently; it says so instead of downgrading.
            throw new PairingPayload.Invalid("配对必须走 HTTPS，地址里的 http:// 请去掉");
        }
        int slash = cleaned.indexOf('/');
        if (slash >= 0) {
            cleaned = cleaned.substring(0, slash);
        }
        if (cleaned.startsWith("[")) {
            int bracketEnd = cleaned.indexOf(']');
            if (bracketEnd < 0) {
                throw new PairingPayload.Invalid("地址里的中括号没有闭合：" + cleaned);
            }
            String after = cleaned.substring(bracketEnd + 1);
            if (!after.isEmpty()) {
                // "[::1]:38411" is two fields written into one box.
                throw new PairingPayload.Invalid(
                        "地址里不带端口，端口请填在下一个框：" + cleaned);
            }
        } else if (!AddressResolver.isIpv6Literal(cleaned) && cleaned.indexOf(':') >= 0) {
            // A single colon is a port, and the port has its own field: appending it to
            // the address too is how https://10.0.2.2:38411:38411 gets dialled.
            throw new PairingPayload.Invalid(
                    "地址里不带端口，端口请填在下一个框：" + cleaned);
        }
        if (cleaned.isEmpty()) {
            throw new PairingPayload.Invalid("没有填写电脑地址");
        }
        return cleaned;
    }

    private static String normaliseCode(String value) throws PairingPayload.Invalid {
        if (value == null || value.trim().isEmpty()) {
            throw new PairingPayload.Invalid("没有填写配对码");
        }
        String cleaned = value.trim().toLowerCase(Locale.US).replace(" ", "-");
        if (cleaned.length() != CODE_LENGTH) {
            throw new PairingPayload.Invalid("配对码是 " + CODE_LENGTH + " 位，你填了 "
                    + cleaned.length() + " 位");
        }
        for (int i = 0; i < cleaned.length(); i++) {
            if (CODE_ALPHABET.indexOf(cleaned.charAt(i)) < 0) {
                // Named exactly, and the list is the Go side's (pairing.codeAlphabet):
                // the digits 0, 1 and 2 and the letters o, l and i are dropped, while
                // z stays in. Saying "no z" would send a user who typed a valid code
                // away to retype it.
                throw new PairingPayload.Invalid("配对码里没有这个字符：" + cleaned.charAt(i)
                        + "（不含 0、1、2 和 o、l、i）");
            }
        }
        return cleaned;
    }
}

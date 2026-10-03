package com.aiusage.monitor.bridge;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * One pairing offer, as the Bridge writes it: {@code aiusage://pair#<base64url(json)>}.
 * Spec §20, and the field list the Go side writes in
 * {@code bridge/internal/pairing/payload.go}.
 *
 * <p>Parsing is strict in both directions, and each strictness has a reason:
 * <ul>
 *   <li><b>Unknown keys are refused, not ignored.</b> A future Bridge that adds a
 *       field this build does not understand must say "this offer is from a version
 *       I cannot read" rather than pair successfully and then misbehave — silently
 *       dropping a field is how two halves of one protocol start disagreeing while
 *       everything looks fine.</li>
 *   <li><b>Only the one-time secret is accepted here.</b> Spec §20's payload carries
 *       a Pair Token, and §0.3 of the Phase 7 plan reads the two prohibitions
 *       ("never put a long-lived credential in the QR") together: no API key shape,
 *       no device token field, no OpenAI credential. The field <em>names</em> are
 *       fixed, so a payload that arrives with {@code deviceToken} is refused as an
 *       unknown key rather than being read as the thing it is not.</li>
 *   <li><b>{@code hosts} is a list.</b> Which address a phone can reach depends on
 *       where the phone is, and the Bridge now derives that list from what it
 *       actually listens on (docs/PHASE-7-REVIEW.md P2), so the client tries them in
 *       order instead of guessing one.</li>
 * </ul>
 *
 * <p>The wire shape is asserted against a payload produced by the real Go Bridge, in
 * {@code PairingPayloadTest} — two languages implementing the same format from their
 * own reading of it is exactly the drift a test exists to catch.
 */
public final class PairingPayload {

    public static final String PREFIX = "aiusage://pair#";
    public static final int VERSION = 1;

    private static final String FIELD_VERSION = "v";
    private static final String FIELD_BRIDGE_ID = "bridgeId";
    private static final String FIELD_HOSTS = "hosts";
    private static final String FIELD_PORT = "port";
    private static final String FIELD_PAIR_TOKEN = "pairToken";
    private static final String FIELD_FINGERPRINT = "fingerprint";

    /**
     * Every field a version-1 offer must carry, checked for presence before any of
     * them is read. A missing one is named on screen: "no address to connect to" is a
     * guess about which field was empty, and the user cannot act on a guess.
     */
    private static final String[] REQUIRED_FIELDS = {
            FIELD_VERSION, FIELD_BRIDGE_ID, FIELD_HOSTS, FIELD_PORT,
            FIELD_PAIR_TOKEN, FIELD_FINGERPRINT,
    };

    /** Why an offer was refused, in a form the pairing screen can show. */
    public static class Invalid extends Exception {
        public Invalid(String message) {
            super(message);
        }
    }

    private final String bridgeId;
    private final List<String> hosts;
    private final int port;
    private final String pairToken;
    private final String fingerprint;

    private PairingPayload(String bridgeId, List<String> hosts, int port, String pairToken,
                           String fingerprint) {
        this.bridgeId = bridgeId;
        this.hosts = Collections.unmodifiableList(hosts);
        this.port = port;
        this.pairToken = pairToken;
        this.fingerprint = fingerprint;
    }

    public String getBridgeId() {
        return bridgeId;
    }

    /** The addresses the Bridge said it is listening on, in the order to try. */
    public List<String> getHosts() {
        return hosts;
    }

    public int getPort() {
        return port;
    }

    /** The one-time secret. Never logged, never stored: it dies at the exchange. */
    public String getPairToken() {
        return pairToken;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    /** Where to send the exchange: TLS, because the token inside it is a secret. */
    public String baseUrlFor(int index) {
        return "https://" + AddressResolver.authorityFor(hosts.get(index)) + ":" + port;
    }

    public static PairingPayload parse(String text) throws Invalid {
        if (text == null || text.trim().isEmpty()) {
            throw new Invalid("没有配对内容");
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith(PREFIX)) {
            throw new Invalid("配对内容不是以 " + PREFIX + " 开头的");
        }
        String encoded = trimmed.substring(PREFIX.length());
        if (encoded.isEmpty()) {
            throw new Invalid("配对内容缺少数据");
        }

        byte[] decoded;
        try {
            decoded = Base64Url.decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new Invalid("配对内容解码失败：" + exception.getMessage());
        }

        JSONObject json;
        try {
            json = new JSONObject(new String(decoded, "UTF-8"));
        } catch (JSONException | java.io.UnsupportedEncodingException exception) {
            throw new Invalid("配对内容不是可读的 JSON");
        }

        requireKnownFields(json);
        // The version is answered before the fields: a payload from another version may
        // legitimately carry a different set, and "missing bridgeId" would send the user
        // to re-paste something that was pasted whole.
        int version = json.optInt(FIELD_VERSION, -1);
        if (version != VERSION) {
            throw new Invalid("这份配对内容来自版本 " + version + "，本机只认版本 " + VERSION);
        }
        requirePresentFields(json);

        String bridgeId = requireText(json, FIELD_BRIDGE_ID);
        if (!bridgeId.matches("^br_[0-9a-z]{6,40}$")) {
            throw new Invalid("Bridge ID 的形状不对");
        }
        List<String> hosts = requireHosts(json);
        int port = json.optInt(FIELD_PORT, -1);
        if (port < 1 || port > 65535) {
            throw new Invalid("端口不在范围内：" + port);
        }
        String pairToken = requireText(json, FIELD_PAIR_TOKEN);
        if (!pairToken.matches("^[0-9a-f]{64}$")) {
            throw new Invalid("配对令牌不是一枚一次性密钥");
        }
        String fingerprint = requireText(json, FIELD_FINGERPRINT);
        if (!fingerprint.matches("^[0-9a-f]{64}$")) {
            throw new Invalid("指纹不是一枚 SHA-256 摘要");
        }
        return new PairingPayload(bridgeId, hosts, port, pairToken, fingerprint);
    }

    private static void requirePresentFields(JSONObject json) throws Invalid {
        for (String field : REQUIRED_FIELDS) {
            if (json.isNull(field)) {
                throw new Invalid("配对内容缺少字段：" + field);
            }
        }
    }

    private static void requireKnownFields(JSONObject json) throws Invalid {
        for (Iterator<String> it = json.keys(); it.hasNext(); ) {
            String key = it.next();
            if (!FIELD_VERSION.equals(key) && !FIELD_BRIDGE_ID.equals(key)
                    && !FIELD_HOSTS.equals(key) && !FIELD_PORT.equals(key)
                    && !FIELD_PAIR_TOKEN.equals(key) && !FIELD_FINGERPRINT.equals(key)) {
                // The name is reported, never the value: an unknown key could be the
                // one carrying a secret.
                throw new Invalid("这份配对内容包含本机不认识的字段：" + key);
            }
        }
    }

    private static String requireText(JSONObject json, String field) throws Invalid {
        String value = json.optString(field, null);
        if (value == null || value.trim().isEmpty()) {
            throw new Invalid("配对内容缺少字段：" + field);
        }
        return value.trim();
    }

    private static List<String> requireHosts(JSONObject json) throws Invalid {
        JSONArray array = json.optJSONArray(FIELD_HOSTS);
        if (array == null || array.length() == 0) {
            throw new Invalid("配对内容没有可连接的地址");
        }
        List<String> hosts = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            String host = array.optString(i, null);
            if (host == null || host.trim().isEmpty()) {
                throw new Invalid("地址列表里有一项是空的");
            }
            String cleaned = host.trim();
            // An address with a scheme, a path or a port in it means the two ends
            // disagree about what the field holds; the client builds the URL. An IPv6
            // literal is allowed because that is the form Go's net.IP.String() writes,
            // and it is the one kind of host whose colons are not a port.
            if (cleaned.contains("://") || cleaned.contains("/")
                    || (!AddressResolver.isIpv6Literal(cleaned) && cleaned.contains(":"))) {
                throw new Invalid("地址应该是主机名或 IP，不带端口：" + cleaned);
            }
            hosts.add(cleaned);
        }
        return hosts;
    }
}

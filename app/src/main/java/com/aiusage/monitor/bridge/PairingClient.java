package com.aiusage.monitor.bridge;

import com.aiusage.monitor.model.Bridge;
import com.aiusage.monitor.util.Http;

import java.io.IOException;
import java.util.Locale;


/**
 * The network half of pairing: ask a Bridge what it is, then trade a one-time secret
 * for a device token. Spec §21, docs/PHASE-7-PLAN.md A3/A5/A10/A11.
 *
 * <p>Two shapes of call, and the difference is the security story:
 * <ul>
 *   <li>{@link #pair(PairingPayload)} — the offer carried a digest, so the connection
 *       is pinned from the first byte and there is no trust-on-first-use window.</li>
 *   <li>{@link #discover(ManualTarget)} then {@link #exchangeManual} — a typed address
 *       and code carry no digest. The probe is explicitly unverified (it sends no
 *       credential), the digest it returns is what a human compares against the
 *       computer's terminal, and the exchange refuses to send the code unless the
 *       caller both confirms that comparison and hands back the same digest it saw.
 *       That is A11, and it is enforced here rather than assumed of the screen.</li>
 * </ul>
 *
 * <p>Failure wording follows the server's deliberate vagueness: an outsider who
 * guesses a code is told "无效或已过期" for every wrong answer, because which of the
 * three it was is exactly what a guessing attack learns from. The distinct reasons
 * stay on the computer's side.
 */
public final class PairingClient {

    static final String PAIR_PATH = "/v1/pair";
    static final String HEALTH_PATH = "/v1/health";

    /** Why a pairing did not complete, in a form the screen can show verbatim. */
    public enum Failure {
        /** The user did not confirm the fingerprint, or confirmed a different one. */
        NOT_CONFIRMED("请先在手机上是这台电脑的指纹"),
        /** The digest on the wire is not the one that was paired or confirmed. */
        FINGERPRINT_MISMATCH("证书指纹与配对时记录的不一致，连接已中止"),
        /** 401: wrong, expired or already-used code, and the Bridge will not say which. */
        CODE_REFUSED("配对码无效或已过期，请在电脑上重新点「添加设备」"),
        /** The Bridge could not write its own registry; retrying is the user's move. */
        BRIDGE_BUSY("电脑端暂时无法保存配对结果，请稍后重试"),
        /** Nothing answered, or it answered outside TLS. */
        UNREACHABLE("连不上这台电脑，请确认它们在同一网络且 Bridge 已用 --pair 启动"),
        /** A 200 that did not carry what the protocol promises. */
        BAD_RESPONSE("电脑端的应答不是预期格式");

        private final String message;

        Failure(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    /** A transport seam so the mapping above is testable without a socket. */
    public interface Transport {
        /** GETs a JSON document over TLS with no credential attached. */
        String probe(String baseUrl, String path) throws IOException;

        /** POSTs a JSON body over a connection pinned to {@code pin}. */
        Http.Response post(String baseUrl, String path, String jsonBody, FingerprintPin pin)
                throws IOException;
    }

    /** What a probe learned about an address: which Bridge lives there. */
    public static final class Discovered {
        private final String bridgeId;
        private final String fingerprint;

        Discovered(String bridgeId, String fingerprint) {
            this.bridgeId = bridgeId;
            this.fingerprint = fingerprint;
        }

        public String bridgeId() {
            return bridgeId;
        }

        public String fingerprint() {
            return fingerprint;
        }

        /** The eight characters a person compares by eye. */
        public String tail() {
            return ManualTarget.tailOf(fingerprint);
        }
    }

    /** A finished pairing: the Bridge row to store, and the secret to store with it. */
    public static final class Paired {
        private final Bridge bridge;
        private final String deviceToken;
        private final String deviceId;

        Paired(Bridge bridge, String deviceId, String deviceToken) {
            this.bridge = bridge;
            this.deviceId = deviceId;
            this.deviceToken = deviceToken;
        }

        public Bridge bridge() {
            return bridge;
        }

        public String deviceId() {
            return deviceId;
        }

        /** Shown once, stored encrypted, never logged. Spec §21. */
        public String deviceToken() {
            return deviceToken;
        }
    }

    private final Transport transport;
    private final Clock clock;
    private final AddressResolver addresses;

    public interface Clock {
        long nowMs();
    }

    /** The addresses on this device are rewritten by {@link AddressResolver}. */
    public PairingClient(Transport transport, Clock clock, AddressResolver addresses) {
        this.transport = transport;
        this.clock = clock;
        this.addresses = addresses == null ? AddressResolver.REAL_DEVICE : addresses;
    }

    /**
     * Pairs from a complete offer. The digest came with it, so there is nothing to
     * confirm: a Bridge that answers with a different key fails the handshake and no
     * request is sent at all.
     */
    public Paired pair(PairingPayload offer, String deviceName) throws PairingFailed {
        FingerprintPin pin = new FingerprintPin(offer.getFingerprint());
        for (int i = 0; i < offer.getHosts().size(); i++) {
            // What the offer named and what gets dialled can differ by one host on the
            // emulator only; the digest travels unchanged either way.
            String baseUrl = addresses.baseUrl(offer.baseUrlFor(i));
            try {
                return exchange(baseUrl, offer.getPairToken(), deviceName, pin,
                        offer.getBridgeId());
            } catch (PairingFailed failure) {
                // A host that is not there is the one failure worth trying again with:
                // the offer lists every address the Bridge listens on, and a phone on
                // the Wi-Fi may only reach one of them. Anything else is the answer.
                if (failure.retryableAddress && i + 1 < offer.getHosts().size()) {
                    continue;
                }
                throw failure;
            }
        }
        throw new PairingFailed(Failure.UNREACHABLE);
    }

    /** Reads the identity of an address the user typed, without pinning anything. */
    public Discovered discover(ManualTarget target) throws PairingFailed {
        String body;
        try {
            body = transport.probe(addresses.baseUrl(target.baseUrl()), HEALTH_PATH);
        } catch (IOException exception) {
            throw new PairingFailed(Failure.UNREACHABLE);
        }
        org.json.JSONObject json = parse(body);
        if (json == null) {
            throw new PairingFailed(Failure.BAD_RESPONSE);
        }
        String fingerprint = json.optString("fingerprint", "");
        String bridgeId = json.optString("bridgeId", "");
        if (fingerprint.length() != 64 || bridgeId.isEmpty()) {
            // A Bridge that predates pairing answers /v1/health without these fields.
            // Saying "this computer cannot be paired" is truer than pairing against a
            // digest of the empty string.
            throw new PairingFailed(Failure.BAD_RESPONSE);
        }
        return new Discovered(bridgeId, fingerprint.toLowerCase(Locale.US));
    }

    /**
     * Sends the typed code, provided a human confirmed the digest.
     *
     * @param confirmed the fingerprint the screen showed the user
     * @param userConfirmed the answer to "is this the computer shown on your screen?"
     */
    public Paired exchangeManual(ManualTarget target, Discovered confirmed, boolean userConfirmed)
            throws PairingFailed {
        return exchangeManual(target, confirmed, userConfirmed, "Android");
    }

    /**
     * As {@link #exchangeManual(ManualTarget, Discovered, boolean)}, with the name the
     * computer's device list will show.
     */
    public Paired exchangeManual(ManualTarget target, Discovered confirmed, boolean userConfirmed,
                                 String deviceName) throws PairingFailed {
        if (!userConfirmed) {
            throw new PairingFailed(Failure.NOT_CONFIRMED);
        }
        if (confirmed == null || !confirmed.fingerprint().equals(currentProbeOf(target))) {
            // The digest the user confirmed has to be the digest this address answers
            // with now. Without this, a screen could show one Bridge's tail and send
            // the code to another.
            throw new PairingFailed(Failure.NOT_CONFIRMED);
        }
        // The same resolution the probe used. Exchanging against the unresolved address
        // is how the emulator ends up sending the code to itself after having confirmed
        // the fingerprint of the machine at 10.0.2.2.
        FingerprintPin pin = new FingerprintPin(confirmed.fingerprint());
        return exchange(addresses.baseUrl(target.baseUrl()), target.getCode(), deviceName, pin,
                confirmed.bridgeId());
    }

    private String currentProbeOf(ManualTarget target) throws PairingFailed {
        return discover(target).fingerprint();
    }

    private Paired exchange(String baseUrl, String secret, String deviceName,
                            FingerprintPin pin, String expectedBridgeId) throws PairingFailed {
        String body;
        try {
            body = new org.json.JSONObject()
                    .put("pairToken", secret)
                    .put("deviceName", deviceName == null ? "" : deviceName)
                    .toString();
        } catch (org.json.JSONException exception) {
            throw new PairingFailed(Failure.BAD_RESPONSE);
        }

        Http.Response response;
        try {
            response = transport.post(baseUrl, PAIR_PATH, body, pin);
        } catch (javax.net.ssl.SSLHandshakeException exception) {
            // The pin is checked during the handshake, so this is the shape a
            // mismatched certificate takes — and nothing was sent to learn from it.
            throw new PairingFailed(Failure.FINGERPRINT_MISMATCH);
        } catch (IOException exception) {
            PairingFailed failed = new PairingFailed(Failure.UNREACHABLE);
            failed.retryableAddress = true;
            throw failed;
        }

        int code = response.getCode();
        if (code == 401) {
            throw new PairingFailed(Failure.CODE_REFUSED);
        }
        if (code == 503) {
            throw new PairingFailed(Failure.BRIDGE_BUSY);
        }
        if (code < 200 || code >= 300) {
            throw new PairingFailed(Failure.BAD_RESPONSE);
        }
        org.json.JSONObject json = parse(response.getBody());
        if (json == null) {
            throw new PairingFailed(Failure.BAD_RESPONSE);
        }
        String deviceToken = json.optString("deviceToken", "");
        String deviceId = json.optString("deviceId", "");
        String bridgeId = json.optString("bridgeId", expectedBridgeId);
        if (deviceToken.length() < 32 || deviceId.isEmpty()) {
            throw new PairingFailed(Failure.BAD_RESPONSE);
        }
        Bridge bridge = Bridge.builder()
                .id(bridgeId)
                .name(baseUrl)
                .baseUrl(baseUrl)
                .fingerprint(pin.value())
                .addedAt(clock.nowMs())
                .lastSeen(clock.nowMs())
                .build();
        return new Paired(bridge, deviceId, deviceToken);
    }

    private static org.json.JSONObject parse(String text) {
        try {
            return new org.json.JSONObject(text);
        } catch (Exception exception) {
            // org.json throws a handful of shapes across the two implementations of
            // it this project can end up compiling against; none of them is an answer.
            return null;
        }
    }

    /** A pairing that did not happen, with a message meant for the screen. */
    public static final class PairingFailed extends Exception {
        private final Failure failure;
        /** True when the next address in the offer may still work. */
        private boolean retryableAddress;

        PairingFailed(Failure failure) {
            super(failure.message());
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }
    }
}

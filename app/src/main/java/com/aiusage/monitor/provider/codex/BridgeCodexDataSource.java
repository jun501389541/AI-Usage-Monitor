package com.aiusage.monitor.provider.codex;

import com.aiusage.monitor.model.UsageError;
import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.provider.UsageException;
import com.aiusage.monitor.util.BridgeTransport;
import com.aiusage.monitor.util.Http;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/**
 * Reads one Codex account from a Windows AI Usage Bridge.
 *
 * <p>Spec §53 rule 20 requires Codex Bridge and Codex Direct to share a single
 * provider, so this is deliberately a *data source* and not a provider of its
 * own: the transport, the URL shape and the status mapping live here, and the
 * provider that will register in step 2 only chooses between sources.
 *
 * <p>Failure mapping is the whole point of this class. Spec §53 rule 19 demands
 * that "the computer is offline" and "the authorisation expired" stay
 * distinguishable, and the Bridge already reports which one happened in its 503
 * body, so the classes are translated one by one instead of collapsing into a
 * generic network error.
 */
public final class BridgeCodexDataSource {

    private static final String USAGE_PATH = "/v1/accounts/codex/usage";
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 12000;

    private final BridgeTransport transport;
    private final com.aiusage.monitor.util.PinnedTransportSource pinned;

    public BridgeCodexDataSource(BridgeTransport transport) {
        this(transport, new com.aiusage.monitor.bridge.BridgeTlsTransportSource());
    }

    /**
     * @param pinned how to reach a computer whose certificate digest is known; a
     *               build with no pairing storage passes an explicit source so this
     *               class stays the only place that decides which transport speaks.
     */
    public BridgeCodexDataSource(BridgeTransport transport,
                                 com.aiusage.monitor.util.PinnedTransportSource pinned) {
        this.transport = transport;
        this.pinned = pinned;
    }

    /**
     * @param baseUrl     e.g. {@code http://10.0.2.2:38411}; no trailing slash needed
     * @param deviceToken the Temporary Token; empty means "send no header"
     * @param pin         hex SHA-256 of the paired computer's public key, or empty
     *                    for an account whose address was typed in by hand
     */
    public UsageResult fetch(String baseUrl, String deviceToken, String pin, String accountId,
                             long nowMs) throws UsageException {
        return fetch(baseUrl, deviceToken, pin, accountId, nowMs, "");
    }

    public UsageResult fetch(String baseUrl, String deviceToken, String pin, String accountId,
                             long nowMs, String remoteAccountId) throws UsageException {
        String url = buildUrl(baseUrl);
        if (pin != null && pin.startsWith(com.aiusage.monitor.bridge.FingerprintPin.CERTIFICATE_PREFIX)) {
            if (remoteAccountId == null || !remoteAccountId.matches("[0-9a-f]{64}")) {
                throw new UsageException(UsageError.BRIDGE_PAIRING_REQUIRED, "没有已授权的远端账户，请重新配对。");
            }
            url = url.substring(0, url.length() - USAGE_PATH.length())
                    + "/v1/accounts/" + remoteAccountId + "/usage";
        }
        BridgeTransport chosen = chooseTransport(url, pin);

        Http.Response response;
        try {
            response = send(chosen, url, deviceToken);
        } catch (IOException exception) {
            Throwable cause = exception;
            for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
                if (cause instanceof com.aiusage.monitor.bridge.PinnedTrustManager.PinMismatchException) {
                    throw new UsageException(UsageError.BRIDGE_PAIRING_REQUIRED,
                            "电脑端证书与配对记录不一致，请核对电脑后重新扫码配对。", exception);
                }
            }
            // Nothing reached the Bridge, or it died mid-request. This is the
            // "computer offline" case, and the only one allowed to say it.
            throw new UsageException(UsageError.BRIDGE_OFFLINE,
                    "无法连接电脑端 Bridge：" + exception.getMessage(), exception);
        }

        int code = response.getCode();
        if (response.isSuccess()) {
            if (pin != null && pin.startsWith(com.aiusage.monitor.bridge.FingerprintPin.CERTIFICATE_PREFIX)) {
                try {
                    if (!remoteAccountId.equals(new JSONObject(response.getBody()).optString("accountId"))) {
                        throw new UsageException(UsageError.BRIDGE_UNAUTHORIZED,
                                "电脑返回的账户与配对授权账户不同，请重新配对。");
                    }
                } catch (JSONException invalid) {
                    throw new UsageException(UsageError.UNKNOWN, "电脑端额度响应格式无效。", invalid);
                }
            }
            return BridgeUsageParser.parse(response.getBody(), accountId, nowMs);
        }
        if (code == 401 || code == 403) {
            throw new UsageException(UsageError.BRIDGE_UNAUTHORIZED,
                    "电脑端未授权这台手机或授权已失效，请重新配对并在电脑端批准。");
        }
        if (code == 409) {
            throw new UsageException(UsageError.BRIDGE_UNAUTHORIZED,
                    "电脑的 Codex 账户已变化，请在电脑重新授权并配对。");
        }
        if (code == 503) {
            // The Bridge's 503 body names the class, so the app can tell offline
            // from unsupported-from-expired without guessing.
            throw new UsageException(classToError(errorClassOf(response.getBody())),
                    "电脑端返回 HTTP 503");
        }
        throw new UsageException(UsageError.UNKNOWN, "电脑端返回 HTTP " + code);
    }

    /**
     * Which transport speaks to this address, decided from the address and the digest
     * and nothing else.
     *
     * <p>The two refusals are the point. A paired computer serves TLS with a self
     * signed certificate whose subject is a random id, so reading it without the pin
     * cannot succeed and the fallback people reach for — trust the platform, or trust
     * anything — is the hole pairing was added to close; saying so is both true and
     * actionable. And a digest paired with a plaintext address means the row and the
     * account disagree, which is a corrupted record rather than a network problem.
     */
    private BridgeTransport chooseTransport(String url, String pin) throws UsageException {
        boolean tls = url.startsWith("https://");
        boolean hasPin = pin != null && !pin.trim().isEmpty();
        if (hasPin) {
            if (!tls) {
                throw new UsageException(UsageError.BRIDGE_PAIRING_REQUIRED,
                        "这台电脑是配对来的，只能用 HTTPS 读取，但账户里记的地址不是：" + url);
            }
            return pinned.forPin(pin.trim());
        }
        if (tls) {
            throw new UsageException(UsageError.BRIDGE_PAIRING_REQUIRED,
                    "这个地址要求 HTTPS，但账户没有可钉的证书指纹，请先与这台电脑配对");
        }
        return transport;
    }

    private Http.Response send(BridgeTransport transport, String url, String deviceToken)
            throws IOException {
        if (deviceToken == null || deviceToken.trim().isEmpty()) {
            // The Phase 5 Bridge authenticates nothing (it binds loopback only), so
            // an empty token means "no header" rather than an empty Bearer value -
            // sending one would claim a credential the user never typed.
            return transport.get(url, Collections.<String, String>emptyMap(),
                    CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
        }
        Map<String, String> headers =
                Collections.singletonMap("Authorization", "Bearer " + deviceToken.trim());
        return transport.get(url, headers, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    private static String buildUrl(String baseUrl) throws UsageException {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new UsageException(UsageError.UNSUPPORTED, "没有填写电脑端 Bridge 地址");
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new UsageException(UsageError.UNSUPPORTED,
                    "Bridge 地址必须以 http:// 或 https:// 开头");
        }
        return trimmed + USAGE_PATH;
    }

    static UsageError classToError(String bridgeClass) {
        if (bridgeClass == null) {
            return UsageError.UNKNOWN;
        }
        switch (bridgeClass) {
            case "CODEX_NOT_FOUND":
            case "CODEX_TIMEOUT":
                // Both mean "the computer did not answer", which is what the
                // BRIDGE_OFFLINE wording says.
                return UsageError.BRIDGE_OFFLINE;
            case "CODEX_AUTH_REQUIRED":
                return UsageError.BRIDGE_UNAUTHORIZED;
            case "CODEX_METHOD_UNAVAILABLE":
                // Not offline, not unauthorised: this Codex build cannot answer the
                // question. Collapsing it into either of the above would be a lie.
                return UsageError.UNSUPPORTED;
            default:
                return UsageError.UNKNOWN;
        }
    }

    private static String errorClassOf(String body) {
        try {
            return new JSONObject(body).optString("error", "");
        } catch (JSONException exception) {
            // A 503 with an unreadable body is still a 503; report it as unknown
            // rather than as a transport failure.
            return "";
        }
    }
}

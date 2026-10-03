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

    public BridgeCodexDataSource(BridgeTransport transport) {
        this.transport = transport;
    }

    /**
     * @param baseUrl     e.g. {@code http://10.0.2.2:38411}; no trailing slash needed
     * @param deviceToken the Temporary Token; empty means "send no header"
     */
    public UsageResult fetch(String baseUrl, String deviceToken, String accountId, long nowMs)
            throws UsageException {
        String url = buildUrl(baseUrl);

        Http.Response response;
        try {
            response = send(url, deviceToken);
        } catch (IOException exception) {
            // Nothing reached the Bridge, or it died mid-request. This is the
            // "computer offline" case, and the only one allowed to say it.
            throw new UsageException(UsageError.BRIDGE_OFFLINE,
                    "无法连接电脑端 Bridge：" + exception.getMessage(), exception);
        }

        int code = response.getCode();
        if (response.isSuccess()) {
            return BridgeUsageParser.parse(response.getBody(), accountId, nowMs);
        }
        if (code == 401 || code == 403) {
            throw new UsageException(UsageError.BRIDGE_UNAUTHORIZED,
                    "电脑端拒绝了这个令牌（HTTP " + code + "）");
        }
        if (code == 503) {
            // The Bridge's 503 body names the class, so the app can tell offline
            // from unsupported-from-expired without guessing.
            throw new UsageException(classToError(errorClassOf(response.getBody())),
                    "电脑端返回 HTTP 503");
        }
        throw new UsageException(UsageError.UNKNOWN, "电脑端返回 HTTP " + code);
    }

    private Http.Response send(String url, String deviceToken) throws IOException {
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

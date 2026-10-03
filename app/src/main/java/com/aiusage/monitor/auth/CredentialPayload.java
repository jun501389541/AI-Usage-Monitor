package com.aiusage.monitor.auth;

import com.aiusage.monitor.model.UsageError;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Encodes and decodes the plaintext payload stored inside a
 * {@link Credential}. Spec §7.
 *
 * <p>The spec fixes the payload shapes:
 *
 * <pre>
 * API key  {"apiKey":"…"}
 * OAuth    {"accessToken":"…","refreshToken":"…","expiresAt":"…"}
 * Bridge   {"deviceToken":"…"}
 * </pre>
 *
 * <p>Pure string work, so it is testable on a plain JVM. The payload this class
 * produces is always handed straight to encryption; it is never persisted or
 * logged in this form.
 */
public final class CredentialPayload {

    private CredentialPayload() {
    }

    /** Builds {@code {"apiKey":"…"}}. */
    public static String forApiKey(String apiKey) throws AuthException {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new AuthException(UsageError.INVALID_CREDENTIAL, "API Key 不能为空");
        }
        try {
            JSONObject json = new JSONObject();
            json.put("apiKey", apiKey.trim());
            return json.toString();
        } catch (JSONException exception) {
            throw new AuthException(UsageError.UNKNOWN, "无法编码 API Key", exception);
        }
    }

    /** Extracts {@code apiKey}, or throws when the payload is unusable. */
    public static String extractApiKey(String payload) throws AuthException {
        JSONObject json = parse(payload);
        String apiKey = json.optString("apiKey", "").trim();
        if (apiKey.isEmpty()) {
            throw new AuthException(UsageError.INVALID_CREDENTIAL, "凭据中没有 API Key");
        }
        return apiKey;
    }

    /** Builds {@code {"deviceToken":"…"}}. */
    public static String forDeviceToken(String deviceToken) throws AuthException {
        if (deviceToken == null || deviceToken.trim().isEmpty()) {
            throw new AuthException(UsageError.BRIDGE_UNAUTHORIZED, "设备令牌不能为空");
        }
        try {
            JSONObject json = new JSONObject();
            json.put("deviceToken", deviceToken.trim());
            return json.toString();
        } catch (JSONException exception) {
            throw new AuthException(UsageError.UNKNOWN, "无法编码设备令牌", exception);
        }
    }

    /**
     * Builds {@code {"deviceToken":"…","bridgeUrl":"…"}}.
     *
     * <p>Spec §7 shows the Bridge payload as {@code {"deviceToken":"…"}}; the url
     * member is a deliberate extension. The alternative - a column of its own -
     * would mean a schema migration for one string, and {@code accounts.bridge_id}
     * is reserved for a Bridge's permanent identity rather than its address
     * (Spec §54). Keeping the two together also means rotating the token cannot
     * leave it pointing at a different machine.
     */
    public static String forBridge(String bridgeUrl, String deviceToken) throws AuthException {
        String url = normaliseUrl(bridgeUrl);
        String token = deviceToken == null ? "" : deviceToken.trim();
        try {
            JSONObject json = new JSONObject();
            json.put("bridgeUrl", url);
            if (!token.isEmpty()) {
                json.put("deviceToken", token);
            }
            return json.toString();
        } catch (JSONException exception) {
            throw new AuthException(UsageError.UNKNOWN, "无法编码 Bridge 凭据", exception);
        }
    }

    /** Extracts {@code deviceToken}, or throws when the payload is unusable. */
    public static String extractDeviceToken(String payload) throws AuthException {
        JSONObject json = parse(payload);
        String token = json.optString("deviceToken", "").trim();
        if (token.isEmpty()) {
            throw new AuthException(UsageError.BRIDGE_UNAUTHORIZED, "凭据中没有设备令牌");
        }
        return token;
    }

    /**
     * Extracts {@code deviceToken}, allowing it to be absent.
     *
     * <p>The Phase 5 Bridge checks no token at all - it is reachable only from the
     * host itself, where a token would guard nothing - so the configuration that
     * works today is an address with no token. Requiring one would make the
     * account unusable on exactly that setup; the field is optional here and the
     * data source sends no header when it comes back empty.
     */
    public static String extractOptionalDeviceToken(String payload) throws AuthException {
        return parse(payload).optString("deviceToken", "").trim();
    }

    /**
     * Extracts {@code bridgeUrl}. May legitimately be empty: the Phase 5 Bridge
     * needs a token for nothing, so an address alone is a working configuration.
     */
    public static String extractBridgeUrl(String payload) throws AuthException {
        JSONObject json = parse(payload);
        String url = json.optString("bridgeUrl", "").trim();
        if (url.isEmpty()) {
            return "";
        }
        return normaliseUrl(url);
    }

    /**
     * Rejects an address that could not be requested, so a typo surfaces while the
     * user is still in the field. Deliberately permissive about host form:
     * {@code 10.0.2.2}, {@code localhost} and a LAN address are all legitimate
     * here, and deciding what the app may reach is the network security
     * configuration's job, not this method's.
     */
    private static String normaliseUrl(String bridgeUrl) throws AuthException {
        String url = bridgeUrl == null ? "" : bridgeUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        // The slash trimming above is what rejects a bare scheme: "http://" with
        // its trailing slash removed is "http:", which fails this check. A length
        // guard after it would be unreachable, and a mutation test proved it.
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new AuthException(UsageError.UNSUPPORTED,
                    "Bridge 地址必须以 http:// 或 https:// 开头");
        }
        return url;
    }

    private static JSONObject parse(String payload) throws AuthException {
        if (payload == null || payload.trim().isEmpty()) {
            throw new AuthException(UsageError.INVALID_CREDENTIAL, "凭据为空");
        }
        try {
            return new JSONObject(payload);
        } catch (JSONException exception) {
            throw new AuthException(UsageError.INVALID_CREDENTIAL, "凭据格式无法识别", exception);
        }
    }
}

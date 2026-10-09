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

    /** Builds the independently encrypted OAuth payload used by phone Direct. */
    public static String forOAuth(String accessToken, String refreshToken, String idToken,
                                  long expiresAt, String accountId, String email)
            throws AuthException {
        return forOAuth(accessToken, refreshToken, idToken, expiresAt, accountId, email, "");
    }

    public static String forOAuth(String accessToken, String refreshToken, String idToken,
                                  long expiresAt, String accountId, String email, String workspace)
            throws AuthException {
        String access = accessToken == null ? "" : accessToken.trim();
        String refresh = refreshToken == null ? "" : refreshToken.trim();
        if (access.isEmpty() || refresh.isEmpty()) {
            throw new AuthException(UsageError.INVALID_CREDENTIAL,
                    "OAuth 凭据需要访问令牌和续期令牌");
        }
        try {
            JSONObject json = new JSONObject();
            json.put("accessToken", access);
            json.put("refreshToken", refresh);
            json.put("idToken", idToken == null ? "" : idToken);
            json.put("expiresAt", Math.max(0L, expiresAt));
            json.put("oauthAccountId", accountId == null ? "" : accountId);
            json.put("oauthEmail", email == null ? "" : email);
            json.put("oauthWorkspace", workspace == null ? "" : workspace);
            return json.toString();
        } catch (JSONException exception) {
            throw new AuthException(UsageError.UNKNOWN, "无法编码 OAuth 凭据", exception);
        }
    }

    public static String extractOAuthAccessToken(String payload) throws AuthException {
        return requireOAuthField(payload, "accessToken", "访问令牌");
    }

    public static String extractOAuthRefreshToken(String payload) throws AuthException {
        return requireOAuthField(payload, "refreshToken", "续期令牌");
    }

    private static String requireOAuthField(String payload, String key, String label)
            throws AuthException {
        String value = parse(payload).optString(key, "").trim();
        if (value.isEmpty()) {
            throw new AuthException(UsageError.INVALID_CREDENTIAL,
                    "OAuth 凭据中没有" + label);
        }
        return value;
    }

    public static String forRemoteDevice(String token, String accountId) throws AuthException {
        if (accountId == null || accountId.isEmpty()) return forDeviceToken(token);
        if (!accountId.matches("[0-9a-f]{64}")) {
            throw new AuthException(UsageError.INVALID_CREDENTIAL, "远端账户标识无效");
        }
        try {
            return new JSONObject(forDeviceToken(token)).put("remoteAccountId", accountId).toString();
        } catch (JSONException failed) {
            throw new AuthException(UsageError.UNKNOWN, "无法保存远端账户标识", failed);
        }
    }

    public static String extractRemoteAccountId(String payload) throws AuthException {
        String id = parse(payload).optString("remoteAccountId", "");
        if (!id.isEmpty() && !id.matches("[0-9a-f]{64}")) {
            throw new AuthException(UsageError.INVALID_CREDENTIAL, "远端账户标识无效");
        }
        return id;
    }

    /**
     * Builds {@code {"deviceToken":"…","bridgeUrl":"…"}}.
     *
     * <p>Hand-configured debug accounts have no paired bridge row, so they keep
     * their endpoint in this payload. Paired accounts store only the token and
     * resolve their current endpoint from the row referenced by
     * {@code accounts.bridge_id}.
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

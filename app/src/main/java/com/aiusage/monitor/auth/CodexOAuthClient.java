package com.aiusage.monitor.auth;

import android.util.Base64;

import com.aiusage.monitor.model.UsageError;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Codex CLI's public device-code OAuth flow, isolated from the computer's auth file. */
public final class CodexOAuthClient {
    public static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    public static final String DEVICE_PAGE = "https://auth.openai.com/codex/device";
    private static final String API_ACCOUNTS = "https://auth.openai.com/api/accounts";
    private static final String TOKEN_URL = "https://auth.openai.com/oauth/token";
    private static final String REDIRECT_URI = "https://auth.openai.com/deviceauth/callback";
    public static final long MAX_POLL_MS = 15L * 60L * 1000L;
    private final OAuthHttpTransport transport;
    private final Clock clock;
    private final Sleeper sleeper;

    public CodexOAuthClient() {
        this(new UrlConnectionOAuthTransport(), System::currentTimeMillis, Thread::sleep);
    }

    public CodexOAuthClient(OAuthHttpTransport transport, Clock clock, Sleeper sleeper) {
        this.transport = transport;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public DeviceCode requestDeviceCode() throws AuthException {
        JSONObject request = new JSONObject();
        try {
            request.put("client_id", CLIENT_ID);
        } catch (JSONException impossible) {
            throw new AuthException(UsageError.UNKNOWN, "无法准备 Codex 授权请求", impossible);
        }
        JSONObject result = postJson(API_ACCOUNTS + "/deviceauth/usercode", request.toString(),
                "无法向 Codex 请求设备码");
        String deviceAuthId = result.optString("device_auth_id", result.optString("deviceAuthId", ""));
        String userCode = result.optString("user_code", result.optString("usercode", ""));
        long interval = positiveLong(result.opt("interval"), 5L);
        long expires = positiveLong(result.opt("expires_in"), MAX_POLL_MS / 1000L);
        if (deviceAuthId.isEmpty() || userCode.isEmpty()) {
            throw new AuthException(UsageError.UNKNOWN, "Codex 没有返回可用设备码");
        }
        long expiresMs = expires >= MAX_POLL_MS / 1000L
                ? MAX_POLL_MS : expires * 1000L;
        return new DeviceCode(deviceAuthId, userCode, interval, expiresMs);
    }

    public Tokens authorize(DeviceCode deviceCode, Cancellation cancellation) throws AuthException {
        long deadline = clock.nowMs() + Math.min(deviceCode.expiresInMs, MAX_POLL_MS);
        long intervalMs = Math.max(1000L, secondsToMillis(deviceCode.intervalSeconds));
        while (clock.nowMs() < deadline) {
            if (cancellation != null && cancellation.isCancelled()) {
                throw new AuthException(UsageError.UNKNOWN, "Codex 手机授权已取消");
            }
            try {
                sleeper.sleep(Math.min(intervalMs, Math.max(1L, deadline - clock.nowMs())));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AuthException(UsageError.UNKNOWN, "Codex 手机授权已取消", interrupted);
            }
            if (cancellation != null && cancellation.isCancelled()) {
                throw new AuthException(UsageError.UNKNOWN, "Codex 手机授权已取消");
            }
            JSONObject request = new JSONObject();
            try {
                request.put("device_auth_id", deviceCode.deviceAuthId);
                request.put("user_code", deviceCode.userCode);
            } catch (JSONException impossible) {
                throw new AuthException(UsageError.UNKNOWN, "无法准备设备码轮询", impossible);
            }
            final OAuthHttpTransport.Response response;
            try {
                response = transport.post(API_ACCOUNTS + "/deviceauth/token",
                        "application/json", request.toString());
            } catch (IOException network) {
                throw new AuthException(UsageError.NETWORK_ERROR, "等待 Codex 授权时网络中断", network);
            }
            if (response.getCode() == 403 || response.getCode() == 404) continue;
            if (response.getCode() == 429) {
                long retryMs = retryAfterMs(response.getRetryAfter(), clock.nowMs());
                if (retryMs <= 0L) retryMs = retryAfterBodyMs(response.getBody());
                intervalMs = Math.max(intervalMs, retryMs);
                continue;
            }
            if (!response.isSuccess()) {
                if (response.getCode() == 400 || response.getCode() == 401) {
                    throw new AuthException(UsageError.AUTH_EXPIRED,
                            "设备码已失效或授权被拒绝，请重新开始登录");
                }
                throw new AuthException(UsageError.NETWORK_ERROR,
                        "Codex 设备码授权暂时不可用（HTTP " + response.getCode() + "）");
            }
            JSONObject code = parseObject(response.getBody(), "Codex 授权响应格式无效");
            String authorizationCode = code.optString("authorization_code", "");
            String verifier = code.optString("code_verifier", "");
            if (authorizationCode.isEmpty() || verifier.isEmpty()) {
                throw new AuthException(UsageError.UNKNOWN, "Codex 没有返回完整的授权结果");
            }
            // The code is one-time: exactly one exchange attempt, with no automatic retry.
            return exchangeAuthorizationCode(authorizationCode, verifier);
        }
        throw new AuthException(UsageError.AUTH_EXPIRED,
                "设备码已过期或等待超时，请重新开始授权");
    }

    public Tokens refresh(String refreshToken) throws AuthException {
        return refresh(refreshToken, null);
    }

    public Tokens refresh(String refreshToken, com.aiusage.monitor.provider.AuthContext previous)
            throws AuthException {
        String body = "grant_type=refresh_token&client_id=" + enc(CLIENT_ID)
                + "&refresh_token=" + enc(refreshToken);
        JSONObject response = postForm(TOKEN_URL, body, "Codex 手机授权续期失败");
        Profile fallback = previous == null ? null : new Profile(
                previous.get(com.aiusage.monitor.provider.AuthContext.KEY_OAUTH_ACCOUNT_ID),
                previous.get(com.aiusage.monitor.provider.AuthContext.KEY_OAUTH_EMAIL),
                previous.get(com.aiusage.monitor.provider.AuthContext.KEY_OAUTH_WORKSPACE));
        return parseTokens(response, refreshToken, fallback);
    }

    private Tokens exchangeAuthorizationCode(String code, String verifier) throws AuthException {
        String body = "grant_type=authorization_code&client_id=" + enc(CLIENT_ID)
                + "&redirect_uri=" + enc(REDIRECT_URI)
                + "&code=" + enc(code)
                + "&code_verifier=" + enc(verifier);
        JSONObject response = postForm(TOKEN_URL, body, "Codex 登录完成但令牌交换失败");
        return parseTokens(response, "", null);
    }

    private JSONObject postJson(String url, String body, String failure) throws AuthException {
        return post(url, "application/json", body, failure);
    }

    private JSONObject postForm(String url, String body, String failure) throws AuthException {
        return post(url, "application/x-www-form-urlencoded", body, failure);
    }

    private JSONObject post(String url, String contentType, String body, String failure)
            throws AuthException {
        final OAuthHttpTransport.Response response;
        try {
            response = transport.post(url, contentType, body);
        } catch (IOException network) {
            throw new AuthException(UsageError.NETWORK_ERROR, failure, network);
        }
        if (!response.isSuccess()) {
            if (response.getCode() == 404 && url.endsWith("/deviceauth/usercode")) {
                throw new AuthException(UsageError.UNSUPPORTED,
                        "此账号当前未启用 Codex 设备码登录");
            }
            if (response.getCode() == 400 || response.getCode() == 401 || response.getCode() == 403) {
                throw new AuthException(UsageError.AUTH_EXPIRED, failure + "，请重新登录");
            }
            if (response.getCode() == 429) {
                throw new AuthException(UsageError.NETWORK_ERROR, failure + "，服务暂时限流");
            }
            throw new AuthException(UsageError.NETWORK_ERROR,
                    failure + "（HTTP " + response.getCode() + "）");
        }
        return parseObject(response.getBody(), failure + "：响应格式无效");
    }

    private static Tokens parseTokens(JSONObject response, String fallbackRefresh, Profile fallbackProfile)
            throws AuthException {
        String access = response.optString("access_token", "");
        String refresh = response.optString("refresh_token", fallbackRefresh);
        String idToken = response.optString("id_token", "");
        long expiresIn = positiveLong(response.opt("expires_in"), 0L);
        if (access.isEmpty() || refresh.isEmpty()) {
            throw new AuthException(UsageError.AUTH_EXPIRED,
                    "Codex 没有返回可续期的令牌，请重新登录");
        }
        Profile profile = Profile.fromIdToken(idToken);
        if (fallbackProfile != null) {
            profile = new Profile(profile.accountId.isEmpty() ? fallbackProfile.accountId : profile.accountId,
                    profile.email.isEmpty() ? fallbackProfile.email : profile.email,
                    profile.workspaceId.isEmpty() ? fallbackProfile.workspaceId : profile.workspaceId);
        }
        return new Tokens(access, refresh, idToken,
                expiresIn > 0L ? System.currentTimeMillis() + expiresIn * 1000L : 0L,
                profile.accountId, profile.email, profile.workspaceId);
    }

    private static JSONObject parseObject(String body, String message) throws AuthException {
        try { return new JSONObject(body); }
        catch (Exception invalid) { throw new AuthException(UsageError.UNKNOWN, message, invalid); }
    }

    private static long positiveLong(Object raw, long fallback) {
        try {
            long parsed = raw instanceof Number ? ((Number) raw).longValue()
                    : Long.parseLong(String.valueOf(raw));
            return parsed > 0L ? parsed : fallback;
        } catch (RuntimeException invalid) { return fallback; }
    }

    private static long retryAfterBodyMs(String body) {
        try {
            long seconds = new JSONObject(body).optLong("retry_after", 0L);
            return secondsToMillis(seconds);
        } catch (Exception ignored) { return 0L; }
    }

    private static long retryAfterMs(String header, long nowMs) {
        if (header == null || header.trim().isEmpty()) return 0L;
        try {
            long seconds = Long.parseLong(header);
            return secondsToMillis(seconds);
        } catch (NumberFormatException notSeconds) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(
                        "EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
                format.setLenient(false);
                format.setTimeZone(TimeZone.getTimeZone("GMT"));
                Date retryAt = format.parse(header.trim());
                return retryAt == null || retryAt.getTime() <= nowMs
                        ? 0L : retryAt.getTime() - nowMs;
            } catch (Exception invalidDate) {
                return 0L;
            }
        }
    }

    private static long secondsToMillis(long seconds) {
        if (seconds <= 0L) return 0L;
        return seconds > Long.MAX_VALUE / 1000L ? Long.MAX_VALUE : seconds * 1000L;
    }

    private static String enc(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    public static final class DeviceCode {
        public final String deviceAuthId;
        public final String userCode;
        public final long intervalSeconds;
        public final long expiresInMs;
        private DeviceCode(String authId, String code, long interval, long expires) {
            deviceAuthId = authId; userCode = code; intervalSeconds = interval; expiresInMs = expires;
        }
    }

    public static final class Tokens {
        public final String accessToken, refreshToken, idToken, accountId, email, workspaceId;
        public final long expiresAt;
        private Tokens(String access, String refresh, String id, long expiry,
                       String account, String emailValue, String workspace) {
            accessToken = access; refreshToken = refresh; idToken = id; expiresAt = expiry;
            accountId = account; this.email = emailValue; workspaceId = workspace;
        }
        public String toCredentialPayload() throws AuthException {
            return CredentialPayload.forOAuth(accessToken, refreshToken, idToken, expiresAt,
                    accountId, email, workspaceId);
        }
        public com.aiusage.monitor.provider.AuthContext toAuthContext() {
            java.util.Map<String, String> values = new java.util.HashMap<>();
            values.put(com.aiusage.monitor.provider.AuthContext.KEY_ACCESS_TOKEN, accessToken);
            values.put(com.aiusage.monitor.provider.AuthContext.KEY_REFRESH_TOKEN, refreshToken);
            values.put(com.aiusage.monitor.provider.AuthContext.KEY_ID_TOKEN, idToken);
            values.put(com.aiusage.monitor.provider.AuthContext.KEY_EXPIRES_AT, String.valueOf(expiresAt));
            values.put(com.aiusage.monitor.provider.AuthContext.KEY_OAUTH_ACCOUNT_ID, accountId);
            values.put(com.aiusage.monitor.provider.AuthContext.KEY_OAUTH_EMAIL, email);
            values.put(com.aiusage.monitor.provider.AuthContext.KEY_OAUTH_WORKSPACE, workspaceId);
            return com.aiusage.monitor.provider.AuthContext.of(AuthType.OAUTH, values);
        }
        public String identityHash() {
            String identity = accountId + "\n" + workspaceId + "\n" + email;
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(identity.getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder(digest.length * 2);
                for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
                return hex.toString();
            } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
    }

    public static final class Profile {
        public final String accountId, email, workspaceId;
        private Profile(String account, String mail, String workspace) {
            accountId = account; email = mail; workspaceId = workspace;
        }
        static Profile fromIdToken(String idToken) {
            try {
                String[] parts = idToken.split("\\.");
                if (parts.length < 2) return new Profile("", "", "");
                JSONObject claims = new JSONObject(new String(Base64.decode(parts[1], Base64.URL_SAFE
                        | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8));
                JSONObject auth = claims.optJSONObject("https://api.openai.com/auth");
                if (auth == null) auth = new JSONObject();
                String account = auth.optString("chatgpt_account_id", "");
                String workspace = auth.optString("chatgpt_workspace_id", account);
                return new Profile(account, claims.optString("email", ""), workspace);
            } catch (Exception invalid) { return new Profile("", "", ""); }
        }
    }

    public interface Cancellation { boolean isCancelled(); }
    public interface Clock { long nowMs(); }
    public interface Sleeper { void sleep(long ms) throws InterruptedException; }
}

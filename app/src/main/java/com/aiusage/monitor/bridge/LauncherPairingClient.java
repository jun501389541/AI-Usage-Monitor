package com.aiusage.monitor.bridge;

import com.aiusage.monitor.model.Bridge;
import com.aiusage.monitor.util.Http;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import javax.net.ssl.HttpsURLConnection;

/** Pins the invitation certificate and waits for local approval before storing a device. */
public final class LauncherPairingClient {
    public interface Transport {
        Http.Response request(String endpoint, String path, String method, String body,
                              String bearer, FingerprintPin pin) throws IOException;
    }
    public interface Waiter { void pause() throws InterruptedException; }
    private final Transport transport;
    private final Waiter waiter;
    private final PairingClient.Clock clock;
    private final AddressResolver addresses;

    public LauncherPairingClient(AddressResolver addresses) {
        this(LauncherPairingClient::request, () -> Thread.sleep(1000),
                System::currentTimeMillis, addresses);
    }

    public LauncherPairingClient(Transport transport, Waiter waiter, PairingClient.Clock clock,
                                 AddressResolver addresses) {
        this.transport = transport; this.waiter = waiter; this.clock = clock;
        this.addresses = addresses == null ? AddressResolver.REAL_DEVICE : addresses;
    }

    public PairingClient.Paired pair(LauncherInvitation invite, String deviceName,
                                     Consumer<String> progress) throws Exception {
        if (clock.nowMs() >= invite.expiresAt) {
            throw new IOException("二维码已过期，请在电脑端重新生成。");
        }
        String endpoint = addresses.baseUrl(invite.endpoint);
        FingerprintPin pin = new FingerprintPin(invite.certificatePin);
        String body = new JSONObject().put("pairToken", invite.pairToken)
                .put("deviceName", deviceName).toString();
        Http.Response response = transport.request(endpoint, "/v1/pair", "POST", body, "", pin);
        requireSuccess(response);
        if (response.getCode() != 202) throw new IOException("电脑端未接受配对申请。");
        JSONObject accepted = new JSONObject(response.getBody());
        String pairId = accepted.getString("pairId");
        java.util.UUID.fromString(pairId);
        String session = accepted.getString("sessionToken");
        requireToken(session);
        String path = "/v1/pair/" + pairId;
        long deadline = Math.min(clock.nowMs() + 5 * 60_000,
                java.time.Instant.parse(accepted.getString("expiresAt")).toEpochMilli());
        progress.accept("请在电脑端批准这台手机的连接申请，批准后会自动完成配对。");
        while (clock.nowMs() < deadline) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            Http.Response statusResponse = transport.request(endpoint, path, "GET", "", session, pin);
            requireSuccess(statusResponse);
            JSONObject status = new JSONObject(statusResponse.getBody());
            if (!pairId.equals(status.getString("pairId"))) throw new IOException("电脑端配对应答不匹配。");
            String state = status.getString("state");
            if ("Approved".equals(state)) {
                String deviceId = status.getString("deviceId");
                java.util.UUID.fromString(deviceId);
                String token = status.getString("deviceToken");
                requireToken(token);
                Http.Response accountsResponse = transport.request(endpoint, "/v1/accounts",
                        "GET", "", token, pin);
                requireSuccess(accountsResponse);
                JSONArray accounts = new JSONArray(accountsResponse.getBody());
                JSONObject account = null;
                for (int i = 0; i < accounts.length(); i++) {
                    JSONObject candidate = accounts.getJSONObject(i);
                    if ("codex".equals(candidate.optString("providerId"))) {
                        if (account != null) throw new IOException("电脑授权了多个 Codex 账户，请仅授权一个账户再配对。");
                        account = candidate;
                    }
                }
                if (account == null) throw new IOException("电脑尚未授权 Codex 账户，请登录 Codex、开启额度监测后重新配对。");
                String accountId = account.getString("accountId");
                if (!accountId.matches("[0-9a-f]{64}")) throw new IOException("电脑端账户标识无效。");
                Bridge bridge = Bridge.builder().id(invite.bridgeId).name("CodexLauncher")
                        .baseUrl(endpoint).fingerprint(pin.value()).addedAt(clock.nowMs())
                        .lastSeen(clock.nowMs()).build();
                return new PairingClient.Paired(bridge, deviceId, token, accountId,
                        account.optString("displayName", "Codex 账户"), () -> {
                    IOException last = null;
                    for (int attempt = 0; attempt < 3; attempt++) {
                        try {
                            requireSuccess(transport.request(endpoint, path + "/ack", "POST", "", session, pin));
                            return;
                        } catch (IOException failed) { last = failed; }
                    }
                    throw last;
                });
            }
            if ("Rejected".equals(state)) throw new IOException("电脑端拒绝了连接，请重新生成二维码再试。");
            if ("Expired".equals(state)) throw new IOException("配对申请已过期，请重新扫码。");
            if (!"PendingApproval".equals(state)) throw new IOException("电脑端配对状态无效。");
            waiter.pause();
        }
        throw new IOException("等待电脑批准超时，请在电脑端重新生成二维码。");
    }

    private static void requireToken(String token) throws IOException {
        if (!token.matches("[A-Za-z0-9_-]{43}")) throw new IOException("电脑端返回的配对令牌无效。");
    }

    private static void requireSuccess(Http.Response response) throws IOException {
        int code = response.getCode();
        if (code >= 200 && code < 300) return;
        if (code == 410) throw new IOException("配对内容已过期，请在电脑端重新生成二维码。");
        if (code == 409) throw new IOException("二维码已使用或配对状态已变化，请重新生成二维码。");
        if (code == 429) throw new IOException("配对请求太频繁，请稍后重试。");
        if (code == 401 || code == 403) throw new IOException("电脑端未授权这台手机，请重新配对并在电脑批准。");
        throw new IOException("电脑端配对请求失败（HTTP " + code + "）。");
    }

    private static Http.Response request(String endpoint, String path, String method,
                                          String body, String bearer, FingerprintPin pin) throws IOException {
        HttpsURLConnection connection = BridgeTls.open(endpoint, path, pin);
        try {
            connection.setRequestMethod(method);
            if (!bearer.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + bearer);
            if ("POST".equals(method)) {
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setDoOutput(true);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (stream != null) {
                try (InputStream input = stream) {
                    byte[] chunk = new byte[4096];
                    int count;
                    while ((count = input.read(chunk)) != -1) {
                        if (buffer.size() + count > 1024 * 1024) throw new IOException("电脑端响应过大。");
                        buffer.write(chunk, 0, count);
                    }
                }
            }
            return new Http.Response(code, new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        } finally { connection.disconnect(); }
    }
}

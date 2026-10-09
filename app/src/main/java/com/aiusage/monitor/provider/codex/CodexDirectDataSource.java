package com.aiusage.monitor.provider.codex;

import com.aiusage.monitor.model.UsageError;
import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.provider.UsageException;
import com.aiusage.monitor.util.BridgeTransport;
import com.aiusage.monitor.util.Http;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Experimental phone-side reader for the private Codex Wham usage endpoint. */
public final class CodexDirectDataSource {
    public static final String USAGE_URL = "https://chatgpt.com/backend-api/wham/usage";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;
    private final BridgeTransport transport;

    public CodexDirectDataSource(BridgeTransport transport) {
        this.transport = transport;
    }

    public UsageResult fetch(String accessToken, String accountId, long nowMs)
            throws UsageException {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new UsageException(UsageError.INVALID_CREDENTIAL, "Codex OAuth 访问令牌为空");
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + accessToken);
        headers.put("Accept", "application/json");
        headers.put("User-Agent", "AI-Usage-Monitor-Android");
        if (accountId != null && !accountId.isEmpty()) {
            headers.put("ChatGPT-Account-Id", accountId);
        }
        final Http.Response response;
        try {
            response = transport.get(USAGE_URL, headers, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
        } catch (IOException network) {
            // Error text deliberately omits request headers and response content.
            throw new UsageException(UsageError.NETWORK_ERROR,
                    "无法连接 Codex 额度服务", network);
        }
        if (response.getCode() == 401 || response.getCode() == 403) {
            throw new UsageException(UsageError.AUTH_EXPIRED,
                    "Codex 手机授权已失效，需要重新授权");
        }
        if (response.getCode() == 429) {
            throw new UsageException(UsageError.NETWORK_ERROR,
                    "Codex 额度接口暂时限流，请稍后重试");
        }
        if (!response.isSuccess()) {
            throw new UsageException(UsageError.NETWORK_ERROR,
                    "Codex 额度接口暂时不可用（HTTP " + response.getCode() + "）");
        }
        try {
            return CodexDirectUsageParser.parse(new JSONObject(response.getBody()), "", nowMs);
        } catch (JSONException malformed) {
            throw new UsageException(UsageError.UNKNOWN,
                    "Codex 额度响应无法识别", malformed);
        }
    }
}

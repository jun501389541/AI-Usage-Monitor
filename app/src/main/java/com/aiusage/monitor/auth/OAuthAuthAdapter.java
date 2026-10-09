package com.aiusage.monitor.auth;

import com.aiusage.monitor.model.UsageError;
import com.aiusage.monitor.provider.AuthContext;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/** Converts a decrypted OAuth credential into the short-lived Codex request context. */
public final class OAuthAuthAdapter implements AuthAdapter {
    @Override public AuthType getAuthType() { return AuthType.OAUTH; }

    @Override public AuthContext adapt(String decryptedPayload) throws AuthException {
        JSONObject json = parse(decryptedPayload);
        Map<String, String> values = new HashMap<>();
        values.put(AuthContext.KEY_ACCESS_TOKEN,
                CredentialPayload.extractOAuthAccessToken(decryptedPayload));
        values.put(AuthContext.KEY_REFRESH_TOKEN,
                CredentialPayload.extractOAuthRefreshToken(decryptedPayload));
        values.put(AuthContext.KEY_ID_TOKEN, json.optString("idToken", ""));
        values.put(AuthContext.KEY_EXPIRES_AT,
                String.valueOf(json.optLong("expiresAt", 0L)));
        values.put(AuthContext.KEY_OAUTH_ACCOUNT_ID, json.optString("oauthAccountId", ""));
        values.put(AuthContext.KEY_OAUTH_EMAIL, json.optString("oauthEmail", ""));
        values.put(AuthContext.KEY_OAUTH_WORKSPACE, json.optString("oauthWorkspace", ""));
        return AuthContext.of(AuthType.OAUTH, values);
    }

    @Override public void validate(String decryptedPayload) throws AuthException {
        CredentialPayload.extractOAuthAccessToken(decryptedPayload);
        CredentialPayload.extractOAuthRefreshToken(decryptedPayload);
    }

    private static JSONObject parse(String payload) throws AuthException {
        try {
            return new JSONObject(payload);
        } catch (Exception invalid) {
            throw new AuthException(UsageError.INVALID_CREDENTIAL,
                    "OAuth 凭据格式无效", invalid);
        }
    }
}

package com.aiusage.monitor.auth;

import com.aiusage.monitor.model.UsageError;
import com.aiusage.monitor.provider.AuthContext;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns a static API key into an {@link AuthContext}. Spec §8.
 *
 * <p>Kept separate from the provider on purpose: authentication and usage
 * retrieval are independent concerns, and a provider must not be responsible
 * for how a secret is stored or refreshed. Spec §53 rule 22.
 *
 * <p>Pure and Android-free, so it is unit-testable on a plain JVM.
 */
public final class ApiKeyAuthAdapter implements AuthAdapter {

    @Override
    public AuthType getAuthType() {
        return AuthType.API_KEY;
    }

    @Override
    public AuthContext adapt(String decryptedPayload) throws AuthException {
        String apiKey = CredentialPayload.extractApiKey(decryptedPayload);
        Map<String, String> values = new HashMap<>();
        values.put(AuthContext.KEY_API_KEY, apiKey);
        return AuthContext.of(AuthType.API_KEY, values);
    }

    /**
     * Checks that a pasted key is at least plausibly a DeepSeek key, so that an
     * obvious typo is reported before a network round trip. Deliberately
     * permissive: the platform is the authority on what it accepts, and this
     * must not reject a valid key of an unforeseen shape.
     */
    @Override
    public void validate(String decryptedPayload) throws AuthException {
        String apiKey = CredentialPayload.extractApiKey(decryptedPayload);
        if (apiKey.length() < 8) {
            throw new AuthException(UsageError.INVALID_CREDENTIAL, "API Key 长度不足");
        }
        if (apiKey.contains(" ") || apiKey.contains("\n")) {
            throw new AuthException(UsageError.INVALID_CREDENTIAL, "API Key 含有空白字符");
        }
    }

    /**
     * Renders a key for display as {@code sk-abc…xyz}. Used so the account edit
     * screen can show which key is stored without revealing it, and so a log or
     * a screenshot cannot leak one. Spec §50.
     */
    public static String mask(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return "";
        }
        if (apiKey.length() <= 10) {
            return "…";
        }
        return apiKey.substring(0, 6) + "…" + apiKey.substring(apiKey.length() - 4);
    }

    /** Convenience for a payload object; never renders the secret. */
    static String describe(String decryptedPayload) {
        try {
            JSONObject json = new JSONObject(decryptedPayload);
            return mask(json.optString("apiKey", ""));
        } catch (Exception exception) {
            return "";
        }
    }
}

package com.aiusage.monitor.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aiusage.monitor.provider.AuthContext;

import org.junit.Test;

public final class CredentialPayloadOAuthTest {
    @Test public void roundTripsOAuthMaterialWithoutExposingSecretsInContextString() throws Exception {
        String payload = CredentialPayload.forOAuth(
                "access-secret", "refresh-secret", "id-secret", 123456L,
                "chatgpt-account", "user@example.com");

        AuthContext context = new OAuthAuthAdapter().adapt(payload);

        assertEquals("access-secret", context.get(AuthContext.KEY_ACCESS_TOKEN));
        assertEquals("refresh-secret", context.get(AuthContext.KEY_REFRESH_TOKEN));
        assertEquals("id-secret", context.get(AuthContext.KEY_ID_TOKEN));
        assertEquals("123456", context.get(AuthContext.KEY_EXPIRES_AT));
        assertEquals("chatgpt-account", context.get(AuthContext.KEY_OAUTH_ACCOUNT_ID));
        assertEquals("user@example.com", context.get(AuthContext.KEY_OAUTH_EMAIL));
        assertFalse(context.toString().contains("access-secret"));
        assertFalse(context.toString().contains("refresh-secret"));
    }

    @Test public void rejectsMissingRefreshToken() throws Exception {
        try {
            CredentialPayload.forOAuth("access-secret", "", "", 123456L, "", "");
        } catch (AuthException expected) {
            assertTrue(expected.getMessage().contains("OAuth"));
            return;
        }
        throw new AssertionError("OAuth credentials must be refreshable");
    }
}

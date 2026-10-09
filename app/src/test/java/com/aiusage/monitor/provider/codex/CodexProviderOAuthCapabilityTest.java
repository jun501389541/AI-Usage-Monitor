package com.aiusage.monitor.provider.codex;

import static org.junit.Assert.assertTrue;

import com.aiusage.monitor.auth.AuthType;
import org.junit.Test;

/** Codex exposes an opt-in OAuth data source alongside its existing Bridge source. */
public class CodexProviderOAuthCapabilityTest {

    @Test
    public void declaresOAuthAsASupportedAuthenticationType() {
        assertTrue(new CodexProvider().getSupportedAuthTypes().contains(AuthType.OAUTH));
    }
}

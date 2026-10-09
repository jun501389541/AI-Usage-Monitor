package com.aiusage.monitor.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.aiusage.monitor.auth.AuthType;

import org.junit.Test;

public final class CredentialProtectionPolicyTest {
    @Test public void rejectsDegradedOAuthStorage() {
        try {
            CredentialProtectionPolicy.requireAcceptable(AuthType.OAUTH,
                    SecureStorage.PROTECTION_DEGRADED);
            fail("OAuth must never fall back to degraded local protection");
        } catch (IllegalStateException expected) {
            assertEquals("OAUTH credentials require Android Keystore protection", expected.getMessage());
        }
    }

    @Test public void preservesExistingProtectionBehaviorForOtherCredentialTypes() {
        CredentialProtectionPolicy.requireAcceptable(AuthType.BRIDGE_TOKEN,
                SecureStorage.PROTECTION_DEGRADED);
    }
}

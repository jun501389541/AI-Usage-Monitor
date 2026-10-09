package com.aiusage.monitor.storage;

import com.aiusage.monitor.auth.AuthType;

/** Enforces the stronger at-rest requirement for refreshable OAuth credentials. */
public final class CredentialProtectionPolicy {
    private CredentialProtectionPolicy() { }

    public static void requireAcceptable(AuthType type, String protection) {
        if (type == AuthType.OAUTH
                && SecureStorage.PROTECTION_DEGRADED.equals(protection)) {
            throw new IllegalStateException(
                    "OAUTH credentials require Android Keystore protection");
        }
    }
}

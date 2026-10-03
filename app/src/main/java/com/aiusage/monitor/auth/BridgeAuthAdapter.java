package com.aiusage.monitor.auth;

import com.aiusage.monitor.model.UsageError;
import com.aiusage.monitor.provider.AuthContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns a Bridge device token into an {@link AuthContext}. Spec §8.
 *
 * <p>The token is the only secret; the base URL travels with it for
 * configuration convenience and is not treated as a credential.
 *
 * <p>Like {@link ApiKeyAuthAdapter} this is pure and Android-free, so the
 * payload rules can be tested without a device.
 */
public final class BridgeAuthAdapter implements AuthAdapter {

    @Override
    public AuthType getAuthType() {
        return AuthType.BRIDGE_TOKEN;
    }

    @Override
    public AuthContext adapt(String decryptedPayload) throws AuthException {
        Map<String, String> values = new HashMap<>();
        values.put(AuthContext.KEY_DEVICE_TOKEN,
                CredentialPayload.extractOptionalDeviceToken(decryptedPayload));
        values.put(AuthContext.KEY_BRIDGE_URL, CredentialPayload.extractBridgeUrl(decryptedPayload));
        return AuthContext.of(AuthType.BRIDGE_TOKEN, values);
    }

    /**
     * Checks the payload before it is stored. No network request happens here:
     * probing the Bridge would mean connecting to a host the user has just typed,
     * before anything authorises that. Spec §53 rule 22 keeps authentication and
     * usage retrieval separate for exactly this reason.
     */
    @Override
    public void validate(String decryptedPayload) throws AuthException {
        // The address is the part that can be wrong in a way the user can fix
        // while still looking at the field. A missing token is legitimate today,
        // and no network request happens here: probing the host the user just typed
        // would connect somewhere before anything authorises it. Spec §53 rule 22.
        CredentialPayload.extractBridgeUrl(decryptedPayload);
        CredentialPayload.extractOptionalDeviceToken(decryptedPayload);
    }
}

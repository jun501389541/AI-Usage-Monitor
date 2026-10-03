package com.aiusage.monitor.auth;

import com.aiusage.monitor.provider.AuthContext;

/**
 * Converts a decrypted credential payload into an {@link AuthContext} for one
 * fetch. Spec §8.
 *
 * <p>One adapter per authentication mechanism, selected by {@link AuthType}
 * rather than by provider. That separation is what lets a single provider accept
 * several mechanisms later — the spec's example is a Codex provider that works
 * both through a direct ChatGPT login and through a local bridge. Spec §3, §8.
 */
public interface AuthAdapter {

    /** The mechanism this adapter handles. */
    AuthType getAuthType();

    /**
     * Builds a context for one fetch.
     *
     * @param decryptedPayload the plaintext payload, valid only for this call
     * @throws AuthException when the payload is missing, malformed, or expired
     */
    AuthContext adapt(String decryptedPayload) throws AuthException;

    /**
     * Checks a payload before it is stored, so an obviously wrong key is caught
     * while the user is still looking at the field. Implementations must not
     * perform a network request here.
     */
    void validate(String decryptedPayload) throws AuthException;
}

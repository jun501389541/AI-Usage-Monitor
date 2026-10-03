package com.aiusage.monitor.auth;

import com.aiusage.monitor.provider.AuthContext;

/**
 * Reads and writes secrets. Spec §7, §45 ({@code auth/CredentialStore}).
 *
 * <p>This is the only interface through which credentials move, and it is
 * deliberately the narrowest one in the codebase: it stores, retrieves and
 * deletes, and it hands back a decrypted {@link AuthContext} for a single fetch.
 * A provider never sees it, and neither does the widget. Spec §53 rules 9, 11.
 *
 * <p>Implementations must never write plaintext, must never log a payload, and
 * must never place one in a QR code or an Intent extra. Spec §7, §50.
 */
public interface CredentialStore {

    /**
     * Stores a new credential.
     *
     * @param type    how the secret authenticates
     * @param payload plaintext payload in the shape the spec fixes for this
     *                type, for example {@code {"apiKey":"…"}}
     * @return the generated credential id
     */
    String create(AuthType type, String payload) throws AuthException;

    /**
     * Replaces the secret behind an existing credential, keeping its id and its
     * creation time. Used when a user pastes a new key for an existing account,
     * which must not change the account's identity or lose its history.
     * Spec §14.
     */
    void update(String credentialId, String payload) throws AuthException;

    /**
     * Decrypts a credential for immediate use.
     *
     * @return a context valid for one fetch; the caller must not retain it
     */
    AuthContext open(String credentialId, AuthType type) throws AuthException;

    /** Removes a credential. Missing ids are ignored. */
    void delete(String credentialId);

    /** True when the credential exists and can be decrypted. */
    boolean isUsable(String credentialId);
}

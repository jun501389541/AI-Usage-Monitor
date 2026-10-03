package com.aiusage.monitor.auth;

/**
 * A secret belonging to an account, stored separately from the account itself.
 * Spec §7.
 *
 * <p>Sensitive material never lives in {@code Account}; an account only carries
 * a {@code credentialId}. The payload itself is opaque here — it is produced by
 * {@link CredentialStore} and only ever decrypted into an {@code AuthContext}
 * for the duration of a fetch.
 *
 * <p>Structure mirrors the spec exactly: {@code id / type / encryptedPayload /
 * createdAt / updatedAt}.
 */
public final class Credential {

    private final String id;
    private final AuthType type;
    private final String encryptedPayload;
    private final long createdAt;
    private final long updatedAt;

    public Credential(String id,
                      AuthType type,
                      String encryptedPayload,
                      long createdAt,
                      long updatedAt) {
        this.id = id == null ? "" : id;
        this.type = type == null ? AuthType.CUSTOM : type;
        this.encryptedPayload = encryptedPayload == null ? "" : encryptedPayload;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public AuthType getType() {
        return type;
    }

    /**
     * Ciphertext, never plaintext. See {@link CredentialStore} for the
     * encryption contract.
     */
    public String getEncryptedPayload() {
        return encryptedPayload;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Renders as a redacted placeholder so that an accidental log statement or
     * crash report can never leak the payload. Spec §50 items 1–3.
     */
    @Override
    public String toString() {
        return "Credential{id=" + id + ", type=" + type + ", payload=<redacted>}";
    }
}

package com.aiusage.monitor.auth;

/**
 * Thrown when a credential cannot be turned into a usable
 * {@link com.aiusage.monitor.provider.AuthContext}. Spec §49.
 *
 * <p>Kept separate from {@code UsageException} so the auth layer does not depend
 * on the provider layer — authentication and usage retrieval are independent
 * concerns by design. Spec §53 rule 22.
 */
public class AuthException extends Exception {

    private static final long serialVersionUID = 1L;

    private final com.aiusage.monitor.model.UsageError error;

    public AuthException(com.aiusage.monitor.model.UsageError error, String detail) {
        super(detail);
        this.error = error == null ? com.aiusage.monitor.model.UsageError.UNKNOWN : error;
    }

    public AuthException(com.aiusage.monitor.model.UsageError error, String detail, Throwable cause) {
        super(detail, cause);
        this.error = error == null ? com.aiusage.monitor.model.UsageError.UNKNOWN : error;
    }

    public com.aiusage.monitor.model.UsageError getError() {
        return error;
    }
}

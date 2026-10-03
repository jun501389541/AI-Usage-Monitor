package com.aiusage.monitor.provider;

import com.aiusage.monitor.model.UsageError;

/**
 * Thrown by a provider when a fetch fails. Spec §49.
 *
 * <p>Carries a normalised {@link UsageError} rather than a platform-specific
 * message, so that the UI and the widget can render consistent text without
 * knowing which platform produced the failure. Raw provider errors must never
 * reach the widget. Spec §49 / §53 rule 23.
 */
public class UsageException extends Exception {

    private static final long serialVersionUID = 1L;

    private final UsageError error;

    public UsageException(UsageError error) {
        this(error, error == null ? null : error.getMessage());
    }

    public UsageException(UsageError error, String detail) {
        super(detail);
        this.error = error == null ? UsageError.UNKNOWN : error;
    }

    public UsageException(UsageError error, String detail, Throwable cause) {
        super(detail, cause);
        this.error = error == null ? UsageError.UNKNOWN : error;
    }

    public UsageError getError() {
        return error;
    }
}

package com.aiusage.monitor.model;

/**
 * Lifecycle state of a usage result. Spec §40.
 *
 * <p>The distinction between {@link #BRIDGE_OFFLINE} and {@link #AUTH_REQUIRED}
 * is load-bearing: "the computer is off" and "you need to sign in again" are
 * different problems with different fixes, and the widget must not conflate
 * them. Spec §53 rule 19.
 */
public enum UsageStatus {

    /** Fresh data was fetched successfully. */
    OK,

    /** A refresh is in flight; the previously known data is still valid. */
    REFRESHING,

    /** Last success is older than the freshness threshold. */
    STALE,

    /** The network request failed. */
    NETWORK_ERROR,

    /** The data source (for example a Windows bridge) is unreachable. */
    BRIDGE_OFFLINE,

    /** Credentials are missing, expired, or rejected. */
    AUTH_REQUIRED,

    /**
     * A Bridge account's device token was refused. Kept separate from
     * {@link #AUTH_REQUIRED} because that state's wording names an API key, and a
     * Codex account has none — telling it "API Key 无效" reports a credential the
     * account does not own (docs/PHASE-6-PLAN.md D2).
     */
    BRIDGE_AUTH_REQUIRED,

    /**
     * A Bridge account that has no pairing to read through: the computer's row is
     * gone, or the account predates pairing entirely. Separate from
     * {@link #BRIDGE_AUTH_REQUIRED} because that one means the computer answered
     * "no" to a token — the fix there is to pair again, the fix here is to pair at
     * all, and neither is "the computer is offline" (Spec §53 rule 19, and
     * docs/PHASE-7-PLAN.md step 9).
     */
    BRIDGE_PAIRING_REQUIRED,

    /** Nothing has ever been fetched for this account. */
    NO_DATA
}

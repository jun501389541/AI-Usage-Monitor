package com.aiusage.monitor.auth;

/**
 * How an account proves who it is. Spec §7.
 *
 * <p>Kept separate from the provider on purpose: the spec forbids modelling
 * "DeepSeek = API key" or "Codex = bridge". One provider may support several
 * authentication types, so the pair (provider, auth type) is what selects an
 * {@link AuthAdapter}.
 */
public enum AuthType {

    /** A static API key or token pasted by the user. */
    API_KEY,

    /** A long-lived device token issued by a local bridge. */
    BRIDGE_TOKEN,

    /** An OAuth access/refresh token pair. */
    OAUTH,

    /** A browser session cookie. */
    COOKIE,

    /** Anything provider-specific that does not fit the above. */
    CUSTOM
}

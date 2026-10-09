package com.aiusage.monitor.provider;

import com.aiusage.monitor.auth.AuthType;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The decrypted secret handed to a provider for exactly one fetch. Spec §8.
 *
 * <p>This is the only door through which a provider sees credentials, and it is
 * deliberately narrow: a provider receives the material it needs to build a
 * request and nothing else. It cannot read the credential store, cannot write
 * one, and cannot enumerate other accounts' secrets. Spec §4 ("provider is not
 * responsible for how tokens are stored or refreshed").
 */
public final class AuthContext {

    /** Key name for a static API key payload, for example {@code {"apiKey":"sk-…"}}. */
    public static final String KEY_API_KEY = "apiKey";

    /** Key name for an OAuth access token. */
    public static final String KEY_ACCESS_TOKEN = "accessToken";

    /** Key name for an OAuth refresh token. */
    public static final String KEY_REFRESH_TOKEN = "refreshToken";

    /** Key name for an OAuth expiry, epoch millis. */
    public static final String KEY_EXPIRES_AT = "expiresAt";

    public static final String KEY_ID_TOKEN = "idToken";
    public static final String KEY_OAUTH_ACCOUNT_ID = "oauthAccountId";
    public static final String KEY_OAUTH_EMAIL = "oauthEmail";
    public static final String KEY_OAUTH_WORKSPACE = "oauthWorkspace";

    /** Key name for a bridge device token. */
    public static final String KEY_DEVICE_TOKEN = "deviceToken";

    /**
     * Key name for the Bridge base URL the device token belongs to. Not a secret,
     * but stored with one: an address only makes sense next to the token that
     * authorises it, and Spec §54 forbids treating an address as a Bridge identity
     * the way {@code Account.bridgeId} is meant to.
     */
    public static final String KEY_BRIDGE_URL = "bridgeUrl";

    /**
     * Key name for the certificate digest this connection must be pinned to: hex
     * SHA-256 of the Bridge's SubjectPublicKeyInfo, from its {@code bridges} row.
     *
     * <p>Not a secret — the Bridge advertises the same digest on its plaintext health
     * endpoint and prints it for a human to compare (A11) — but it is what turns the
     * address above from "somebody answered" into "the computer I paired with
     * answered". An empty value means the account was typed in by hand and has no
     * pairing, which is Phase 6's behaviour and stays plaintext.
     */
    public static final String KEY_BRIDGE_PIN = "bridgePin";
    public static final String KEY_REMOTE_ACCOUNT_ID = "remoteAccountId";

    private final AuthType authType;
    private final Map<String, String> values;

    private AuthContext(AuthType authType, Map<String, String> values) {
        this.authType = authType;
        this.values = Collections.unmodifiableMap(new HashMap<>(values));
    }

    public AuthType getAuthType() {
        return authType;
    }

    /** Returns the named secret, or empty string when absent. Never null. */
    public String get(String key) {
        String value = values.get(key);
        return value == null ? "" : value;
    }

    /** True when every named key is present and non-empty. */
    public boolean has(String... keys) {
        for (String key : keys) {
            if (get(key).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Convenience for the common single-secret case. */
    public static AuthContext ofApiKey(String apiKey) {
        Map<String, String> map = new HashMap<>();
        map.put(KEY_API_KEY, apiKey == null ? "" : apiKey);
        return new AuthContext(AuthType.API_KEY, map);
    }

    /** Builds a context from arbitrary named values. */
    public static AuthContext of(AuthType type, Map<String, String> values) {
        return new AuthContext(type, values == null ? new HashMap<String, String>() : values);
    }

    /**
     * Never renders the secret material — an accidental log statement must not
     * be able to leak a key. Spec §50 items 1–3.
     */
    @Override
    public String toString() {
        return "AuthContext{type=" + authType + ", keys=" + values.keySet() + "}";
    }
}

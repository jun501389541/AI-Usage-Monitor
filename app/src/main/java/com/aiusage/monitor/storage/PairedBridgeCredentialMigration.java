package com.aiusage.monitor.storage;

import com.aiusage.monitor.account.AccountManager;
import com.aiusage.monitor.auth.AuthException;
import com.aiusage.monitor.auth.CredentialPayload;
import com.aiusage.monitor.bridge.BridgeRepository;
import com.aiusage.monitor.model.Account;
import com.aiusage.monitor.model.Bridge;
import com.aiusage.monitor.provider.AuthContext;
import com.aiusage.monitor.provider.codex.CodexProvider;

/** Removes the duplicated Bridge URL from credentials of accounts backed by a bridge row. */
public final class PairedBridgeCredentialMigration {

    private final AccountManager accounts;
    private final BridgeRepository bridges;

    public PairedBridgeCredentialMigration(AccountManager accounts, BridgeRepository bridges) {
        this.accounts = accounts;
        this.bridges = bridges;
    }

    /**
     * Rewrites eligible payloads in place. This is idempotent: token-only
     * credentials are already migrated, while hand-configured accounts have no
     * bridge id and continue keeping their URL in the credential.
     */
    public synchronized void runIfNeeded() {
        for (Account account : accounts.list()) {
            if (!CodexProvider.ID.equals(account.getProviderId())
                    || isEmpty(account.getBridgeId())
                    || isEmpty(account.getCredentialId())) {
                continue;
            }

            // Keep the old payload intact if the paired row is missing. The row,
            // not the credential, is authoritative for where this account reads.
            Bridge bridge = bridges.findById(account.getBridgeId());
            if (bridge == null) {
                continue;
            }

            try {
                AuthContext saved = accounts.openCredential(account);
                if (isEmpty(saved.get(AuthContext.KEY_BRIDGE_URL))) {
                    continue;
                }
                String token = saved.get(AuthContext.KEY_DEVICE_TOKEN);
                if (isEmpty(token)) {
                    continue;
                }
                accounts.replaceCredential(account.getId(), CredentialPayload.forDeviceToken(token));
            } catch (AuthException ignored) {
                // A damaged or temporarily unavailable secret must not prevent
                // the rest of the app from starting; the legacy payload remains.
            }
        }
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }
}

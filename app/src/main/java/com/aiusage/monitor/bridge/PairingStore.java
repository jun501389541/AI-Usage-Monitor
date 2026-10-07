package com.aiusage.monitor.bridge;

import com.aiusage.monitor.account.AccountManager;
import com.aiusage.monitor.auth.AuthException;
import com.aiusage.monitor.auth.AuthType;
import com.aiusage.monitor.auth.CredentialPayload;
import com.aiusage.monitor.model.Account;
import com.aiusage.monitor.model.Bridge;

/**
 * Turns a finished pairing into the two rows the app reads from afterwards: the
 * computer in {@code bridges}, and a {@code BRIDGE_TOKEN} account pointed at it.
 * Phase 7 step 9, plan §3.2 ({@code BridgeRepository} plus decision A9).
 *
 * <p>Why a class rather than code in the pairing screen: the same write happens from
 * 「配对新电脑」, from the account editor's Codex path, and from 「重新配对」 on a row whose
 * token was revoked — three entry points, one order of operations.
 *
 * <p>What matters is not which write comes first but that <em>every</em> write sits
 * inside the region that can undo the others. The secret goes in first because the
 * keystore can be refused by the platform at any moment while a row of this app's own
 * database is the reliable part; the computer's row is written next, and a failure at
 * either step rolls back the account this call created and the row it created or
 * changed. So a failed pairing leaves either both rows or neither — never an account
 * whose {@code bridge_id} points at nothing, which would read as 「需要重新配对」 forever
 * with a valid token in its credential. That last state is precisely what an earlier
 * version of this class produced: its doc promised the pairing was atomic, and its
 * {@code bridges.save(...)} sat <em>after</em> the try block. Review, 2026-10-03.
 *
 * <p>The check that runs before any writing is the fingerprint. A re-pairing that
 * presents a different digest under the same Bridge ID is not "the computer renewed
 * its certificate" — A2 makes the key the identity — so it is refused and left for a
 * human to resolve, rather than re-pinning a machine the user never agreed to trust.
 */
public final class PairingStore {

    /** A pairing that succeeded over the wire and could not be recorded. */
    public static final class NotStored extends Exception {
        public NotStored(String message) {
            super(message);
        }
    }

    /** What was written. */
    public static final class Stored {
        private final Bridge bridge;
        private final Account account;

        Stored(Bridge bridge, Account account) {
            this.bridge = bridge;
            this.account = account;
        }

        public Bridge bridge() {
            return bridge;
        }

        public Account account() {
            return account;
        }
    }

    private final BridgeRepository bridges;
    private final AccountManager accounts;

    public PairingStore(BridgeRepository bridges, AccountManager accounts) {
        this.bridges = bridges;
        this.accounts = accounts;
    }

    /** Records a pairing as a new account of {@code providerId}. */
    public Stored record(PairingClient.Paired paired, String providerId, String accountName)
            throws NotStored, AuthException {
        Bridge row = decideRow(paired.bridge());
        Bridge previous = bridges.findById(row.getId());
        Account created = accounts.createAccount(providerId, accountName, AuthType.BRIDGE_TOKEN);
        try {
            // The row first, and inside the same protected region as the secret. It used
            // to be written after the try, so a locked or full bridges table left an
            // account holding a fresh device token and pointing at nothing - the exact
            // state this class promises never to leave, because it reads as
            // 「还没有与这台电脑配对」 forever with a valid credential in the keystore.
            bridges.save(row);
            apply(row, created.getId(), paired.deviceToken());
        } catch (RuntimeException | AuthException failure) {
            accounts.delete(created.getId(), nothingToClean());
            // A row this call created goes with the account; a row that was already
            // there is put back exactly as it was, because another account is reading
            // through it.
            if (previous == null) {
                bridges.delete(row.getId());
            } else {
                bridges.save(previous);
            }
            throw failure;
        }
        return new Stored(row, accounts.find(created.getId()));
    }

    /**
     * Points an existing account at a fresh pairing of the same computer, which is
     * what 「重新配对」 means: the token on the computer was revoked, and the account,
     * its history and its widget slots all have to survive.
     */
    public Stored rebind(PairingClient.Paired paired, String accountId)
            throws NotStored, AuthException {
        Account account = accounts.find(accountId);
        if (account == null) {
            throw new NotStored("要重新配对的账户已经不在了");
        }
        Bridge row = decideRow(paired.bridge());
        Bridge previous = bridges.findById(row.getId());
        try {
            bridges.save(row);
        } catch (RuntimeException failure) {
            // Nothing else has been touched yet, so the account keeps its old row and
            // its old secret, and the pairing can simply be tried again.
            throw new NotStored("这台电脑的新地址没能写进手机的配对表：" + failure.getMessage());
        }
        try {
            apply(row, account.getId(), paired.deviceToken());
        } catch (RuntimeException | AuthException failure) {
            if (previous == null) {
                bridges.delete(row.getId());
            } else {
                bridges.save(previous);
            }
            throw failure;
        }
        return new Stored(row, accounts.find(account.getId()));
    }

    /**
     * The row to write, refusing a digest that does not match the one on record.
     *
     * <p>Re-pairing keeps the original {@code added_at}: the device list says "paired
     * three weeks ago", and trading a revoked token for a new one is not a new
     * pairing.
     */
    private Bridge decideRow(Bridge incoming) throws NotStored {
        Bridge existing = bridges.findById(incoming.getId());
        if (existing != null && !existing.getFingerprint().equals(incoming.getFingerprint())) {
            throw new NotStored("这台 Bridge 的证书指纹和配对时记录的不一致，请先在设备管理里删除它，"
                    + "并确认电脑端的身份文件没有换过");
        }
        return existing == null
                ? incoming
                : incoming.toBuilder().addedAt(existing.getAddedAt()).build();
    }

    private void apply(Bridge row, String accountId, String deviceToken) throws AuthException {
        accounts.replaceCredential(accountId,
                CredentialPayload.forDeviceToken(deviceToken));
        accounts.attachBridge(accountId, row.getId());
    }

    /**
     * A freshly created account has no usage rows, so the history hook the account
     * layer demands has nothing to do. Named rather than written inline because an
     * empty body here is a claim about ordering, and the ordering above is what makes
     * it true.
     */
    private static com.aiusage.monitor.account.AccountManager.HistoryCleaner nothingToClean() {
        return accountId -> {
        };
    }
}

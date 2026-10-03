package com.aiusage.monitor.account;

import com.aiusage.monitor.auth.AuthException;
import com.aiusage.monitor.auth.AuthType;
import com.aiusage.monitor.auth.CredentialStore;
import com.aiusage.monitor.model.Account;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Account lifecycle rules. Spec §6 and §14.
 *
 * <p>An account is an <em>identity</em>, not a key. The key lives in a
 * credential row and the account only stores its id, which is what lets the
 * first-phase acceptance criterion hold: editing a key rewrites the credential
 * and leaves the account's id, its history and every widget bound to it
 * untouched.
 *
 * <p>This class owns the invariants that the repositories deliberately do not:
 * <ul>
 *   <li>an account always points at a credential of a type the provider
 *       supports;</li>
 *   <li>deleting an account also removes its credential and its history, so a
 *       deleted key does not linger in the database;</li>
 *   <li>{@code sortOrder} stays dense, so reordering cannot drift into ties.</li>
 * </ul>
 */
public final class AccountManager {

    /**
     * Guards the multi-step delete, the credential changes, and the refresh
     * path's check-then-write window against each other. R3.
     *
     * <p>The refresh side re-reads the account and its credential generation
     * right before it writes, but a delete or a key change that lands between
     * that check and the writes would still orphan rows or commit a reading the
     * current credential did not produce. Deletion is three steps across two
     * stores, so no single-store transaction can cover it; holding this one
     * monitor on both sides closes every interleaving without pushing a usage
     * dependency into the account layer. Credential mutators hold it too, so a
     * generation cannot advance inside someone else's check-then-commit.
     */
    private final Object writeMonitor = new Object();

    /**
     * Per-account credential generation, advanced every time the account's
     * secret changes. R3.
     *
     * <p>Closes the window the write monitor cannot: a request sent with the
     * <em>old</em> key can come back after the user saved a new one, and the
     * account still exists, so an existence check lets the old key's balance
     * land in the history of an account now standing on a different credential.
     * Comparing the generation captured before the secret was read against the
     * current one at commit time discards precisely that result.
     *
     * <p>In memory deliberately. The interleaving guarded here is in-process —
     * one refresh thread against one UI save — and a process death takes the
     * in-flight request with it, so a persisted counter would guard a shape
     * that cannot occur.
     */
    private final ConcurrentMap<String, AtomicLong> credentialGenerations =
            new ConcurrentHashMap<>();

    private final AccountRepository accounts;
    private final CredentialStore credentials;

    public AccountManager(AccountRepository accounts, CredentialStore credentials) {
        this.accounts = accounts;
        this.credentials = credentials;
    }

    /**
     * The monitor guarding the delete-against-refresh write window. The refresh
     * manager holds it across its own check-then-write sections so that a
     * concurrent delete cannot land inside one.
     */
    public Object writeMonitor() {
        return writeMonitor;
    }

    /**
     * The current credential generation of an account: 0 until its secret
     * changes for the first time. A refresh captures it before opening the
     * credential and re-checks it, still holding {@link #writeMonitor()},
     * before committing anything. R3.
     */
    public long credentialGeneration(String accountId) {
        AtomicLong generation = accountId == null ? null : credentialGenerations.get(accountId);
        return generation == null ? 0L : generation.get();
    }

    // putIfAbsent + increment rather than Map#compute: compute is API 24 and the
    // project's minSdk is 23.
    private void advanceCredentialGeneration(String accountId) {
        if (accountId == null) {
            return;
        }
        AtomicLong generation = credentialGenerations.get(accountId);
        if (generation == null) {
            AtomicLong fresh = new AtomicLong();
            AtomicLong existing = credentialGenerations.putIfAbsent(accountId, fresh);
            generation = existing == null ? fresh : existing;
        }
        generation.incrementAndGet();
    }

    /** All accounts in display order. */
    public List<Account> list() {
        return accounts.findAll();
    }

    /** Enabled accounts only. */
    public List<Account> listEnabled() {
        return accounts.findEnabled();
    }

    public Account find(String accountId) {
        return accounts.findById(accountId);
    }

    /** True when no account exists yet, used to decide whether to import legacy data. */
    public boolean isEmpty() {
        return accounts.count() == 0;
    }

    /**
     * Creates an account and its credential together.
     *
     * @param providerId   the provider this account belongs to
     * @param displayName  user-visible name, for example {@code DeepSeek个人}
     * @param type         authentication type, for example {@link AuthType#API_KEY}
     * @param payload      the credential payload, for example {@code {"apiKey":"..."}}
     * @return the new account
     */
    public Account create(String providerId, String displayName, AuthType type, String payload)
            throws AuthException {
        String credentialId = credentials.create(type, payload);
        long now = System.currentTimeMillis();
        Account account = Account.builder()
                .id(newId())
                .providerId(providerId)
                .displayName(displayName)
                .authType(type)
                .credentialId(credentialId)
                .enabled(true)
                .sortOrder(nextSortOrder())
                .createdAt(now)
                .updatedAt(now)
                .build();
        accounts.save(account);
        return account;
    }

    /**
     * Creates an account that has no credential yet.
     *
     * <p>A legitimate state, not a broken one: the user may have typed a key
     * without asking to remember it, or may be about to add one. The account
     * still owns its history and its widget bindings, so the identity is worth
     * keeping; a refresh attempt against it fails cleanly with
     * {@link com.aiusage.monitor.model.UsageError#INVALID_CREDENTIAL} and the
     * last successful snapshot is left alone. Spec §39.
     */
    public Account createAccount(String providerId, String displayName, AuthType type) {
        long now = System.currentTimeMillis();
        Account account = Account.builder()
                .id(newId())
                .providerId(providerId)
                .displayName(displayName)
                .authType(type)
                .credentialId("")
                .enabled(true)
                .sortOrder(nextSortOrder())
                .createdAt(now)
                .updatedAt(now)
                .build();
        accounts.save(account);
        return account;
    }

    /**
     * Replaces the secret behind an account.
     *
     * <p>The account row is deliberately not rewritten beyond its
     * {@code updatedAt}: the id, the creation time and the sort order are what
     * history and widget bindings refer to. Spec §14 requires that changing a
     * key leaves the account id unchanged and loses no history or widget
     * configuration, and this method is where that promise is kept.
     */
    public void updateCredential(String accountId, String payload) throws AuthException {
        // Held so the generation cannot advance between a refresh's check and its
        // commit: the refresh holds this same monitor across "is the credential
        // still the one I asked for?" and the writes that answer it. Without the
        // lock here, a save landing inside that window would let the old key's
        // balance commit under the new key's account.
        synchronized (writeMonitor) {
            Account account = require(accountId);
            credentials.update(account.getCredentialId(), payload);
            accounts.save(account.toBuilder().updatedAt(System.currentTimeMillis()).build());
            advanceCredentialGeneration(accountId);
        }
    }

    /**
     * Stores a secret for an account, creating the credential row when the
     * account does not have one yet.
     *
     * <p>Distinct from {@link #updateCredential} so that a caller which cannot
     * know whether a credential exists — the key field, where the user may have
     * typed before ever ticking "remember" — has one correct call to make. The
     * account id is preserved either way, which is what §14 protects.
     */
    public void replaceCredential(String accountId, String payload) throws AuthException {
        synchronized (writeMonitor) {
            Account account = require(accountId);
            if (account.getCredentialId() == null || account.getCredentialId().isEmpty()) {
                String credentialId = credentials.create(account.getAuthType(), payload);
                accounts.save(account.toBuilder()
                        .credentialId(credentialId)
                        .updatedAt(System.currentTimeMillis())
                        .build());
                advanceCredentialGeneration(accountId);
                return;
            }
            credentials.update(account.getCredentialId(), payload);
            accounts.save(account.toBuilder().updatedAt(System.currentTimeMillis()).build());
            advanceCredentialGeneration(accountId);
        }
    }

    /**
     * Removes an account's secret while keeping the account, its history and its
     * widget bindings.
     *
     * <p>This is what unticking "remember" means: forget the key, keep the
     * account. Upstream removed only the {@code api_key} preference and left the
     * usage counters and widget snapshot intact, and that is the behaviour
     * preserved here.
     */
    public void clearCredential(String accountId) {
        synchronized (writeMonitor) {
            Account account = require(accountId);
            if (account.getCredentialId() != null && !account.getCredentialId().isEmpty()) {
                credentials.delete(account.getCredentialId());
            }
            accounts.save(account.toBuilder()
                    .credentialId("")
                    .updatedAt(System.currentTimeMillis())
                    .build());
            advanceCredentialGeneration(accountId);
        }
    }

    /** Renames an account. The id does not change. */
    public void rename(String accountId, String displayName) {
        Account account = require(accountId);
        accounts.save(account.toBuilder()
                .displayName(displayName)
                .updatedAt(System.currentTimeMillis())
                .build());
    }

    /** Enables or disables an account without deleting it. */
    public void setEnabled(String accountId, boolean enabled) {
        Account account = require(accountId);
        accounts.save(account.toBuilder()
                .enabled(enabled)
                .updatedAt(System.currentTimeMillis())
                .build());
    }

    /**
     * Points an account at a paired Bridge. Phase 7 step 5; Spec §21.
     *
     * <p>Deliberately not a credential change: the generation counter tracks the
     * secret, and attaching a Bridge rewrites the {@code bridge_id} column and
     * nothing else. A refresh in flight keeps its meaning — it was produced by the
     * key that is still stored — so dropping it would throw away a good reading for
     * a bookkeeping edit.
     *
     * <p>Passing the empty string detaches, which returns the account to the
     * hand-typed path it was on before pairing: readers must treat "no bridge_id"
     * as "ask the credential payload for an address", because every account created
     * before this table existed looks like that.
     */
    public void attachBridge(String accountId, String bridgeId) {
        Account account = require(accountId);
        accounts.save(account.toBuilder()
                .bridgeId(bridgeId == null ? "" : bridgeId)
                .updatedAt(System.currentTimeMillis())
                .build());
    }

    /** The Bridge an account points at, or "" when it is a hand-typed account. */
    public String bridgeIdOf(String accountId) {
        Account account = find(accountId);
        return account == null ? "" : account.getBridgeId();
    }

    /**
     * Deletes an account and everything belonging to it: the credential row so
     * no key material lingers, and the history via the caller-supplied hook.
     *
     * <p>History is deleted through a callback rather than a direct repository
     * field because usage storage lives in a different package and the account
     * layer must not depend on it. Spec §45's layering puts
     * {@code account/} below {@code usage/}.
     */
    public void delete(String accountId, HistoryCleaner historyCleaner) {
        // Held for the whole three-step teardown, so the refresh path's
        // check-then-write section can never interleave with half a delete.
        // The section is milliseconds of local DB work; a concurrent refresh
        // waiting on the monitor waits milliseconds, not seconds.
        synchronized (writeMonitor) {
            Account account = accounts.findById(accountId);
            if (account == null) {
                return;
            }
            if (account.getCredentialId() != null && !account.getCredentialId().isEmpty()) {
                credentials.delete(account.getCredentialId());
            }
            if (historyCleaner != null) {
                historyCleaner.deleteHistory(accountId);
            }
            accounts.delete(accountId);
            compactSortOrder();
        }
    }

    /** Persists a new display order. */
    public void reorder(List<String> orderedIds) {
        accounts.reorder(orderedIds);
    }

    /** Reads the secret for an account, ready to hand to its provider. */
    public com.aiusage.monitor.provider.AuthContext openCredential(Account account) throws AuthException {
        if (account == null) {
            throw new AuthException(com.aiusage.monitor.model.UsageError.INVALID_CREDENTIAL, "账户不存在");
        }
        return credentials.open(account.getCredentialId(), account.getAuthType());
    }

    /** True when the account's stored credential is degraded, for a UI warning. */
    public boolean isCredentialDegraded(Account account) {
        if (account == null || !(credentials instanceof DegradedAware)) {
            return false;
        }
        return ((DegradedAware) credentials).isDegraded(account.getCredentialId());
    }

    private Account require(String accountId) {
        Account account = accounts.findById(accountId);
        if (account == null) {
            throw new IllegalArgumentException("账户不存在：" + accountId);
        }
        return account;
    }

    private int nextSortOrder() {
        List<Account> existing = accounts.findAll();
        int highest = -1;
        for (Account account : existing) {
            highest = Math.max(highest, account.getSortOrder());
        }
        return highest + 1;
    }

    /** Rewrites sortOrder to 0..n-1 so reordering cannot leave duplicates. */
    private void compactSortOrder() {
        List<Account> existing = accounts.findAll();
        List<String> ids = new ArrayList<>();
        for (Account account : existing) {
            ids.add(account.getId());
        }
        accounts.reorder(ids);
    }

    private static String newId() {
        return "acct_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** Removes an account's usage history. Implemented by the usage layer. */
    public interface HistoryCleaner {
        void deleteHistory(String accountId);
    }

    /** Implemented by credential stores that can report degraded protection. */
    public interface DegradedAware {
        boolean isDegraded(String credentialId);
    }
}

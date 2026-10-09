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
     * Per-account refresh generation, advanced when credentials change and when
     * an enabled Direct account's Bridge fallback route changes. R3.
     *
     * <p>Closes the window the write monitor cannot: a request sent with the
     * <em>old</em> request can come back after the user changed a credential or
     * an active Direct account's fallback route, and the account still exists,
     * so an existence check alone could commit a result from the prior state.
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
     * The current refresh generation of an account: 0 until its secret changes or
     * its enabled Direct fallback route changes. A refresh captures it before
     * opening credentials and re-checks it, still holding {@link #writeMonitor()},
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

    /** Creates a standalone Codex card backed only by the phone's OAuth credential. */
    public Account createDirectOAuthAccount(String displayName, String payload, String identityHash)
            throws AuthException {
        synchronized (writeMonitor) {
            String credentialId = credentials.create(AuthType.OAUTH, payload);
            long now = System.currentTimeMillis();
            Account account = Account.builder().id(newId())
                    .providerId(com.aiusage.monitor.provider.codex.CodexProvider.ID)
                    .displayName(displayName).authType(AuthType.OAUTH)
                    .credentialId(credentialId).directEnabled(true)
                    .directIdentityHash(identityHash).enabled(true)
                    .sortOrder(nextSortOrder()).createdAt(now).updatedAt(now).build();
            accounts.save(account);
            return account;
        }
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

    /** Saves a newly authorized Codex source, keeping any Bridge credential intact. */
    public void saveDirectOAuthCredential(String accountId, String payload, String identityHash)
            throws AuthException {
        synchronized (writeMonitor) {
            Account account = require(accountId);
            String replacement = credentials.create(AuthType.OAUTH, payload);
            if (account.getAuthType() == AuthType.OAUTH) {
                String oldPrimary = account.getCredentialId();
                accounts.save(account.toBuilder().credentialId(replacement)
                        .directEnabled(true).directNeedsAuth(false)
                        .directIdentityHash(identityHash).updatedAt(System.currentTimeMillis()).build());
                if (oldPrimary != null && !oldPrimary.isEmpty()) credentials.delete(oldPrimary);
            } else {
                String oldDirect = account.getDirectCredentialId();
                accounts.save(account.toBuilder().directCredentialId(replacement)
                        .directEnabled(true).directNeedsAuth(false)
                        .directIdentityHash(identityHash).updatedAt(System.currentTimeMillis()).build());
                if (oldDirect != null && !oldDirect.isEmpty()) credentials.delete(oldDirect);
            }
            advanceCredentialGeneration(accountId);
        }
    }

    /** Persists rotated OAuth tokens only while the request still owns this generation. */
    public boolean rotateDirectOAuthCredential(String accountId, long expectedGeneration,
                                               String payload) throws AuthException {
        synchronized (writeMonitor) {
            Account account = accounts.findById(accountId);
            if (account == null || credentialGeneration(accountId) != expectedGeneration) return false;
            String id = directCredentialId(account);
            if (id.isEmpty()) return false;
            credentials.update(id, payload);
            accounts.save(account.toBuilder().updatedAt(System.currentTimeMillis()).build());
            advanceCredentialGeneration(accountId);
            return true;
        }
    }

    public com.aiusage.monitor.provider.AuthContext openDirectOAuthCredential(Account account)
            throws AuthException {
        if (account == null) {
            throw new AuthException(com.aiusage.monitor.model.UsageError.INVALID_CREDENTIAL,
                    "账户不存在");
        }
        return credentials.open(directCredentialId(account), AuthType.OAUTH);
    }

    /** Enables or disables only the experimental Direct source. */
    public void setDirectEnabled(String accountId, boolean enabled) {
        synchronized (writeMonitor) {
            Account account = require(accountId);
            if (enabled && directCredentialId(account).isEmpty()) {
                throw new IllegalStateException("Codex 手机授权尚未完成");
            }
            accounts.save(account.toBuilder().directEnabled(enabled)
                    .updatedAt(System.currentTimeMillis()).build());
            advanceCredentialGeneration(accountId);
        }
    }

    /** Records a reauthorization state without invalidating an in-flight reading. */
    public void setDirectNeedsAuth(String accountId, boolean needsAuth) {
        synchronized (writeMonitor) {
            Account account = accounts.findById(accountId);
            if (account == null || account.isDirectNeedsAuth() == needsAuth) return;
            accounts.save(account.toBuilder().directNeedsAuth(needsAuth)
                    .updatedAt(System.currentTimeMillis()).build());
        }
    }

    /** Removes only the Direct token; a Bridge credential and its pairing remain usable. */
    public void clearDirectOAuthCredential(String accountId) {
        synchronized (writeMonitor) {
            Account account = require(accountId);
            if (account.getAuthType() == AuthType.OAUTH) {
                if (!account.getCredentialId().isEmpty()) credentials.delete(account.getCredentialId());
                accounts.save(account.toBuilder().credentialId("").directEnabled(false)
                        .directNeedsAuth(false).directIdentityHash("")
                        .updatedAt(System.currentTimeMillis()).build());
            } else {
                String id = account.getDirectCredentialId();
                if (id != null && !id.isEmpty()) credentials.delete(id);
                accounts.save(account.toBuilder().directCredentialId("").directEnabled(false)
                        .directNeedsAuth(false).directIdentityHash("")
                        .updatedAt(System.currentTimeMillis()).build());
            }
            advanceCredentialGeneration(accountId);
        }
    }

    public boolean hasDirectCredential(Account account) {
        return account != null && !directCredentialId(account).isEmpty();
    }

    /** Enabled, opted-in accounts for the no-widget background alarm. */
    public List<Account> listDirectRefreshEnabled() {
        List<Account> result = new ArrayList<>();
        for (Account account : accounts.findEnabled()) {
            if (account.isDirectEnabled() && !directCredentialId(account).isEmpty()) result.add(account);
        }
        return result;
    }

    private static String directCredentialId(Account account) {
        if (account == null) return "";
        String secondary = account.getDirectCredentialId();
        if (secondary != null && !secondary.isEmpty()) return secondary;
        return account.getAuthType() == AuthType.OAUTH ? account.getCredentialId() : "";
    }

    /** Renames an account. The id does not change. */
    public void rename(String accountId, String displayName) {
        synchronized (writeMonitor) {
            Account account = require(accountId);
            accounts.save(account.toBuilder()
                    .displayName(displayName)
                    .updatedAt(System.currentTimeMillis())
                    .build());
        }
    }

    /** Enables or disables an account without deleting it. */
    public void setEnabled(String accountId, boolean enabled) {
        synchronized (writeMonitor) {
            Account account = require(accountId);
            accounts.save(account.toBuilder()
                    .enabled(enabled)
                    .updatedAt(System.currentTimeMillis())
                    .build());
        }
    }

    /**
     * Points an account at a paired Bridge. Phase 7 step 5; Spec §21.
     *
     * <p>For a Bridge-only account this remains a routing edit, not a credential
     * change, and an in-flight read keeps its meaning. When Direct is enabled,
     * however, this Bridge is the fallback source; changing it advances the refresh
     * generation so an older fallback result cannot be stored after relinking.
     *
     * <p>Passing the empty string detaches, which returns the account to the
     * hand-typed path it was on before pairing: readers must treat "no bridge_id"
     * as "ask the credential payload for an address", because every account created
     * before this table existed looks like that.
     */
    public void attachBridge(String accountId, String bridgeId) {
        synchronized (writeMonitor) {
            Account account = require(accountId);
            String nextBridgeId = bridgeId == null ? "" : bridgeId;
            boolean directFallbackChanged = account.isDirectEnabled()
                    && !account.getBridgeId().equals(nextBridgeId);
            accounts.save(account.toBuilder()
                    .bridgeId(nextBridgeId)
                    .updatedAt(System.currentTimeMillis())
                    .build());
            if (directFallbackChanged) advanceCredentialGeneration(accountId);
        }
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
            if (account.getDirectCredentialId() != null
                    && !account.getDirectCredentialId().isEmpty()
                    && !account.getDirectCredentialId().equals(account.getCredentialId())) {
                credentials.delete(account.getDirectCredentialId());
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
        if (orderedIds == null || orderedIds.isEmpty()) return;
        List<Account> current = accounts.findAll();
        java.util.Map<String, Boolean> pinnedById = new java.util.HashMap<>();
        for (Account account : current) pinnedById.put(account.getId(), account.isPinned());

        List<String> pinned = new ArrayList<>();
        List<String> ordinary = new ArrayList<>();
        for (String id : orderedIds) {
            Boolean isPinned = pinnedById.remove(id);
            if (isPinned == null) continue;
            (isPinned ? pinned : ordinary).add(id);
        }
        // Keep accounts omitted by a partial caller in their existing group and order.
        for (Account account : current) {
            if (pinnedById.containsKey(account.getId())) {
                (account.isPinned() ? pinned : ordinary).add(account.getId());
            }
        }
        pinned.addAll(ordinary);
        accounts.reorder(pinned);
    }

    /** Moves an account between the fixed pinned and ordinary groups. */
    public void setPinned(String accountId, boolean pinned) {
        synchronized (writeMonitor) {
            Account account = accounts.findById(accountId);
            if (account == null || account.isPinned() == pinned) return;
            accounts.save(account.toBuilder().pinned(pinned)
                    .updatedAt(System.currentTimeMillis()).build());
        }
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

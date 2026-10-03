package com.aiusage.monitor.refresh;

import com.aiusage.monitor.account.AccountManager;
import com.aiusage.monitor.auth.AuthException;
import com.aiusage.monitor.model.Account;
import com.aiusage.monitor.model.UsageError;
import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.model.UsageStatus;
import com.aiusage.monitor.provider.AuthContext;
import com.aiusage.monitor.provider.ProviderRegistry;
import com.aiusage.monitor.provider.UsageException;
import com.aiusage.monitor.provider.UsageProvider;
import com.aiusage.monitor.usage.UsageRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * The one refresh path. Spec §37.
 *
 * <p>Every trigger — the app's foreground timer, a widget's manual refresh, the
 * background alarm — calls this class. That is the whole point: with a single
 * chain there is no way for the app and a widget to disagree about what an
 * account's balance is, and no way for one of the three paths to quietly skip
 * persisting its result.
 *
 * <pre>
 * Account → ProviderRegistry → CredentialStore → AuthAdapter → Provider
 *         → UsageResult → UsageRepository → UsageSnapshot → (App | Widget)
 * </pre>
 *
 * <p>Failure handling follows the spec's two hard rules:
 * <ul>
 *   <li>a failed refresh never deletes the last successful data (§39, rule 18)
 *       — it is recorded as a failed snapshot and the previous row is left
 *       alone;</li>
 *   <li>the provider's raw error never reaches a widget (§49) — it is mapped to
 *       a {@link UsageError} here, and the widget reads only stored state.</li>
 * </ul>
 */
public final class AccountRefreshManager {

    private final AccountManager accountManager;
    private final UsageRepository usageRepository;
    private final ProviderRegistry registry;

    public AccountRefreshManager(AccountManager accountManager,
                                 UsageRepository usageRepository,
                                 ProviderRegistry registry) {
        this.accountManager = accountManager;
        this.usageRepository = usageRepository;
        this.registry = registry;
    }

    /** The outcome of one refresh attempt. */
    public static final class RefreshOutcome {
        private final String accountId;
        private final boolean success;
        private final UsageResult result;
        private final UsageError error;
        private final String message;

        private RefreshOutcome(String accountId, boolean success, UsageResult result,
                               UsageError error, String message) {
            this.accountId = accountId;
            this.success = success;
            this.result = result;
            this.error = error;
            this.message = message;
        }

        static RefreshOutcome ok(String accountId, UsageResult result) {
            return new RefreshOutcome(accountId, true, result, null, null);
        }

        static RefreshOutcome failed(String accountId, UsageError error, String message) {
            UsageError effective = error == null ? UsageError.UNKNOWN : error;
            String text = message == null || message.trim().isEmpty()
                    ? effective.getMessage()
                    : message;
            return new RefreshOutcome(accountId, false, null, effective, text);
        }

        /**
         * A result that was deliberately thrown away, never stored. R3.
         *
         * <p>Used when the account vanished, or its credential was replaced or
         * cleared, while its request was in flight. It carries no error: nothing
         * failed, and showing "请求失败" for a request that returned a perfectly
         * good balance would be a lie.
         */
        static RefreshOutcome abandoned(String accountId) {
            return new RefreshOutcome(accountId, false, null, null, null);
        }

        public String getAccountId() {
            return accountId;
        }

        public boolean isSuccess() {
            return success;
        }

        /** The fresh result, or null on failure. */
        public UsageResult getResult() {
            return result;
        }

        /** The mapped error, or null on success. */
        public UsageError getError() {
            return error;
        }

        /**
         * The message to show the user, never empty on failure.
         *
         * <p>Carried separately from {@link #getError()} because some failures
         * are only meaningful with their detail: upstream rendered
         * {@code 请求失败（HTTP 500）} for an unlisted status code, and collapsing
         * that to the category's generic text would be a visible regression.
         */
        public String getMessage() {
            return message;
        }

        /**
         * True when the result was thrown away because its account was deleted,
         * or its credential replaced, while the request was running. R3.
         *
         * <p>Not a failure of the provider and not an error to show the user as
         * one: the request may well have succeeded. It is a signal that nothing
         * was stored, so callers can skip rendering a state that no longer has
         * an account behind it.
         */
        public boolean isAbandoned() {
            return !success && error == null && result == null;
        }
    }

    /**
     * Refreshes one account: opens its credential, asks its provider, records
     * the result.
     *
     * <p>Never throws. A failure is a normal outcome that callers render, not
     * an exception that would abort a batch refresh and leave the other
     * accounts unrefreshed — the spec requires one account's error not to
     * affect the others (§13).
     */
    public RefreshOutcome refresh(String accountId) {
        Account account = accountManager.find(accountId);
        if (account == null) {
            return RefreshOutcome.failed(accountId, UsageError.UNKNOWN, null);
        }
        return refresh(account);
    }

    /**
     * Refreshes an account using a credential supplied by the caller instead of
     * the stored one.
     *
     * <p>Exists for the one case the upstream UI allowed: a user who types a key
     * and does <em>not</em> tick "remember". The key must still be usable for the
     * request it was typed for, while never being written to storage. The caller
     * passes an {@link AuthContext} it built from the in-memory text, and nothing
     * is persisted except the resulting snapshot.
     */
    public RefreshOutcome refreshWithAuthContext(Account account, AuthContext authContext) {
        if (account == null) {
            return RefreshOutcome.failed("", UsageError.UNKNOWN, null);
        }
        // R3: on this path the secret never goes through storage, so nothing
        // below would notice a delete until the result came back. Asking before
        // spending the request is what stops a caller's stale object from
        // querying the platform with a key whose account no longer exists. The
        // commit-time check stays as well; this one only saves the round trip.
        if (isGone(account.getId())) {
            return RefreshOutcome.abandoned(account.getId());
        }
        return fetch(account, authContext, accountManager.credentialGeneration(account.getId()));
    }

    /** Refreshes one account that the caller already loaded. */
    public RefreshOutcome refresh(Account account) {
        if (account == null) {
            return RefreshOutcome.failed("", UsageError.UNKNOWN, null);
        }
        String accountId = account.getId();
        // The caller's object can be a snapshot from before someone touched this
        // account's secret - refreshAll() takes its list once and works through it,
        // so an account whose key was saved (or cleared) during the batch still
        // carries the old credential id, or none. Opening *that* while stamping the
        // current generation files the superseded secret's failure as the new key's
        // (docs/PHASE-0-7-REVIEW.md §2.1). Re-read the row together with its
        // generation, inside the monitor the credential writers hold, and work from
        // what the store says now; the network call stays outside the lock.
        Account current;
        long generation;
        synchronized (accountManager.writeMonitor()) {
            current = accountManager.find(accountId);
            if (current == null) {
                return RefreshOutcome.abandoned(accountId);
            }
            generation = accountManager.credentialGeneration(accountId);
        }

        AuthContext authContext;
        try {
            authContext = accountManager.openCredential(current);
        } catch (AuthException exception) {
            // R3: the credential is deleted along with the account, so an
            // account deleted before its refresh got here fails right here as
            // INVALID_CREDENTIAL. Reporting that as a failure would write a
            // failure row for an account that no longer exists. The check and
            // the write share the delete's monitor: an unlocked isGone here
            // could be crossed by a concurrent delete between the check and
            // the save, exactly the window the success path already closes.
            synchronized (accountManager.writeMonitor()) {
                if (isVoid(accountId, generation)) {
                    return RefreshOutcome.abandoned(accountId);
                }
                return recordFailure(accountId, current.getAuthType(), exception.getError(), exception.getMessage());
            }
        }
        return fetch(current, authContext, generation);
    }

    /** The provider call itself, shared by both entry points. */
    private RefreshOutcome fetch(Account account, AuthContext authContext, long generation) {
        String accountId = account.getId();

        UsageProvider provider;
        try {
            provider = registry.require(account.getProviderId());
        } catch (UsageException exception) {
            // Same race as the openCredential catch above: a delete landing
            // between the registry lookup and the failure save would leave an
            // orphan row for an account that no longer exists.
            synchronized (accountManager.writeMonitor()) {
                if (isVoid(accountId, generation)) {
                    return RefreshOutcome.abandoned(accountId);
                }
                return recordFailure(accountId, account.getAuthType(), exception.getError(), exception.getMessage());
            }
        }

        try {
            UsageResult result = provider.fetchUsage(account, authContext);

            // R3 + review P2-2: the check-then-write window is closed by
            // serializing it against the whole delete on one shared monitor.
            // The provider call stays outside the monitor: it is seconds of
            // network I/O and must not block a delete for its duration.
            synchronized (accountManager.writeMonitor()) {
                // R3: the delete, or a credential change, can land while the
                // provider call is in flight. The account is re-read from
                // storage rather than trusting the caller's object, which the UI
                // may have captured before either event.
                if (isVoid(accountId, generation)) {
                    return RefreshOutcome.abandoned(accountId);
                }

                // The provider returns the platform's view; the account
                // identity and a fresh timestamp are stamped here so every
                // stored row is self-describing regardless of what the
                // provider filled in.
                UsageResult stamped = result.toBuilder()
                        .accountId(accountId)
                        .providerId(account.getProviderId())
                        .updatedAt(System.currentTimeMillis())
                        .build();

                // Daily usage is derived from the balance reading, per account.
                if (stamped.getBalance() != null && !stamped.getBalance().getRawText().isEmpty()) {
                    usageRepository.recordDailyUsage(accountId, stamped.getBalance().getRawText());
                }

                usageRepository.save(accountId, stamped, true);
                return RefreshOutcome.ok(accountId, stamped);
            }
        } catch (UsageException exception) {
            synchronized (accountManager.writeMonitor()) {
                if (isVoid(accountId, generation)) {
                    return RefreshOutcome.abandoned(accountId);
                }
                return recordFailure(accountId, account.getAuthType(), exception.getError(), exception.getMessage());
            }
        } catch (RuntimeException exception) {
            // A provider bug must not take down the refresh of every other
            // account, so it is contained and reported as an unknown error.
            synchronized (accountManager.writeMonitor()) {
                if (isVoid(accountId, generation)) {
                    return RefreshOutcome.abandoned(accountId);
                }
                return recordFailure(accountId, account.getAuthType(), UsageError.UNKNOWN, null);
            }
        }
    }

    /**
     * True when the account no longer exists. R3: a delete that lands while a
     * request is in flight must not leave rows behind, so the account is
     * re-read from storage instead of trusting the caller's object, which the
     * UI may have captured before the delete.
     *
     * <p>On its own this is only a check; the lock is the shared write monitor
     * ({@link AccountManager#writeMonitor()}), which every caller of this
     * method holds across the check and the writes that follow, and which the
     * whole three-step delete also holds. Together they close the window.
     */
    private boolean isGone(String accountId) {
        return accountManager.find(accountId) == null;
    }

    /**
     * True when a result must be thrown away: its account is gone, or its
     * credential has changed since the request was built. R3.
     *
     * <p>The two cases leave the same mark on a result — it describes an
     * account state that no longer exists — so neither may be stored. The
     * existence check alone was not enough: after a key change the account is
     * still there, and a balance read from the superseded key would land under
     * an identity that never produced it. A generation counter that is never
     * compared is decoration, so each branch of this is pinned by
     * {@code CredentialEpochDropsStaleResultTest}.
     */
    private boolean isVoid(String accountId, long generation) {
        return isGone(accountId)
                || accountManager.credentialGeneration(accountId) != generation;
    }

    /**
     * Refreshes every enabled account.
     *
     * <p>Sequential by design: the spec's refresh intervals are minutes, the
     * account count is small, and a provider's rate limit is easier to respect
     * when requests are not fired in parallel.
     */
    public List<RefreshOutcome> refreshAll() {
        List<RefreshOutcome> outcomes = new ArrayList<>();
        for (Account account : accountManager.listEnabled()) {
            outcomes.add(refresh(account));
        }
        return outcomes;
    }

    /**
     * What the account list needs to draw one row: the last good reading and
     * the newest attempt, kept apart.
     *
     * <p>They are two different facts and the UI must show both, because either
     * one alone is misleading: the balance is real but possibly hours old, and
     * the status is current but says nothing about the value. Conflating them
     * is what made a deleted-key account still read "账户可用" while its newest
     * snapshot was AUTH_REQUIRED.
     */
    public static final class AccountView {
        /** The last successful reading, or null when there has never been one. */
        public final UsageResult lastSuccess;
        /** The newest attempt, successful or not; null only if never fetched. */
        public final UsageResult lastAttempt;

        /**
         * Pairs the two readings for one account.
         *
         * <p>Public because this is a value, not a factory: the widget layer
         * assembles a display from it and a caller that has already read both
         * rows — a screen holding a snapshot from a refresh it just did — should
         * not have to reach through the manager to pair them.
         */
        public AccountView(UsageResult lastSuccess, UsageResult lastAttempt) {
            this.lastSuccess = lastSuccess;
            this.lastAttempt = lastAttempt;
        }

        /**
         * The status to display: the newest attempt's when there is one, so a
         * failure after a success is visible instead of being hidden behind the
         * last good reading.
         */
        public UsageStatus status() {
            if (lastAttempt != null) {
                return lastAttempt.getStatus();
            }
            return lastSuccess == null ? UsageStatus.NO_DATA : lastSuccess.getStatus();
        }

        /**
         * The status to display, with staleness folded in.
         *
         * <p>Staleness used to live only in the widget path ({@code lastKnown()}),
         * so a row whose newest snapshot was a success but hours old still read
         * "账户可用" while its widget said "数据已过期" — the two surfaces
         * disagreed about the same stored data. A failed newest attempt is
         * returned as-is: a failure is a present-tense fact about the service,
         * not data that quietly went stale.
         *
         * @param intervalMs the configured background refresh interval
         * @param now        the instant to judge freshness against
         */
        public UsageStatus displayStatus(long intervalMs, long now) {
            if (lastAttempt != null && lastAttempt.getStatus() != UsageStatus.OK) {
                return lastAttempt.getStatus();
            }
            UsageResult display = lastAttempt != null ? lastAttempt : lastSuccess;
            if (display == null) {
                return UsageStatus.NO_DATA;
            }
            if (RefreshPolicy.isStale(display.getUpdatedAt(), intervalMs, now)) {
                return UsageStatus.STALE;
            }
            return display.getStatus();
        }

        /** True when the last attempt failed but an older success is retained. */
        public boolean showingRetainedData() {
            if (lastAttempt == null || lastSuccess == null) {
                return false;
            }
            // >=, not >: a success and the failure right after it can land in
            // the same millisecond, and equal timestamps still mean the newest
            // row (the failure) is what the row is displaying.
            return lastAttempt.getStatus() != UsageStatus.OK
                    && lastAttempt.getUpdatedAt() >= lastSuccess.getUpdatedAt();
        }
    }

    /** The list-facing view of one account. */
    public AccountView view(String accountId) {
        return new AccountView(
                usageRepository.latest(accountId),
                usageRepository.latestAttempt(accountId));
    }

    /**
     * Records a failure without touching the previous successful row.
     *
     * <p>The stored failed snapshot carries the error as a result with status
     * {@link UsageStatus#NETWORK_ERROR} so the history shows the attempt, while
     * {@code latest()} — which filters on {@code success = 1} — keeps returning
     * the last good data.
     */
    private RefreshOutcome recordFailure(String accountId,
                                        com.aiusage.monitor.auth.AuthType authType,
                                        UsageError error,
                                        String message) {
        UsageError effective = error == null ? UsageError.UNKNOWN : error;
        UsageResult placeholder = UsageResult.builder()
                .accountId(accountId)
                .status(statusFor(effective))
                .updatedAt(System.currentTimeMillis())
                // Was hard-coded to DIRECT_API for every provider, which filed a
                // Codex failure as a DeepSeek reading in the history the UI and the
                // retention policy both read.
                .source(sourceFor(authType))
                .build();
        usageRepository.save(accountId, placeholder, false);
        return RefreshOutcome.failed(accountId, effective, message);
    }

    /**
     * Which channel a failure came in on. Derived from the credential mechanism
     * rather than the provider id, because that is the fact the refresh chain
     * actually knows: a BRIDGE_TOKEN account was reached through the Bridge
     * whatever the platform turns out to be.
     */
    static UsageResult.Source sourceFor(com.aiusage.monitor.auth.AuthType authType) {
        return authType == com.aiusage.monitor.auth.AuthType.BRIDGE_TOKEN
                ? UsageResult.Source.BRIDGE
                : UsageResult.Source.DIRECT_API;
    }

    /**
     * Maps an error to the widget-facing status the spec defines. §40.
     * Package-visible so the offline-versus-expired separation can be asserted
     * directly; it needs no Android context.
     */
    static UsageStatus statusFor(UsageError error) {
        switch (error) {
            case NETWORK_ERROR:
                return UsageStatus.NETWORK_ERROR;
            case BRIDGE_OFFLINE:
                return UsageStatus.BRIDGE_OFFLINE;
            case INVALID_CREDENTIAL:
            case AUTH_EXPIRED:
            case PERMISSION_DENIED:
                return UsageStatus.AUTH_REQUIRED;
            case BRIDGE_UNAUTHORIZED:
                // Its own status: the wording for AUTH_REQUIRED says "API Key",
                // which is not what a Bridge account has.
                return UsageStatus.BRIDGE_AUTH_REQUIRED;
            default:
                return UsageStatus.NETWORK_ERROR;
        }
    }
}

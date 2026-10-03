package com.aiusage.monitor.refresh;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aiusage.monitor.account.AccountManager;
import com.aiusage.monitor.account.AccountRepository;
import com.aiusage.monitor.auth.ApiKeyAuthAdapter;
import com.aiusage.monitor.auth.AuthException;
import com.aiusage.monitor.auth.AuthType;
import com.aiusage.monitor.auth.CredentialPayload;
import com.aiusage.monitor.auth.CredentialStore;
import com.aiusage.monitor.model.Account;
import com.aiusage.monitor.model.Balance;
import com.aiusage.monitor.model.UsageError;
import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.model.UsageStatus;
import com.aiusage.monitor.provider.AuthContext;
import com.aiusage.monitor.provider.ProviderCapabilities;
import com.aiusage.monitor.provider.ProviderRegistry;
import com.aiusage.monitor.provider.UsageException;
import com.aiusage.monitor.provider.UsageProvider;
import com.aiusage.monitor.storage.SqliteCredentialStore;
import com.aiusage.monitor.usage.SnapshotRetention;
import com.aiusage.monitor.usage.UsageRepository;
import com.aiusage.monitor.usage.UsageSnapshot;
import com.aiusage.monitor.usage.UsageSnapshotCodec;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The account list must keep the last good balance and the newest attempt's
 * status as two separate facts.
 *
 * <p>Review finding R4: {@code statusText} read {@code lastKnown()}, which goes
 * through {@code latest()} — a query filtered on {@code success = 1}. So after a
 * successful refresh followed by a 401, the list kept showing "账户可用" while
 * the newest stored snapshot said {@code AUTH_REQUIRED}. The balance was honest
 * (it really was the last good reading) but the status was a lie about the
 * present. This test pins the two apart.
 *
 * <p>Review finding R2 is pinned here too, by asserting the type relation that
 * {@code AccountManager.isCredentialDegraded} tests with {@code instanceof}:
 * {@code SqliteCredentialStore} must implement {@code DegradedAware}. Without it
 * the degraded-credential warning can never appear on a real device, no matter
 * what the database says. Reflection is used because the class needs no Android
 * runtime to load, while its {@code isDegraded} query does.
 */
public class AccountViewStatusTest {

    private static final String PROVIDER_ID = "test-view-status";
    private static final double GOOD_BALANCE = 24.94d;
    private static final long BASE_TIME = 1_700_000_000_000L;

    /**
     * One instance for the class: {@link ProviderRegistry} is a JVM-wide
     * singleton and rejects a duplicate id.
     */
    private static SwitchableProvider provider;

    private InMemoryAccountRepository accounts;
    private InMemoryCredentialStore credentials;
    private InMemoryUsageRepository usage;
    private AccountManager accountManager;
    private AccountRefreshManager refreshManager;
    private Account account;

    @Before
    public void setUp() throws AuthException {
        accounts = new InMemoryAccountRepository();
        credentials = new InMemoryCredentialStore();
        usage = new InMemoryUsageRepository();
        accountManager = new AccountManager(accounts, credentials);

        provider = sharedProvider();
        provider.reset();
        refreshManager = new AccountRefreshManager(accountManager, usage, ProviderRegistry.get());
        account = accountManager.createAccount(PROVIDER_ID, "DeepSeek", AuthType.API_KEY);
        // createAccount does not bind a key, and an unbound account fails with
        // INVALID_CREDENTIAL before the provider is ever reached — so bind one,
        // or every refresh is a credential failure regardless of the stub.
        // replaceCredential stores a toBuilder() copy, so the local reference is
        // stale afterwards and has to be re-read.
        // The stored payload is the encoded JSON form, not the raw key:
        // CredentialPayload.extractApiKey parses it, so a bare string would be
        // rejected as INVALID_CREDENTIAL before the provider is reached.
        accountManager.replaceCredential(account.getId(),
                CredentialPayload.forApiKey("sk-view-status-test"));
        account = accountManager.find(account.getId());
        usage.setClock(BASE_TIME);
    }

    private static SwitchableProvider sharedProvider() {
        if (provider == null) {
            provider = new SwitchableProvider(PROVIDER_ID);
            ProviderRegistry.get().register(provider);
        }
        return provider;
    }

    // ---------------------------------------------------------------- R4

    @Test
    public void successThenAuthFailureReportsTheFailureNotTheOldSuccess() {
        provider.setResult(ok(BASE_TIME));
        refreshManager.refresh(account);

        provider.setFailure(UsageError.INVALID_CREDENTIAL);
        refreshManager.refresh(account);

        AccountRefreshManager.AccountView view = refreshManager.view(account.getId());

        assertNotNull("the last good balance must survive the failure", view.lastSuccess);
        assertEquals(GOOD_BALANCE, view.lastSuccess.getBalance().getAmount(), 0.001d);
        assertNotNull("the newest attempt must be readable", view.lastAttempt);
        assertEquals(UsageStatus.AUTH_REQUIRED, view.status());
        assertTrue("the row is showing retained data, not a fresh reading",
                view.showingRetainedData());
    }

    @Test
    public void successThenNetworkFailureKeepsBalanceAndReportsNetwork() {
        provider.setResult(ok(BASE_TIME));
        refreshManager.refresh(account);

        provider.setFailure(UsageError.NETWORK_ERROR);
        refreshManager.refresh(account);

        AccountRefreshManager.AccountView view = refreshManager.view(account.getId());
        assertEquals(GOOD_BALANCE, view.lastSuccess.getBalance().getAmount(), 0.001d);
        assertEquals(UsageStatus.NETWORK_ERROR, view.status());
        assertTrue(view.showingRetainedData());
    }

    @Test
    public void firstEverFailureHasNoSuccessAndStillReportsTheStatus() {
        provider.setFailure(UsageError.INVALID_CREDENTIAL);
        refreshManager.refresh(account);

        AccountRefreshManager.AccountView view = refreshManager.view(account.getId());
        assertNull("no successful reading has ever happened", view.lastSuccess);
        assertNotNull(view.lastAttempt);
        assertEquals(UsageStatus.AUTH_REQUIRED, view.status());
        assertFalse("nothing is being retained when there is nothing to retain",
                view.showingRetainedData());
    }

    @Test
    public void recoveringFromAFailureClearsTheErrorStatus() {
        provider.setResult(ok(BASE_TIME));
        refreshManager.refresh(account);
        provider.setFailure(UsageError.NETWORK_ERROR);
        refreshManager.refresh(account);
        assertEquals(UsageStatus.NETWORK_ERROR, refreshManager.view(account.getId()).status());

        provider.setResult(ok(BASE_TIME + 5_000L));
        refreshManager.refresh(account);

        AccountRefreshManager.AccountView view = refreshManager.view(account.getId());
        assertEquals(UsageStatus.OK, view.status());
        assertFalse(view.showingRetainedData());
    }

    @Test
    public void neverFetchedAccountHasNoDataStatus() {
        AccountRefreshManager.AccountView view = refreshManager.view(account.getId());
        assertNull(view.lastSuccess);
        assertNull(view.lastAttempt);
        assertEquals(UsageStatus.NO_DATA, view.status());
        assertFalse(view.showingRetainedData());
    }

    @Test
    public void aFailureDoesNotOverwriteTheStoredSuccessRow() {
        provider.setResult(ok(BASE_TIME));
        refreshManager.refresh(account);
        provider.setFailure(UsageError.INVALID_CREDENTIAL);
        refreshManager.refresh(account);

        // The repository contract behind rule 18: latest() is untouched by the
        // failure, latestAttempt() sees it. Both facts, no destructive update.
        assertEquals(GOOD_BALANCE, usage.latest(account.getId()).getBalance().getAmount(), 0.001d);
        assertEquals(UsageStatus.AUTH_REQUIRED, usage.latestAttempt(account.getId()).getStatus());
    }

    // ------------------------------------------------- staleness in the list

    @Test
    public void freshSuccessIsNotStale() {
        provider.setResult(ok(System.currentTimeMillis()));
        refreshManager.refresh(account);

        AccountRefreshManager.AccountView view = refreshManager.view(account.getId());
        assertEquals("a just-stored success is current",
                UsageStatus.OK, view.displayStatus(60_000L, view.lastAttempt.getUpdatedAt()));
    }

    @Test
    public void staleBoundaryIsTwiceTheIntervalExclusive() {
        provider.setResult(ok(System.currentTimeMillis()));
        refreshManager.refresh(account);

        // The boundary is judged against the timestamp actually stored, not a
        // separately sampled clock: sampling twice could straddle a millisecond
        // and make the "exact boundary" assertion flaky.
        long interval = 60_000L;
        AccountRefreshManager.AccountView view = refreshManager.view(account.getId());
        long storedAt = view.lastAttempt.getUpdatedAt();
        assertEquals("exactly 2×interval is the spec's freshness boundary",
                UsageStatus.OK, view.displayStatus(interval, storedAt + 2 * interval));
        assertEquals("one millisecond past it the data must read stale",
                UsageStatus.STALE, view.displayStatus(interval, storedAt + 2 * interval + 1L));
    }

    @Test
    public void aFailedNewestAttemptReadsAsTheFailureEvenWhenOld() {
        provider.setResult(ok(System.currentTimeMillis()));
        refreshManager.refresh(account);
        provider.setFailure(UsageError.INVALID_CREDENTIAL);
        refreshManager.refresh(account);

        // A failure is a present-tense fact about the service, not data that
        // quietly went stale: hours later the row must still say AUTH_REQUIRED.
        long interval = 60_000L;
        long farFuture = viewUpdatedAtAfterFailure() + 10 * interval;
        assertEquals(UsageStatus.AUTH_REQUIRED,
                refreshManager.view(account.getId()).displayStatus(interval, farFuture));
    }

    @Test
    public void anOldSuccessWithNoAttemptRowReadsStale() {
        // The shape the UI can get when the newest-attempt query returns null
        // while a success exists: only lastSuccess is populated.
        UsageResult old = ok(System.currentTimeMillis() - 10 * 60_000L);
        AccountRefreshManager.AccountView view = new AccountRefreshManager.AccountView(old, null);
        assertEquals(UsageStatus.STALE, view.displayStatus(60_000L, System.currentTimeMillis()));
    }

    private long viewUpdatedAtAfterFailure() {
        AccountRefreshManager.AccountView view = refreshManager.view(account.getId());
        assertNotNull(view.lastAttempt);
        return view.lastAttempt.getUpdatedAt();
    }

    // ---------------------------------------------------------------- R2

    @Test
    public void sqliteCredentialStoreImplementsDegradedAware() {
        // This is the whole defect: isCredentialDegraded() tests
        // `credentials instanceof DegradedAware`, so a store that has an
        // isDegraded() method but does not implement the interface makes the
        // warning unreachable on a real device.
        assertTrue(
                "SqliteCredentialStore must implement AccountManager.DegradedAware, "
                        + "otherwise the degraded-protection warning can never show",
                AccountManager.DegradedAware.class.isAssignableFrom(SqliteCredentialStore.class));
    }

    @Test
    public void degradedAwareStoreWiringIsVisibleToAccountManager() throws AuthException {
        AccountManager.DegradedAware degraded = new StubDegradedStore();
        AccountManager manager = new AccountManager(accounts, (CredentialStore) degraded);
        accountManager.replaceCredential(account.getId(), "sk-some-key");
        Account withCredential = accountManager.find(account.getId());

        assertTrue(manager.isCredentialDegraded(withCredential));
    }

    @Test
    public void nonDegradedStoreReportsFalseRatherThanCrashing() {
        assertFalse(accountManager.isCredentialDegraded(account));
    }

    // ---------------------------------------------------------------- helpers

    private static UsageResult ok(long at) {
        return UsageResult.builder()
                .accountId("")
                .status(UsageStatus.OK)
                .balance(new Balance(GOOD_BALANCE, "CNY", "24.94"))
                .updatedAt(at)
                .source(UsageResult.Source.DIRECT_API)
                .build();
    }

    /** A provider whose next answer is switched per test. */
    private static final class SwitchableProvider implements UsageProvider {

        private final String id;
        private UsageResult result;
        private UsageError error;

        SwitchableProvider(String id) {
            this.id = id;
        }

        void reset() {
            result = null;
            error = null;
        }

        void setResult(UsageResult next) {
            result = next;
            error = null;
        }

        void setFailure(UsageError next) {
            error = next;
            result = null;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getName() {
            return "View Status Test Provider";
        }

        @Override
        public List<AuthType> getSupportedAuthTypes() {
            return Arrays.asList(AuthType.API_KEY);
        }

        @Override
        public ProviderCapabilities getCapabilities() {
            return ProviderCapabilities.builder().supports(AuthType.API_KEY).reportsBalance(true).build();
        }

        @Override
        public UsageResult fetchUsage(Account account, AuthContext authContext)
                throws UsageException {
            if (error != null) {
                throw new UsageException(error, "stubbed failure");
            }
            if (result == null) {
                throw new UsageException(UsageError.UNKNOWN, "no stubbed result");
            }
            return result;
        }
    }

    /** Reports every credential as degraded, to prove the manager reads it. */
    private static final class StubDegradedStore implements CredentialStore,
            AccountManager.DegradedAware {

        @Override
        public String create(AuthType type, String payload) throws AuthException {
            return "cred_degraded";
        }

        @Override
        public void update(String credentialId, String payload) throws AuthException {
        }

        @Override
        public AuthContext open(String credentialId, AuthType type) throws AuthException {
            return new ApiKeyAuthAdapter().adapt("sk-stub");
        }

        @Override
        public void delete(String credentialId) {
        }

        @Override
        public boolean isUsable(String credentialId) {
            return true;
        }

        @Override
        public boolean isDegraded(String credentialId) {
            return true;
        }
    }

    private static final class InMemoryAccountRepository implements AccountRepository {

        private final Map<String, Account> byId = new LinkedHashMap<>();
        private int sequence;

        @Override
        public List<Account> findAll() {
            List<Account> all = new ArrayList<>(byId.values());
            all.sort((a, b) -> Integer.compare(a.getSortOrder(), b.getSortOrder()));
            return all;
        }

        @Override
        public Account findById(String id) {
            return id == null ? null : byId.get(id);
        }

        @Override
        public List<Account> findEnabled() {
            List<Account> enabled = new ArrayList<>();
            for (Account account : findAll()) {
                if (account.isEnabled()) {
                    enabled.add(account);
                }
            }
            return enabled;
        }

        @Override
        public void save(Account account) {
            byId.put(account.getId(), account);
        }

        @Override
        public void delete(String id) {
            if (id != null) {
                byId.remove(id);
            }
        }

        @Override
        public void reorder(List<String> orderedIds) {
            if (orderedIds == null) {
                return;
            }
            int order = 0;
            for (String id : orderedIds) {
                Account account = byId.get(id);
                if (account != null) {
                    byId.put(id, account.toBuilder().sortOrder(order++).build());
                }
            }
        }

        @Override
        public int count() {
            return byId.size();
        }
    }

    private static final class InMemoryCredentialStore implements CredentialStore {

        private final Map<String, String> payloads = new LinkedHashMap<>();
        private int sequence;

        @Override
        public String create(AuthType type, String payload) throws AuthException {
            String id = "cred_test" + (++sequence);
            payloads.put(id, payload);
            return id;
        }

        @Override
        public void update(String credentialId, String payload) throws AuthException {
            payloads.put(credentialId, payload);
        }

        @Override
        public AuthContext open(String credentialId, AuthType type) throws AuthException {
            if (credentialId == null || !payloads.containsKey(credentialId)) {
                throw new AuthException(UsageError.INVALID_CREDENTIAL, "凭据不存在");
            }
            return new ApiKeyAuthAdapter().adapt(payloads.get(credentialId));
        }

        @Override
        public void delete(String credentialId) {
            if (credentialId != null) {
                payloads.remove(credentialId);
            }
        }

        @Override
        public boolean isUsable(String credentialId) {
            return payloads.containsKey(credentialId);
        }
    }

    /** Mirrors the SQL: rows ordered by timestamp desc then id desc. */
    private static final class InMemoryUsageRepository implements UsageRepository {

        private final List<Row> rows = new ArrayList<>();
        private int sequence;
        private long clock;

        void setClock(long at) {
            clock = at;
        }

        @Override
        public void save(String accountId, UsageResult result, boolean success) {
            if (accountId == null || accountId.isEmpty() || result == null) {
                return;
            }
            long at = result.getUpdatedAt() > 0L ? result.getUpdatedAt() : clock++;
            rows.add(new Row(++sequence, accountId, at,
                    UsageSnapshotCodec.encode(result), success));
        }

        @Override
        public UsageResult latest(String accountId) {
            Row newest = newest(accountId, true);
            return newest == null ? null : UsageSnapshotCodec.decode(newest.usageData);
        }

        @Override
        public UsageResult latestAttempt(String accountId) {
            Row newest = newest(accountId, false);
            return newest == null ? null : UsageSnapshotCodec.decode(newest.usageData);
        }

        private Row newest(String accountId, boolean successOnly) {
            if (accountId == null || accountId.isEmpty()) {
                return null;
            }
            Row best = null;
            for (Row row : rows) {
                if (!accountId.equals(row.accountId)) {
                    continue;
                }
                if (successOnly && !row.success) {
                    continue;
                }
                if (best == null || row.timestamp > best.timestamp
                        || (row.timestamp == best.timestamp && row.id > best.id)) {
                    best = row;
                }
            }
            return best;
        }

        @Override
        public List<UsageSnapshot> history(String accountId, long fromInclusive, long toExclusive,
                                       int limit) {
            return new ArrayList<>();
        }

    /** Mirrors SqliteUsageRepository.prune: the policy decides, this deletes exactly that list. */
        @Override
        public int prune(SnapshotRetention retention, long nowMs) {
            List<UsageSnapshot> light = new ArrayList<>();
            for (Row row : rows) {
                light.add(new UsageSnapshot(row.id, row.accountId, row.timestamp, "", "", row.success));
            }
            List<Long> doomed = retention.expired(light, nowMs);
            rows.removeIf(row -> doomed.contains(row.id));
            return doomed.size();
        }

        @Override
        public void deleteForAccount(String accountId) {
            rows.removeIf(row -> accountId.equals(row.accountId));
        }

        @Override
        public java.math.BigDecimal recordDailyUsage(String accountId, String rawBalance) {
            return java.math.BigDecimal.ZERO;
        }

        @Override
        public java.math.BigDecimal dailyUsage(String accountId) {
            return java.math.BigDecimal.ZERO;
        }

        private static final class Row {
            final long id;
            final String accountId;
            final long timestamp;
            final String usageData;
            final boolean success;

            Row(long id, String accountId, long timestamp, String usageData, boolean success) {
                this.id = id;
                this.accountId = accountId;
                this.timestamp = timestamp;
                this.usageData = usageData;
                this.success = success;
            }
        }
    }
}

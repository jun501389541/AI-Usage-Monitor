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
import com.aiusage.monitor.usage.SnapshotRetention;
import com.aiusage.monitor.usage.UsageRepository;
import com.aiusage.monitor.usage.UsageSnapshot;
import com.aiusage.monitor.usage.UsageSnapshotCodec;

import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A result that arrives after its account was deleted must not be stored.
 *
 * <p>Review finding R3: {@code fetch()} went straight from the provider's answer
 * to {@code recordDailyUsage} and {@code save}, with no check that the account
 * still existed. The delete path runs on the UI thread and the refresh runs on
 * an executor, so "delete account" and "its refresh returns" interleave freely,
 * and the leftover rows are orphaned: snapshots and daily-usage rows whose
 * account_id matches nothing, invisible in the UI but permanent in the file.
 *
 * <p>The guard is two layers. Inside the refresh side, the re-read
 * ({@code isGone}) discards the result outright when the account is already
 * gone. Across the two sides, the check-then-write section and the whole
 * three-step delete hold one shared write monitor, so no delete can land
 * between the check and the writes. A global deletion counter was considered
 * and rejected: it would also cancel an unrelated account's refresh that
 * merely overlapped the deletion, which is a different bug wearing the same
 * costume. The residual gap the monitor cannot cover is a provider that runs
 * its fetch entirely outside both sides' sections while deleting on its own
 * thread -- not a shape this app produces.
 */
public class DeleteRaceDropsOrphanTest {

    private static final String PROVIDER_ID = "test-delete-race";
    private static final long BASE_TIME = 1_700_000_000_000L;

    /**
     * One instance for the class: {@link ProviderRegistry} is a JVM-wide
     * singleton and rejects a duplicate id.
     */
    private static DeletingProvider provider;

    private InMemoryAccountRepository accounts;
    private InMemoryCredentialStore credentials;
    private RecordingUsageRepository usage;
    private AccountManager accountManager;
    private AccountRefreshManager refreshManager;

    @Before
    public void setUp() {
        accounts = new InMemoryAccountRepository();
        credentials = new InMemoryCredentialStore();
        usage = new RecordingUsageRepository();
        accountManager = new AccountManager(accounts, credentials);

        provider = sharedProvider();
        provider.reset();
        refreshManager = new AccountRefreshManager(accountManager, usage, ProviderRegistry.get());
    }

    private static DeletingProvider sharedProvider() {
        if (provider == null) {
            provider = new DeletingProvider(PROVIDER_ID);
            ProviderRegistry.get().register(provider);
        }
        return provider;
    }

    private Account newAccount(String name) throws AuthException {
        Account account = accountManager.createAccount(PROVIDER_ID, name, AuthType.API_KEY);
        // The stored form is the encoded JSON payload, not the raw key:
        // CredentialPayload.extractApiKey parses it, so a bare string is
        // rejected as INVALID_CREDENTIAL before the provider is reached.
        accountManager.replaceCredential(account.getId(),
                CredentialPayload.forApiKey("sk-" + name));
        // replaceCredential stores a toBuilder() copy, so re-read it.
        return accountManager.find(account.getId());
    }

    // ---------------------------------------------------------------- R3

    @Test
    public void aResultArrivingAfterItsAccountWasDeletedIsNotStored() throws AuthException {
        Account doomed = newAccount("Doomed");
        provider.setResult(ok(BASE_TIME));
        // The delete happens inside the provider call, i.e. mid-flight.
        provider.setDuringFetch(() -> accountManager.delete(doomed.getId(),
                usage::deleteForAccount));

        AccountRefreshManager.RefreshOutcome outcome = refreshManager.refresh(doomed);

        assertTrue("the outcome must be marked abandoned, not failed", outcome.isAbandoned());
        assertFalse("and it is not a success", outcome.isSuccess());
        assertNull("no error: the request itself did not fail", outcome.getError());
        assertEquals("no snapshot row may survive for the deleted account",
                0, usage.rowCount(doomed.getId()));
        assertEquals(0, usage.dailyCalls.size());
    }

    @Test
    public void deletingOneAccountDoesNotCancelAnotherAccountsOverlappingRefresh()
            throws AuthException {
        Account survivor = newAccount("Survivor");
        Account doomed = newAccount("Doomed");
        provider.setResult(ok(BASE_TIME));
        // A delete of *another* account happens mid-flight.
        provider.setDuringFetch(() -> accountManager.delete(doomed.getId(),
                usage::deleteForAccount));

        AccountRefreshManager.RefreshOutcome outcome = refreshManager.refresh(survivor);

        assertTrue("the survivor's refresh must still land", outcome.isSuccess());
        assertFalse(outcome.isAbandoned());
        assertEquals(1, usage.rowCount(survivor.getId()));
        assertNotNull(usage.latest(survivor.getId()));
    }

    @Test
    public void aFailedRequestArrivingAfterDeletionIsAbandonedNotRecorded()
            throws AuthException {
        Account doomed = newAccount("Doomed");
        provider.setFailure(UsageError.INVALID_CREDENTIAL);
        provider.setDuringFetch(() -> accountManager.delete(doomed.getId(),
                usage::deleteForAccount));

        AccountRefreshManager.RefreshOutcome outcome = refreshManager.refresh(doomed);

        assertTrue(outcome.isAbandoned());
        assertNull("an abandoned outcome carries no error to show the user",
                outcome.getError());
        assertEquals("no failure row may be written for a deleted account",
                0, usage.rowCount(doomed.getId()));
    }

    @Test
    public void withoutADeletionTheResultIsStoredAsBefore() throws AuthException {
        Account account = newAccount("Healthy");
        provider.setResult(ok(BASE_TIME));

        AccountRefreshManager.RefreshOutcome outcome = refreshManager.refresh(account);

        assertTrue(outcome.isSuccess());
        assertFalse(outcome.isAbandoned());
        assertEquals(1, usage.rowCount(account.getId()));
    }

    @Test
    public void deletingAnAccountRemovesOnlyThatAccountFromLookup() throws AuthException {
        Account alpha = newAccount("Alpha");
        Account beta = newAccount("Beta");

        accountManager.delete(alpha.getId(), usage::deleteForAccount);

        // The whole R3 check rests on this: the deleted account reads as gone
        // and a bystander still reads as present. If deletion removed anything
        // else from lookup, unrelated refreshes would be abandoned too.
        assertNull(accountManager.find(alpha.getId()));
        assertNotNull(accountManager.find(beta.getId()));
    }

    @Test
    public void anAccountDeletedBeforeItsRefreshIsNeverQueried() throws AuthException {
        Account doomed = newAccount("Doomed");
        provider.setResult(ok(BASE_TIME));
        accountManager.delete(doomed.getId(), usage::deleteForAccount);

        // The caller still holds the stale object, which is exactly the shape
        // the UI can produce: a list row captured before the delete landed.
        AccountRefreshManager.RefreshOutcome outcome = refreshManager.refresh(doomed);

        assertTrue(outcome.isAbandoned());
        assertEquals(0, usage.rowCount(doomed.getId()));
    }

    /**
     * R3, failure branch: the account is deleted, its credential row went with
     * it, so openCredential throws INVALID_CREDENTIAL. The catch must consult
     * isGone and report the refresh as abandoned -- never write a failure row
     * for an account that no longer exists.
     */
    @Test
    public void anOpenCredentialFailureAfterDeletionIsAbandonedNotRecorded()
            throws AuthException {
        Account doomed = newAccount("Doomed");
        provider.setResult(ok(BASE_TIME));
        accountManager.delete(doomed.getId(), usage::deleteForAccount);

        // The caller still holds the stale object: the UI's exact shape.
        AccountRefreshManager.RefreshOutcome outcome = refreshManager.refresh(doomed);

        assertTrue(outcome.isAbandoned());
        assertFalse(outcome.isSuccess());
        assertNull("an abandoned outcome carries no error to show the user",
                outcome.getError());
        assertEquals("no failure row may be written for a deleted account",
                0, usage.rowCount(doomed.getId()));
        assertNull(usage.latestAttempt(doomed.getId()));
    }

    /**
     * R3, failure branch: an account that is alive but whose credential row
     * vanished still reports a real failure -- isGone is a liveness check, not
     * a blanket suppressor. This is the counterpart to the test above.
     */
    @Test
    public void anOpenCredentialFailureForALivingAccountIsStillRecorded()
            throws AuthException {
        Account alive = newAccount("Alive");
        // The account row stays, its credential row is gone: openCredential
        // throws INVALID_CREDENTIAL and the catch must record the failure.
        credentials.delete(alive.getCredentialId());

        AccountRefreshManager.RefreshOutcome outcome =
                refreshManager.refresh(accountManager.find(alive.getId()));

        assertFalse(outcome.isAbandoned());
        assertFalse(outcome.isSuccess());
        assertNotNull(outcome.getError());
        assertEquals("a living account's failure must still land",
                1, usage.rowCount(alive.getId()));
        assertNotNull(usage.latestAttempt(alive.getId()));
        assertNull("and it is a failure row, not a success row",
                usage.latest(alive.getId()));
    }

    /**
     * R3, failure branch: an unknown provider id reaches the registry.require
     * catch. For a living account the failure must still be recorded inside
     * the monitor; the abandoned variant of this branch is unreachable
     * sequentially (the delete would first remove the credential, routing the
     * refresh into the openCredential catch) and its serialization is proven
     * by aDeleteWaitingOnTheWriteMonitorCannotSlipIntoTheWriteSection.
     */
    @Test
    public void anUnknownProviderFailureForALivingAccountIsStillRecorded()
            throws AuthException {
        Account alive = accountManager.createAccount("no-such-provider", "Ghost",
                AuthType.API_KEY);
        accountManager.replaceCredential(alive.getId(),
                CredentialPayload.forApiKey("sk-Ghost"));

        AccountRefreshManager.RefreshOutcome outcome =
                refreshManager.refresh(accountManager.find(alive.getId()));

        assertFalse(outcome.isAbandoned());
        assertFalse(outcome.isSuccess());
        assertNotNull(outcome.getError());
        assertEquals("the registry failure must land for a living account",
                1, usage.rowCount(alive.getId()));
        assertNull(usage.latest(alive.getId()));
    }

    @Test
    public void aDeleteWaitingOnTheWriteMonitorCannotSlipIntoTheWriteSection()
            throws Exception {
        Account doomed = newAccount("Doomed");
        provider.setResult(ok(BASE_TIME));

        // Hold the monitor the way the refresh's write section does, delete
        // concurrently, then release: the delete must not complete while the
        // section is held, proving the two sides are serialized and the check
        // inside the section cannot be invalidated mid-flight.
        Object monitor = accountManager.writeMonitor();
        CountDownLatch deleteStarted = new CountDownLatch(1);
        CountDownLatch deleteDone = new CountDownLatch(1);
        Thread deleter = new Thread(() -> {
            deleteStarted.countDown();
            accountManager.delete(doomed.getId(), usage::deleteForAccount);
            deleteDone.countDown();
        });
        synchronized (monitor) {
            deleter.start();
            assertTrue("the delete thread must reach its blocking call",
                    deleteStarted.await(2, TimeUnit.SECONDS));
            Thread.sleep(100L); // let the deleter park on the monitor
            assertEquals("delete must still be blocked while the section is held",
                    1L, deleteDone.getCount());

            // Inside the held section the account is still present -- the
            // delete has not been able to run.
            assertNotNull(accountManager.find(doomed.getId()));
        }
        assertTrue("delete must complete after the section releases",
                deleteDone.await(2, TimeUnit.SECONDS));
        assertNull(accountManager.find(doomed.getId()));
        assertEquals(0, usage.rowCount(doomed.getId()));
    }

    private static UsageResult ok(long at) {
        return UsageResult.builder()
                .status(UsageStatus.OK)
                .balance(new Balance(24.94d, "CNY", "¥24.94"))
                .updatedAt(at)
                .build();
    }

    // ------------------------------------------------------------- stubs

    /**
     * A provider that lets the test run an arbitrary action from inside
     * {@code fetchUsage}, which is what "the delete landed mid-flight" means.
     */
    private static final class DeletingProvider implements UsageProvider {

        private final String id;
        private UsageResult result;
        private UsageError error;
        private Runnable duringFetch;

        DeletingProvider(String id) {
            this.id = id;
        }

        void reset() {
            result = null;
            error = null;
            duringFetch = null;
        }

        void setResult(UsageResult next) {
            result = next;
            error = null;
        }

        void setFailure(UsageError next) {
            error = next;
            result = null;
        }

        void setDuringFetch(Runnable action) {
            duringFetch = action;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getName() {
            return "Delete Race Test Provider";
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
            if (duringFetch != null) {
                duringFetch.run();
            }
            if (error != null) {
                throw new UsageException(error, "stubbed failure");
            }
            if (result == null) {
                throw new UsageException(UsageError.UNKNOWN, "no stubbed result");
            }
            return result;
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
            if (byId.put(account.getId(), account) == null) {
                account.toBuilder().sortOrder(sequence++).build();
            }
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
            String id = "cred_race" + (++sequence);
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

    /** Records every write so "nothing was stored" is assertable. */
    private static final class RecordingUsageRepository implements UsageRepository {

        private final List<Row> rows = new ArrayList<>();
        private final List<String> dailyCalls = new ArrayList<>();
        private int sequence;
        private long clock;

        void setClock(long at) {
            clock = at;
        }

        int rowCount(String accountId) {
            int count = 0;
            for (Row row : rows) {
                if (accountId.equals(row.accountId)) {
                    count++;
                }
            }
            return count;
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
        public BigDecimal recordDailyUsage(String accountId, String rawBalance) {
            dailyCalls.add(accountId);
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal dailyUsage(String accountId) {
            return BigDecimal.ZERO;
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

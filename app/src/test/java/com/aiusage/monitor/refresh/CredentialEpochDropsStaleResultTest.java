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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A result produced by a superseded credential must not be stored. R3's last
 * open branch (re-review, {@code docs/REVIEW-AND-NEXT-STEPS.md}).
 *
 * <p>The deletion guard already re-reads the account before writing. A key
 * change is the same shape of race with the opposite answer to that check: the
 * account is <em>still there</em>, so an existence test waves the old key's
 * response through, and the list then shows the balance of a key the user has
 * just replaced. Clearing a credential and unticking "remember" open the same
 * window.
 *
 * <p>The guard is a per-account generation that {@code AccountManager}
 * advances on every credential change. The refresh captures it before opening
 * the secret and compares it, holding the same write monitor as the delete,
 * before committing either a success or a failure. Capturing before the open is
 * load-bearing and is pinned separately: capturing after would let a save that
 * lands during the open look like it happened before the request.
 *
 * <p>Each test here also has a control on the other side of the same branch, so
 * the check cannot be satisfied by a blanket suppressor — a mutation that makes
 * the comparison always report "current" turns the drop tests red, and one that
 * makes it always report "stale" turns the controls red.
 */
public class CredentialEpochDropsStaleResultTest {

    private static final String PROVIDER_ID = "test-credential-epoch";
    private static final long BASE_TIME = 1_700_000_000_000L;

    /** One instance for the class: the registry is a JVM-wide singleton. */
    private static EpochProvider provider;

    private InMemoryCredentialStore credentials;
    private RecordingUsageRepository usage;
    private AccountManager accountManager;
    private AccountRefreshManager refreshManager;

    @Before
    public void setUp() {
        credentials = new InMemoryCredentialStore();
        usage = new RecordingUsageRepository();
        accountManager = new AccountManager(new InMemoryAccountRepository(), credentials);

        provider = sharedProvider();
        provider.reset();
        refreshManager = new AccountRefreshManager(accountManager, usage, ProviderRegistry.get());
    }

    private static EpochProvider sharedProvider() {
        if (provider == null) {
            provider = new EpochProvider(PROVIDER_ID);
            ProviderRegistry.get().register(provider);
        }
        return provider;
    }

    private Account newAccount(String name) throws AuthException {
        Account account = accountManager.createAccount(PROVIDER_ID, name, AuthType.API_KEY);
        accountManager.replaceCredential(account.getId(),
                CredentialPayload.forApiKey("sk-" + name));
        // replaceCredential stores a toBuilder() copy, so re-read it.
        return accountManager.find(account.getId());
    }

    private static AuthContext typedContext(String key) throws AuthException {
        return new ApiKeyAuthAdapter().adapt(CredentialPayload.forApiKey(key));
    }

    /** Runnable-shaped form of a key change, for the mid-flight hooks. */
    private void replaceQuietly(String accountId, String key) {
        try {
            accountManager.replaceCredential(accountId, CredentialPayload.forApiKey(key));
        } catch (AuthException exception) {
            throw new IllegalStateException(exception);
        }
    }

    // ------------------------------------------------ the replaced key

    @Test
    public void aBalanceArrivingAfterTheKeyWasReplacedIsNotStored() throws AuthException {
        Account account = newAccount("Original");
        provider.setResult(ok(BASE_TIME));
        provider.setDuringFetch(() -> replaceQuietly(account.getId(), "sk-Replaced"));

        AccountRefreshManager.RefreshOutcome outcome = refreshManager.refresh(account);

        assertTrue("the old key's answer must be discarded, not committed",
                outcome.isAbandoned());
        assertFalse(outcome.isSuccess());
        assertNull(outcome.getError());
        assertEquals("no snapshot row for a result the current key did not produce",
                0, usage.rowCount(account.getId()));
        assertEquals("and its balance must not move today's accumulator either",
                0, usage.dailyCalls.size());
    }

    @Test
    public void aFailureArrivingAfterTheKeyWasReplacedIsNotRecorded() throws AuthException {
        Account account = newAccount("Original");
        provider.setFailure(UsageError.NETWORK_ERROR);
        provider.setDuringFetch(() -> replaceQuietly(account.getId(), "sk-Replaced"));

        AccountRefreshManager.RefreshOutcome outcome = refreshManager.refresh(account);

        assertTrue(outcome.isAbandoned());
        assertNull("the old key's failure is not the new key's error to show",
                outcome.getError());
        assertEquals(0, usage.rowCount(account.getId()));
        assertNull(usage.latestAttempt(account.getId()));
    }

    @Test
    public void aKeyClearedMidFlightDropsTheResultAlreadyInHand() throws AuthException {
        Account account = newAccount("Original");
        provider.setResult(ok(BASE_TIME));
        // The request already carries the key, so clearing does not stop it; the
        // commit-time comparison is what keeps its answer out of the history.
        provider.setDuringFetch(() -> accountManager.clearCredential(account.getId()));

        assertTrue(refreshManager.refresh(account).isAbandoned());
        assertEquals(0, usage.rowCount(account.getId()));
    }

    @Test
    public void aSaveLandingWhileTheSecretIsBeingOpenedStillCountsAsNewer()
            throws AuthException {
        Account account = newAccount("Original");
        provider.setResult(ok(BASE_TIME));
        // Runs inside CredentialStore.open, i.e. after the generation was
        // captured and before the request was sent.
        credentials.setDuringOpen(() -> replaceQuietly(account.getId(), "sk-Replaced"));

        assertTrue("capturing the generation after the open would pass this test and "
                        + "still store the superseded key's balance",
                refreshManager.refresh(account).isAbandoned());
        assertEquals(0, usage.rowCount(account.getId()));
    }

    // ------------------------------------------------------ controls

    @Test
    public void withoutACredentialChangeTheResultIsStoredAsBefore() throws AuthException {
        Account account = newAccount("Healthy");
        provider.setResult(ok(BASE_TIME));

        AccountRefreshManager.RefreshOutcome outcome = refreshManager.refresh(account);

        assertTrue(outcome.isSuccess());
        assertFalse(outcome.isAbandoned());
        assertEquals(1, usage.rowCount(account.getId()));
        assertNotNull(usage.latest(account.getId()));
    }

    @Test
    public void changingAnotherAccountsKeyDoesNotCancelThisRefresh() throws AuthException {
        Account survivor = newAccount("Survivor");
        Account bystander = newAccount("Bystander");
        provider.setResult(ok(BASE_TIME));
        provider.setDuringFetch(() -> replaceQuietly(bystander.getId(), "sk-Bystander-2"));

        AccountRefreshManager.RefreshOutcome outcome = refreshManager.refresh(survivor);

        assertTrue("the generation is per account, not global", outcome.isSuccess());
        assertEquals(1, usage.rowCount(survivor.getId()));
    }

    @Test
    public void aKeySavedBeforeTheRefreshStartsIsNotTreatedAsStale() throws AuthException {
        Account account = accountManager.createAccount(PROVIDER_ID, "Typed", AuthType.API_KEY);
        accountManager.replaceCredential(account.getId(),
                CredentialPayload.forApiKey("sk-Typed"));
        provider.setResult(ok(BASE_TIME));

        // The detail screen's "remember this key" path saves and then refreshes
        // on the same account. That save must not invalidate the very request it
        // is followed by.
        AccountRefreshManager.RefreshOutcome outcome =
                refreshManager.refresh(accountManager.find(account.getId()));

        assertTrue(outcome.isSuccess());
        assertEquals(1, usage.rowCount(account.getId()));
    }

    // -------------------------------------- the caller-supplied context

    @Test
    public void aDeletedAccountIsNeverQueriedWithItsCallersInMemoryKey()
            throws AuthException {
        Account doomed = newAccount("Doomed");
        AuthContext context = typedContext("sk-Doomed");
        provider.setResult(ok(BASE_TIME));
        accountManager.delete(doomed.getId(), usage::deleteForAccount);

        AccountRefreshManager.RefreshOutcome outcome =
                refreshManager.refreshWithAuthContext(doomed, context);

        assertTrue(outcome.isAbandoned());
        assertEquals("the pre-flight check must save the round trip, not merely the write",
                0, provider.fetchCount);
        assertEquals(0, usage.rowCount(doomed.getId()));
    }

    @Test
    public void anInFlightCallerSuppliedRequestDroppedAfterAKeyChangeIsNotStored()
            throws AuthException {
        Account account = newAccount("Typed");
        AuthContext context = typedContext("sk-Typed");
        provider.setResult(ok(BASE_TIME));
        provider.setDuringFetch(() -> replaceQuietly(account.getId(), "sk-Saved-Later"));

        assertTrue("the commit-time comparison still applies when the caller brought "
                        + "the credential itself",
                refreshManager.refreshWithAuthContext(account, context).isAbandoned());
        assertEquals(0, usage.rowCount(account.getId()));
    }

    @Test
    public void aLiveAccountIsStillQueriedWithItsCallersInMemoryKey() throws AuthException {
        Account account = newAccount("Typed");
        AuthContext context = typedContext("sk-Typed");
        provider.setResult(ok(BASE_TIME));

        AccountRefreshManager.RefreshOutcome outcome =
                refreshManager.refreshWithAuthContext(account, context);

        assertFalse(outcome.isAbandoned());
        assertTrue("the pre-flight check is a liveness check, not a suppressor",
                outcome.isSuccess());
        assertEquals(1, provider.fetchCount);
        assertEquals(1, usage.rowCount(account.getId()));
    }

    @Test
    public void theGenerationOnlyMovesWhenTheSecretActuallyChanges() throws AuthException {
        Account account = newAccount("Renamed");
        long before = accountManager.credentialGeneration(account.getId());

        accountManager.rename(account.getId(), "DeepSeek 改名");
        accountManager.setEnabled(account.getId(), false);
        accountManager.setEnabled(account.getId(), true);

        assertEquals("renaming and enabling are not credential changes",
                before, accountManager.credentialGeneration(account.getId()));

        accountManager.replaceCredential(account.getId(),
                CredentialPayload.forApiKey("sk-Renamed-2"));
        assertEquals(before + 1, accountManager.credentialGeneration(account.getId()));
    }

    /**
     * The lock is what makes the generation check mean anything.
     *
     * <p>A refresh holds the write monitor across "is this still the credential I
     * asked for?" and the writes that follow. If a save could advance the
     * generation while that section was held, the check would pass and the old
     * key's balance would still commit under the new key -- the exact hole the
     * generation was added to close. So the mutators hold the same monitor, and
     * this pins it: the save has to wait.
     */
    @Test
    public void aCredentialSaveCannotAdvanceInsideTheWriteSection() throws Exception {
        Account account = newAccount("Original");
        long captured = accountManager.credentialGeneration(account.getId());

        CountDownLatch saveStarted = new CountDownLatch(1);
        CountDownLatch saveDone = new CountDownLatch(1);
        Thread saver = new Thread(() -> {
            saveStarted.countDown();
            replaceQuietly(account.getId(), "sk-Later");
            saveDone.countDown();
        });

        Object monitor = accountManager.writeMonitor();
        synchronized (monitor) {
            saver.start();
            assertTrue("the save thread must reach its blocking call",
                    saveStarted.await(2, TimeUnit.SECONDS));
            Thread.sleep(100L); // let it park on the monitor
            assertEquals("the save must block while the write section is held",
                    1L, saveDone.getCount());
            assertEquals("and the generation the refresh captured must still be current",
                    captured, accountManager.credentialGeneration(account.getId()));
        }

        assertTrue("the save completes once the section releases",
                saveDone.await(2, TimeUnit.SECONDS));
        assertEquals(captured + 1, accountManager.credentialGeneration(account.getId()));
    }

    // ------------------------------------------- the batch's stale account object

    /**
     * {@code refreshAll()} reads the enabled list once and then works through it,
     * so every object in it is a snapshot from the moment the batch started. A key
     * saved for a later account while the first one is in flight leaves that list
     * carrying an account whose {@code credentialId} is empty or already deleted -
     * and opening <em>that</em> while stamping the <em>current</em> generation
     * filed the superseded secret's failure as the new key's, so the list said
     * "电脑端授权已失效" for a key that had never been tried
     * (docs/PHASE-0-7-REVIEW.md §2.1).
     */
    @Test
    public void aKeySavedWhileTheFirstAccountIsInFlightIsTheOneTheBatchUses()
            throws AuthException {
        Account first = newAccount("First");
        // The second account exists but has no stored key yet: the list the batch
        // takes carries an empty credentialId for it.
        Account second = accountManager.createAccount(PROVIDER_ID, "Second", AuthType.API_KEY);
        provider.setResult(ok(BASE_TIME));
        // One-shot: the save happens while the *first* account is in flight. Left
        // to run on every fetch it would also advance the second account's
        // generation during its own request, and the epoch guard would - correctly
        // - drop that result, which is a different test.
        final AtomicBoolean saved = new AtomicBoolean();
        provider.setDuringFetch(() -> {
            if (saved.compareAndSet(false, true)) {
                replaceQuietly(second.getId(), "sk-Second-Saved");
            }
        });

        List<AccountRefreshManager.RefreshOutcome> outcomes = refreshManager.refreshAll();

        assertEquals(2, outcomes.size());
        assertEquals("the request for the second account must carry the key that was "
                        + "saved during the batch, not the empty one its list held",
                "sk-Second-Saved", provider.keysRequested.get(second.getId()));
        AccountRefreshManager.RefreshOutcome forSecond = outcomes.get(1);
        assertTrue("and that request succeeded rather than filing a credential error: "
                        + forSecond.getError(),
                forSecond.isSuccess());
        assertEquals(1, usage.rowCount(second.getId()));
        assertNotNull(usage.latest(second.getId()));
    }

    /**
     * The control on the other side of the same re-read: an account that still has
     * no key when its own turn arrives must keep failing the way it did. Without
     * this, "re-read the account" could be satisfied by never reporting a missing
     * credential at all.
     */
    @Test
    public void anAccountStillWithoutAKeyFailsTheWayItAlwaysDid() throws AuthException {
        Account first = newAccount("First");
        Account keyless = accountManager.createAccount(PROVIDER_ID, "Keyless", AuthType.API_KEY);
        provider.setResult(ok(BASE_TIME));

        List<AccountRefreshManager.RefreshOutcome> outcomes = refreshManager.refreshAll();

        assertEquals(2, outcomes.size());
        AccountRefreshManager.RefreshOutcome forKeyless = outcomes.get(1);
        assertFalse(forKeyless.isSuccess());
        assertFalse("a missing credential is a real answer, not an abandoned write",
                forKeyless.isAbandoned());
        assertEquals(UsageError.INVALID_CREDENTIAL, forKeyless.getError());
        assertNull("and nothing was queried with a key that does not exist",
                provider.keysRequested.get(keyless.getId()));
        assertEquals(first.getId(), provider.keysRequested.keySet().iterator().next());
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
     * A provider that can run an arbitrary action from inside
     * {@code fetchUsage} — which is what "the key changed mid-flight" means —
     * and counts calls, so "never queried" is assertable.
     */
    private static final class EpochProvider implements UsageProvider {

        private final String id;
        private UsageResult result;
        private UsageError error;
        private Runnable duringFetch;
        private int fetchCount;
        /** Which secret each account was actually queried with, in call order. */
        private final Map<String, String> keysRequested = new LinkedHashMap<>();

        EpochProvider(String id) {
            this.id = id;
        }

        void reset() {
            result = null;
            error = null;
            duringFetch = null;
            fetchCount = 0;
            keysRequested.clear();
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
            return "Credential Epoch Test Provider";
        }

        @Override
        public List<AuthType> getSupportedAuthTypes() {
            return Arrays.asList(AuthType.API_KEY);
        }

        @Override
        public ProviderCapabilities getCapabilities() {
            return ProviderCapabilities.builder()
                    .supports(AuthType.API_KEY)
                    .reportsBalance(true)
                    .build();
        }

        @Override
        public UsageResult fetchUsage(Account account, AuthContext authContext)
                throws UsageException {
            fetchCount++;
            if (authContext != null && authContext.get(AuthContext.KEY_API_KEY) != null) {
                keysRequested.put(account.getId(), authContext.get(AuthContext.KEY_API_KEY));
            }
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

    /** Credential store with a hook that runs while a secret is being opened. */
    private static final class InMemoryCredentialStore implements CredentialStore {

        private final Map<String, String> payloads = new LinkedHashMap<>();
        private Runnable duringOpen;
        private int sequence;

        void setDuringOpen(Runnable action) {
            duringOpen = action;
        }

        @Override
        public String create(AuthType type, String payload) throws AuthException {
            String id = "cred_epoch" + (++sequence);
            payloads.put(id, payload);
            return id;
        }

        @Override
        public void update(String credentialId, String payload) throws AuthException {
            payloads.put(credentialId, payload);
        }

        @Override
        public AuthContext open(String credentialId, AuthType type) throws AuthException {
            if (duringOpen != null) {
                duringOpen.run();
            }
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
